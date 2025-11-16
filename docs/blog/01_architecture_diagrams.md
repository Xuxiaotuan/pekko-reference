# 架构图详解

> 配套文档：从DolphinScheduler到Pekko - 战略篇

本文档提供详细的系统架构图和组件交互图。

---

## 系统架构全景图

```mermaid
graph TB
    subgraph "用户接口层"
        UI[Web UI<br/>可视化编排]
        API[REST API<br/>编程接口]
        CLI[CLI<br/>命令行工具]
    end
    
    subgraph "API网关层"
        Gateway[API Gateway<br/>HTTP Routes]
    end
    
    subgraph "Pekko集群核心"
        subgraph "节点1"
            Guardian1[PekkoGuardian]
            WS1[WorkflowSupervisor]
            WA1[WorkflowActor 1..n]
            Scheduler1[WorkflowScheduler]
        end
        
        subgraph "节点2"
            Guardian2[PekkoGuardian]
            WS2[WorkflowSupervisor]
            WA2[WorkflowActor 1..n]
            Scheduler2[WorkflowScheduler]
        end
        
        subgraph "节点3"
            Guardian3[PekkoGuardian]
            WS3[WorkflowSupervisor]
            WA3[WorkflowActor 1..n]
            Scheduler3[WorkflowScheduler]
        end
        
        CS[Cluster Sharding<br/>分布式工作流路由]
    end
    
    subgraph "执行引擎层"
        Engine[WorkflowExecutionEngine]
        SourceExec[SourceExecutor]
        TransformExec[TransformExecutor]
        SinkExec[SinkExecutor]
    end
    
    subgraph "节点注册中心"
        Registry[NodeRegistry<br/>插件化节点管理]
        SourceNodes[Source节点<br/>MySQL/Kafka/File/HTTP]
        TransformNodes[Transform节点<br/>Filter/Map/Aggregate]
        SinkNodes[Sink节点<br/>MySQL/Kafka/File/Console]
    end
    
    subgraph "事件源"
        Cron[Cron定时器]
        EventBus[Event Bus<br/>Kafka/Pulsar]
        CDC[CDC变更流]
        APITrigger[API触发]
    end
    
    subgraph "持久化层"
        EventStore[(Event Store<br/>Cassandra)]
        Metadata[(Metadata DB<br/>PostgreSQL)]
        DistributedData[Distributed Data<br/>配置共享]
    end
    
    subgraph "监控与可观测性"
        Metrics[Prometheus Metrics]
        Tracing[Distributed Tracing<br/>Jaeger]
        Logging[Centralized Logging<br/>ELK]
    end
    
    UI --> Gateway
    API --> Gateway
    CLI --> Gateway
    
    Gateway --> Guardian1
    Gateway --> Guardian2
    Gateway --> Guardian3
    
    Guardian1 --> WS1
    Guardian2 --> WS2
    Guardian3 --> WS3
    
    WS1 --> CS
    WS2 --> CS
    WS3 --> CS
    
    CS --> WA1
    CS --> WA2
    CS --> WA3
    
    WA1 --> Engine
    WA2 --> Engine
    WA3 --> Engine
    
    Engine --> SourceExec
    Engine --> TransformExec
    Engine --> SinkExec
    
    SourceExec --> Registry
    TransformExec --> Registry
    SinkExec --> Registry
    
    Registry --> SourceNodes
    Registry --> TransformNodes
    Registry --> SinkNodes
    
    Cron --> Scheduler1
    EventBus --> Guardian1
    CDC --> Guardian2
    APITrigger --> Gateway
    
    WA1 -.持久化.-> EventStore
    WA2 -.持久化.-> EventStore
    WA3 -.持久化.-> EventStore
    
    Guardian1 -.配置.-> DistributedData
    Guardian2 -.配置.-> DistributedData
    Guardian3 -.配置.-> DistributedData
    
    WS1 -.元数据.-> Metadata
    WS2 -.元数据.-> Metadata
    WS3 -.元数据.-> Metadata
    
    WA1 -.监控.-> Metrics
    WA2 -.追踪.-> Tracing
    WA3 -.日志.-> Logging
    
    style CS fill:#ff6b6b
    style Registry fill:#4ecdc4
    style EventStore fill:#95e1d3
    style DistributedData fill:#f38181
```

