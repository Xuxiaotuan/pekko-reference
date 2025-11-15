package cn.xuyinyin.magic.core.cluster

import scala.collection.mutable

/**
 * 节点角色定义
 * 
 * 定义了Pekko DataFusion Arrow分布式系统中不同类型节点的角色和职责。
 * 每个节点根据其角色承担不同的任务和功能。
 * 
 * @author : Xuxiaotuan
 * @since : 2024-09-21 22:18
 */
object NodeRole {

  // === DataFusion Arrow 系统角色 ===
  
  /**
   * 任务协调节点
   * - 负责任务调度和分发
   * - 维护全局任务状态
   * - 协调各个Worker节点的工作
   */
  val COORDINATOR = "coordinator"
  
  /**
   * 数据处理节点
   * - 执行具体的数据处理任务
   * - 运行DataFusion查询引擎
   * - 处理Arrow格式的数据
   */
  val WORKER = "worker"
  
  /**
   * 存储节点
   * - 提供数据存储服务
   * - 管理数据分片和副本
   * - 支持数据的持久化和恢复
   */
  val STORAGE = "storage"
  
  /**
   * API网关节点
   * - 提供外部API接口
   * - 处理客户端请求
   * - 路由请求到相应的内部节点
   */
  val API_GATEWAY = "api-gateway"

  // === 兼容旧系统的角色 ===
  
  // Aggregation roles (保持向后兼容)
  private lazy val ServerAll = "server-all"
  private lazy val Dispatcher = "dispatcher"
  private lazy val WorkerLegacy = "worker" // 与新的WORKER冲突，需要处理
  private lazy val HTTP = "http"

  // Aggregation roles -> Details roles
  private lazy val ServerAllRoles = DispatcherRoles ++ WorkerRoles ++ HttpRoles
  private lazy val DispatcherRoles = Seq(DataPumpDispatcher)
  private lazy val WorkerRoles = Seq(DataPumpWorker)
  private lazy val HttpRoles = Seq(HttpCommand, HttpQuery)

  // Details roles
  private val DataPumpDispatcher = "dispatcher-pump"
  private val DataPumpWorker = "worker-pump"
  private val HttpCommand = "http-cmd"
  private val HttpQuery = "http-query"

  /**
   * 所有有效的角色列表
   */
  val ALL_ROLES = Set(COORDINATOR, WORKER, STORAGE, API_GATEWAY, 
                     ServerAll, Dispatcher, HTTP, 
                     DataPumpDispatcher, DataPumpWorker, HttpCommand, HttpQuery)
  
  /**
   * 检查角色是否有效
   */
  def isValidRole(role: String): Boolean = ALL_ROLES.contains(role)
  
  /**
   * 角色描述信息
   */
  def roleDescription(role: String): String = role match {
    case COORDINATOR => "Task coordination and distribution node"
    case WORKER => "Data processing and computation node"  
    case STORAGE => "Data storage and management node"
    case API_GATEWAY => "External API gateway and routing node"
    case ServerAll => "Legacy server-all role"
    case Dispatcher => "Legacy dispatcher role"
    case HTTP => "Legacy HTTP role"
    case DataPumpDispatcher => "Legacy data pump dispatcher"
    case DataPumpWorker => "Legacy data pump worker"
    case HttpCommand => "Legacy HTTP command"
    case HttpQuery => "Legacy HTTP query"
    case _ => "Unknown role"
  }
  
  /**
   * 获取角色的主要职责
   */
  def getResponsibilities(role: String): List[String] = role match {
    case COORDINATOR => List(
      "Task scheduling and distribution",
      "Global task state management", 
      "Worker node coordination",
      "Load balancing"
    )
    case WORKER => List(
      "Execute data processing tasks",
      "Run DataFusion query engine",
      "Process Arrow format data",
      "Report task execution status"
    )
    case STORAGE => List(
      "Provide data storage services",
      "Manage data shards and replicas",
      "Data persistence and recovery",
      "Data consistency maintenance"
    )
    case API_GATEWAY => List(
      "Handle external API requests",
      "Request routing and load balancing",
      "Authentication and authorization",
      "Protocol translation"
    )
    case _ => List("Legacy role - no specific responsibilities defined")
  }
  
  /**
   * 获取默认角色（如果没有指定角色）
   */
  def getDefaultRole: String = WORKER
  
  /**
   * 验证角色组合的有效性
   * 某些角色组合可能有特殊要求
   */
  def validateRoleCombination(roles: Set[String]): Boolean = {
    // 基本验证：所有角色都必须有效
    if (!roles.forall(isValidRole)) return false
    
    // 业务规则验证
    val hasCoordinator = roles.contains(COORDINATOR)
    val hasWorker = roles.contains(WORKER)
    val hasStorage = roles.contains(STORAGE)
    val hasApiGateway = roles.contains(API_GATEWAY)
    
    // 至少需要一个worker或coordinator
    if (!hasCoordinator && !hasWorker) return false
    
    // API网关通常需要coordinator存在
    if (hasApiGateway && !hasCoordinator) {
      // 可以考虑是否允许这个组合
      return true // 暂时允许
    }
    
    true
  }

  /**
   * Flatten pekko reference node roles.
   * 保持向后兼容的同时支持新的角色系统
   */
  def flattenRoles(roles: Seq[String]): Seq[String] = {
    val rs = mutable.Set[String]()
    
    // 处理新的DataFusion Arrow角色（转换为小写）
    roles.map(_.toLowerCase.trim).filter(isValidRole).foreach(rs += _)
    
    // 处理旧的聚合角色（保持向后兼容）
    if (roles.map(_.toLowerCase.trim).contains(ServerAll)) rs ++= ServerAllRoles
    if (roles.map(_.toLowerCase.trim).contains(Dispatcher)) rs ++= DispatcherRoles
    if (roles.map(_.toLowerCase.trim).contains(WorkerLegacy)) rs ++= WorkerRoles
    if (roles.map(_.toLowerCase.trim).contains(HTTP)) rs ++= HttpRoles
    
    rs.toSeq
  }
}