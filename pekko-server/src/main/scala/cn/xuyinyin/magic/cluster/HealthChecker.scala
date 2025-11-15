package cn.xuyinyin.magic.cluster

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.util.Timeout
import com.typesafe.scalalogging.Logger

import java.lang.management.ManagementFactory
import scala.concurrent.duration._
import scala.util.{Success, Failure}

/**
 * 节点健康检查器
 * 
 * 监控集群节点的健康状态，包括系统资源、Actor状态和网络连接检查。
 * 提供周期性健康检查和按需健康状态查询功能。
 * 
 * @author : Xuxiaotuan
 * @since : 2024-09-21 22:18
 */
object HealthChecker {

  private val logger = Logger(getClass)

  /**
   * 健康检查命令接口
   */
  sealed trait Command
  final case class CheckHealth(replyTo: ActorRef[HealthStatus]) extends Command
  final case class StartPeriodicCheck(intervalMs: Long) extends Command
  final case class StopPeriodicCheck() extends Command
  final case class GetMetrics(replyTo: ActorRef[SystemMetrics]) extends Command
  private case class PeriodicCheck() extends Command

  /**
   * 健康状态数据结构
   */
  final case class HealthStatus(
    isHealthy: Boolean,
    timestamp: Long,
    checks: Map[String, Boolean],
    overallScore: Double,
    issues: List[String]
  )

  /**
   * 系统指标数据结构
   */
  final case class SystemMetrics(
    memoryUsage: MemoryMetrics,
    cpuUsage: Double,
    actorSystemMetrics: ActorMetrics,
    diskSpace: DiskMetrics,
    networkStatus: NetworkMetrics
  )

  final case class MemoryMetrics(
    used: Long,
    max: Long,
    usagePercentage: Double
  )

  final case class ActorMetrics(
    totalActors: Int,
    activeActors: Int,
    deadLetters: Long
  )

  final case class DiskMetrics(
    freeSpace: Long,
    totalSpace: Long,
    usagePercentage: Double
  )

  final case class NetworkMetrics(
    isReachable: Boolean,
    responseTime: Long,
    connectionsActive: Int
  )

  /**
   * 健康检查阈值配置
   */
  object Thresholds {
    val MEMORY_WARNING_THRESHOLD = 80.0  // 内存使用率警告阈值
    val MEMORY_CRITICAL_THRESHOLD = 90.0 // 内存使用率严重阈值
    val CPU_WARNING_THRESHOLD = 70.0     // CPU使用率警告阈值
    val CPU_CRITICAL_THRESHOLD = 85.0    // CPU使用率严重阈值
    val DISK_WARNING_THRESHOLD = 85.0    // 磁盘使用率警告阈值
    val DISK_CRITICAL_THRESHOLD = 95.0   // 磁盘使用率严重阈值
    val NETWORK_TIMEOUT_MS = 5000        // 网络检查超时时间
  }

  def apply(): Behavior[Command] = Behaviors.setup { ctx =>
    Behaviors.withTimers { timers =>
      ctx.log.info("HealthChecker starting up")
      
      var checkInterval: Long = 30000L // 默认30秒检查一次
      var isPeriodicCheckActive = false

      def startPeriodicCheck(): Unit = {
        if (isPeriodicCheckActive) {
          timers.cancel(PeriodicCheck())
        }
        timers.startTimerWithFixedDelay(PeriodicCheck(), Duration(checkInterval, MILLISECONDS))
        isPeriodicCheckActive = true
        ctx.log.info(s"Started periodic health check every ${checkInterval}ms")
      }

      def stopPeriodicCheck(): Unit = {
        if (isPeriodicCheckActive) {
          timers.cancel(PeriodicCheck())
          isPeriodicCheckActive = false
        }
        ctx.log.info("Stopped periodic health check")
      }

      Behaviors.receiveMessage {
        case CheckHealth(replyTo) =>
          ctx.log.debug("Performing health check")
          val status = performHealthCheck(ctx)
          replyTo ! status
          
          // 如果健康状态有问题，记录警告
          if (!status.isHealthy) {
            ctx.log.warn(s"Health check failed with score ${status.overallScore}%.1f%%: ${status.issues.mkString(", ")}")
          }
          
          Behaviors.same

        case StartPeriodicCheck(intervalMs) =>
          checkInterval = intervalMs
          startPeriodicCheck()
          Behaviors.same

        case StopPeriodicCheck() =>
          stopPeriodicCheck()
          Behaviors.same

        case GetMetrics(replyTo) =>
          ctx.log.debug("Collecting system metrics")
          val metrics = collectSystemMetrics()
          replyTo ! metrics
          Behaviors.same

        case PeriodicCheck() =>
          ctx.log.debug("Performing periodic health check")
          val status = performHealthCheck(ctx)
          
          if (!status.isHealthy) {
            ctx.log.warn(s"Periodic health check failed: ${status.issues.mkString(", ")}")
          } else {
            ctx.log.debug(s"Periodic health check passed with score ${status.overallScore}%.1f%%")
          }
          
          Behaviors.same
      }
    }
  }

