package cn.xuyinyin.magic.api.http.routes

import cn.xuyinyin.magic.infrastructure.cluster.{HealthChecker, ReadinessCheck}
import cn.xuyinyin.magic.bootstrap.{PekkoGuardian}
import cn.xuyinyin.magic.workflow.actors.WorkflowSupervisor
import cn.xuyinyin.magic.workflow.scheduler.SchedulerCoordinator
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
 * HTTP服务路由
 * 
 * 提供完整的HTTP服务接口，包括：
 * - API接口
 * - 任务管理
 * - 健康检查
 * - 系统监控
 * 
 * @author : Xuxiaotuan
 * @since : 2024-09-21 22:18
 */
object HttpRoutes {

  /**
   * 创建HTTP服务路由
   */
  def createRoutes(
    system: ActorSystem[_],
    healthChecker: ActorRef[HealthChecker.Command],
    guardian: ActorRef[PekkoGuardian.Command],
    schedulerCoordinator: ActorRef[SchedulerCoordinator.Command],
    workflowSupervisor: ActorRef[WorkflowSupervisor.Command],
    readinessProbes: Option[HealthChecker.ReadinessProbes] = None
  )(implicit ec: ExecutionContext): Route = {
    implicit val readinessTimeout: org.apache.pekko.util.Timeout = 2.seconds
    implicit val readinessScheduler: org.apache.pekko.actor.typed.Scheduler = system.scheduler
    val shardingProbe = () => workflowSupervisor.ask[cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor.WorkflowSummary](replyTo => WorkflowSupervisor.GetWorkflowSummary("__readiness_probe__", replyTo)).map(_ => true)
    val probes = readinessProbes.getOrElse(HealthChecker.defaultReadinessProbes(system, shardingProbe))
    val readiness = new ReadinessCheck(probes, system.settings.config.getDuration("pekko.workflow.readiness-timeout").toMillis.millis)(system, ec)
    def readinessRoute: Route = get {
      complete(readiness.check().map { result =>
        val status = if (result.ready) StatusCodes.OK else StatusCodes.ServiceUnavailable
        status -> HttpEntity(ContentTypes.`text/plain(UTF-8)`, s"memberUp=${result.memberUp} shardingInitialized=${result.shardingInitialized} jdbcAvailable=${result.jdbcAvailable}")
      })
    }
    concat(
      // 工作流管理API (DSL可视化 + Event Sourcing + 调度)
      new EnhancedWorkflowRoutes(workflowSupervisor)(system, ec).routes,
      new SnapshotSchedulingRoutes(cn.xuyinyin.magic.workflow.scheduling.TableSchedulingExtension(system).runtime).routes,
      
      // 执行历史查询API (Event Sourcing)
      new EventHistoryRoutes(workflowSupervisor)(system, ec).routes,
      
      // 调度管理API
      new SchedulerRoutes(schedulerCoordinator)(system, ec).routes,
      
      // 集群监控API (Cluster Sharding)
      new ClusterRoutes()(system, ec).routes,
      
      // Prometheus指标端点
      new MetricsRoutes().routes,
      
      path("api" / "v1" / "status") {
        get { complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, s"Pekko CDC / Workflow API; timestamp=${System.currentTimeMillis()}")) }
      },
      pathPrefix("health") {
        concat(
          path("live") { get { complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, "process=alive")) } },
          path("ready") { readinessRoute },
          pathEndOrSingleSlash { readinessRoute }
        )
      },

      // 监控路径
      pathPrefix("monitoring") {
        concat(
          pathPrefix("cluster") {
            path("status") {
              get {
                complete(getClusterStatus(system))
              }
            }
          },
          path("metrics") {
            get {
              complete(StatusCodes.ServiceUnavailable -> spray.json.JsObject(
                "availability" -> spray.json.JsString("unavailable"),
                "reason" -> spray.json.JsString("System metrics are not collected by this endpoint; /metrics exposes the existing Prometheus registry")
              ))
            }
          }
        )
      },
      
      // 根路径，显示所有可用端点
      pathEndOrSingleSlash {
        get {
          val endpoints = 
            """
              |Pekko HTTP Server
              |Available endpoints:
              |
              |API Endpoints:
              |- GET /api/v1/status - API status
              |
              |Health Endpoints:
              |- GET /health - Overall health status
              |- GET /health/live - Liveness probe
              |- GET /health/ready - Readiness probe
              |
              |Monitoring Endpoints:
              |- GET /monitoring/cluster/status - Cluster status
              |- GET /monitoring/metrics - System metrics
              |""".stripMargin
          complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, endpoints))
        }
      }
    )
  }

  /**
   * 获取集群状态
   */
  private def getClusterStatus(system: ActorSystem[_]): String = {
    val cluster = Cluster(system)
    val selfMember = cluster.selfMember
    val members = cluster.state.members
    val unreachableMembers = cluster.state.unreachable
    val leader = cluster.state.leader
    
    s"""
       |Cluster Status:
       |Self: ${selfMember.address} (roles: ${selfMember.roles.mkString(", ")})
       |Leader: ${leader.getOrElse("None")}
       |Members: ${members.size}
       |${members.map(m => s"  - ${m.address} (${m.status}, roles: ${m.roles.mkString(", ")})").mkString("\n")}
       |Unreachable: ${unreachableMembers.size}
       |${unreachableMembers.map(m => s"  - ${m.address}").mkString("\n")}
       |""".stripMargin
  }
}
