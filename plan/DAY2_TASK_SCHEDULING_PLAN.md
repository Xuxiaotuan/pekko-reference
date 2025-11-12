# 🚀 Day 2 实施计划：任务调度架构

## 📋 今日目标

设计并实现任务调度的核心架构，为Pekko DataFusion Arrow分布式数据处理平台提供强大的任务分解、分发和执行能力。

---

## 🎯 核心任务概览

- **2.1 任务模型定义** - 设计任务数据结构和类型系统
- **2.2 任务分解器设计** - 实现智能任务分解算法  
- **2.3 负载均衡器实现** - 构建高效的节点选择机制
- **2.4 任务队列管理** - 实现优先级队列和状态监控

---

## 📅 详细实施计划

### 🗓️ **阶段1: 任务模型定义 (2.1)**
**预计时间**: 1.5小时 | **优先级**: 🔴 高

#### 🎯 目标
建立完整的任务类型系统，支持SQL查询、数据处理、存储操作等多种任务类型。

#### 📝 具体任务

**1.1.1 创建基础任务模型**
```scala
// 文件: pekko-server/src/main/scala/cn/xuyinyin/magic/task/TaskModels.scala
package cn.xuyinyin.magic.task

object TaskModels {
  // 基础任务接口
  sealed trait Task {
    def id: String
    def taskType: String
    def priority: Int
    def dependencies: List[String]
    def createdAt: Long
    def estimatedDuration: Option[Long]
  }
  
  // 任务状态
  sealed trait TaskStatus
  case object Pending extends TaskStatus
  case object Running extends TaskStatus  
  case object Completed extends TaskStatus
  case object Failed extends TaskStatus
  case object Cancelled extends TaskStatus
}
```

**1.1.2 实现SQL任务类型**
```scala
final case class SqlTask(
  id: String,
  sql: String, 
  database: String,
  priority: Int = 0,
  dependencies: List[String] = Nil,
  createdAt: Long = System.currentTimeMillis(),
  estimatedDuration: Option[Long] = None
) extends Task {
  def taskType: String = "SQL"
}

final case class DataFusionTask(
  id: String,
  query: String,
  inputFormat: String = "arrow",
  outputFormat: String = "arrow", 
  priority: Int = 0,
  dependencies: List[String] = Nil,
  createdAt: Long = System.currentTimeMillis(),
  estimatedDuration: Option[Long] = None
) extends Task {
  def taskType: String = "DATAFUSION"
}
```

**1.1.3 添加任务工厂和验证**
```scala
object TaskFactory {
  def createSqlTask(id: String, sql: String, database: String): SqlTask = {
    // SQL验证逻辑
    SqlTask(id, sql.trim, database)
  }
  
  def createDataFusionTask(id: String, query: String): DataFusionTask = {
    // 查询验证逻辑
    DataFusionTask(id, query.trim)
  }
}

object TaskValidator {
  def validateTask(task: Task): Either[String, Unit] = {
    // 任务验证逻辑
    if (task.id.isEmpty) Left("Task ID cannot be empty")
    else if (task.priority < 0 || task.priority > 10) Left("Priority must be between 0-10")
    else Right(())
  }
}
```

#### ✅ 验收标准
- [ ] 所有任务类型都能正确序列化/反序列化
- [ ] 任务工厂能创建有效的任务实例
- [ ] 任务验证器能捕获无效输入
- [ ] 单元测试覆盖率达到90%+

---

### 🗓️ **阶段2: 任务分解器设计 (2.2)**
**预计时间**: 2小时 | **优先级**: 🔴 高

#### 🎯 目标
实现智能任务分解算法，将复杂任务分解为可并行执行的子任务。

#### 📝 具体任务

**2.2.1 创建分解器接口**
```scala
// 文件: pekko-server/src/main/scala/cn/xuyinyin/magic/task/TaskDecomposer.scala
package cn.xuyinyin.magic.task

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

object TaskDecomposer {
  // 命令接口
  sealed trait Command
  final case class DecomposeTask(task: Task, replyTo: ActorRef[DecompositionResult]) extends Command
  final case class GetDecompositionHistory(taskId: String, replyTo: ActorRef[List[DecompositionResult]]) extends Command
  
  // 响应数据结构
  final case class DecompositionResult(
    originalTaskId: String,
    subTasks: List[Task],
    executionPlan: ExecutionPlan,
    estimatedTotalDuration: Long,
    decompositionTime: Long = System.currentTimeMillis()
  )
  
  // 执行计划
  final case class ExecutionPlan(
    steps: List[ExecutionStep],
    parallelGroups: List[List[String]]
  )
  
  final case class ExecutionStep(
    taskId: String,
    stepOrder: Int,
    canRunInParallel: Boolean,
    requiredResources: List[String]
  )
}
```

