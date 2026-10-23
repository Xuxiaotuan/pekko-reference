package cn.xuyinyin.magic.infrastructure.cluster.sharding

import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import cn.xuyinyin.magic.workflow.execution.WorkflowExecutionEngine
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import org.apache.pekko.cluster.sharding.typed.{ClusterShardingSettings, ShardingEnvelope, ShardingMessageExtractor}

import scala.concurrent.{ExecutionContext,Future}
import scala.concurrent.duration._

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
    
    require(numberOfShards > 0, "workflow shard count must be positive")
    val sharding = ClusterSharding(system)
    
    // 创建消息提取器
    val messageExtractor = new WorkflowShardingExtractor(numberOfShards)
    
    // 初始化Entity
    val entity=Entity(TypeKey) { entityContext =>
        val workflowId = entityContext.entityId
        
        EventSourcedWorkflowActor(workflowId, executionEngine, ref => entityContext.shard ! ClusterSharding.Passivate(ref))
      }
        .withMessageExtractor(messageExtractor)
        .withSettings(ClusterShardingSettings(system).withRole("worker"))
        .withStopMessage(EventSourcedWorkflowActor.Stop)
    val managed=executionEngine.tableScheduling.filter(_.store.ownership.nonEmpty)
    managed.foreach { runtime=>
      val cluster=org.apache.pekko.cluster.typed.Cluster(system)
      scala.concurrent.Await.result(runtime.io(runtime.store.ownership.get.setAddress(runtime.nodeId,s"${cluster.selfMember.address}|${cluster.selfMember.uniqueAddress.longUid}")),10.seconds)
      system.systemActorOf(cn.xuyinyin.magic.workflow.scheduling.SnapshotClusterRetirement(runtime),"snapshot-owner-retirement")
    }
    sharding.init(if(managed.isEmpty)entity else entity.withAllocationStrategy(new NoLiveSnapshotRebalance))
  }
  
  private[magic] def configuredNumberOfShards(config: com.typesafe.config.Config): Int = {
    val count = config.getInt("pekko.cluster.sharding.number-of-shards")
    require(count > 0, "workflow shard count must be positive")
    count
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
    require(numberOfShards > 0, "workflow shard count must be positive")
    Math.floorMod(entityId.hashCode, numberOfShards).toString
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

/** Fault takeover only: no live transfer of persistence writers during routine balancing. */
private final class NoLiveSnapshotRebalance extends org.apache.pekko.cluster.sharding.ShardCoordinator.ShardAllocationStrategy {
  import org.apache.pekko.actor.{ActorRef => ClassicRef}
  def allocateShard(requester:ClassicRef,shardId:String,allocations:Map[ClassicRef,IndexedSeq[String]]):Future[ClassicRef]=
    Future.successful(if(allocations.isEmpty)requester else allocations.minBy { case (ref,shards)=>(shards.size,ref.path.toString) }._1)
  def rebalance(allocations:Map[ClassicRef,IndexedSeq[String]],inProgress:Set[String]):Future[Set[String]]=Future.successful(Set.empty)
}
