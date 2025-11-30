package cn.xuyinyin.magic.monitoring

import com.typesafe.scalalogging.Logger
import spray.json._
import spray.json.DefaultJsonProtocol._

import java.time.Instant

/**
 * 集群事件记录器
 * 
 * 使用结构化日志格式记录集群和分片事件：
 * - 工作流迁移事件
 * - 分片再平衡事件
 * - 故障转移事件
 * 
 * 所有事件都以JSON格式记录，便于日志分析和监控
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-28
 */
object ClusterEventLogger {
  
  private val logger = Logger("ClusterEvents")
  
  /**
   * 事件类型
   */
  sealed trait EventType {
    def name: String
  }
  
  case object WorkflowMigration extends EventType {
    val name = "workflow_migration"
  }
  
  case object ShardRebalance extends EventType {
    val name = "shard_rebalance"
  }
  
  case object WorkflowFailover extends EventType {
    val name = "workflow_failover"
  }
  
  case object ShardStarted extends EventType {
    val name = "shard_started"
  }
  
  case object ShardStopped extends EventType {
    val name = "shard_stopped"
  }
  
  case object EntityStarted extends EventType {
    val name = "entity_started"
  }
  
  case object EntityStopped extends EventType {
    val name = "entity_stopped"
  }
  
  /**
   * 记录工作流迁移事件
   * 
   * 当工作流Entity从一个节点迁移到另一个节点时调用
   * 
   * @param workflowId 工作流ID
   * @param fromNode 源节点地址
   * @param toNode 目标节点地址
   * @param reason 迁移原因
   */
  def logWorkflowMigration(
    workflowId: String,
    fromNode: String,
    toNode: String,
    reason: String
  ): Unit = {
    val event = JsObject(
      "timestamp" -> JsString(Instant.now().toString),
      "event_type" -> JsString(WorkflowMigration.name),
      "workflow_id" -> JsString(workflowId),
      "from_node" -> JsString(fromNode),
      "to_node" -> JsString(toNode),
      "reason" -> JsString(reason),
      "severity" -> JsString("INFO")
    )
    
    logger.info(s"CLUSTER_EVENT: ${event.compactPrint}")
    
    // 同时记录到Prometheus指标
    PrometheusMetrics.recordFailover(workflowId, fromNode, toNode)
  }
  
  /**
   * 记录分片再平衡事件
   * 
   * 当集群进行分片再平衡时调用
   * 
   * @param shardId 分片ID
   * @param fromNode 源节点地址
   * @param toNode 目标节点地址
   * @param entityCount 受影响的Entity数量
   */
  def logShardRebalance(
    shardId: String,
    fromNode: String,
    toNode: String,
    entityCount: Int
  ): Unit = {
    val event = JsObject(
      "timestamp" -> JsString(Instant.now().toString),
      "event_type" -> JsString(ShardRebalance.name),
      "shard_id" -> JsString(shardId),
      "from_node" -> JsString(fromNode),
      "to_node" -> JsString(toNode),
      "entity_count" -> JsNumber(entityCount),
      "severity" -> JsString("INFO")
    )
    
    logger.info(s"CLUSTER_EVENT: ${event.compactPrint}")
    
    // 同时记录到Prometheus指标
    PrometheusMetrics.recordRebalance(shardId, fromNode, toNode)
  }
  
  /**
   * 记录工作流故障转移事件
   * 
   * 当工作流因节点故障而转移到其他节点时调用
   * 
   * @param workflowId 工作流ID
   * @param failedNode 故障节点地址
   * @param newNode 新节点地址
   * @param downtime 停机时间（秒）
   */
  def logWorkflowFailover(
    workflowId: String,
    failedNode: String,
    newNode: String,
    downtime: Double
  ): Unit = {
    val event = JsObject(
      "timestamp" -> JsString(Instant.now().toString),
      "event_type" -> JsString(WorkflowFailover.name),
      "workflow_id" -> JsString(workflowId),
      "failed_node" -> JsString(failedNode),
      "new_node" -> JsString(newNode),
      "downtime_seconds" -> JsNumber(downtime),
      "severity" -> JsString("WARNING")
    )
    
    logger.warn(s"CLUSTER_EVENT: ${event.compactPrint}")
    
    // 同时记录到Prometheus指标
    PrometheusMetrics.recordFailover(workflowId, failedNode, newNode)
  }
  
  /**
   * 记录分片启动事件
   * 
   * @param shardId 分片ID
   * @param node 节点地址
   */
  def logShardStarted(shardId: String, node: String): Unit = {
    val event = JsObject(
      "timestamp" -> JsString(Instant.now().toString),
      "event_type" -> JsString(ShardStarted.name),
      "shard_id" -> JsString(shardId),
      "node" -> JsString(node),
      "severity" -> JsString("INFO")
    )
    
    logger.info(s"CLUSTER_EVENT: ${event.compactPrint}")
  }
  
