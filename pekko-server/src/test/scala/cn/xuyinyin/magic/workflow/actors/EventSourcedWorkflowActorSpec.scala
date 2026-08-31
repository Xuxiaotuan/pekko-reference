package cn.xuyinyin.magic.workflow.engine {
  import cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry
  import cn.xuyinyin.magic.workflow.nodes.base.NodeSource

  private[workflow] object ActorSpecNodeRegistryCleanup {
    def unregister(source: NodeSource): Unit = NodeRegistry.unregisterSource(source.nodeType, source)
  }
}

package cn.xuyinyin.magic.workflow.actors {

import cn.xuyinyin.magic.workflow.WorkflowFixtures
import cn.xuyinyin.magic.workflow.checkpoint.{BatchCheckpoint, BatchId, SnapshotBoundary, SourceBatch, SourceCursor}
import cn.xuyinyin.magic.workflow.engine.{ExecutionResult, NodeExecutionResult, ReliableRunContext, WorkflowExecutionEngine}
import cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.events.WorkflowEvents._
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base.{CheckpointedNodeSource, NodeSource}
import cn.xuyinyin.magic.workflow.nodes.sources.KafkaCheckpointCodec
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.SerializationTestKit
import org.apache.pekko.stream.scaladsl.Source
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues
import org.scalatest.wordspec.AnyWordSpecLike

import java.sql.DriverManager
import scala.collection.mutable
import scala.io.{Source => IoSource}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import spray.json._

object EventSourcedWorkflowActorSpec {
  private val h2Url = s"jdbc:h2:mem:workflow-actor-${java.util.UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1"

  /** The JDBC journal never creates tables itself; initialize Pekko's official H2 schema before the ActorSystem. */
  def config: Config = synchronized {
    Class.forName("org.h2.Driver")
    val connection = DriverManager.getConnection(h2Url)
    try {
      val statement = connection.createStatement()
      try {
        statement.execute("DROP ALL OBJECTS")
        val input = Option(getClass.getClassLoader.getResourceAsStream("schema/h2/h2-create-schema.sql"))
          .getOrElse(throw new IllegalStateException("Pekko Persistence JDBC H2 schema resource is unavailable"))
        val sql = try IoSource.fromInputStream(input).mkString finally input.close()
        sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(statement.execute)
      } finally statement.close()
    } finally connection.close()
    ConfigFactory.parseString(
      s"""pekko-persistence-jdbc.shared-databases.slick.db.url = \"$h2Url\""""
    ).withFallback(ConfigFactory.load("application-test"))
  }
}

class EventSourcedWorkflowActorSpec
    extends ScalaTestWithActorTestKit(EventSourcedWorkflowActorSpec.config)
    with AnyWordSpecLike
    with OptionValues
    with Matchers {

  private implicit val executionContext: scala.concurrent.ExecutionContext = system.executionContext

  private object TestSnapshotSource extends NodeSource with CheckpointedNodeSource {
    override val nodeType: String = "mysql.snapshot"
    override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = Source.empty
    override def discoverBoundary(node: WorkflowDSL.Node, onLog: String => Unit)(implicit blockingEc: ExecutionContext): Future[SnapshotBoundary] =
      Future.failed(new AssertionError("actor checkpoint test must not execute the source"))
    override def createBatches(
      node: WorkflowDSL.Node,
      executionId: String,
      boundary: SnapshotBoundary,
      resume: Option[BatchCheckpoint],
      onLog: String => Unit
    )(implicit blockingEc: ExecutionContext): Source[SourceBatch, NotUsed] =
      Source.failed(new AssertionError("actor checkpoint test must not execute the source"))
  }

  private object TestKafkaSource extends NodeSource with CheckpointedNodeSource {
    override val nodeType: String = "kafka.consumer"
    override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = Source.empty
    override def discoverBoundary(node: WorkflowDSL.Node, onLog: String => Unit)(implicit blockingEc: ExecutionContext): Future[SnapshotBoundary] =
      Future.failed(new AssertionError("actor Kafka checkpoint test must not execute the source"))
    override def createBatches(
      node: WorkflowDSL.Node,
      executionId: String,
      boundary: SnapshotBoundary,
      resume: Option[BatchCheckpoint],
      onLog: String => Unit
    )(implicit blockingEc: ExecutionContext): Source[SourceBatch, NotUsed] =
      Source.failed(new AssertionError("actor Kafka checkpoint test must not execute the source"))
  }

  private val reliableWorkflow = WorkflowFixtures.linearWorkflow.copy(nodes =
    WorkflowFixtures.linearWorkflow.nodes
      .updated(0, WorkflowFixtures.linearWorkflow.nodes.head.copy(nodeType = "mysql.snapshot"))
      .updated(2, WorkflowFixtures.linearWorkflow.nodes(2).copy(nodeType = "mysql.write"))
  )

  private val kafkaWorkflow = reliableWorkflow.copy(nodes =
    reliableWorkflow.nodes.updated(0, reliableWorkflow.nodes.head.copy(nodeType = TestKafkaSource.nodeType))
  )

  private def engineReturning(result: ExecutionResult): WorkflowExecutionEngine = new WorkflowExecutionEngine() {
    override def execute(workflow: cn.xuyinyin.magic.workflow.model.WorkflowDSL.Workflow, executionId: String, onLog: String => Unit): Future[ExecutionResult] =
      Future.successful(result)
  }

  private def define(entity: org.apache.pekko.actor.typed.ActorRef[EventSourcedWorkflowActor.Command], workflowId: String): Unit = {
    val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()
    entity ! EventSourcedWorkflowActor.DefineWorkflow(WorkflowFixtures.linearWorkflow, expectedRevision = 0L, reply.ref)
    reply.expectMessage(EventSourcedWorkflowActor.Defined(workflowId, revision = 1L))
  }

  private val succeeded = ExecutionResult("completed", success = true, "done", Some(1), Some(1L))
  private val failed = ExecutionResult("failed", success = false, "sink failed", None, Some(1L))

  private def eventuallySummary(
    entity: org.apache.pekko.actor.typed.ActorRef[EventSourcedWorkflowActor.Command]
  )(assertion: EventSourcedWorkflowActor.WorkflowSummary => Unit): Unit = {
    val deadline = 5.seconds.fromNow
    var lastFailure: Option[Throwable] = None
    while (deadline.hasTimeLeft()) {
      val summary = createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
      entity ! EventSourcedWorkflowActor.GetSummary(summary.ref)
      try {
        assertion(summary.receiveMessage(500.millis))
        return
      } catch {
        case error: Throwable => lastFailure = Some(error); Thread.sleep(50)
      }
    }
    throw lastFailure.getOrElse(new AssertionError("summary did not reach the expected state"))
  }

  "EventSourcedWorkflowActor" should {
    "round-trip public replies containing small Long option values" in {
      val serialization = new SerializationTestKit(system)
      val commandReply = createTestProbe[EventSourcedWorkflowActor.Reply]()
      val define = serialization.verifySerialization(
        EventSourcedWorkflowActor.DefineWorkflow(WorkflowFixtures.linearWorkflow, 0L, commandReply.ref)
      )
      import cn.xuyinyin.magic.workflow.model.WorkflowDSL.workflowFormat
      define.workflowJson.parseJson.convertTo[cn.xuyinyin.magic.workflow.model.WorkflowDSL.Workflow] shouldBe WorkflowFixtures.linearWorkflow

      val summary = serialization.verifySerialization(EventSourcedWorkflowActor.ExecutionSummary("execution", 1L, Some(2L), "completed", Some(1L)))
      summary.endTime shouldBe Some(2L)
      summary.duration shouldBe Some(1L)

      val info = serialization.verifySerialization(EventSourcedWorkflowActor.ExecutionInfo("execution", 1L, Some(2L), "completed", 1, 1))
      info.endTime shouldBe Some(2L)
      val status = serialization.verifySerialization(EventSourcedWorkflowActor.StatusResponse("workflow", "completed", Some(info), List(summary)))
      status.currentExecution.flatMap(_.endTime) shouldBe Some(2L)
      status.allExecutions.head.endTime shouldBe Some(2L)

      val node = EventSourcedWorkflowActor.NodeExecutionDetail("node", "sink", Some(1L), Some(2L), Some(1L), "completed", Some(1), None)
      val history = serialization.verifySerialization(EventSourcedWorkflowActor.ExecutionHistoryResponse("workflow", List(EventSourcedWorkflowActor.ExecutionDetail("execution", "workflow", 1L, Some(2L), "completed", Some(1L), List(node)))))
      history.executions.head.duration shouldBe Some(1L)
      history.executions.head.nodes.head.endTime shouldBe Some(2L)
      history.executions.head.nodes.head.duration shouldBe Some(1L)
    }

    "round-trip resumable events, checkpoint commands, and deterministic replies" in {
      val serialization = new SerializationTestKit(system)
      val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()
      val trigger = ExecutionTrigger("manual", requestId = Some("request-1"))
      val boundary = SnapshotBoundary("source-1", "pk-range-0", Some("18446744073709551615"))
      val checkpoint = BatchCheckpoint(
        "source-1",
        "pk-range-0",
        0L,
        "batch-0",
        SourceCursor("mysql.numeric-pk", "1009", "18446744073709551615"),
        10L,
        8L
      )

      serialization.verifySerialization(ResumableExecutionStarted("execution-1", trigger, 7L, 10L)) shouldBe
        ResumableExecutionStarted("execution-1", trigger, 7L, 10L)
      serialization.verifySerialization(ExecutionSnapshotInitialized("execution-1", boundary, 11L)) shouldBe
        ExecutionSnapshotInitialized("execution-1", boundary, 11L)
      serialization.verifySerialization(ExecutionCheckpointAdvanced("execution-1", checkpoint, 12L)) shouldBe
        ExecutionCheckpointAdvanced("execution-1", checkpoint, 12L)

      serialization.verifySerialization(EventSourcedWorkflowActor.InitializeSnapshot("execution-1", boundary, reply.ref)).boundary shouldBe boundary
      serialization.verifySerialization(EventSourcedWorkflowActor.AdvanceCheckpoint("execution-1", checkpoint, reply.ref)).checkpoint shouldBe checkpoint
      serialization.verifySerialization(EventSourcedWorkflowActor.SnapshotInitialized(boundary)) shouldBe EventSourcedWorkflowActor.SnapshotInitialized(boundary)
      serialization.verifySerialization(EventSourcedWorkflowActor.SnapshotAlreadyInitialized(boundary)) shouldBe EventSourcedWorkflowActor.SnapshotAlreadyInitialized(boundary)
      serialization.verifySerialization(EventSourcedWorkflowActor.CheckpointAccepted(checkpoint)) shouldBe EventSourcedWorkflowActor.CheckpointAccepted(checkpoint)
      serialization.verifySerialization(EventSourcedWorkflowActor.CheckpointAlreadyStored(checkpoint)) shouldBe EventSourcedWorkflowActor.CheckpointAlreadyStored(checkpoint)
      serialization.verifySerialization(EventSourcedWorkflowActor.CheckpointRejected("sequence gap")) shouldBe EventSourcedWorkflowActor.CheckpointRejected("sequence gap")
    }

    "persist one immutable boundary and monotonically advance reliable checkpoints" in {
      NodeRegistry.registerSource(TestSnapshotSource)
      try {
        val completion = Promise[ExecutionResult]()
        val pendingEngine = new WorkflowExecutionEngine() {
          override def execute(workflow: WorkflowDSL.Workflow, executionId: String, onLog: String => Unit): Future[ExecutionResult] = completion.future
          override def execute(workflow: WorkflowDSL.Workflow, runContext: ReliableRunContext, onLog: String => Unit): Future[ExecutionResult] = completion.future
        }
        val workflowId = "reliable-checkpoints"
        val entity = spawn(EventSourcedWorkflowActor(workflowId, pendingEngine), workflowId)
        val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()
        entity ! EventSourcedWorkflowActor.DefineWorkflow(reliableWorkflow, 0L, reply.ref)
        reply.expectMessage(EventSourcedWorkflowActor.Defined(workflowId, 1L))
        entity ! EventSourcedWorkflowActor.ExecuteManual("request-1", reply.ref)
        val accepted = reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]

        entity ! EventSourcedWorkflowActor.DefineWorkflow(reliableWorkflow, 1L, reply.ref)
        reply.expectMessage(EventSourcedWorkflowActor.AlreadyRunning(accepted.executionId))

        val boundary = SnapshotBoundary("source-1", "pk-range-0", Some("18446744073709551615"))
        val conflictingBoundary = boundary.copy(upperBound = Some("18446744073709551614"))
        entity ! EventSourcedWorkflowActor.InitializeSnapshot(accepted.executionId, boundary, reply.ref)
        reply.expectMessage(EventSourcedWorkflowActor.SnapshotInitialized(boundary))
        entity ! EventSourcedWorkflowActor.InitializeSnapshot(accepted.executionId, boundary, reply.ref)
        reply.expectMessage(EventSourcedWorkflowActor.SnapshotAlreadyInitialized(boundary))
        entity ! EventSourcedWorkflowActor.InitializeSnapshot(accepted.executionId, conflictingBoundary, reply.ref)
        reply.expectMessageType[EventSourcedWorkflowActor.CheckpointRejected]

        val sequence0 = BatchCheckpoint(
          "source-1",
          "pk-range-0",
          0L,
          BatchId.sha256(accepted.executionId, "source-1", "pk-range-0", 0L),
          SourceCursor("mysql.numeric-pk", "1009", "18446744073709551615"),
          10L,
          8L
        )
        entity ! EventSourcedWorkflowActor.AdvanceCheckpoint(accepted.executionId, sequence0, reply.ref)
        reply.expectMessage(EventSourcedWorkflowActor.CheckpointAccepted(sequence0))
        entity ! EventSourcedWorkflowActor.AdvanceCheckpoint(accepted.executionId, sequence0, reply.ref)
        reply.expectMessage(EventSourcedWorkflowActor.CheckpointAlreadyStored(sequence0))
        entity ! EventSourcedWorkflowActor.AdvanceCheckpoint(accepted.executionId, sequence0.copy(batchSequence = 2L, batchId = "batch-2"), reply.ref)
        reply.expectMessageType[EventSourcedWorkflowActor.CheckpointRejected]
        entity ! EventSourcedWorkflowActor.AdvanceCheckpoint(accepted.executionId, sequence0.copy(batchId = "conflicting-batch"), reply.ref)
        reply.expectMessageType[EventSourcedWorkflowActor.CheckpointRejected]
        entity ! EventSourcedWorkflowActor.AdvanceCheckpoint("wrong-execution", sequence0, reply.ref)
        reply.expectMessageType[EventSourcedWorkflowActor.CheckpointRejected]
        entity ! EventSourcedWorkflowActor.AdvanceCheckpoint(
          accepted.executionId,
          sequence0.copy(cursor = sequence0.cursor.copy(upperBound = "18446744073709551614")),
          reply.ref
        )
        reply.expectMessageType[EventSourcedWorkflowActor.CheckpointRejected]

        val sequence1 = sequence0.copy(
          batchSequence = 1L,
          batchId = BatchId.sha256(accepted.executionId, "source-1", "pk-range-0", 1L),
          cursor = sequence0.cursor.copy(value = "1019")
        )
        entity ! EventSourcedWorkflowActor.AdvanceCheckpoint(accepted.executionId, sequence1, reply.ref)
        reply.expectMessage(EventSourcedWorkflowActor.CheckpointAccepted(sequence1))
        entity ! EventSourcedWorkflowActor.AdvanceCheckpoint(accepted.executionId, sequence0, reply.ref)
        reply.expectMessageType[EventSourcedWorkflowActor.CheckpointRejected]
        entity ! EventSourcedWorkflowActor.AdvanceCheckpoint(accepted.executionId, sequence0.copy(batchId = "conflicting-batch"), reply.ref)
        reply.expectMessageType[EventSourcedWorkflowActor.CheckpointRejected]
        entity ! EventSourcedWorkflowActor.AdvanceCheckpoint(
          accepted.executionId,
          sequence1.copy(cursor = sequence1.cursor.copy(value = "1018")),
          reply.ref
        )
        reply.expectMessageType[EventSourcedWorkflowActor.CheckpointRejected]

        val sequence2WithInvalidIdentity = sequence1.copy(
          batchSequence = 2L,
          batchId = "invalid-deterministic-identity",
          cursor = sequence1.cursor.copy(value = "1029")
        )
        entity ! EventSourcedWorkflowActor.AdvanceCheckpoint(accepted.executionId, sequence2WithInvalidIdentity, reply.ref)
        reply.expectMessageType[EventSourcedWorkflowActor.CheckpointRejected]

        val reliableState = createTestProbe[EventSourcedWorkflowActor.ReliableRunState]()
        entity ! EventSourcedWorkflowActor.GetReliableRunState(reliableState.ref)
        val running = reliableState.receiveMessage().currentExecution.value
        running.executionId shouldBe accepted.executionId
        running.workflowRevision shouldBe 1L
        running.resumable shouldBe true
        running.boundary shouldBe Some(boundary)
        running.checkpoints shouldBe Vector(sequence1)
      } finally cn.xuyinyin.magic.workflow.engine.ActorSpecNodeRegistryCleanup.unregister(TestSnapshotSource)
    }

    "carry an accepted Kafka checkpoint from a failed execution into the next run" in {
      NodeRegistry.registerSource(TestKafkaSource)
      val firstCompletion = Promise[ExecutionResult]()
      val secondCompletion = Promise[ExecutionResult]()
      val completions = mutable.Queue(firstCompletion, secondCompletion)
      val pendingEngine = new WorkflowExecutionEngine() {
        private def nextResult(): Future[ExecutionResult] = completions.dequeue().future
        override def execute(workflow: WorkflowDSL.Workflow, executionId: String, onLog: String => Unit): Future[ExecutionResult] =
          nextResult()
        override def execute(workflow: WorkflowDSL.Workflow, runContext: ReliableRunContext, onLog: String => Unit): Future[ExecutionResult] =
          nextResult()
      }
      val actor = spawn(EventSourcedWorkflowActor("kafka-progress", pendingEngine), "kafka-progress")
      val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()

      try {
        actor ! EventSourcedWorkflowActor.DefineWorkflow(kafkaWorkflow, expectedRevision = 0L, reply.ref)
        reply.expectMessage(EventSourcedWorkflowActor.Defined("kafka-progress", revision = 1L))
        actor ! EventSourcedWorkflowActor.ExecuteManual("run-1", reply.ref)
        val first = reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]

        val frozenBoundary = "{\"0\":50}"
        val boundary = SnapshotBoundary("source-1", "kafka-boundary-1", Some(frozenBoundary))
        actor ! EventSourcedWorkflowActor.InitializeSnapshot(first.executionId, boundary, reply.ref)
        reply.expectMessage(EventSourcedWorkflowActor.SnapshotInitialized(boundary))
        val checkpoint = BatchCheckpoint(
          sourceNodeId = "source-1",
          partitionId = "kafka-boundary-1",
          batchSequence = 0L,
          batchId = BatchId.sha256(first.executionId, "source-1", "kafka-boundary-1", 0L),
          cursor = SourceCursor(KafkaCheckpointCodec.CursorKind, "{\"0\":30}", frozenBoundary),
          sourceRowsScanned = 10L,
          targetRowsWritten = 10L
        )
        actor ! EventSourcedWorkflowActor.AdvanceCheckpoint(first.executionId, checkpoint, reply.ref)
        reply.expectMessage(EventSourcedWorkflowActor.CheckpointAccepted(checkpoint))
        firstCompletion.success(failed)
        eventuallySummary(actor)(_.status shouldBe EventSourcedWorkflowActor.Failed)

        actor ! EventSourcedWorkflowActor.ExecuteManual("run-2", reply.ref)
        val second = reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
        second.executionId should not be first.executionId
        val stateProbe = createTestProbe[EventSourcedWorkflowActor.ReliableRunState]()
        actor ! EventSourcedWorkflowActor.GetReliableRunState(stateProbe.ref)
        stateProbe.receiveMessage().currentExecution.value.checkpoints shouldBe Vector(checkpoint)
      } finally {
        firstCompletion.trySuccess(failed)
        secondCompletion.trySuccess(succeeded)
        testKit.stop(actor)
        cn.xuyinyin.magic.workflow.engine.ActorSpecNodeRegistryCleanup.unregister(TestKafkaSource)
      }
    }

    "keep a MySQL checkpoint scoped to its execution" in {
      NodeRegistry.registerSource(TestSnapshotSource)
      val firstCompletion = Promise[ExecutionResult]()
      val secondCompletion = Promise[ExecutionResult]()
      val completions = mutable.Queue(firstCompletion, secondCompletion)
      val pendingEngine = new WorkflowExecutionEngine() {
        private def nextResult(): Future[ExecutionResult] = completions.dequeue().future
        override def execute(workflow: WorkflowDSL.Workflow, executionId: String, onLog: String => Unit): Future[ExecutionResult] =
          nextResult()
        override def execute(workflow: WorkflowDSL.Workflow, runContext: ReliableRunContext, onLog: String => Unit): Future[ExecutionResult] =
          nextResult()
      }
      val actor = spawn(EventSourcedWorkflowActor("mysql-execution-progress", pendingEngine), "mysql-execution-progress")
      val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()

      try {
        actor ! EventSourcedWorkflowActor.DefineWorkflow(reliableWorkflow, expectedRevision = 0L, reply.ref)
        reply.expectMessage(EventSourcedWorkflowActor.Defined("mysql-execution-progress", revision = 1L))
        actor ! EventSourcedWorkflowActor.ExecuteManual("run-1", reply.ref)
        val first = reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]

        val boundary = SnapshotBoundary("source-1", "pk-range-0", Some("50"))
        actor ! EventSourcedWorkflowActor.InitializeSnapshot(first.executionId, boundary, reply.ref)
        reply.expectMessage(EventSourcedWorkflowActor.SnapshotInitialized(boundary))
        val checkpoint = BatchCheckpoint(
          "source-1",
          "pk-range-0",
          0L,
          BatchId.sha256(first.executionId, "source-1", "pk-range-0", 0L),
          SourceCursor("mysql.numeric-pk", "30", "50"),
          10L,
          10L
        )
        actor ! EventSourcedWorkflowActor.AdvanceCheckpoint(first.executionId, checkpoint, reply.ref)
        reply.expectMessage(EventSourcedWorkflowActor.CheckpointAccepted(checkpoint))
        firstCompletion.success(failed)
        eventuallySummary(actor)(_.status shouldBe EventSourcedWorkflowActor.Failed)

        actor ! EventSourcedWorkflowActor.ExecuteManual("run-2", reply.ref)
        reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
        val stateProbe = createTestProbe[EventSourcedWorkflowActor.ReliableRunState]()
        actor ! EventSourcedWorkflowActor.GetReliableRunState(stateProbe.ref)
        stateProbe.receiveMessage().currentExecution.value.checkpoints shouldBe empty
      } finally {
        firstCompletion.trySuccess(failed)
        secondCompletion.trySuccess(succeeded)
        testKit.stop(actor)
        cn.xuyinyin.magic.workflow.engine.ActorSpecNodeRegistryCleanup.unregister(TestSnapshotSource)
      }
    }

    "clear Kafka progress when a new workflow revision is defined" in {
      NodeRegistry.registerSource(TestKafkaSource)
      val firstCompletion = Promise[ExecutionResult]()
      val secondCompletion = Promise[ExecutionResult]()
      val completions = mutable.Queue(firstCompletion, secondCompletion)
      val pendingEngine = new WorkflowExecutionEngine() {
        private def nextResult(): Future[ExecutionResult] = completions.dequeue().future
        override def execute(workflow: WorkflowDSL.Workflow, executionId: String, onLog: String => Unit): Future[ExecutionResult] =
          nextResult()
        override def execute(workflow: WorkflowDSL.Workflow, runContext: ReliableRunContext, onLog: String => Unit): Future[ExecutionResult] =
          nextResult()
      }
      val actor = spawn(EventSourcedWorkflowActor("kafka-progress-revision", pendingEngine), "kafka-progress-revision")
      val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()

      try {
        actor ! EventSourcedWorkflowActor.DefineWorkflow(kafkaWorkflow, expectedRevision = 0L, reply.ref)
        reply.expectMessage(EventSourcedWorkflowActor.Defined("kafka-progress-revision", revision = 1L))
        actor ! EventSourcedWorkflowActor.ExecuteManual("run-1", reply.ref)
        val first = reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
        val frozenBoundary = "{\"0\":50}"
        val boundary = SnapshotBoundary("source-1", "kafka-boundary-1", Some(frozenBoundary))
        actor ! EventSourcedWorkflowActor.InitializeSnapshot(first.executionId, boundary, reply.ref)
        reply.expectMessage(EventSourcedWorkflowActor.SnapshotInitialized(boundary))
        val checkpoint = BatchCheckpoint(
          "source-1",
          "kafka-boundary-1",
          0L,
          BatchId.sha256(first.executionId, "source-1", "kafka-boundary-1", 0L),
          SourceCursor(KafkaCheckpointCodec.CursorKind, "{\"0\":30}", frozenBoundary),
          10L,
          10L
        )
        actor ! EventSourcedWorkflowActor.AdvanceCheckpoint(first.executionId, checkpoint, reply.ref)
        reply.expectMessage(EventSourcedWorkflowActor.CheckpointAccepted(checkpoint))
        firstCompletion.success(failed)
        eventuallySummary(actor)(_.status shouldBe EventSourcedWorkflowActor.Failed)

        actor ! EventSourcedWorkflowActor.DefineWorkflow(kafkaWorkflow, expectedRevision = 1L, reply.ref)
        reply.expectMessage(EventSourcedWorkflowActor.Defined("kafka-progress-revision", revision = 2L))
        actor ! EventSourcedWorkflowActor.ExecuteManual("run-2", reply.ref)
        reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
        val stateProbe = createTestProbe[EventSourcedWorkflowActor.ReliableRunState]()
        actor ! EventSourcedWorkflowActor.GetReliableRunState(stateProbe.ref)
        stateProbe.receiveMessage().currentExecution.value.checkpoints shouldBe empty
      } finally {
        firstCompletion.trySuccess(failed)
        secondCompletion.trySuccess(succeeded)
        testKit.stop(actor)
        cn.xuyinyin.magic.workflow.engine.ActorSpecNodeRegistryCleanup.unregister(TestKafkaSource)
      }
    }

    "canonicalize recursively sorted workflow JSON and bound schedule watermarks" in {
      val firstConfig = JsObject("z" -> JsObject("b" -> JsNumber(2), "a" -> JsNumber(1)), "a" -> JsString("first"))
      val secondConfig = JsObject("a" -> JsString("first"), "z" -> JsObject("a" -> JsNumber(1), "b" -> JsNumber(2)))
      val first = WorkflowFixtures.linearWorkflow.copy(nodes = WorkflowFixtures.linearWorkflow.nodes.updated(0, WorkflowFixtures.linearWorkflow.nodes.head.copy(config = firstConfig)))
      val second = WorkflowFixtures.linearWorkflow.copy(nodes = WorkflowFixtures.linearWorkflow.nodes.updated(0, WorkflowFixtures.linearWorkflow.nodes.head.copy(config = secondConfig)))

      EventSourcedWorkflowActor.canonicalWorkflowJson(first) shouldBe EventSourcedWorkflowActor.canonicalWorkflowJson(second)
      val retained = (1 to 101).foldLeft(Vector.empty[EventSourcedWorkflowActor.ScheduleWatermark]) { (watermarks, index) =>
        EventSourcedWorkflowActor.updateScheduleWatermarks(watermarks, s"schedule-$index", index.toLong)
      }
      retained.size shouldBe 100
      retained.head.scheduleId shouldBe "schedule-2"
    }

    "reject checkpoint sequence wraparound after Long.MaxValue" in {
      EventSourcedWorkflowActor.isExpectedNextCheckpointSequence(None, 0L) shouldBe true
      EventSourcedWorkflowActor.isExpectedNextCheckpointSequence(Some(0L), 1L) shouldBe true
      EventSourcedWorkflowActor.isExpectedNextCheckpointSequence(Some(Long.MaxValue), Long.MinValue) shouldBe false
      EventSourcedWorkflowActor.isExpectedNextCheckpointSequence(Some(Long.MaxValue), 0L) shouldBe false
    }

    "persist a definition before acknowledging it and reject execution before initialization" in {
      val emptyEntity = spawn(EventSourcedWorkflowActor("workflow-1", executionEngine = null), "empty-workflow")
      val executeReply = createTestProbe[EventSourcedWorkflowActor.Reply]()
      emptyEntity ! EventSourcedWorkflowActor.ExecuteManual("request-1", executeReply.ref)
      executeReply.expectMessage(EventSourcedWorkflowActor.NotInitialized("workflow-1"))

      val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()
      emptyEntity ! EventSourcedWorkflowActor.DefineWorkflow(WorkflowFixtures.linearWorkflow, expectedRevision = 0L, reply.ref)
      reply.expectMessage(EventSourcedWorkflowActor.Defined("workflow-1", revision = 1L))
    }

    "reject invalid definitions and conflicting revisions without changing the definition" in {
      val workflowId = "definition-validation"
      val entity = spawn(EventSourcedWorkflowActor(workflowId, engineReturning(succeeded)), "definition-validation")
      val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()
      entity ! EventSourcedWorkflowActor.DefineWorkflow(WorkflowFixtures.branchedWorkflow, expectedRevision = 0L, reply.ref)
      reply.expectMessageType[EventSourcedWorkflowActor.DefinitionRejected]

      entity ! EventSourcedWorkflowActor.DefineWorkflow(WorkflowFixtures.linearWorkflow, expectedRevision = 0L, reply.ref)
      reply.expectMessage(EventSourcedWorkflowActor.Defined(workflowId, 1L))
      entity ! EventSourcedWorkflowActor.DefineWorkflow(WorkflowFixtures.linearWorkflow, expectedRevision = 0L, reply.ref)
      reply.expectMessage(EventSourcedWorkflowActor.RevisionConflict(workflowId, expectedRevision = 0L, actualRevision = 1L))
    }

    "reject workflow definitions whose canonical event payload exceeds persistence bounds" in {
      val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()
      val tooManyNodes = WorkflowFixtures.linearWorkflow.copy(nodes = List.tabulate(101) { index =>
        WorkflowFixtures.linearWorkflow.nodes.head.copy(id = s"node-$index")
      })
      val nodeBound = spawn(EventSourcedWorkflowActor("node-bound", engineReturning(succeeded)), "node-bound")
      nodeBound ! EventSourcedWorkflowActor.DefineWorkflow(tooManyNodes, 0L, reply.ref)
      reply.expectMessageType[EventSourcedWorkflowActor.DefinitionRejected].errors should contain("workflow node count exceeds 100")

      val oversized = WorkflowFixtures.linearWorkflow.copy(nodes = WorkflowFixtures.linearWorkflow.nodes.updated(0, WorkflowFixtures.linearWorkflow.nodes.head.copy(config = JsObject("payload" -> JsString("x" * 65536)))))
      val byteBound = spawn(EventSourcedWorkflowActor("json-bound", engineReturning(succeeded)), "json-bound")
      byteBound ! EventSourcedWorkflowActor.DefineWorkflow(oversized, 0L, reply.ref)
      reply.expectMessageType[EventSourcedWorkflowActor.DefinitionRejected].errors should contain("workflow canonical JSON exceeds 65536 bytes")
    }

    "persist successful and failed engine results as distinct terminal states" in {
      val successfulId = "successful-execution"
      val successful = spawn(EventSourcedWorkflowActor(successfulId, engineReturning(succeeded)), successfulId)
      define(successful, successfulId)
      val successReply = createTestProbe[EventSourcedWorkflowActor.Reply]()
      successful ! EventSourcedWorkflowActor.ExecuteManual("success-request", successReply.ref)
      successReply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
      eventuallySummary(successful)(_.status shouldBe EventSourcedWorkflowActor.Completed)

      val unsuccessfulId = "failed-execution"
      val unsuccessful = spawn(EventSourcedWorkflowActor(unsuccessfulId, engineReturning(failed)), unsuccessfulId)
      val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()
      unsuccessful ! EventSourcedWorkflowActor.DefineWorkflow(WorkflowFixtures.linearWorkflow, 0L, reply.ref)
      reply.expectMessage(EventSourcedWorkflowActor.Defined(unsuccessfulId, 1L))
      unsuccessful ! EventSourcedWorkflowActor.ExecuteManual("failure-request", reply.ref)
      reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
      eventuallySummary(unsuccessful)(_.status shouldBe EventSourcedWorkflowActor.Failed)
    }

    "retain bounded node attribution and errors in execution history" in {
      val workflowId = "failed-history"
      val result = ExecutionResult(
        "failed",
        success = false,
        "sink failed",
        None,
        Some(9L),
        Vector(NodeExecutionResult("sink", "mysql", "failed", Some("connection refused"), Some(7L)))
      )
      val entity = spawn(EventSourcedWorkflowActor(workflowId, engineReturning(result)), workflowId)
      define(entity, workflowId)
      val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()
      entity ! EventSourcedWorkflowActor.ExecuteManual("failed-history-request", reply.ref)
      reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
      eventuallySummary(entity)(_.status shouldBe EventSourcedWorkflowActor.Failed)

      val history = createTestProbe[EventSourcedWorkflowActor.ExecutionHistoryResponse]()
      entity ! EventSourcedWorkflowActor.GetExecutionHistory(history.ref)
      val detail = history.receiveMessage().executions.head
      detail.status shouldBe "failed"
      detail.nodes.map(_.nodeId) shouldBe List("sink")
      detail.nodes.head.status shouldBe "failed"
      detail.nodes.head.error shouldBe Some("connection refused")
      detail.nodes.head.duration shouldBe Some(7L)
      detail.nodes.head.startTime shouldBe None
      detail.nodes.head.endTime shouldBe None
      detail.nodes.head.recordsProcessed shouldBe None
    }

    "persist an oversized engine result as a bounded failed execution" in {
      val oversized = ExecutionResult(
        "completed",
        success = true,
        "x" * 4097,
        Some(1),
        Some(1L),
        Vector.tabulate(101)(index => NodeExecutionResult(s"node-$index", "sink", "completed", Some("x" * 4097), Some(1L)))
      )
      val entity = spawn(EventSourcedWorkflowActor("result-bound", engineReturning(oversized)), "result-bound")
      define(entity, "result-bound")
      val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()
      entity ! EventSourcedWorkflowActor.ExecuteManual("oversized-result", reply.ref)
      reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
      eventuallySummary(entity) { summary =>
        summary.status shouldBe EventSourcedWorkflowActor.Failed
        summary.recentExecutions.last.status shouldBe "failed"
      }
    }

    "deduplicate retained manual requests and reject a concurrent schedule run" in {
      val completion = Promise[ExecutionResult]()
      val engine = new WorkflowExecutionEngine() {
        override def execute(workflow: cn.xuyinyin.magic.workflow.model.WorkflowDSL.Workflow, executionId: String, onLog: String => Unit): Future[ExecutionResult] = completion.future
      }
      val workflowId = "idempotency"
      val entity = spawn(EventSourcedWorkflowActor(workflowId, engine), workflowId)
      define(entity, workflowId)
      val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()
      entity ! EventSourcedWorkflowActor.ExecuteManual("request-1", reply.ref)
      val accepted = reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
      entity ! EventSourcedWorkflowActor.ExecuteManual("request-1", reply.ref)
      reply.expectMessage(EventSourcedWorkflowActor.DuplicateExecution("request-1", accepted.executionId))
      entity ! EventSourcedWorkflowActor.ExecuteScheduled("daily", 1000L, "daily-1000", reply.ref)
      reply.expectMessageType[EventSourcedWorkflowActor.AlreadyRunning]
      completion.success(succeeded)
    }

    "deduplicate an equal scheduled watermark after its execution completes" in {
      val workflowId = "schedule-watermark"
      val entity = spawn(EventSourcedWorkflowActor(workflowId, engineReturning(succeeded)), workflowId)
      define(entity, workflowId)
      val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()
      entity ! EventSourcedWorkflowActor.ExecuteScheduled("daily", 1000L, "daily-1000", reply.ref)
      reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
      eventuallySummary(entity)(_.status shouldBe EventSourcedWorkflowActor.Completed)
      entity ! EventSourcedWorkflowActor.ExecuteScheduled("daily", 1000L, "daily-1000", reply.ref)
      reply.expectMessageType[EventSourcedWorkflowActor.DuplicateExecution]
    }
  }
}
}