---

## Actor层级结构图

```mermaid
graph TD
    Root[PekkoGuardian<br/>根Guardian Actor]
    
    Root --> CL[ClusterListener<br/>集群事件监听]
    Root --> HC[HealthChecker<br/>健康检查]
    Root --> WS[WorkflowSupervisor<br/>工作流监督者]
    Root --> SM[SchedulerManager<br/>调度管理器]
    
    WS --> WA1[WorkflowActor<br/>workflow-1]
    WS --> WA2[WorkflowActor<br/>workflow-2]
    WS --> WA3[WorkflowActor<br/>workflow-3]
    
    WA1 --> TA11[TaskActor<br/>task-1-1]
    WA1 --> TA12[TaskActor<br/>task-1-2]
    WA1 --> TA13[TaskActor<br/>task-1-3]
    
    WA2 --> TA21[TaskActor<br/>task-2-1]
    WA2 --> TA22[TaskActor<br/>task-2-2]
    
    SM --> Sched1[WorkflowScheduler<br/>Scheduler Instance 1]
    SM --> Sched2[WorkflowScheduler<br/>Scheduler Instance 2]
    
    style Root fill:#ffcc00
    style WS fill:#ff6b6b
    style WA1 fill:#4ecdc4
    style WA2 fill:#4ecdc4
    style WA3 fill:#4ecdc4
    style SM fill:#95e1d3
```

---

## 工作流执行时序图

```mermaid
sequenceDiagram
    participant User
    participant API as API Gateway
    participant Guardian as PekkoGuardian
    participant Supervisor as WorkflowSupervisor
    participant WF as WorkflowActor
    participant Task as TaskActor
    participant Engine as ExecutionEngine
    participant Registry as NodeRegistry
    participant Source as MySQLSource
    participant Stream as Pekko Streams
    
    User->>API: POST /api/v1/workflows/{id}/execute
    API->>Guardian: GetWorkflowSupervisor
    Guardian-->>API: WorkflowSupervisor ref
    API->>Supervisor: ExecuteWorkflow(workflowId)
    
    Supervisor->>WF: Execute
    activate WF
    
    WF->>Task: Execute
    activate Task
    
    Task->>Engine: execute(task)
    Engine->>Registry: getSource("mysql", config)
    Registry->>Source: createSource(config)
    Source-->>Registry: Source[Row, _]
    Registry-->>Engine: Source
    
    Engine->>Stream: run()
    activate Stream
    Stream->>Stream: 处理数据流
    Stream-->>Engine: Future[Done]
    deactivate Stream
    
    Engine-->>Task: ExecutionResult
    Task->>WF: TaskCompleted(taskId)
    deactivate Task
    
    WF->>WF: 检查依赖
    WF->>Task: Execute (下游任务)
    
    Task-->>WF: TaskCompleted
    WF->>Supervisor: WorkflowCompleted
    deactivate WF
    
    Supervisor-->>API: Success
    API-->>User: 200 OK
```

---

## 集群分片机制图

```mermaid
graph TB
    subgraph "Cluster Sharding Layer"
        Shard1[Shard 0-33]
        Shard2[Shard 34-66]
        Shard3[Shard 67-99]
    end
    
    subgraph "Node 1"
        N1Guardian[Guardian]
        N1Shard1[Shard Manager]
        WF1[Workflow-A<br/>hash=12]
        WF2[Workflow-B<br/>hash=25]
        WF3[Workflow-C<br/>hash=30]
    end
    
    subgraph "Node 2"
        N2Guardian[Guardian]
        N2Shard2[Shard Manager]
        WF4[Workflow-D<br/>hash=45]
        WF5[Workflow-E<br/>hash=52]
        WF6[Workflow-F<br/>hash=60]
    end
    
    subgraph "Node 3"
        N3Guardian[Guardian]
        N3Shard3[Shard Manager]
        WF7[Workflow-G<br/>hash=70]
        WF8[Workflow-H<br/>hash=85]
        WF9[Workflow-I<br/>hash=95]
    end
    
    Shard1 --> N1Shard1
    Shard2 --> N2Shard2
    Shard3 --> N3Shard3
    
    N1Shard1 --> WF1
    N1Shard1 --> WF2
    N1Shard1 --> WF3
    
    N2Shard2 --> WF4
    N2Shard2 --> WF5
    N2Shard2 --> WF6
    
    N3Shard3 --> WF7
    N3Shard3 --> WF8
    N3Shard3 --> WF9
    
    style Shard1 fill:#ffcc00
    style Shard2 fill:#ffcc00
    style Shard3 fill:#ffcc00
```

