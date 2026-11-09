package cn.xuyinyin.magic

import cn.xuyinyin.magic.workflow.cdc._
import com.typesafe.config.ConfigFactory
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import scala.concurrent.Future
import spray.json._

/** Verifies the actual standalone route assembly, not the persistence or CDC data path. */
class CdcStandaloneRoutesSpec extends AnyWordSpecLike with Matchers with ScalatestRouteTest {
  override def testConfigSource: String = "pekko.actor.provider = local"
  private val request = CdcTaskRequest("request-1", "capture-1", "orders", "g1", "target_orders")
  private val task = CdcTaskView("task-1", request.requestId, request.captureId, request.table,
    request.generation, request.targetTable, "fingerprint", "RUNNING", 1L, "QUEUED", 0L, None,
    "STARTING", None, "UNKNOWN", "NOT_ALLOCATED", None, 1L, None, true)
  private final class Service extends CdcTaskService {
    var submitted = Vector.empty[CdcTaskRequest]
    var canceled = Vector.empty[String]
    def status = CdcPlatformStatus(true, "ACTIVE", None, 0, 2)
    def captures() = Future.successful(Vector(CdcCaptureView("capture-1", "shop", "STARTING", "UNKNOWN", None)))
    def submit(value: CdcTaskRequest) = { submitted :+= value; Future.successful(CdcTaskAcceptance(task, true)) }
    def get(id: String) = Future.successful(Option.when(id == task.taskId)(task))
    def listAfter(after: Option[String], limit: Int) = Future.successful(CdcTaskPage(Vector(task), None))
    def cancel(id: String) = { canceled :+= id; Future.successful(task.copy(desiredState = "CANCELED", phase = "CANCELING")) }
  }
  private def platform(extra: String = "") = ConfigFactory.parseString(s"""
    pekko.workflow.mysql-cdc.state-jdbc {
      url="jdbc:mysql://127.0.0.1:14376/state", username="control", password="test-control",
      offset-table="offsets", history-table="history", offset-flush-interval-ms=0
    }
    pekko.workflow.cdc-platform {
      enabled=true
      captures=[{id="capture",host="127.0.0.1",port=14375,database="source",username="source_sdk",
        password="test-source",server-id=4810,source-lineage="11111111-1111-1111-1111-111111111111",
        state-lineage="22222222-2222-2222-2222-222222222222",sdk-state-username="sdk",
        sdk-state-password="test-sdk",exclusive-sdk-accounts=true $extra}]
    }
    pekko.cdc-standalone.http.port=0
  """)
  "Standalone CDC routes" should {
    "reject disabled, managed, and v2 configurations before opening resources" in {
      intercept[IllegalArgumentException](CdcStandalone.start(ConfigFactory.empty()))
      intercept[IllegalArgumentException](CdcStandalone.start(
        ConfigFactory.parseString("pekko.cdc-standalone.managed-worker=true").withFallback(platform())))
      intercept[IllegalArgumentException](CdcStandalone.start(
        ConfigFactory.parseString("pekko.workflow.snapshot-ha.enabled=true").withFallback(platform())))
      intercept[IllegalArgumentException](CdcStandalone.start(platform(",source-fence-v2=true,source-observer-username=observer,source-observer-password=observer")))
    }
    "require an explicit loopback management endpoint" in {
      CdcStandalone.httpAddress(ConfigFactory.parseString("pekko.cdc-standalone.http.port=0")) shouldBe ("127.0.0.1" -> 0)
      intercept[IllegalArgumentException](CdcStandalone.httpAddress(ConfigFactory.empty()))
      intercept[IllegalArgumentException](CdcStandalone.httpAddress(ConfigFactory.parseString(
        "pekko.cdc-standalone.http { host=0.0.0.0, port=8080 }")))
    }

    "expose CDC operations and no workflow, schedule, or front-end routes" in {
      val service = new Service
      val route = CdcStandalone.routes(service)(system.dispatcher)
      val body = JsObject("requestId" -> JsString(request.requestId), "captureId" -> JsString(request.captureId),
        "table" -> JsString(request.table), "generation" -> JsString(request.generation), "targetTable" -> JsString(request.targetTable))
      Post("/api/v1/cdc-tasks", HttpEntity(ContentTypes.`application/json`, body.compactPrint)) ~> route ~> check {
        status shouldBe StatusCodes.Accepted
        responseAs[String].parseJson.asJsObject.fields("task").asJsObject.fields("phase") shouldBe JsString("QUEUED")
      }
      Get("/api/v1/cdc-tasks/task-1") ~> route ~> check { status shouldBe StatusCodes.OK }
      Post("/api/v1/cdc-tasks/task-1/cancel") ~> route ~> check { status shouldBe StatusCodes.Accepted }
      service.submitted shouldBe Vector(request)
      service.canceled shouldBe Vector("task-1")
      for (path <- Vector("/", "/api/v1/workflows", "/api/v1/schedules")) {
        Get(path) ~> route ~> check { handled shouldBe false }
      }
    }
  }
}
