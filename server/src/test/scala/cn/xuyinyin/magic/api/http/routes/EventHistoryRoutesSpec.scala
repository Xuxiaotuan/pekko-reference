package cn.xuyinyin.magic.api.http.routes

import cn.xuyinyin.magic.workflow.actors.{EventSourcedWorkflowActor, WorkflowSupervisor}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import spray.json._

class EventHistoryRoutesSpec extends AnyWordSpecLike with Matchers with ScalatestRouteTest {
  "Event history routes" should {
    "return the state reported by the workflow entity" in {
      val typedSystem = system.toTyped
      val supervisor = typedSystem.systemActorOf(Behaviors.receiveMessage[WorkflowSupervisor.Command] {
        case WorkflowSupervisor.GetWorkflowStatus("wf-1", replyTo) =>
          replyTo ! EventSourcedWorkflowActor.StatusResponse("wf-1", "running", None, Nil)
          Behaviors.same
        case _ => Behaviors.unhandled
      }, s"history-routes-${java.util.UUID.randomUUID()}")
      val routes = new EventHistoryRoutes(supervisor)(typedSystem, typedSystem.executionContext).routes

      Get("/api/history/wf-1/status") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String].parseJson.asJsObject.fields("state") shouldBe JsString("running")
      }
    }

    "return persisted node attribution and errors" in {
      val typedSystem = system.toTyped
      val supervisor = typedSystem.systemActorOf(Behaviors.receiveMessage[WorkflowSupervisor.Command] {
        case WorkflowSupervisor.GetWorkflowSummary("wf-errors", replyTo) =>
          replyTo ! EventSourcedWorkflowActor.WorkflowSummary(
            "wf-errors",
            1L,
            EventSourcedWorkflowActor.Failed,
            None,
            Vector(EventSourcedWorkflowActor.ExecutionSummary("exec-1", 1L, Some(8L), "failed", Some(7L)))
          )
          Behaviors.same
        case WorkflowSupervisor.GetExecutionHistory("wf-errors", replyTo) =>
          val node = EventSourcedWorkflowActor.NodeExecutionDetail("sink", "mysql", None, None, Some(7L), "failed", None, Some("connection refused"))
          replyTo ! EventSourcedWorkflowActor.ExecutionHistoryResponse(
            "wf-errors",
            List(EventSourcedWorkflowActor.ExecutionDetail("exec-1", "workflow", 1L, Some(8L), "failed", Some(7L), List(node)))
          )
          Behaviors.same
        case _ => Behaviors.unhandled
      }, s"history-errors-${java.util.UUID.randomUUID()}")
      val routes = new EventHistoryRoutes(supervisor)(typedSystem, typedSystem.executionContext).routes

      Get("/api/history/wf-errors") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val node = responseAs[String].parseJson.asJsObject.fields("executions").asInstanceOf[JsArray].elements.head
          .asJsObject.fields("nodes").asInstanceOf[JsArray].elements.head.asJsObject
        node.fields("nodeId") shouldBe JsString("sink")
        node.fields("error") shouldBe JsString("connection refused")
        node.fields should not contain key("startTime")
        node.fields should not contain key("recordsProcessed")
      }
    }
  }
}