  /**
   * 记录分片停止事件
   * 
   * @param shardId 分片ID
   * @param node 节点地址
   * @param reason 停止原因
   */
  def logShardStopped(shardId: String, node: String, reason: String): Unit = {
    val event = JsObject(
      "timestamp" -> JsString(Instant.now().toString),
      "event_type" -> JsString(ShardStopped.name),
      "shard_id" -> JsString(shardId),
      "node" -> JsString(node),
      "reason" -> JsString(reason),
      "severity" -> JsString("INFO")
    )
    
    logger.info(s"CLUSTER_EVENT: ${event.compactPrint}")
  }
  
  /**
   * 记录Entity启动事件
   * 
   * @param entityId Entity ID（工作流ID）
   * @param shardId 分片ID
   * @param node 节点地址
   */
  def logEntityStarted(entityId: String, shardId: String, node: String): Unit = {
    val event = JsObject(
      "timestamp" -> JsString(Instant.now().toString),
      "event_type" -> JsString(EntityStarted.name),
      "entity_id" -> JsString(entityId),
      "shard_id" -> JsString(shardId),
      "node" -> JsString(node),
      "severity" -> JsString("DEBUG")
    )
    
    logger.debug(s"CLUSTER_EVENT: ${event.compactPrint}")
  }
  
  /**
   * 记录Entity停止事件
   * 
   * @param entityId Entity ID（工作流ID）
   * @param shardId 分片ID
   * @param node 节点地址
   * @param reason 停止原因
   */
  def logEntityStopped(
    entityId: String,
    shardId: String,
    node: String,
    reason: String
  ): Unit = {
    val event = JsObject(
      "timestamp" -> JsString(Instant.now().toString),
      "event_type" -> JsString(EntityStopped.name),
      "entity_id" -> JsString(entityId),
      "shard_id" -> JsString(shardId),
      "node" -> JsString(node),
      "reason" -> JsString(reason),
      "severity" -> JsString("DEBUG")
    )
    
    logger.debug(s"CLUSTER_EVENT: ${event.compactPrint}")
  }
  
  /**
   * 记录集群成员变化事件
   * 
   * @param memberAddress 成员地址
   * @param status 状态（joining, up, leaving, exiting, removed）
   * @param roles 角色列表
   */
  def logMemberEvent(
    memberAddress: String,
    status: String,
    roles: Set[String]
  ): Unit = {
    val event = JsObject(
      "timestamp" -> JsString(Instant.now().toString),
      "event_type" -> JsString("member_event"),
      "member_address" -> JsString(memberAddress),
      "status" -> JsString(status),
      "roles" -> JsArray(roles.map(JsString(_)).toVector),
      "severity" -> JsString("INFO")
    )
    
    logger.info(s"CLUSTER_EVENT: ${event.compactPrint}")
  }
  
  /**
   * 记录Leader变更事件
   * 
   * @param oldLeader 旧Leader地址（可选）
   * @param newLeader 新Leader地址
   */
  def logLeaderChanged(oldLeader: Option[String], newLeader: String): Unit = {
    val event = JsObject(
      "timestamp" -> JsString(Instant.now().toString),
      "event_type" -> JsString("leader_changed"),
      "old_leader" -> oldLeader.map(JsString(_)).getOrElse(JsNull),
      "new_leader" -> JsString(newLeader),
      "severity" -> JsString("INFO")
    )
    
    logger.info(s"CLUSTER_EVENT: ${event.compactPrint}")
  }
  
  /**
   * 记录不可达节点事件
   * 
   * @param unreachableNode 不可达节点地址
   * @param detectedBy 检测到的节点地址
   */
  def logUnreachableMember(unreachableNode: String, detectedBy: String): Unit = {
    val event = JsObject(
      "timestamp" -> JsString(Instant.now().toString),
      "event_type" -> JsString("unreachable_member"),
      "unreachable_node" -> JsString(unreachableNode),
      "detected_by" -> JsString(detectedBy),
      "severity" -> JsString("ERROR")
    )
    
    logger.error(s"CLUSTER_EVENT: ${event.compactPrint}")
  }
  
  /**
   * 记录可达节点恢复事件
   * 
   * @param reachableNode 恢复可达的节点地址
   */
  def logReachableMember(reachableNode: String): Unit = {
    val event = JsObject(
      "timestamp" -> JsString(Instant.now().toString),
      "event_type" -> JsString("reachable_member"),
      "reachable_node" -> JsString(reachableNode),
      "severity" -> JsString("INFO")
    )
    
    logger.info(s"CLUSTER_EVENT: ${event.compactPrint}")
  }
}
