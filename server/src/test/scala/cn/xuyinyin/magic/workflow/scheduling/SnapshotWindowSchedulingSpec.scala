package cn.xuyinyin.magic.workflow.scheduling

import cn.xuyinyin.magic.workflow.checkpoint._
import cn.xuyinyin.magic.workflow.execution.SnapshotSplitRunContext
import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import cn.xuyinyin.magic.workflow.snapshot._
import cn.xuyinyin.magic.workflow.spi._
import com.typesafe.config.ConfigFactory
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.stream.SystemMaterializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import spray.json._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

class SnapshotWindowSchedulingSpec extends AnyWordSpec with Matchers {
  "scheduled snapshot windows" should {
    "yield after a fully retired window without changing identity or spending recovery attempts" in {
      val kit = ActorTestKit("window-yield", ConfigFactory.parseString("pekko.actor.provider=local").withFallback(ConfigFactory.load("application-test")))
      implicit val system: org.apache.pekko.actor.typed.ActorSystem[_] = kit.system
      implicit val ec: ExecutionContext = system.executionContext
      implicit val mat: org.apache.pekko.stream.Materializer = SystemMaterializer(system).materializer
      val lock = new Object; var saved = Option.empty[SnapshotSplitState]; var retries = 0
      var batches = Set.empty[String]; var opens = 0; var closes = 0
      val context = SnapshotSplitRunContext(() => lock.synchronized(Future.successful(saved)), update => lock.synchronized {
        SnapshotSplitState.advance(saved, update) match {
          case Right(s) => saved = Some(s); if (update.isInstanceOf[RetrySnapshotSplit]) retries += 1; Future.successful(s)
          case Left(e) => Future.failed(new IllegalStateException(e))
        }
      })
      val source = new NodeSource with CheckpointedNodeSource with SnapshotSplitSource {
        def nodeType = "test"
        def createSource(n: Node, l: String => Unit): Source[String, NotUsed] = Source.empty
        def createBatches(n: Node, e: String, b: SnapshotBoundary, r: Option[BatchCheckpoint], l: String => Unit)(implicit c: ExecutionContext): Source[SourceBatch, NotUsed] = Source.empty
        override def discoverBoundary(n: Node, r: Option[BatchCheckpoint], l: String => Unit)(implicit c: ExecutionContext) = Future.successful(SnapshotBoundary(n.id, "legacy", Some("20")))
        def discoverSplit(n: Node, p: SnapshotSplitPlanState, lower: Option[String], i: Long)(implicit c: ExecutionContext) =
          Future.successful(Some(SnapshotSplit(s"${p.id}:$i", i, lower, (i + 1).toString)))
        def openSplit(n: Node, e: String, p: SnapshotSplitPlanState, s: SnapshotSplit, c: Option[BatchCheckpoint], l: String => Unit, ce: ExecutionContext)(implicit x: ExecutionContext) = {
          lock.synchronized { opens += 1 }
          val batch = SourceBatch(n.id, s.id, 0, BatchId.sha256(e, n.id, s.id, 0), SourceCursor(SnapshotSplitState.CursorKind, s.upper, s.upper), Vector("row"))
          BatchSourceSession(Source.single(batch), () => { lock.synchronized { closes += 1 }; Future.successful(Done) })
        }
      }
      val sink = new NodeSink with CheckpointedNodeSink {
        def nodeType = "target"
        def createSink(n: Node, l: String => Unit)(implicit c: ExecutionContext): Sink[String, Future[Done]] = Sink.ignore
        def validateReady(n: Node, l: String => Unit)(implicit c: ExecutionContext) = Future.successful(Done)
        def commitBatch(n: Node, w: String, e: String, b: SourceBatch, rows: Vector[String], l: String => Unit)(implicit c: ExecutionContext) = {
          lock.synchronized { assert(!batches(b.batchId)); batches += b.batchId }
          Future.successful(Committed(BatchCheckpoint(b.sourceNodeId, b.partitionId, b.batchSequence, b.batchId, b.cursor, 1L, 1L)))
        }
      }
      val node = Node("s", "source", "test", "s", Position(0, 0), JsObject("chunkSize" -> JsNumber(1), "splitRows" -> JsNumber(1)))
      def executor = new SnapshotSplitExecutor(source, sink, node, node.copy(id = "t"), "w", "same-execution", 1, context,
        () => Future.successful(Done), _ => (), _ => (), _ => (), ec, ec)
      try {
        Await.result(executor.runWindow(), 10.seconds) shouldBe SnapshotWindowYielded(16L)
        val plan = saved.get.plan
        saved.get.nextIndex shouldBe 16L
        saved.get.splits shouldBe empty
        opens shouldBe 16; closes shouldBe 16; retries shouldBe 0
        Await.result(executor.runWindow(), 10.seconds) shouldBe SnapshotWindowCompleted(20L)
        saved.get.plan shouldBe plan
        saved.get.enumerated shouldBe true
        opens shouldBe 20; closes shouldBe 20; retries shouldBe 0; batches.size shouldBe 20
      } finally kit.shutdownTestKit()
    }
  }
}
