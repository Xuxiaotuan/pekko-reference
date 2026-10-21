package cn.xuyinyin.magic.workflow.execution

import cn.xuyinyin.magic.workflow.engine.CdcExecutionException

import cn.xuyinyin.magic.testkit.STSpec
import cn.xuyinyin.magic.workflow.WorkflowFixtures
import cn.xuyinyin.magic.workflow.checkpoint._
import cn.xuyinyin.magic.workflow.TestNodeRegistry
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.spi._
import com.typesafe.config.ConfigFactory
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._

class SourceResourceLifecycleSpec extends STSpec {
  private val testRegistry = new TestNodeRegistry

  private implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](Behaviors.empty[Nothing], "source-lifecycle",
    ConfigFactory.parseString("pekko.actor.provider=local\npekko.workflow.session-control-wait-timeout=200ms").withFallback(ConfigFactory.load("application-test")))
  private implicit val ec: ExecutionContext = system.executionContext
  override def afterAll(): Unit = { system.terminate(); Await.result(system.whenTerminated, 10.seconds); super.afterAll() }

  "execution release" should {
    "wait for asynchronous resource close after stream cancellation" in {
      val opened = Promise[Done]()
      val closeStarted = Promise[Done]()
      val closeFinished = Promise[Done]()
      val source = new NodeSource with CheckpointedNodeSource {
        val nodeType = "mysql.cdc"
        def createSource(n: WorkflowDSL.Node, log: String => Unit): Source[String, NotUsed] = Source.empty
        private def session(managed: Boolean): BatchSourceSession = BatchSourceSession.resource[Unit](
          () => { opened.trySuccess(Done); Future.successful(()) },
          _ => Promise[Option[SourceBatch]]().future,
          _ => { closeStarted.trySuccess(Done); closeFinished.future }, managed)
        override def createBatches(n: WorkflowDSL.Node, id: String, b: SnapshotBoundary, cp: Option[BatchCheckpoint], log: String => Unit)
          (implicit ec: ExecutionContext): Source[SourceBatch, NotUsed] = session(false).source
        override def openBatches(n: WorkflowDSL.Node, id: String, b: SnapshotBoundary, cp: Option[BatchCheckpoint], log: String => Unit,
          closeEc: ExecutionContext)(implicit ec: ExecutionContext): BatchSourceSession = session(true)

      }
      val sink = new NodeSink with CheckpointedNodeSink {
        val nodeType = "mysql.cdc.apply"
        def createSink(n: WorkflowDSL.Node, log: String => Unit)(implicit ec: ExecutionContext): Sink[String, Future[Done]] = Sink.ignore
        def validateReady(n: WorkflowDSL.Node, log: String => Unit)(implicit ec: ExecutionContext): Future[Done] = Future.successful(Done)
        def commitBatch(n: WorkflowDSL.Node, w: String, e: String, b: SourceBatch, rows: Vector[String], log: String => Unit)
          (implicit ec: ExecutionContext): Future[BatchCommitResult] = Future.failed(new AssertionError("no data"))
      }
      testRegistry.registerSource(source); testRegistry.registerSink(sink)
      lazy val engine = new WorkflowExecutionEngine(_ => Some("test"), nodeRegistry = testRegistry.snapshot)
      val context = ReliableRunContext("close-proof", 1L, Some(SnapshotBoundary("source-1", "p", Some("b"))), Vector.empty,
        _ => Future.successful(Done), _ => Future.successful(Done))
      try {
        val running = engine.execute(WorkflowFixtures.mysqlCdcWorkflow, context, _ => ())
        Await.result(opened.future, 3.seconds)
        val cancellation = engine.cancel(context.executionId)
        Await.result(closeStarted.future, 3.seconds)
        intercept[java.util.concurrent.TimeoutException] { Await.ready(cancellation, 300.millis) }
        val failure=Await.result(running.failed,3.seconds).asInstanceOf[CdcExecutionException]
        failure.failure.releaseConfirmed shouldBe false
        engine.activeReliableExecutionCount shouldBe 1
        closeFinished.success(Done)
        Await.result(cancellation, 3.seconds) shouldBe Done
        engine.activeReliableExecutionCount shouldBe 0
      } finally {
        closeFinished.trySuccess(Done)
        testRegistry.unregisterSource(source.nodeType, source); testRegistry.unregisterSink(sink.nodeType, sink)
      }
    }
  }
}
