package cn.xuyinyin.magic.server

import cn.xuyinyin.magic.core.cluster.PekkoGuardian
import cn.xuyinyin.magic.api.http.routes.HttpRoutes
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.util.Timeout

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import scala.concurrent.Await
import scala.concurrent.ExecutionContext

/**
 * Pekko集群服务
 * 
 * 负责管理Pekko集群的完整生命周期，包括：
 * 1. ActorSystem初始化
 * 2. 集群角色管理
 * 3. HTTP服务启动（基于角色）
 * 4. 优雅关闭机制
 * 
 * @author : Xuxiaotuan
 * @since : 2024-09-21 22:18
 */
object PekkoClusterService {
  private val logger = Logger(getClass)
  
  /**
   * 启动Pekko集群服务
   * 
   * @param systemName 系统名称
   * @param config 配置对象
   * @param port 端口号
   */
  def start(systemName: String, config: Config, port: Int = 2551): Unit = {
    // 创建动态配置，覆盖端口设置
    val dynamicConfig = {
      val portConfig = s"""
        pekko.remote.artery.canonical.port = $port
        pekko.cluster.seed-nodes = ["pekko://$systemName@127.0.0.1:2551"]
      """
      com.typesafe.config.ConfigFactory.parseString(portConfig).withFallback(config)
    }
    
    implicit val system: ActorSystem[PekkoGuardian.Command] = 
      ActorSystem(PekkoGuardian(), systemName, dynamicConfig)
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val timeout: Timeout = Timeout(5.seconds)
    implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = system.scheduler
    
    // 获取集群角色信息
    val cluster = Cluster(system)
    val currentRoles = cluster.selfMember.roles
    
    logger.info(s"Pekko cluster system started with roles: ${currentRoles.mkString(", ")}")
    
    // 注册优雅关闭钩子
    registerShutdownHook(system)
    
    // 根据角色启动服务
    startServicesByRole(currentRoles, system, system.scheduler)
    
    logger.info("Pekko server started successfully!")
    
    // 保持服务运行
    waitForTermination(system)
  }
  
  /**
   * 根据节点角色启动相应的服务
   * 
   * @param roles 当前节点角色列表
   * @param system ActorSystem实例
   * @param scheduler 调度器实例
   */
  private def startServicesByRole(
    roles: Set[String], 
    system: ActorSystem[PekkoGuardian.Command],
    scheduler: org.apache.pekko.actor.typed.Scheduler
  )(implicit ec: ExecutionContextExecutor, timeout: Timeout): Unit = {
    if (roles.contains("api-gateway")) {
      startHttpServer(system, scheduler)
    } else {
      logger.info("Current node does not have api-gateway role, HTTP server not started")
    }
  }
  
  /**
   * 启动HTTP服务器
   * 
   * @param system ActorSystem实例
   * @param scheduler 调度器实例
   */
  private def startHttpServer(
    system: ActorSystem[PekkoGuardian.Command],
    scheduler: org.apache.pekko.actor.typed.Scheduler
  )(implicit ec: ExecutionContextExecutor, timeout: Timeout): Unit = {
    logger.info("Starting HTTP server with Task Scheduling support...")
    
    // Guardian引用就是system本身
    val guardian = system
    
    // 获取HealthChecker引用
    val healthCheckerFuture = system.ask(ref => PekkoGuardian.GetHealthChecker(ref))(timeout, scheduler)
    
    healthCheckerFuture.onComplete {
      case Success(healthChecker) =>
        val routes = HttpRoutes.createRoutes(system, healthChecker, guardian)
        implicit val classicSystem = system.classicSystem
        val bindingFuture = Http().newServerAt("localhost", 8080).bind(routes)
        
        bindingFuture.onComplete {
          case Success(binding) =>
            val address = binding.localAddress
            logger.info(s"HTTP server successfully started at http://${address.getHostString}:${address.getPort}/")
            logger.info("Available HTTP endpoints:")
            logger.info("  - API Status: http://localhost:8080/api/v1/status")
            logger.info("  - Task Submit: POST http://localhost:8080/api/v1/tasks")
            logger.info("  - Task Statistics: http://localhost:8080/api/v1/tasks/statistics")
            logger.info("  - Task Status: http://localhost:8080/api/v1/tasks/{taskId}")
            logger.info("  - Health Check: http://localhost:8080/health")
            logger.info("  - Monitoring: http://localhost:8080/monitoring/")
            
          case Failure(exception) =>
            logger.error(s"Failed to bind HTTP server: ${exception.getMessage}")
            system.terminate()
        }
        
      case Failure(exception) =>
        logger.error(s"Failed to get HealthChecker reference: ${exception.getMessage}")
        system.terminate()
    }
  }
  
  /**
   * 注册优雅关闭钩子
   * 
   * @param system ActorSystem实例
   */
  private def registerShutdownHook(system: ActorSystem[_]): Unit = {
    sys.addShutdownHook {
      logger.info("Shutdown hook triggered, terminating actor system...")
      system.terminate()
    }
  }
  
  /**
   * 等待系统终止信号
   * 
   * @param system ActorSystem实例
   */
  private def waitForTermination(system: ActorSystem[_])(implicit ec: ExecutionContext): Unit = {
    system.whenTerminated.foreach { _ =>
      logger.info("Pekko server terminated")
    }
    
    try {
      Await.result(system.whenTerminated, Duration.Inf)
    } catch {
      case _: InterruptedException =>
        logger.info("Server interrupted, shutting down...")
    }
  }
}