**2.2.2 实现分解算法**
```scala
def apply(): Behavior[Command] = Behaviors.receive { (ctx, msg) =>
  msg match {
    case DecomposeTask(task, replyTo) =>
      ctx.log.info(s"Decomposing task: ${task.id} of type: ${task.taskType}")
      
      val result = task match {
        case sqlTask: SqlTask => decomposeSqlTask(sqlTask)
        case dfTask: DataFusionTask => decomposeDataFusionTask(dfTask)
        case _ => DecompositionResult(task.id, List(task), ExecutionPlan(Nil, Nil), 0)
      }
      
      replyTo ! result
      Behaviors.same
      
    case GetDecompositionHistory(taskId, replyTo) =>
      // 从存储中获取历史记录
      replyTo ! List.empty
      Behaviors.same
  }
}

private def decomposeSqlTask(task: SqlTask): DecompositionResult = {
  // SQL解析和分解逻辑
  val subTasks = analyzeSqlComplexity(task.sql) match {
    case SimpleSelect => List(task)
    case ComplexJoin => breakDownJoin(task)
    case AggregationQuery => breakDownAggregation(task)
  }
  
  val plan = createExecutionPlan(subTasks)
  DecompositionResult(task.id, subTasks, plan, estimateDuration(subTasks))
}
```

**2.2.3 添加SQL分析器**
```scala
object SqlAnalyzer {
  sealed trait SqlComplexity
  case object SimpleSelect extends SqlComplexity
  case object ComplexJoin extends SqlComplexity  
  case object AggregationQuery extends SqlComplexity
  case object Unknown extends SqlComplexity
  
  def analyze(sql: String): SqlComplexity = {
    val normalizedSql = sql.toLowerCase.trim
    
    if (normalizedSql.contains("join") && normalizedSql.count(_ == ' ') > 10) {
      ComplexJoin
    } else if (normalizedSql.contains("group by") || normalizedSql.contains("sum(") || normalizedSql.contains("count(")) {
      AggregationQuery
    } else if (normalizedSql.startsWith("select")) {
      SimpleSelect
    } else {
      Unknown
    }
  }
}
```

#### ✅ 验收标准
- [ ] SQL任务能正确识别复杂度
- [ ] 复杂查询能分解为可并行的子任务
- [ ] 执行计划图正确构建
- [ ] 分解结果可序列化传输

---

### 🗓️ **阶段3: 负载均衡器实现 (2.3)**
**预计时间**: 2小时 | **优先级**: 🟡 中

#### 🎯 目标
实现智能负载均衡，根据节点负载、任务类型和网络状况选择最优执行节点。

#### 📝 具体任务

**3.3.1 创建负载均衡器**
```scala
// 文件: pekko-server/src/main/scala/cn/xuyinyin/magic/task/LoadBalancer.scala
package cn.xuyinyin.magic.task

object LoadBalancer {
  sealed trait Command
  final case class SelectWorker(task: Task, replyTo: ActorRef[WorkerSelection]) extends Command
  final case class UpdateWorkerLoad(workerId: String, load: Double) extends Command
  final case class RegisterWorker(worker: ActorRef[WorkerActor], capabilities: Set[String]) extends Command
  final case class GetClusterLoad(replyTo: ActorRef[ClusterLoadStatus]) extends Command
  
  // 响应数据结构
  final case class WorkerSelection(
    worker: Option[ActorRef[WorkerActor]], 
    selectionReason: String,
    estimatedWaitTime: Long
  )
  
  final case class WorkerNode(
    ref: ActorRef[WorkerActor],
    id: String,
    currentLoad: Double,
    capabilities: Set[String],
    lastHeartbeat: Long,
    taskHistory: List[String]
  )
  
  final case class ClusterLoadStatus(
    totalWorkers: Int,
    averageLoad: Double,
    availableWorkers: Int,
    overloadedWorkers: Int
  )
}
```

