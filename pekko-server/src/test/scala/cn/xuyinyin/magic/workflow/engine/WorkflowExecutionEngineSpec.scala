package cn.xuyinyin.magic.workflow.engine

import cn.xuyinyin.magic.testkit.STSpec
import cn.xuyinyin.magic.workflow.WorkflowFixtures
import cn.xuyinyin.magic.workflow.checkpoint._
import cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base.{CheckpointedNodeSink, CheckpointedNodeSource, NodeSink, NodeSource}
import cn.xuyinyin.magic.workflow.nodes.sinks.MySQLCdcApplySinkNode
import cn.xuyinyin.magic.workflow.nodes.sources.MySQLCdcSourceNode
import com.typesafe.config.ConfigFactory
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import spray.json.{JsObject, JsString}

import scala.collection.mutable.ListBuffer
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

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
    "register the built-in MySQL CDC source and apply sink" in {
      NodeRegistry.findSource("mysql.cdc").get shouldBe a[MySQLCdcSourceNode]
      NodeRegistry.findSink("mysql.cdc.apply").get shouldBe a[MySQLCdcApplySinkNode]
    }

    "inject MySQL CDC passwords only into runtime nodes without exposing them" in {
      val secret = "cdc-runtime-secret"
      val environmentLookups = ListBuffer.empty[String]
      val logs = ListBuffer.empty[String]
      val source = new ObservingCdcSource
      val sink = new ObservingCdcSink
      val workflow = WorkflowFixtures.mysqlCdcWorkflow
      val context = ReliableRunContext(
        "exec-mysql-cdc-environment",
        1L,
        None,
        Vector.empty,
        _ => Future.successful(Done),
        _ => Future.successful(Done)
      )
      val engine = new WorkflowExecutionEngine(name => {
        environmentLookups += name
        Some(secret)
      })

      NodeRegistry.registerSource(source)
      NodeRegistry.registerSink(sink)
      try {
        val result = Await.result(engine.execute(workflow, context, message => {
          logs += message
          ()
        }), 5.seconds)

        result.success shouldBe true
        environmentLookups shouldBe ListBuffer("MYSQL_CDC_PASSWORD", "DB_PASSWORD")
        (source.receivedNodes ++ sink.receivedNodes).foreach { node =>
          node.config.fields.get("password") shouldBe Some(JsString(secret))
          node.config.fields should not contain "passwordEnv"
        }
        source.receivedNodes.size shouldBe 2
        sink.receivedNodes.size shouldBe 2
        WorkflowDSL.workflowFormat.write(workflow).compactPrint should not include secret
        result.toString should not include secret
        logs.mkString("\n") should not include secret
      } finally {
        NodeRegistry.unregisterSource(source.nodeType, source)
        NodeRegistry.unregisterSink(sink.nodeType, sink)
      }
    }

    "redact MySQL CDC runtime passwords from connector logs and failure state" in {
      val secret = "cdc-runtime-failure-secret"
      val logs = ListBuffer.empty[String]
      val source = new RuntimeSecretFailingCdcSource
      val sink = new ObservingCdcSink
      val workflow = WorkflowFixtures.mysqlCdcWorkflow
      val context = ReliableRunContext(
        "exec-mysql-cdc-secret-failure",
        1L,
        None,
        Vector.empty,
        _ => Future.successful(Done),
        _ => Future.successful(Done)
      )
      val engine = new WorkflowExecutionEngine(_ => Some(secret))

      NodeRegistry.registerSource(source)
      NodeRegistry.registerSink(sink)
      try {
        val result = Await.result(engine.execute(workflow, context, message => {
          logs += message
          ()
        }), 5.seconds)

        result.success shouldBe false
        result.nodeResults.find(_.nodeId == "source-1").map(_.status) shouldBe Some("failed")
        WorkflowDSL.workflowFormat.write(workflow).compactPrint should not include secret
        result.toString should not include secret
        logs.mkString("\n") should not include secret
      } finally {
        NodeRegistry.unregisterSource(source.nodeType, source)
        NodeRegistry.unregisterSink(sink.nodeType, sink)
      }
    }

    "prepare MySQL environment passwords once per reliable execution without exposing them" in {
      var environmentValue = "runtime-secret"
      val environmentLookups = ListBuffer.empty[String]
      val logs = ListBuffer.empty[String]
      val source = new ObservingMySQLSource(() => environmentValue = "changed-secret")
      val sink = new ObservingMySQLSink
      val workflow = mysqlEnvironmentWorkflow()
      val context = ReliableRunContext(
        "exec-mysql-environment",
        1L,
        None,
        Vector.empty,
        _ => Future.successful(Done),
        _ => Future.successful(Done)
      )
      val engine = new WorkflowExecutionEngine(name => {
        environmentLookups += name
        Some(environmentValue)
      })

      NodeRegistry.registerSource(source)
      NodeRegistry.registerSink(sink)
      try {
        val result = Await.result(engine.execute(workflow, context, message => {
          logs += message
          ()
        }), 5.seconds)

        result.success shouldBe true
        environmentLookups shouldBe ListBuffer("WORKFLOW_DB_PASSWORD", "WORKFLOW_DB_PASSWORD")
        source.receivedNodes.size shouldBe 2
        sink.receivedNodes.size shouldBe 3
        (source.receivedNodes ++ sink.receivedNodes).foreach { node =>
          node.config.fields.get("password") shouldBe Some(JsString("runtime-secret"))
          node.config.fields should not contain "passwordEnv"
        }
        WorkflowDSL.workflowFormat.write(workflow).compactPrint should not include "runtime-secret"
        WorkflowDSL.workflowFormat.write(workflow).compactPrint should not include "changed-secret"
        result.toString should not include "runtime-secret"
        result.toString should not include "changed-secret"
        logs.mkString("\\n") should not include "runtime-secret"
        logs.mkString("\\n") should not include "changed-secret"
      } finally {
        NodeRegistry.unregisterSource(source.nodeType, source)
        NodeRegistry.unregisterSink(sink.nodeType, sink)
      }
    }

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

    "acknowledge a source batch only after sink commit and actor checkpoint persistence" in {
      register(ReliableSource)
      NodeRegistry.registerSink(ReliableSink)
      ReliableSource.batches = Vector(batch("exec-ack-order", 0L, "9", Vector("one")))
      val order = ListBuffer.empty[String]
      ReliableSink.onCommit = _ => order.synchronized(order += "sink")
      ReliableSource.onAcknowledge = _ => {
        order.synchronized(order += "source")
        Future.successful(Done)
      }
      val context = ReliableRunContext(
        "exec-ack-order",
        1L,
        Some(SnapshotBoundary("source-1", "pk-range-0", Some("9"))),
        Vector.empty,
        _ => Future.successful(Done),
        _ => {
          order.synchronized(order += "actor")
          Future.successful(Done)
        }
      )

      val result = Await.result(new WorkflowExecutionEngine().execute(reliableWorkflow(), context, _ => ()), 5.seconds)

      result.success shouldBe true
      order.synchronized(order.toVector) shouldBe Vector("sink", "actor", "source")
    }

    "not acknowledge the source when actor checkpoint persistence fails" in {
      register(ReliableSource)
      NodeRegistry.registerSink(ReliableSink)
      ReliableSource.batches = Vector(batch("exec-actor-failure", 0L, "9", Vector("one")))
      val acknowledged = new AtomicBoolean(false)
      ReliableSource.onAcknowledge = _ => {
        acknowledged.set(true)
        Future.successful(Done)
      }
      val context = ReliableRunContext(
        "exec-actor-failure",
        1L,
        Some(SnapshotBoundary("source-1", "pk-range-0", Some("9"))),
        Vector.empty,
        _ => Future.successful(Done),
        _ => Future.failed(new IllegalStateException("actor checkpoint unavailable"))
      )

      val result = Await.result(new WorkflowExecutionEngine().execute(reliableWorkflow(), context, _ => ()), 5.seconds)

      result.success shouldBe false
      acknowledged.get shouldBe false
      ReliableSink.commits.size shouldBe 1
    }

    "attribute source acknowledgement failure after one sink commit and actor checkpoint" in {
      register(ReliableSource)
      NodeRegistry.registerSink(ReliableSink)
      ReliableSource.batches = Vector(batch("exec-ack-failure", 0L, "9", Vector("one")))
      var actorCheckpoints = 0
      ReliableSource.onAcknowledge = _ => Future.failed(new IllegalStateException("source acknowledgement unavailable"))
      val context = ReliableRunContext(
        "exec-ack-failure",
        1L,
        Some(SnapshotBoundary("source-1", "pk-range-0", Some("9"))),
        Vector.empty,
        _ => Future.successful(Done),
        _ => {
          actorCheckpoints += 1
          Future.successful(Done)
        }
      )

      val result = Await.result(new WorkflowExecutionEngine().execute(reliableWorkflow(), context, _ => ()), 5.seconds)

      result.success shouldBe false
      result.nodeResults.find(_.nodeId == "source-1").map(_.status) shouldBe Some("failed")
      ReliableSink.commits.size shouldBe 1
      actorCheckpoints shouldBe 1
    }

    "validate the source boundary before creating and committing batches" in {
      register(ReliableSource)
      NodeRegistry.registerSink(ReliableSink)
      ReliableSource.batches = Vector(batch("exec-boundary-order", 0L, "9", Vector("one")))
      val order = ListBuffer.empty[String]
      ReliableSink.onReady = () => order.synchronized(order += "sink-ready")
      ReliableSource.onBoundaryDiscovered = _ => order.synchronized(order += "source-boundary")
      ReliableSink.onValidateBoundary = _ => {
        order.synchronized(order += "sink-boundary")
        Future.successful(Done)
      }
      ReliableSink.onCommit = _ => order.synchronized(order += "sink-commit")
      ReliableSource.onAcknowledge = _ => {
        order.synchronized(order += "source-ack")
        Future.successful(Done)
      }
      val context = ReliableRunContext(
        "exec-boundary-order",
        1L,
        None,
        Vector.empty,
        _ => Future.successful(Done),
        _ => {
          order.synchronized(order += "actor")
          Future.successful(Done)
        }
      )

      val result = Await.result(new WorkflowExecutionEngine().execute(reliableWorkflow(), context, _ => ()), 5.seconds)

      result.success shouldBe true
      order.synchronized(order.toVector) shouldBe Vector(
        "sink-ready",
        "source-boundary",
        "sink-boundary",
        "sink-commit",
        "actor",
        "source-ack"
      )
    }

    "attribute boundary validation failure to the sink before creating or committing batches" in {
      register(ReliableSource)
      NodeRegistry.registerSink(ReliableSink)
      ReliableSource.batches = Vector(batch("exec-boundary-failure", 0L, "9", Vector("one")))
      ReliableSink.onValidateBoundary = _ => Future.failed(new IllegalArgumentException("source boundary rejected"))
      val context = ReliableRunContext(
        "exec-boundary-failure",
        1L,
        None,
        Vector.empty,
        _ => Future.successful(Done),
        _ => Future.successful(Done)
      )

      val result = Await.result(new WorkflowExecutionEngine().execute(reliableWorkflow(), context, _ => ()), 5.seconds)

      result.success shouldBe false
      result.nodeResults.find(_.nodeId == "sink-1").map(_.status) shouldBe Some("failed")
      ReliableSource.createCalls shouldBe 0
      ReliableSink.commits shouldBe empty
    }

    "pass the latest source checkpoint to boundary discovery" in {
      register(ReliableSource)
      NodeRegistry.registerSink(ReliableSink)
      val older = checkpoint("exec-previous", 1L, "2", sourceRows = 2L, targetRows = 2L)
      val latest = checkpoint("exec-previous", 3L, "9", sourceRows = 4L, targetRows = 4L)
      val unrelated = latest.copy(sourceNodeId = "other-source", batchSequence = 99L)
      val context = ReliableRunContext(
        "exec-next",
        7L,
        None,
        Vector(latest, unrelated, older),
        _ => Future.successful(Done),
        _ => Future.successful(Done)
      )

      val result = Await.result(new WorkflowExecutionEngine().execute(reliableWorkflow(), context, _ => ()), 5.seconds)

      result.success shouldBe true
      ReliableSource.discoveryResumeFrom shouldBe Some(latest)
    }

    "fail clearly when a checkpoint source omits boundary discovery" in {
      val source = new NodeSource with CheckpointedNodeSource {
        override val nodeType: String = "test.missing-boundary-discovery"

        override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] =
          Source.empty

        override def createBatches(
          node: WorkflowDSL.Node,
          executionId: String,
          boundary: SnapshotBoundary,
          resumeFrom: Option[BatchCheckpoint],
          onLog: String => Unit
        )(implicit blockingEc: ExecutionContext): Source[SourceBatch, NotUsed] = Source.empty
      }

      val failure = intercept[UnsupportedOperationException] {
        Await.result(
          source.discoverBoundary(WorkflowFixtures.linearWorkflow.nodes.head, None, _ => ()),
          2.seconds
        )
      }

      failure.getMessage should include("must implement boundary discovery")
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

    "cancel a reliable execution before stream materialization without creating its source" in {
      val source = new CancellationSource("test.cancel-before-source")
      val readiness = Promise[Done]()
      val sink = new CancellationSink("test.cancel-before-sink", readiness.future)
      val engine = new WorkflowExecutionEngine()
      val context = reliableContext("cancel-before-materialization")
      NodeRegistry.registerSource(source)
      NodeRegistry.registerSink(sink)
      try {
        val execution = engine.execute(cancellationWorkflow(source.nodeType, sink.nodeType), context, _ => ())
        Await.result(sink.readyCalled.future, 2.seconds)

        val cancelled = engine.cancel(context.executionId)
        cancelled.isCompleted shouldBe false
        readiness.success(Done)

        Await.result(cancelled, 2.seconds) shouldBe Done
        Await.result(execution, 2.seconds)
        source.createCalls.get shouldBe 0
        engine.activeReliableExecutionCount shouldBe 0
      } finally {
        NodeRegistry.unregisterSource(source.nodeType, source)
        NodeRegistry.unregisterSink(sink.nodeType, sink)
      }
    }

    "cancel a materialized reliable source exactly once and remove its active control" in {
      val source = new CancellationSource("test.cancel-materialized-source")
      val sink = new CancellationSink("test.cancel-materialized-sink", Future.successful(Done))
      val engine = new WorkflowExecutionEngine()
      val context = reliableContext("cancel-materialized")
      NodeRegistry.registerSource(source)
      NodeRegistry.registerSink(sink)
      try {
        val execution = engine.execute(cancellationWorkflow(source.nodeType, sink.nodeType), context, _ => ())
        Await.result(source.firstMaterialized.future, 2.seconds)

        val first = engine.cancel(context.executionId)
        val second = engine.cancel(context.executionId)

        Await.result(first, 2.seconds) shouldBe Done
        Await.result(second, 2.seconds) shouldBe Done
        Await.result(execution, 2.seconds)
        source.closed.get shouldBe 1
        engine.activeReliableExecutionCount shouldBe 0
      } finally {
        NodeRegistry.unregisterSource(source.nodeType, source)
        NodeRegistry.unregisterSink(sink.nodeType, sink)
      }
    }

    "release an old execution before restarting the same durable execution ID" in {
      val source = new CancellationSource("test.cancel-recovery-source")
      val sink = new CancellationSink("test.cancel-recovery-sink", Future.successful(Done))
      val engine = new WorkflowExecutionEngine()
      val context = reliableContext("same-recovered-execution")
      val workflow = cancellationWorkflow(source.nodeType, sink.nodeType)
      NodeRegistry.registerSource(source)
      NodeRegistry.registerSink(sink)
      try {
        val firstExecution = engine.execute(workflow, context, _ => ())
        awaitCondition("first reliable source materialization") { source.eventsSnapshot.contains("open-1") }

        val recoveredExecution = engine.execute(workflow, context, _ => ())
        awaitCondition("recovered reliable source materialization") { source.eventsSnapshot.contains("open-2") }

        val events = source.eventsSnapshot
        events.indexOf("close-1") should be < events.indexOf("open-2")
        Await.result(firstExecution, 2.seconds)
        Await.result(engine.cancel(context.executionId), 2.seconds) shouldBe Done
        Await.result(recoveredExecution, 2.seconds)
        source.closed.get shouldBe 2
        engine.activeReliableExecutionCount shouldBe 0
      } finally {
        NodeRegistry.unregisterSource(source.nodeType, source)
        NodeRegistry.unregisterSink(sink.nodeType, sink)
      }
    }

    "cancel every queued generation while the old execution is still releasing" in {
      val source = new CancellationSource("test.cancel-queued-source", finite = true)
      val sink = new CancellationSink("test.cancel-queued-sink", Future.successful(Done))
      val engine = new WorkflowExecutionEngine()
      val context = reliableContext("cancel-queued-generations")
      val workflow = cancellationWorkflow(source.nodeType, sink.nodeType)
      val oldReleaseStarted = Promise[Unit]()
      val releaseOld = Promise[Done]()
      NodeRegistry.registerSource(source)
      NodeRegistry.registerSink(sink)
      try {
        val firstExecution = engine.execute(workflow, context, message => {
          if (message.startsWith("工作流执行成功完成")) {
            oldReleaseStarted.trySuccess(())
            Await.result(releaseOld.future, 5.seconds)
          }
          ()
        })
        Await.result(oldReleaseStarted.future, 2.seconds)
        awaitCondition("old source close before holding its control release") {
          source.eventsSnapshot.contains("close-1")
        }

        val firstQueued = engine.execute(workflow, context, _ => ())
        val secondQueued = engine.execute(workflow, context, _ => ())
        engine.activeReliableExecutionCount shouldBe 3

        val cancelled = engine.cancel(context.executionId)
        cancelled.isCompleted shouldBe false
        releaseOld.success(Done)

        Await.result(cancelled, 2.seconds) shouldBe Done
        Await.ready(firstExecution, 2.seconds)
        Await.ready(firstQueued, 2.seconds)
        Await.ready(secondQueued, 2.seconds)
        source.eventsSnapshot should contain("close-1")
        source.eventsSnapshot should not contain "open-2"
        source.createCalls.get shouldBe 1
        engine.activeReliableExecutionCount shouldBe 0
      } finally {
        releaseOld.trySuccess(Done)
        NodeRegistry.unregisterSource(source.nodeType, source)
        NodeRegistry.unregisterSink(sink.nodeType, sink)
      }
    }

    "remove the active execution control after normal completion" in {
      val source = new CancellationSource("test.normal-completion-source", finite = true)
      val sink = new CancellationSink("test.normal-completion-sink", Future.successful(Done))
      val engine = new WorkflowExecutionEngine()
      val context = reliableContext("normal-control-cleanup")
      NodeRegistry.registerSource(source)
      NodeRegistry.registerSink(sink)
      try {
        Await.result(engine.execute(cancellationWorkflow(source.nodeType, sink.nodeType), context, _ => ()), 2.seconds).success shouldBe true

        engine.activeReliableExecutionCount shouldBe 0
        Await.result(engine.cancel(context.executionId), 2.seconds) shouldBe Done
        source.closed.get shouldBe 1
      } finally {
        NodeRegistry.unregisterSource(source.nodeType, source)
        NodeRegistry.unregisterSink(sink.nodeType, sink)
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

  private def mysqlEnvironmentWorkflow(): WorkflowDSL.Workflow = WorkflowFixtures.linearWorkflow.copy(nodes =
    WorkflowFixtures.linearWorkflow.nodes
      .updated(0, WorkflowFixtures.linearWorkflow.nodes.head.copy(
        nodeType = "mysql.snapshot",
        config = JsObject("passwordEnv" -> JsString("WORKFLOW_DB_PASSWORD"))
      ))
      .updated(2, WorkflowFixtures.linearWorkflow.nodes(2).copy(
        nodeType = "mysql.write",
        config = JsObject("passwordEnv" -> JsString("WORKFLOW_DB_PASSWORD"))
      ))
  )

  private val legacyCapableSinkWorkflow: WorkflowDSL.Workflow = WorkflowFixtures.linearWorkflow.copy(nodes =
    WorkflowFixtures.linearWorkflow.nodes
      .updated(0, WorkflowFixtures.linearWorkflow.nodes.head.copy(nodeType = LegacySource.nodeType))
      .updated(2, WorkflowFixtures.linearWorkflow.nodes(2).copy(nodeType = CapableLegacySink.nodeType))
  )

  private def cancellationWorkflow(sourceType: String, sinkType: String): WorkflowDSL.Workflow =
    WorkflowFixtures.linearWorkflow.copy(nodes =
      WorkflowFixtures.linearWorkflow.nodes
        .updated(0, WorkflowFixtures.linearWorkflow.nodes.head.copy(nodeType = sourceType))
        .updated(2, WorkflowFixtures.linearWorkflow.nodes(2).copy(nodeType = sinkType))
    )

  private def reliableContext(executionId: String): ReliableRunContext = ReliableRunContext(
    executionId,
    1L,
    None,
    Vector.empty,
    _ => Future.successful(Done),
    _ => Future.successful(Done)
  )

  private def awaitCondition(clue: String)(condition: => Boolean): Unit = {
    val deadline = 3.seconds.fromNow
    while (deadline.hasTimeLeft() && !condition) Thread.sleep(10)
    withClue(clue)(condition shouldBe true)
  }

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

  private final class ObservingMySQLSource(onDiscover: () => Unit) extends NodeSource with CheckpointedNodeSource {
    override val nodeType: String = "mysql.snapshot"
    var receivedNodes = Vector.empty[WorkflowDSL.Node]

    override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] =
      throw new AssertionError("reliable source used the legacy row stream")

    override def discoverBoundary(
      node: WorkflowDSL.Node,
      resumeFrom: Option[BatchCheckpoint],
      onLog: String => Unit
    )(implicit blockingEc: ExecutionContext): Future[SnapshotBoundary] = {
      receivedNodes :+= node
      onDiscover()
      Future.successful(SnapshotBoundary(node.id, "pk-range-0", Some("2")))
    }

    override def createBatches(
      node: WorkflowDSL.Node,
      executionId: String,
      boundary: SnapshotBoundary,
      resume: Option[BatchCheckpoint],
      onLog: String => Unit
    )(implicit blockingEc: ExecutionContext): Source[SourceBatch, NotUsed] = {
      receivedNodes :+= node
      Source(Vector(
        batch(executionId, 0L, "1", Vector("one")),
        batch(executionId, 1L, "2", Vector("two"))
      ))
    }
  }

  private final class ObservingCdcSource extends NodeSource with CheckpointedNodeSource {
    override val nodeType: String = "mysql.cdc"
    var receivedNodes = Vector.empty[WorkflowDSL.Node]

    override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] =
      throw new AssertionError("CDC source used the legacy row stream")

    override def discoverBoundary(
      node: WorkflowDSL.Node,
      resumeFrom: Option[BatchCheckpoint],
      onLog: String => Unit
    )(implicit blockingEc: ExecutionContext): Future[SnapshotBoundary] = {
      receivedNodes :+= node
      Future.successful(SnapshotBoundary(node.id, "mysql-cdc:orders-cdc-v1", Some("stream-v1")))
    }

    override def createBatches(
      node: WorkflowDSL.Node,
      executionId: String,
      boundary: SnapshotBoundary,
      resume: Option[BatchCheckpoint],
      onLog: String => Unit
    )(implicit blockingEc: ExecutionContext): Source[SourceBatch, NotUsed] = {
      receivedNodes :+= node
      Source.empty
    }
  }

  private final class RuntimeSecretFailingCdcSource extends NodeSource with CheckpointedNodeSource {
    override val nodeType: String = "mysql.cdc"

    override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] =
      throw new AssertionError("CDC source used the legacy row stream")

    override def discoverBoundary(
      node: WorkflowDSL.Node,
      resumeFrom: Option[BatchCheckpoint],
      onLog: String => Unit
    )(implicit blockingEc: ExecutionContext): Future[SnapshotBoundary] = {
      val secret = node.config.fields("password") match {
        case JsString(value) => value
        case other => throw new AssertionError(s"expected runtime password, got $other")
      }
      onLog(s"connector diagnostic includes $secret")
      Future.failed(new IllegalStateException(s"connector failed with $secret"))
    }

    override def createBatches(
      node: WorkflowDSL.Node,
      executionId: String,
      boundary: SnapshotBoundary,
      resume: Option[BatchCheckpoint],
      onLog: String => Unit
    )(implicit blockingEc: ExecutionContext): Source[SourceBatch, NotUsed] = Source.empty
  }

  private final class ObservingCdcSink extends NodeSink with CheckpointedNodeSink {
    override val nodeType: String = "mysql.cdc.apply"
    var receivedNodes = Vector.empty[WorkflowDSL.Node]

    override def createSink(node: WorkflowDSL.Node, onLog: String => Unit)(implicit ec: ExecutionContext): Sink[String, Future[Done]] =
      throw new AssertionError("CDC sink used the legacy row stream")

    override def validateReady(
      node: WorkflowDSL.Node,
      onLog: String => Unit
    )(implicit blockingEc: ExecutionContext): Future[Done] = {
      receivedNodes :+= node
      Future.successful(Done)
    }

    override def validateSourceBoundary(
      node: WorkflowDSL.Node,
      boundary: SnapshotBoundary,
      onLog: String => Unit
    )(implicit blockingEc: ExecutionContext): Future[Done] = {
      receivedNodes :+= node
      Future.successful(Done)
    }

    override def commitBatch(
      node: WorkflowDSL.Node,
      workflowId: String,
      executionId: String,
      batch: SourceBatch,
      transformedRows: Vector[String],
      onLog: String => Unit
    )(implicit blockingEc: ExecutionContext): Future[BatchCommitResult] =
      Future.failed(new AssertionError("empty CDC source committed a batch"))
  }

  private final class ObservingMySQLSink extends NodeSink with CheckpointedNodeSink {
    override val nodeType: String = "mysql.write"
    var receivedNodes = Vector.empty[WorkflowDSL.Node]

    override def createSink(node: WorkflowDSL.Node, onLog: String => Unit)(implicit ec: ExecutionContext): Sink[String, Future[Done]] =
      throw new AssertionError("reliable sink used the legacy row stream")

    override def validateReady(node: WorkflowDSL.Node, onLog: String => Unit)(implicit blockingEc: ExecutionContext): Future[Done] = {
      receivedNodes :+= node
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
      receivedNodes :+= node
      Future.successful(Committed(BatchCheckpoint(
        value.sourceNodeId,
        value.partitionId,
        value.batchSequence,
        value.batchId,
        value.cursor,
        value.rows.size.toLong,
        transformedRows.size.toLong
      )))
    }
  }

  private final class CancellationSource(
    override val nodeType: String,
    finite: Boolean = false
  ) extends NodeSource with CheckpointedNodeSource {
    val createCalls = new AtomicInteger(0)
    val closed = new AtomicInteger(0)
    val firstMaterialized = Promise[Unit]()
    private val events = ListBuffer.empty[String]

    def eventsSnapshot: Vector[String] = events.synchronized(events.toVector)

    override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] =
      throw new AssertionError("cancellation source used the legacy row stream")

    override def discoverBoundary(
      node: WorkflowDSL.Node,
      resumeFrom: Option[BatchCheckpoint],
      onLog: String => Unit
    )(implicit blockingEc: ExecutionContext): Future[SnapshotBoundary] =
      Future.successful(SnapshotBoundary(node.id, "cancel-partition", Some("cancel-boundary")))

    override def createBatches(
      node: WorkflowDSL.Node,
      executionId: String,
      boundary: SnapshotBoundary,
      resume: Option[BatchCheckpoint],
      onLog: String => Unit
    )(implicit blockingEc: ExecutionContext): Source[SourceBatch, NotUsed] = {
      val generation = createCalls.incrementAndGet()
      val batches = if (finite) Source.empty[SourceBatch] else Source.maybe[SourceBatch]
      batches.watchTermination() { (_, termination) =>
        events.synchronized(events += s"open-$generation")
        firstMaterialized.trySuccess(())
        termination.onComplete { _ =>
          events.synchronized(events += s"close-$generation")
          closed.incrementAndGet()
        }(blockingEc)
        NotUsed
      }
    }
  }

  private final class CancellationSink(
    override val nodeType: String,
    readiness: Future[Done]
  ) extends NodeSink with CheckpointedNodeSink {
    val readyCalled = Promise[Unit]()

    override def createSink(node: WorkflowDSL.Node, onLog: String => Unit)(implicit ec: ExecutionContext): Sink[String, Future[Done]] =
      throw new AssertionError("cancellation sink used the legacy row stream")

    override def validateReady(
      node: WorkflowDSL.Node,
      onLog: String => Unit
    )(implicit blockingEc: ExecutionContext): Future[Done] = {
      readyCalled.trySuccess(())
      readiness
    }

    override def commitBatch(
      node: WorkflowDSL.Node,
      workflowId: String,
      executionId: String,
      batch: SourceBatch,
      transformedRows: Vector[String],
      onLog: String => Unit
    )(implicit blockingEc: ExecutionContext): Future[BatchCommitResult] =
      Future.failed(new AssertionError("cancellation source unexpectedly emitted a batch"))
  }

  private object ReliableSource extends NodeSource with CheckpointedNodeSource {
    override val nodeType: String = "test.checkpoint-source"
    @volatile var batches: Vector[SourceBatch] = Vector.empty
    @volatile var resumeFrom: Option[BatchCheckpoint] = None
    @volatile var discoveryResumeFrom: Option[BatchCheckpoint] = None
    @volatile var discoveries: Int = 0
    @volatile var createCalls: Int = 0
    @volatile var onBoundaryDiscovered: SnapshotBoundary => Unit = _ => ()
    @volatile var onAcknowledge: SourceBatch => Future[Done] = _ => Future.successful(Done)

    def reset(): Unit = {
      batches = Vector.empty
      resumeFrom = None
      discoveryResumeFrom = None
      discoveries = 0
      createCalls = 0
      onBoundaryDiscovered = _ => ()
      onAcknowledge = _ => Future.successful(Done)
      ReliableEvents.clear()
    }

    override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] =
      throw new AssertionError("reliable source used the legacy row stream")

    override def discoverBoundary(
      node: WorkflowDSL.Node,
      resumeFrom: Option[BatchCheckpoint],
      onLog: String => Unit
    )(implicit blockingEc: ExecutionContext): Future[SnapshotBoundary] = {
      discoveries += 1
      discoveryResumeFrom = resumeFrom
      ReliableEvents.add("discover-boundary")
      val boundary = SnapshotBoundary(node.id, "pk-range-0", Some("9"))
      onBoundaryDiscovered(boundary)
      Future.successful(boundary)
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

    override def acknowledgeCommittedBatch(
      node: WorkflowDSL.Node,
      value: SourceBatch,
      onLog: String => Unit
    )(implicit blockingEc: ExecutionContext): Future[Done] = onAcknowledge(value)
  }

  private object ReliableSink extends NodeSink with CheckpointedNodeSink {
    override val nodeType: String = "test.checkpoint-sink"
    @volatile var readinessFailure: Option[Throwable] = None
    @volatile var commits: Vector[(SourceBatch, Vector[String])] = Vector.empty
    @volatile var targetRowsBySequence: Map[Long, Long] = Map.empty
    @volatile var onReady: () => Unit = () => ()
    @volatile var onValidateBoundary: SnapshotBoundary => Future[Done] = _ => Future.successful(Done)
    @volatile var onCommit: SourceBatch => Unit = _ => ()

    def reset(): Unit = {
      readinessFailure = None
      commits = Vector.empty
      targetRowsBySequence = Map.empty
      onReady = () => ()
      onValidateBoundary = _ => Future.successful(Done)
      onCommit = _ => ()
    }

    override def createSink(node: WorkflowDSL.Node, onLog: String => Unit)(implicit ec: ExecutionContext): Sink[String, Future[Done]] =
      throw new AssertionError("reliable sink used the legacy row stream")

    override def validateReady(node: WorkflowDSL.Node, onLog: String => Unit)(implicit blockingEc: ExecutionContext): Future[Done] = {
      ReliableEvents.add("validate")
      onReady()
      readinessFailure.fold(Future.successful(Done))(Future.failed)
    }

    override def validateSourceBoundary(
      node: WorkflowDSL.Node,
      boundary: SnapshotBoundary,
      onLog: String => Unit
    )(implicit blockingEc: ExecutionContext): Future[Done] = onValidateBoundary(boundary)

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
      onCommit(value)
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
