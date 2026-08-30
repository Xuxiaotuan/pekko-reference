package cn.xuyinyin.magic.workflow.actors

import cn.xuyinyin.magic.workflow.WorkflowFixtures
import cn.xuyinyin.magic.workflow.engine.{ExecutionResult, NodeExecutionResult, WorkflowExecutionEngine}
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.sql.DriverManager
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._
import scala.io.Source

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
        val sql = try Source.fromInputStream(input).mkString finally input.close()
        sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(statement.execute)
      } finally statement.close()
    } finally connection.close()
    ConfigFactory.parseString(
      s"""pekko-persistence-jdbc.shared-databases.slick.db.url = \"$h2Url\""""
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
}

class EventSourcedWorkflowActorRecoverySpec
    extends ScalaTestWithActorTestKit(EventSourcedWorkflowActorRecoverySpec.config)
    with AnyWordSpecLike
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

  "EventSourcedWorkflowActor recovery" should {
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