### 分片策略说明

```scala
// WorkflowId → Shard映射
workflowId.hashCode % 100 → Shard[0-99]

// 例如:
"workflow-abc".hashCode % 100 = 45 → Shard45 → Node2
"workflow-xyz".hashCode % 100 = 12 → Shard12 → Node1
```

**优势**：
- 自动负载均衡
- 同一workflow的消息总是路由到同一Actor
- 节点故障时自动rebalance

---

## 节点注册系统图

```mermaid
graph LR
    subgraph "NodeRegistry"
        Registry[Node Registry<br/>Central Registry]
    end
    
    subgraph "Source Nodes"
        MySQL[MySQLSource]
        Kafka[KafkaSource]
        File[FileTextSource]
        HTTP[HttpSource]
        Custom1[CustomSource]
    end
    
    subgraph "Transform Nodes"
        Filter[FilterTransform]
        Map[MapTransform]
        Agg[AggregateTransform]
        Custom2[CustomTransform]
    end
    
    subgraph "Sink Nodes"
        MySQLSink[MySQLSink]
        KafkaSink[KafkaSink]
        FileSink[FileTextSink]
        ConsoleSink[ConsoleLogSink]
        Custom3[CustomSink]
    end
    
    subgraph "Executor Layer"
        SourceExec[SourceExecutor]
        TransformExec[TransformExecutor]
        SinkExec[SinkExecutor]
    end
    
    SourceExec --> Registry
    TransformExec --> Registry
    SinkExec --> Registry
    
    Registry --> MySQL
    Registry --> Kafka
    Registry --> File
    Registry --> HTTP
    Registry --> Custom1
    
    Registry --> Filter
    Registry --> Map
    Registry --> Agg
    Registry --> Custom2
    
    Registry --> MySQLSink
    Registry --> KafkaSink
    Registry --> FileSink
    Registry --> ConsoleSink
    Registry --> Custom3
    
    style Registry fill:#4ecdc4
    style Custom1 fill:#ff6b6b
    style Custom2 fill:#ff6b6b
    style Custom3 fill:#ff6b6b
```

### 注册机制

```scala
// 1. 内置节点自动注册
object NodeRegistry {
  private val sources = Map(
    "mysql" -> MySQLSource,
    "kafka" -> KafkaSource,
    "file" -> FileTextSource,
    "http" -> HttpSource
  )
  
  // 2. 动态注册自定义节点
  def register(nodeType: String, node: NodeSource): Unit = {
    sources += (nodeType -> node)
  }
  
  // 3. 获取节点
  def getSource(nodeType: String, config: SourceConfig): Source[_, _] = {
    sources.get(nodeType)
      .map(_.createSource(config))
      .getOrElse(throw new IllegalArgumentException(s"Unknown: $nodeType"))
  }
}
```

---

## 调度器架构图

