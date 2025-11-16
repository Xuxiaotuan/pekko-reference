# Timers与定时任务：时间驱动的Actor

> **深度分析系列** - 第九篇：深入TimerScheduler机制与定时任务实践

---

## 📋 目录

- [引言](#引言)
- [TimerScheduler接口](#timerscheduler接口)
- [三种定时器类型](#三种定时器类型)
- [时间轮实现](#时间轮实现)
- [定时器精度](#定时器精度)
- [Actor生命周期](#actor生命周期)
- [常见模式](#常见模式)
- [最佳实践](#最佳实践)
- [实战案例](#实战案例)
- [总结](#总结)

---

## 引言

Actor需要定时执行任务：

```scala
// 常见场景：
// 1. 超时处理：请求超过3秒未响应
// 2. 心跳检测：每30秒发送心跳
// 3. 定期清理：每小时清理过期缓存
// 4. 重试机制：失败后5秒重试

问题：
1. 如何在Actor中使用定时器？
2. 单次vs周期定时器？
3. 定时器精度如何？
4. Actor停止后定时器会怎样？
```

---

## TimerScheduler接口

### 基本API

```scala
trait TimerScheduler[T] {
  // 单次定时器
  def startSingleTimer(key: Any, msg: T, delay: FiniteDuration): Unit
  
  // 周期定时器（固定延迟）
  def startTimerWithFixedDelay(
    key: Any, msg: T,
    initialDelay: FiniteDuration,
    delay: FiniteDuration
  ): Unit
  
  // 周期定时器（固定频率）
  def startTimerAtFixedRate(
    key: Any, msg: T,
    initialDelay: FiniteDuration,
    interval: FiniteDuration
  ): Unit
  
  // 取消定时器
  def cancel(key: Any): Unit
  def cancelAll(): Unit
  
  // 查询
  def isTimerActive(key: Any): Boolean
}
```

### 获取TimerScheduler

```scala
def apply(): Behavior[Command] = {
  Behaviors.withTimers { timers =>
    idle(timers)
  }
}
```

---

## 三种定时器类型

### 1. 单次定时器

```scala
// 超时处理
timers.startSingleTimer(Timeout, 5.seconds)

// 执行一次后自动取消
```

### 2. Fixed Delay（固定延迟）

```scala
// 上次执行完成后延迟
timers.startTimerWithFixedDelay(
  SendHeartbeat,
  SendHeartbeat,
  0.seconds,
  30.seconds
)

// 时间线：
// t=0s:  执行（耗时5s）
// t=5s:  完成
// t=35s: 执行（5s完成 + 30s延迟）
```

### 3. Fixed Rate（固定频率）

```scala
// 固定间隔
timers.startTimerAtFixedRate(
  CollectMetrics,
  CollectMetrics,
  0.seconds,
  10.seconds
)

// 时间线：
// t=0s:  执行
// t=10s: 执行（严格10秒间隔）
// t=20s: 执行
```

### 选择建议

| 场景 | 推荐类型 |
|-----|---------|
| 超时 | Single Timer |
| 心跳（任务耗时不固定） | Fixed Delay |
| 指标采集（需要精确间隔） | Fixed Rate |

---

## 时间轮实现

### 原理

```
时间轮：环形数组，每个槽代表一个时间单位

┌─────────────────────────────────┐
│  0  │  1  │  2  │ ... │ 511 │  0  │
└─────────────────────────────────┘
  ↑
当前位置

参数：
- 512个槽
- 每槽100ms
- 周期：51.2秒

添加3秒定时器：
3000ms / 100ms = 30个槽
放入槽30

复杂度：O(1)插入/删除
```

---

## 定时器精度

### 精度分析

```
理论精度：100ms（tick duration）

实际误差来源：
1. 时间轮：0-100ms
2. 线程调度：0-10ms
3. GC暂停：0-100ms

总体精度：±100-200ms

示例：
设置1秒定时器
实际触发：0.9s - 1.2s
```

### 提高精度

```hocon
# 减少tick duration（增加CPU开销）
pekko.scheduler {
  tick-duration = 50ms  # 默认100ms
}
```

---

## Actor生命周期

### 自动清理

```scala
// Actor停止时，定时器自动取消
Behaviors.stopped
// 所有定时器自动清理
```

### 重启时重置

```scala
// Actor重启后，定时器被清除
Behaviors.supervise(
  Behaviors.withTimers { timers =>
    Behaviors.setup { ctx =>
      // setup每次启动都执行
      timers.startSingleTimer(...)
    }
  }
).onFailure(SupervisorStrategy.restart)
```

---

## 常见模式

### 模式1：请求超时

```scala
object TimeoutPattern {
  case class Request(data: String, replyTo: ActorRef[Response]) extends Command
  private case class Timeout(requestId: String) extends Command
  
  def apply(): Behavior[Command] = {
    Behaviors.withTimers { timers =>
      Behaviors.receive { (ctx, msg) =>
        msg match {
          case Request(data, replyTo) =>
            val requestId = UUID.randomUUID().toString
            
            // 设置5秒超时
            timers.startSingleTimer(
              s"timeout-$requestId",
              Timeout(requestId),
              5.seconds
            )
            
            // 异步处理
            ctx.pipeToSelf(process(data)) {
              case Success(result) => Complete(requestId, result)
              case Failure(_) => Timeout(requestId)
            }
            
            Behaviors.same
          
          case Complete(requestId, result) =>
            // 取消超时
            timers.cancel(s"timeout-$requestId")
            Behaviors.same
          
          case Timeout(requestId) =>
            // 超时处理
            Behaviors.same
        }
      }
    }
  }
}
```

### 模式2：心跳检测

```scala
object HeartbeatPattern {
  def apply(): Behavior[Command] = {
    Behaviors.withTimers { timers =>
      // 每30秒发送心跳
      timers.startTimerAtFixedRate(
        SendHeartbeat,
        SendHeartbeat,
        30.seconds,
        30.seconds
      )
      
      Behaviors.receive { (ctx, msg) =>
        msg match {
          case SendHeartbeat =>
            remote ! Heartbeat(ctx.self)
            // 设置10秒超时
            timers.startSingleTimer(HeartbeatTimeout, 10.seconds)
            waitingAck(timers)
          
          case HeartbeatAck =>
            timers.cancel(HeartbeatTimeout)
            Behaviors.same
          
          case HeartbeatTimeout =>
            // 连接断开
            disconnected(timers)
        }
      }
    }
  }
}
```

### 模式3：指数退避重试

```scala
object RetryPattern {
  def apply(): Behavior[Command] = {
    Behaviors.withTimers { timers =>
      Behaviors.receive { (ctx, msg) =>
        msg match {
          case Execute(task) =>
            retry(ctx, timers, task, attempt = 0)
            Behaviors.same
        }
      }
    }
  }
  
  private def retry(
    ctx: ActorContext[Command],
    timers: TimerScheduler[Command],
    task: Task,
    attempt: Int
  ): Unit = {
    ctx.pipeToSelf(perform(task)) {
      case Success(result) => 
        Success(result)
      
      case Failure(e) if attempt < 5 =>
        val backoff = math.pow(2, attempt).seconds
        timers.startSingleTimer(
          s"retry-${task.id}",
          Retry(task, attempt + 1),
          backoff
        )
        Retrying
      
      case Failure(e) =>
        Failed(e)
    }
  }
}
```

---

## 最佳实践

### 1. 复用定时器key

```scala
// ✓ 推荐
timers.startSingleTimer(TimeoutKey, Timeout, 5.seconds)

// ❌ 避免
timers.startSingleTimer(s"timeout-$id", Timeout, 5.seconds)
```

### 2. 及时取消

```scala
case Complete =>
  timers.cancel(TimeoutKey)
  // 避免定时器堆积
```

### 3. 合理间隔

```scala
// ✓ 推荐
timers.startTimerAtFixedRate(..., 100.millis, 100.millis)

// ❌ 避免
timers.startTimerAtFixedRate(..., 10.millis, 10.millis)  // 太频繁
```

### 4. 监控定时器数量

```scala
// 记录活跃定时器
if (activeTimers > 100) {
  ctx.log.warn(s"Too many timers: $activeTimers")
}
```

---

## 实战案例

### 案例1：缓存过期清理

```scala
object CacheActor {
  def apply(): Behavior[Command] = {
    Behaviors.withTimers { timers =>
      // 每分钟清理过期项
      timers.startTimerAtFixedRate(
        CleanupExpired,
        CleanupExpired,
        1.minute,
        1.minute
      )
      
      running(timers, Map.empty)
    }
  }
  
  private def running(
    timers: TimerScheduler[Command],
    cache: Map[String, CacheEntry]
  ): Behavior[Command] = {
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case CleanupExpired =>
          val now = System.currentTimeMillis()
          val cleaned = cache.filter(_._2.expireAt > now)
          running(timers, cleaned)
      }
    }
  }
}
```

### 案例2：令牌桶限流

```scala
object RateLimiter {
  def apply(maxTokens: Int, refillRate: FiniteDuration): Behavior[Command] = {
    Behaviors.withTimers { timers =>
      // 定期补充令牌
      timers.startTimerAtFixedRate(
        RefillTokens,
        RefillTokens,
        refillRate,
        refillRate
      )
      
      running(timers, maxTokens, maxTokens)
    }
  }
}
```

---

## 总结

### 核心要点

**1. 三种定时器**
- Single Timer：单次执行
- Fixed Delay：上次完成后延迟
- Fixed Rate：固定间隔

**2. 时间轮实现**
- O(1)插入/删除
- 100ms精度
- 自动清理

**3. 生命周期**
- Actor停止自动取消
- 重启后需重新设置

**4. 最佳实践**
- 复用key
- 及时取消
- 合理间隔
- 监控数量

### 性能

| 操作 | 复杂度 | 说明 |
|-----|-------|------|
| 插入 | O(1) | 时间轮 |
| 删除 | O(1) | HashMap查找 |
| 触发 | O(n) | 该槽任务数 |
| 精度 | ±100-200ms | 实际误差 |

### 下一篇预告

**第四部分：集群理论**即将开始！

**《Gossip协议与最终一致性》**
- Gossip协议数学模型
- 收敛时间分析
- SWIM协议详解
- Phi Accrual Failure Detector

---

**项目地址**: https://github.com/Xuxiaotuan/pekko-reference

**作者**: Xuxiaotuan  
**日期**: 2024年11月
