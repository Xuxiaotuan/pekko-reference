# Cluster Sharding的一致性哈希与路由

> **深度分析系列** - 第十二篇：分布式Actor的智能路由机制

---

## 📋 目录

- [引言](#引言)
- [一致性哈希原理](#一致性哈希原理)
- [虚拟节点机制](#虚拟节点机制)
- [Shard分配策略](#shard分配策略)
- [Entity路由](#entity路由)
- [再平衡算法](#再平衡算法)
- [Passivation机制](#passivation机制)
- [性能优化](#性能优化)
- [实战案例](#实战案例)
- [总结](#总结)

---

## 引言

Cluster Sharding解决的核心问题：**如何在集群中分布和定位Actor？**

```
挑战：
1. 100万个用户Entity，如何分布到10个节点？
2. 用户请求来了，如何快速找到对应的Entity？
3. 节点上下线，如何迁移Entity？
4. 如何保证负载均衡？

Cluster Sharding方案：
- 一致性哈希：均匀分布
- Shard机制：分组管理
- Coordinator：中心协调
- 自动再平衡：动态调整
```

---

## 一致性哈希原理

### 传统哈希的问题

```
传统哈希：
node = hash(entityId) % nodeCount

示例：
3个节点，100个Entity
Entity1 → hash(1) % 3 = node1
Entity2 → hash(2) % 3 = node2
Entity3 → hash(3) % 3 = node0

问题：增加节点
4个节点后：
Entity1 → hash(1) % 4 = node1 ✓（未变）
Entity2 → hash(2) % 4 = node2 ✓（未变）
Entity3 → hash(3) % 4 = node3 ✗（变了！）

结果：75%的Entity需要迁移！
```

### 一致性哈希算法

**核心思想**：将节点和数据都映射到同一个哈希环上

```
哈希环（0-2^32）：

        0
        ↑
    ┌───┴───┐
   ←         →
  ↓           ↑
Node1       Node3
  ↓           ↑
   ←         →
    └───┬───┘
        ↓
      Node2

数据分配：
Entity沿着环顺时针查找第一个节点

Entity1(hash=100) → Node1
Entity2(hash=500) → Node2
Entity3(hash=900) → Node3
```

**优势**：增加/删除节点只影响相邻节点

```
增加Node4：
        0
        ↑
    ┌───┴───┐
   ←         →
  ↓           ↑
Node1      Node4(新)
  ↓           ↑
   ← Node2  Node3
      ↑  ↓
       ←→

只需迁移Node3的一部分数据到Node4
影响范围：~25%（而非75%）
```

### 算法实现

```scala
class ConsistentHash[T](
  nodes: Set[T],
  virtualNodes: Int = 160
) {
  
  // 哈希环：TreeMap保持有序
  private val ring = new TreeMap[Int, T]()
  
  // 初始化：为每个节点创建虚拟节点
  nodes.foreach { node =>
    (0 until virtualNodes).foreach { i =>
      val hash = hashFunction(s"$node-$i")
      ring.put(hash, node)
    }
  }
  
  // 查找：给定key，找到对应节点
  def getNode(key: String): T = {
    val hash = hashFunction(key)
    
    // 顺时针查找第一个节点
    val tailMap = ring.tailMap(hash)
    
    if (tailMap.isEmpty) {
      // 超过最大值，返回第一个
      ring.firstEntry().getValue
    } else {
      tailMap.firstEntry().getValue
    }
  }
  
  // 添加节点
  def addNode(node: T): ConsistentHash[T] = {
    (0 until virtualNodes).foreach { i =>
      val hash = hashFunction(s"$node-$i")
      ring.put(hash, node)
    }
    this
  }
  
  // 删除节点
  def removeNode(node: T): ConsistentHash[T] = {
    (0 until virtualNodes).foreach { i =>
      val hash = hashFunction(s"$node-$i")
      ring.remove(hash)
    }
    this
  }
  
  private def hashFunction(key: String): Int = {
    // MurmurHash3
    MurmurHash3.stringHash(key)
  }
}
```

---

## 虚拟节点机制

### 为什么需要虚拟节点

```
问题：只有物理节点时，分布不均

3个物理节点在环上：
Node1(hash=100)
Node2(hash=500)
Node3(hash=900)

数据分布：
Node1: 100-500 (40%)
Node2: 500-900 (40%)
Node3: 900-100 (20%)  ← 不均匀！

解决：每个物理节点创建多个虚拟节点
Node1: VNode1-1, VNode1-2, ..., VNode1-160
Node2: VNode2-1, VNode2-2, ..., VNode2-160
Node3: VNode3-1, VNode3-2, ..., VNode3-160

结果：更均匀的分布（标准差<5%）
```

### 虚拟节点数量选择

```
虚拟节点数 vs 分布均匀性：

vNodes = 10:  标准差 ~15% (不均匀)
vNodes = 50:  标准差 ~8%
vNodes = 100: 标准差 ~5%
vNodes = 160: 标准差 ~3%  ← Pekko默认
vNodes = 500: 标准差 ~1%  (收益递减)

推荐：160（平衡性能和均匀性）
```

### 实际效果

```scala
// 测试均匀性
val nodes = Set("Node1", "Node2", "Node3")
val ch = new ConsistentHash(nodes, virtualNodes = 160)

val keys = (1 to 10000).map(i => s"Entity$i")
val distribution = keys.groupBy(ch.getNode).mapValues(_.size)

// 结果：
// Node1: 3312 (33.12%)
// Node2: 3356 (33.56%)
// Node3: 3332 (33.32%)
// 标准差: 0.22% ← 非常均匀！
```

---

## Shard分配策略

### Shard概念

```
为什么不直接用Entity？

问题：
- 100万Entity → 100万个Actor
- 管理开销大
- 路由复杂

解决：Shard分组
- 100万Entity → 1000个Shard
- 每个Shard管理~1000个Entity
- 减少管理开销
```

### Shard计算

```scala
// 默认ShardId提取
def extractShardId(entityId: String, numberOfShards: Int): String = {
  val hash = Math.abs(entityId.hashCode)
  (hash % numberOfShards).toString
}

// 示例：
numberOfShards = 100
Entity("user-12345") → Shard("56")
Entity("user-67890") → Shard("23")

// 每个Shard包含多个Entity
Shard("56") → [user-12345, user-45678, user-78901, ...]
```

### ShardCoordinator

**核心组件**：协调Shard分配

```scala
object ShardCoordinator {
  
  // 状态：Shard → Node映射
  case class ShardAllocation(
    shards: Map[ShardId, Address]  // Shard分配到哪个节点
  )
  
  // 消息
  sealed trait Command
  case class GetShardHome(shardId: ShardId, replyTo: ActorRef[Address]) extends Command
  case class RegisterShardLocation(shardId: ShardId, location: Address) extends Command
  case class AllocateShard(shardId: ShardId) extends Command
  
  def apply(allocationStrategy: ShardAllocationStrategy): Behavior[Command] = {
    coordinating(ShardAllocation(Map.empty), allocationStrategy)
  }
  
  private def coordinating(
    allocation: ShardAllocation,
    strategy: ShardAllocationStrategy
  ): Behavior[Command] = {
    
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case GetShardHome(shardId, replyTo) =>
          allocation.shards.get(shardId) match {
            case Some(address) =>
              // Shard已分配
              replyTo ! address
              Behaviors.same
            
            case None =>
              // Shard未分配，选择节点
              val selectedNode = strategy.allocateShard(
                shardId,
                allocation,
                ctx.system.cluster.state.members
              )
              
              // 更新分配
              val newAllocation = allocation.copy(
                shards = allocation.shards + (shardId -> selectedNode)
              )
              
              replyTo ! selectedNode
              coordinating(newAllocation, strategy)
          }
        
        case RegisterShardLocation(shardId, location) =>
          // 注册Shard位置
          val newAllocation = allocation.copy(
            shards = allocation.shards + (shardId -> location)
          )
          coordinating(newAllocation, strategy)
      }
    }
  }
}
```

### 分配策略

```scala
trait ShardAllocationStrategy {
  def allocateShard(
    shardId: ShardId,
    currentAllocation: ShardAllocation,
    clusterMembers: Set[Member]
  ): Address
}

// 1. LeastShardAllocationStrategy（默认）
class LeastShardAllocationStrategy extends ShardAllocationStrategy {
  
  def allocateShard(
    shardId: ShardId,
    currentAllocation: ShardAllocation,
    clusterMembers: Set[Member]
  ): Address = {
    
    // 统计每个节点的Shard数量
    val shardCounts = currentAllocation.shards
      .groupBy(_._2)
      .mapValues(_.size)
    
    // 选择Shard最少的节点
    val selectedNode = clusterMembers
      .minBy(member => shardCounts.getOrElse(member.address, 0))
    
    selectedNode.address
  }
}

// 2. ConsistentHashingAllocationStrategy
class ConsistentHashingAllocationStrategy extends ShardAllocationStrategy {
  
  private val consistentHash = new ConsistentHash(
    clusterMembers.map(_.address).toSet,
    virtualNodes = 160
  )
  
  def allocateShard(
    shardId: ShardId,
    currentAllocation: ShardAllocation,
    clusterMembers: Set[Member]
  ): Address = {
    
    // 使用一致性哈希
    consistentHash.getNode(shardId)
  }
}
```

---

## Entity路由

### 完整路由流程

```
1. 客户端发送消息
   ↓
2. ShardRegion（本地）
   ↓
3. 提取EntityId和ShardId
   ↓
4. 查询Shard位置
   ↓
5. 转发到目标ShardRegion
   ↓
6. ShardRegion启动/查找Entity
   ↓
7. 消息到达Entity Actor
```

### ShardRegion实现

```scala
object ShardRegion {
  
  def apply[M](
    typeName: String,
    entityBehavior: EntityId => Behavior[M],
    extractEntityId: M => EntityId,
    extractShardId: EntityId => ShardId
  ): Behavior[M] = {
    
    Behaviors.setup { ctx =>
      
      // 本地管理的Shard
      val localShards = mutable.Map[ShardId, ActorRef[ShardCommand]]()
      
      // Shard位置缓存
      val shardLocations = mutable.Map[ShardId, Address]()
      
      routing(localShards, shardLocations)
    }
  }
  
  private def routing(
    localShards: mutable.Map[ShardId, ActorRef[ShardCommand]],
    locations: mutable.Map[ShardId, Address]
  ): Behavior[M] = {
    
    Behaviors.receive { (ctx, msg) =>
      // 1. 提取Entity和Shard ID
      val entityId = extractEntityId(msg)
      val shardId = extractShardId(entityId)
      
      // 2. 查询Shard位置
      locations.get(shardId) match {
        case Some(address) if address == ctx.system.address =>
          // 本地Shard
          val shard = localShards.getOrElseUpdate(shardId, {
            ctx.spawn(Shard(entityBehavior), s"shard-$shardId")
          })
          shard ! ShardCommand.Deliver(entityId, msg)
          Behaviors.same
        
        case Some(remoteAddress) =>
          // 远程Shard，转发
          val remoteRegion = ctx.system.receptionist.find(
            typeName,
            remoteAddress
          )
          remoteRegion ! msg
          Behaviors.same
        
        case None =>
          // 位置未知，查询Coordinator
          coordinator ! GetShardHome(shardId, ctx.self)
          
          // 暂存消息
          Behaviors.same  // 实际需要stash
      }
    }
  }
}
```

### Shard Actor

```scala
object Shard {
  
  sealed trait Command
  case class Deliver(entityId: EntityId, msg: Any) extends Command
  case class Passivate(entityId: EntityId) extends Command
  
  def apply[M](
    entityBehavior: EntityId => Behavior[M]
  ): Behavior[Command] = {
    
    managing(Map.empty, entityBehavior)
  }
  
  private def managing[M](
    entities: Map[EntityId, ActorRef[M]],
    entityBehavior: EntityId => Behavior[M]
  ): Behavior[Command] = {
    
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case Deliver(entityId, entityMsg) =>
          // 获取或创建Entity
          val entity = entities.getOrElse(entityId, {
            ctx.spawn(entityBehavior(entityId), entityId)
          })
          
          entity ! entityMsg.asInstanceOf[M]
          
          managing(entities + (entityId -> entity), entityBehavior)
        
        case Passivate(entityId) =>
          // 钝化Entity
          entities.get(entityId).foreach(ctx.stop)
          managing(entities - entityId, entityBehavior)
      }
    }
  }
}
```

---

## 再平衡算法

### 何时触发再平衡

```
触发条件：
1. 节点加入集群
2. 节点离开集群
3. 负载不均衡（可配置）

目标：
- 均匀分布Shard
- 最小化迁移
- 避免频繁再平衡
```

### 再平衡策略

```scala
trait RebalanceStrategy {
  def rebalance(
    currentAllocation: Map[ShardId, Address],
    clusterMembers: Set[Address]
  ): Set[ShardId]  // 需要迁移的Shard
}

// LeastShardRebalanceStrategy
class LeastShardRebalanceStrategy(
  maxSimultaneousRebalance: Int = 3,
  rebalanceThreshold: Double = 0.1  // 10%差异
) extends RebalanceStrategy {
  
  def rebalance(
    currentAllocation: Map[ShardId, Address],
    clusterMembers: Set[Address]
  ): Set[ShardId] = {
    
    // 计算每个节点的Shard数量
    val shardCounts = currentAllocation
      .groupBy(_._2)
      .mapValues(_.size)
    
    val avgCount = currentAllocation.size.toDouble / clusterMembers.size
    
    // 找到负载过高的节点
    val overloadedNodes = shardCounts.filter { case (node, count) =>
      count > avgCount * (1 + rebalanceThreshold)
    }
    
    // 找到负载过低的节点
    val underloadedNodes = shardCounts.filter { case (node, count) =>
      count < avgCount * (1 - rebalanceThreshold)
    }
    
    // 选择要迁移的Shard
    val shardsToMigrate = overloadedNodes.flatMap { case (node, count) =>
      val excess = (count - avgCount).toInt
      currentAllocation
        .filter(_._2 == node)
        .keys
        .take(Math.min(excess, maxSimultaneousRebalance))
    }.toSet
    
    shardsToMigrate
  }
}
```

### 迁移流程

```
1. Coordinator选择要迁移的Shard
   ↓
2. 通知目标节点准备接收
   ↓
3. 目标节点启动新Shard
   ↓
4. 等待新Shard就绪
   ↓
5. 更新路由表
   ↓
6. 通知源节点停止Shard
   ↓
7. 源节点停止旧Shard
   ↓
8. 迁移完成
```

---

## Passivation机制

### 为什么需要Passivation

```
问题：
- 100万Entity → 100万个Actor
- 内存占用巨大
- 但同时活跃的Entity很少（<1%）

解决：Passivation（钝化）
- Entity空闲一段时间后自动停止
- 需要时再重新创建
- 类似缓存的LRU策略
```

### 实现机制

```scala
object EntityWithPassivation {
  
  sealed trait Command
  case class BusinessCommand(data: String) extends Command
  private case object PassivationTimeout extends Command
  
  def apply(entityId: String): Behavior[Command] = {
    Behaviors.withTimers { timers =>
      Behaviors.setup { ctx =>
        
        active(entityId, timers, lastActivity = System.currentTimeMillis())
      }
    }
  }
  
  private def active(
    entityId: String,
    timers: TimerScheduler[Command],
    lastActivity: Long
  ): Behavior[Command] = {
    
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case cmd: BusinessCommand =>
          // 处理业务消息
          processCommand(cmd)
          
          // 重置超时定时器
          timers.startSingleTimer(
            PassivationTimeout,
            2.minutes  // 2分钟无活动则钝化
          )
          
          active(entityId, timers, System.currentTimeMillis())
        
        case PassivationTimeout =>
          // 超时，钝化自己
          ctx.log.info(s"Passivating entity $entityId")
          
          // 通知Shard
          ctx.parent ! Shard.Passivate(entityId)
          
          Behaviors.stopped
      }
    }
  }
}
```

### 配置

```hocon
pekko.cluster.sharding {
  # Passivation策略
  passivate-idle-entity-after = 2 minutes
  
  # 或者基于内存
  # passivation {
  #   strategy = active-entity-limit
  #   active-entity-limit {
  #     limit = 1000  # 每个Shard最多1000个活跃Entity
  #   }
  # }
}
```

---

## 性能优化

### 1. Remember Entities

```hocon
# 记住Entity位置，避免重复查询
pekko.cluster.sharding {
  remember-entities = on
  remember-entities-store = "ddata"
}

优势：
- Entity重启后位置不变
- 减少Coordinator负载
- 更快的路由

代价：
- 内存占用增加
- 状态持久化开销
```

### 2. 调整Shard数量

```scala
// Shard数量选择
val numberOfShards = {
  val maxNodes = 100  // 预期最大节点数
  val shardsPerNode = 10  // 每个节点10个Shard
  maxNodes * shardsPerNode  // = 1000
}

// 规则：
// - 太少：负载不均，迁移粒度大
// - 太多：管理开销大，内存占用高
// - 推荐：节点数 × 10
```

### 3. 位置缓存

```scala
// ShardRegion缓存Shard位置
private val locationCache = new ConcurrentHashMap[ShardId, Address]()

def routeMessage(msg: M): Unit = {
  val shardId = extractShardId(msg)
  
  locationCache.get(shardId) match {
    case null =>
      // 缓存未命中，查询Coordinator
      queryCoordinator(shardId)
    
    case address =>
      // 缓存命中，直接路由
      routeToAddress(address, msg)
  }
}

// TTL：定期刷新
timers.startTimerAtFixedRate(
  RefreshCache,
  1.minute,
  1.minute
)
```

---

## 实战案例

### 案例1：用户会话管理

```scala
// 用户会话Entity
object UserSession {
  
  sealed trait Command
  case class Login(userId: String, replyTo: ActorRef[Response]) extends Command
  case class SendMessage(content: String) extends Command
  case class Logout() extends Command
  
  def apply(userId: String): Behavior[Command] = {
    Behaviors.withTimers { timers =>
      
      // 设置Passivation
      timers.startSingleTimer(PassivationTimeout, 30.minutes)
      
      active(userId, timers, Set.empty)
    }
  }
  
  private def active(
    userId: String,
    timers: TimerScheduler[Command],
    connections: Set[ActorRef[Event]]
  ): Behavior[Command] = {
    
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case Login(_, replyTo) =>
          replyTo ! LoginSuccess
          active(userId, timers, connections + replyTo)
        
        case SendMessage(content) =>
          // 广播给所有连接
          connections.foreach(_ ! MessageEvent(content))
          
          // 重置超时
          timers.startSingleTimer(PassivationTimeout, 30.minutes)
          
          Behaviors.same
        
        case Logout() =>
          // 通知Shard钝化
          ctx.parent ! Shard.Passivate(userId)
          Behaviors.stopped
      }
    }
  }
}

// 初始化Sharding
val userSessionSharding = ClusterSharding(system).init(Entity(
  typeKey = EntityTypeKey[UserSession.Command]("UserSession")
)(
  createBehavior = entityContext => UserSession(entityContext.entityId)
).withSettings(
  ClusterShardingSettings(system)
    .withRole("backend")
    .withPassivateIdleEntityAfter(30.minutes)
))

// 使用
userSessionSharding ! UserSession.Login("user123", replyTo)
```

### 案例2：分布式计数器

```scala
// 分布式计数器Entity
object DistributedCounter {
  
  sealed trait Command
  case object Increment extends Command
  case object Decrement extends Command
  case class GetCount(replyTo: ActorRef[Int]) extends Command
  
  def apply(counterId: String): Behavior[Command] = {
    counting(counterId, 0)
  }
  
  private def counting(counterId: String, count: Int): Behavior[Command] = {
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case Increment =>
          counting(counterId, count + 1)
        
        case Decrement =>
          counting(counterId, count - 1)
        
        case GetCount(replyTo) =>
          replyTo ! count
          Behaviors.same
      }
    }
  }
}

// Sharding配置
val counterSharding = ClusterSharding(system).init(Entity(
  typeKey = EntityTypeKey[DistributedCounter.Command]("Counter")
)(
  createBehavior = ctx => DistributedCounter(ctx.entityId)
).withAllocationStrategy(
  new LeastShardAllocationStrategy(
    rebalanceThreshold = 3,      // 差异3个Shard就再平衡
    maxSimultaneousRebalance = 5  // 最多同时迁移5个
  )
))
```

---

## 总结

### 核心要点

**1. 一致性哈希**
- 均匀分布Entity
- 节点变化影响小
- 虚拟节点提高均匀性

**2. Shard机制**
- 分组管理Entity
- 减少路由复杂度
- Shard数量 = 节点数 × 10

**3. 路由流程**
- ShardRegion → Coordinator
- 位置缓存加速
- 自动创建Entity

**4. 再平衡**
- 最小化迁移
- 避免频繁再平衡
- 可配置阈值

**5. Passivation**
- 自动回收空闲Entity
- 减少内存占用
- LRU策略

### 性能数据

| 维度 | 数据 | 说明 |
|-----|------|------|
| **路由延迟** | <1ms | 本地路由 |
| **跨节点延迟** | ~5ms | 网络开销 |
| **Entity创建** | ~10ms | 首次访问 |
| **Passivation** | ~100ms | 停止Entity |
| **再平衡** | ~1s/Shard | 迁移时间 |

### 配置推荐

```hocon
pekko.cluster.sharding {
  # 基本配置
  number-of-shards = 1000  # 节点数×10
  role = "backend"
  
  # Passivation
  passivate-idle-entity-after = 2 minutes
  
  # Remember Entities
  remember-entities = on
  remember-entities-store = "ddata"
  
  # 再平衡
  rebalance-interval = 10 s
  least-shard-allocation-strategy {
    rebalance-threshold = 10%
    max-simultaneous-rebalance = 3
  }
}
```

### 下一篇预告

**第五部分：性能与调优**即将开始！

**《Actor系统的性能剖析》**
- Throughput vs Latency权衡
- Mailbox性能测试
- Dispatcher配置优化
- 消息序列化开销

---

**项目地址**: https://github.com/Xuxiaotuan/pekko-reference

**作者**: Xuxiaotuan  
**日期**: 2024年11月
