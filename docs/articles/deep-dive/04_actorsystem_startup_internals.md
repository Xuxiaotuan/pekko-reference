# Pekko ActorSystem启动流程源码剖析

> **深度分析系列** - 第四篇：从源码看ActorSystem如何启动

---

## 📋 目录

- [引言](#引言)
- [ActorSystem概览](#actorsystem概览)
- [启动流程总览](#启动流程总览)
- [Guardian Actor初始化](#guardian-actor初始化)
- [Dispatcher线程池构建](#dispatcher线程池构建)
- [Mailbox实现机制](#mailbox实现机制)
- [Scheduler调度器](#scheduler调度器)
- [系统扩展加载](#系统扩展加载)
- [优雅关闭流程](#优雅关闭流程)
- [总结](#总结)

---

## 引言

```scala
// 看似简单的一行代码
val system = ActorSystem(Behaviors.empty, "MySystem")

// 背后发生了什么？
// - 解析配置文件
// - 创建线程池
// - 初始化Guardian
// - 加载扩展
// - ...100+步骤
```

本文将深入Pekko源码，揭示ActorSystem启动的完整流程。

---

## ActorSystem概览

### ActorSystem的职责

```
ActorSystem是Actor世界的"操作系统"

职责：
1. 管理Actor生命周期
2. 提供线程池（Dispatcher）
3. 管理配置（Config）
4. 提供调度器（Scheduler）
5. 管理扩展（Extension）
6. 提供日志（Logging）
```

### 核心组件

```scala
trait ActorSystem[T] {
  // Actor相关
  def systemActorOf[U](behavior: Behavior[U], name: String): ActorRef[U]
  
  // 配置
  def settings: Settings
  
  // 执行上下文
  implicit def executionContext: ExecutionContext
  
  // 调度器
  def scheduler: Scheduler
  
  // 日志
  def log: Logger
  
  // 生命周期
  def terminate(): Future[Terminated]
  def whenTerminated: Future[Terminated]
}
```

---

## 启动流程总览

### 启动步骤概览

```
1. 解析配置
   └─ application.conf + reference.conf
   
2. 创建ActorSystemImpl
   ├─ 初始化Settings
   ├─ 创建EventStream
   └─ 创建Scheduler

3. 构建Dispatcher
   ├─ 解析dispatcher配置
   ├─ 创建ExecutorService
   └─ 创建MessageDispatcher

4. 初始化Guardian
   ├─ 创建SystemGuardian
   ├─ 创建UserGuardian
   └─ 启动用户根Actor

5. 加载扩展
   └─ 通过SPI机制加载

6. 完成启动
   └─ ActorSystem就绪
```

### 源码入口

```scala
// ActorSystem.scala
object ActorSystem {
  
  def apply[T](
    guardianBehavior: Behavior[T],
    name: String
  ): ActorSystem[T] = {
    apply(guardianBehavior, name, ConfigFactory.load())
  }
  
  def apply[T](
    guardianBehavior: Behavior[T],
    name: String,
    config: Config
  ): ActorSystem[T] = {
    // 1. 验证名称
    validateName(name)
    
    // 2. 应用配置
    val finalConfig = config
      .withFallback(ConfigFactory.defaultReference())
      .resolve()
    
    // 3. 创建ActorSystemImpl
    new ActorSystemImpl(name, finalConfig, guardianBehavior)
  }
}
```

---

## Guardian Actor初始化

### Guardian层级结构

```
ActorSystem
    │
    └─ SystemGuardian ("/")
           │
           ├─ System Actors (系统级)
           │  ├─ /system/log
           │  ├─ /system/deadLetters
           │  └─ /system/eventStream
           │
           └─ UserGuardian ("/user")
                  │
                  └─ User Actors (用户级)
                     ├─ /user/myActor1
                     └─ /user/myActor2
```

### SystemGuardian创建

```scala
// ActorSystemImpl.scala
class ActorSystemImpl[T](
  val name: String,
  config: Config,
  guardianBehavior: Behavior[T]
) extends ActorSystem[T] {
  
  // 1. 创建Settings
  val settings = new Settings(config)
  
  // 2. 创建EventStream
  val eventStream = new EventStreamImpl(this)
  
  // 3. 创建SystemGuardian
  private val systemGuardian: InternalActorRef = {
    val dispatcher = dispatchers.defaultGlobalDispatcher
    
    // SystemGuardian的Behavior
    val behavior = Behaviors.supervise(
      Behaviors.setup[SystemMessage] { ctx =>
        // 创建系统级Actor
        ctx.spawn(DeadLetterActor(), "deadLetters")
        ctx.spawn(EventStreamActor(), "eventStream")
        
        // 启动UserGuardian
        startUserGuardian(ctx, guardianBehavior)
        
        Behaviors.receive { (ctx, msg) =>
          msg match {
            case Terminated(ref) =>
              // 处理Actor终止
              handleTerminated(ref)
              Behaviors.same
          }
        }
      }
    ).onFailure(SupervisorStrategy.restart)
    
    // 创建SystemGuardian的ActorRef
    actorRefFactory.actorOf(
      props = Props(behavior),
      name = "system",
      dispatcher = dispatcher
    )
  }
  
  // 4. 启动UserGuardian
  private def startUserGuardian[T](
    ctx: ActorContext[SystemMessage],
    behavior: Behavior[T]
  ): ActorRef[T] = {
    ctx.spawn(
      behavior = Behaviors.supervise(behavior)
        .onFailure(SupervisorStrategy.restart),
      name = "user"
    )
  }
}
```

### Guardian监督策略

```scala
// SystemGuardian监督所有顶层Actor
object SystemGuardian {
  
  sealed trait Command
  private case class Failed(
    ref: ActorRef[Nothing],
    cause: Throwable
  ) extends Command
  
  def apply(): Behavior[Command] = {
    Behaviors.setup { ctx =>
      
      supervising(ctx, Map.empty)
    }
  }
  
  private def supervising(
    ctx: ActorContext[Command],
    children: Map[String, ActorRef[Nothing]]
  ): Behavior[Command] = {
    
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case Failed(ref, cause) =>
          // 根据策略处理失败
          ctx.log.error(s"Actor ${ref.path} failed", cause)
          
          // Restart策略
          val newRef = restartChild(ref)
          supervising(ctx, children + (ref.path.name -> newRef))
      }
    }
  }
}
```

---

## Dispatcher线程池构建

### Dispatcher配置解析

```hocon
# reference.conf
pekko.actor {
  default-dispatcher {
    type = "Dispatcher"
    executor = "fork-join-executor"
    
    fork-join-executor {
      parallelism-min = 8
      parallelism-factor = 3.0
      parallelism-max = 64
    }
    
    throughput = 5
  }
}
```

### Dispatcher创建流程

```scala
// Dispatchers.scala
class Dispatchers(
  settings: Settings,
  prerequisites: DispatcherPrerequisites
) {
  
  // Dispatcher缓存
  private val dispatcherConfigurators = 
    new ConcurrentHashMap[String, MessageDispatcherConfigurator]()
  
  def lookup(id: String): MessageDispatcher = {
    lookupConfigurator(id).dispatcher()
  }
  
  private def lookupConfigurator(id: String): MessageDispatcherConfigurator = {
    dispatcherConfigurators.computeIfAbsent(id, createConfigurator)
  }
  
  private def createConfigurator(id: String): MessageDispatcherConfigurator = {
    val config = settings.config.getConfig(s"pekko.actor.$id")
    
    val executorType = config.getString("executor")
    
    executorType match {
      case "fork-join-executor" =>
        new ForkJoinExecutorConfigurator(config, prerequisites)
      
      case "thread-pool-executor" =>
        new ThreadPoolExecutorConfigurator(config, prerequisites)
      
      case fqcn =>
        // 自定义Executor
        dynamicAccess.createInstanceFor[ExecutorServiceConfigurator](
          fqcn,
          immutable.Seq(classOf[Config] -> config)
        )
    }
  }
}
```

### ForkJoinPool创建

```scala
// ForkJoinExecutorConfigurator.scala
class ForkJoinExecutorConfigurator(
  config: Config,
  prerequisites: DispatcherPrerequisites
) extends ExecutorServiceConfigurator {
  
  def createExecutorService: ExecutorService = {
    val parallelism = calculateParallelism(config)
    
    new ForkJoinPool(
      parallelism = parallelism,
      threadFactory = threadFactory,
      handler = exceptionHandler,
      asyncMode = true  // FIFO模式
    )
  }
  
  private def calculateParallelism(config: Config): Int = {
    val min = config.getInt("parallelism-min")
    val factor = config.getDouble("parallelism-factor")
    val max = config.getInt("parallelism-max")
    
    val cores = Runtime.getRuntime.availableProcessors()
    val calculated = (cores * factor).toInt
    
    math.max(min, math.min(calculated, max))
  }
}
```

### MessageDispatcher

```scala
// Dispatcher.scala
class Dispatcher(
  _configurator: MessageDispatcherConfigurator,
  id: String,
  throughput: Int,
  executorService: ExecutorServiceDelegate
) extends MessageDispatcher {
  
  // 调度Actor执行
  def dispatch(receiver: ActorCell, invocation: Envelope): Unit = {
    val mbox = receiver.mailbox
    mbox.enqueue(receiver.self, invocation)
    
    // 注册到Dispatcher
    registerForExecution(mbox, hasMessageHint = true, hasSystemMessageHint = false)
  }
  
  protected def registerForExecution(
    mbox: Mailbox,
    hasMessageHint: Boolean,
    hasSystemMessageHint: Boolean
  ): Boolean = {
    
    if (mbox.canBeScheduledForExecution(hasMessageHint, hasSystemMessageHint)) {
      if (mbox.setAsScheduled()) {
        try {
          // 提交到线程池执行
          executorService.execute(mbox)
          true
        } catch {
          case e: RejectedExecutionException =>
            mbox.setAsIdle()
            throw e
        }
      } else {
        false
      }
    } else {
      false
    }
  }
}
```

---

## Mailbox实现机制

### Mailbox接口

```scala
// Mailbox.scala
trait Mailbox {
  // 入队消息
  def enqueue(receiver: ActorRef, msg: Envelope): Unit
  
  // 出队消息
  def dequeue(): Envelope
  
  // 是否有消息
  def hasMessages: Boolean
  
  // 消息数量
  def numberOfMessages: Int
  
  // 执行消息处理
  def run(): Unit
}
```

### UnboundedMailbox实现

```scala
// UnboundedMailbox.scala
class UnboundedMailbox extends Mailbox {
  
  // MPSC队列（JCTools）
  private val queue = new MpscUnboundedArrayQueue[Envelope](128)
  
  def enqueue(receiver: ActorRef, msg: Envelope): Unit = {
    queue.offer(msg)
  }
  
  def dequeue(): Envelope = {
    queue.poll()
  }
  
  def hasMessages: Boolean = {
    !queue.isEmpty
  }
  
  // 核心：消息处理循环
  def run(): Unit = {
    try {
      // 处理throughput条消息
      var left = throughput
      
      while (left > 0 && hasMessages) {
        val envelope = dequeue()
        
        if (envelope ne null) {
          actor.invoke(envelope)  // 调用Actor处理消息
          left -= 1
        }
      }
      
      // 如果还有消息，重新调度
      if (hasMessages) {
        dispatcher.registerForExecution(this, hasMessageHint = true, hasSystemMessageHint = false)
      }
    } catch {
      case NonFatal(e) =>
        handleException(e)
    } finally {
      setAsIdle()
    }
  }
}
```

### BoundedMailbox实现

```scala
// BoundedMailbox.scala
class BoundedMailbox(
  capacity: Int,
  pushTimeOut: Duration
) extends Mailbox {
  
  // 有界队列
  private val queue = new ArrayBlockingQueue[Envelope](capacity)
  
  def enqueue(receiver: ActorRef, msg: Envelope): Unit = {
    if (pushTimeOut.isFinite) {
      // 超时入队
      if (!queue.offer(msg, pushTimeOut.length, pushTimeOut.unit)) {
        // 队列满，根据策略处理
        handleOverflow(receiver, msg)
      }
    } else if (pushTimeOut == Duration.Zero) {
      // 非阻塞
      if (!queue.offer(msg)) {
        handleOverflow(receiver, msg)
      }
    } else {
      // 阻塞
      queue.put(msg)
    }
  }
  
  private def handleOverflow(receiver: ActorRef, msg: Envelope): Unit = {
    receiver match {
      case ref: InternalActorRef =>
        // 发送到DeadLetter
        ref.provider.deadLetters ! DeadLetter(msg.message, msg.sender, receiver)
    }
  }
}
```

---

## Scheduler调度器

### Scheduler接口

```scala
// Scheduler.scala
trait Scheduler {
  // 延迟执行
  def scheduleOnce(
    delay: FiniteDuration,
    runnable: Runnable
  )(implicit executor: ExecutionContext): Cancellable
  
  // 周期执行
  def scheduleAtFixedRate(
    initialDelay: FiniteDuration,
    interval: FiniteDuration,
    runnable: Runnable
  )(implicit executor: ExecutionContext): Cancellable
  
  // 周期执行（上次执行完成后延迟）
  def scheduleWithFixedDelay(
    initialDelay: FiniteDuration,
    delay: FiniteDuration,
    runnable: Runnable
  )(implicit executor: ExecutionContext): Cancellable
}
```

### LightArrayRevolverScheduler

Pekko使用**时间轮**（Timing Wheel）实现高效调度：

```scala
// LightArrayRevolverScheduler.scala
class LightArrayRevolverScheduler(
  config: Config,
  log: LoggingAdapter,
  threadFactory: ThreadFactory
) extends Scheduler {
  
  // 时间轮参数
  private val WheelSize = 512
  private val TickDuration = 100.millis  // 每格100ms
  
  // 时间轮数组
  private val wheel = new Array[Bucket](WheelSize)
  
  // 当前位置
  @volatile private var tick = 0L
  
  // 调度线程
  private val thread = threadFactory.newThread(new Runnable {
    def run(): Unit = {
      while (!stopped) {
        // 每100ms tick一次
        Thread.sleep(TickDuration.toMillis)
        
        val currentTick = tick
        tick = currentTick + 1
        
        // 处理当前bucket
        val bucket = wheel((currentTick % WheelSize).toInt)
        if (bucket ne null) {
          bucket.executeTasks()
        }
      }
    }
  })
  
  def scheduleOnce(
    delay: FiniteDuration,
    runnable: Runnable
  )(implicit executor: ExecutionContext): Cancellable = {
    
    val delayTicks = (delay / TickDuration).toLong
    val targetTick = tick + delayTicks
    val bucketIndex = (targetTick % WheelSize).toInt
    
    val bucket = wheel(bucketIndex)
    val task = new ScheduledTask(runnable, executor, targetTick)
    
    bucket.addTask(task)
    
    task  // 返回Cancellable
  }
}

// Bucket：存储任务的桶
class Bucket {
  private val tasks = new ConcurrentLinkedQueue[ScheduledTask]()
  
  def addTask(task: ScheduledTask): Unit = {
    tasks.offer(task)
  }
  
  def executeTasks(): Unit = {
    var task = tasks.poll()
    while (task ne null) {
      if (!task.isCancelled) {
        task.execute()
      }
      task = tasks.poll()
    }
  }
}
```

**时间轮优势**：
- O(1) 插入
- O(1) 删除
- 高效处理大量定时任务

---

## 系统扩展加载

### Extension机制

```scala
// Extension.scala
trait Extension

trait ExtensionId[T <: Extension] {
  def createExtension(system: ActorSystem[_]): T
}

// 扩展注册
object MyExtension extends ExtensionId[MyExtensionImpl] {
  def createExtension(system: ActorSystem[_]): MyExtensionImpl = {
    new MyExtensionImpl(system)
  }
}

class MyExtensionImpl(system: ActorSystem[_]) extends Extension {
  // 扩展实现
}

// 使用
val ext = MyExtension(system)
```

### 自动加载扩展

```hocon
# application.conf
pekko.extensions = [
  "com.example.MyExtension"
]
```

```scala
// ActorSystemImpl初始化时加载
private def loadExtensions(): Unit = {
  val extensionIds = settings.config.getStringList("pekko.extensions")
  
  extensionIds.asScala.foreach { fqcn =>
    try {
      val extId = dynamicAccess.getObjectFor[ExtensionId[_]](fqcn).get
      extId.createExtension(this)
    } catch {
      case NonFatal(e) =>
        log.error(s"Failed to load extension $fqcn", e)
    }
  }
}
```

---

## 优雅关闭流程

### 关闭步骤

```scala
// ActorSystem.terminate()的流程
def terminate(): Future[Terminated] = {
  if (terminationFuture.isCompleted) {
    terminationFuture
  } else {
    // 1. 标记为正在关闭
    markAsTerminating()
    
    // 2. 停止接受新Actor
    stopAcceptingNewActors()
    
    // 3. 停止UserGuardian
    stopUserGuardian()
    
    // 4. 等待所有Actor终止
    whenAllActorsTerminated()
    
    // 5. 关闭Dispatcher
    shutdownDispatchers()
    
    // 6. 关闭Scheduler
    shutdownScheduler()
    
    // 7. 完成终止
    completeTermination()
    
    terminationFuture
  }
}
```

### CoordinatedShutdown

```scala
// CoordinatedShutdown：协调关闭
object CoordinatedShutdown {
  
  // 关闭阶段
  val PhaseBeforeServiceUnbind = "before-service-unbind"
  val PhaseServiceUnbind = "service-unbind"
  val PhaseServiceRequestsDone = "service-requests-done"
  val PhaseServiceStop = "service-stop"
  val PhaseBeforeClusterShutdown = "before-cluster-shutdown"
  val PhaseClusterShardingShutdownRegion = "cluster-sharding-shutdown-region"
  val PhaseClusterLeave = "cluster-leave"
  val PhaseClusterExiting = "cluster-exiting"
  val PhaseClusterShutdown = "cluster-shutdown"
  val PhaseBeforeActorSystemTerminate = "before-actor-system-terminate"
  val PhaseActorSystemTerminate = "actor-system-terminate"
  
  def apply(system: ActorSystem[_]): CoordinatedShutdown = {
    // 获取或创建CoordinatedShutdown实例
  }
}

// 注册关闭任务
CoordinatedShutdown(system).addTask(
  phase = CoordinatedShutdown.PhaseServiceUnbind,
  taskName = "unbind-http"
) { () =>
  http.unbind().map(_ => Done)
}
```

---

## 总结

### 启动流程回顾

```
ActorSystem.apply()
   ↓
1. 解析配置（Typesafe Config）
   ↓
2. 创建Settings
   ↓
3. 创建EventStream
   ↓
4. 创建Scheduler（时间轮）
   ↓
5. 创建Dispatcher（ForkJoinPool）
   ↓
6. 初始化SystemGuardian
   ↓
7. 启动UserGuardian
   ↓
8. 加载Extensions
   ↓
9. ActorSystem就绪
```

### 核心组件

| 组件 | 职责 | 实现 |
|-----|------|------|
| **Guardian** | Actor层级根节点 | SystemGuardian + UserGuardian |
| **Dispatcher** | 线程池管理 | ForkJoinPool / ThreadPoolExecutor |
| **Mailbox** | 消息队列 | MPSC无锁队列 |
| **Scheduler** | 定时任务 | 时间轮算法 |
| **Extension** | 扩展机制 | SPI动态加载 |

### 性能优化点

1. **Dispatcher**：ForkJoinPool的work-stealing
2. **Mailbox**：无锁MPSC队列
3. **Scheduler**：时间轮O(1)复杂度
4. **批处理**：throughput参数控制

### 下一篇预告

**《消息发送与处理的完整链路》**
- `!` 操作符的底层实现
- 消息如何从发送者到接收者
- Mailbox入队出队机制
- Actor消息处理循环

---

**项目地址**: https://github.com/Xuxiaotuan/pekko-reference

**作者**: Xuxiaotuan  
**日期**: 2024年11月
