package cn.xuyinyin.magic.workflow.sharding

import cn.xuyinyin.magic.workflow.actors.{EventSourcedWorkflowActor, WorkflowActor}
import cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngine
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import org.apache.pekko.cluster.sharding.typed.{ClusterShardingSettings, ShardingEnvelope, ShardingMessageExtractor}

import scala.concurrent.ExecutionContext

/**
 * WorkflowSharding - 工作流集群分片管理
 * 
 * 负责：
 * - 管理WorkflowActor的Cluster Sharding
 * - 提供Entity初始化逻辑
 * - 配置分片策略
 * - 透明路由到正确的节点
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-27
 */
object WorkflowSharding {
  
  /**
   * Entity类型键
   */
  val TypeKey: EntityTypeKey[EventSourcedWorkflowActor.Command] = EntityTypeKey[EventSourcedWorkflowActor.Command]("Workflow")
  
  /**
   * 初始化Cluster Sharding
   * 
   * @param system ActorSystem
   * @param executionEngine 工作流执行引擎
   * @param numberOfShards 分片数量（默认100）
   * @return ShardRegion引用
   */
  def init(
    system: ActorSystem[_],
    executionEngine: WorkflowExecutionEngine,
    numberOfShards: Int = 100
  )(implicit ec: ExecutionContext): ActorRef[ShardingEnvelope[EventSourcedWorkflowActor.Command]] = {
    
    val sharding = ClusterSharding(system)
    
    // 创建消息提取器
    val messageExtractor = new WorkflowShardingExtractor(numberOfShards)
    
    // 初始化Entity
    sharding.init(
      Entity(TypeKey) { entityContext =>
        val workflowId = entityContext.entityId
        
        // 创建一个临时的workflow对象用于初始化
        // 实际的workflow会通过Initialize命令传入
        val emptyWorkflow = WorkflowDSL.Workflow(
          id = workflowId,
          name = s"workflow-$workflowId",
          description = "",
          version = "1.0",
          author = "system",
          tags = List.empty,
          nodes = List.empty,
          edges = List.empty,
          metadata = WorkflowDSL.WorkflowMetadata(
            createdAt = java.time.Instant.now().toString,
            updatedAt = java.time.Instant.now().toString
          )
        )
        
        // 使用Event Sourcing的WorkflowActor
        EventSourcedWorkflowActor(workflowId, emptyWorkflow, executionEngine)
      }
        .withMessageExtractor(messageExtractor)
        .withSettings(ClusterShardingSettings(system).withRole("worker"))
        .withStopMessage(EventSourcedWorkflowActor.Stop)
    )
  }
  
  /**
   * 提取EntityId（workflowId）
   */
  def extractEntityId[T <: EventSourcedWorkflowActor.Command](envelope: ShardingEnvelope[T]): String = {
    envelope.entityId
  }
  
  /**
   * 提取ShardId（基于workflowId的哈希）
   */
  def extractShardId(entityId: String, numberOfShards: Int = 100): String = {
    (math.abs(entityId.hashCode) % numberOfShards).toString
  }
}

/**
 * 工作流分片消息提取器
 * 
 * 实现确定性的哈希分片策略
 */
class WorkflowShardingExtractor(numberOfShards: Int) 
  extends ShardingMessageExtractor[ShardingEnvelope[EventSourcedWorkflowActor.Command], EventSourcedWorkflowActor.Command] {
  
  override def entityId(envelope: ShardingEnvelope[EventSourcedWorkflowActor.Command]): String = {
    envelope.entityId
  }
  
  override def shardId(entityId: String): String = {
    WorkflowSharding.extractShardId(entityId, numberOfShards)
  }
  
  override def unwrapMessage(envelope: ShardingEnvelope[EventSourcedWorkflowActor.Command]): EventSourcedWorkflowActor.Command = {
    envelope.message
  }
  
  // 泛型辅助方法用于测试
  def entityIdGeneric[T <: EventSourcedWorkflowActor.Command](envelope: ShardingEnvelope[T]): String = {
    envelope.entityId
  }
  
  def unwrapMessageGeneric[T <: EventSourcedWorkflowActor.Command](envelope: ShardingEnvelope[T]): T = {
    envelope.message
  }
}