**3.3.2 实现选择策略**
```scala
def apply(): Behavior[Command] = Behaviors.setup { ctx =>
  ctx.log.info("LoadBalancer starting up")
  
  var workers = Map[String, WorkerNode]()
  
  Behaviors.receiveMessage {
    case SelectWorker(task, replyTo) =>
      val selected = selectBestWorker(task, workers.values.toList)
      replyTo ! selected
      Behaviors.same
      
    case RegisterWorker(worker, capabilities) =>
      val workerId = worker.path.name
      val node = WorkerNode(worker, workerId, 0.0, capabilities, System.currentTimeMillis(), Nil)
      workers += workerId -> node
      ctx.log.info(s"Registered worker: $workerId with capabilities: $capabilities")
      Behaviors.same
      
    case UpdateWorkerLoad(workerId, load) =>
      workers.get(workerId).foreach { worker =>
        val updated = worker.copy(currentLoad = load, lastHeartbeat = System.currentTimeMillis())
        workers += workerId -> updated
      }
      Behaviors.same
  }
}

private def selectBestWorker(task: Task, workers: List[WorkerNode]): WorkerSelection = {
  val availableWorkers = workers.filter(_.currentLoad < 0.8) // 负载小于80%
  
  if (availableWorkers.isEmpty) {
    WorkerSelection(None, "No available workers", Long.MaxValue)
  } else {
    // 根据负载和任务匹配度选择
    val best = availableWorkers.minBy { worker =>
      val loadScore = worker.currentLoad
      val capabilityScore = if (worker.capabilities.contains(task.taskType)) 0 else 1
      loadScore + capabilityScore
    }
    
    val waitTime = (best.currentLoad * 1000).toLong // 简单估算
    WorkerSelection(Some(best.ref), s"Best match with load ${best.currentLoad}", waitTime)
  }
}
```

#### ✅ 验收标准
- [ ] 能根据负载选择最优节点
- [ ] 支持任务类型匹配
- [ ] 节点心跳监控正常
- [ ] 负载信息实时更新

---

### 🗓️ **阶段4: 任务队列管理 (2.4)**
**预计时间**: 1.5小时 | **优先级**: 🟡 中

#### 🎯 目标
实现高效的优先级任务队列，支持任务入队、出队和状态监控。

#### 📝 具体任务

**4.4.1 创建任务队列**
```scala
// 文件: pekko-server/src/main/scala/cn/xuyinyin/magic/task/TaskQueue.scala
package cn.xuyinyin.magic.task

import scala.collection.mutable

object TaskQueue {
  sealed trait Command
  final case class EnqueueTask(task: Task, replyTo: ActorRef[EnqueueResult]) extends Command
  final case class DequeueTask(workerId: String, replyTo: ActorRef[Option[Task]]) extends Command
  final case class GetQueueStatus(replyTo: ActorRef[QueueStatus]) extends Command
  final case class GetTasksByStatus(status: TaskStatus, replyTo: ActorRef[List[Task]]) extends Command
  final case class CancelTask(taskId: String, replyTo: ActorRef[Boolean]) extends Command
  
  // 响应数据结构
  final case class EnqueueResult(success: Boolean, queuePosition: Option[Int])
  final case class QueueStatus(
    pendingTasks: Int,
    runningTasks: Int,
    completedTasks: Int,
    failedTasks: Int,
    averageWaitTime: Long
  )
}
```

**4.4.2 实现优先级队列**
```scala
def apply(): Behavior[Command] = Behaviors.setup { ctx =>
  ctx.log.info("TaskQueue starting up")
  
  // 按优先级排序的队列
  val pendingQueue = mutable.PriorityQueue.empty[Task](Ordering.by[Task, Int](-_.priority))
  val runningTasks = mutable.Map[String, Task]()
  val completedTasks = mutable.Map[String, Task]()
  val failedTasks = mutable.Map[String, Task]()
  
  Behaviors.receiveMessage {
    case EnqueueTask(task, replyTo) =>
      TaskValidator.validateTask(task) match {
        case Right(_) =>
          pendingQueue.enqueue(task)
          val position = pendingQueue.indexOf(task)
          replyTo ! EnqueueResult(true, Some(position))
          ctx.log.info(s"Task ${task.id} enqueued at position $position")
          
        case Left(error) =>
          replyTo ! EnqueueResult(false, None)
          ctx.log.warn(s"Task validation failed: $error")
      }
      Behaviors.same
      
    case DequeueTask(workerId, replyTo) =>
      if (pendingQueue.nonEmpty) {
        val task = pendingQueue.dequeue()
        runningTasks += task.id -> task
        replyTo ! Some(task)
        ctx.log.info(s"Task ${task.id} dequeued by worker $workerId")
      } else {
        replyTo ! None
      }
      Behaviors.same
      
    case GetQueueStatus(replyTo) =>
      val status = QueueStatus(
        pendingTasks = pendingQueue.size,
        runningTasks = runningTasks.size,
        completedTasks = completedTasks.size,
        failedTasks = failedTasks.size,
        averageWaitTime = calculateAverageWaitTime()
      )
      replyTo ! status
      Behaviors.same
  }
}
```

