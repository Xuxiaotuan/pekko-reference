# 设计文档：分布式工作流引擎架构重构

## 概述

本设计文档描述如何将现有的单机工作流引擎重构为真正的分布式架构。核心思路是引入Pekko Cluster Sharding，将WorkflowActor实例分布到集群的多个节点上，实现负载均衡、故障转移和水平扩展。

### 设计目标

1. **真正的分布式**：工作流实例分布在多个节点，而不是所有实例在同一节点
2. **透明路由**：客户端无需关心工作流在哪个节点，系统自动路由
3. **自动故障转移**：节点宕机时，工作流实例自动迁移到其他节点
4. **向后兼容**：保持现有API和DSL不变
5. **性能提升**：充分利用集群资源，提升系统吞吐量

### 核心变更

- **引入Cluster Sharding**：WorkflowActor通过Sharding分布
- **重构WorkflowSupervisor**：从本地Actor管理器变为Sharding代理
- **保持Event Sourcing**：确保状态持久化和恢复能力
- **保持HTTP API**：API层透明地使用Sharding

## 架构设计

### 当前架构（v0.4）

```
┌─────────────────────────────────────────┐
│           单节点架构                     │
│                                         │
│  HTTP API                               │
│     ↓                                   │
│  WorkflowSupervisor (本地)              │
│     ↓                                   │
│  WorkflowActor (所有实例在同一节点)      │
│     ↓                                   │
│  WorkflowExecutionEngine (本地执行)     │
└─────────────────────────────────────────┘

问题：
- 所有工作流在同一个JVM
- 无法利用集群资源
- 单点故障风险
```

### 目标架构（v0.5）

```
┌──────────────────────────────────────────────────────────────┐
│                    分布式集群架构                              │
│                                                              │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐            │
│  │  Node 1    │  │  Node 2    │  │  Node 3    │            │
│  │            │  │            │  │            │            │
│  │ HTTP API   │  │ HTTP API   │  │ HTTP API   │            │
│  │     ↓      │  │     ↓      │  │     ↓      │            │
│  │ ShardRegion│  │ ShardRegion│  │ ShardRegion│            │
│  │     ↓      │  │     ↓      │  │     ↓      │            │
│  │ Shard 1,4  │  │ Shard 2,5  │  │ Shard 3,6  │            │
│  │     ↓      │  │     ↓      │  │     ↓      │            │
│  │ Workflow   │  │ Workflow   │  │ Workflow   │            │
│  │ Actor A,D  │  │ Actor B,E  │  │ Actor C,F  │            │
│  └────────────┘  └────────────┘  └────────────┘            │
│         │               │               │                   │
│         └───────────────┼───────────────┘                   │
│                         │                                   │
│                  Cluster Sharding                           │
│                  - 自动分片                                  │
│                  - 透明路由                                  │
│                  - 故障转移                                  │
└──────────────────────────────────────────────────────────────┘

优势：
- 工作流分布在多个节点
- 自动负载均衡
- 节点故障自动恢复
- 水平扩展能力
```


## 组件设计

### 1. WorkflowSharding（新增）

**职责**：管理WorkflowActor的Cluster Sharding

```scala
object WorkflowSharding {
  val TypeKey = EntityTypeKey[WorkflowActor.Command]("Workflow")
  
  def init(
    system: ActorSystem[_],
    executionEngine: WorkflowExecutionEngine
  ): ActorRef[ShardingEnvelope[WorkflowActor.Command]] = {
    
    ClusterSharding(system).init(Entity(TypeKey) { entityContext =>
      // 每个工作流实例是一个Entity
      val workflowId = entityContext.entityId
      
      // 使用Event Sourcing的WorkflowActor
      EventSourcedWorkflowActor(workflowId, executionEngine)
    }
    .withRole("worker")  // 只在worker节点上创建
    .withStopMessage(WorkflowActor.Stop))
  }
}
```

**关键设计**：
- EntityId = workflowId（工作流ID作为Entity标识）
- 使用EventSourcedWorkflowActor确保状态持久化
- 限制在worker角色节点上运行
- 支持优雅停止

### 2. WorkflowSupervisor（重构）

