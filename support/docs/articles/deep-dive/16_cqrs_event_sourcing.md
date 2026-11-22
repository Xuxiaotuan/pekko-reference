# CQRS与Event Sourcing深度解析

> **深度分析系列** - 第十六篇：深入命令查询职责分离与事件溯源模式

---

## 📋 目录

- [引言](#引言)
- [CQRS模式](#cqrs模式)
- [Event Sourcing](#event-sourcing)
- [Read Model投影](#read-model投影)
- [快照优化](#快照优化)
- [事件版本控制](#事件版本控制)
- [最终一致性](#最终一致性)
- [实战案例](#实战案例)
- [总结](#总结)

---

## 引言

传统CRUD的局限性：

```
传统方式：
User {
  id: 123
  name: "Alice"
  balance: 1000
}

UPDATE users SET balance = 900 WHERE id = 123

问题：
❌ 历史丢失（不知道为什么变成900）
❌ 审计困难
❌ 无法回溯
❌ 读写冲突

CQRS + Event Sourcing：
Event: UserBalanceChanged(userId=123, amount=-100, reason="购买商品")
Event: UserBalanceChanged(userId=123, amount=+50, reason="退款")

优势：
✓ 完整历史
✓ 审计友好
✓ 可回溯
✓ 读写分离
```

---

## CQRS模式

### 核心概念

**CQRS = Command Query Responsibility Segregation**

```
传统架构：
Client → Service → Database
         ↓     ↑
       Write  Read

CQRS架构：
Client → Command Service → Write Model (Event Store)
                              ↓
                            Events
                              ↓
Client ← Query Service ← Read Model (Projection)

分离：
- Command：修改状态（写）
- Query：查询状态（读）
```

### 为什么需要CQRS

```
读写特性差异：

写操作：
- 需要验证
- 需要事务
- 低频率
- 强一致性

读操作：
- 无需验证
- 无需事务
- 高频率
- 可最终一致

问题：
同一个模型 → 冲突
- 写优化 ↔ 读优化
- 规范化 ↔ 反规范化

解决：分离模型
```

### CQRS架构

```scala
// Command Side（写端）
object BankAccountCommandHandler {
  
  sealed trait Command
  case class OpenAccount(accountId: String, initialBalance: BigDecimal) extends Command
  case class Deposit(amount: BigDecimal) extends Command
  case class Withdraw(amount: BigDecimal) extends Command
  
  sealed trait Event
  case class AccountOpened(accountId: String, initialBalance: BigDecimal) extends Event
  case class MoneyDeposited(amount: BigDecimal) extends Event
  case class MoneyWithdrawn(amount: BigDecimal) extends Event
  
  def apply(accountId: String): EventSourcedBehavior[Command, Event, State] = {
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId("BankAccount", accountId),
      emptyState = State.empty,
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
  }
  
  private def commandHandler: (State, Command) => Effect[Event, State] = {
    case (state, OpenAccount(accountId, initialBalance)) =>
      if (state.isOpen) {
        Effect.none  // 已开户
      } else {
        Effect.persist(AccountOpened(accountId, initialBalance))
      }
    
    case (state, Deposit(amount)) =>
      if (amount <= 0) {
        Effect.none  // 金额无效
      } else {
        Effect.persist(MoneyDeposited(amount))
      }
    
    case (state, Withdraw(amount)) =>
      if (amount > state.balance) {
        Effect.none  // 余额不足
      } else {
        Effect.persist(MoneyWithdrawn(amount))
      }
  }
  
  private def eventHandler: (State, Event) => State = {
    case (state, AccountOpened(accountId, initialBalance)) =>
      state.copy(
        accountId = accountId,
        balance = initialBalance,
        isOpen = true
      )
    
    case (state, MoneyDeposited(amount)) =>
      state.copy(balance = state.balance + amount)
    
    case (state, MoneyWithdrawn(amount)) =>
      state.copy(balance = state.balance - amount)
  }
  
  case class State(
    accountId: String,
    balance: BigDecimal,
    isOpen: Boolean
  )
  
  object State {
    val empty = State("", BigDecimal(0), false)
  }
}

// Query Side（读端）
object BankAccountQueryHandler {
  
  case class AccountView(
    accountId: String,
    balance: BigDecimal,
    transactions: List[Transaction],
    lastUpdated: Instant
  )
  
  case class Transaction(
    timestamp: Instant,
    `type`: String,
    amount: BigDecimal,
    balance: BigDecimal
  )
  
  // 从Read Model查询
  def getAccount(accountId: String): Future[Option[AccountView]] = {
    readModelRepository.findById(accountId)
  }
  
  def getTransactionHistory(accountId: String): Future[List[Transaction]] = {
    readModelRepository.findTransactions(accountId)
  }
}
```

---

## Event Sourcing

### 核心原理

**Event Sourcing**：存储状态变化而非最终状态

```
传统存储（状态）：
User(id=1, name="Alice", balance=1000)

Event Sourcing（事件）：
1. UserCreated(id=1, name="Alice", balance=1000)
2. MoneyDeposited(id=1, amount=500)
3. MoneyWithdrawn(id=1, amount=300)
4. MoneyWithdrawn(id=1, amount=200)

当前状态 = 重放所有事件
balance = 1000 + 500 - 300 - 200 = 1000
```

### 事件存储

```scala
// Event Journal（事件日志）
trait EventJournal {
  
  // 持久化事件
  def persist(
    persistenceId: String,
    event: Any,
    sequenceNr: Long
  ): Future[Unit]
  
  // 读取事件
  def readEvents(
    persistenceId: String,
    fromSequenceNr: Long,
    toSequenceNr: Long
  ): Source[EventEnvelope, NotUsed]
  
  // 删除事件（仅用于GDPR等法规要求）
  def deleteEvents(
    persistenceId: String,
    toSequenceNr: Long
  ): Future[Unit]
}

// Event Envelope
case class EventEnvelope(
  persistenceId: String,
  sequenceNr: Long,
  event: Any,
  timestamp: Long
)
```

### 事件重放

```scala
// 状态重建
def recoverState(
  persistenceId: String,
  eventHandler: (State, Event) => State
): Future[State] = {
  
  eventJournal
    .readEvents(persistenceId, fromSequenceNr = 1L, toSequenceNr = Long.MaxValue)
    .runFold(State.empty) { (state, envelope) =>
      val event = envelope.event.asInstanceOf[Event]
      eventHandler(state, event)
    }
}

// 示例
val finalState = recoverState("BankAccount-123", eventHandler)
// 重放1000个事件 → 得到最终状态
```

---

## Read Model投影

### Projection概念

**Projection**：从Event Stream构建Read Model

```
Event Stream:
AccountOpened(id=1, balance=1000)
MoneyDeposited(id=1, amount=500)
MoneyWithdrawn(id=1, amount=300)
    ↓
Projection
    ↓
Read Model:
AccountView(id=1, balance=1200, transactions=[...])
```

### 实现Projection

```scala
object AccountProjection {
  
  def apply(): Behavior[ProjectionCommand] = {
    Behaviors.setup { ctx =>
      
      // 订阅事件
      val eventStream = EventStream.subscribe("BankAccount")
      
      eventStream.runForeach { envelope =>
        val event = envelope.event.asInstanceOf[BankAccountEvent]
        
        event match {
          case AccountOpened(accountId, initialBalance) =>
            // 插入Read Model
            readModelRepository.insert(AccountView(
              accountId = accountId,
              balance = initialBalance,
              transactions = List.empty,
              lastUpdated = Instant.now()
            ))
          
          case MoneyDeposited(accountId, amount) =>
            // 更新Read Model
            val current = readModelRepository.findById(accountId)
            readModelRepository.update(current.copy(
              balance = current.balance + amount,
              transactions = current.transactions :+ Transaction(
                timestamp = Instant.now(),
                `type` = "Deposit",
                amount = amount,
                balance = current.balance + amount
              )
            ))
          
          case MoneyWithdrawn(accountId, amount) =>
            // 更新Read Model
            val current = readModelRepository.findById(accountId)
            readModelRepository.update(current.copy(
              balance = current.balance - amount,
              transactions = current.transactions :+ Transaction(
                timestamp = Instant.now(),
                `type` = "Withdraw",
                amount = amount,
                balance = current.balance - amount
              )
            ))
        }
      }
      
      Behaviors.empty
    }
  }
}
```

### 多个Read Model

```
同一个Event Stream可以构建多个Read Model：

Event Stream
    ↓
    ├→ Projection 1 → AccountSummaryView（余额摘要）
    ├→ Projection 2 → TransactionHistoryView（交易历史）
    ├→ Projection 3 → AuditLogView（审计日志）
    └→ Projection 4 → AnalyticsView（分析报表）

优势：
- 针对不同查询优化
- 独立扩展
- 故障隔离
```

---

## 快照优化

### 为什么需要快照

```
问题：
1000万个事件 → 重放耗时10秒

解决：快照（Snapshot）
Event 1-999,999（跳过）
Snapshot at 1,000,000（加载快照）
Event 1,000,001-1,000,100（重放100个）

性能：
10秒 → 0.01秒
1000倍提升！
```

### 快照策略

```scala
// EventSourcedBehavior配置快照
EventSourcedBehavior[Command, Event, State](
  persistenceId = PersistenceId("BankAccount", accountId),
  emptyState = State.empty,
  commandHandler = commandHandler,
  eventHandler = eventHandler
)
.snapshotWhen {
  // 策略1：每N个事件
  case (state, event, sequenceNr) =>
    sequenceNr % 100 == 0  // 每100个事件一次快照
  
  // 策略2：基于时间
  case (state, event, sequenceNr) =>
    Duration.between(state.lastSnapshotTime, Instant.now()) > Duration.ofMinutes(10)
  
  // 策略3：基于大小
  case (state, event, sequenceNr) =>
    state.estimatedSize > 1024 * 1024  // 超过1MB
}
.withRetention(
  RetentionCriteria.snapshotEvery(
    numberOfEvents = 100,
    keepNSnapshots = 2  // 保留最近2个快照
  )
)
```

### 快照存储

```scala
trait SnapshotStore {
  
  // 保存快照
  def saveSnapshot(
    persistenceId: String,
    sequenceNr: Long,
    snapshot: Any
  ): Future[Unit]
  
  // 加载快照
  def loadSnapshot(
    persistenceId: String,
    maxSequenceNr: Long
  ): Future[Option[SnapshotMetadata]]
  
  // 删除快照
  def deleteSnapshot(
    persistenceId: String,
    sequenceNr: Long
  ): Future[Unit]
}

case class SnapshotMetadata(
  persistenceId: String,
  sequenceNr: Long,
  snapshot: Any,
  timestamp: Long
)
```

---

## 事件版本控制

### Schema Evolution

**问题**：事件结构演化

```scala
// V1：原始版本
case class UserCreated(
  userId: String,
  name: String
)

// V2：添加email字段
case class UserCreatedV2(
  userId: String,
  name: String,
  email: String
)

// 挑战：
// 历史事件是V1
// 新代码期望V2
// 如何兼容？
```

### 解决方案1：Upcasting

```scala
// 事件Upcaster
trait EventAdapter[E] {
  def toJournal(event: E): Any
  def fromJournal(event: Any, manifest: String): E
}

class UserEventAdapter extends EventAdapter[UserEvent] {
  
  def fromJournal(event: Any, manifest: String): UserEvent = {
    event match {
      // V1 → V2转换
      case UserCreated(userId, name) =>
        UserCreatedV2(
          userId = userId,
          name = name,
          email = s"$userId@example.com"  // 默认email
        )
      
      // V2直接返回
      case v2: UserCreatedV2 =>
        v2
    }
  }
  
  def toJournal(event: UserEvent): Any = {
    event  // 总是保存最新版本
  }
}
```

### 解决方案2：Versioned Events

```scala
// 事件版本化
sealed trait UserEvent {
  def version: Int
}

case class UserCreatedV1(
  userId: String,
  name: String
) extends UserEvent {
  val version = 1
}

case class UserCreatedV2(
  userId: String,
  name: String,
  email: String
) extends UserEvent {
  val version = 2
}

// Event Handler处理所有版本
private def eventHandler: (State, UserEvent) => State = {
  case (state, event: UserCreatedV1) =>
    // 处理V1
    state.copy(userId = event.userId, name = event.name)
  
  case (state, event: UserCreatedV2) =>
    // 处理V2
    state.copy(
      userId = event.userId,
      name = event.name,
      email = event.email
    )
}
```

### 解决方案3：Event Migration

```scala
// 批量迁移历史事件
def migrateEvents(): Future[Unit] = {
  eventJournal
    .readAllEvents()
    .mapAsync(10) { envelope =>
      envelope.event match {
        case UserCreated(userId, name) =>
          val newEvent = UserCreatedV2(
            userId, name, s"$userId@example.com"
          )
          eventJournal.updateEvent(
            envelope.persistenceId,
            envelope.sequenceNr,
            newEvent
          )
        
        case _ =>
          Future.successful(())
      }
    }
    .runWith(Sink.ignore)
}
```

---

## 最终一致性

### CAP定理

```
CAP定理：分布式系统只能满足两个

C (Consistency)    一致性
A (Availability)   可用性
P (Partition tolerance) 分区容错

CQRS选择：AP
- 写入立即返回（可用性）
- 读取最终一致（分区容错）
- 牺牲强一致性
```

### 最终一致性窗口

```
时间线：
t0: Command写入Event Store
t1: Event持久化完成
t2: Projection开始处理
t3: Read Model更新完成

不一致窗口：t1 - t3（通常<100ms）

Client读取：
t2.5: 查询Read Model → 可能读到旧数据

解决方案：
1. 接受最终一致
2. Read-your-writes一致性
3. 版本号机制
```

### Read-your-writes

```scala
// 确保读取自己的写入
object ConsistentRead {
  
  case class WriteToken(
    sequenceNr: Long,
    timestamp: Instant
  )
  
  // 写入返回token
  def write(command: Command): Future[WriteToken] = {
    commandHandler
      .handle(command)
      .map { sequenceNr =>
        WriteToken(sequenceNr, Instant.now())
      }
  }
  
  // 读取时检查token
  def read(
    accountId: String,
    token: Option[WriteToken]
  ): Future[AccountView] = {
    
    token match {
      case Some(WriteToken(seqNr, _)) =>
        // 等待Projection追上
        waitForSequenceNr(accountId, seqNr).flatMap { _ =>
          readModelRepository.findById(accountId)
        }
      
      case None =>
        // 直接读取
        readModelRepository.findById(accountId)
    }
  }
  
  private def waitForSequenceNr(
    accountId: String,
    targetSeqNr: Long
  ): Future[Unit] = {
    
    def check(): Future[Unit] = {
      projectionOffset.get(accountId).flatMap { currentSeqNr =>
        if (currentSeqNr >= targetSeqNr) {
          Future.successful(())
        } else {
          // 等待100ms后重试
          after(100.millis, system.scheduler)(check())
        }
      }
    }
    
    check()
  }
}
```

---

## 实战案例

### 案例1：订单系统

```scala
object OrderAggregate {
  
  sealed trait Command
  case class CreateOrder(orderId: String, items: List[OrderItem]) extends Command
  case class AddItem(item: OrderItem) extends Command
  case class RemoveItem(itemId: String) extends Command
  case class SubmitOrder() extends Command
  case class CancelOrder() extends Command
  
  sealed trait Event
  case class OrderCreated(orderId: String, items: List[OrderItem]) extends Event
  case class ItemAdded(item: OrderItem) extends Event
  case class ItemRemoved(itemId: String) extends Event
  case class OrderSubmitted(totalAmount: BigDecimal) extends Event
  case class OrderCancelled(reason: String) extends Event
  
  sealed trait OrderStatus
  case object Draft extends OrderStatus
  case object Submitted extends OrderStatus
  case object Cancelled extends OrderStatus
  
  case class State(
    orderId: String,
    items: List[OrderItem],
    status: OrderStatus,
    totalAmount: BigDecimal
  )
  
  def apply(orderId: String): EventSourcedBehavior[Command, Event, State] = {
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId("Order", orderId),
      emptyState = State("", List.empty, Draft, BigDecimal(0)),
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
    .snapshotWhen((_, _, seqNr) => seqNr % 20 == 0)
  }
  
  private def commandHandler: (State, Command) => Effect[Event, State] = {
    case (state, CreateOrder(orderId, items)) if state.orderId.isEmpty =>
      Effect.persist(OrderCreated(orderId, items))
    
    case (state, AddItem(item)) if state.status == Draft =>
      Effect.persist(ItemAdded(item))
    
    case (state, RemoveItem(itemId)) if state.status == Draft =>
      Effect.persist(ItemRemoved(itemId))
    
    case (state, SubmitOrder()) if state.status == Draft =>
      val total = state.items.map(_.price).sum
      Effect.persist(OrderSubmitted(total))
    
    case (state, CancelOrder()) if state.status != Cancelled =>
      Effect.persist(OrderCancelled("User cancelled"))
    
    case _ =>
      Effect.none  // 无效命令
  }
  
  private def eventHandler: (State, Event) => State = {
    case (state, OrderCreated(orderId, items)) =>
      state.copy(orderId = orderId, items = items)
    
    case (state, ItemAdded(item)) =>
      state.copy(items = state.items :+ item)
    
    case (state, ItemRemoved(itemId)) =>
      state.copy(items = state.items.filterNot(_.id == itemId))
    
    case (state, OrderSubmitted(totalAmount)) =>
      state.copy(status = Submitted, totalAmount = totalAmount)
    
    case (state, OrderCancelled(_)) =>
      state.copy(status = Cancelled)
  }
}
```

---

## 总结

### 核心要点

**1. CQRS模式**
- 读写分离
- Command → Event Store
- Query → Read Model
- 针对性优化

**2. Event Sourcing**
- 存储事件而非状态
- 完整历史
- 可重放
- 审计友好

**3. Read Model**
- Projection构建
- 多个视图
- 查询优化
- 最终一致

**4. 快照优化**
- 每N个事件
- 性能提升1000倍
- 保留策略

**5. 事件演化**
- Upcasting
- 版本化
- 批量迁移

**6. 最终一致性**
- CAP选择AP
- 不一致窗口<100ms
- Read-your-writes

### 优缺点

**优势**：
- ✅ 完整审计
- ✅ 时间旅行
- ✅ 读写优化
- ✅ 可扩展

**挑战**：
- ❌ 复杂度高
- ❌ 最终一致
- ❌ 事件演化
- ❌ 存储增长

### 适用场景

```
✓ 金融系统（审计）
✓ 电商系统（订单历史）
✓ 协作系统（操作记录）
✓ 需要时间旅行

✗ 简单CRUD
✗ 强一致性要求
✗ 无需历史
```

### 下一篇预告

**《Saga模式：分布式事务协调》**
- Saga模式原理
- Choreography vs Orchestration
- 补偿事务
- 长时间运行流程

---

**项目地址**: https://github.com/Xuxiaotuan/pekko-reference

**作者**: Xuxiaotuan  
**日期**: 2024年11月