#### ✅ 验收标准
- [ ] 任务按优先级正确排序
- [ ] 队列状态统计准确
- [ ] 支持任务取消操作
- [ ] 并发访问线程安全

---

### 🗓️ **阶段5: 测试验证**
**预计时间**: 1小时 | **优先级**: 🔴 高

#### 🎯 目标
创建完整的测试套件，验证Day 2所有功能的正确性。

#### 📝 具体任务

**5.5.1 创建Day 2测试**
```scala
// 文件: pekko-server/src/test/scala/cn/xuyinyin/magic/test/week1/Day2TaskSchedulingTest.scala
package cn.xuyinyin.magic.test.week1

object Day2TaskSchedulingTest {
  def main(args: Array[String]): Unit = {
    var allTestsPassed = true
    
    allTestsPassed &= testTaskModels()
    allTestsPassed &= testTaskDecomposer()
    allTestsPassed &= testLoadBalancer()
    allTestsPassed &= testTaskQueue()
    
    if (allTestsPassed) {
      logger.info("✅ All Day 2 Task Scheduling tests passed!")
      System.exit(0)
    } else {
      logger.error("❌ Some Day 2 tests failed!")
      System.exit(1)
    }
  }
  
  def testTaskModels(): Boolean = {
    // 测试任务模型
  }
  
  def testTaskDecomposer(): Boolean = {
    // 测试任务分解器
  }
  
  def testLoadBalancer(): Boolean = {
    // 测试负载均衡器
  }
  
  def testTaskQueue(): Boolean = {
    // 测试任务队列
  }
}
```

#### ✅ 验收标准
- [ ] 所有组件单元测试通过
- [ ] 集成测试验证数据流正确
- [ ] 性能测试满足要求
- [ ] 错误处理测试覆盖完整

---

## 🎯 总体验收标准

### 功能验收
- [ ] 任务能正确创建、验证和序列化
- [ ] 复杂任务能智能分解为子任务
- [ ] 负载均衡器能选择最优执行节点
- [ ] 任务队列能按优先级正确调度
- [ ] 所有组件能协同工作

### 性能验收
- [ ] 任务分解延迟 < 100ms
- [ ] 负载均衡选择延迟 < 50ms  
- [ ] 队列入队/出队操作 < 10ms
- [ ] 支持1000+并发任务

### 质量验收
- [ ] 代码覆盖率 > 85%
- [ ] 所有公共接口有文档
- [ ] 错误处理机制完善
- [ ] 日志记录详细准确

---

## 📊 进度跟踪

| 阶段 | 状态 | 完成时间 | 备注 |
|------|------|----------|------|
| 2.1 任务模型定义 | ⏳ 待开始 | - | 基础组件，优先级最高 |
| 2.2 任务分解器设计 | ⏳ 待开始 | - | 核心算法，需要重点测试 |
| 2.3 负载均衡器实现 | ⏳ 待开始 | - | 需要与集群集成 |
| 2.4 任务队列管理 | ⏳ 待开始 | - | 性能关键组件 |
| 2.5 测试验证 | ⏳ 待开始 | - | 质量保证 |

---

## 🚀 下一步行动

1. **立即开始**: 创建TaskModels.scala，建立任务类型系统
2. **并行准备**: 设计TaskDecomposer的分解算法
3. **持续集成**: 每完成一个组件立即编写测试
4. **性能监控**: 在实现过程中持续关注性能指标

准备好开始Day 2的实施了吗？让我们从TaskModels开始！💪