**当前实现**：本地管理所有WorkflowActor

```scala
// 旧版本 - 本地管理
class WorkflowSupervisor {
  private var workflows: Map[String, ActorRef[WorkflowActor.Command]] = Map.empty
  
  case CreateWorkflow(workflow, replyTo) =>
    val actor = context.spawn(WorkflowActor(workflow), workflow.id)
    workflows += (workflow.id -> actor)
}
```

**新实现**：Sharding代理

```scala
// 新版本 - Sharding代理
object WorkflowSupervisor {
  def apply(
    shardRegion: ActorRef[ShardingEnvelope[WorkflowActor.Command]]
  ): Behavior[Command] = {
    Behaviors.receive { (context, message) =>
      message match {
        case CreateWorkflow(workflow, replyTo) =>
          // 不再本地创建，而是通过Sharding路由
          shardRegion ! ShardingEnvelope(
            workflow.id,
            WorkflowActor.Initialize(workflow)
          )
          replyTo ! WorkflowCreated(workflow.id)
          Behaviors.same
          
        case ExecuteWorkflow(workflowId, replyTo) =>
          // 透明路由到正确的节点
          shardRegion ! ShardingEnvelope(
            workflowId,
            WorkflowActor.Execute(replyTo)
          )
          Behaviors.same
          
        case GetWorkflowStatus(workflowId, replyTo) =>
          // 透明路由到正确的节点
          shardRegion ! ShardingEnvelope(
            workflowId,
            WorkflowActor.GetStatus(replyTo)
          )
          Behaviors.same
      }
    }
  }
}
```

**关键变更**：
- 不再维护本地Actor引用
- 所有操作通过ShardingEnvelope路由
- 成为无状态的代理层


### 3. EventSourcedWorkflowActor（增强）

**当前实现**：已有Event Sourcing支持

**增强点**：
1. 支持Passivation（空闲时自动停止）
2. 支持Remember Entities（重启后自动恢复）
3. 优化快照策略

```scala
object EventSourcedWorkflowActor {
  def apply(
    workflowId: String,
    executionEngine: WorkflowExecutionEngine
  ): Behavior[Command] = {
    
    Behaviors.setup { context =>
      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.ofUniqueId(workflowId),
        emptyState = State.empty,
        commandHandler = commandHandler,
        eventHandler = eventHandler
      )
      .withRetention(
        RetentionCriteria
          .snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2)
          .withDeleteEventsOnSnapshot
      )
      .receiveSignal {
        case (state, RecoveryCompleted) =>
          context.log.info(s"Workflow $workflowId recovered: ${state.status}")
          
        case (state, SnapshotCompleted(metadata)) =>
          context.log.info(s"Snapshot completed for $workflowId at seqNr ${metadata.sequenceNr}")
      }
    }
  }
}
```

### 4. 分片策略（新增）

**默认策略**：基于workflowId哈希

```scala
object WorkflowShardingStrategy {
  
  // 默认分片数量（可配置）
  val numberOfShards = 100
  
  // 提取EntityId
  val extractEntityId: ShardingMessageExtractor[ShardingEnvelope[WorkflowActor.Command], WorkflowActor.Command] =
    new ShardingMessageExtractor[ShardingEnvelope[WorkflowActor.Command], WorkflowActor.Command] {
      def entityId(envelope: ShardingEnvelope[WorkflowActor.Command]): String = 
        envelope.entityId
      
      def shardId(entityId: String): String = 
        (math.abs(entityId.hashCode) % numberOfShards).toString
      
      def unwrapMessage(envelope: ShardingEnvelope[WorkflowActor.Command]): WorkflowActor.Command = 
        envelope.message
    }
}
```

**自定义策略**（可选）：

```scala
// 基于工作流类型的分片策略
class WorkflowTypeBasedStrategy extends ShardingMessageExtractor[...] {
  def shardId(entityId: String): String = {
    // 根据工作流类型分片
    val workflowType = extractWorkflowType(entityId)
    workflowType match {
      case "data-pipeline" => "shard-data"
      case "ml-training" => "shard-ml"
      case _ => "shard-default"
    }
  }
}
```