```mermaid
graph TB
    subgraph "调度触发层"
        CronTrigger[Cron定时触发]
        FixedRateTrigger[固定频率触发]
        FixedDelayTrigger[固定延迟触发]
        ImmediateTrigger[立即触发]
        EventTrigger[事件触发]
    end
    
    subgraph "调度管理层"
        SM[SchedulerManager<br/>调度统一管理]
        
        subgraph "调度实例"
            WS1[WorkflowScheduler 1<br/>workflow-a]
            WS2[WorkflowScheduler 2<br/>workflow-b]
            WS3[WorkflowScheduler 3<br/>workflow-c]
        end
    end
    
    subgraph "执行层"
        Supervisor[WorkflowSupervisor]
        WA1[WorkflowActor<br/>workflow-a]
        WA2[WorkflowActor<br/>workflow-b]
        WA3[WorkflowActor<br/>workflow-c]
    end
    
    CronTrigger --> WS1
    FixedRateTrigger --> WS2
    FixedDelayTrigger --> WS3
    ImmediateTrigger --> SM
    EventTrigger --> SM
    
    SM --> WS1
    SM --> WS2
    SM --> WS3
    
    WS1 --> Supervisor
    WS2 --> Supervisor
    WS3 --> Supervisor
    
    Supervisor --> WA1
    Supervisor --> WA2
    Supervisor --> WA3
    
    style SM fill:#95e1d3
    style Supervisor fill:#ff6b6b
```

### 调度类型说明

| 调度类型 | 说明 | 使用场景 |
|---------|------|---------|
| **Cron** | 基于Cron表达式 | 每天凌晨2点执行 |
| **Fixed Rate** | 固定频率（不考虑执行时间） | 每小时执行一次 |
| **Fixed Delay** | 固定延迟（上次执行完后延迟） | 执行完成后等待30分钟 |
| **Immediate** | 立即执行一次 | 手动触发 |
| **Event** | 事件驱动触发 | 数据到达时执行 |

---

## 事件驱动架构图（规划中）

```mermaid
graph TB
    subgraph "事件源"
        CDC[CDC变更流<br/>MySQL Binlog]
        Kafka[Kafka消息]
        FileWatch[文件监听<br/>inotify/FSEvents]
        Webhook[Webhook]
        Schedule[定时调度]
    end
    
    subgraph "事件总线"
        EventBus[Event Bus<br/>统一事件处理]
        
        subgraph "事件处理器"
            Filter[事件过滤器]
            Router[事件路由器]
            Transformer[事件转换器]
        end
    end
    
    subgraph "工作流触发"
        TriggerEngine[Trigger Engine]
        Rule1[Trigger Rule 1<br/>订单创建→处理工作流]
        Rule2[Trigger Rule 2<br/>文件上传→ETL工作流]
        Rule3[Trigger Rule 3<br/>Binlog变更→同步工作流]
    end
    
    subgraph "工作流执行"
        Supervisor[WorkflowSupervisor]
        WF1[WorkflowActor 1]
        WF2[WorkflowActor 2]
        WF3[WorkflowActor 3]
    end
    
    CDC --> EventBus
    Kafka --> EventBus
    FileWatch --> EventBus
    Webhook --> EventBus
    Schedule --> EventBus
    
    EventBus --> Filter
    Filter --> Router
    Router --> Transformer
    Transformer --> TriggerEngine
    
    TriggerEngine --> Rule1
    TriggerEngine --> Rule2
    TriggerEngine --> Rule3
    
    Rule1 --> Supervisor
    Rule2 --> Supervisor
    Rule3 --> Supervisor
    
    Supervisor --> WF1
    Supervisor --> WF2
    Supervisor --> WF3
    
    style EventBus fill:#ffcc00
    style TriggerEngine fill:#4ecdc4
```

---

## 持久化架构图（规划中）

```mermaid
graph TB
    subgraph "Actor层"
        WF[WorkflowActor<br/>Event Sourced]
    end
    
    subgraph "事件持久化"
        Journal[(Event Journal<br/>Cassandra/PostgreSQL)]
        
        subgraph "事件类型"
            E1[WorkflowStarted]
            E2[TaskScheduled]
            E3[TaskCompleted]
            E4[WorkflowCompleted]
            E5[WorkflowFailed]
        end
    end
    
    subgraph "快照存储"
        Snapshot[(Snapshot Store<br/>定期快照)]
    end
    
    subgraph "状态恢复"
        Recovery[Recovery Handler<br/>故障恢复]
    end
    
    subgraph "查询侧"
        ReadModel[(Read Model<br/>PostgreSQL)]
        API[Query API<br/>工作流查询]
    end
    
    WF -->|persist event| Journal
    Journal --> E1
    Journal --> E2
    Journal --> E3
    Journal --> E4
    Journal --> E5
    
    WF -->|save snapshot| Snapshot
    
    Journal --> Recovery
    Snapshot --> Recovery
    Recovery --> WF
    
    Journal -->|projection| ReadModel
    ReadModel --> API
    
    style Journal fill:#95e1d3
    style Snapshot fill:#f38181
    style ReadModel fill:#4ecdc4
```

