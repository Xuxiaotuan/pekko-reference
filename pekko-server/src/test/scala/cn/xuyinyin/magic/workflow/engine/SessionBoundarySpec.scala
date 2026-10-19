package cn.xuyinyin.magic.workflow.engine

import cn.xuyinyin.magic.testkit.STSpec
import cn.xuyinyin.magic.workflow.WorkflowFixtures
import cn.xuyinyin.magic.workflow.checkpoint._
import cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base._
import cn.xuyinyin.magic.workflow.nodes.sources.DebeziumBatchBridge
import com.typesafe.config.ConfigFactory
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import java.util.concurrent.{ConcurrentLinkedQueue, Executors}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters._

class SessionBoundarySpec extends STSpec {
  private implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](Behaviors.empty, "session-boundaries",
    ConfigFactory.parseString("pekko.actor.provider=local").withFallback(ConfigFactory.load("application-test")))
  private implicit val ec: ExecutionContext = system.executionContext
  override def afterAll(): Unit = { system.terminate(); Await.result(system.whenTerminated, 10.seconds); super.afterAll() }
  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  "instance catalogs" should {
    "keep overrides and validator decisions isolated, with stable adapter identity" in {
      val original = NodeRegistry.builtins()
      val extra = new ProbeSource { override val nodeType = "test.private" }
      val modified = original.withSource(extra)
      modified.findSource(extra.nodeType).get should be theSameInstanceAs extra
      original.findSource(extra.nodeType) shouldBe None
      NodeRegistry.builtins().findSource(extra.nodeType) shouldBe None
      val workflow = WorkflowFixtures.mysqlCdcWorkflow.copy(nodes = WorkflowFixtures.mysqlCdcWorkflow.nodes.map(n =>
        if (n.`type` == "source") n.copy(nodeType = extra.nodeType) else n.copy(nodeType = "console.log")))
      WorkflowValidator.validate(workflow, original).isLeft shouldBe true
      WorkflowValidator.validate(workflow, modified).isRight shouldBe true
      (original.findSource("mysql.cdc").get eq NodeRegistry.builtins().findSource("mysql.cdc").get) shouldBe false
    }
  }

  "asynchronous bridge reads" should {
    "wake a queued asynchronous read after the previous ACK completes" in {
      val bridge = new DebeziumBatchBridge
      val handle = new cn.xuyinyin.magic.workflow.nodes.sources.CdcBatchCommitHandle {
        def markProcessedAndFinished(): Unit = ()
      }
      val first = Future { bridge.publish("one", Vector("1"), "1", handle) }
      val delivered = await(bridge.takeAsync())
      val second = Promise[Done]()
      val publisher = new Thread(() => {
        try { bridge.publish("two", Vector("2"), "2", handle); second.success(Done) }
        catch { case e: Throwable => second.tryFailure(e) }
      }, "queued-publisher")
      publisher.setDaemon(true); publisher.start()
      val deadline = 3.seconds.fromNow
      while (deadline.hasTimeLeft() && !publisher.getStackTrace.exists(_.getMethodName == "awaitCompletion")) Thread.`yield`()
      publisher.getStackTrace.exists(_.getMethodName == "awaitCompletion") shouldBe true
      val next = bridge.takeAsync()
      try {
        bridge.acknowledge(delivered.deliveryToken, "one")
        val two = await(next)
        two.batchId shouldBe "two"
        bridge.acknowledge(two.deliveryToken, "two")
        await(first); await(second.future)
      } finally bridge.close()
    }
    "leave a single worker available with sixteen idle readers and release all waiters on close" in {
      val executor = Executors.newSingleThreadExecutor()
      val worker = ExecutionContext.fromExecutor(executor)
      val bridges = Vector.fill(16)(new DebeziumBatchBridge)
      try {
        val pending = await(Future(bridges.map(_.takeAsync()))(worker))
        await(Future(42)(worker)) shouldBe 42
        pending.forall(!_.isCompleted) shouldBe true
        bridges.foreach(_.close())
        pending.foreach(f => await(f.failed).getMessage should include("closed"))
      } finally { bridges.foreach(_.close()); executor.shutdownNow() }
    }
  }

  "resource ownership" should {
    "drain a resource read before starting physical close" in {
      val reading = Promise[Done]()
      val read = Promise[Option[SourceBatch]]()
      val closeStarted = Promise[Done]()
      val session = BatchSourceSession.resource[Unit](() => Future.successful(()),
        _ => { reading.trySuccess(Done); read.future },
        _ => { closeStarted.trySuccess(Done); Future.successful(Done) }, managed = true, drainReads = true)
      val (kill, done) = session.source.viaMat(org.apache.pekko.stream.KillSwitches.single)(org.apache.pekko.stream.scaladsl.Keep.right)
        .toMat(Sink.ignore)(org.apache.pekko.stream.scaladsl.Keep.both).run()
      await(reading.future)
      kill.shutdown()
      await(done)
      val closed = session.close()
      try {
        closeStarted.isCompleted shouldBe false
        closed.isCompleted shouldBe false
        read.success(None)
        await(closed) shouldBe Done
      } finally read.trySuccess(None)
    }
    "wait for late acquisition, close once, and preserve a close failure" in {
      val scope = new SourceResourceScope
      val opened = Promise[String]()
      val closeGate = Promise[Done]()
      val calls = new AtomicInteger
      scope.acquire(() => opened.future, (_: String) => { calls.incrementAndGet(); closeGate.future })
      val closing = scope.close()
      closing.isCompleted shouldBe false
      opened.success("late")
      calls.get() shouldBe 1
      closing.isCompleted shouldBe false
      val failure = new IllegalStateException("close failed")
      closeGate.failure(failure)
      await(closing.failed) shouldBe failure
      await(scope.close().failed) shouldBe failure
      calls.get() shouldBe 1
      await(scope.acquire(() => Future.successful("forbidden"), (_: String) => Future.successful(Done)).failed)
        .getMessage should include("closing")
    }
  }

  "execution cancellation" should {
    "redact runtime secrets from asynchronous resource close failures" in {
      val secret = "stage-c-runtime-close-secret"
      val source = new ProbeSource
      val engine = new WorkflowExecutionEngine(_ => Some(secret), NodeRegistry.builtins().withSource(source).withSink(new ProbeSink))
      val context = runContext("secret-close")
      val running = engine.execute(WorkflowFixtures.mysqlCdcWorkflow, context, _ => ())
      await(source.opened.future)
      engine.cancel(context.executionId)
      await(source.closeStarted.future)
      source.closeGate.failure(new IllegalStateException(s"close failed: $secret"))
      await(running.failed).getMessage should not include secret
      await(engine.cancelWithin(context.executionId, 100.millis)) shouldBe WorkflowExecutionEngine.CloseFailed
    }

    "avoid initializing a boundary returned after cancellation" in {
      val discovery = Promise[SnapshotBoundary]()
      val discoveryStarted = Promise[Done]()
      val initialized = new AtomicInteger
      val source = new ProbeSource {
        override def discoverBoundary(n: WorkflowDSL.Node, cp: Option[BatchCheckpoint], log: String => Unit)
          (implicit ec: ExecutionContext): Future[SnapshotBoundary] = {
          discoveryStarted.success(Done); discovery.future
        }
      }
      val engine = new WorkflowExecutionEngine(_ => Some("test"), NodeRegistry.builtins().withSource(source).withSink(new ProbeSink))
      val context = runContext("cancel-discovery").copy(boundary = None,
        initializeBoundary = _ => { initialized.incrementAndGet(); Future.successful(Done) })
      val running = engine.execute(WorkflowFixtures.mysqlCdcWorkflow, context, _ => ())
      await(discoveryStarted.future)
      await(engine.cancelWithin(context.executionId, 100.millis)) shouldBe WorkflowExecutionEngine.TimedOut
      discovery.success(SnapshotBoundary("source-1", "p", Some("b")))
      await(running).success shouldBe false
      await(engine.cancel(context.executionId)) shouldBe Done
      initialized.get shouldBe 0
      source.opens.get shouldBe 0
    }

    "drain T then J then ACK before closing, and return a bounded observation while blocked" in {
      val source = new ProbeSource
      val commit = Promise[BatchCommitResult]()
      val j = Promise[Done]()
      val tStarted = Promise[Done]()
      val jStarted = Promise[Done]()
      val events = new ConcurrentLinkedQueue[String]()
      source.events = events
      source.emit = true
      val sink = new ProbeSink {
        override def commitBatch(n: WorkflowDSL.Node, w: String, e: String, b: SourceBatch, rows: Vector[String], log: String => Unit)
          (implicit ec: ExecutionContext): Future[BatchCommitResult] = {
          events.add("T.begin"); tStarted.success(Done); commit.future
        }
      }
      val engine = new WorkflowExecutionEngine(_ => Some("test"), NodeRegistry.builtins().withSource(source).withSink(sink))
      val context = runContext("drain").copy(checkpointCommitted = _ => {
        events.add("J.begin"); jStarted.success(Done); j.future.map { d => events.add("J.done"); d }
      })
      val running = engine.execute(WorkflowFixtures.mysqlCdcWorkflow, context, _ => ())
      try {
        await(tStarted.future)
        await(engine.cancelWithin("drain", 100.millis)) shouldBe WorkflowExecutionEngine.TimedOut
        source.closeStarted.isCompleted shouldBe false
        val batch = source.batch("drain")
        events.add("T.done")
        commit.success(Committed(BatchCheckpoint(batch.sourceNodeId, batch.partitionId, 0, batch.batchId, batch.cursor, 1, 1)))
        await(jStarted.future)
        await(engine.cancelWithin("drain", 100.millis)) shouldBe WorkflowExecutionEngine.TimedOut
        events.asScala should not contain "ACK"
        source.closeStarted.isCompleted shouldBe false
        j.success(Done)
        await(source.closeStarted.future)
        await(engine.cancelWithin("drain", 100.millis)) shouldBe WorkflowExecutionEngine.TimedOut
        source.closeGate.success(Done)
        await(running)
        await(engine.cancel("drain")) shouldBe Done
        events.asScala.toVector shouldBe Vector("T.begin", "T.done", "J.begin", "J.done", "ACK", "close")
      } finally { commit.tryFailure(new IllegalStateException("cleanup")); j.trySuccess(Done); source.closeGate.trySuccess(Done) }
    }

    "hold replacement materialization until physical release, including repeated cancellation" in {
      val source = new ProbeSource
      val engine = new WorkflowExecutionEngine(_ => Some("test"), NodeRegistry.builtins().withSource(source).withSink(new ProbeSink))
      val context = runContext("replace")
      val old = engine.execute(WorkflowFixtures.mysqlCdcWorkflow, context, _ => ())
      await(source.opened.future)
      await(engine.cancelWithin("replace", 100.millis)) shouldBe WorkflowExecutionEngine.TimedOut
      val replacement = engine.execute(WorkflowFixtures.mysqlCdcWorkflow, context, _ => ())
      try {
        await(source.closeStarted.future)
        source.opens.get shouldBe 1
        engine.activeReliableExecutionCount shouldBe 2
        source.closeGate.success(Done)
        await(old)
        await(source.secondOpened.future)
        source.opens.get shouldBe 2
        await(engine.cancel("replace")) shouldBe Done
        await(replacement)
        engine.activeReliableExecutionCount shouldBe 0
      } finally { source.closeGate.trySuccess(Done); engine.cancel("replace") }
    }

    "keep a failed close fenced instead of reporting release or launching a replacement" in {
      val source = new ProbeSource
      val engine = new WorkflowExecutionEngine(_ => Some("test"), NodeRegistry.builtins().withSource(source).withSink(new ProbeSink))
      val context = runContext("failed-close")
      val running = engine.execute(WorkflowFixtures.mysqlCdcWorkflow, context, _ => ())
      await(source.opened.future)
      engine.cancel("failed-close")
      await(source.closeStarted.future)
      source.closeGate.failure(new IllegalStateException("close failed"))
      await(running.failed).getMessage shouldBe "close failed"
      await(engine.cancelWithin("failed-close", 100.millis)) shouldBe WorkflowExecutionEngine.CloseFailed
      val replacement = engine.execute(WorkflowFixtures.mysqlCdcWorkflow, context, _ => ())
      await(engine.cancelWithin("failed-close", 100.millis)) shouldBe WorkflowExecutionEngine.CloseFailed
      replacement.isCompleted shouldBe false
      source.opens.get shouldBe 1
    }
  }

  private def runContext(id: String): ReliableRunContext = ReliableRunContext(id, 1,
    Some(SnapshotBoundary("source-1", "p", Some("b"))), Vector.empty, _ => Future.successful(Done), _ => Future.successful(Done))

  private class ProbeSource extends NodeSource with CheckpointedNodeSource {
    val nodeType = "mysql.cdc"
    var emit = false
    var events = new ConcurrentLinkedQueue[String]()
    val opened = Promise[Done](); val secondOpened = Promise[Done]()
    val closeStarted = Promise[Done](); val closeGate = Promise[Done]()
    val opens = new AtomicInteger
    def batch(id: String): SourceBatch = SourceBatch("source-1", "p", 0, BatchId.sha256(id, "source-1", "p", 0),
      SourceCursor("test", "0", "b"), Vector("row"))
    def createSource(n: WorkflowDSL.Node, log: String => Unit): Source[String, NotUsed] = Source.empty
    def createBatches(n: WorkflowDSL.Node, id: String, b: SnapshotBoundary, cp: Option[BatchCheckpoint], log: String => Unit)
      (implicit ec: ExecutionContext): Source[SourceBatch, NotUsed] = session(id, false).source
    override def openBatches(n: WorkflowDSL.Node, id: String, b: SnapshotBoundary, cp: Option[BatchCheckpoint], log: String => Unit,
      closeEc: ExecutionContext)(implicit ec: ExecutionContext): BatchSourceSession = session(id, true)
    private def session(id: String, managed: Boolean): BatchSourceSession = {
      var sent = false
      val idle = Promise[Option[SourceBatch]]()
      BatchSourceSession.resource[Unit](
        () => { if (opens.incrementAndGet() == 1) opened.trySuccess(Done) else secondOpened.trySuccess(Done); Future.successful(()) },
        _ => if (emit && !sent) { sent = true; Future.successful(Some(batch(id))) } else idle.future,
        _ => { events.add("close"); idle.trySuccess(None); closeStarted.trySuccess(Done); closeGate.future }, managed)
    }
    override def acknowledgeCommittedBatch(n: WorkflowDSL.Node, b: SourceBatch, log: String => Unit)
      (implicit ec: ExecutionContext): Future[Done] = { events.add("ACK"); Future.successful(Done) }
  }

  private class ProbeSink extends NodeSink with CheckpointedNodeSink {
    val nodeType = "mysql.cdc.apply"
    def createSink(n: WorkflowDSL.Node, log: String => Unit)(implicit ec: ExecutionContext): Sink[String, Future[Done]] = Sink.ignore
    def validateReady(n: WorkflowDSL.Node, log: String => Unit)(implicit ec: ExecutionContext): Future[Done] = Future.successful(Done)
    def commitBatch(n: WorkflowDSL.Node, w: String, e: String, b: SourceBatch, rows: Vector[String], log: String => Unit)
      (implicit ec: ExecutionContext): Future[BatchCommitResult] = Future.failed(new AssertionError("unexpected batch"))
  }
}
