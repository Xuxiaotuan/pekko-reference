# 消息发送与处理的完整链路

> **深度分析系列** - 第五篇：从`!`到Actor处理的完整源码追踪

---

## 📋 目录

- [引言](#引言)
- [消息发送操作符](#消息发送操作符)
- [ActorRef内部结构](#actorref内部结构)
- [Mailbox入队机制](#mailbox入队机制)
- [Dispatcher调度算法](#dispatcher调度算法)
- [Actor消息处理循环](#actor消息处理循环)
- [背压机制](#背压机制)
- [性能优化](#性能优化)
- [总结](#总结)

---

## 引言

一条消息从发送到处理，经历了什么？

```scala
sender ! msg  // 看似简单的一行

背后的完整链路：
sender.tell(msg)
  → ActorRef查找
  → Mailbox.enqueue()  // CAS入队
  → Dispatcher.schedule()  // 调度
  → Mailbox.run()  // 批量处理
  → Actor.invoke(msg)  // 处理消息
```

本文将深入源码，追踪这条链路的每一步。

---

## 消息发送操作符

### `!` vs `tell` vs `?` vs `ask`

```scala
// 1. ! 操作符（fire-and-forget）
actorRef ! msg

// 等价于
actorRef.tell(msg)

// 2. ? 操作符（request-response）
implicit val timeout: Timeout = 3.seconds
val future: Future[Response] = actorRef ? Request

// 等价于
actorRef.ask(Request)(timeout)

// 3. tell with sender
actorRef.tell(msg, sender)
```

### `!` 源码实现

```scala
// ActorRef.scala
trait ActorRef[-T] {
  
  // ! 操作符
  def !(msg: T): Unit = tell(msg)
  
  // tell方法
  def tell(msg: T): Unit
}

// LocalActorRef.scala（本地Actor引用）
class LocalActorRef[-T](
  path: ActorPath,
  system: ActorSystemImpl
) extends ActorRef[T] {
  
  def tell(msg: T): Unit = {
    // 1. 检查消息类型
    if (msg == null) {
      throw InvalidMessageException("Message must not be null")
    }
    
    // 2. 获取ActorCell
    val cell = underlying
    
    if (cell eq null) {
      // Actor已停止，发送到DeadLetters
      system.deadLetters ! DeadLetter(msg, ActorRef.noSender, this)
    } else {
      // 3. 发送消息
      cell.sendMessage(Envelope(msg, ActorRef.noSender, system))
    }
  }
}
```

### `?` (ask) 源码实现

```scala
// AskPattern.scala
object AskPattern {
  
  implicit class AskableActorRef[-T](val ref: ActorRef[T]) extends AnyVal {
    
    def ?[U](message: ActorRef[U] => T)(implicit timeout: Timeout): Future[U] = {
      ask(ref, message, timeout)
    }
  }
  
  def ask[T, U](
    ref: ActorRef[T],
    createMessage: ActorRef[U] => T,
    timeout: Timeout
  ): Future[U] = {
    
    // 1. 创建临时Actor作为响应接收者
    val promiseRef = PromiseActorRef[U](ref.path.root, timeout)
    
    // 2. 构造消息
    val message = createMessage(promiseRef)
    
    // 3. 发送消息
    ref.tell(message)
    
    // 4. 返回Future
    promiseRef.result.future
  }
}

// PromiseActorRef：临时Actor，只接收一次响应
class PromiseActorRef[T](
  override val path: ActorPath,
  timeout: Timeout
) extends ActorRef[T] {
  
  val result = Promise[T]()
  
  // 设置超时
  system.scheduler.scheduleOnce(timeout.duration) {
    result.tryFailure(AskTimeoutException(s"Ask timed out after $timeout"))
  }
  
  def tell(msg: T): Unit = {
    result.trySuccess(msg)
  }
}
```

---

## ActorRef内部结构

### ActorRef类型层级

```scala
// ActorRef类型层级
trait ActorRef[-T]
  ├─ LocalActorRef[T]        // 本地Actor引用
  ├─ RemoteActorRef[T]       // 远程Actor引用
  ├─ RepointableActorRef[T]  // 可重定向引用
  └─ InternalActorRef        // 内部引用
```

### LocalActorRef

```scala
// LocalActorRef.scala
class LocalActorRef[-T](
  val path: ActorPath,
  val system: ActorSystemImpl
) extends ActorRef[T] {
  
  // ActorCell：Actor的底层实现
  @volatile private var _cell: ActorCell = _
  
  def underlying: ActorCell = _cell
  
  // 初始化
  def initialize(cell: ActorCell): Unit = {
    _cell = cell
  }
  
  // 发送消息
  def tell(msg: T): Unit = {
    val cell = underlying
    if (cell ne null) {
      cell.sendMessage(Envelope(msg, ActorRef.noSender, system))
    } else {
      system.deadLetters ! DeadLetter(msg, ActorRef.noSender, this)
    }
  }
  
  // 比较
  override def equals(other: Any): Boolean = other match {
    case that: LocalActorRef[_] => this.path == that.path
    case _ => false
  }
  
  override def hashCode(): Int = path.hashCode()
}
```

### ActorCell

```scala
// ActorCell.scala
class ActorCell(
  val system: ActorSystemImpl,
  val self: InternalActorRef,
  val props: Props,
  val dispatcher: MessageDispatcher,
  val parent: InternalActorRef
) {
  
  // Mailbox
  private val mailbox: Mailbox = dispatcher.createMailbox(this, props)
  
  // 当前Actor实例
  @volatile private var actor: Actor = _
  
  // 当前Behavior
  @volatile private var behavior: Behavior[_] = _
  
  // 发送消息
  def sendMessage(msg: Envelope): Unit = {
    mailbox.enqueue(self, msg)
    dispatcher.dispatch(this, msg)
  }
  
  // 处理消息
  def invoke(msg: Envelope): Unit = {
    val currentBehavior = behavior
    
    try {
      // 应用Behavior
      val next = Behavior.interpretMessage(currentBehavior, this, msg)
      behavior = next
    } catch {
      case NonFatal(e) =>
        handleException(e, msg)
    }
  }
}
```

---

## Mailbox入队机制

### Mailbox接口

```scala
// Mailbox.scala
abstract class Mailbox(val messageQueue: MessageQueue) extends Runnable {
  
  // 入队
  def enqueue(receiver: ActorRef, msg: Envelope): Unit
  
  // 出队
  def dequeue(): Envelope
  
  // 是否有消息
  def hasMessages: Boolean
  
  // 消息数量
  def numberOfMessages: Int
  
  // 运行（处理消息）
  def run(): Unit
  
  // Actor引用
  var actor: ActorCell = _
  
  // Dispatcher
  var dispatcher: MessageDispatcher = _
  
  // 状态标志
  private val status = new AtomicInteger(Open)
  
  // 状态常量
  final val Open = 0
  final val Scheduled = 1
  final val Closed = 2
}
```

### UnboundedMailbox入队

```scala
// UnboundedMailbox.scala
class UnboundedMailbox extends Mailbox {
  
  // MPSC队列（JCTools）
  private val queue = new MpscUnboundedArrayQueue[Envelope](InitialCapacity)
  
  def enqueue(receiver: ActorRef, msg: Envelope): Unit = {
    // CAS入队（无锁）
    if (queue.offer(msg)) {
      // 入队成功
    } else {
      // 队列满（不应该发生在Unbounded）
      throw new IllegalStateException("Unbounded queue is full")
    }
  }
  
  def dequeue(): Envelope = {
    queue.poll()  // 只有Actor线程调用，无竞争
  }
  
  def hasMessages: Boolean = {
    !queue.isEmpty
  }
  
  def numberOfMessages: Int = {
    queue.size()
  }
}
```

### MPSC队列原理

**MPSC = Multiple Producer, Single Consumer**

```
多个线程可以并发入队（Producer）
只有一个线程出队（Consumer）

入队：CAS操作
  Thread 1 ─┐
  Thread 2 ─┼─→ offer(msg) → CAS → 入队
  Thread 3 ─┘

出队：无锁
  Actor Thread → poll() → 出队（无竞争）

优势：
✓ 无锁入队（CAS）
✓ 无锁出队（单消费者）
✓ 高性能
```

### CAS入队实现

```java
// MpscUnboundedArrayQueue.java（JCTools）
public boolean offer(E e) {
    if (e == null) {
        throw new NullPointerException();
    }
    
    // 1. 获取生产者索引
    long producerIndex = lvProducerIndex();
    long offset = calcElementOffset(producerIndex);
    
    // 2. 写入元素
    soElement(buffer, offset, e);
    
    // 3. CAS更新索引
    while (!casProducerIndex(producerIndex, producerIndex + 1)) {
        producerIndex = lvProducerIndex();
    }
    
    return true;
}

// Unsafe操作
private static final Unsafe UNSAFE = ...;
private static final long PRODUCER_INDEX_OFFSET = ...;

private boolean casProducerIndex(long expect, long update) {
    return UNSAFE.compareAndSwapLong(
        this,
        PRODUCER_INDEX_OFFSET,
        expect,
        update
    );
}
```

---

## Dispatcher调度算法

### 调度流程

```scala
// Dispatcher.scala
class Dispatcher extends MessageDispatcher {
  
  // ForkJoinPool
  private val executorService: ExecutorService = ...
  
  def dispatch(receiver: ActorCell, msg: Envelope): Unit = {
    val mbox = receiver.mailbox
    
    // 尝试调度
    if (mbox.canBeScheduledForExecution(
      hasMessageHint = true,
      hasSystemMessageHint = false
    )) {
      if (mbox.setAsScheduled()) {
        try {
          // 提交到线程池
          executorService.execute(mbox)
        } catch {
          case e: RejectedExecutionException =>
            mbox.setAsIdle()
            receiver.system.eventStream.publish(
              Error(e, receiver.self.path.toString, classOf[Dispatcher], e.getMessage)
            )
            throw e
        }
      }
    }
  }
}
```

### Mailbox状态转换

```scala
// Mailbox状态机
sealed trait MailboxStatus
case object Open extends MailboxStatus       // 空闲
case object Scheduled extends MailboxStatus  // 已调度
case object Closed extends MailboxStatus     // 已关闭

// 状态转换
def setAsScheduled(): Boolean = {
  val current = status.get()
  
  current match {
    case Open =>
      // Open → Scheduled
      status.compareAndSet(Open, Scheduled)
    
    case Scheduled =>
      // 已经调度，跳过
      false
    
    case Closed =>
      // 已关闭，不能调度
      false
  }
}

def setAsIdle(): Unit = {
  // Scheduled → Open
  status.set(Open)
}
```

### ForkJoinPool work-stealing

```
ForkJoinPool的work-stealing机制：

Thread 1的队列：[Task1, Task2, Task3, Task4]
Thread 2的队列：[Task5]
Thread 3的队列：[]（空闲）

Thread 3会"偷"Thread 1的Task4：
Thread 1的队列：[Task1, Task2, Task3]
Thread 3的队列：[Task4]

优势：
✓ 负载均衡
✓ 提高吞吐量
✓ 减少线程空闲

Pekko中：
- 每个Mailbox是一个Task
- ForkJoinPool自动平衡负载
```

---

## Actor消息处理循环

### Mailbox.run()

```scala
// Mailbox.scala
abstract class Mailbox extends Runnable {
  
  def run(): Unit = {
    try {
      // 处理throughput条消息
      processMailbox()
    } catch {
      case NonFatal(e) =>
        actor.system.eventStream.publish(
          Error(e, actor.self.path.toString, actor.getClass, e.getMessage)
        )
    } finally {
      setAsIdle()
      // 如果还有消息，重新调度
      dispatcher.registerForExecution(this, hasMessages, false)
    }
  }
  
  private def processMailbox(
    left: Int = dispatcher.throughput,
    deadlineNs: Long = if (dispatcher.isThroughputDeadlineTimeDefined)
      System.nanoTime + dispatcher.throughputDeadlineTime.toNanos
    else 0L
  ): Unit = {
    
    // 批量处理消息
    while (shouldProcessMessage(left, deadlineNs)) {
      val next = dequeue()
      
      if (next ne null) {
        // 处理消息
        actor.invoke(next)
        left -= 1
      }
    }
  }
  
  private def shouldProcessMessage(left: Int, deadlineNs: Long): Boolean = {
    if (left > 0 && hasMessages) {
      if (deadlineNs > 0) {
        // 检查是否超过deadline
        System.nanoTime < deadlineNs
      } else {
        true
      }
    } else {
      false
    }
  }
}
```

### Actor.invoke()

```scala
// ActorCell.scala
class ActorCell {
  
  def invoke(msg: Envelope): Unit = {
    val currentBehavior = behavior
    
    // 记录消息处理开始
    val start = if (system.settings.DebugLifecycle) System.nanoTime() else 0L
    
    try {
      // 解释消息
      val next = Behavior.interpretMessage(currentBehavior, this, msg)
      
      // 更新Behavior
      if (!Behavior.isUnhandled(next) && !Behavior.same(next, currentBehavior)) {
        behavior = Behavior.canonicalize(next, currentBehavior, this)
      }
      
    } catch {
      case NonFatal(e) =>
        // 处理异常
        handleException(e, msg)
    } finally {
      // 记录消息处理结束
      if (system.settings.DebugLifecycle) {
        val duration = System.nanoTime() - start
        if (duration > 1000000) {  // > 1ms
          system.log.warning(
            s"Message processing took ${duration / 1000000}ms: ${msg.message}"
          )
        }
      }
    }
  }
}
```

### Behavior.interpretMessage()

```scala
// BehaviorImpl.scala
object Behavior {
  
  def interpretMessage[T](
    behavior: Behavior[T],
    ctx: ActorContext[T],
    msg: Any
  ): Behavior[T] = {
    
    behavior match {
      case r: Receive[T] =>
        // 函数式Behavior
        r.onMessage(ctx, msg.asInstanceOf[T])
      
      case d: DeferredBehavior[T] =>
        // 延迟创建的Behavior
        val concrete = d.apply(ctx)
        interpretMessage(concrete, ctx, msg)
      
      case i: Interceptor[T, T] =>
        // 拦截器Behavior
        val intercepted = i.aroundReceive(ctx, msg.asInstanceOf[T], i.nestedBehavior)
        interpretMessage(intercepted, ctx, msg)
      
      case _ =>
        // 其他类型
        Behaviors.unhandled
    }
  }
}
```

---

## 背压机制

### Mailbox溢出处理

```scala
// BoundedMailbox.scala
class BoundedMailbox(
  capacity: Int,
  pushTimeout: Duration
) extends Mailbox {
  
  private val queue = new ArrayBlockingQueue[Envelope](capacity)
  
  def enqueue(receiver: ActorRef, msg: Envelope): Unit = {
    if (pushTimeout.isFinite) {
      // 超时入队
      if (!queue.offer(msg, pushTimeout.length, pushTimeout.unit)) {
        // 队列满，触发背压
        handleOverflow(receiver, msg)
      }
    } else {
      // 阻塞入队（背压发送者）
      queue.put(msg)
    }
  }
  
  private def handleOverflow(receiver: ActorRef, msg: Envelope): Unit = {
    // 策略1：丢弃消息
    receiver.system.deadLetters ! DeadLetter(msg.message, msg.sender, receiver)
    
    // 策略2：抛出异常
    // throw new MailboxOverflowException(...)
    
    // 策略3：阻塞发送者（已在put()中实现）
  }
}
```

### 背压策略

```hocon
# 配置背压策略
pekko.actor.mailbox {
  bounded-mailbox {
    mailbox-type = "org.apache.pekko.dispatch.BoundedMailbox"
    mailbox-capacity = 1000
    mailbox-push-timeout-time = 10s
  }
  
  dropping-mailbox {
    mailbox-type = "org.apache.pekko.dispatch.BoundedMailbox"
    mailbox-capacity = 1000
    mailbox-push-timeout-time = 0s  # 立即丢弃
  }
}
```

### Stash机制

```scala
// Stash：暂存消息
trait StashBuffer[T] {
  def stash(msg: T): Unit
  def unstashAll(): Behavior[T]
}

object StashActor {
  
  sealed trait Command
  case class Message(content: String) extends Command
  case object Process extends Command
  
  def apply(): Behavior[Command] = {
    Behaviors.withStash(100) { stashBuffer =>
      
      idle(stashBuffer)
    }
  }
  
  private def idle(stash: StashBuffer[Command]): Behavior[Command] = {
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case Message(content) =>
          // 暂存消息
          stash.stash(msg)
          Behaviors.same
        
        case Process =>
          // 处理所有暂存的消息
          stash.unstashAll(processing())
      }
    }
  }
  
  private def processing(): Behavior[Command] = {
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case Message(content) =>
          // 处理消息
          ctx.log.info(s"Processing: $content")
          Behaviors.same
        
        case Process =>
          Behaviors.same
      }
    }
  }
}
```

---

## 性能优化

### 批量处理（throughput）

```hocon
# 每次处理多少条消息
pekko.actor.default-dispatcher {
  throughput = 5  # 默认5条
}

# CPU密集型任务
cpu-dispatcher {
  throughput = 10  # 更大的批次
}

# IO密集型任务
io-dispatcher {
  throughput = 1  # 更快的响应
}
```

**原理**：
```
throughput = 1:
  取1条消息 → 处理 → 切换线程 → 取1条...
  优势：响应快
  劣势：线程切换频繁

throughput = 10:
  取10条消息 → 处理10条 → 切换线程
  优势：减少线程切换
  劣势：响应慢
```

### 消息对象池

```scala
// 对象池减少GC
object MessagePool {
  
  private val pool = new ConcurrentLinkedQueue[Message]()
  private val maxPoolSize = 1000
  
  def acquire(content: String): Message = {
    val msg = pool.poll()
    if (msg ne null) {
      msg.content = content
      msg
    } else {
      new Message(content)
    }
  }
  
  def release(msg: Message): Unit = {
    if (pool.size() < maxPoolSize) {
      msg.content = null
      pool.offer(msg)
    }
  }
}
```

### 消息批量发送

```scala
// 批量发送减少系统调用
object BatchSender {
  
  def sendBatch[T](
    receiver: ActorRef[T],
    messages: Seq[T]
  ): Unit = {
    messages.foreach(msg => receiver ! msg)
  }
  
  // 更高效：直接操作Mailbox
  def sendBatchOptimized[T](
    receiver: LocalActorRef[T],
    messages: Seq[T]
  ): Unit = {
    val cell = receiver.underlying
    val mailbox = cell.mailbox
    
    // 批量入队
    messages.foreach { msg =>
      mailbox.enqueue(receiver, Envelope(msg, ActorRef.noSender, cell.system))
    }
    
    // 只调度一次
    cell.dispatcher.dispatch(cell, null)
  }
}
```

---

## 总结

### 完整链路回顾

```
1. sender ! msg
   ↓
2. ActorRef.tell(msg)
   ↓
3. ActorCell.sendMessage(Envelope(msg))
   ↓
4. Mailbox.enqueue(msg)  // CAS无锁入队
   ↓
5. Dispatcher.dispatch()  // 调度
   ↓
6. Mailbox.setAsScheduled()  // CAS状态转换
   ↓
7. ExecutorService.execute(mailbox)  // 提交线程池
   ↓
8. Mailbox.run()  // 批量处理
   ↓
9. Mailbox.dequeue()  // 取出消息
   ↓
10. Actor.invoke(msg)  // 处理消息
   ↓
11. Behavior.interpretMessage()  // 解释消息
   ↓
12. 用户代码执行
```

### 性能关键点

| 组件 | 优化技术 | 性能收益 |
|-----|---------|---------|
| **Mailbox** | MPSC无锁队列 | 高并发入队 |
| **Dispatcher** | ForkJoinPool | work-stealing |
| **批处理** | throughput | 减少线程切换 |
| **状态转换** | CAS原子操作 | 无锁调度 |

### 下一篇预告

**《Behavior切换的魔法：如何实现状态机》**
- Behavior的类型系统
- 状态切换的底层实现
- BehaviorInterceptor机制
- 性能开销分析

---

**项目地址**: https://github.com/Xuxiaotuan/pekko-reference

**作者**: Xuxiaotuan  
**日期**: 2024年11月
