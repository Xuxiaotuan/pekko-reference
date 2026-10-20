package cn.xuyinyin.magic.api.http.routes

import cn.xuyinyin.magic.workflow.WorkflowFixtures
import cn.xuyinyin.magic.workflow.actors.{EventSourcedWorkflowActor, WorkflowSupervisor}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import spray.json._

class WorkflowRoutesSpec extends AnyWordSpecLike with Matchers with ScalatestRouteTest {
  import cn.xuyinyin.magic.workflow.model.WorkflowDSL._

  "Workflow routes" should {
    "expose a paused recovery without dropping the execution context" in {
      import cn.xuyinyin.magic.workflow.events.WorkflowEvents._
      import cn.xuyinyin.magic.workflow.engine.ExecutionFailure
      val typedSystem=system.toTyped
      val current=EventSourcedWorkflowActor.ExecutionState("same-execution",ExecutionTrigger("manual"),1L,
        recovery=Some(ExecutionRecoveryState(3,3,"blocked",0L,ExecutionFailure.BudgetExhausted,"target-commit",
          Some(ExecutionFailure(ExecutionFailure.CommitUnknown,"target-commit",Some("batch-id"))))))
      val supervisor=typedSystem.systemActorOf(Behaviors.receiveMessage[WorkflowSupervisor.Command] {
        case WorkflowSupervisor.GetWorkflowSummary(id,replyTo) =>
          replyTo ! EventSourcedWorkflowActor.WorkflowSummary(id,1L,EventSourcedWorkflowActor.WorkflowStatus("recovery_blocked"),Some(current),Vector.empty)
          Behaviors.same
        case _ => Behaviors.unhandled
      },s"recovery-summary-${java.util.UUID.randomUUID()}")
      val routes=new EnhancedWorkflowRoutes(supervisor)(typedSystem,typedSystem.executionContext).routes
      Get("/api/v1/workflows/paused") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val e=responseAs[String].parseJson.asJsObject.fields("currentExecution").asJsObject
        e.fields("executionId") shouldBe JsString("same-execution")
        val r=e.fields("recovery").asJsObject
        r.fields("attemptsStarted") shouldBe JsNumber(3)
        r.fields("primaryFailureCode") shouldBe JsString(ExecutionFailure.CommitUnknown)
        r.fields("batchId") shouldBe JsString("batch-id")
      }
    }

    "classify infrastructure failures consistently" in {
      HttpErrorMapping.status(new org.apache.pekko.pattern.AskTimeoutException("timeout")) shouldBe StatusCodes.GatewayTimeout
      HttpErrorMapping.status(new java.sql.SQLException("down")) shouldBe StatusCodes.ServiceUnavailable
      HttpErrorMapping.status(new RuntimeException("bug")) shouldBe StatusCodes.InternalServerError
    }
    "return explicit overload and deadline statuses for list queries" in {
      val typedSystem = system.toTyped
      val supervisor = typedSystem.systemActorOf(Behaviors.ignore[WorkflowSupervisor.Command], s"list-errors-${java.util.UUID.randomUUID()}")
      val failures = Vector(
        new java.util.concurrent.RejectedExecutionException("workflow query capacity exhausted") -> StatusCodes.ServiceUnavailable,
        new java.util.concurrent.TimeoutException("workflow list deadline") -> StatusCodes.GatewayTimeout
      )
      failures.foreach { case (failure, expected) =>
        val query = new cn.xuyinyin.magic.workflow.query.WorkflowQueryService(typedSystem, supervisor)(typedSystem.executionContext) {
          override def list(page: Int, requestedPageSize: Int) = scala.concurrent.Future.failed(failure)
        }
        val routes = new EnhancedWorkflowRoutes(supervisor, Some(query))(typedSystem, typedSystem.executionContext).routes
        Get("/api/v1/workflows?page=0&pageSize=50") ~> routes ~> check {
          status shouldBe expected
          responseAs[String].parseJson.asJsObject.fields("operation") shouldBe JsString("list")
        }
      }
    }

    "return Created only after the entity confirms the definition" in {
      val typedSystem = system.toTyped
      val supervisor = typedSystem.systemActorOf(Behaviors.receiveMessage[WorkflowSupervisor.Command] {
        case WorkflowSupervisor.DefineWorkflow(workflow, _, replyTo) =>
          replyTo ! EventSourcedWorkflowActor.Defined(workflow.id, 1L)
          Behaviors.same
        case _ => Behaviors.unhandled
      }, s"workflow-routes-${java.util.UUID.randomUUID()}")
      val routes = new EnhancedWorkflowRoutes(supervisor)(typedSystem, typedSystem.executionContext).routes

      Post("/api/v1/workflows", HttpEntity(ContentTypes.`application/json`, WorkflowFixtures.linearWorkflow.toJson.compactPrint)) ~> routes ~> check {
        status shouldBe StatusCodes.Created
        responseAs[String].parseJson.asJsObject.fields("workflowId") shouldBe JsString("linear")
      }
    }

    "return NotFound for an uninitialized status entity" in {
      val typedSystem = system.toTyped
      val supervisor = typedSystem.systemActorOf(Behaviors.receiveMessage[WorkflowSupervisor.Command] {
        case WorkflowSupervisor.GetWorkflowStatus(id, replyTo) =>
          replyTo ! EventSourcedWorkflowActor.StatusResponse(id, "uninitialized", None, Nil)
          Behaviors.same
        case _ => Behaviors.unhandled
      }, s"workflow-status-${java.util.UUID.randomUUID()}")
      val routes = new EnhancedWorkflowRoutes(supervisor)(typedSystem, typedSystem.executionContext).routes

      Get("/api/v1/workflows/missing/status") ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }
  }
}
