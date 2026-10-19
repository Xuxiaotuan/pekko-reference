package cn.xuyinyin.magic.api.http.routes

import cn.xuyinyin.magic.cluster.{HealthChecker, PekkoGuardian}
import cn.xuyinyin.magic.workflow.actors.WorkflowSupervisor
import cn.xuyinyin.magic.workflow.scheduler.SchedulerCoordinator
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import spray.json._

/** Prevent unavailable observations being presented as measured zeros or this node's ownership. */
class CapabilityRoutesSpec extends AnyWordSpecLike with Matchers with ScalatestRouteTest {
  override def testConfigSource: String = "pekko.remote.artery.canonical.port = 0\npekko.cluster.seed-nodes = []"
  private def clusterRoutes = new ClusterRoutes()(system.toTyped, system.dispatcher).routes

  "Cluster observations" should {
    "preserve member information without inventing shard or entity counts" in {
      Get("/api/v1/cluster/stats") ~> clusterRoutes ~> check {
        status shouldBe StatusCodes.OK
        val json = responseAs[String].parseJson.asJsObject
        json.fields("members") shouldBe a[JsArray]
        val shard = json.fields("sharding").asJsObject.fields
        shard("activeShards") shouldBe JsNull
        shard("totalEntities") shouldBe JsNull
        shard("availability") shouldBe JsString("unavailable")
      }
    }
    "return no fabricated shard distribution" in {
      Get("/api/v1/cluster/shards") ~> clusterRoutes ~> check {
        val fields = responseAs[String].parseJson.asJsObject.fields
        fields("availability") shouldBe JsString("unavailable")
        fields("shards") shouldBe JsNull
      }
    }
    "avoid claiming this node owns an arbitrary workflow" in {
      Get("/api/v1/cluster/workflow/arbitrary-id/location") ~> clusterRoutes ~> check {
        val fields = responseAs[String].parseJson.asJsObject.fields
        fields("nodeAddress") shouldBe JsNull
        fields("availability") shouldBe JsString("unavailable")
      }
    }
    "mark the legacy static metrics endpoint unavailable" in {
      val typed = system.toTyped
      val routes = HttpRoutes.createRoutes(typed,
        typed.systemActorOf(Behaviors.ignore[HealthChecker.Command], "cap-health"),
        typed.systemActorOf(Behaviors.ignore[PekkoGuardian.Command], "cap-guardian"),
        typed.systemActorOf(Behaviors.ignore[SchedulerCoordinator.Command], "cap-scheduler"),
        typed.systemActorOf(Behaviors.ignore[WorkflowSupervisor.Command], "cap-workflow"))(typed.executionContext)
      Get("/monitoring/metrics") ~> routes ~> check {
        status shouldBe StatusCodes.ServiceUnavailable
        responseAs[String].parseJson.asJsObject.fields("availability") shouldBe JsString("unavailable")
      }
    }
  }
}
