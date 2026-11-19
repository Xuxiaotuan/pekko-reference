# 监督策略深度解析：Let it Crash哲学

> **深度分析系列** - 第七篇：从源码看Actor的容错机制

---

## 📋 目录

- [引言](#引言)
- [监督树架构](#监督树架构)
- [四种监督决策](#四种监督决策)
- [监督策略类型](#监督策略类型)
- [BackoffSupervisor](#backoffsupervisor)
- [失败检测与隔离](#失败检测与隔离)
- [监督树设计原则](#监督树设计原则)
- [源码实现分析](#源码实现分析)
- [实战案例](#实战案例)
- [总结](#总结)

---

## 引言

Actor的容错哲学：**Let it crash**

```
传统方式：
try {
  doSomething()
} catch {
  case e: Exception => 
    handleException(e)  // 防御式编程
}

Actor方式：
def receive = {
  case msg => doSomething()  // 让它崩溃！
}
// 父Actor自动处理子Actor崩溃

为什么？
1. 错误处理代码复杂、易出错
2. 监督者更了解如何恢复
3. 隔离失败，不影响其他Actor
```

本文将深入监督策略的实现原理。

---

## 监督树架构

### 层级结构

```
ActorSystem
    │
    └─ Guardian ("/")
           │
           ├─ UserGuardian ("/user")
           │      │
           │      ├─ ServiceActor
           │      │      │
           │      │      ├─ DatabaseActor (worker)
           │      │      └─ CacheActor (worker)
           │      │
           │      └─ APIActor
           │             │
           │             ├─ RequestHandler1
           │             └─ RequestHandler2
           │
           └─ SystemGuardian ("/system")

监督关系：
- Guardian监督UserGuardian和SystemGuardian
- UserGuardian监督ServiceActor和APIActor
- ServiceActor监督DatabaseActor和CacheActor
- 每层父Actor是子Actor的监督者
```

### 监督树的责任

```scala
// 父Actor的职责
trait Supervisor {
  // 1. 创建子Actor
  def createChild(): ActorRef[ChildCommand]
  
  // 2. 监控子Actor生命周期
  def watch(child: ActorRef[_]): Unit
  
  // 3. 处理子Actor失败
  def handleChildFailure(child: ActorRef[_], cause: Throwable): Unit
  
  // 4. 决定恢复策略
  def supervisionStrategy(): SupervisorStrategy
}
```

### 失败传播

```
工作流：
Worker Actor发生异常
    ↓
异常被捕获
    ↓
通知Supervisor Actor
    ↓
Supervisor根据策略决定
    ↓
执行：Restart/Resume/Stop/Escalate
```

---

## 四种监督决策

### 1. Restart（重启）

**语义**：丢弃当前Actor实例，创建新实例

```scala
// 使用场景：Actor状态损坏，需要重新初始化
object DatabaseActor {
  
  sealed trait Command
  case class Query(sql: String, replyTo: ActorRef[Result]) extends Command
  
  def apply(): Behavior[Command] = {
    Behaviors.setup { ctx =>
      // 初始化连接
      val connection = createConnection()
      
      Behaviors.supervise(
        active(connection)
      ).onFailure[SQLException](
        SupervisorStrategy.restart  // SQL异常：重启
          .withLimit(maxNrOfRetries = 3, withinTimeRange = 1.minute)
      )
    }
  }
  
  private def active(connection: Connection): Behavior[Command] = {
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case Query(sql, replyTo) =>
          // 如果connection.execute(sql)抛出SQLException
          // Actor会被重启，重新创建connection
          val result = connection.execute(sql)
          replyTo ! result
          Behaviors.same
      }
    }
  }
}
```

**Restart流程**：
```
1. 捕获异常
2. 调用preRestart钩子
3. 停止所有子Actor
4. 调用postStop钩子
5. 创建新Actor实例
6. 调用preStart钩子
7. 重新处理导致失败的消息（可选）
```

### 2. Resume（恢复）

**语义**：忽略异常，继续处理下一条消息

```scala
// 使用场景：临时错误，Actor状态仍然有效
object CacheActor {
  
  sealed trait Command
  case class Get(key: String, replyTo: ActorRef[Option[String]]) extends Command
  case class Put(key: String, value: String) extends Command
  
  def apply(): Behavior[Command] = {
    Behaviors.supervise(
      cache(Map.empty)
    ).onFailure[IllegalArgumentException](
      SupervisorStrategy.resume  // 参数错误：忽略并继续
    )
  }
  
  private def cache(data: Map[String, String]): Behavior[Command] = {
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case Get(key, replyTo) =>
          if (key.isEmpty) {
            throw new IllegalArgumentException("Key cannot be empty")
            // Resume：忽略此消息，继续处理下一条
          }
          replyTo ! data.get(key)
          Behaviors.same
        
        case Put(key, value) =>
          cache(data + (key -> value))
      }
    }
  }
}
```

**Resume流程**：
```
1. 捕获异常
2. 记录日志
3. 继续处理下一条消息
4. Actor状态不变
```

### 3. Stop（停止）

**语义**：停止Actor，不再处理消息

```scala
// 使用场景：不可恢复的错误
object PaymentActor {
  
  sealed trait Command
  case class ProcessPayment(amount: BigDecimal) extends Command
  
  def apply(): Behavior[Command] = {
    Behaviors.supervise(
      processing()
    ).onFailure[FatalException](
      SupervisorStrategy.stop  // 致命错误：停止Actor
    )
  }
  
  private def processing(): Behavior[Command] = {
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case ProcessPayment(amount) =>
          if (amount < 0) {
            throw new FatalException("Negative amount")
            // Stop：Actor被停止
          }
          processPayment(amount)
          Behaviors.same
      }
    }
  }
}
```

**Stop流程**：
```
1. 捕获异常
2. 停止所有子Actor
3. 调用postStop钩子
4. 从父Actor的children中移除
5. 发送Terminated信号给watchers
```

### 4. Escalate（上报）

**语义**：将失败上报给父Actor的父Actor

```scala
// 使用场景：无法在当前层级处理的错误
object WorkerActor {
  
  sealed trait Command
  case class DoWork(task: Task) extends Command
  
  def apply(): Behavior[Command] = {
    Behaviors.supervise(
      working()
    ).onFailure[SystemException](
      SupervisorStrategy.stop  // 系统异常：停止
    ).onFailure[UnknownException](
      SupervisorStrategy.escalate  // 未知异常：上报给父Actor
    )
  }
  
  private def working(): Behavior[Command] = {
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case DoWork(task) =>
          executeTask(task)  // 可能抛出UnknownException
          Behaviors.same
      }
    }
  }
}
```

**Escalate流程**：
```
1. 捕获异常
2. 停止当前Actor
3. 将异常传递给父Actor
4. 父Actor决定如何处理
```

---

## 监督策略类型

### OneForOne策略

**定义**：只影响失败的子Actor

```scala
object Supervisor {
  
  def apply(): Behavior[Command] = {
    Behaviors.setup { ctx =>
      // 创建多个子Actor
      val worker1 = ctx.spawn(WorkerActor(), "worker1")
      val worker2 = ctx.spawn(WorkerActor(), "worker2")
      val worker3 = ctx.spawn(WorkerActor(), "worker3")
      
      // OneForOne：只重启失败的worker
      supervising(List(worker1, worker2, worker3))
    }
  }
  
  private def supervising(workers: List[ActorRef[WorkerCommand]]): Behavior[Command] = {
    Behaviors.receive { (ctx, msg) =>
      // worker1失败 → 只重启worker1
      // worker2、worker3继续运行
      Behaviors.same
    }
  }
}
```

**适用场景**：
- ✅ 子Actor相互独立
- ✅ 一个失败不影响其他
- ✅ 大多数场景（默认策略）

### AllForOne策略

**定义**：一个子Actor失败，重启所有子Actor

```scala
// Pekko Typed默认没有AllForOne
// 需要手动实现

object AllForOneSupervisor {
  
  def apply(): Behavior[Command] = {
    Behaviors.setup { ctx =>
      val workers = (1 to 3).map { i =>
        ctx.spawn(
          Behaviors.supervise(WorkerActor())
            .onFailure[Exception](SupervisorStrategy.stop),
          s"worker$i"
        )
      }.toList
      
      // 监控所有worker
      workers.foreach(ctx.watch)
      
      supervising(workers)
    }
  }
  
  private def supervising(workers: List[ActorRef[WorkerCommand]]): Behavior[Command] = {
    Behaviors.receiveSignal {
      case (ctx, Terminated(ref)) =>
        // 一个worker停止，重启所有worker
        ctx.log.warn(s"Worker ${ref.path} terminated, restarting all")
        workers.foreach(ctx.stop)
        
        // 重新创建
        val newWorkers = (1 to 3).map { i =>
          ctx.spawn(WorkerActor(), s"worker$i")
        }.toList
        
        supervising(newWorkers)
    }
  }
}
```

**适用场景**：
- ✅ 子Actor有共享状态
- ✅ 需要保持一致性
- ✅ 例如：集群节点、数据库分片

---

## BackoffSupervisor

### 指数退避重启

```scala
// BackoffSupervisor：避免频繁重启
object RobustActor {
  
  def apply(): Behavior[Command] = {
    Behaviors.supervise(
      Behaviors.supervise(
        working()
      ).onFailure[Exception](
        SupervisorStrategy.restart
          .withLimit(maxNrOfRetries = 10, withinTimeRange = 1.minute)
      )
    ).onFailure[Exception](
      SupervisorStrategy.restartWithBackoff(
        minBackoff = 3.seconds,
        maxBackoff = 30.seconds,
        randomFactor = 0.2
      )
    )
  }
  
  private def working(): Behavior[Command] = {
    Behaviors.receive { (ctx, msg) =>
      // 业务逻辑
      Behaviors.same
    }
  }
}

// 退避时间计算：
// 第1次失败：3秒 * (1 + random(-0.2, 0.2)) = 2.4-3.6秒
// 第2次失败：6秒 * (1 + random(-0.2, 0.2)) = 4.8-7.2秒
// 第3次失败：12秒
// 第4次失败：24秒
// 第5次失败：30秒（maxBackoff）
```

**优势**：
- ✅ 避免雪崩效应
- ✅ 给下游服务恢复时间
- ✅ 降低系统负载

### 重启限制

```scala
// 限制重启次数
SupervisorStrategy.restart
  .withLimit(
    maxNrOfRetries = 3,      // 最多重启3次
    withinTimeRange = 1.minute  // 1分钟内
  )

// 超过限制：
// 1分钟内重启超过3次 → 停止Actor
```

---

## 失败检测与隔离

### DeathWatch机制

```scala
// 监控Actor生命周期
object ParentActor {
  
  def apply(): Behavior[Command] = {
    Behaviors.setup { ctx =>
      val child = ctx.spawn(ChildActor(), "child")
      
      // 监控child
      ctx.watch(child)
      
      watching(child)
    }
  }
  
  private def watching(child: ActorRef[ChildCommand]): Behavior[Command] = {
    Behaviors.receiveSignal {
      case (ctx, Terminated(`child`)) =>
        ctx.log.warn("Child actor terminated")
        
        // 重新创建
        val newChild = ctx.spawn(ChildActor(), "child")
        ctx.watch(newChild)
        
        watching(newChild)
    }
  }
}
```

### 失败隔离

```
隔离原则：

1. 进程级隔离
   不同Actor运行在不同JVM进程
   
2. 线程级隔离
   不同Actor在不同线程执行
   
3. 内存级隔离
   Actor状态相互独立
   
4. 故障级隔离
   一个Actor失败不影响其他

示例：
Worker1失败 → 只重启Worker1
Worker2、Worker3继续运行
API层不受影响
```

---

## 监督树设计原则

### 1. 错误内核模式

```
外层：健壮、简单、容错
内层：复杂、易错、被监督

示例：
Supervisor (简单，只管理)
    │
    ├─ Worker1 (复杂业务逻辑)
    ├─ Worker2 (数据库操作)
    └─ Worker3 (网络请求)
```

### 2. 分层监督

```scala
// 三层架构
object ThreeTierSystem {
  
  // 第1层：API层
  def apiLayer(): Behavior[ApiCommand] = {
    Behaviors.supervise(
      apiHandler()
    ).onFailure[Exception](
      SupervisorStrategy.restart  // API层失败：重启
    )
  }
  
  // 第2层：业务逻辑层
  def businessLayer(): Behavior[BusinessCommand] = {
    Behaviors.supervise(
      businessLogic()
    ).onFailure[BusinessException](
      SupervisorStrategy.restart  // 业务异常：重启
    ).onFailure[SystemException](
      SupervisorStrategy.escalate  // 系统异常：上报
    )
  }
  
  // 第3层：数据访问层
  def dataLayer(): Behavior[DataCommand] = {
    Behaviors.supervise(
      dataAccess()
    ).onFailure[SQLException](
      SupervisorStrategy.restart  // SQL异常：重启
        .withLimit(3, 1.minute)
    )
  }
}
```

### 3. 故障边界

```
定义清晰的故障边界：

API Gateway (边界1)
    │
    ├─ Auth Service (边界2)
    │      └─ Token Cache
    │
    ├─ Order Service (边界3)
    │      ├─ Order Processor
    │      └─ Payment Handler
    │
    └─ Notification Service (边界4)

原则：
- 每个Service是独立的故障边界
- Service内部失败不影响其他Service
- 使用Circuit Breaker保护边界
```

---

## 源码实现分析

### SupervisorStrategy实现

```scala
// SupervisorStrategy.scala
sealed abstract class SupervisorStrategy

object SupervisorStrategy {
  
  // Restart策略
  def restart: RestartSupervisorStrategy = RestartSupervisorStrategy
  
  // Resume策略
  def resume: ResumeSupervisorStrategy = ResumeSupervisorStrategy
  
  // Stop策略
  def stop: StopSupervisorStrategy = StopSupervisorStrategy
  
  // Escalate策略
  private[pekko] def escalate: EscalateSupervisorStrategy = EscalateSupervisorStrategy
  
  // Restart with backoff
  def restartWithBackoff(
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double
  ): BackoffSupervisorStrategy = {
    BackoffSupervisorStrategy(minBackoff, maxBackoff, randomFactor)
  }
}
```

### Supervise Behavior实现

```scala
// Supervisor.scala
object Behaviors {
  
  def supervise[T](behavior: Behavior[T]): Supervise[T] = {
    Supervise(behavior, SupervisorStrategy.restart)
  }
}

// Supervise包装器
final case class Supervise[T](
  wrapped: Behavior[T],
  strategy: SupervisorStrategy
) extends Behavior[T]

// 解释消息时处理异常
object Behavior {
  
  def interpretMessage[T](
    behavior: Behavior[T],
    ctx: ActorContext[T],
    msg: T
  ): Behavior[T] = {
    
    behavior match {
      case s: Supervise[T] =>
        try {
          // 正常处理消息
          val next = interpretMessage(s.wrapped, ctx, msg)
          s.copy(wrapped = next)
        } catch {
          case NonFatal(e) =>
            // 应用监督策略
            handleException(s, ctx, e, msg)
        }
      
      case other =>
        // 其他类型...
    }
  }
  
  private def handleException[T](
    supervise: Supervise[T],
    ctx: ActorContext[T],
    e: Throwable,
    msg: T
  ): Behavior[T] = {
    
    supervise.strategy match {
      case RestartSupervisorStrategy =>
        // 重启：返回初始Behavior
        ctx.log.error(s"Restarting due to ${e.getMessage}", e)
        val restarted = Behavior.validateAsInitial(supervise.wrapped)
        supervise.copy(wrapped = restarted)
      
      case ResumeSupervisorStrategy =>
        // 恢复：保持当前Behavior
        ctx.log.warn(s"Resuming after ${e.getMessage}", e)
        supervise
      
      case StopSupervisorStrategy =>
        // 停止
        ctx.log.error(s"Stopping due to ${e.getMessage}", e)
        Behaviors.stopped
      
      case EscalateSupervisorStrategy =>
        // 上报：重新抛出异常
        throw e
    }
  }
}
```

---

## 实战案例

### 案例1：数据库连接池

```scala
// 数据库连接池Actor
object DatabasePool {
  
  sealed trait Command
  case class Execute(sql: String, replyTo: ActorRef[Result]) extends Command
  private case class WorkerFailed(worker: ActorRef[WorkerCommand]) extends Command
  
  def apply(poolSize: Int): Behavior[Command] = {
    Behaviors.setup { ctx =>
      // 创建worker pool
      val workers = (1 to poolSize).map { i =>
        val worker = ctx.spawn(
          Behaviors.supervise(DatabaseWorker())
            .onFailure[SQLException](
              SupervisorStrategy.restart
                .withLimit(3, 1.minute)
            ),
          s"worker-$i"
        )
        ctx.watch(worker)
        worker
      }.toList
      
      managing(workers, workers)
    }
  }
  
  private def managing(
    allWorkers: List[ActorRef[WorkerCommand]],
    availableWorkers: List[ActorRef[WorkerCommand]]
  ): Behavior[Command] = {
    
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case Execute(sql, replyTo) =>
          availableWorkers match {
            case worker :: rest =>
              // 分配worker
              worker ! WorkerCommand.Execute(sql, replyTo, ctx.self)
              managing(allWorkers, rest)
            
            case Nil =>
              // 无可用worker，排队
              ctx.log.warn("No available workers")
              Behaviors.same
          }
      }
    }.receiveSignal {
      case (ctx, Terminated(worker)) =>
        // worker终止，重新创建
        ctx.log.warn(s"Worker ${worker.path} terminated")
        
        val newWorker = ctx.spawn(
          Behaviors.supervise(DatabaseWorker())
            .onFailure[SQLException](
              SupervisorStrategy.restart.withLimit(3, 1.minute)
            ),
          worker.path.name
        )
        ctx.watch(newWorker)
        
        val newAll = allWorkers.filterNot(_ == worker) :+ newWorker
        val newAvailable = availableWorkers.filterNot(_ == worker) :+ newWorker
        
        managing(newAll, newAvailable)
    }
  }
}
```

### 案例2：Circuit Breaker集成

```scala
// Circuit Breaker + Supervisor
object ResilientService {
  
  sealed trait Command
  case class Request(data: String, replyTo: ActorRef[Response]) extends Command
  
  def apply(): Behavior[Command] = {
    Behaviors.setup { ctx =>
      implicit val ec = ctx.executionContext
      
      // Circuit Breaker
      val breaker = CircuitBreaker(
        ctx.system.scheduler,
        maxFailures = 5,
        callTimeout = 10.seconds,
        resetTimeout = 1.minute
      )
      
      Behaviors.supervise(
        active(breaker)
      ).onFailure[TimeoutException](
        SupervisorStrategy.restart
          .withLimit(3, 1.minute)
      )
    }
  }
  
  private def active(breaker: CircuitBreaker): Behavior[Command] = {
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case Request(data, replyTo) =>
          ctx.pipeToSelf(
            breaker.withCircuitBreaker(callService(data))
          ) {
            case Success(result) => 
              InternalResponse(result, replyTo)
            case Failure(e) =>
              InternalFailure(e, replyTo)
          }
          Behaviors.same
      }
    }
  }
}
```

---

## 总结

### 核心要点

**1. Let it Crash哲学**
- 不要防御式编程
- 让监督者处理错误
- 隔离失败，不扩散

**2. 四种监督决策**
- Restart：重新初始化
- Resume：忽略继续
- Stop：停止Actor
- Escalate：上报父Actor

**3. 监督策略**
- OneForOne：独立处理（默认）
- AllForOne：一起恢复（手动实现）
- BackoffSupervisor：指数退避

**4. 设计原则**
- 错误内核模式
- 分层监督
- 清晰故障边界

**5. 实现机制**
- Supervise包装Behavior
- 捕获异常应用策略
- DeathWatch监控生命周期

### 监督策略选择

| 场景 | 推荐策略 |
|-----|---------|
| 状态损坏 | Restart |
| 临时错误 | Resume |
| 不可恢复 | Stop |
| 无法处理 | Escalate |
| 频繁失败 | Backoff Restart |

### 下一篇预告

**《Stash与UnstashAll：消息暂存的艺术》**
- Stash的使用场景
- StashBuffer内部实现
- 消息顺序保证
- 内存泄漏风险
- 最佳实践

---

**项目地址**: https://github.com/Xuxiaotuan/pekko-reference

**作者**: Xuxiaotuan  
**日期**: 2024年11月
