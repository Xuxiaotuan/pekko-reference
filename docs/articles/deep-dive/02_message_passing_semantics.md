# 消息传递语义与顺序保证

> **深度分析系列** - 第二篇：从理论到实践的消息语义

---

## 📋 目录

- [引言](#引言)
- [三种消息传递语义](#三种消息传递语义)
- [消息顺序保证](#消息顺序保证)
- [因果一致性与向量时钟](#因果一致性与向量时钟)
- [Pekko的实现机制](#pekko的实现机制)
- [消息去重与幂等性](#消息去重与幂等性)
- [实战案例](#实战案例)
- [总结](#总结)

---

## 引言

在分布式系统中，**消息传递的可靠性**是最基础也是最复杂的问题。

```
Actor A → Network → Actor B

可能发生什么？
❓ 消息丢失（网络故障）
❓ 消息重复（重试机制）
❓ 消息乱序（并发+延迟）
```

---

## 三种消息传递语义

### 理论基础

| 语义 | 定义 | 特点 |
|-----|------|------|
| **At-most-once** | 最多一次 | 可能丢失，不重复 |
| **At-least-once** | 至少一次 | 不会丢失，可能重复 |
| **Exactly-once** | 恰好一次 | 不丢失，不重复 |

### At-most-once实现

```scala
// Pekko默认语义
receiver ! "hello"  // 发送后立即返回，不等待确认
```

### At-least-once实现

```scala
object ReliableSender {
  def apply(receiver: ActorRef[Message]): Behavior[Command] = {
    Behaviors.withTimers { timers =>
      Behaviors.receiveMessage {
        case Send(msg) =>
          val msgId = UUID.randomUUID()
          receiver ! Message(msgId, msg, ctx.self)
          timers.startSingleTimer(RetryTimeout, 5.seconds)
          waitingAck(msgId, msg, receiver, timers, retryCount = 0)
      }
    }
  }
  
  private def waitingAck(
    msgId: UUID,
    msg: String,
    receiver: ActorRef[Message],
    timers: TimerScheduler[Command],
    retryCount: Int
  ): Behavior[Command] = {
    Behaviors.receiveMessage {
      case Ack(ackId) if ackId == msgId =>
        timers.cancel(RetryTimeout)
        idle(receiver, timers)
      
      case RetryTimeout if retryCount < 3 =>
        receiver ! Message(msgId, msg, ctx.self)
        timers.startSingleTimer(RetryTimeout, 5.seconds)
        waitingAck(msgId, msg, receiver, timers, retryCount + 1)
    }
  }
}
```

### Exactly-once的真相

```
Exactly-once = At-least-once + Idempotent processing

真相：在异步网络中，Exactly-once是不可能的（FLP定理）

实际实现：
发送方：保证至少送达一次
接收方：幂等处理（多次执行=一次执行）
```

---

## 消息顺序保证

### FIFO顺序

**定义**：从同一发送方到同一接收方的消息，按发送顺序接收。

```scala
// Pekko保证FIFO
sender ! msg1
sender ! msg2
sender ! msg3
// 接收顺序：msg1 → msg2 → msg3
```

### 因果顺序

**定义**：如果m1 happens-before m2，那么所有Actor都先看到m1再看到m2。

```
Lamport的happens-before关系：

1. 程序顺序：a → b（同一Actor内）
2. 消息传递：send(m) → receive(m)
3. 传递性：a → b ∧ b → c ⇒ a → c
```

### 全局顺序

**实现方式**：
- 中心化排序（Single leader）
- Paxos/Raft共识
- Lamport timestamp

**代价**：性能低、可用性差

---

## 因果一致性与向量时钟

### 向量时钟实现

```scala
case class VectorClock(clocks: Map[ActorId, Int]) {
  
  def increment(actorId: ActorId): VectorClock = {
    val newValue = clocks.getOrElse(actorId, 0) + 1
    VectorClock(clocks + (actorId -> newValue))
  }
  
  def merge(other: VectorClock): VectorClock = {
    val allKeys = clocks.keySet ++ other.clocks.keySet
    val merged = allKeys.map { key =>
      key -> math.max(
        clocks.getOrElse(key, 0),
        other.clocks.getOrElse(key, 0)
      )
    }.toMap
    VectorClock(merged)
  }
  
  def happensBefore(other: VectorClock): Boolean = {
    clocks.forall { case (actor, time) =>
      other.clocks.getOrElse(actor, 0) >= time
    } && clocks != other.clocks
  }
}
```

---

## Pekko的实现机制

### Mailbox实现

```scala
// MPSC队列（Multiple Producer, Single Consumer）
class UnboundedMailbox extends Mailbox {
  private val queue = new ConcurrentLinkedQueue[Envelope]()
  
  def enqueue(msg: Envelope): Unit = {
    queue.offer(msg)  // CAS操作，无锁
  }
  
  def dequeue(): Envelope = {
    queue.poll()  // 只有Actor线程调用
  }
}
```

### Dispatcher调度

```scala
class ActorCell {
  def invoke(msg: Envelope): Unit = {
    var messageCount = 0
    val throughput = 5  // 每次处理5条消息
    
    while (messageCount < throughput && mailbox.hasMessages) {
      val envelope = mailbox.dequeue()
      currentBehavior = processSingleMessage(envelope)
      messageCount += 1
    }
    
    if (mailbox.hasMessages) {
      dispatcher.dispatch(this, null)  // 重新调度
    }
  }
}
```

---

## 消息去重与幂等性

### 方案1：消息ID

```scala
object IdempotentActor {
  def apply(): Behavior[Message] = {
    process(Set.empty)
  }
  
  private def process(processedIds: Set[String]): Behavior[Message] = {
    Behaviors.receive { (ctx, msg) =>
      if (processedIds.contains(msg.id)) {
        ctx.log.debug(s"Duplicate: ${msg.id}")
        Behaviors.same
      } else {
        handleMessage(msg)
        process(processedIds + msg.id)
      }
    }
  }
}
```

### 方案2：数据库唯一约束

```scala
db.run(
  sqlu"""
    INSERT INTO orders (id, amount)
    VALUES (${order.id}, ${order.amount})
    ON CONFLICT (id) DO NOTHING
  """
)
```

### 方案3：版本号

```scala
case class VersionedMessage(
  entityId: String,
  version: Long,
  command: Command
)

// 拒绝旧版本
if (msg.version <= currentVersion) {
  // 忽略
}
```

---

## 实战案例

### 可靠消息队列

```scala
object ReliableQueue {
  sealed trait Command
  case class Produce(msg: String, replyTo: ActorRef[Result]) extends Command
  case class Consume(replyTo: ActorRef[Option[String]]) extends Command
  
  def apply(): Behavior[Command] = {
    queue(nextId = 0, Queue.empty, Queue.empty)
  }
  
  private def queue(
    nextId: Long,
    messages: Queue[Message],
    consumers: Queue[ActorRef[Option[String]]]
  ): Behavior[Command] = {
    Behaviors.receive { (ctx, cmd) =>
      cmd match {
        case Produce(msg, replyTo) =>
          consumers.headOption match {
            case Some(consumer) =>
              consumer ! Some(msg)
              replyTo ! Success(nextId)
              queue(nextId + 1, messages, consumers.tail)
            
            case None =>
              replyTo ! Success(nextId)
              queue(nextId + 1, messages.enqueue(msg), consumers)
          }
        
        case Consume(replyTo) =>
          messages.dequeueOption match {
            case Some((message, remaining)) =>
              replyTo ! Some(message)
              queue(nextId, remaining, consumers)
            
            case None =>
              queue(nextId, messages, consumers.enqueue(replyTo))
          }
      }
    }
  }
}
```

---

## 总结

### 核心要点

**1. 消息语义选择**
- At-most-once：日志、监控
- At-least-once：订单、支付
- Exactly-once：幂等处理

**2. 顺序保证**
- FIFO：同一发送者
- Causal：向量时钟
- Total：共识算法

**3. Pekko实现**
- 默认At-most-once
- Mailbox + Dispatcher
- 本地FIFO保证

**4. 最佳实践**
- 设计幂等操作
- 使用消息ID去重
- 合理选择语义

### 下一篇

**《Actor并发模型vs传统并发模型》**
- 共享内存的问题
- Actor如何避免死锁
- 性能对比分析

---

**项目地址**: https://github.com/Xuxiaotuan/pekko-reference

**作者**: Xuxiaotuan  
**日期**: 2024年11月