### 5. HTTP API层（适配）

**当前实现**：直接与WorkflowSupervisor交互

**新实现**：通过Sharding透明路由

```scala
object WorkflowRoutes {
  def routes(
    shardRegion: ActorRef[ShardingEnvelope[WorkflowActor.Command]]
  )(implicit system: ActorSystem[_], timeout: Timeout): Route = {
    
    pathPrefix("api" / "v1" / "workflows") {
      concat(
        // 创建工作流
        post {
          entity(as[WorkflowDSL.Workflow]) { workflow =>
            // 通过Sharding创建
            val future = shardRegion.ask[WorkflowCreated](replyTo =>
              ShardingEnvelope(
                workflow.id,
                WorkflowActor.Initialize(workflow, replyTo)
              )
            )
            
            onSuccess(future) { response =>
              complete(StatusCodes.Created, response)
            }
          }
        },
        
        // 执行工作流
        path(Segment / "execute") { workflowId =>
          post {
            // 通过Sharding路由
            val future = shardRegion.ask[ExecutionResponse](replyTo =>
              ShardingEnvelope(
                workflowId,
                WorkflowActor.Execute(replyTo)
              )
            )
            
            onSuccess(future) { response =>
              complete(StatusCodes.OK, response)
            }
          }
        },
        
        // 查询状态
        path(Segment / "status") { workflowId =>
          get {
            // 通过Sharding路由
            val future = shardRegion.ask[StatusResponse](replyTo =>
              ShardingEnvelope(
                workflowId,
                WorkflowActor.GetStatus(replyTo)
              )
            )
            
            onSuccess(future) { response =>
              complete(StatusCodes.OK, response)
            }
          }
        }
      )
    }
  }
}
```

**关键点**：
- API接口保持不变
- 内部使用ShardingEnvelope路由
- 客户端无感知


## 数据模型

### 1. ShardingEnvelope

```scala
// Pekko提供的标准消息包装
case class ShardingEnvelope[M](
  entityId: String,  // workflowId
  message: M         // WorkflowActor.Command
)
```

### 2. WorkflowActor消息协议（保持不变）

```scala
object WorkflowActor {
  sealed trait Command
  
  // 新增：初始化命令
  case class Initialize(
    workflow: WorkflowDSL.Workflow,
    replyTo: ActorRef[WorkflowCreated]
  ) extends Command
  
  // 现有命令保持不变
  case class Execute(replyTo: ActorRef[ExecutionResponse]) extends Command
  case class GetStatus(replyTo: ActorRef[StatusResponse]) extends Command
  case object Stop extends Command
  
  // 响应消息
  case class WorkflowCreated(workflowId: String)
  case class ExecutionResponse(executionId: String, status: String)
  case class StatusResponse(workflowId: String, status: String, logs: List[String])
}
```

### 3. 分片元数据

```scala
case class ShardMetadata(
  shardId: String,
  region: Address,           // 所在节点地址
  entityCount: Int,          // Entity数量
  lastUpdated: Instant
)

case class ClusterShardingStats(
  shards: Map[String, ShardMetadata],
  totalEntities: Int,
  totalShards: Int
)
```

## 配置管理

### application.conf

```hocon
pekko {
  cluster {
    # 节点角色
    roles = ["worker", "api-gateway"]
    
    # Sharding配置
    sharding {
      # 分片数量
      number-of-shards = 100
      
      # 角色限制
      role = "worker"
      
      # Passivation配置
      passivate-idle-entity-after = 30m
      
      # Remember Entities
      remember-entities = on
      remember-entities-store = "eventsourced"
      
      # 再平衡配置
      rebalance-interval = 10s
      least-shard-allocation-strategy {
        rebalance-threshold = 3
        max-simultaneous-rebalance = 5
      }
    }
  }
  
  persistence {
    journal {
      plugin = "pekko.persistence.journal.leveldb"
      leveldb {
        dir = "target/journal"
        native = false
      }
    }
    
    snapshot-store {
      plugin = "pekko.persistence.snapshot-store.local"
      local.dir = "target/snapshots"
    }
  }
}
```

### 配置说明

