# Event Sourcing：让系统永不丢失状态

> **系列文章：构建下一代任务调度平台**  
> 第五篇：持久化篇 - Event Sourcing与Pekko Persistence实践

---

## 📋 目录

- [引言](#引言)
- [Event Sourcing原理](#event-sourcing原理)
- [Pekko Persistence基础](#pekko-persistence基础)
- [WorkflowActor持久化改造](#workflowactor持久化改造)
- [快照机制](#快照机制)
- [CQRS模式](#cqrs模式)
- [生产环境配置](#生产环境配置)
- [最佳实践](#最佳实践)

---

## 引言

在前几篇文章中，我们构建了分布式的任务调度系统。但有一个关键问题：**如果节点崩溃，工作流的执行状态会丢失！**

本文将介绍**Event Sourcing（事件溯源）**和**Pekko Persistence**，让系统具备：

- 🔄 **状态永不丢失**：所有状态变更都持久化
- 📜 **完整历史**：可以回溯到任意时间点
- 🔍 **审计友好**：天然支持审计需求
- 🚀 **快速恢复**：节点重启后自动恢复

### 传统存储 vs Event Sourcing

```
传统存储：
只保存最新状态 → 历史丢失

Event Sourcing：
保存所有事件 → 完整历史 → 可重放
```

---

## Event Sourcing原理

### 核心思想

**不存储状态，而是存储状态变更的事件**：

```scala
// Event Sourcing
事件1: WorkflowCreated
事件2: WorkflowStarted
事件3: TaskCompleted(task-1)
事件4: TaskCompleted(task-2)
事件5: WorkflowCompleted

当前状态 = 重放所有事件
```

### 优势

**1. 完整历史**
```scala
// 查询任意时间点的状态
def getStateAt(workflowId: String, timestamp: Instant): State = {
  events.takeWhile(_.timestamp <= timestamp)
    .foldLeft(State.empty)(applyEvent)
}
```

**2. 天然审计**
```scala
// 所有操作都有记录
events.foreach { event =>
  println(s"${event.timestamp}: ${event.user} did ${event.action}")
}
```

**3. 时间旅行调试**
```scala
// 重放事件找出Bug何时引入
events.foldLeft(State.empty) { (state, event) =>
  val newState = applyEvent(state, event)
  if (newState.isBroken) println(s"Bug by: $event")
  newState
}
```

**4. CQRS支持**
```
Command → Event → Journal
               ↓
          Projection → Read Model
```

---

## Pekko Persistence基础

### 依赖配置

```scala
// build.sbt
libraryDependencies ++= Seq(
  "org.apache.pekko" %% "pekko-persistence-typed" % "1.0.2",
  "org.apache.pekko" %% "pekko-persistence-cassandra" % "1.0.6",
  "org.apache.pekko" %% "pekko-serialization-jackson" % "1.0.2"
)
```

### EventSourcedBehavior

```scala
import org.apache.pekko.persistence.typed.scaladsl.EventSourcedBehavior

object CounterActor {
  // 命令
  sealed trait Command
  case object Increment extends Command
  
  // 事件
  sealed trait Event
  case object Incremented extends Event
  
  // 状态
  case class State(value: Int)
  
  def apply(id: String): Behavior[Command] = {
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId(id),
      emptyState = State(0),
      commandHandler = (state, cmd) => cmd match {
        case Increment => Effect.persist(Incremented)
      },
      eventHandler = (state, evt) => evt match {
        case Incremented => state.copy(value = state.value + 1)
      }
    )
  }
}
```

**核心流程**：
1. Command → commandHandler → Effect.persist(Event)
2. Event → 持久化到Journal
3. Event → eventHandler → 新State

---

## WorkflowActor持久化改造

### 定义事件

```scala
sealed trait Event extends CborSerializable {
  def workflowId: String
}

case class ExecutionStarted(
  workflowId: String,
  executionId: String
) extends Event

case class TaskCompleted(
  workflowId: String,
  executionId: String,
  taskId: String,
  success: Boolean
) extends Event

case class ExecutionCompleted(
  workflowId: String,
  executionId: String,
  success: Boolean,
  duration: Duration
) extends Event

case class WorkflowPaused(
  workflowId: String,
  executionId: String
) extends Event
```

### 定义状态

```scala
case class State(
  workflow: Option[WorkflowDSL.Workflow],
  status: WorkflowStatus,
  currentExecutionId: Option[String],
  completedTasks: Set[String],
  executionCount: Int,
  logs: List[String]
)

object State {
  val empty: State = State(
    workflow = None,
    status = WorkflowStatus.Idle,
    currentExecutionId = None,
    completedTasks = Set.empty,
    executionCount = 0,
    logs = List.empty
  )
}
```

### 命令处理器

```scala
private def commandHandler(
  state: State,
  command: Command
): Effect[Event, State] = command match {
  
  case Execute(replyTo) if state.status == WorkflowStatus.Idle =>
    val executionId = s"exec_${System.currentTimeMillis()}"
    replyTo ! ExecutionResponse(executionId, "started")
    
    Effect
      .persist(ExecutionStarted(workflow.id, executionId))
      .thenRun(_ => startExecution(executionId))
  
  case Execute(replyTo) =>
    replyTo ! ExecutionResponse("", s"Workflow is ${state.status}")
    Effect.none
  
  case Pause if state.status == WorkflowStatus.Running =>
    Effect.persist(WorkflowPaused(workflow.id, state.currentExecutionId.get))
  
  case GetStatus(replyTo) =>
    replyTo ! StatusResponse(workflow.id, state.status, state.logs)
    Effect.none
}
```

### 事件处理器

```scala
private def eventHandler(state: State, event: Event): State = event match {
  
  case ExecutionStarted(_, executionId) =>
    state.copy(
      status = WorkflowStatus.Running,
      currentExecutionId = Some(executionId),
      completedTasks = Set.empty,
      logs = state.logs :+ s"Started: $executionId"
    )
  
  case TaskCompleted(_, _, taskId, success, _) =>
    state.copy(
      completedTasks = state.completedTasks + taskId,
      logs = state.logs :+ s"Task $taskId: ${if(success) "✓" else "✗"}"
    )
  
  case ExecutionCompleted(_, executionId, success, duration) =>
    state.copy(
      status = if (success) WorkflowStatus.Completed else WorkflowStatus.Failed,
      currentExecutionId = None,
      executionCount = state.executionCount + 1,
      logs = state.logs :+ s"Completed in ${duration.toMillis}ms"
    )
  
  case WorkflowPaused(_, _) =>
    state.copy(
      status = WorkflowStatus.Paused,
      logs = state.logs :+ "Paused"
    )
}
```

---

## 快照机制

### 为什么需要快照？

```
1000次执行 = 10000个事件
       ↓
恢复需要重放10000个事件 → 慢！

使用快照：
快照(900次) + 100个新事件 → 快！
```

### 配置快照

```scala
EventSourcedBehavior(/*...*/)
  .withRetention(
    RetentionCriteria
      .snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3)
      .withDeleteEventsOnSnapshot
  )
```

### 快照信号

```scala
EventSourcedBehavior(/*...*/)
  .receiveSignal {
    case (state, SnapshotCompleted(metadata)) =>
      context.log.info(s"Snapshot saved: seqNr=${metadata.sequenceNr}")
    
    case (state, RecoveryCompleted) =>
      context.log.info(s"Recovery completed: status=${state.status}")
  }
```

---

## CQRS模式

### 架构

```
写入侧: Command → EventSourcedActor → Events
                                        ↓
读取侧: Events → Projection → Read Model → Query
```

### Projection

```scala
class WorkflowProjectionHandler(
  repository: WorkflowReadModelRepository
) extends Handler[EventEnvelope[Event]] {
  
  override def process(envelope: EventEnvelope[Event]): Future[Done] = {
    envelope.event match {
      case ExecutionStarted(workflowId, executionId) =>
        repository.updateStatus(workflowId, "RUNNING", executionId)
      
      case ExecutionCompleted(workflowId, execId, success, duration) =>
        repository.recordExecution(
          WorkflowExecution(workflowId, execId, success, duration)
        )
    }
  }
}
```

### Read Model

```sql
-- 优化查询的表
CREATE TABLE workflow_summary (
  workflow_id VARCHAR(255) PRIMARY KEY,
  status VARCHAR(50),
  total_executions INT,
  success_count INT,
  avg_duration_ms BIGINT,
  last_execution_time TIMESTAMP
);
```

### 查询服务

```scala
class WorkflowQueryService(repository: Repository) {
  
  def getStats(workflowId: String): Future[WorkflowStats] =
    repository.findSummary(workflowId)
  
  def getHistory(workflowId: String, limit: Int): Future[List[Execution]] =
    repository.findExecutions(workflowId, limit)
  
  def getDashboard(): Future[DashboardMetrics] =
    for {
      total <- repository.countWorkflows()
      running <- repository.countByStatus("RUNNING")
      successRate <- repository.calculateSuccessRate()
    } yield DashboardMetrics(total, running, successRate)
}
```

**优势**：
- ✅ 写入和读取独立优化
- ✅ 复杂查询不影响写入
- ✅ 可以有多个Read Model

---

## 生产环境配置

### Cassandra配置

```hocon
pekko.persistence.cassandra {
  journal {
    keyspace = "pekko_journal"
    table = "messages"
    replication-factor = 3
    write-consistency = "QUORUM"
  }
  
  snapshot {
    keyspace = "pekko_snapshot"
    table = "snapshots"
    replication-factor = 3
    compression-algorithm = "lz4"
  }
}
```

### 序列化配置

```hocon
pekko.actor {
  serializers {
    jackson-cbor = "org.apache.pekko.serialization.jackson.JacksonCborSerializer"
  }
  serialization-bindings {
    "cn.xuyinyin.magic.CborSerializable" = jackson-cbor
  }
}
```

```scala
// 标记序列化
trait CborSerializable
case class ExecutionStarted(/*...*/) extends Event with CborSerializable
```

---

## 最佳实践

### 1. 事件设计原则

```scala
// ✅ 好的事件
case class UserRegistered(userId: String, email: String) extends Event

// ❌ 坏的事件
case class UserUpdated(user: User) extends Event  // 包含整个对象
```

**原则**：
- ✅ 事件是过去式（Completed，不是Complete）
- ✅ 只包含必要字段
- ✅ 不可变
- ✅ 向后兼容

### 2. 命令验证

```scala
case Execute(replyTo) =>
  if (state.status != WorkflowStatus.Idle) {
    replyTo ! ExecutionResponse("", "Busy")
    Effect.none  // 拒绝
  } else {
    Effect.persist(ExecutionStarted(/*...*/))
  }
```

### 3. 幂等性

```scala
case ExecutionCompleted(execId, _) 
  if state.currentExecutionId.contains(execId) =>
  Effect.persist(/*...*/)  // 当前执行

case ExecutionCompleted(execId, _) =>
  Effect.none  // 过期执行，忽略
```

### 4. 监控

```scala
// 监控事件数量
EventSourcedBehavior(/*...*/)
  .receiveSignal {
    case (state, RecoveryCompleted) =>
      metrics.recordRecoveryTime(/*...*/)
      metrics.recordEventCount(state.sequenceNr)
  }
```

---

## 总结

### 核心要点

1. **Event Sourcing优势**
   - 完整历史追溯
   - 天然审计日志
   - 时间旅行调试
   - CQRS支持

2. **Pekko Persistence**
   - EventSourcedBehavior
   - Command → Event → State
   - 快照机制
   - 恢复策略

3. **CQRS模式**
   - 读写分离
   - Projection
   - 优化Read Model

4. **生产配置**
   - Cassandra/PostgreSQL
   - 序列化
   - 快照策略
   - 监控告警

### 性能数据

| 指标 | 无持久化 | 有持久化 | 快照优化 |
|-----|---------|---------|---------|
| 写入延迟 | 0ms | 5-10ms | 5-10ms |
| 恢复时间 | 0s | 10s(1000事件) | <1s |
| 数据安全 | ❌ | ✅ | ✅ |
| 审计能力 | ❌ | ✅ | ✅ |

### 下一步

- **第六篇：性能篇** - 压测与调优
- **第七篇：生产篇** - 监控运维

---

**项目地址**: https://github.com/Xuxiaotuan/pekko-reference

**作者**: Xuxiaotuan  
**日期**: 2024年11月

---

*下一篇：《性能篇：系统调优与压力测试》*
