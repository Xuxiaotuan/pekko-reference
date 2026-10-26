package cn.xuyinyin.magic.api.http.routes

import cn.xuyinyin.magic.workflow.cdc._
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import scala.concurrent.Future
import spray.json._

class CdcTaskRoutesSpec extends AnyWordSpecLike with Matchers with ScalatestRouteTest {
  override def testConfigSource: String = """
    pekko.actor.provider = local
    pekko.remote.artery.canonical.port = 0
    pekko.cluster.seed-nodes = []
  """
  private val definition = CdcTaskRequest("request-a", "capture-a", "orders", "g1", "target_orders")
  private val view = CdcTaskView("task-a", definition.requestId, definition.captureId, definition.table,
    definition.generation, definition.targetTable, "fingerprint", "RUNNING", 1L, "QUEUED", 0L, None,
    "STARTING", None, "UNKNOWN", "NOT_ALLOCATED", None, 1000L, None, true)
  private def json(value: JsValue) = HttpEntity(ContentTypes.`application/json`, value.compactPrint)
  private val request = JsObject("requestId" -> JsString(definition.requestId), "captureId" -> JsString(definition.captureId),
    "table" -> JsString(definition.table), "generation" -> JsString(definition.generation), "targetTable" -> JsString(definition.targetTable))

  private class Service extends CdcTaskService {
    var submissions = Vector.empty[CdcTaskRequest]
    var pages = Vector.empty[(Option[String], Int)]
    var cancelFailure: Option[Throwable] = None
    var cancelIds = Vector.empty[String]
    def status = CdcPlatformStatus(true, "ACTIVE", None, 0, 2)
    def captures() = Future.successful(Vector(CdcCaptureView("capture-a", "shop", "STARTING", "UNKNOWN", None)))
    def submit(value: CdcTaskRequest) = { submissions :+= value; Future.successful(CdcTaskAcceptance(view, submissions.size == 1)) }
    def get(id: String) = Future.successful(if (id == view.taskId) Some(view) else None)
    def listAfter(after: Option[String], limit: Int) = { pages :+= after -> limit; Future.successful(CdcTaskPage(Vector(view), Some(view.taskId))) }
    def cancel(id: String) = {
      cancelIds :+= id
      cancelFailure.map(Future.failed[CdcTaskView]).getOrElse(Future.successful(view.copy(desiredState = "CANCELED", phase = "CANCELING")))
    }
  }

  "CDC task API" should {
    "report an unconfigured platform as unavailable instead of returning demo tasks" in {
      Get("/api/v1/cdc-tasks") ~> new CdcTaskRoutes(None)(system.dispatcher).routes ~> check {
        status shouldBe StatusCodes.ServiceUnavailable
        responseAs[String].parseJson.asJsObject.fields("error").asJsObject.fields("code") shouldBe JsString("CDC_PLATFORM_DISABLED")
      }
    }
    "accept the durable command without claiming capture or initialization readiness" in {
      val service = new Service
      Post("/api/v1/cdc-tasks", json(request)) ~> new CdcTaskRoutes(Some(service))(system.dispatcher).routes ~> check {
        status shouldBe StatusCodes.Accepted
        val task = responseAs[String].parseJson.asJsObject.fields("task").asJsObject.fields
        task("phase") shouldBe JsString("QUEUED")
        task("captureReadiness") shouldBe JsString("STARTING")
        task("initializationEvidence") shouldBe JsNull
        task("runtimeHealth") shouldBe JsString("UNKNOWN")
        task.keySet should not contain "password"
      }
      service.submissions shouldBe Vector(definition)
    }
    "reject incomplete definitions, extra credential fields and invalid pages before calling the service" in {
      val service = new Service
      val route = new CdcTaskRoutes(Some(service))(system.dispatcher).routes
      for (body <- Vector(JsObject(request.fields - "generation"), JsObject(request.fields + ("password" -> JsString("must-not-be-accepted"))),
        JsObject(request.fields + ("table" -> JsNumber(3))))) {
        Post("/api/v1/cdc-tasks", json(body)) ~> route ~> check { status shouldBe StatusCodes.BadRequest }
      }
      Get("/api/v1/cdc-tasks?pageSize=0") ~> route ~> check { status shouldBe StatusCodes.BadRequest }
      Get("/api/v1/cdc-tasks?pageSize=101") ~> route ~> check { status shouldBe StatusCodes.BadRequest }
      service.submissions shouldBe empty
      service.pages shouldBe empty
    }
    "preserve keyset pagination and actual detail without exposing connection configuration" in {
      val service = new Service
      val route = new CdcTaskRoutes(Some(service))(system.dispatcher).routes
      Get("/api/v1/cdc-tasks?after=before&pageSize=2") ~> route ~> check {
        status shouldBe StatusCodes.OK
        val result = responseAs[String].parseJson.asJsObject.fields
        result("nextAfter") shouldBe JsString("task-a")
        result("items").asInstanceOf[JsArray].elements.size shouldBe 1
      }
      service.pages shouldBe Vector(Some("before") -> 2)
      Get("/api/v1/cdc-tasks/task-a") ~> route ~> check { status shouldBe StatusCodes.OK }
      Get("/api/v1/cdc-tasks/missing") ~> route ~> check { status shouldBe StatusCodes.NotFound }
    }
    "distinguish accepted cancellation from completion and reject unsupported paused operations" in {
      val service = new Service
      val route = new CdcTaskRoutes(Some(service))(system.dispatcher).routes
      Post("/api/v1/cdc-tasks/task-a/cancel") ~> route ~> check {
        status shouldBe StatusCodes.Accepted
        responseAs[String].parseJson.asJsObject.fields("phase") shouldBe JsString("CANCELING")
      }
      service.cancelFailure = Some(new CdcPlatformException("CANCEL_NOT_PERMITTED"))
      Post("/api/v1/cdc-tasks/task-a/cancel") ~> route ~> check { status shouldBe StatusCodes.Conflict }
      service.cancelFailure = Some(new java.sql.SQLException("secret SQL and password must not escape"))
      Post("/api/v1/cdc-tasks/task-a/cancel") ~> route ~> check {
        status shouldBe StatusCodes.ServiceUnavailable
        responseAs[String] should not include "secret SQL"
        responseAs[String] should not include "password"
      }
    }
  }
}
