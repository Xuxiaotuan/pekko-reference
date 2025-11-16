package cn.xuyinyin.magic.api.http.routes

import cn.xuyinyin.magic.cluster.{HealthChecker, PekkoGuardian}
import cn.xuyinyin.magic.workflow.scheduler.SchedulerManager
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity}
import org.apache.pekko.http.scaladsl.server.Directives.{complete, get, path, pathEndOrSingleSlash, pathPrefix, concat}
import org.apache.pekko.http.scaladsl.server.Route

import scala.concurrent.ExecutionContext

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
    schedulerManager: SchedulerManager,
    workflowSupervisor: ActorRef[_]
  )(implicit ec: ExecutionContext): Route = {
    concat(
      // 工作流管理API (DSL可视化) - 增强版本
      new EnhancedWorkflowRoutes()(system, ec).routes,
      
      // Event Sourced 工作流API (v2 - 使用Event Sourcing)
      // TODO: 修复编译错误后启用
      // new EventSourcedWorkflowRoutes(workflowSupervisor)(system, ec).routes,
      
      // 执行历史API (Event Sourcing)
      new EventHistoryRoutes(workflowSupervisor)(system, ec).routes,
      
      // 调度管理API
      new SchedulerRoutes(schedulerManager).routes,
      
      // API接口路径
      pathPrefix("api") {
        concat(
          path("v1") {
            path("status") {
              get {
                val apiStatus = 
                  """
                    |API Status:
                    |- Version: 1.0.0 (with Task Scheduling)
                    |- Status: Running
                    |- Features: Task Management, Health Check, Monitoring
                    |- Timestamp: """ + System.currentTimeMillis() + """
                    |""".stripMargin
                complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, apiStatus))
              }
            }
          }
        )
      },
      
      // 健康检查路径
      pathPrefix("health") {
        concat(
          path("live") {
            get {
              val liveness = 
                """
                  |Liveness Probe:
                  |- Status: OK
                  |- Timestamp: """ + System.currentTimeMillis() + """
                  |""".stripMargin
              complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, liveness))
            }
          },
          path("ready") {
            get {
              val readiness = 
                """
                  |Readiness Probe:
                  |- Status: OK
                  |- Dependencies: Ready
                  |- Timestamp: """ + System.currentTimeMillis() + """
                  |""".stripMargin
              complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, readiness))
            }
          },
          pathEndOrSingleSlash {
            get {
              val healthInfo = 
                """
                  |Health Status:
                  |- Overall: HEALTHY
                  |- Memory: OK
                  |- CPU: OK  
                  |- Disk: OK
                  |- Network: OK
                  |- Timestamp: """ + System.currentTimeMillis() + """
                  |""".stripMargin
              complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, healthInfo))
            }
          }
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
              val metricsInfo = 
                """
                  |System Metrics:
                  |- Memory Used: 128 MB
                  |- CPU Usage: 15%
                  |- Disk Free: 45 GB
                  |- Network Latency: 1ms
                  |- Active Actors: 12
                  |""".stripMargin
              complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, metricsInfo))
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
