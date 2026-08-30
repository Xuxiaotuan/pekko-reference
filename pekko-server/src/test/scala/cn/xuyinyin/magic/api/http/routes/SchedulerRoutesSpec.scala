package cn.xuyinyin.magic.api.http.routes

import cn.xuyinyin.magic.workflow.scheduler.SchedulerCoordinator
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import spray.json._

class SchedulerRoutesSpec extends AnyWordSpecLike with Matchers with ScalatestRouteTest {
  "Scheduler routes" should {
    "return Created after the singleton adds a schedule" in {
      val typedSystem = system.toTyped
      val coordinator = typedSystem.systemActorOf(Behaviors.receiveMessage[SchedulerCoordinator.Command] {
        case SchedulerCoordinator.Add(schedule, replyTo) =>
          replyTo ! SchedulerCoordinator.ScheduleAdded(schedule.id)
          Behaviors.same
        case _ => Behaviors.unhandled
      }, s"scheduler-routes-${java.util.UUID.randomUUID()}")
      val routes = new SchedulerRoutes(coordinator)(typedSystem, typedSystem.executionContext).routes
      val request = JsObject(
        "id" -> JsString("daily"),
        "workflowId" -> JsString("wf-1"),
        "scheduleType" -> JsString("fixed_rate"),
        "interval" -> JsString("1h")
      )

      Post("/api/v1/schedules", HttpEntity(ContentTypes.`application/json`, request.compactPrint)) ~> routes ~> check {
        status shouldBe StatusCodes.Created
        responseAs[String].parseJson.asJsObject.fields("scheduleId") shouldBe JsString("daily")
      }
    }

    "send updates to the singleton" in {
      val typedSystem = system.toTyped
      val coordinator = typedSystem.systemActorOf(Behaviors.receiveMessage[SchedulerCoordinator.Command] {
        case SchedulerCoordinator.Update(schedule, replyTo) => replyTo ! SchedulerCoordinator.ScheduleUpdated(schedule.id); Behaviors.same
        case _ => Behaviors.unhandled
      }, s"scheduler-update-${java.util.UUID.randomUUID()}")
      val routes = new SchedulerRoutes(coordinator)(typedSystem, typedSystem.executionContext).routes
      val request = JsObject("workflowId" -> JsString("wf-1"), "scheduleType" -> JsString("fixed_rate"), "interval" -> JsString("1h"), "cronExpression" -> JsNull, "id" -> JsString("ignored"))
      Put("/api/v1/schedules/daily", HttpEntity(ContentTypes.`application/json`, request.compactPrint)) ~> routes ~> check { status shouldBe StatusCodes.OK }
    }
  }
}
