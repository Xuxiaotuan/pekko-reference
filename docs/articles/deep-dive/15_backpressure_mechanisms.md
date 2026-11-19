# 背压机制的理论与实现

> **深度分析系列** - 第十五篇：深入流控与过载保护机制

---

## 📋 目录

- [引言](#引言)
- [背压理论基础](#背压理论基础)
- [流控算法](#流控算法)
- [Reactive Streams](#reactive-streams)
- [Actor中的背压](#actor中的背压)
- [Pekko Streams背压](#pekko-streams背压)
- [背压策略](#背压策略)
- [实战案例](#实战案例)
- [总结](#总结)

---

## 引言

什么是背压（Backpressure）？

```
场景：
Producer生产速度：1000 msg/s
Consumer消费速度：100 msg/s

问题：
Queue持续增长 → 内存溢出 → 系统崩溃

解决：背压
Consumer告诉Producer：慢一点！

结果：
系统稳定运行，不会崩溃
```

---

## 背压理论基础

### Little's Law

**利特尔法则**：排队论的核心定理

```
L = λ × W

L = 系统中的平均任务数
λ = 到达率（tasks/second）
W = 平均等待时间（seconds）

示例：
到达率 λ = 100 req/s
处理时间 W = 2s
系统中任务数 L = 100 × 2 = 200

如果Queue容量只有100 → 溢出！
```

### 过载条件

```
定义：
输入速率 > 处理速率

数学表示：
λ_in > μ_out

后果：
Queue长度 → ∞
延迟 → ∞
最终 → OOM

解决：
1. 增加处理能力（扩容）
2. 降低输入速率（背压）
3. 丢弃请求（降级）
```

### 队列理论

```
M/M/1队列模型：

到达：Poisson分布，速率λ
服务：Exponential分布，速率μ
服务器：1个

平均队列长度：
L = λ / (μ - λ)

示例：
λ = 90 req/s
μ = 100 req/s
L = 90 / (100 - 90) = 9

λ = 99 req/s
μ = 100 req/s
L = 99 / (100 - 99) = 99 （激增！）

结论：接近容量时，队列指数增长
```

---

## 流控算法

### Token Bucket（令牌桶）

**原理**：桶中有令牌，处理请求消耗令牌

```
算法：
1. 桶容量：capacity（最大令牌数）
2. 补充速率：rate（令牌/秒）
3. 处理请求：消耗1个令牌
4. 无令牌：拒绝或等待

特点：
✓ 允许突发流量（桶中有余量）
✓ 长期平均速率受限
✓ 平滑流量
```

**实现**：

```scala
class TokenBucket(
  capacity: Int,
  refillRate: Double  // tokens per second
) {
  
  @volatile private var tokens: Double = capacity
  @volatile private var lastRefill: Long = System.nanoTime()
  
  def tryAcquire(): Boolean = synchronized {
    refill()
    
    if (tokens >= 1.0) {
      tokens -= 1.0
      true
    } else {
      false  // 无令牌，拒绝
    }
  }
  
  def acquire(): Unit = synchronized {
    while (tokens < 1.0) {
      refill()
      if (tokens < 1.0) {
        wait(10)  // 等待令牌
      }
    }
    tokens -= 1.0
  }
  
  private def refill(): Unit = {
    val now = System.nanoTime()
    val elapsed = (now - lastRefill) / 1e9  // seconds
    
    val newTokens = elapsed * refillRate
    tokens = math.min(capacity, tokens + newTokens)
    
    lastRefill = now
  }
}

// 使用
val limiter = new TokenBucket(capacity = 100, refillRate = 10)

if (limiter.tryAcquire()) {
  processRequest()
} else {
  rejectRequest()
}
```

### Leaky Bucket（漏桶）

**原理**：固定速率流出，超出部分溢出

```
算法：
1. 桶容量：capacity
2. 流出速率：固定
3. 请求到达：加入桶
4. 桶满：溢出（拒绝）

特点：
✓ 输出速率恒定
✓ 平滑流量
✗ 不允许突发

区别Token Bucket：
- Token Bucket：允许突发
- Leaky Bucket：严格限速
```

**实现**：

```scala
class LeakyBucket(
  capacity: Int,
  leakRate: Double  // items per second
) {
  
  private val queue = new ConcurrentLinkedQueue[Long]()
  @volatile private var lastLeak: Long = System.nanoTime()
  
  def offer(): Boolean = {
    leak()
    
    if (queue.size() < capacity) {
      queue.offer(System.nanoTime())
      true
    } else {
      false  // 桶满，拒绝
    }
  }
  
  private def leak(): Unit = {
    val now = System.nanoTime()
    val elapsed = (now - lastLeak) / 1e9
    
    val itemsToLeak = (elapsed * leakRate).toInt
    
    (0 until itemsToLeak).foreach { _ =>
      queue.poll()
    }
    
    lastLeak = now
  }
}
```

### Sliding Window（滑动窗口）

**原理**：统计时间窗口内的请求数

```scala
class SlidingWindow(
  maxRequests: Int,
  windowSize: Duration
) {
  
  private val timestamps = new ConcurrentLinkedQueue[Long]()
  
  def tryAcquire(): Boolean = {
    val now = System.currentTimeMillis()
    val cutoff = now - windowSize.toMillis
    
    // 移除过期时间戳
    while (!timestamps.isEmpty && timestamps.peek() < cutoff) {
      timestamps.poll()
    }
    
    if (timestamps.size() < maxRequests) {
      timestamps.offer(now)
      true
    } else {
      false  // 超过限制
    }
  }
}

// 使用：每秒最多100个请求
val limiter = new SlidingWindow(
  maxRequests = 100,
  windowSize = 1.second
)
```

---

## Reactive Streams

### 规范

**Reactive Streams**：异步流处理标准

```
核心接口：

1. Publisher（发布者）
   - 生产数据
   - 响应订阅

2. Subscriber（订阅者）
   - 消费数据
   - 请求数据（背压）

3. Subscription（订阅）
   - 连接Publisher和Subscriber
   - 传递背压信号

4. Processor（处理器）
   - 既是Publisher又是Subscriber
   - 转换数据
```

### 协议流程

```
Subscriber → Publisher: subscribe(subscriber)
Publisher → Subscriber: onSubscribe(subscription)

Subscriber → Subscription: request(n)  // 请求n个元素
Publisher → Subscriber: onNext(element) × n

Publisher → Subscriber: onComplete()   // 完成
或
Publisher → Subscriber: onError(throwable)  // 错误

关键：request(n)实现背压
```

### 实现示例

```scala
// Publisher
trait Publisher[T] {
  def subscribe(subscriber: Subscriber[T]): Unit
}

// Subscriber
trait Subscriber[T] {
  def onSubscribe(subscription: Subscription): Unit
  def onNext(element: T): Unit
  def onError(throwable: Throwable): Unit
  def onComplete(): Unit
}

// Subscription
trait Subscription {
  def request(n: Long): Unit  // 请求n个元素
  def cancel(): Unit          // 取消订阅
}

// 简单Publisher实现
class SimplePublisher[T](elements: List[T]) extends Publisher[T] {
  
  def subscribe(subscriber: Subscriber[T]): Unit = {
    val subscription = new SimpleSubscription(elements, subscriber)
    subscriber.onSubscribe(subscription)
  }
}

class SimpleSubscription[T](
  elements: List[T],
  subscriber: Subscriber[T]
) extends Subscription {
  
  private var remaining = elements
  private var demand = 0L
  
  def request(n: Long): Unit = synchronized {
    demand += n
    deliver()
  }
  
  private def deliver(): Unit = {
    while (demand > 0 && remaining.nonEmpty) {
      subscriber.onNext(remaining.head)
      remaining = remaining.tail
      demand -= 1
    }
    
    if (remaining.isEmpty) {
      subscriber.onComplete()
    }
  }
  
  def cancel(): Unit = {
    remaining = Nil
  }
}
```

---

## Actor中的背压

### Bounded Mailbox

**有界邮箱**：限制队列大小

```scala
// 配置
bounded-mailbox {
  mailbox-type = "org.apache.pekko.dispatch.BoundedMailbox"
  mailbox-capacity = 1000
  mailbox-push-timeout-time = 10s
}

// Actor
val actor = system.actorOf(
  Props[MyActor].withMailbox("bounded-mailbox")
)

// 发送消息
actor ! msg  // 如果mailbox满，阻塞最多10秒
```

### 背压策略

```scala
// 策略1：阻塞发送者
mailbox-push-timeout-time = 10s
// 邮箱满时，发送者阻塞

// 策略2：立即拒绝
mailbox-push-timeout-time = 0s
// 邮箱满时，抛出异常

// 策略3：丢弃消息
class DroppingMailbox extends UnboundedMailbox {
  override def enqueue(receiver: ActorRef, msg: Envelope): Unit = {
    if (queue.size < capacity) {
      super.enqueue(receiver, msg)
    } else {
      // 丢弃消息
      system.deadLetters ! DeadLetter(msg.message, msg.sender, receiver)
    }
  }
}
```

### Work Pulling模式

**主动拉取**：Worker主动请求任务

```scala
// Master
object Master {
  case class Task(data: String)
  case object GiveMe Work
  
  def apply(): Behavior[Command] = {
    managing(Queue.empty)
  }
  
  private def managing(tasks: Queue[Task]): Behavior[Command] = {
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case task: Task =>
          // 任务入队
          managing(tasks.enqueue(task))
        
        case GiveMeWork(worker) =>
          if (tasks.nonEmpty) {
            val (task, remaining) = tasks.dequeue
            worker ! task
            managing(remaining)
          } else {
            // 无任务，稍后重试
            Behaviors.same
          }
      }
    }
  }
}

// Worker
object Worker {
  def apply(master: ActorRef[Master.Command]): Behavior[Task] = {
    idle(master)
  }
  
  private def idle(master: ActorRef[Master.Command]): Behavior[Task] = {
    Behaviors.setup { ctx =>
      // 主动请求任务
      master ! GiveMeWork(ctx.self)
      
      Behaviors.receiveMessage {
        case task: Task =>
          // 处理任务
          processTask(task)
          
          // 处理完成，请求下一个
          master ! GiveMeWork(ctx.self)
          Behaviors.same
      }
    }
  }
}
```

---

## Pekko Streams背压

### 自动背压

**Pekko Streams**：内置背压支持

```scala
import org.apache.pekko.stream.scaladsl._

// Source: 生产者
val source = Source(1 to 1000000)

// Flow: 转换
val flow = Flow[Int].map(_ * 2)

// Sink: 消费者
val sink = Sink.foreach[Int](println)

// 连接
source
  .via(flow)
  .runWith(sink)

// 自动背压：
// Sink消费慢 → Flow等待 → Source暂停生产
```

### 异步边界

```scala
// 异步边界：隔离不同处理阶段
source
  .async  // 异步边界1
  .map(slowTransform)  // 慢速转换
  .async  // 异步边界2
  .map(fastTransform)  // 快速转换
  .runWith(sink)

// 背压传播：
// Sink满 → fastTransform停止 → slowTransform停止 → Source停止
```

### Buffer策略

```scala
// 缓冲区：平滑速率差异
source
  .buffer(size = 100, OverflowStrategy.backpressure)
  .map(transform)
  .runWith(sink)

// OverflowStrategy:
// - backpressure: 背压（阻塞上游）
// - dropHead:     丢弃最老元素
// - dropTail:     丢弃最新元素
// - dropBuffer:   清空缓冲区
// - dropNew:      丢弃新元素
// - fail:         失败
```

### Throttle限流

```scala
// 限流：控制处理速率
source
  .throttle(
    elements = 10,       // 10个元素
    per = 1.second,      // 每秒
    maximumBurst = 20    // 最大突发
  )
  .runWith(sink)

// Token Bucket实现
```

---

## 背压策略

### 策略对比

| 策略 | 优点 | 缺点 | 适用场景 |
|-----|------|------|---------|
| **阻塞** | 简单、无丢失 | 可能死锁 | 内部系统 |
| **拒绝** | 快速失败 | 需要重试 | 限流、熔断 |
| **丢弃** | 不阻塞 | 数据丢失 | 日志、监控 |
| **缓冲** | 平滑峰值 | 延迟增加 | 临时突发 |
| **降级** | 保持可用 | 功能受限 | 过载保护 |

### 选择指南

```
1. 内部组件间：
   - 使用背压（阻塞或Reactive Streams）
   - 保证数据完整性

2. 外部接口：
   - 使用限流（Token Bucket）
   - 快速失败（拒绝）

3. 监控日志：
   - 使用丢弃策略
   - 允许数据丢失

4. 临时突发：
   - 使用缓冲
   - 注意内存

5. 持续过载：
   - 降级服务
   - 扩容
```

---

## 实战案例

### 案例1：HTTP服务器限流

```scala
object RateLimitedServer {
  
  // Token Bucket限流
  val limiter = new TokenBucket(
    capacity = 1000,
    refillRate = 100  // 100 req/s
  )
  
  def handleRequest(request: HttpRequest): Future[HttpResponse] = {
    if (limiter.tryAcquire()) {
      // 处理请求
      processRequest(request)
    } else {
      // 限流，返回429
      Future.successful(
        HttpResponse(
          status = StatusCodes.TooManyRequests,
          entity = "Rate limit exceeded"
        )
      )
    }
  }
}
```

### 案例2：消息队列消费者

```scala
object KafkaConsumerWithBackpressure {
  
  def apply(): Behavior[Command] = {
    Behaviors.setup { ctx =>
      
      // Kafka Consumer（Pekko Streams）
      val consumer = Consumer
        .committableSource(consumerSettings, Subscriptions.topics("my-topic"))
        .buffer(100, OverflowStrategy.backpressure)  // 缓冲100条
        .mapAsync(parallelism = 10) { msg =>
          // 异步处理
          processMessage(msg.record.value())
            .map(_ => msg.committableOffset)
        }
        .batch(max = 100, first => CommittableOffsetBatch.empty.updated(first)) {
          (batch, elem) => batch.updated(elem)
        }
        .mapAsync(1)(_.commitScaladsl())  // 批量提交offset
        .runWith(Sink.ignore)
      
      running(consumer)
    }
  }
  
  private def running(stream: Future[Done]): Behavior[Command] = {
    Behaviors.receiveMessage {
      case Stop =>
        stream.foreach(_ => ())
        Behaviors.stopped
    }
  }
}
```

### 案例3：批量处理器

```scala
object BatchProcessor {
  
  def apply(): Behavior[Command] = {
    Behaviors.withStash(capacity = 1000) { stash =>
      Behaviors.withTimers { timers =>
        
        collecting(stash, timers, batchSize = 100)
      }
    }
  }
  
  private def collecting(
    stash: StashBuffer[Command],
    timers: TimerScheduler[Command],
    batchSize: Int
  ): Behavior[Command] = {
    
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case item: Item =>
          // 暂存
          stash.stash(item)
          
          if (stash.size >= batchSize) {
            // 达到批量大小，处理
            processing(stash, timers, batchSize)
          } else if (stash.size == 1) {
            // 第一条消息，设置超时
            timers.startSingleTimer(ProcessBatch, 5.seconds)
            Behaviors.same
          } else {
            // 继续收集
            Behaviors.same
          }
        
        case ProcessBatch =>
          // 超时，处理当前批次
          processing(stash, timers, batchSize)
      }
    }
  }
  
  private def processing(
    stash: StashBuffer[Command],
    timers: TimerScheduler[Command],
    batchSize: Int
  ): Behavior[Command] = {
    
    Behaviors.setup { ctx =>
      // 取出所有暂存消息
      val items = extractItems(stash)
      
      // 批量处理
      processBatch(items)
      
      // 返回收集状态
      collecting(stash, timers, batchSize)
    }
  }
}
```

---

## 总结

### 核心要点

**1. 背压理论**
- Little's Law：L = λ × W
- 过载：λ > μ
- 队列长度指数增长

**2. 流控算法**
- Token Bucket：允许突发
- Leaky Bucket：恒定速率
- Sliding Window：时间窗口

**3. Reactive Streams**
- Publisher/Subscriber
- request(n)实现背压
- 异步流标准

**4. Actor背压**
- Bounded Mailbox
- Work Pulling模式
- 阻塞/拒绝/丢弃策略

**5. Pekko Streams**
- 自动背压
- 异步边界
- Buffer/Throttle

### 策略选择

```
场景              推荐策略
─────────────────────────────
内部组件           背压
外部API           限流
监控日志           丢弃
临时突发           缓冲
持续过载           降级/扩容
```

### 最佳实践

```
✓ 始终考虑背压
✓ 设置合理容量
✓ 监控队列长度
✓ 快速失败
✓ 优雅降级

❌ 无限队列
❌ 忽略过载
❌ 盲目扩容
```

### 下一篇预告

**第六部分：分布式模式**即将开始！

**《CQRS与Event Sourcing深度解析》**
- CQRS模式原理
- Event Sourcing实现
- Read Model投影
- 最终一致性保证

---

**项目地址**: https://github.com/Xuxiaotuan/pekko-reference

**作者**: Xuxiaotuan  
**日期**: 2024年11月
