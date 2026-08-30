package cn.xuyinyin.magic.workflow.engine {
  import cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry
  import cn.xuyinyin.magic.workflow.nodes.base.{NodeSink, NodeSource}

  private[workflow] object RecoverySpecNodeRegistryCleanup {
    def unregister(source: NodeSource): Unit = NodeRegistry.unregisterSource(source.nodeType, source)
    def unregister(sink: NodeSink): Unit = NodeRegistry.unregisterSink(sink.nodeType, sink)
  }
}

package cn.xuyinyin.magic.workflow.actors {

import cn.xuyinyin.magic.workflow.WorkflowFixtures
import cn.xuyinyin.magic.common.CborSerializable
import cn.xuyinyin.magic.workflow.checkpoint._
import cn.xuyinyin.magic.workflow.engine.{ExecutionResult, NodeExecutionResult, ReliableRunContext, WorkflowExecutionEngine}
import cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.events.WorkflowEvents.ExecutionTrigger
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base.{CheckpointedNodeSink, CheckpointedNodeSource, NodeSink, NodeSource}
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.serialization.{SerializationExtension, Serializers}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.io.{Source => IoSource}

object EventSourcedWorkflowActorRecoverySpec {
  private val h2Url = s"jdbc:h2:mem:workflow-actor-recovery-${java.util.UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1"

  /** Must run before ScalaTestWithActorTestKit creates its ActorSystem. */
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
      s"""pekko-persistence-jdbc.shared-databases.slick.db.url = \"$h2Url\"
         |pekko.workflow.event-sourcing.keep-n-snapshots = 3""".stripMargin
    ).withFallback(ConfigFactory.load("application-test"))
  }

  def snapshotSequenceNumber(persistenceId: String): Option[Long] = {
    val connection = DriverManager.getConnection(h2Url)
    try {
      val statement = connection.prepareStatement("SELECT MAX(\"sequence_number\") FROM \"snapshot\" WHERE \"persistence_id\" = ?")
      try {
        statement.setString(1, persistenceId)
        val result = statement.executeQuery()
        try Option.when(result.next() && result.getObject(1) != null)(result.getLong(1)) finally result.close()
      } finally statement.close()
    } finally connection.close()
  }

  def snapshotSequenceNumbers(persistenceId: String): Vector[Long] = {
    val connection = DriverManager.getConnection(h2Url)
    try {
      val statement = connection.prepareStatement(
        "SELECT \"sequence_number\" FROM \"snapshot\" WHERE \"persistence_id\" = ? ORDER BY \"sequence_number\""
      )
      try {
        statement.setString(1, persistenceId)
        val result = statement.executeQuery()
        try {
          val sequences = Vector.newBuilder[Long]
          while (result.next()) sequences += result.getLong(1)
          sequences.result()
        } finally result.close()
      } finally statement.close()
    } finally connection.close()
  }

  def deleteJournalThrough(persistenceId: String, sequenceNumber: Long): Unit = {
    val connection = DriverManager.getConnection(h2Url)
    try {
      val statement = connection.prepareStatement("DELETE FROM \"event_journal\" WHERE \"persistence_id\" = ? AND \"sequence_number\" <= ?")
      try {
        statement.setString(1, persistenceId)
        statement.setLong(2, sequenceNumber)
        statement.executeUpdate()
      } finally statement.close()
    } finally connection.close()
  }

  final case class StoredSnapshot(sequenceNumber: Long, serializerId: Int, serializerManifest: String, payload: Array[Byte])

  final case class LegacyExecutionState(executionId: String, trigger: ExecutionTrigger, startedAt: Long) extends CborSerializable
  final case class LegacyWorkflowState(
    workflowJson: Option[String],
    revision: Long,
    status: EventSourcedWorkflowActor.WorkflowStatus,
    currentExecution: Option[LegacyExecutionState],
    recentExecutions: Vector[EventSourcedWorkflowActor.StoredExecutionSummary],
    lastAcceptedTriggerBySchedule: Vector[EventSourcedWorkflowActor.ScheduleWatermark],
    manualRequests: Vector[EventSourcedWorkflowActor.ManualRequestRecord]
  ) extends CborSerializable

  def readSnapshot(persistenceId: String, sequenceNumber: Long): StoredSnapshot = {
    val connection = DriverManager.getConnection(h2Url)
    try {
      val statement = connection.prepareStatement(
        "SELECT \"snapshot_ser_id\", \"snapshot_ser_manifest\", \"snapshot_payload\" FROM \"snapshot\" WHERE \"persistence_id\" = ? AND \"sequence_number\" = ?"
      )
      try {
        statement.setString(1, persistenceId)
        statement.setLong(2, sequenceNumber)
        val result = statement.executeQuery()
        try {
          if (!result.next()) throw new AssertionError(s"snapshot $persistenceId/$sequenceNumber was not found")
          StoredSnapshot(sequenceNumber, result.getInt(1), result.getString(2), result.getBytes(3))
        } finally result.close()
      } finally statement.close()
    } finally connection.close()
  }

  def replaceSnapshot(persistenceId: String, snapshot: StoredSnapshot): Unit = {
    val connection = DriverManager.getConnection(h2Url)
    try {
      val statement = connection.prepareStatement(
        "UPDATE \"snapshot\" SET \"snapshot_ser_id\" = ?, \"snapshot_ser_manifest\" = ?, \"snapshot_payload\" = ? WHERE \"persistence_id\" = ? AND \"sequence_number\" = ?"
      )
      try {
        statement.setInt(1, snapshot.serializerId)
        statement.setString(2, snapshot.serializerManifest)
        statement.setBytes(3, snapshot.payload)
        statement.setString(4, persistenceId)
        statement.setLong(5, snapshot.sequenceNumber)
        if (statement.executeUpdate() != 1) throw new AssertionError(s"snapshot $persistenceId/${snapshot.sequenceNumber} was not replaced")
      } finally statement.close()
    } finally connection.close()
  }
}

