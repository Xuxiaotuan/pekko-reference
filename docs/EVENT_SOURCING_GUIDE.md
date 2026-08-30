# Event Sourcing 使用指南

> 工作流执行历史与事件溯源完整指南

---

## 📋 目录

- [概述](#概述)
- [架构设计](#架构设计)
- [快速开始](#快速开始)
- [API 使用](#api-使用)
- [前端展示](#前端展示)
- [配置说明](#配置说明)
- [高级特性](#高级特性)

---

## 概述

### 什么是 Event Sourcing？

Event Sourcing（事件溯源）是一种设计模式，将所有状态变更记录为一系列事件：

```
传统方式：
[状态] → 更新 → [新状态]  // 只保留最终状态

Event Sourcing：
[事件1] → [事件2] → [事件3] → ... → [当前状态]  // 保留完整历史
```

### 为什么需要 Event Sourcing？

**对工作流引擎的价值**：

1. **完整审计**：每个节点的执行历史都被记录
2. **可重放**：可以重放历史事件来复现问题
3. **可观测性**：实时了解每个节点的执行情况
4. **性能分析**：识别瓶颈节点
5. **调试利器**：精确定位失败原因

---

## 架构设计

### 事件模型

```scala
// 工作流事件
WorkflowStarted        // 工作流开始
WorkflowCompleted      // 工作流完成
WorkflowFailed         // 工作流失败

// 节点事件
NodeExecutionStarted   // 节点开始执行
NodeExecutionCompleted // 节点执行完成
NodeExecutionFailed    // 节点执行失败
```

### 数据流

```
WorkflowActor (Command) 
    ↓
产生事件 (Event)
    ↓
持久化到 Journal
    ↓
更新内部状态 (State)
    ↓
可查询历史 (Query)
```

---

## 快速开始

### 1. 启动项目

```bash
# 启动后端
cd pekko-server
sbt run

# 启动前端
cd xxt-ui
npm install
npm run dev
```

### 2. 创建并执行工作流

```bash
# 创建工作流
curl -X POST http://localhost:9906/api/workflows \
  -H "Content-Type: application/json" \
  -d '{
    "id": "test-workflow",
    "name": "测试工作流",
    "nodes": [...]
  }'

# 执行工作流
curl -X POST http://localhost:9906/api/workflows/test-workflow/execute
```

### 3. 查看执行历史

打开浏览器访问：
```
http://localhost:5173/history/test-workflow
```

---

## API 使用

### 获取执行历史

```bash
GET /api/history/:workflowId
```

**响应示例**：
```json
{
  "workflowId": "test-workflow",
  "executions": [
    {
      "executionId": "exec_1234567890",
      "workflowName": "测试工作流",
      "startTime": 1700000000000,
      "endTime": 1700000060000,
      "status": "completed",
      "duration": 60000,
      "nodes": [
        {
          "nodeId": "node1",
          "nodeType": "source",
          "duration": 20000,
          "status": "completed",
          "error": null
        }
      ]
    }
  ]
}
```

节点级 `startTime`、`endTime` 和 `recordsProcessed` 仅在执行器提供真实测量值时返回；当前 MVP 未采集这些字段，因此响应中省略它们，不会用工作流级时间或 `0` 代替未知值。

### 获取工作流状态

```bash
GET /api/history/:workflowId/status
```

**响应示例**：
```json
{
  "workflowId": "test-workflow",
  "state": "running",
  "currentExecution": {
    "executionId": "exec_1234567890",
    "startTime": 1700000000000,
    "endTime": null,
    "status": "running",
    "completedNodes": 2,
    "totalNodes": 5
  },
  "allExecutions": [...]
}
```

### 获取执行时间线

```bash
GET /api/history/:workflowId/timeline?executionId=exec_1234567890
```

**响应示例**：
```json
{
  "executionId": "exec_1234567890",
  "startTime": 1700000000000,
  "endTime": 1700000060000,
  "duration": 60000,
  "nodes": [
    {
      "nodeId": "node1",
      "nodeType": "source",
      "startTime": 1700000000000,
      "endTime": 1700000020000,
      "duration": 20000,
      "status": "completed",
      "recordsProcessed": 100
    }
  ]
}
```

---

## 前端展示

### 执行历史页面

页面包含以下组件：

1. **执行统计卡片**
   - 总执行次数
   - 成功/失败次数
   - 平均耗时

2. **执行记录表格**
   - 执行ID
   - 开始/结束时间
   - 耗时
   - 状态

3. **执行详情**
   - 基本信息
   - 节点时间线（Timeline）
   - 节点执行详情表格

### 使用示例

```typescript
import ExecutionHistory from './pages/ExecutionHistory';

// 在路由中添加
<Route path="/history/:workflowId" element={<ExecutionHistory />} />
```

---

## 配置说明

### Pekko Persistence 配置

```hocon
pekko {
  persistence {
    # Journal（事件存储）
    journal {
      plugin = "pekko.persistence.journal.leveldb"
      leveldb {
        dir = "target/journal"
        native = false
      }
    }
    
    # Snapshot（快照存储）
    snapshot-store {
      plugin = "pekko.persistence.snapshot-store.local"
      local {
        dir = "target/snapshots"
      }
    }
  }
  
  # 工作流事件溯源配置
  workflow {
    event-sourcing {
      snapshot-every = 100  # 每100个事件保存快照
      keep-n-snapshots = 2  # 保留最近2个快照
    }
  }
}
```

### 持久化存储选择

**开发环境**（当前）：
- **LevelDB**：本地文件存储，简单易用

**生产环境**（推荐）：
- **PostgreSQL**：关系型数据库，支持 SQL 查询
- **Cassandra**：分布式 NoSQL，高可用

---

## 高级特性

### 1. 快照优化

**问题**：事件太多导致重放缓慢

**解决方案**：定期保存快照

```scala
.withRetention(
  RetentionCriteria.snapshotEvery(
    numberOfEvents = 100,  // 每100个事件
    keepNSnapshots = 2     // 保留最近2个快照
  )
)
```

**效果**：
- 无快照：重放1000个事件 = 10秒
- 有快照：加载快照 + 重放10个事件 = 0.1秒
- **提升100倍！**

### 2. 事件重放

**场景**：调试工作流执行问题

```scala
// 重放历史事件，复现问题
def replayExecution(workflowId: String, executionId: String): Unit = {
  val events = loadEvents(workflowId, executionId)
  events.foreach { event =>
    println(s"Replaying: $event")
    applyEvent(event)
  }
}
```

### 3. 性能分析

**识别瓶颈节点**：

```scala
// 从事件中提取性能数据
val nodePerformance = events
  .collect { case e: NodeExecutionCompleted => e }
  .groupBy(_.nodeId)
  .mapValues(events => 
    events.map(_.duration).sum / events.size
  )
  .toList
  .sortBy(-_._2)  // 按平均耗时降序

// 输出：
// node3: 500ms (瓶颈！)
// node1: 200ms
// node2: 100ms
```

### 4. 事件查询

**使用 Pekko Persistence Query**：

```scala
import org.apache.pekko.persistence.query.PersistenceQuery
import org.apache.pekko.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal

val readJournal = PersistenceQuery(system)
  .readJournalFor[LeveldbReadJournal](
    LeveldbReadJournal.Identifier
  )

// 查询特定工作流的所有事件
readJournal
  .currentEventsByPersistenceId(s"workflow-$workflowId", 0, Long.MaxValue)
  .runForeach { envelope =>
    println(s"Event: ${envelope.event}")
  }
```

---

## 实战场景

### 场景1：定位失败原因

```bash
# 1. 查询执行历史
GET /api/history/my-workflow

# 2. 找到失败的执行
{
  "executionId": "exec_failed",
  "status": "failed",
  "nodes": [
    {
      "nodeId": "node3",
      "status": "failed",
      "error": "Connection timeout"  // ← 找到原因
    }
  ]
}

# 3. 修复后重试
POST /api/workflows/my-workflow/execute
```

### 场景2：性能优化

```bash
# 1. 查看执行时间线
GET /api/history/my-workflow/timeline

# 2. 分析节点耗时
node1: 100ms  ✓ 正常
node2: 100ms  ✓ 正常
node3: 5000ms ✗ 瓶颈！

# 3. 优化node3
- 添加索引
- 增加并行度
- 调整批处理大小

# 4. 对比优化前后
Before: 5000ms
After:  500ms
提升10倍！
```

### 场景3：复现Bug

```scala
// 1. 加载历史事件
val events = loadExecutionEvents("exec_buggy")

// 2. 在测试环境重放
events.foreach(event => actor ! ReplayEvent(event))

// 3. 精确定位问题
// 在第50个事件处发生异常
// → 修复代码
// → 重新测试
```

---

## 总结

### Event Sourcing 带来的价值

| 维度 | 传统方式 | Event Sourcing |
|-----|---------|---------------|
| **历史记录** | ❌ 无 | ✅ 完整 |
| **调试能力** | ❌ 困难 | ✅ 可重放 |
| **可观测性** | ❌ 黑盒 | ✅ 透明 |
| **性能分析** | ❌ 难 | ✅ 数据丰富 |
| **审计** | ❌ 不完整 | ✅ 完整审计 |

### 下一步

1. ✅ 基础事件系统 → **已完成**
2. ✅ HTTP API → **已完成**
3. ✅ 前端展示 → **已完成**
4. ⬜ 集成到现有系统
5. ⬜ 添加更多事件类型
6. ⬜ 实现事件重放功能
7. ⬜ 切换到生产级存储（PostgreSQL/Cassandra）

---

## 参考资料

- [Pekko Persistence 文档](https://pekko.apache.org/docs/pekko/current/typed/persistence.html)
- [Event Sourcing 模式](https://martinfowler.com/eaaDev/EventSourcing.html)
- [深度分析系列 - 第16篇：CQRS与Event Sourcing](../docs/deep-dive/16_cqrs_event_sourcing.md)

---

**项目地址**: https://github.com/Xuxiaotuan/pekko-reference

**作者**: Xuxiaotuan  
**日期**: 2024年11月16日
