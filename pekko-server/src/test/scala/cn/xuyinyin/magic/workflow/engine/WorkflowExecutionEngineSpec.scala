package cn.xuyinyin.magic.workflow.engine

import cn.xuyinyin.magic.testkit.STSpec
import cn.xuyinyin.magic.workflow.WorkflowFixtures
import cn.xuyinyin.magic.workflow.checkpoint._
import cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base.{CheckpointedNodeSink, CheckpointedNodeSource, NodeSink, NodeSource}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import spray.json.{JsObject, JsString}

import scala.collection.mutable.ListBuffer
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import java.util.concurrent.atomic.AtomicBoolean

class WorkflowExecutionEngineSpec extends STSpec {
  private var registeredSource: Option[NodeSource] = None
  private implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](
    Behaviors.empty[Nothing],
    "workflow-execution-engine-spec",
    ConfigFactory.parseString(
      """pekko.actor.provider = local
        |pekko.coordinated-shutdown.exit-jvm = off""".stripMargin
    ).withFallback(ConfigFactory.load("application-test"))
  )
  private implicit val ec: ExecutionContext = system.executionContext

  override protected def afterAll(): Unit = {
    system.terminate()
    Await.result(system.whenTerminated, 5.seconds)
    super.afterAll()
  }

  override protected def afterEach(): Unit = {
    try registeredSource.foreach(source => NodeRegistry.unregisterSource(source.nodeType, source))
    finally {
      registeredSource = None
      FailingSource.reset()
      ReliableSource.reset()
      ReliableSink.reset()
      LegacySource.reset()
      CapableLegacySink.reset()
      super.afterEach()
    }
  }

  "WorkflowExecutionEngine" should {
    "report the failing Sink node without converting the execution to success" in {
      val engine = new WorkflowExecutionEngine()

      val result = Await.result(engine.execute(WorkflowFixtures.failingSinkWorkflow, "exec-1", _ => ()), 5.seconds)

      result.success shouldBe false
      result.nodeResults.map(_.nodeId) shouldBe Vector("source-1", "sink-1")
      result.nodeResults.map(_.status) shouldBe Vector("not_started", "failed")
    }

    "attribute an asynchronous Source failure and preserve all pipeline node results" in {
      register(FailingSource)
      val engine = new WorkflowExecutionEngine()
      FailingSource.reset()

      val result = Await.result(engine.execute(
        WorkflowFixtures.failingSourceWorkflow,
        "exec-2",
        _ => ()
      ), 5.seconds)

      result.success shouldBe false
      FailingSource.emittedFirstValue shouldBe true
      result.nodeResults.map(_.nodeId) shouldBe Vector("source-1", "transform-1", "sink-1")
      result.nodeResults.map(_.status) shouldBe Vector("failed", "unknown", "unknown")
    }

    "return ordered conservative results when validation prevents execution" in {
      val engine = new WorkflowExecutionEngine()

      val result = Await.result(engine.execute(WorkflowFixtures.branchedWorkflow, "exec-3", _ => ()), 5.seconds)

      result.success shouldBe false
      result.nodeResults.map(_.nodeId) shouldBe Vector("source-1", "transform-1", "sink-1", "sink-2")
      result.nodeResults.map(_.status) shouldBe Vector("not_started", "not_started", "not_started", "not_started")
    }

    "keep a replacement Source registered when cleanup expects the previous instance" in {
      register(FailingSource)
      NodeRegistry.registerSource(ReplacementSource)

      NodeRegistry.unregisterSource(FailingSource.nodeType, FailingSource)

      NodeRegistry.findSource(FailingSource.nodeType) shouldBe Some(ReplacementSource)
      registeredSource = Some(ReplacementSource)
    }

    "keep an equal but distinct replacement Source registered during cleanup" in {
      register(FailingSource)
      NodeRegistry.registerSource(EqualReplacementSource)
      registeredSource = Some(EqualReplacementSource)

      NodeRegistry.unregisterSource(FailingSource.nodeType, FailingSource)

      NodeRegistry.findSource(FailingSource.nodeType) shouldBe Some(EqualReplacementSource)
    }

    "keep an equal but distinct replacement Sink registered during cleanup" in {
      NodeRegistry.registerSink(LegacySink)
      NodeRegistry.registerSink(EqualReplacementSink)

      NodeRegistry.unregisterSink(LegacySink.nodeType, LegacySink)

      NodeRegistry.findSink(LegacySink.nodeType) shouldBe Some(EqualReplacementSink)
      NodeRegistry.unregisterSink(EqualReplacementSink.nodeType, EqualReplacementSink)
    }

    "run supplied-boundary batches sequentially from the latest checkpoint" in {
      register(ReliableSource)
      NodeRegistry.registerSink(ReliableSink)
      val boundary = SnapshotBoundary("source-1", "pk-range-0", Some("9"))
      val checkpoint0 = checkpoint("exec-reliable", 0L, "2", sourceRows = 2L, targetRows = 2L)
      ReliableSource.batches = Vector(
        batch("exec-reliable", 1L, "5", Vector("three", "four")),
        batch("exec-reliable", 2L, "9", Vector("five"))
      )
      val context = ReliableRunContext(
        "exec-reliable",
        7L,
        Some(boundary),
        Vector(checkpoint0),
        value => Future.successful(Done),
        value => {
          ReliableEvents.add(s"checkpoint-${value.batchSequence}")
          Future.successful(Done)
        }
      )

      val result = Await.result(new WorkflowExecutionEngine().execute(reliableWorkflow(), context, _ => ()), 5.seconds)

      result.success shouldBe true
      result.rowsProcessed shouldBe None
      ReliableSource.discoveries shouldBe 0
      ReliableSource.resumeFrom shouldBe Some(checkpoint0)
      ReliableSink.commits.map(_._2) shouldBe Vector(Vector("THREE", "FOUR"), Vector("FIVE"))
      val events = ReliableEvents.snapshot
      events.indexOf("validate") should be < events.indexOf("create-batches")
      events.indexOf("checkpoint-1") should be < events.indexOf("pull-2")
      events.indexOf("checkpoint-2") should be > events.indexOf("commit-2")
    }

    "report a representable target row count for a new reliable execution" in {
      register(ReliableSource)
      NodeRegistry.registerSink(ReliableSink)
      ReliableSource.batches = Vector(
        batch("exec-counted", 0L, "2", Vector("one", "two")),
        batch("exec-counted", 1L, "9", Vector("three"))
      )
      val context = ReliableRunContext(
        "exec-counted",
        1L,
        Some(SnapshotBoundary("source-1", "pk-range-0", Some("9"))),
        Vector.empty,
        _ => Future.successful(Done),
        _ => Future.successful(Done)
      )

      val result = Await.result(new WorkflowExecutionEngine().execute(reliableWorkflow(), context, _ => ()), 5.seconds)

      result.success shouldBe true
      result.rowsProcessed shouldBe Some(3)
    }

    "complete with no target row count when durable commits exceed Int range" in {
      register(ReliableSource)
      NodeRegistry.registerSink(ReliableSink)
      ReliableSource.batches = Vector(
        batch("exec-large-count", 0L, "2", Vector("one")),
        batch("exec-large-count", 1L, "9", Vector("two"))
      )
      ReliableSink.targetRowsBySequence = Map(0L -> Int.MaxValue.toLong, 1L -> 1L)
      val acknowledged = ListBuffer.empty[Long]
      val context = ReliableRunContext(
        "exec-large-count",
        1L,
        Some(SnapshotBoundary("source-1", "pk-range-0", Some("9"))),
        Vector.empty,
        _ => Future.successful(Done),
        checkpoint => {
          acknowledged.synchronized(acknowledged += checkpoint.batchSequence)
          Future.successful(Done)
        }
      )

      val result = Await.result(new WorkflowExecutionEngine().execute(reliableWorkflow(), context, _ => ()), 5.seconds)

      result.status shouldBe "completed"
      result.success shouldBe true
      result.rowsProcessed shouldBe None
      ReliableSink.commits.size shouldBe 2
      acknowledged.synchronized(acknowledged.toVector) shouldBe Vector(0L, 1L)
    }

    "persist a discovered boundary before committing an empty transformed batch" in {
      register(ReliableSource)
      NodeRegistry.registerSink(ReliableSink)
      ReliableSource.batches = Vector(batch("exec-empty", 0L, "9", Vector("1")))
      val context = ReliableRunContext(
        "exec-empty",
        1L,
        None,
        Vector.empty,
        boundary => {
          ReliableEvents.add(s"boundary-${boundary.upperBound.getOrElse("empty")}")
          Future.successful(Done)
        },
        checkpoint => {
          ReliableEvents.add(s"checkpoint-${checkpoint.batchSequence}")
          Future.successful(Done)
        }
      )

      val result = Await.result(new WorkflowExecutionEngine().execute(reliableWorkflow("filter", "condition", "value > 10"), context, _ => ()), 5.seconds)

      result.success shouldBe true
      result.rowsProcessed shouldBe Some(0)
      ReliableSource.discoveries shouldBe 1
      ReliableSource.resumeFrom shouldBe None
      ReliableSink.commits.map(_._2) shouldBe Vector(Vector.empty)
      ReliableEvents.snapshot should contain.inOrderOnly(
        "validate",
        "discover-boundary",
        "boundary-9",
        "create-batches",
        "pull-0",
        "commit-0",
        "checkpoint-0"
      )
    }

    "stop before boundary discovery when reliable sink readiness fails" in {
      register(ReliableSource)
      NodeRegistry.registerSink(ReliableSink)
      ReliableSink.readinessFailure = Some(new IllegalStateException("ledger unavailable"))
      val context = ReliableRunContext("exec-not-ready", 1L, None, Vector.empty, _ => Future.successful(Done), _ => Future.successful(Done))

      val result = Await.result(new WorkflowExecutionEngine().execute(reliableWorkflow(), context, _ => ()), 5.seconds)

      result.success shouldBe false
      result.nodeResults.find(_.nodeId == "sink-1").map(_.status) shouldBe Some("failed")
      ReliableSource.discoveries shouldBe 0
      ReliableSource.createCalls shouldBe 0
      ReliableEvents.snapshot shouldBe Vector("validate")
    }

    "attribute reliable transform setup failures to the transform node" in {
      register(ReliableSource)
      NodeRegistry.registerSink(ReliableSink)
      val boundary = SnapshotBoundary("source-1", "pk-range-0", Some("9"))
      val context = ReliableRunContext("exec-transform-failure", 1L, Some(boundary), Vector.empty, _ => Future.successful(Done), _ => Future.successful(Done))

      val result = Await.result(new WorkflowExecutionEngine().execute(
        reliableWorkflow(transformConfigKey = "missing-expression"),
        context,
        _ => ()
      ), 5.seconds)

      result.success shouldBe false
      result.nodeResults.map(_.status) shouldBe Vector("unknown", "failed", "unknown")
    }

    "reject a checkpoint-aware source paired with a legacy sink" in {
      register(ReliableSource)
      NodeRegistry.registerSink(LegacySink)
      val context = ReliableRunContext("exec-mismatch", 1L, None, Vector.empty, _ => Future.successful(Done), _ => Future.successful(Done))

      val result = Await.result(new WorkflowExecutionEngine().execute(reliableWorkflow(sinkType = LegacySink.nodeType), context, _ => ()), 5.seconds)

      result.success shouldBe false
      result.message should include("ReliableRunContext capability loss")
      result.message should include(ReliableSource.nodeType)
      result.message should include(LegacySink.nodeType)
      result.message should include("checkpoint-aware sink")
      ReliableSource.discoveries shouldBe 0
      ReliableSource.createCalls shouldBe 0
    }

    "keep a legacy source on the row stream when its sink is checkpoint-aware" in {
      register(LegacySource)
      NodeRegistry.registerSink(CapableLegacySink)

      val result = Await.result(new WorkflowExecutionEngine().execute(legacyCapableSinkWorkflow, "exec-legacy", _ => ()), 5.seconds)

      result.success shouldBe true
      LegacySource.legacyCreates shouldBe 1
      CapableLegacySink.legacyCreates shouldBe 1
      CapableLegacySink.readyCalls shouldBe 0
      CapableLegacySink.batchCommits shouldBe 0
    }

    "fail closed when a reliable run context loses its source capability" in {
      register(LegacySource)
      NodeRegistry.registerSink(CapableLegacySink)
      val context = ReliableRunContext(
        "exec-lost-source-capability",
        1L,
        Some(SnapshotBoundary("source-1", "pk-range-0", Some("9"))),
        Vector(checkpoint("exec-lost-source-capability", 0L, "2", sourceRows = 2L, targetRows = 2L)),
        _ => Future.successful(Done),
        _ => Future.successful(Done)
      )

      val result = Await.result(new WorkflowExecutionEngine().execute(legacyCapableSinkWorkflow, context, _ => ()), 5.seconds)

      result.success shouldBe false
      result.message should include("ReliableRunContext")
      result.message should include(LegacySource.nodeType)
      result.message should include("checkpoint-aware source")
      LegacySource.legacyCreates shouldBe 0
      CapableLegacySink.legacyCreates shouldBe 0
      CapableLegacySink.readyCalls shouldBe 0
      CapableLegacySink.batchCommits shouldBe 0
    }

    "register mysql.snapshot separately from mysql.query" in {
      val snapshot = NodeRegistry.findSource("mysql.snapshot").get
      val query = NodeRegistry.findSource("mysql.query").get

      snapshot.nodeType shouldBe "mysql.snapshot"
      query.nodeType shouldBe "mysql.query"
      (snapshot eq query) shouldBe false
    }

    "load a fixed JDBC dispatcher in every runtime profile" in {
      Vector("application.conf", "application-dev.conf", "application-prod.conf", "application-test.conf").foreach { resource =>
        val dispatcher = ConfigFactory.parseResources(resource).getConfig("pekko.workflow.jdbc-dispatcher")
        dispatcher.getString("executor") shouldBe "thread-pool-executor"
        dispatcher.getInt("thread-pool-executor.fixed-pool-size") should be > 0
      }
    }
  }

  private def register(source: NodeSource): Unit = {
    registeredSource = Some(source)
    NodeRegistry.registerSource(source)
  }

  private def reliableWorkflow(
    transformType: String = "map",
    transformConfigKey: String = "expression",
    transformConfigValue: String = "toUpperCase",
    sinkType: String = ReliableSink.nodeType
  ): WorkflowDSL.Workflow = WorkflowFixtures.linearWorkflow.copy(nodes =
    WorkflowFixtures.linearWorkflow.nodes
      .updated(0, WorkflowFixtures.linearWorkflow.nodes.head.copy(nodeType = ReliableSource.nodeType))
      .updated(1, WorkflowFixtures.linearWorkflow.nodes(1).copy(
        nodeType = transformType,
        config = JsObject(transformConfigKey -> JsString(transformConfigValue))
      ))
      .updated(2, WorkflowFixtures.linearWorkflow.nodes(2).copy(nodeType = sinkType))
  )

  private val legacyCapableSinkWorkflow: WorkflowDSL.Workflow = WorkflowFixtures.linearWorkflow.copy(nodes =
    WorkflowFixtures.linearWorkflow.nodes
      .updated(0, WorkflowFixtures.linearWorkflow.nodes.head.copy(nodeType = LegacySource.nodeType))
      .updated(2, WorkflowFixtures.linearWorkflow.nodes(2).copy(nodeType = CapableLegacySink.nodeType))
  )

  private def batch(executionId: String, sequence: Long, cursorValue: String, rows: Vector[String]): SourceBatch = SourceBatch(
    "source-1",
    "pk-range-0",
    sequence,
    BatchId.sha256(executionId, "source-1", "pk-range-0", sequence),
    SourceCursor("mysql.numeric-pk", cursorValue, "9"),
    rows
  )

  private def checkpoint(executionId: String, sequence: Long, cursorValue: String, sourceRows: Long, targetRows: Long): BatchCheckpoint = BatchCheckpoint(
    "source-1",
    "pk-range-0",
    sequence,
    BatchId.sha256(executionId, "source-1", "pk-range-0", sequence),
    SourceCursor("mysql.numeric-pk", cursorValue, "9"),
    sourceRows,
    targetRows
  )

  private object ReliableEvents {
    private val values = ListBuffer.empty[String]
    def add(value: String): Unit = synchronized(values += value)
    def clear(): Unit = synchronized(values.clear())
    def snapshot: Vector[String] = synchronized(values.toVector)
  }

  private object ReliableSource extends NodeSource with CheckpointedNodeSource {
    override val nodeType: String = "test.checkpoint-source"
    @volatile var batches: Vector[SourceBatch] = Vector.empty
    @volatile var resumeFrom: Option[BatchCheckpoint] = None
    @volatile var discoveries: Int = 0
    @volatile var createCalls: Int = 0

    def reset(): Unit = {
      batches = Vector.empty
      resumeFrom = None
      discoveries = 0
      createCalls = 0
      ReliableEvents.clear()
    }

    override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] =
      throw new AssertionError("reliable source used the legacy row stream")

    override def discoverBoundary(node: WorkflowDSL.Node, onLog: String => Unit)(implicit blockingEc: ExecutionContext): Future[SnapshotBoundary] = {
      discoveries += 1
      ReliableEvents.add("discover-boundary")
      Future.successful(SnapshotBoundary(node.id, "pk-range-0", Some("9")))
    }

    override def createBatches(
      node: WorkflowDSL.Node,
      executionId: String,
      boundary: SnapshotBoundary,
      resume: Option[BatchCheckpoint],
      onLog: String => Unit
    )(implicit blockingEc: ExecutionContext): Source[SourceBatch, NotUsed] = {
      createCalls += 1
      resumeFrom = resume
      ReliableEvents.add("create-batches")
      Source.fromIterator(() => batches.iterator.map { value =>
        ReliableEvents.add(s"pull-${value.batchSequence}")
        value
      })
    }
  }

  private object ReliableSink extends NodeSink with CheckpointedNodeSink {
    override val nodeType: String = "test.checkpoint-sink"
    @volatile var readinessFailure: Option[Throwable] = None
    @volatile var commits: Vector[(SourceBatch, Vector[String])] = Vector.empty
    @volatile var targetRowsBySequence: Map[Long, Long] = Map.empty

    def reset(): Unit = {
      readinessFailure = None
      commits = Vector.empty
      targetRowsBySequence = Map.empty
    }

    override def createSink(node: WorkflowDSL.Node, onLog: String => Unit)(implicit ec: ExecutionContext): Sink[String, Future[Done]] =
      throw new AssertionError("reliable sink used the legacy row stream")

    override def validateReady(node: WorkflowDSL.Node, onLog: String => Unit)(implicit blockingEc: ExecutionContext): Future[Done] = {
      ReliableEvents.add("validate")
      readinessFailure.fold(Future.successful(Done))(Future.failed)
    }

    override def commitBatch(
      node: WorkflowDSL.Node,
      workflowId: String,
      executionId: String,
      value: SourceBatch,
      transformedRows: Vector[String],
      onLog: String => Unit
    )(implicit blockingEc: ExecutionContext): Future[BatchCommitResult] = {
      commits = commits :+ (value -> transformedRows)
      ReliableEvents.add(s"commit-${value.batchSequence}")
      Future.successful(Committed(BatchCheckpoint(
        value.sourceNodeId,
        value.partitionId,
        value.batchSequence,
        value.batchId,
        value.cursor,
        value.rows.size.toLong,
        targetRowsBySequence.getOrElse(value.batchSequence, transformedRows.size.toLong)
      )))
    }
  }

  private object LegacySink extends NodeSink {
    override val nodeType: String = "test.legacy-sink"
    override def createSink(node: WorkflowDSL.Node, onLog: String => Unit)(implicit ec: ExecutionContext): Sink[String, Future[Done]] = Sink.ignore
  }

  private object EqualReplacementSink extends NodeSink {
    override val nodeType: String = LegacySink.nodeType

    override def equals(other: Any): Boolean = other match {
      case sink: NodeSink => sink.nodeType == nodeType
      case _ => false
    }

    override def hashCode(): Int = nodeType.hashCode

    override def createSink(node: WorkflowDSL.Node, onLog: String => Unit)(implicit ec: ExecutionContext): Sink[String, Future[Done]] = Sink.ignore
  }

  private object LegacySource extends NodeSource {
    override val nodeType: String = "test.legacy-source"
    @volatile var legacyCreates: Int = 0
    def reset(): Unit = legacyCreates = 0
    override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = {
      legacyCreates += 1
      Source.single(" legacy ")
    }
  }

  private object CapableLegacySink extends NodeSink with CheckpointedNodeSink {
    override val nodeType: String = "test.capable-legacy-sink"
    @volatile var legacyCreates: Int = 0
    @volatile var readyCalls: Int = 0
    @volatile var batchCommits: Int = 0

    def reset(): Unit = {
      legacyCreates = 0
      readyCalls = 0
      batchCommits = 0
    }

    override def createSink(node: WorkflowDSL.Node, onLog: String => Unit)(implicit ec: ExecutionContext): Sink[String, Future[Done]] = {
      legacyCreates += 1
      Sink.ignore
    }

    override def validateReady(node: WorkflowDSL.Node, onLog: String => Unit)(implicit blockingEc: ExecutionContext): Future[Done] = {
      readyCalls += 1
      Future.successful(Done)
    }

    override def commitBatch(
      node: WorkflowDSL.Node,
      workflowId: String,
      executionId: String,
      value: SourceBatch,
      transformedRows: Vector[String],
      onLog: String => Unit
    )(implicit blockingEc: ExecutionContext): Future[BatchCommitResult] = {
      batchCommits += 1
      Future.failed(new AssertionError("legacy source entered reliable mode"))
    }
  }

  private object FailingSource extends NodeSource {
    override val nodeType: String = "test.failing-source"
    private val emittedFirst = new AtomicBoolean(false)

    def emittedFirstValue: Boolean = emittedFirst.get
    def reset(): Unit = emittedFirst.set(false)

    override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] =
      Source(List("first"))
        .map { value =>
          emittedFirst.set(true)
          value
        }
        .concat(Source.failed(new IllegalStateException("source boom")))
  }

  private object ReplacementSource extends NodeSource {
    override val nodeType: String = FailingSource.nodeType

    override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] =
      Source.empty
  }

  private object EqualReplacementSource extends NodeSource {
    override val nodeType: String = FailingSource.nodeType

    override def equals(other: Any): Boolean = other match {
      case source: NodeSource => source.nodeType == nodeType
      case _ => false
    }

    override def hashCode(): Int = nodeType.hashCode

    override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] =
      Source.empty
  }
}