- **number-of-shards**: 分片数量，建议为节点数的10倍
- **role**: 限制Sharding只在worker节点运行
- **passivate-idle-entity-after**: 空闲Entity自动停止时间
- **remember-entities**: 节点重启后自动恢复Entity
- **rebalance-interval**: 再平衡检查间隔
- **rebalance-threshold**: 触发再平衡的阈值


## 正确性属性

*A property is a characteristic or behavior that should hold true across all valid executions of a system-essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### 分布式部署属性

**Property 1: 工作流分片一致性**
*For any* 工作流ID，多次查询应该总是路由到相同的Shard和节点（在没有再平衡的情况下）
**Validates: Requirements 1.1, 1.3, 1.4**

**Property 2: 负载均衡性**
*For any* 大量工作流实例（N > 100），它们在各个节点上的分布应该相对均匀（标准差 < 平均值的20%）
**Validates: Requirements 1.2, 5.1**

**Property 3: 路由透明性**
*For any* 工作流操作（创建/执行/查询），客户端无需指定目标节点，系统自动路由到正确节点
**Validates: Requirements 4.1, 4.2, 4.3**

### 故障恢复属性

**Property 4: 故障转移完整性**
*For any* 工作流实例，当其所在节点宕机后，该实例应该在其他节点上重新启动，且状态完整恢复
**Validates: Requirements 2.1, 2.2**

**Property 5: 执行连续性**
*For any* 正在执行的工作流，节点故障后应该能够从最近的检查点继续执行，不丢失已完成的进度
**Validates: Requirements 2.4**

**Property 6: 路由更新及时性**
*For any* 工作流实例迁移，后续请求应该在迁移完成后的5秒内自动路由到新节点
**Validates: Requirements 2.5, 4.4**

### 分片策略属性

**Property 7: 哈希分片确定性**
*For any* 工作流ID，使用哈希分片策略时，相同的ID应该总是映射到相同的Shard
**Validates: Requirements 3.2**

**Property 8: 分片数量正确性**
*For any* 配置的分片数量N，系统应该创建恰好N个Shard，不多不少
**Validates: Requirements 3.1**

**Property 9: 再平衡保持一致性**
*For any* 分片再平衡操作，所有Entity应该在再平衡前后保持可访问，且状态不丢失
**Validates: Requirements 1.5, 3.4**

### 监控和可观测性属性

**Property 10: 状态查询准确性**
*For any* 时刻，查询集群状态应该返回准确的节点数量、Shard分布和Entity数量
**Validates: Requirements 6.1, 6.2**

**Property 11: 事件记录完整性**
*For any* 工作流迁移或再平衡事件，系统应该记录事件的时间、原因和涉及的Entity
**Validates: Requirements 6.3, 6.4**

**Property 12: 位置查询正确性**
*For any* 工作流ID，查询其位置应该返回当前实际所在的节点地址
**Validates: Requirements 6.5**

### 性能和扩展性属性

**Property 13: 线性扩展性**
*For any* 节点数量N（N >= 2），系统的总吞吐量应该至少是单节点吞吐量的0.8N倍
**Validates: Requirements 8.1**

**Property 14: 性能隔离性**
*For any* 工作流实例，其执行延迟不应该随着集群中总工作流数量的增加而显著增加（增幅 < 20%）
**Validates: Requirements 8.3**

**Property 15: 动态扩展响应性**
*For any* 新加入的节点，应该在30秒内开始接受新的工作流实例分配
**Validates: Requirements 8.4**

### 向后兼容性属性

**Property 16: API兼容性**
*For any* 现有的HTTP API调用，在新架构下应该返回相同格式的响应（可能有性能差异）
**Validates: Requirements 7.1**

**Property 17: DSL兼容性**
*For any* 现有的WorkflowDSL定义，应该能够在新架构下正常解析和执行
**Validates: Requirements 7.2**

**Property 18: Event Sourcing兼容性**
*For any* 旧版本产生的Event，新版本应该能够正常读取和回放
**Validates: Requirements 7.3**


## 错误处理

### 1. 节点故障

**场景**：运行工作流的节点宕机

