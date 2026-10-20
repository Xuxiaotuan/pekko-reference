package cn.xuyinyin.magic.server

import cn.xuyinyin.magic.cluster.PekkoGuardian
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.management.cluster.bootstrap.ClusterBootstrap
import org.apache.pekko.management.scaladsl.PekkoManagement

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
  def start(systemName: String, config: Config, port: Option[Int] = None): Unit = {
    val dynamicConfig = port
      .map(value => com.typesafe.config.ConfigFactory.parseString(s"pekko.remote.artery.canonical.port = $value").withFallback(config))
      .getOrElse(config)
    
    implicit val system: ActorSystem[PekkoGuardian.Command] = 
      ActorSystem(PekkoGuardian(), systemName, dynamicConfig)
    implicit val ec: ExecutionContextExecutor = system.executionContext

    if (shouldStartClusterBootstrap(dynamicConfig)) {
      PekkoManagement(system).start().failed.foreach { exception =>
        logger.error(s"Failed to bind Pekko Management: ${exception.getMessage}")
        system.terminate()
      }
      ClusterBootstrap(system).start()
    }
    
    // 获取集群角色信息
    val cluster = Cluster(system)
    val currentRoles = cluster.selfMember.roles
    
    logger.info(s"Pekko cluster system started with roles: ${currentRoles.mkString(", ")}")
    
    // Pekko CoordinatedShutdown owns the JVM hook and service shutdown phases.
    
    // 根据角色启动服务
    startServicesByRole(currentRoles, system)
    
    logger.info("ActorSystem initialized; HTTP binding result is reported separately")
    
    // 保持服务运行
    waitForTermination(system)
  }

  private[server] def shouldStartClusterBootstrap(config: Config): Boolean =
    config.hasPath("pekko.workflow.cluster-bootstrap.enabled") &&
      config.getBoolean("pekko.workflow.cluster-bootstrap.enabled")
  
  /**
   * 根据节点角色启动相应的服务
   * 
   * @param roles 当前节点角色列表
   * @param system ActorSystem实例
   */
  private def startServicesByRole(
    roles: Set[String], 
    system: ActorSystem[PekkoGuardian.Command]
  )(implicit ec: ExecutionContextExecutor): Unit = {
    if (roles.contains("api-gateway")) {
      startHttpServer(system)
    } else {
      logger.info("Current node does not have api-gateway role, HTTP server not started")
    }
  }
  
  /**
   * 启动HTTP服务器
   * 
   * @param system ActorSystem实例
   */
  private def startHttpServer(
    system: ActorSystem[PekkoGuardian.Command]
  )(implicit ec: ExecutionContextExecutor): Unit = {
    cn.xuyinyin.magic.server.http.HttpServerLifecycle.start(system).onComplete {
      case Success(binding) => logger.info(s"HTTP server bound at ${binding.localAddress}; coordinated unbind/drain registered")
      case Failure(exception) =>
        logger.error("HTTP startup failed", exception)
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
        system.terminate()
    }
  }
}