class EventSourcedWorkflowActorRecoverySpec
    extends ScalaTestWithActorTestKit(EventSourcedWorkflowActorRecoverySpec.config)
    with AnyWordSpecLike
    with OptionValues
    with Matchers {

  private implicit val executionContext: scala.concurrent.ExecutionContext = system.executionContext
  private val result = ExecutionResult(
    "completed",
    success = true,
    "done",
    Some(1),
    Some(1L),
    Vector(NodeExecutionResult("sink-1", "console.log", "completed", Some("done"), Some(1L)))
  )
  private val reliableWorkflow = WorkflowFixtures.linearWorkflow.copy(nodes =
    WorkflowFixtures.linearWorkflow.nodes
      .updated(0, WorkflowFixtures.linearWorkflow.nodes.head.copy(nodeType = "mysql.snapshot"))
      .updated(2, WorkflowFixtures.linearWorkflow.nodes(2).copy(nodeType = "mysql.write"))
  )
  private val customReliableWorkflow = WorkflowFixtures.linearWorkflow.copy(nodes =
    WorkflowFixtures.linearWorkflow.nodes
      .updated(0, WorkflowFixtures.linearWorkflow.nodes.head.copy(nodeType = CustomCheckpointSource.nodeType))
      .updated(2, WorkflowFixtures.linearWorkflow.nodes(2).copy(nodeType = CustomCheckpointSink.nodeType))
  )

  private object CustomCheckpointSource extends NodeSource with CheckpointedNodeSource {
    override val nodeType: String = "test.actor-checkpoint-source"
    override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = Source.empty
    override def discoverBoundary(node: WorkflowDSL.Node, onLog: String => Unit)(implicit blockingEc: ExecutionContext): Future[SnapshotBoundary] =
      Future.successful(SnapshotBoundary(node.id, "pk-range-0", Some("9")))
    override def createBatches(
      node: WorkflowDSL.Node,
      executionId: String,
      boundary: SnapshotBoundary,
      resume: Option[BatchCheckpoint],
      onLog: String => Unit
    )(implicit blockingEc: ExecutionContext): Source[SourceBatch, NotUsed] = Source.empty
  }

  private object CustomCheckpointSink extends NodeSink with CheckpointedNodeSink {
    override val nodeType: String = "test.actor-checkpoint-sink"
    override def createSink(node: WorkflowDSL.Node, onLog: String => Unit)(implicit ec: ExecutionContext): Sink[String, Future[Done]] = Sink.ignore
    override def validateReady(node: WorkflowDSL.Node, onLog: String => Unit)(implicit blockingEc: ExecutionContext): Future[Done] = Future.successful(Done)
    override def commitBatch(
      node: WorkflowDSL.Node,
      workflowId: String,
      executionId: String,
      batch: SourceBatch,
      transformedRows: Vector[String],
      onLog: String => Unit
    )(implicit blockingEc: ExecutionContext): Future[BatchCommitResult] =
      Future.failed(new AssertionError("actor routing test must not execute connector batches"))
  }

  private object CapabilityLostLegacySource extends NodeSource {
    override val nodeType: String = CustomCheckpointSource.nodeType
    private val legacyCreateCount = new AtomicInteger(0)

    def legacyCreates: Int = legacyCreateCount.get()
    def reset(): Unit = legacyCreateCount.set(0)

    override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = {
      legacyCreateCount.incrementAndGet()
      Source.single("replayed-from-start")
    }
  }

  private object BuiltinNamedLegacySource extends NodeSource {
    override val nodeType: String = "mysql.snapshot"
    override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = Source.empty
  }

  private object BuiltinNamedLegacySink extends NodeSink {
    override val nodeType: String = "mysql.write"
    override def createSink(node: WorkflowDSL.Node, onLog: String => Unit)(implicit ec: ExecutionContext): Sink[String, Future[Done]] = Sink.ignore
  }

  private def engine: WorkflowExecutionEngine = new WorkflowExecutionEngine() {
    override def execute(workflow: cn.xuyinyin.magic.workflow.model.WorkflowDSL.Workflow, executionId: String, onLog: String => Unit): Future[ExecutionResult] =
      Future.successful(result)
  }

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

  private def eventuallySnapshot(persistenceId: String): Long = {
    val deadline = 5.seconds.fromNow
    while (deadline.hasTimeLeft()) {
      EventSourcedWorkflowActorRecoverySpec.snapshotSequenceNumber(persistenceId) match {
        case Some(sequenceNumber) => return sequenceNumber
        case None => Thread.sleep(50)
      }
    }
    throw new AssertionError(s"snapshot was not stored for $persistenceId")
  }

  private def eventuallySnapshotSequences(persistenceId: String, expected: Vector[Long]): Unit = {
    val deadline = 5.seconds.fromNow
    var actual = Vector.empty[Long]
    var stableSince = System.nanoTime()
    while (deadline.hasTimeLeft()) {
      val observed = EventSourcedWorkflowActorRecoverySpec.snapshotSequenceNumbers(persistenceId)
      if (observed != actual) {
        actual = observed
        stableSince = System.nanoTime()
      }
      if (actual.lastOption.contains(expected.last) && (System.nanoTime() - stableSince).nanos >= 1.second) {
        actual shouldBe expected
        return
      }
      Thread.sleep(50)
    }
    throw new AssertionError(s"expected snapshots $expected for $persistenceId, got $actual")
  }

  "EventSourcedWorkflowActor recovery" should {
    "honor the configured snapshot frequency" in {
      val workflowId = "configured-snapshot-frequency"
      val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()
      val entity = spawn(EventSourcedWorkflowActor(workflowId, engine), "configured-snapshot-frequency")

      entity ! EventSourcedWorkflowActor.DefineWorkflow(WorkflowFixtures.linearWorkflow, 0L, reply.ref)
      reply.expectMessage(EventSourcedWorkflowActor.Defined(workflowId, 1L))
      (1L to 5L).foreach { scheduledAt =>
        entity ! EventSourcedWorkflowActor.ExecuteScheduled("snapshot-frequency", scheduledAt, s"snapshot-$scheduledAt", reply.ref)
        reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
        eventuallySummary(entity)(_.status shouldBe EventSourcedWorkflowActor.Completed)
      }

      eventuallySnapshot(s"workflow-$workflowId") shouldBe 10L
    }

    "honor the configured number of retained snapshots" in {
      val workflowId = "configured-snapshot-retention"
      val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()
      val entity = spawn(EventSourcedWorkflowActor(workflowId, engine), "configured-snapshot-retention")

      entity ! EventSourcedWorkflowActor.DefineWorkflow(WorkflowFixtures.linearWorkflow, 0L, reply.ref)
      reply.expectMessage(EventSourcedWorkflowActor.Defined(workflowId, 1L))
      (1L to 15L).foreach { scheduledAt =>
        entity ! EventSourcedWorkflowActor.ExecuteScheduled("snapshot-retention", scheduledAt, s"retention-$scheduledAt", reply.ref)
        reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
        eventuallySummary(entity)(_.status shouldBe EventSourcedWorkflowActor.Completed)
      }

      eventuallySnapshotSequences(s"workflow-$workflowId", Vector(10L, 20L, 30L))
    }

    "select reliable execution from the currently registered source and sink capabilities" in {
      NodeRegistry.registerSource(CustomCheckpointSource)
      NodeRegistry.registerSink(CustomCheckpointSink)
      val reliableInvocation = Promise[ReliableRunContext]()
      val pending = Promise[ExecutionResult]()
      val capabilityEngine = new WorkflowExecutionEngine() {
        override def execute(workflow: WorkflowDSL.Workflow, executionId: String, onLog: String => Unit): Future[ExecutionResult] =
          Future.failed(new AssertionError("custom checkpoint nodes entered legacy execution"))
        override def execute(workflow: WorkflowDSL.Workflow, runContext: ReliableRunContext, onLog: String => Unit): Future[ExecutionResult] = {
          reliableInvocation.trySuccess(runContext)
          pending.future
        }
      }
      val workflowId = "custom-capability-reliable"
      val entity = spawn(EventSourcedWorkflowActor(workflowId, capabilityEngine), workflowId)
      val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()
      try {
        entity ! EventSourcedWorkflowActor.DefineWorkflow(customReliableWorkflow, 0L, reply.ref)
        reply.expectMessage(EventSourcedWorkflowActor.Defined(workflowId, 1L))
        entity ! EventSourcedWorkflowActor.ExecuteManual("custom-capability-request", reply.ref)
        val accepted = reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]

        val context = Await.result(reliableInvocation.future, 3.seconds)
        context.executionId shouldBe accepted.executionId
        context.workflowRevision shouldBe 1L
        context.checkpoints shouldBe empty
        val state = createTestProbe[EventSourcedWorkflowActor.ReliableRunState]()
        entity ! EventSourcedWorkflowActor.GetReliableRunState(state.ref)
        state.receiveMessage().currentExecution.value.resumable shouldBe true
      } finally {
        testKit.stop(entity)
        cn.xuyinyin.magic.workflow.engine.RecoverySpecNodeRegistryCleanup.unregister(CustomCheckpointSource)
        cn.xuyinyin.magic.workflow.engine.RecoverySpecNodeRegistryCleanup.unregister(CustomCheckpointSink)
      }
    }

    "fail a recovered resumable execution when its registered source loses checkpoint capability" in {
      CapabilityLostLegacySource.reset()
      NodeRegistry.registerSource(CustomCheckpointSource)
      NodeRegistry.registerSink(CustomCheckpointSink)
      val initialContext = Promise[ReliableRunContext]()
      val initialPending = Promise[ExecutionResult]()
      val initialEngine = new WorkflowExecutionEngine() {
        override def execute(workflow: WorkflowDSL.Workflow, runContext: ReliableRunContext, onLog: String => Unit): Future[ExecutionResult] = {
          initialContext.trySuccess(runContext)
          initialPending.future
        }
      }
      val workflowId = "resumable-source-capability-loss"
      val entity = spawn(EventSourcedWorkflowActor(workflowId, initialEngine), "before-source-capability-loss")
      val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()
      var entityStopped = false
      try {
        entity ! EventSourcedWorkflowActor.DefineWorkflow(customReliableWorkflow, 0L, reply.ref)
        reply.expectMessage(EventSourcedWorkflowActor.Defined(workflowId, 1L))
        entity ! EventSourcedWorkflowActor.ExecuteManual("source-capability-loss-request", reply.ref)
        val accepted = reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
        val runContext = Await.result(initialContext.future, 3.seconds)
        val boundary = SnapshotBoundary("source-1", "pk-range-0", Some("9"))
        Await.result(runContext.initializeBoundary(boundary), 3.seconds) shouldBe Done
        val checkpoint0 = BatchCheckpoint(
          "source-1",
          "pk-range-0",
          0L,
          BatchId.sha256(accepted.executionId, "source-1", "pk-range-0", 0L),
          SourceCursor("test.cursor", "2", "9"),
          2L,
          2L
        )
        Await.result(runContext.checkpointCommitted(checkpoint0), 3.seconds) shouldBe Done
        testKit.stop(entity)
        entityStopped = true

        NodeRegistry.registerSource(CapabilityLostLegacySource)
        val recovered = spawn(EventSourcedWorkflowActor(workflowId, new WorkflowExecutionEngine()), "after-source-capability-loss")
        try {
          eventuallySummary(recovered) { summary =>
            summary.status shouldBe EventSourcedWorkflowActor.Failed
            summary.currentExecution shouldBe None
            summary.recentExecutions.last.executionId shouldBe accepted.executionId
          }
          CapabilityLostLegacySource.legacyCreates shouldBe 0
        } finally testKit.stop(recovered)
      } finally {
        if (!entityStopped) testKit.stop(entity)
        cn.xuyinyin.magic.workflow.engine.RecoverySpecNodeRegistryCleanup.unregister(CustomCheckpointSource)
        cn.xuyinyin.magic.workflow.engine.RecoverySpecNodeRegistryCleanup.unregister(CapabilityLostLegacySource)
        cn.xuyinyin.magic.workflow.engine.RecoverySpecNodeRegistryCleanup.unregister(CustomCheckpointSink)
      }
    }

    "keep builtin names non-resumable when the current registrations are legacy nodes" in {
      NodeRegistry.registerSource(BuiltinNamedLegacySource)
      NodeRegistry.registerSink(BuiltinNamedLegacySink)
      val legacyInvocation = Promise[String]()
      val pending = Promise[ExecutionResult]()
      val routingEngine = new WorkflowExecutionEngine() {
        override def execute(workflow: WorkflowDSL.Workflow, executionId: String, onLog: String => Unit): Future[ExecutionResult] = {
          legacyInvocation.trySuccess(executionId)
          pending.future
        }
        override def execute(workflow: WorkflowDSL.Workflow, runContext: ReliableRunContext, onLog: String => Unit): Future[ExecutionResult] =
          pending.future
      }
      val workflowId = "builtin-named-legacy"
      val entity = spawn(EventSourcedWorkflowActor(workflowId, routingEngine), "before-builtin-named-legacy")
      val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()
      var entityStopped = false
      try {
        entity ! EventSourcedWorkflowActor.DefineWorkflow(reliableWorkflow, 0L, reply.ref)
        reply.expectMessage(EventSourcedWorkflowActor.Defined(workflowId, 1L))
        entity ! EventSourcedWorkflowActor.ExecuteManual("legacy-registration-request", reply.ref)
        val accepted = reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
        Await.result(legacyInvocation.future, 3.seconds) shouldBe accepted.executionId
        val state = createTestProbe[EventSourcedWorkflowActor.ReliableRunState]()
        entity ! EventSourcedWorkflowActor.GetReliableRunState(state.ref)
        state.receiveMessage().currentExecution.value.resumable shouldBe false
        testKit.stop(entity)
        entityStopped = true

        val recoveryCalls = new AtomicInteger(0)
        val recoveryEngine = new WorkflowExecutionEngine() {
          override def execute(workflow: WorkflowDSL.Workflow, executionId: String, onLog: String => Unit): Future[ExecutionResult] = {
            recoveryCalls.incrementAndGet()
            Future.successful(result)
          }
          override def execute(workflow: WorkflowDSL.Workflow, runContext: ReliableRunContext, onLog: String => Unit): Future[ExecutionResult] = {
            recoveryCalls.incrementAndGet()
            Future.successful(result)
          }
        }
        val recovered = spawn(EventSourcedWorkflowActor(workflowId, recoveryEngine), "after-builtin-named-legacy")
        try {
          eventuallySummary(recovered)(_.status shouldBe EventSourcedWorkflowActor.Failed)
          recoveryCalls.get shouldBe 0
        } finally testKit.stop(recovered)
      } finally {
        if (!entityStopped) testKit.stop(entity)
        cn.xuyinyin.magic.workflow.engine.RecoverySpecNodeRegistryCleanup.unregister(BuiltinNamedLegacySource)
        cn.xuyinyin.magic.workflow.engine.RecoverySpecNodeRegistryCleanup.unregister(BuiltinNamedLegacySink)
      }
    }

    "recover snapshot-backed numeric state and accept a newer schedule watermark" in {
      val workflowId = "snapshot-workflow"
      val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()
      val entity = spawn(EventSourcedWorkflowActor(workflowId, engine), "before-snapshot-recovery")
      entity ! EventSourcedWorkflowActor.DefineWorkflow(WorkflowFixtures.linearWorkflow, 0L, reply.ref)
      reply.expectMessage(EventSourcedWorkflowActor.Defined(workflowId, 1L))
      (1L to 51L).foreach { scheduledAt =>
        entity ! EventSourcedWorkflowActor.ExecuteScheduled("daily", scheduledAt, s"daily-$scheduledAt", reply.ref)
        reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
        eventuallySummary(entity)(_.status shouldBe EventSourcedWorkflowActor.Completed)
      }
      val beforeSnapshotRecovery = createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
      entity ! EventSourcedWorkflowActor.GetSummary(beforeSnapshotRecovery.ref)
      val expected = beforeSnapshotRecovery.receiveMessage()
      val persistenceId = s"workflow-$workflowId"
      val snapshotSequence = eventuallySnapshot(persistenceId)
      snapshotSequence should be >= 100L
      testKit.stop(entity)
      EventSourcedWorkflowActorRecoverySpec.deleteJournalThrough(persistenceId, snapshotSequence)

      val recovered = spawn(EventSourcedWorkflowActor(workflowId, engine), "after-snapshot-recovery")
      eventuallySummary(recovered) { summary =>
        summary.revision shouldBe 1L
        summary.recentExecutions.last.endTime shouldBe expected.recentExecutions.last.endTime
        summary.recentExecutions.last.duration shouldBe expected.recentExecutions.last.duration
      }
      recovered ! EventSourcedWorkflowActor.ExecuteScheduled("daily", 51L, "daily-51", reply.ref)
      reply.expectMessageType[EventSourcedWorkflowActor.DuplicateExecution]
      recovered ! EventSourcedWorkflowActor.ExecuteScheduled("daily", 52L, "daily-52", reply.ref)
      reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
    }

    "read a legacy-shaped running-execution snapshot with reliable defaults" in {
      val workflowId = "legacy-snapshot-shape"
      val persistenceId = s"workflow-$workflowId"
      val invocation = new java.util.concurrent.atomic.AtomicInteger(0)
      val pending = Promise[ExecutionResult]()
      val snapshotEngine = new WorkflowExecutionEngine() {
        override def execute(workflow: cn.xuyinyin.magic.workflow.model.WorkflowDSL.Workflow, executionId: String, onLog: String => Unit): Future[ExecutionResult] =
          if (invocation.incrementAndGet() <= 49) Future.successful(result) else pending.future
      }
      val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()
      val entity = spawn(EventSourcedWorkflowActor(workflowId, snapshotEngine), "before-legacy-shape-recovery")
      entity ! EventSourcedWorkflowActor.DefineWorkflow(WorkflowFixtures.linearWorkflow, 0L, reply.ref)
      reply.expectMessage(EventSourcedWorkflowActor.Defined(workflowId, 1L))
      (1L to 49L).foreach { scheduledAt =>
        entity ! EventSourcedWorkflowActor.ExecuteScheduled("daily", scheduledAt, s"daily-$scheduledAt", reply.ref)
        reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
        eventuallySummary(entity)(_.status shouldBe EventSourcedWorkflowActor.Completed)
      }
      entity ! EventSourcedWorkflowActor.ExecuteManual("legacy-running", reply.ref)
      reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
      val sequenceNumber = eventuallySnapshot(persistenceId)
      sequenceNumber shouldBe 100L
      testKit.stop(entity)

      val stored = EventSourcedWorkflowActorRecoverySpec.readSnapshot(persistenceId, sequenceNumber)
      val serialization = SerializationExtension(system.toClassic.asInstanceOf[ExtendedActorSystem])
      val current = serialization.deserialize(stored.payload, stored.serializerId, stored.serializerManifest).get
        .asInstanceOf[EventSourcedWorkflowActor.WorkflowState]
      val legacy = EventSourcedWorkflowActorRecoverySpec.LegacyWorkflowState(
        current.workflowJson,
        current.revision,
        current.status,
        current.currentExecution.map(execution => EventSourcedWorkflowActorRecoverySpec.LegacyExecutionState(execution.executionId, execution.trigger, execution.startedAt)),
        current.recentExecutions,
        current.lastAcceptedTriggerBySchedule,
        current.manualRequests
      )
      val serializer = serialization.findSerializerFor(legacy)
      val currentSerializer = serialization.findSerializerFor(current)
      val currentManifest = Serializers.manifestFor(currentSerializer, current)
      val legacyPayload = serializer.toBinary(legacy)
      val decoded = serialization.deserialize(legacyPayload, currentSerializer.identifier, currentManifest).get
        .asInstanceOf[EventSourcedWorkflowActor.WorkflowState]
      decoded.currentExecution.value.resumable shouldBe false
      decoded.currentExecution.value.workflowRevision shouldBe 0L
      decoded.currentExecution.value.boundary shouldBe None
      decoded.currentExecution.value.checkpoints shouldBe empty

      EventSourcedWorkflowActorRecoverySpec.replaceSnapshot(
        persistenceId,
        EventSourcedWorkflowActorRecoverySpec.StoredSnapshot(sequenceNumber, currentSerializer.identifier, currentManifest, legacyPayload)
      )
      EventSourcedWorkflowActorRecoverySpec.deleteJournalThrough(persistenceId, sequenceNumber)
      val recovered = spawn(EventSourcedWorkflowActor(workflowId, engine), "after-legacy-shape-recovery")
      eventuallySummary(recovered)(_.status shouldBe EventSourcedWorkflowActor.Failed)
    }

    "turn an interrupted recovered execution into failed before accepting a new schedule run" in {
      val pending = Promise[ExecutionResult]()
      val pendingEngine = new WorkflowExecutionEngine() {
        override def execute(workflow: cn.xuyinyin.magic.workflow.model.WorkflowDSL.Workflow, executionId: String, onLog: String => Unit): Future[ExecutionResult] = pending.future
      }
      val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()
      val entity = spawn(EventSourcedWorkflowActor("interrupted-workflow", pendingEngine), "before-interruption")
      entity ! EventSourcedWorkflowActor.DefineWorkflow(WorkflowFixtures.linearWorkflow, 0L, reply.ref)
      reply.expectMessage(EventSourcedWorkflowActor.Defined("interrupted-workflow", 1L))
      entity ! EventSourcedWorkflowActor.ExecuteManual("manual-before-stop", reply.ref)
      reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
      entity ! EventSourcedWorkflowActor.ExecuteScheduled("daily", 1000L, "daily-1000", reply.ref)
      reply.expectMessageType[EventSourcedWorkflowActor.AlreadyRunning]
      testKit.stop(entity)

      val recovered = spawn(EventSourcedWorkflowActor("interrupted-workflow", engine), "after-interruption")
      eventuallySummary(recovered)(_.status shouldBe EventSourcedWorkflowActor.Failed)
      recovered ! EventSourcedWorkflowActor.ExecuteManual("manual-before-stop", reply.ref)
      reply.expectMessageType[EventSourcedWorkflowActor.DuplicateExecution]
      recovered ! EventSourcedWorkflowActor.ExecuteScheduled("daily", 1000L, "daily-1000", reply.ref)
      reply.expectMessageType[EventSourcedWorkflowActor.DuplicateExecution]
      recovered ! EventSourcedWorkflowActor.ExecuteScheduled("daily", 2000L, "daily-2000", reply.ref)
      reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
    }

    "gate externally queued commands until recovered interruption is terminal" in {
      val pending = Promise[ExecutionResult]()
      val pendingEngine = new WorkflowExecutionEngine() {
        override def execute(workflow: cn.xuyinyin.magic.workflow.model.WorkflowDSL.Workflow, executionId: String, onLog: String => Unit): Future[ExecutionResult] = pending.future
      }
      val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()
      val entity = spawn(EventSourcedWorkflowActor("recovery-gate", pendingEngine), "before-recovery-gate")
      entity ! EventSourcedWorkflowActor.DefineWorkflow(WorkflowFixtures.linearWorkflow, 0L, reply.ref)
      reply.expectMessage(EventSourcedWorkflowActor.Defined("recovery-gate", 1L))
      entity ! EventSourcedWorkflowActor.ExecuteManual("manual-before-recovery", reply.ref)
      reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
      testKit.stop(entity)

      val recovered = spawn(EventSourcedWorkflowActor("recovery-gate", engine), "after-recovery-gate")
      recovered ! EventSourcedWorkflowActor.ExecuteScheduled("daily", 2000L, "daily-2000", reply.ref)
      recovered ! EventSourcedWorkflowActor.ExecuteManual("manual-during-recovery", reply.ref)
      reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
      reply.expectMessageType[EventSourcedWorkflowActor.AlreadyRunning]
      eventuallySummary(recovered)(_.status shouldBe EventSourcedWorkflowActor.Completed)
    }

    "restart a checkpointed execution with its durable identity and progress" in {
      val workflowId = "resumable-recovery"
      val initialContext = Promise[ReliableRunContext]()
      val initialPending = Promise[ExecutionResult]()
      val initialEngine = new WorkflowExecutionEngine() {
        override def execute(workflow: cn.xuyinyin.magic.workflow.model.WorkflowDSL.Workflow, runContext: ReliableRunContext, onLog: String => Unit): Future[ExecutionResult] = {
          initialContext.trySuccess(runContext)
          initialPending.future
        }
      }
      val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()
      val entity = spawn(EventSourcedWorkflowActor(workflowId, initialEngine), "before-resumable-recovery")
      entity ! EventSourcedWorkflowActor.DefineWorkflow(reliableWorkflow, 0L, reply.ref)
      reply.expectMessage(EventSourcedWorkflowActor.Defined(workflowId, 1L))
      entity ! EventSourcedWorkflowActor.ExecuteManual("resumable-request", reply.ref)
      val accepted = reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
      val firstRun = Await.result(initialContext.future, 3.seconds)
      firstRun.executionId shouldBe accepted.executionId
      firstRun.workflowRevision shouldBe 1L
      firstRun.boundary shouldBe None
      firstRun.checkpoints shouldBe empty

      val boundary = SnapshotBoundary("source-1", "pk-range-0", Some("9"))
      Await.result(firstRun.initializeBoundary(boundary), 3.seconds) shouldBe Done
      val checkpoint0 = BatchCheckpoint(
        "source-1",
        "pk-range-0",
        0L,
        BatchId.sha256(accepted.executionId, "source-1", "pk-range-0", 0L),
        SourceCursor("mysql.numeric-pk", "2", "9"),
        2L,
        2L
      )
      Await.result(firstRun.checkpointCommitted(checkpoint0), 3.seconds) shouldBe Done
      testKit.stop(entity)

      val recoveredContext = Promise[ReliableRunContext]()
      val duplicateAck = Promise[Done]()
      val recoveredPending = Promise[ExecutionResult]()
      val recoveryEngine = new WorkflowExecutionEngine() {
        override def execute(workflow: cn.xuyinyin.magic.workflow.model.WorkflowDSL.Workflow, runContext: ReliableRunContext, onLog: String => Unit): Future[ExecutionResult] = {
          recoveredContext.trySuccess(runContext)
          val acknowledged = runContext.checkpointCommitted(checkpoint0)
          duplicateAck.completeWith(acknowledged)
          acknowledged.flatMap(_ => recoveredPending.future)
        }
      }
      val recovered = spawn(EventSourcedWorkflowActor(workflowId, recoveryEngine), "after-resumable-recovery")
      try {
        val resumed = Await.result(recoveredContext.future, 3.seconds)
        resumed.executionId shouldBe accepted.executionId
        resumed.workflowRevision shouldBe 1L
        resumed.boundary shouldBe Some(boundary)
        resumed.checkpoints shouldBe Vector(checkpoint0)
        Await.result(duplicateAck.future, 3.seconds) shouldBe Done

        recovered ! EventSourcedWorkflowActor.ExecuteScheduled("daily", 2000L, "daily-2000", reply.ref)
        reply.expectMessage(EventSourcedWorkflowActor.AlreadyRunning(accepted.executionId))
      } finally testKit.stop(recovered)
    }

    "recover the definition, terminal state, and schedule watermark from JDBC" in {
      val reply = createTestProbe[EventSourcedWorkflowActor.Reply]()
      val entity = spawn(EventSourcedWorkflowActor("workflow-1", engine), "before-recovery")
      entity ! EventSourcedWorkflowActor.DefineWorkflow(WorkflowFixtures.linearWorkflow, 0L, reply.ref)
      reply.expectMessage(EventSourcedWorkflowActor.Defined("workflow-1", 1L))
      entity ! EventSourcedWorkflowActor.ExecuteScheduled("daily", 1000L, "daily-1000", reply.ref)
      reply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted]
      eventuallySummary(entity)(_.status shouldBe EventSourcedWorkflowActor.Completed)
      testKit.stop(entity)

      val recovered = spawn(EventSourcedWorkflowActor("workflow-1", engine), "after-recovery")
      val summary = createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
      recovered ! EventSourcedWorkflowActor.GetSummary(summary.ref)
      val recoveredSummary = summary.receiveMessage()
      recoveredSummary.revision shouldBe 1L
      recoveredSummary.status shouldBe EventSourcedWorkflowActor.Completed
      recovered ! EventSourcedWorkflowActor.ExecuteScheduled("daily", 1000L, "daily-1000", reply.ref)
      reply.expectMessageType[EventSourcedWorkflowActor.DuplicateExecution]
    }
  }
}
}