**处理策略**：
1. Cluster检测到节点不可达（通过Phi Accrual故障检测器）
2. Sharding自动将该节点的Shard标记为不可用
3. 在其他节点上重新启动受影响的Entity
4. Entity通过Event Sourcing恢复状态
5. 更新路由表，后续请求路由到新节点

**超时配置**：
- 故障检测时间：< 10秒
- Entity重启时间：< 5秒
- 总故障转移时间：< 15秒

### 2. 网络分区

**场景**：集群发生网络分区

**处理策略**：
1. 使用Split Brain Resolver检测分区
2. 保留多数派分区，关闭少数派分区
3. 少数派节点上的Entity在多数派节点重启
4. 分区恢复后，少数派节点重新加入集群

**配置**：
```hocon
pekko.cluster.split-brain-resolver {
  active-strategy = keep-majority
  stable-after = 20s
}
```

### 3. Entity启动失败

**场景**：Entity启动时发生异常

**处理策略**：
1. 使用Supervisor Strategy重试
2. 指数退避：3s → 6s → 12s → 30s
3. 最多重试5次
4. 失败后记录错误日志，Entity进入失败状态

```scala
Behaviors.supervise(entityBehavior)
  .onFailure[Exception](
    SupervisorStrategy.restartWithBackoff(
      minBackoff = 3.seconds,
      maxBackoff = 30.seconds,
      randomFactor = 0.2
    ).withMaxRestarts(5)
  )
```

### 4. 路由超时

**场景**：向Entity发送消息超时

**处理策略**：
1. 设置合理的Ask超时（默认5秒）
2. 超时后返回明确的错误信息
3. 客户端可以选择重试

```scala
implicit val timeout: Timeout = 5.seconds

shardRegion.ask[Response](replyTo => 
  ShardingEnvelope(entityId, Command(replyTo))
).recover {
  case _: TimeoutException =>
    ErrorResponse("Request timeout, entity may be busy or unreachable")
}
```

### 5. 分片再平衡冲突

**场景**：再平衡期间收到请求

**处理策略**：
1. 再平衡期间，Entity仍然可以处理请求
2. 使用Handoff机制确保平滑迁移
3. 迁移完成前，请求路由到旧节点
4. 迁移完成后，请求路由到新节点

## 测试策略

### 单元测试

**测试目标**：验证各个组件的独立功能

```scala
class WorkflowShardingSpec extends ScalaTestWithActorTestKit {
  "WorkflowSharding" should "route messages to correct entity" in {
    val sharding = WorkflowSharding.init(system, executionEngine)
    
    // 发送消息到同一个workflowId
    sharding ! ShardingEnvelope("workflow-1", Initialize(workflow))
    sharding ! ShardingEnvelope("workflow-1", Execute(probe.ref))
    
    // 验证消息被路由到同一个Entity
    probe.expectMessage(ExecutionResponse("exec_xxx", "started"))
  }
}
```

### 集成测试

**测试目标**：验证组件间的交互

```scala
class DistributedWorkflowIntegrationSpec extends ScalaTestWithActorTestKit {
  "Distributed workflow system" should "execute workflow across cluster" in {
    // 启动3节点集群
    val cluster = Cluster(system)
    
    // 创建工作流
    val createFuture = httpClient.post("/api/v1/workflows", workflow)
    
    // 执行工作流
    val executeFuture = httpClient.post(s"/api/v1/workflows/${workflow.id}/execute")
    
    // 验证执行成功
    executeFuture.futureValue.status shouldBe "completed"
  }
}
```

### 属性测试

**测试目标**：验证正确性属性

```scala
class WorkflowShardingPropertySpec extends AnyPropSpec with PropertyChecks {
  property("Property 1: Workflow sharding consistency") {
    forAll(workflowIdGen) { workflowId =>
      // 多次查询应该路由到相同的Shard
      val shard1 = getShardId(workflowId)
      val shard2 = getShardId(workflowId)
      val shard3 = getShardId(workflowId)
      
      shard1 shouldBe shard2
      shard2 shouldBe shard3
    }
  }
  
  property("Property 2: Load balancing") {
    forAll(Gen.listOfN(1000, workflowIdGen)) { workflowIds =>
      // 大量工作流应该均匀分布
      val distribution = workflowIds.groupBy(getShardId).mapValues(_.size)
      val mean = distribution.values.sum.toDouble / distribution.size
      val variance = distribution.values.map(v => math.pow(v - mean, 2)).sum / distribution.size
      val stdDev = math.sqrt(variance)
      
      stdDev should be < (mean * 0.2)  // 标准差 < 平均值的20%
    }
  }
}
```

