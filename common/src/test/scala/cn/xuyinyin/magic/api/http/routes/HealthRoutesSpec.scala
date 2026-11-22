package cn.xuyinyin.magic.api.http.routes

import cn.xuyinyin.magic.infrastructure.cluster.{HealthChecker}
import cn.xuyinyin.magic.bootstrap.{PekkoGuardian}
import cn.xuyinyin.magic.workflow.actors.WorkflowSupervisor
import cn.xuyinyin.magic.workflow.scheduler.SchedulerCoordinator
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Future

class HealthRoutesSpec extends AnyWordSpecLike with Matchers with ScalatestRouteTest {
  private def routes(status: HealthChecker.ReadinessStatus) = {
    val typed = system.toTyped
    val suffix = java.util.UUID.randomUUID().toString
    HttpRoutes.createRoutes(
      typed,
      typed.systemActorOf(Behaviors.ignore[HealthChecker.Command], s"health-$suffix"),
      typed.systemActorOf(Behaviors.ignore[PekkoGuardian.Command], s"guardian-$suffix"),
      typed.systemActorOf(Behaviors.ignore[SchedulerCoordinator.Command], s"scheduler-$suffix"),
      typed.systemActorOf(Behaviors.ignore[WorkflowSupervisor.Command], s"workflow-$suffix"),
      Some(HealthChecker.ReadinessProbes(() => Future.successful(status.memberUp), () => Future.successful(status.shardingInitialized), () => Future.successful(status.jdbcAvailable)))
    )(typed.executionContext)
  }

  "Health routes" should {
    "serve the documented API status resource" in { Get("/api/v1/status") ~> routes(HealthChecker.ReadinessStatus(true,true,true)) ~> check { status shouldBe StatusCodes.OK } }
    "report actual readiness for the compatibility health resource" in {
      Get("/health") ~> routes(HealthChecker.ReadinessStatus(true,true,false)) ~> check {
        status shouldBe StatusCodes.ServiceUnavailable
        responseAs[String] should not include "Overall: HEALTHY"
      }
    }
    "keep liveness process-only" in { Get("/health/live") ~> routes(HealthChecker.ReadinessStatus(false, false, false)) ~> check { status shouldBe StatusCodes.OK } }
    "require all readiness dependencies" in {
      Seq(HealthChecker.ReadinessStatus(false, true, true), HealthChecker.ReadinessStatus(true, false, true), HealthChecker.ReadinessStatus(true, true, false)).foreach { state =>
        Get("/health/ready") ~> routes(state) ~> check { status shouldBe StatusCodes.ServiceUnavailable }
      }
    }
    "be ready only when every dependency is ready" in { Get("/health/ready") ~> routes(HealthChecker.ReadinessStatus(true, true, true)) ~> check { status shouldBe StatusCodes.OK } }
  }
}
