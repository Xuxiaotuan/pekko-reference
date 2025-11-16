# Actor设计模式与实战案例

> 配套文档：Actor模型深度解析 - 架构篇

本文档提供Actor模型的实战案例和常用设计模式。

---

## 目录

- [Actor生命周期管理](#actor生命周期管理)
- [复杂状态管理模式](#复杂状态管理模式)
- [高级消息模式](#高级消息模式)
- [性能优化技巧](#性能优化技巧)
- [调试与监控](#调试与监控)

---

## Actor生命周期管理

### 生命周期钩子

```scala
def myActor(): Behavior[Command] = Behaviors.setup { context =>
  
  // 1. PreStart: Actor创建时
  context.log.info("Actor starting up")
  
  // 2. PostStop: Actor停止时
  Behaviors.receiveMessage[Command] {
    case DoWork =>
      // 处理消息
      Behaviors.same
    
    case Stop =>
      // 优雅停止
      Behaviors.stopped {
        context.log.info("Actor shutting down")
        // 清理资源
      }
  }
}
```

### 优雅关闭模式

```scala
sealed trait Command
case class ProcessItem(item: String) extends Command
case object GracefulShutdown extends Command
private case object ShutdownTimeout extends Command

def worker(pending: List[String] = List.empty): Behavior[Command] = {
  Behaviors.setup { context =>
    Behaviors.receiveMessage {
      case ProcessItem(item) =>
        // 正常处理
        context.log.info(s"Processing: $item")
        worker(pending :+ item)
      
      case GracefulShutdown =>
        if (pending.isEmpty) {
          // 立即停止
          context.log.info("No pending work, stopping immediately")
          Behaviors.stopped
        } else {
          // 等待完成
          context.log.info(s"Waiting for ${pending.size} items to complete")
          context.scheduleOnce(30.seconds, context.self, ShutdownTimeout)
          shuttingDown(pending)
        }
    }
  }
}

def shuttingDown(pending: List[String]): Behavior[Command] = {
  Behaviors.receiveMessage {
    case ProcessItem(item) =>
      // 拒绝新任务
      Behaviors.same
    
    case ItemCompleted(item) =>
      val remaining = pending.filterNot(_ == item)
      if (remaining.isEmpty) {
        Behaviors.stopped
      } else {
        shuttingDown(remaining)
      }
    
    case ShutdownTimeout =>
      // 强制停止
      Behaviors.stopped
  }
}
```

---

## 复杂状态管理模式

### 1. FSM模式（有限状态机）

```scala
// 定义状态和数据
sealed trait State
case object Idle extends State
case object Active extends State
case object Busy extends State

case class Data(
  counter: Int,
  lastActivity: Instant,
  items: List[String]
)

// FSM Actor
def fsmActor(state: State, data: Data): Behavior[Command] = {
  state match {
    case Idle =>
      idleBehavior(data)
    
    case Active =>
      activeBehavior(data)
    
    case Busy =>
      busyBehavior(data)
  }
}

def idleBehavior(data: Data): Behavior[Command] = {
  Behaviors.receiveMessage {
    case StartWork =>
      // 状态转换: Idle -> Active
      fsmActor(Active, data.copy(lastActivity = Instant.now()))
    
    case GetStatus(replyTo) =>
      replyTo ! StatusResponse("idle", data)
      Behaviors.same
  }
}

def activeBehavior(data: Data): Behavior[Command] = {
  Behaviors.receiveMessage {
    case AddItem(item) =>
      val newData = data.copy(items = data.items :+ item)
      if (newData.items.size > 10) {
        // 状态转换: Active -> Busy
        fsmActor(Busy, newData)
      } else {
        fsmActor(Active, newData)
      }
    
    case Complete =>
      // 状态转换: Active -> Idle
      fsmActor(Idle, Data(data.counter + 1, Instant.now(), List.empty))
  }
}
```

### 2. Stash模式（消息暂存）

```scala
import org.apache.pekko.actor.typed.scaladsl.StashBuffer

def withStash(): Behavior[Command] = Behaviors.setup { context =>
  Behaviors.withStash(100) { buffer =>
    idle(buffer)
  }
}

def idle(buffer: StashBuffer[Command]): Behavior[Command] = {
  Behaviors.receiveMessage {
    case Initialize =>
      // 初始化需要时间
      context.pipeToSelf(initializeFuture) {
        case Success(_) => InitializeComplete
        case Failure(ex) => InitializeFailed(ex)
      }
      initializing(buffer)
    
    case otherMessage =>
      // 暂存消息
      buffer.stash(otherMessage)
      Behaviors.same
  }
}

def initializing(buffer: StashBuffer[Command]): Behavior[Command] = {
  Behaviors.receiveMessage {
    case InitializeComplete =>
      // 初始化完成，处理暂存的消息
      buffer.unstashAll(active())
    
    case InitializeFailed(ex) =>
      context.log.error("Initialization failed", ex)
      Behaviors.stopped
    
    case otherMessage =>
      // 继续暂存
      buffer.stash(otherMessage)
      Behaviors.same
  }
}

def active(): Behavior[Command] = {
  Behaviors.receiveMessage {
    case ProcessItem(item) =>
      // 正常处理
      context.log.info(s"Processing: $item")
      Behaviors.same
  }
}
```

### 3. Router模式（负载均衡）

```scala
import org.apache.pekko.actor.typed.receptionist.{Receptionist, ServiceKey}
import org.apache.pekko.actor.typed.scaladsl.Routers

// 定义服务Key
val workerServiceKey = ServiceKey[WorkerCommand]("worker-service")

// 创建Worker Pool
def createWorkerPool(size: Int): Behavior[WorkerCommand] = Behaviors.setup { context =>
  
  // 创建Worker实例
  val workers = (1 to size).map { i =>
    context.spawn(workerBehavior(), s"worker-$i")
  }
  
  // 注册到Receptionist
  workers.foreach { worker =>
    context.system.receptionist ! Receptionist.Register(workerServiceKey, worker)
  }
  
  // 创建Router
  val pool = Routers.pool(size) {
    workerBehavior()
  }
  
  // 或使用Group Router（从Receptionist发现）
  val group = Routers.group(workerServiceKey)
  
  pool
}

// 使用Router
def master(): Behavior[Command] = Behaviors.setup { context =>
  val workerPool = context.spawn(createWorkerPool(5), "worker-pool")
  
  Behaviors.receiveMessage {
    case ProcessData(data) =>
      // 路由到worker
      workerPool ! WorkerCommand.Process(data)
      Behaviors.same
  }
}
```

---

## 高级消息模式

### 1. Scatter-Gather模式

```scala
// 向多个Actor发送请求，收集所有响应
def scatterGather(workers: List[ActorRef[WorkerCommand]]): Behavior[Command] = {
  Behaviors.setup { context =>
    
    Behaviors.receiveMessage {
      case QueryAll(query, replyTo) =>
        // 创建聚合Actor
        val aggregator = context.spawn(
          responseAggregator(workers.size, replyTo),
          s"aggregator-${System.currentTimeMillis()}"
        )
        
        // 发送请求到所有Worker
        workers.foreach { worker =>
          worker ! WorkerCommand.Query(query, aggregator)
        }
        
        Behaviors.same
    }
  }
}

// 响应聚合Actor
def responseAggregator(
  expectedResponses: Int,
  originalReplyTo: ActorRef[AggregatedResponse]
): Behavior[WorkerResponse] = {
  collectResponses(List.empty, expectedResponses, originalReplyTo)
}

def collectResponses(
  collected: List[WorkerResponse],
  remaining: Int,
  originalReplyTo: ActorRef[AggregatedResponse]
): Behavior[WorkerResponse] = {
  Behaviors.setup { context =>
    // 设置超时
    context.setReceiveTimeout(5.seconds, ReceiveTimeout)
    
    Behaviors.receiveMessage {
      case response: WorkerResponse =>
        val newCollected = collected :+ response
        val newRemaining = remaining - 1
        
        if (newRemaining == 0) {
          // 所有响应收集完成
          originalReplyTo ! AggregatedResponse(newCollected)
          Behaviors.stopped
        } else {
          collectResponses(newCollected, newRemaining, originalReplyTo)
        }
      
      case ReceiveTimeout =>
        // 超时，返回已收集的响应
        context.log.warn(s"Timeout, collected ${collected.size} of $expectedResponses")
        originalReplyTo ! AggregatedResponse(collected)
        Behaviors.stopped
    }
  }
}
```

### 2. Request Throttling（请求限流）

```scala
import scala.concurrent.duration._

def throttledActor(
  maxRequests: Int,
  perDuration: FiniteDuration
): Behavior[Command] = {
  Behaviors.setup { context =>
    Behaviors.withTimers { timers =>
      throttling(Queue.empty, 0, maxRequests, perDuration, timers)
    }
  }
}

def throttling(
  queue: Queue[Command],
  currentCount: Int,
  maxRequests: Int,
  perDuration: FiniteDuration,
  timers: TimerScheduler[Command]
): Behavior[Command] = {
  Behaviors.receiveMessage {
    case request: ProcessRequest =>
      if (currentCount < maxRequests) {
        // 直接处理
        processRequest(request)
        
        // 设置重置计数器的定时器
        if (currentCount == 0) {
          timers.startSingleTimer(ResetCounter, perDuration)
        }
        
        throttling(queue, currentCount + 1, maxRequests, perDuration, timers)
      } else {
        // 队列已满，拒绝
        if (queue.size >= 100) {
          request.replyTo ! RequestRejected("Queue full")
          Behaviors.same
        } else {
          // 加入队列
          throttling(queue.enqueue(request), currentCount, maxRequests, perDuration, timers)
        }
      }
    
    case ResetCounter =>
      // 重置计数器，处理队列中的请求
      val toProcess = math.min(queue.size, maxRequests)
      val (process, remaining) = queue.splitAt(toProcess)
      
      process.foreach(processRequest)
      
      if (remaining.nonEmpty) {
        timers.startSingleTimer(ResetCounter, perDuration)
      }
      
      throttling(remaining, toProcess, maxRequests, perDuration, timers)
  }
}
```

### 3. Circuit Breaker模式

```scala
sealed trait CircuitState
case object Closed extends CircuitState   // 正常
case object Open extends CircuitState     // 断开
case object HalfOpen extends CircuitState // 半开

def circuitBreaker(
  maxFailures: Int = 5,
  resetTimeout: FiniteDuration = 30.seconds
): Behavior[Command] = {
  Behaviors.setup { context =>
    Behaviors.withTimers { timers =>
      circuitBehavior(Closed, 0, maxFailures, resetTimeout, timers)
    }
  }
}

def circuitBehavior(
  state: CircuitState,
  failureCount: Int,
  maxFailures: Int,
  resetTimeout: FiniteDuration,
  timers: TimerScheduler[Command]
): Behavior[Command] = {
  state match {
    case Closed =>
      Behaviors.receiveMessage {
        case CallService(request, replyTo) =>
          // 调用服务
          context.pipeToSelf(callExternalService(request)) {
            case Success(result) => ServiceSuccess(result, replyTo)
            case Failure(ex) => ServiceFailure(ex, replyTo)
          }
          Behaviors.same
        
        case ServiceSuccess(result, replyTo) =>
          replyTo ! result
          // 重置失败计数
          circuitBehavior(Closed, 0, maxFailures, resetTimeout, timers)
        
        case ServiceFailure(ex, replyTo) =>
          replyTo ! ErrorResponse(ex)
          val newCount = failureCount + 1
          
          if (newCount >= maxFailures) {
            // 打开断路器
            context.log.warn(s"Circuit breaker opened after $newCount failures")
            timers.startSingleTimer(TryReset, resetTimeout)
            circuitBehavior(Open, newCount, maxFailures, resetTimeout, timers)
          } else {
            circuitBehavior(Closed, newCount, maxFailures, resetTimeout, timers)
          }
      }
    
    case Open =>
      Behaviors.receiveMessage {
        case CallService(request, replyTo) =>
          // 快速失败
          replyTo ! CircuitOpenResponse("Service unavailable")
          Behaviors.same
        
        case TryReset =>
          context.log.info("Circuit breaker entering half-open state")
          circuitBehavior(HalfOpen, failureCount, maxFailures, resetTimeout, timers)
      }
    
    case HalfOpen =>
      Behaviors.receiveMessage {
        case CallService(request, replyTo) =>
          // 尝试调用
          context.pipeToSelf(callExternalService(request)) {
            case Success(result) => ServiceSuccess(result, replyTo)
            case Failure(ex) => ServiceFailure(ex, replyTo)
          }
          Behaviors.same
        
        case ServiceSuccess(result, replyTo) =>
          replyTo ! result
          context.log.info("Circuit breaker closed after successful call")
          // 关闭断路器
          circuitBehavior(Closed, 0, maxFailures, resetTimeout, timers)
        
        case ServiceFailure(ex, replyTo) =>
          replyTo ! ErrorResponse(ex)
          context.log.warn("Circuit breaker re-opened after failure")
          // 重新打开
          timers.startSingleTimer(TryReset, resetTimeout)
          circuitBehavior(Open, failureCount, maxFailures, resetTimeout, timers)
      }
  }
}
```

---

## 性能优化技巧

### 1. Mailbox配置

```hocon
# application.conf

# 自定义Mailbox
custom-dispatcher {
  mailbox-type = "org.apache.pekko.dispatch.BoundedMailbox"
  mailbox-capacity = 1000
  mailbox-push-timeout-time = 10ms
}

# Actor使用自定义Dispatcher
pekko.actor.deployment {
  /workflow-supervisor/* {
    dispatcher = custom-dispatcher
  }
}
```

```scala
// 代码中指定Dispatcher
val props = DispatcherSelector.fromConfig("custom-dispatcher")
context.spawn(workflowActor(), "workflow", props)
```

### 2. 批处理消息

```scala
def batchProcessor(): Behavior[Command] = {
  Behaviors.withTimers { timers =>
    processing(List.empty, timers)
  }
}

def processing(
  buffer: List[Item],
  timers: TimerScheduler[Command]
): Behavior[Command] = {
  Behaviors.receiveMessage {
    case AddItem(item) =>
      val newBuffer = buffer :+ item
      
      // 批量大小达到阈值
      if (newBuffer.size >= 100) {
        processBatch(newBuffer)
        processing(List.empty, timers)
      } else {
        // 设置定时器，避免消息积压
        if (buffer.isEmpty) {
          timers.startSingleTimer(FlushBatch, 1.second)
        }
        processing(newBuffer, timers)
      }
    
    case FlushBatch =>
      if (buffer.nonEmpty) {
        processBatch(buffer)
      }
      processing(List.empty, timers)
  }
}

def processBatch(items: List[Item]): Unit = {
  // 批量处理，比逐个处理高效
  database.batchInsert(items)
}
```

### 3. 避免阻塞

```scala
// ❌ 错误：阻塞Actor
Behaviors.receiveMessage {
  case FetchData(id, replyTo) =>
    val data = database.query(id)  // 阻塞！
    replyTo ! data
    Behaviors.same
}

// ✅ 正确：使用Future + pipeToSelf
Behaviors.receiveMessage {
  case FetchData(id, replyTo) =>
    context.pipeToSelf(Future {
      database.query(id)  // 在Future中执行
    }(executionContext)) {
      case Success(data) => DataFetched(data, replyTo)
      case Failure(ex) => DataFetchFailed(ex, replyTo)
    }
    Behaviors.same
  
  case DataFetched(data, replyTo) =>
    replyTo ! data
    Behaviors.same
}
```

---

## 调试与监控

### 1. 日志记录

```scala
Behaviors.setup { context =>
  // 使用context.log
  context.log.info("Actor started")
  context.log.debug("Processing message: {}", message)
  context.log.warn("Unusual condition detected")
  context.log.error("Error occurred", exception)
  
  // 结构化日志
  context.log.info(
    "Workflow execution completed - id: {}, duration: {}ms",
    workflowId,
    duration
  )
  
  Behaviors.receiveMessage { ... }
}
```

### 2. 监控指标

```scala
import org.apache.pekko.actor.typed.scaladsl.Behaviors

def monitoredActor(): Behavior[Command] = Behaviors.setup { context =>
  var messageCount = 0L
  var processingTime = 0L
  
  Behaviors.receiveMessage {
    case ProcessItem(item) =>
      val startTime = System.nanoTime()
      
      // 处理消息
      processItem(item)
      
      val duration = System.nanoTime() - startTime
      messageCount += 1
      processingTime += duration
      
      // 定期报告指标
      if (messageCount % 1000 == 0) {
        val avgTime = processingTime / messageCount
        context.log.info(
          "Actor metrics - messages: {}, avg processing time: {}μs",
          messageCount,
          avgTime / 1000
        )
      }
      
      Behaviors.same
  }
}
```

### 3. 死信监控

```scala
import org.apache.pekko.actor.typed.DeadLetter

// 订阅死信
val deadLetterListener = context.spawn(
  Behaviors.receive[DeadLetter] { (context, deadLetter) =>
    context.log.warn(
      "Dead letter detected - message: {}, sender: {}, recipient: {}",
      deadLetter.message,
      deadLetter.sender,
      deadLetter.recipient
    )
    Behaviors.same
  },
  "dead-letter-listener"
)

context.system.eventStream ! EventStream.Subscribe(
  classOf[DeadLetter],
  deadLetterListener
)
```

---

## 总结

本文档介绍了Actor模型的高级模式和实战技巧：

### 关键模式

1. **生命周期管理**：优雅关闭、资源清理
2. **复杂状态**：FSM、Stash、Router
3. **消息模式**：Scatter-Gather、限流、熔断
4. **性能优化**：Mailbox、批处理、避免阻塞
5. **监控调试**：日志、指标、死信

### 设计原则

- ✅ 保持Actor职责单一
- ✅ 消息不可变
- ✅ 避免阻塞操作
- ✅ 合理使用监督策略
- ✅ 监控关键指标

---

**返回主文档**: [02_actor_model_architecture.md](./02_actor_model_architecture.md)