### 故障注入测试

**测试目标**：验证故障恢复能力

```scala
class FaultToleranceSpec extends ScalaTestWithActorTestKit {
  "System" should "recover from node failure" in {
    // 启动3节点集群
    val nodes = startCluster(3)
    
    // 创建工作流
    createWorkflow("workflow-1")
    
    // 查询工作流所在节点
    val node = getWorkflowLocation("workflow-1")
    
    // 杀死该节点
    killNode(node)
    
    // 等待故障转移
    Thread.sleep(15000)
    
    // 验证工作流仍然可访问
    val status = getWorkflowStatus("workflow-1")
    status should not be empty
  }
}
```

### 性能测试

**测试目标**：验证性能和扩展性

```scala
class PerformanceSpec extends AnyFlatSpec {
  "System" should "scale linearly with nodes" in {
    // 测试1节点吞吐量
    val throughput1 = measureThroughput(nodes = 1, workflows = 100)
    
    // 测试3节点吞吐量
    val throughput3 = measureThroughput(nodes = 3, workflows = 300)
    
    // 验证线性扩展（至少2.4倍）
    throughput3 should be >= (throughput1 * 2.4)
  }
}
```


## 部署架构

### 开发环境

```bash
# 单节点模式（用于开发和调试）
sbt "project pekko-server" "runMain cn.xuyinyin.magic.PekkoServer 2551"
```

### 测试环境

```bash
# 3节点集群
# 节点1 (Seed + Worker + API Gateway)
sbt -Dpekko.cluster.roles.0=worker -Dpekko.cluster.roles.1=api-gateway \
    "project pekko-server" "runMain cn.xuyinyin.magic.PekkoServer 2551"

# 节点2 (Worker)
sbt -Dpekko.cluster.roles.0=worker \
    -Dpekko.remote.artery.canonical.port=2552 \
    "project pekko-server" "runMain cn.xuyinyin.magic.PekkoServer 2552"

# 节点3 (Worker)
sbt -Dpekko.cluster.roles.0=worker \
    -Dpekko.remote.artery.canonical.port=2553 \
    "project pekko-server" "runMain cn.xuyinyin.magic.PekkoServer 2553"
```

### 生产环境（Kubernetes）

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: pekko-workflow
spec:
  serviceName: pekko-workflow
  replicas: 3
  selector:
    matchLabels:
      app: pekko-workflow
  template:
    metadata:
      labels:
        app: pekko-workflow
    spec:
      containers:
      - name: pekko-workflow
        image: pekko-workflow:0.5
        ports:
        - containerPort: 2551
          name: remoting
        - containerPort: 8080
          name: http
        env:
        - name: PEKKO_CLUSTER_ROLES
          value: "worker,api-gateway"
        - name: PEKKO_CLUSTER_SEED_NODES
          value: "pekko://pekko-cluster-system@pekko-workflow-0.pekko-workflow:2551"
        resources:
          requests:
            memory: "2Gi"
            cpu: "1000m"
          limits:
            memory: "4Gi"
            cpu: "2000m"
        livenessProbe:
          httpGet:
            path: /health/live
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /health/ready
            port: 8080
          initialDelaySeconds: 20
          periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: pekko-workflow
spec:
  clusterIP: None
  selector:
    app: pekko-workflow
  ports:
  - port: 2551
    name: remoting
  - port: 8080
    name: http
