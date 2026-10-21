package cn.xuyinyin.magic.workflow.execution

import cn.xuyinyin.magic.workflow.snapshot._
import cn.xuyinyin.magic.workflow.checkpoint._
import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import cn.xuyinyin.magic.workflow.spi._
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.stream.{Materializer, SystemMaterializer}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import com.typesafe.config.ConfigFactory
import spray.json._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import java.util.concurrent.atomic.AtomicInteger

class SnapshotSplitExecutorSpec extends AnyWordSpec with Matchers {
  "split release accounting" should {
    "drain an admitted target commit before reacting to an upstream failure" in {
      val kit = ActorTestKit("split-target-drain", ConfigFactory.parseString("pekko.actor.provider=local").withFallback(ConfigFactory.load("application-test")))
      implicit val system: org.apache.pekko.actor.typed.ActorSystem[_] = kit.system
      implicit val ec: ExecutionContext = system.executionContext
      implicit val mat: Materializer = SystemMaterializer(system).materializer
      val entered = Promise[Done](); val target = Promise[BatchCommitResult](); val failed = Promise[Done]()
      val closed = Promise[Done](); val blocked = Promise[Done](); val journaled = Promise[Done]()
      val lock = new Object; var saved = Option.empty[SnapshotSplitState]
      var page: SourceBatch = null
      val context = SnapshotSplitRunContext(() => lock.synchronized(Future.successful(saved)), update => lock.synchronized {
        SnapshotSplitState.advance(saved, update) match {
          case Right(next) =>
            saved = Some(next)
            update match {
              case _: CommitSnapshotPage => journaled.trySuccess(Done)
              case _: BlockSnapshotSplit => blocked.trySuccess(Done)
              case _ => ()
            }
            Future.successful(next)
          case Left(error) => Future.failed(new IllegalStateException(error))
        }
      })
      val source = new NodeSource with CheckpointedNodeSource with SnapshotSplitSource {
        def nodeType = "test-split"
        def createSource(n: Node, l: String => Unit): Source[String, NotUsed] = Source.empty
        def createBatches(n: Node, e: String, b: SnapshotBoundary, r: Option[BatchCheckpoint], l: String => Unit)(implicit c: ExecutionContext): Source[SourceBatch, NotUsed] = Source.empty
        override def discoverBoundary(n: Node, r: Option[BatchCheckpoint], l: String => Unit)(implicit e: ExecutionContext) = Future.successful(SnapshotBoundary(n.id, "old", Some("1")))
        def discoverSplit(n: Node, p: SnapshotSplitPlanState, lower: Option[String], index: Long)(implicit e: ExecutionContext) =
          Future.successful(Some(SnapshotSplit(s"${p.id}:$index", index, lower, "1")))
        def openSplit(n: Node, e: String, p: SnapshotSplitPlanState, s: SnapshotSplit, c: Option[BatchCheckpoint], l: String => Unit, closeEc: ExecutionContext)(implicit ex: ExecutionContext) = {
          page = SourceBatch(n.id, s.id, 0L, BatchId.sha256(e, n.id, s.id, 0L), SourceCursor(SnapshotSplitState.CursorKind, "1", "1"), Vector("{\"id\":\"1\"}"))
          val reads = new AtomicInteger()
          BatchSourceSession.resource[Done](() => Future.successful(Done), _ => {
            if (reads.getAndIncrement() == 0) Future.successful(Some(page))
            else entered.future.flatMap { _ => failed.trySuccess(Done); Future.failed[Option[SourceBatch]](new IllegalStateException("reader failed while target pending")) }(ex)
          }, _ => { closed.trySuccess(Done); Future.successful(Done) }, managed = false, drainReads = true)
        }
      }
      val sink = new NodeSink with CheckpointedNodeSink {
        def nodeType = "test-target"
        def createSink(n: Node, l: String => Unit)(implicit e: ExecutionContext): Sink[String, Future[Done]] = Sink.ignore
        def validateReady(n: Node, l: String => Unit)(implicit e: ExecutionContext) = Future.successful(Done)
        def commitBatch(n: Node, w: String, e: String, b: SourceBatch, rows: Vector[String], l: String => Unit)(implicit c: ExecutionContext) = {
          entered.trySuccess(Done); target.future
        }
      }
      val n = Node("s", "source", "test-split", "s", Position(0, 0), JsObject("splitRows" -> JsNumber(1), "chunkSize" -> JsNumber(1)))
      try {
        val running = new SnapshotSplitExecutor(source, sink, n, n.copy(id = "target"), "w", "e", 1, context,
          () => Future.successful(Done), _ => (), _ => (), _ => (), ec, ec).run()
        Await.result(failed.future, 5.seconds)
        // Upstream failure is already observable, but the execution must retain the admitted T/J work.
        intercept[java.util.concurrent.TimeoutException](Await.ready(running, 500.millis))
        blocked.isCompleted shouldBe false
        target.success(Committed(BatchCheckpoint(page.sourceNodeId, page.partitionId, page.batchSequence, page.batchId, page.cursor, 1L, 1L)))
        intercept[Exception](Await.result(running, 5.seconds))
        Await.result(journaled.future, 5.seconds)
        Await.result(closed.future, 5.seconds)
        Await.result(blocked.future, 5.seconds)
      } finally { target.tryFailure(new IllegalStateException("test cleanup")); kit.shutdownTestKit() }
    }

    "stop opening new readers after an unconfirmed release while draining an admitted sibling" in {
      val kit = ActorTestKit("split-release", ConfigFactory.parseString("pekko.actor.provider=local").withFallback(ConfigFactory.load("application-test")))
      implicit val system: org.apache.pekko.actor.typed.ActorSystem[_] = kit.system
      implicit val ec: ExecutionContext = kit.system.executionContext
      implicit val mat: Materializer = SystemMaterializer(system).materializer
      val sibling = Promise[Done](); val blocked = Promise[Done](); val opened = new AtomicInteger()
      val lock = new Object; var saved = Option.empty[SnapshotSplitState]
      val context = SnapshotSplitRunContext(() => lock.synchronized(Future.successful(saved)), update => lock.synchronized {
        SnapshotSplitState.advance(saved, update) match {
          case Right(next) => saved = Some(next); update match { case _: BlockSnapshotSplit => blocked.trySuccess(Done); case _ => () }; Future.successful(next)
          case Left(error) => Future.failed(new IllegalStateException(error))
        }
      })
      val source = new NodeSource with CheckpointedNodeSource with SnapshotSplitSource {
        def nodeType = "test-split"
        def createSource(n: Node, l: String => Unit): Source[String, NotUsed] = Source.empty
        override def discoverBoundary(n: Node, r: Option[BatchCheckpoint], l: String => Unit)(implicit e: ExecutionContext) = Future.successful(SnapshotBoundary(n.id, "old", Some("12")))
        def createBatches(n: Node, e: String, b: SnapshotBoundary, r: Option[BatchCheckpoint], l: String => Unit)(implicit c: ExecutionContext): Source[SourceBatch, NotUsed] = Source.empty
        def discoverSplit(n: Node, p: SnapshotSplitPlanState, lower: Option[String], index: Long)(implicit e: ExecutionContext) =
          Future.successful(Some(SnapshotSplit(s"${p.id}:$index", index, lower, ((index + 1) * 2).toString)))
        def openSplit(n: Node, e: String, p: SnapshotSplitPlanState, s: SnapshotSplit, c: Option[BatchCheckpoint], l: String => Unit, closeEc: ExecutionContext)(implicit ex: ExecutionContext) = {
          opened.incrementAndGet()
          if (s.index == 0) BatchSourceSession(Source.failed(new IllegalStateException("reader failure")), () => Future.failed(new IllegalStateException("close unknown")))
          else BatchSourceSession(Source.future(sibling.future).mapConcat(_ => List.empty[SourceBatch]), () => Future.successful(Done))
        }
      }
      val sink = new NodeSink with CheckpointedNodeSink {
        def nodeType = "test-target"
        def createSink(n: Node, l: String => Unit)(implicit e: ExecutionContext): Sink[String, Future[Done]] = Sink.ignore
        def validateReady(n: Node, l: String => Unit)(implicit e: ExecutionContext) = Future.successful(Done)
        def commitBatch(n: Node, w: String, e: String, b: SourceBatch, rows: Vector[String], l: String => Unit)(implicit c: ExecutionContext) = Future.failed(new AssertionError("no rows expected"))
      }
      val n = Node("s", "source", "test-split", "s", Position(0, 0), JsObject("splitRows" -> JsNumber(2), "chunkSize" -> JsNumber(1)))
      try {
        val running = new SnapshotSplitExecutor(source, sink, n, n.copy(id = "target"), "w", "e", 1, context,
          () => Future.successful(Done), _ => (), _ => (), _ => (), ec, ec).run()
        Await.result(blocked.future, 5.seconds)
        sibling.trySuccess(Done)
        intercept[Exception](Await.result(running, 5.seconds))
        opened.get() should be <= 2
      } finally { sibling.trySuccess(Done); kit.shutdownTestKit() }
    }
  }
}