  /**
   * 执行完整的健康检查
   */
  private def performHealthCheck(ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command]): HealthStatus = {
    val checks = Map(
      "memory" -> checkMemory(),
      "cpu" -> checkCpu(),
      "disk" -> checkDisk(),
      "actors" -> checkActors(),
      "network" -> checkNetwork()
    )
    
    val failedChecks = checks.filter(!_._2).keys.toList
    val overallScore = (checks.values.count(identity).toDouble / checks.size) * 100
    val isHealthy = overallScore >= 80.0 && failedChecks.isEmpty
    
    HealthStatus(
      isHealthy = isHealthy,
      timestamp = System.currentTimeMillis(),
      checks = checks,
      overallScore = overallScore,
      issues = failedChecks.map(check => s"Health check failed for: $check")
    )
  }

  /**
   * 检查内存使用情况
   */
  private def checkMemory(): Boolean = {
    try {
      val runtime = Runtime.getRuntime
      val usedMemory = runtime.totalMemory() - runtime.freeMemory()
      val maxMemory = runtime.maxMemory()
      val usagePercentage = (usedMemory.toDouble / maxMemory) * 100
      
      usagePercentage < Thresholds.MEMORY_CRITICAL_THRESHOLD
    } catch {
      case ex: Exception =>
        logger.error("Memory check failed", ex)
        false
    }
  }

  /**
   * 检查CPU使用情况
   */
  private def checkCpu(): Boolean = {
    try {
      val osBean = ManagementFactory.getOperatingSystemMXBean.asInstanceOf[com.sun.management.OperatingSystemMXBean]
      val cpuUsage = osBean.getProcessCpuLoad * 100
      
      // 如果CPU使用率为负数（可能是因为系统刚启动），认为是健康的
      cpuUsage < 0 || cpuUsage < Thresholds.CPU_CRITICAL_THRESHOLD
    } catch {
      case ex: Exception =>
        logger.error("CPU check failed", ex)
        false
    }
  }

  /**
   * 检查磁盘空间
   */
  private def checkDisk(): Boolean = {
    try {
      val roots = java.io.File.listRoots()
      if (roots.isEmpty) {
        logger.warn("No disk roots found")
        false
      } else {
        val root = roots.head // 检查根分区
        val freeSpace = root.getFreeSpace
        val totalSpace = root.getTotalSpace
        val usagePercentage = if (totalSpace > 0) {
          ((totalSpace - freeSpace).toDouble / totalSpace) * 100
        } else 0.0
        
        usagePercentage < Thresholds.DISK_CRITICAL_THRESHOLD
      }
    } catch {
      case ex: Exception =>
        logger.error("Disk check failed", ex)
        false
    }
  }

  /**
   * 检查Actor系统状态
   */
  private def checkActors(): Boolean = {
    try {
      // 简化实现：检查当前Actor系统是否正常运行
      // 在实际应用中，可以检查更详细的Actor指标
      true
    } catch {
      case ex: Exception =>
        logger.error("Actor system check failed", ex)
        false
    }
  }

  /**
   * 检查网络连接
   */
  private def checkNetwork(): Boolean = {
    try {
      val address = java.net.InetAddress.getByName("127.0.0.1")
      address.isReachable(Thresholds.NETWORK_TIMEOUT_MS)
    } catch {
      case ex: Exception =>
        logger.error("Network check failed", ex)
        false
    }
  }

  /**
   * 收集详细的系统指标
   */
  private def collectSystemMetrics(): SystemMetrics = {
    val memoryMetrics = collectMemoryMetrics()
    val cpuUsage = collectCpuUsage()
    val actorMetrics = collectActorMetrics()
    val diskMetrics = collectDiskMetrics()
    val networkMetrics = collectNetworkMetrics()

    SystemMetrics(memoryMetrics, cpuUsage, actorMetrics, diskMetrics, networkMetrics)
  }

  private def collectMemoryMetrics(): MemoryMetrics = {
    val runtime = Runtime.getRuntime
    val used = runtime.totalMemory() - runtime.freeMemory()
    val max = runtime.maxMemory()
    val usagePercentage = if (max > 0) (used.toDouble / max) * 100 else 0.0
    
    MemoryMetrics(used, max, usagePercentage)
  }

  private def collectCpuUsage(): Double = {
    try {
      val osBean = ManagementFactory.getOperatingSystemMXBean.asInstanceOf[com.sun.management.OperatingSystemMXBean]
      osBean.getProcessCpuLoad * 100
    } catch {
      case _: Exception => -1.0 // 无法获取CPU使用率
    }
  }

  private def collectActorMetrics(): ActorMetrics = {
    // 简化实现，在实际应用中可以从Actor系统获取更详细的指标
    ActorMetrics(
      totalActors = 0, // 需要从Actor系统获取
      activeActors = 0, // 需要从Actor系统获取
      deadLetters = 0 // 需要从Actor系统获取
    )
  }

  private def collectDiskMetrics(): DiskMetrics = {
    try {
      val roots = java.io.File.listRoots()
      if (roots.nonEmpty) {
        val root = roots.head
        val freeSpace = root.getFreeSpace
        val totalSpace = root.getTotalSpace
        val usagePercentage = if (totalSpace > 0) {
          ((totalSpace - freeSpace).toDouble / totalSpace) * 100
        } else 0.0
        
        DiskMetrics(freeSpace, totalSpace, usagePercentage)
      } else {
        DiskMetrics(0, 0, 0.0)
      }
    } catch {
      case _: Exception => DiskMetrics(0, 0, 0.0)
    }
  }

  private def collectNetworkMetrics(): NetworkMetrics = {
    try {
      val startTime = System.currentTimeMillis()
      val address = java.net.InetAddress.getByName("127.0.0.1")
      val isReachable = address.isReachable(Thresholds.NETWORK_TIMEOUT_MS)
      val responseTime = System.currentTimeMillis() - startTime
      
      NetworkMetrics(isReachable, responseTime, connectionsActive = 1)
    } catch {
      case _: Exception => NetworkMetrics(false, Thresholds.NETWORK_TIMEOUT_MS.toLong, 0)
    }
  }
}