### 事件溯源优势

1. **完整历史**：每个状态变更都被记录
2. **时间旅行**：可以回溯到任意时间点的状态
3. **审计友好**：天然支持审计需求
4. **故障恢复**：通过重放事件恢复状态
5. **CQRS支持**：命令和查询分离

---

## 监控与可观测性架构图

```mermaid
graph TB
    subgraph "Pekko应用"
        WF1[WorkflowActor 1]
        WF2[WorkflowActor 2]
        WF3[WorkflowActor 3]
        Streams[Pekko Streams]
    end
    
    subgraph "指标采集"
        Metrics[Prometheus Metrics<br/>Cinnamon/Kamon]
        
        subgraph "指标类型"
            M1[Actor Mailbox Size]
            M2[Message Processing Time]
            M3[Stream Throughput]
            M4[Error Rate]
            M5[Cluster Health]
        end
    end
    
    subgraph "分布式追踪"
        Tracing[OpenTelemetry/Jaeger]
        
        subgraph "追踪内容"
            T1[Workflow Execution]
            T2[Task Dependencies]
            T3[External API Calls]
            T4[Database Queries]
        end
    end
    
    subgraph "日志聚合"
        Logging[ELK/Loki]
        
        subgraph "日志类型"
            L1[Application Logs]
            L2[Actor Lifecycle]
            L3[Error Logs]
            L4[Audit Logs]
        end
    end
    
    subgraph "可视化与告警"
        Grafana[Grafana Dashboard]
        Alertmanager[Alert Manager]
    end
    
    WF1 --> Metrics
    WF2 --> Metrics
    WF3 --> Metrics
    Streams --> Metrics
    
    Metrics --> M1
    Metrics --> M2
    Metrics --> M3
    Metrics --> M4
    Metrics --> M5
    
    WF1 --> Tracing
    WF2 --> Tracing
    WF3 --> Tracing
    
    Tracing --> T1
    Tracing --> T2
    Tracing --> T3
    Tracing --> T4
    
    WF1 --> Logging
    WF2 --> Logging
    WF3 --> Logging
    
    Logging --> L1
    Logging --> L2
    Logging --> L3
    Logging --> L4
    
    Metrics --> Grafana
    Tracing --> Grafana
    Logging --> Grafana
    
    Grafana --> Alertmanager
    
    style Metrics fill:#4ecdc4
    style Tracing fill:#95e1d3
    style Logging fill:#f38181
    style Grafana fill:#ffcc00
```

---

## 对比：传统架构 vs Pekko架构

### 传统DolphinScheduler架构

```
┌─────────────────────────────────────┐
│           MasterServer              │
│  (轮询DB, 中心化调度)                 │
└───────────────┬─────────────────────┘
                │
                ▼
        ┌───────────────┐
        │   Database    │◄─── 所有状态存储
        │   (轮询中心)   │     高频查询
        └───────────────┘
                │
                ▼
┌───────────────────────────────────────┐
│        WorkerServer Cluster            │
│  (被动等待任务分配)                     │
└───────────────────────────────────────┘

问题：
❌ Master轮询DB（延迟1-5秒）
❌ DB成为瓶颈
❌ Master单点故障（恢复30-60秒）
❌ Worker被动接收任务
```

### Pekko架构