```

## 迁移策略

### Phase 1: 准备阶段（1周）

**目标**：搭建基础设施，不影响现有功能

**任务**：
1. 添加Cluster Sharding依赖
2. 创建WorkflowSharding组件
3. 配置Sharding参数
4. 编写单元测试

**验证**：
- 编译通过
- 单元测试通过
- 不影响现有功能

### Phase 2: 并行运行（2周）

**目标**：新旧架构并行运行，逐步切换流量

**任务**：
1. 重构WorkflowSupervisor支持Sharding
2. 添加特性开关（Feature Toggle）
3. 小流量测试（10%）
4. 监控和调优

**特性开关**：
```hocon
workflow {
  use-sharding = false  # 默认关闭，逐步开启
}
```

**验证**：
- 10%流量使用Sharding，无错误
- 性能指标正常
- 故障转移测试通过

### Phase 3: 全量切换（1周）

**目标**：100%流量切换到新架构

**任务**：
1. 逐步提升Sharding流量：10% → 50% → 100%
2. 监控关键指标
3. 准备回滚方案
4. 删除旧代码

**回滚方案**：
```bash
# 如果出现问题，立即回滚
kubectl set env deployment/pekko-workflow WORKFLOW_USE_SHARDING=false
```

**验证**：
- 100%流量使用Sharding
- 所有功能正常
- 性能达到预期

### Phase 4: 优化阶段（持续）

**目标**：持续优化和改进

**任务**：
1. 调优分片数量和策略
2. 优化Passivation配置
3. 改进监控和告警
4. 性能压测和调优

## 监控指标

### 关键指标

```scala
// Prometheus指标定义
object WorkflowShardingMetrics {
  // Entity数量
  val entityCount = Gauge.build()
    .name("workflow_entity_count")
    .help("Number of workflow entities")
    .labelNames("node", "shard")
    .register()
  
  // 路由延迟
  val routingLatency = Histogram.build()
    .name("workflow_routing_latency_seconds")
    .help("Workflow routing latency")
    .register()
  
  // 故障转移次数
  val failoverCount = Counter.build()
    .name("workflow_failover_total")
    .help("Total number of workflow failovers")
    .labelNames("reason")
    .register()
  
  // 再平衡次数
  val rebalanceCount = Counter.build()
    .name("workflow_rebalance_total")
    .help("Total number of shard rebalances")
    .register()
}
```

### Grafana仪表板

**面板1：集群概览**
- 节点数量
- 总Entity数量
- 每个节点的Entity数量
- Shard分布

**面板2：性能指标**
- 路由延迟（P50, P95, P99）
- 工作流执行延迟
- 吞吐量（QPS）

**面板3：故障和恢复**
- 故障转移次数
- 再平衡次数
- Entity重启次数

**面板4：资源使用**
- CPU使用率
- 内存使用率
- 网络流量

## 风险和缓解措施

### 风险1：性能下降

**描述**：Sharding引入的网络开销可能导致性能下降

**缓解措施**：
- 优化分片数量，减少跨节点通信
- 使用本地缓存减少远程调用
- 监控网络延迟，及时发现问题

### 风险2：数据不一致

**描述**：分布式环境下可能出现数据不一致

**缓解措施**：
- 使用Event Sourcing确保数据持久化
- 使用Cluster Singleton确保全局唯一性
- 完善的测试覆盖

### 风险3：运维复杂度增加

**描述**：分布式系统的运维和调试更复杂

**缓解措施**：
- 完善的监控和告警
- 详细的日志记录
- 自动化运维工具
- 完善的文档

### 风险4：兼容性问题

**描述**：新架构可能与现有功能不兼容

**缓解措施**：
- 完整的回归测试
- 特性开关支持快速回滚
- 灰度发布，逐步切换

## 总结

本设计文档描述了将工作流引擎从单机架构重构为分布式架构的完整方案。核心思路是引入Pekko Cluster Sharding，实现工作流实例的分布式部署、自动故障转移和水平扩展。

**关键设计决策**：
1. 使用Cluster Sharding而不是自己实现分布式协调
2. 保持Event Sourcing确保数据不丢失
3. 保持API向后兼容，客户端无感知
4. 分阶段迁移，降低风险

**预期收益**：
- 工作流实例分布在多个节点，充分利用集群资源
- 节点故障自动恢复，提高系统可用性
- 支持水平扩展，提升系统吞吐量
- 为未来的节点Actor化打下基础
