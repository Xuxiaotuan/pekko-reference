# Saga模式：分布式事务协调

> **深度分析系列** - 第十七篇：深入长事务与补偿机制

---

## 📋 目录

- [引言](#引言)
- [Saga vs 2PC](#saga-vs-2pc)
- [Choreography模式](#choreography模式)
- [Orchestration模式](#orchestration模式)
- [补偿事务](#补偿事务)
- [超时与重试](#超时与重试)
- [Pekko实现](#pekko实现)
- [实战案例](#实战案例)
- [总结](#总结)

---

## 引言

分布式事务的挑战：

```
场景：电商下单
1. 订单服务：创建订单
2. 库存服务：扣减库存
3. 支付服务：扣款
4. 积分服务：增加积分

问题：
如果支付失败，如何回滚订单和库存？

传统方案：2PC（Two-Phase Commit）
问题：
❌ 阻塞
❌ 性能差
❌ 不适合微服务

Saga方案：
✓ 非阻塞
✓ 最终一致性
✓ 补偿机制
```

---

## Saga vs 2PC

### 2PC（两阶段提交）

```
Phase 1: Prepare（准备阶段）
Coordinator → Service1: Prepare
Coordinator → Service2: Prepare
Coordinator → Service3: Prepare
所有服务锁定资源

Phase 2: Commit（提交阶段）
如果全部成功：
  Coordinator → All: Commit
如果任一失败：
  Coordinator → All: Rollback

问题：
❌ 同步阻塞（资源锁定）
❌ 单点故障（Coordinator）
❌ 性能瓶颈
❌ 不适合长事务
```

### Saga模式

```
执行流程：
T1 → T2 → T3 → T4 → ... → Tn
每个事务独立提交

失败处理：
T1 → T2 → T3 → [T4 失败]
         ↓
C3 ← C2 ← C1（补偿）

特点：
✓ 异步非阻塞
✓ 长事务友好
✓ 最终一致性
✓ 无资源锁定

代价：
- 需要设计补偿逻辑
- 无隔离性
- 复杂度高
```

### 对比表

| 维度 | 2PC | Saga |
|-----|-----|------|
| **一致性** | 强一致 | 最终一致 |
| **隔离性** | 有 | 无 |
| **性能** | 差（阻塞） | 好（异步） |
| **可用性** | 差（单点） | 好（分布式） |
| **复杂度** | 低 | 高 |
| **适用场景** | 短事务 | 长事务 |

---

## Choreography模式

### 编舞式（去中心化）

**原理**：服务之间直接通信，无中心协调

```
OrderService创建订单
    ↓ (发布OrderCreated事件)
InventoryService监听 → 扣减库存
    ↓ (发布InventoryReserved事件)
PaymentService监听 → 扣款
    ↓ (发布PaymentCompleted事件)
PointService监听 → 增加积分
    ↓ (发布PointsAdded事件)
OrderService监听 → 订单完成

失败处理：
PaymentService扣款失败
    ↓ (发布PaymentFailed事件)
InventoryService监听 → 恢复库存
OrderService监听 → 取消订单
```

### 实现示例

```scala
// OrderService
object OrderService {
  
  sealed trait Command
  case class CreateOrder(orderId: String, items: List[Item]) extends Command
  case class CompleteOrder(orderId: String) extends Command
  case class CancelOrder(orderId: String) extends Command
  
  sealed trait Event
  case class OrderCreated(orderId: String, items: List[Item]) extends Event
  case class OrderCompleted(orderId: String) extends Event
  case class OrderCancelled(orderId: String) extends Event
  
  def apply(orderId: String): Behavior[Command] = {
    Behaviors.setup { ctx =>
      
      // 订阅事件
      ctx.system.eventStream ! Subscribe(classOf[InventoryEvent], ctx.self)
      ctx.system.eventStream ! Subscribe(classOf[PaymentEvent], ctx.self)
      
      pending()
    }
  }
  
  private def pending(): Behavior[Command] = {
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case CreateOrder(orderId, items) =>
          // 创建订单
          saveOrder(orderId, items, status = "Pending")
          
          // 发布事件
          ctx.system.eventStream ! Publish(
            OrderCreated(orderId, items)
          )
          
          waitingInventory(orderId, items)
      }
    }
  }
  
  private def waitingInventory(orderId: String, items: List[Item]): Behavior[Command] = {
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case InventoryReserved(`orderId`) =>
          // 库存预留成功，等待支付
          waitingPayment(orderId, items)
        
        case InventoryFailed(`orderId`) =>
          // 库存预留失败，取消订单
          updateOrder(orderId, status = "Cancelled")
          ctx.system.eventStream ! Publish(OrderCancelled(orderId))
          Behaviors.stopped
      }
    }
  }
  
  private def waitingPayment(orderId: String, items: List[Item]): Behavior[Command] = {
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case PaymentCompleted(`orderId`) =>
          // 支付成功，订单完成
          updateOrder(orderId, status = "Completed")
          ctx.system.eventStream ! Publish(OrderCompleted(orderId))
          Behaviors.stopped
        
        case PaymentFailed(`orderId`) =>
          // 支付失败，取消订单（触发补偿）
          updateOrder(orderId, status = "Cancelled")
          ctx.system.eventStream ! Publish(OrderCancelled(orderId))
          Behaviors.stopped
      }
    }
  }
}

// InventoryService
object InventoryService {
  
  def apply(): Behavior[Event] = {
    Behaviors.setup { ctx =>
      // 订阅OrderCreated事件
      ctx.system.eventStream ! Subscribe(classOf[OrderCreated], ctx.self)
      ctx.system.eventStream ! Subscribe(classOf[OrderCancelled], ctx.self)
      
      active()
    }
  }
  
  private def active(): Behavior[Event] = {
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case OrderCreated(orderId, items) =>
          // 预留库存
          if (reserveInventory(items)) {
            // 成功
            ctx.system.eventStream ! Publish(
              InventoryReserved(orderId)
            )
          } else {
            // 失败
            ctx.system.eventStream ! Publish(
              InventoryFailed(orderId)
            )
          }
          Behaviors.same
        
        case OrderCancelled(orderId) =>
          // 补偿：恢复库存
          compensateInventory(orderId)
          Behaviors.same
      }
    }
  }
}
```

### 优缺点

**优点**：
- ✅ 去中心化
- ✅ 松耦合
- ✅ 高可用

**缺点**：
- ❌ 难以理解
- ❌ 循环依赖风险
- ❌ 监控困难
- ❌ 测试复杂

---

## Orchestration模式

### 编排式（中心化）

**原理**：中心Orchestrator协调所有服务

```
Client → Orchestrator
         ↓
         1. OrderService.createOrder()
         ↓
         2. InventoryService.reserve()
         ↓
         3. PaymentService.charge()
         ↓
         4. PointService.addPoints()
         ↓
         Success/Failure → Client

失败处理：
如果步骤3失败：
  Orchestrator执行补偿：
  1. InventoryService.release()
  2. OrderService.cancel()
```

### 实现示例

```scala
// SagaOrchestrator
object SagaOrchestrator {
  
  sealed trait Command
  case class StartSaga(sagaId: String, order: Order, replyTo: ActorRef[Result]) extends Command
  private case class Step1Complete(sagaId: String) extends Command
  private case class Step2Complete(sagaId: String) extends Command
  private case class Step3Complete(sagaId: String) extends Command
  private case class StepFailed(sagaId: String, step: Int, reason: String) extends Command
  
  def apply(): Behavior[Command] = {
    orchestrating(Map.empty)
  }
  
  private def orchestrating(
    sagas: Map[String, SagaState]
  ): Behavior[Command] = {
    
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case StartSaga(sagaId, order, replyTo) =>
          // 创建Saga状态
          val state = SagaState(sagaId, order, replyTo, currentStep = 1)
          
          // 执行步骤1：创建订单
          executeStep1(ctx, state)
          
          orchestrating(sagas + (sagaId -> state))
        
        case Step1Complete(sagaId) =>
          sagas.get(sagaId) match {
            case Some(state) =>
              // 执行步骤2：预留库存
              val newState = state.copy(currentStep = 2)
              executeStep2(ctx, newState)
              orchestrating(sagas + (sagaId -> newState))
            
            case None =>
              Behaviors.same
          }
        
        case Step2Complete(sagaId) =>
          sagas.get(sagaId) match {
            case Some(state) =>
              // 执行步骤3：支付
              val newState = state.copy(currentStep = 3)
              executeStep3(ctx, newState)
              orchestrating(sagas + (sagaId -> newState))
            
            case None =>
              Behaviors.same
          }
        
        case Step3Complete(sagaId) =>
          sagas.get(sagaId) match {
            case Some(state) =>
              // 所有步骤完成
              state.replyTo ! Success(sagaId)
              orchestrating(sagas - sagaId)
            
            case None =>
              Behaviors.same
          }
        
        case StepFailed(sagaId, step, reason) =>
          sagas.get(sagaId) match {
            case Some(state) =>
              // 执行补偿
              compensate(ctx, state, step)
              state.replyTo ! Failure(reason)
              orchestrating(sagas - sagaId)
            
            case None =>
              Behaviors.same
          }
      }
    }
  }
  
  private def executeStep1(ctx: ActorContext[Command], state: SagaState): Unit = {
    ctx.pipeToSelf(orderService.createOrder(state.order)) {
      case scala.util.Success(_) => Step1Complete(state.sagaId)
      case scala.util.Failure(e) => StepFailed(state.sagaId, 1, e.getMessage)
    }
  }
  
  private def executeStep2(ctx: ActorContext[Command], state: SagaState): Unit = {
    ctx.pipeToSelf(inventoryService.reserve(state.order.items)) {
      case scala.util.Success(_) => Step2Complete(state.sagaId)
      case scala.util.Failure(e) => StepFailed(state.sagaId, 2, e.getMessage)
    }
  }
  
  private def executeStep3(ctx: ActorContext[Command], state: SagaState): Unit = {
    ctx.pipeToSelf(paymentService.charge(state.order.amount)) {
      case scala.util.Success(_) => Step3Complete(state.sagaId)
      case scala.util.Failure(e) => StepFailed(state.sagaId, 3, e.getMessage)
    }
  }
  
  private def compensate(
    ctx: ActorContext[Command],
    state: SagaState,
    failedStep: Int
  ): Unit = {
    // 补偿逻辑：反向执行
    if (failedStep >= 3) {
      // 步骤3失败，补偿步骤2
      inventoryService.release(state.order.items)
    }
    if (failedStep >= 2) {
      // 步骤2失败，补偿步骤1
      orderService.cancel(state.order.orderId)
    }
  }
  
  case class SagaState(
    sagaId: String,
    order: Order,
    replyTo: ActorRef[Result],
    currentStep: Int
  )
}
```

### 优缺点

**优点**：
- ✅ 清晰的流程
- ✅ 易于理解
- ✅ 集中监控
- ✅ 易于测试

**缺点**：
- ❌ 中心化（单点）
- ❌ 紧耦合
- ❌ Orchestrator复杂

---

## 补偿事务

### 补偿原则

```
正向事务：T
补偿事务：C

要求：
T · C = I（恒等）
即：执行T后再执行C，等于什么都没做

示例：
T: 扣款100元
C: 退款100元
结果：余额不变
```

### 补偿设计

```scala
// 补偿事务接口
trait CompensableTransaction[T] {
  // 正向操作
  def execute(): Future[T]
  
  // 补偿操作
  def compensate(): Future[Unit]
  
  // 是否需要补偿
  def needsCompensation: Boolean
}

// 示例：库存预留
class ReserveInventoryTransaction(
  items: List[Item]
) extends CompensableTransaction[Unit] {
  
  private var reserved = false
  
  def execute(): Future[Unit] = {
    inventoryService.reserve(items).map { _ =>
      reserved = true
    }
  }
  
  def compensate(): Future[Unit] = {
    if (reserved) {
      inventoryService.release(items)
    } else {
      Future.successful(())
    }
  }
  
  def needsCompensation: Boolean = reserved
}
```

### 补偿顺序

```
执行顺序：T1 → T2 → T3 → T4
补偿顺序：C4 → C3 → C2 → C1（反向）

原因：
- 后面的步骤可能依赖前面
- 必须先回滚依赖方
```

### 幂等性

```scala
// 补偿必须幂等
def compensate(): Future[Unit] = {
  // ✓ 幂等：检查状态
  if (orderExists(orderId)) {
    cancelOrder(orderId)
  } else {
    Future.successful(())  // 已取消，无需操作
  }
}

// ❌ 非幂等：直接操作
def compensate(): Future[Unit] = {
  cancelOrder(orderId)  // 可能重复取消
}
```

---

## 超时与重试

### 超时处理

```scala
// 为每个步骤设置超时
object SagaWithTimeout {
  
  private def executeStepWithTimeout[T](
    step: => Future[T],
    timeout: FiniteDuration
  ): Future[T] = {
    
    val promise = Promise[T]()
    
    // 启动定时器
    val timer = system.scheduler.scheduleOnce(timeout) {
      promise.tryFailure(new TimeoutException(s"Step timeout after $timeout"))
    }
    
    // 执行步骤
    step.onComplete { result =>
      timer.cancel()
      promise.tryComplete(result)
    }
    
    promise.future
  }
  
  // 使用
  executeStepWithTimeout(
    orderService.createOrder(order),
    timeout = 5.seconds
  )
}
```

### 重试策略

```scala
// 指数退避重试
object RetryStrategy {
  
  def retry[T](
    operation: => Future[T],
    maxAttempts: Int = 3,
    initialDelay: FiniteDuration = 1.second
  )(implicit ec: ExecutionContext, scheduler: Scheduler): Future[T] = {
    
    def attempt(n: Int, delay: FiniteDuration): Future[T] = {
      operation.recoverWith {
        case e if n < maxAttempts =>
          // 重试
          after(delay, scheduler) {
            attempt(n + 1, delay * 2)  // 指数退避
          }
        
        case e =>
          // 超过最大次数，失败
          Future.failed(e)
      }
    }
    
    attempt(1, initialDelay)
  }
}

// 使用
retry(
  paymentService.charge(amount),
  maxAttempts = 3,
  initialDelay = 1.second
)
// 1秒后重试 → 2秒后重试 → 4秒后重试 → 失败
```

---

## Pekko实现

### Pekko Persistence实现Saga

```scala
object SagaActor {
  
  sealed trait Command
  case class StartSaga(sagaData: SagaData, replyTo: ActorRef[SagaResult]) extends Command
  
  sealed trait Event
  case class SagaStarted(sagaId: String, sagaData: SagaData) extends Event
  case class StepCompleted(step: Int) extends Event
  case class StepFailed(step: Int, reason: String) extends Event
  case class SagaCompleted() extends Event
  case class SagaFailed(reason: String) extends Event
  
  sealed trait State
  case object Idle extends State
  case class Running(sagaId: String, sagaData: SagaData, completedSteps: Set[Int], replyTo: ActorRef[SagaResult]) extends State
  case class Compensating(sagaId: String, sagaData: SagaData, completedSteps: Set[Int], replyTo: ActorRef[SagaResult]) extends State
  
  def apply(sagaId: String): EventSourcedBehavior[Command, Event, State] = {
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId("Saga", sagaId),
      emptyState = Idle,
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
  }
  
  private def commandHandler: (State, Command) => Effect[Event, State] = {
    case (Idle, StartSaga(sagaData, replyTo)) =>
      Effect
        .persist(SagaStarted(sagaData.sagaId, sagaData))
        .thenRun { _ =>
          // 执行第一步
          executeStep(1, sagaData)
        }
    
    case (Running(sagaId, sagaData, completedSteps, replyTo), StepCompleted(step)) =>
      val newCompleted = completedSteps + step
      
      if (newCompleted.size == totalSteps) {
        // 所有步骤完成
        Effect
          .persist(SagaCompleted())
          .thenRun { _ =>
            replyTo ! SagaSuccess(sagaId)
          }
      } else {
        // 执行下一步
        Effect
          .persist(StepCompleted(step))
          .thenRun { _ =>
            executeStep(step + 1, sagaData)
          }
      }
    
    case (Running(sagaId, sagaData, completedSteps, replyTo), StepFailed(step, reason)) =>
      // 步骤失败，开始补偿
      Effect
        .persist(StepFailed(step, reason))
        .thenRun { _ =>
          // 补偿已完成的步骤
          compensateSteps(completedSteps, sagaData)
        }
    
    case _ =>
      Effect.none
  }
  
  private def eventHandler: (State, Event) => State = {
    case (Idle, SagaStarted(sagaId, sagaData)) =>
      Running(sagaId, sagaData, Set.empty, replyTo)
    
    case (Running(sagaId, sagaData, completed, replyTo), StepCompleted(step)) =>
      Running(sagaId, sagaData, completed + step, replyTo)
    
    case (Running(sagaId, sagaData, completed, replyTo), StepFailed(step, reason)) =>
      Compensating(sagaId, sagaData, completed, replyTo)
    
    case (_, SagaCompleted()) =>
      Idle
    
    case (_, SagaFailed(_)) =>
      Idle
  }
}
```

---

## 实战案例

### 案例：旅行预订Saga

```scala
// 预订流程：酒店 → 机票 → 租车
object TravelBookingSaga {
  
  case class BookingData(
    hotelId: String,
    flightId: String,
    carId: String,
    userId: String
  )
  
  def apply(): Behavior[Command] = {
    Behaviors.setup { ctx =>
      orchestrating()
    }
  }
  
  private def orchestrating(): Behavior[Command] = {
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case StartBooking(data, replyTo) =>
          // 步骤1：预订酒店
          ctx.pipeToSelf(hotelService.book(data.hotelId, data.userId)) {
            case Success(hotelBooking) =>
              HotelBooked(hotelBooking)
            case Failure(e) =>
              BookingFailed(1, e.getMessage)
          }
          
          waitingHotel(data, replyTo, None, None, None)
      }
    }
  }
  
  private def waitingHotel(
    data: BookingData,
    replyTo: ActorRef[Result],
    hotelBooking: Option[Booking],
    flightBooking: Option[Booking],
    carBooking: Option[Booking]
  ): Behavior[Command] = {
    
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case HotelBooked(booking) =>
          // 步骤2：预订机票
          ctx.pipeToSelf(flightService.book(data.flightId, data.userId)) {
            case Success(flightBooking) =>
              FlightBooked(flightBooking)
            case Failure(e) =>
              BookingFailed(2, e.getMessage)
          }
          
          waitingFlight(data, replyTo, Some(booking), None, None)
        
        case BookingFailed(step, reason) =>
          // 步骤1失败，无需补偿
          replyTo ! Failure(reason)
          Behaviors.stopped
      }
    }
  }
  
  private def waitingFlight(
    data: BookingData,
    replyTo: ActorRef[Result],
    hotelBooking: Option[Booking],
    flightBooking: Option[Booking],
    carBooking: Option[Booking]
  ): Behavior[Command] = {
    
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case FlightBooked(booking) =>
          // 步骤3：预订租车
          ctx.pipeToSelf(carService.book(data.carId, data.userId)) {
            case Success(carBooking) =>
              CarBooked(carBooking)
            case Failure(e) =>
              BookingFailed(3, e.getMessage)
          }
          
          waitingCar(data, replyTo, hotelBooking, Some(booking), None)
        
        case BookingFailed(step, reason) =>
          // 步骤2失败，补偿步骤1
          hotelBooking.foreach { booking =>
            hotelService.cancel(booking.id)
          }
          replyTo ! Failure(reason)
          Behaviors.stopped
      }
    }
  }
  
  private def waitingCar(
    data: BookingData,
    replyTo: ActorRef[Result],
    hotelBooking: Option[Booking],
    flightBooking: Option[Booking],
    carBooking: Option[Booking]
  ): Behavior[Command] = {
    
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case CarBooked(booking) =>
          // 所有步骤完成
          replyTo ! Success(
            TravelBooking(hotelBooking.get, flightBooking.get, booking)
          )
          Behaviors.stopped
        
        case BookingFailed(step, reason) =>
          // 步骤3失败，补偿步骤2和1
          flightBooking.foreach { booking =>
            flightService.cancel(booking.id)
          }
          hotelBooking.foreach { booking =>
            hotelService.cancel(booking.id)
          }
          replyTo ! Failure(reason)
          Behaviors.stopped
      }
    }
  }
}
```

---

## 总结

### 核心要点

**1. Saga模式**
- 长事务友好
- 最终一致性
- 补偿机制
- 非阻塞

**2. Choreography vs Orchestration**
- Choreography：去中心化、松耦合
- Orchestration：中心化、易理解

**3. 补偿事务**
- T · C = I（恒等）
- 反向顺序
- 幂等性
- 状态检查

**4. 超时重试**
- 每步设超时
- 指数退避
- 最大次数
- 失败补偿

**5. Pekko实现**
- Event Sourcing
- 状态持久化
- 故障恢复
- 监控友好

### 模式对比

| 维度 | 2PC | Choreography | Orchestration |
|-----|-----|-------------|---------------|
| **中心化** | 是 | 否 | 是 |
| **一致性** | 强 | 最终 | 最终 |
| **性能** | 差 | 好 | 中 |
| **复杂度** | 低 | 高 | 中 |
| **监控** | 易 | 难 | 易 |
| **推荐** | ❌ | 小规模 | ⭐⭐⭐⭐⭐ |

### 最佳实践

```
✓ 使用Orchestration（推荐）
✓ 设计幂等补偿
✓ 设置合理超时
✓ 实现重试机制
✓ 记录Saga状态
✓ 监控每个步骤

❌ 嵌套Saga
❌ 跨Saga共享状态
❌ 忽略补偿设计
```

### 下一篇预告

**《分布式Actor的位置透明性》**
- Location Transparency原理
- ActorRef序列化
- 远程消息传递
- 网络分区处理

---

**项目地址**: https://github.com/Xuxiaotuan/pekko-reference

**作者**: Xuxiaotuan  
**日期**: 2024年11月