```
┌──────────┐    ┌──────────┐    ┌──────────┐
│  Node 1  │◄──►│  Node 2  │◄──►│  Node 3  │
│  Guardian│    │  Guardian│    │  Guardian│
│  ├─WF1   │    │  ├─WF2   │    │  ├─WF3   │
│  └─WF4   │    │  └─WF5   │    │  └─WF6   │
└──────────┘    └──────────┘    └──────────┘
     ▲               ▲               ▲
     └───────────────┴───────────────┘
            Cluster Sharding
           (消息驱动, 无轮询)
                  │
                  ▼
        ┌──────────────────┐
        │  Event Store     │◄─── 事件持久化
        │  (仅追加写入)     │     无频繁查询
        └──────────────────┘

优势：
✅ 消息驱动（延迟<10ms）
✅ 无中心化（无单点）
✅ 自动故障恢复（<1秒）
✅ 工作流自主执行
```

---

## 部署架构图

### Kubernetes部署

```mermaid
graph TB
    subgraph "Kubernetes Cluster"
        subgraph "Ingress Layer"
            Ingress[Ingress Controller<br/>nginx/traefik]
        end
        
        subgraph "Application Layer"
            subgraph "StatefulSet: pekko-cluster"
                Pod1[Pod 1<br/>pekko-node-0]
                Pod2[Pod 2<br/>pekko-node-1]
                Pod3[Pod 3<br/>pekko-node-2]
            end
            
            Service[Headless Service<br/>pekko-cluster]
        end
        
        subgraph "Storage Layer"
            PVC1[PVC 1<br/>Event Store]
            PVC2[PVC 2<br/>Snapshots]
        end
        
        subgraph "External Services"
            DB[(PostgreSQL<br/>Metadata)]
            Cassandra[(Cassandra<br/>Event Journal)]
            Kafka[Kafka Cluster]
        end
        
        subgraph "Monitoring"
            Prometheus[Prometheus]
            Grafana[Grafana]
        end
    end
    
    Ingress --> Service
    Service --> Pod1
    Service --> Pod2
    Service --> Pod3
    
    Pod1 --> PVC1
    Pod2 --> PVC1
    Pod3 --> PVC1
    
    Pod1 --> PVC2
    Pod2 --> PVC2
    Pod3 --> PVC2
    
    Pod1 --> DB
    Pod2 --> DB
    Pod3 --> DB
    
    Pod1 --> Cassandra
    Pod2 --> Cassandra
    Pod3 --> Cassandra
    
    Pod1 --> Kafka
    Pod2 --> Kafka
    Pod3 --> Kafka
    
    Pod1 --> Prometheus
    Pod2 --> Prometheus
    Pod3 --> Prometheus
    
    Prometheus --> Grafana
```

### 配置示例

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: pekko-cluster
spec:
  serviceName: pekko-cluster
  replicas: 3
  selector:
    matchLabels:
      app: pekko-scheduler
  template:
    metadata:
      labels:
        app: pekko-scheduler
    spec:
      containers:
      - name: pekko
        image: pekko-scheduler:latest
        ports:
        - containerPort: 2551
          name: pekko-remote
        - containerPort: 8080
          name: http
        env:
        - name: PEKKO_CLUSTER_BOOTSTRAP_SERVICE_NAME
          value: "pekko-cluster"
        resources:
          requests:
            memory: "2Gi"
            cpu: "1000m"
          limits:
            memory: "4Gi"
            cpu: "2000m"
```

---

## 总结

本文档提供了完整的系统架构视图，涵盖：

1. **系统全景**：端到端的组件关系
2. **Actor层级**：清晰的监督树结构
3. **时序交互**：消息流转过程
4. **集群分片**：分布式负载均衡
5. **节点注册**：插件化扩展机制
6. **调度架构**：多种触发方式
7. **事件驱动**：未来架构演进
8. **持久化**：事件溯源设计
9. **可观测性**：监控告警体系
10. **部署方案**：Kubernetes生产环境

这些架构图展示了基于Pekko构建的任务调度平台如何通过**Actor模型**、**集群化**、**事件驱动**实现下一代调度系统的愿景。

---

**返回主文档**: [01_strategy_pekko_vs_dolphinscheduler.md](./01_strategy_pekko_vs_dolphinscheduler.md)
