package cn.xuyinyin.magic.workflow.actors

import cn.xuyinyin.magic.workflow.WorkflowFixtures
import cn.xuyinyin.magic.workflow.engine.{ExecutionResult, NodeExecutionResult, WorkflowExecutionEngine}
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.SerializationTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.sql.DriverManager
import scala.io.Source
import scala.concurrent.{Future, Promise}
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
        val sql = try Source.fromInputStream(input).mkString finally input.close()
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
    with Matchers {

  private implicit val executionContext: scala.concurrent.ExecutionContext = system.executionContext

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
