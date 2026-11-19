# 性能调优：让系统飞起来

> **系列文章：构建下一代任务调度平台**  
> 第六篇：性能篇 - 压测、调优与最佳实践

---

## 📋 目录

- [引言](#引言)
- [性能测试方法](#性能测试方法)
- [Actor系统调优](#actor系统调优)
- [Streams性能优化](#streams性能优化)
- [集群性能调优](#集群性能调优)
- [JVM调优](#jvm调优)
- [压测与基准测试](#压测与基准测试)
- [监控与分析](#监控与分析)
- [生产环境经验](#生产环境经验)

---

## 引言

前面5篇文章中，我们构建了完整的分布式任务调度系统。但**系统能不能抗住生产负载？**这是关键问题。

本文将深入性能调优，包括：

- 📊 **性能测试**：如何科学地测试性能
- ⚡ **Actor调优**：Dispatcher、Mailbox优化
- 🚰 **Streams调优**：背压、批量、并发
- 🌐 **集群调优**：Sharding、网络、序列化
- 🔥 **压测实战**：JMeter、Gatling压力测试
- 📈 **监控分析**：Prometheus、Grafana可视化

### 性能目标

| 指标 | 目标值 | 说明 |
|-----|-------|------|
| **API响应时间** | P99 < 100ms | 99%请求100ms内 |
| **工作流吞吐** | 10000/s | 每秒处理1万个工作流 |
| **集群恢复** | < 5s | 节点故障5秒内恢复 |
| **CPU使用率** | 60-70% | 留有余量应对突发 |
| **内存使用** | < 80% | 避免频繁GC |
| **可用性** | 99.99% | 年故障时间<53分钟 |

---

## 性能测试方法

### 测试类型

#### 1. 基准测试（Benchmark）

**目标**：测试单个组件的极限性能

```scala
import org.openjdk.jmh.annotations._
import java.util.concurrent.TimeUnit

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class ActorBenchmark {
  
  var system: ActorSystem[_] = _
  var actor: ActorRef[Command] = _
  
  @Setup
  def setup(): Unit = {
    system = ActorSystem(Behaviors.empty, "benchmark")
    actor = system.systemActorOf(WorkflowActor(/*...*/), "workflow")
  }
  
  @Benchmark
  def sendMessage(): Unit = {
    actor ! Execute(replyTo)
  }
  
  @TearDown
  def teardown(): Unit = {
    system.terminate()
  }
}
```

**运行**：
```bash
sbt "jmh:run -i 10 -wi 5 -f 1 -t 4"
# -i: 迭代次数  -wi: 预热次数  -f: fork次数  -t: 线程数
```

#### 2. 负载测试（Load Test）

**目标**：测试系统在预期负载下的表现

```scala
// Gatling负载测试
class WorkflowLoadTest extends Simulation {
  
  val httpProtocol = http
    .baseUrl("http://localhost:8080")
    .acceptHeader("application/json")
  
  val scn = scenario("WorkflowExecution")
    .exec(
      http("创建工作流")
        .post("/api/v1/workflows")
        .body(StringBody("""{"id": "wf-${id}", "name": "test"}"""))
        .check(status.is(200))
    )
    .pause(1)
    .exec(
      http("执行工作流")
        .post("/api/v1/workflows/${id}/execute")
        .check(status.is(200))
    )
  
  setUp(
    scn.inject(
      rampUsers(1000) during (60.seconds),  // 60秒内加载1000用户
      constantUsersPerSec(100) during (300.seconds)  // 持续100 QPS 5分钟
    )
  ).protocols(httpProtocol)
}
```

#### 3. 压力测试（Stress Test）

**目标**：测试系统的极限承受能力

```scala
setUp(
  scn.inject(
    rampUsers(100) during (10.seconds),
    rampUsers(500) during (20.seconds),
    rampUsers(1000) during (30.seconds),
    constantUsersPerSec(200) during (60.seconds)  // 持续施压
  )
)
```

#### 4. 浸泡测试（Soak Test）

**目标**：长时间运行，发现内存泄漏等问题

```scala
setUp(
  scn.inject(
    constantUsersPerSec(50) during (24.hours)  // 持续24小时
  )
)
```

### 性能指标

#### 关键指标

```scala
case class PerformanceMetrics(
  throughput: Double,        // 吞吐量 (req/s)
  latencyP50: Duration,      // 中位数延迟
  latencyP95: Duration,      // 95分位延迟
  latencyP99: Duration,      // 99分位延迟
  errorRate: Double,         // 错误率
  cpuUsage: Double,          // CPU使用率
  memoryUsage: Long,         // 内存使用
  gcTime: Duration           // GC时间
)
```

#### 测量工具

```scala
// 使用Kamon监控
val timer = Kamon.timer("workflow.execution.time")
val counter = Kamon.counter("workflow.execution.count")

val span = timer.start()
try {
  executeWorkflow(workflow)
  counter.increment()
} finally {
  span.stop()
}
```

---

## Actor系统调优

### Dispatcher配置

Dispatcher控制Actor执行线程：

```hocon
# application.conf

# 默认Dispatcher（共享线程池）
pekko.actor.default-dispatcher {
  type = Dispatcher
  executor = "fork-join-executor"
  
  fork-join-executor {
    # 线程数 = cores * parallelism-factor
    parallelism-min = 8
    parallelism-factor = 3.0
    parallelism-max = 64
  }
  
  # 吞吐量（每次处理多少消息）
  throughput = 5
}

# 阻塞IO专用Dispatcher
blocking-io-dispatcher {
  type = Dispatcher
  executor = "thread-pool-executor"
  
  thread-pool-executor {
    fixed-pool-size = 32  # 固定线程池
  }
  
  throughput = 1  # 阻塞操作throughput设为1
}

# CPU密集型Dispatcher
cpu-intensive-dispatcher {
  type = Dispatcher
  executor = "fork-join-executor"
  
  fork-join-executor {
    parallelism-min = 4
    parallelism-factor = 1.0  # cores * 1
    parallelism-max = 16
  }
  
  throughput = 10  # CPU密集型可以更高
}
```

**使用自定义Dispatcher**：

```scala
// 方式1：配置中指定
pekko.actor.deployment {
  /workflow-supervisor/* {
    dispatcher = blocking-io-dispatcher
  }
}

// 方式2：代码中指定
context.spawn(
  WorkflowActor(/*...*/),
  "workflow",
  DispatcherSelector.fromConfig("blocking-io-dispatcher")
)
```

### Mailbox优化

```hocon
# 有界Mailbox（防止OOM）
bounded-mailbox {
  mailbox-type = "org.apache.pekko.dispatch.BoundedMailbox"
  mailbox-capacity = 10000
  mailbox-push-timeout-time = 100ms
}

# 优先级Mailbox
priority-mailbox {
  mailbox-type = "cn.xuyinyin.magic.PriorityMailbox"
}
```

**自定义优先级Mailbox**：

```scala
class PriorityMailbox extends UnboundedPriorityMailbox(
  PriorityGenerator {
    case Execute(_) => 0      // 高优先级
    case GetStatus(_) => 1    // 中优先级
    case Stop => 2            // 低优先级
    case _ => 1
  }
)
```

### Actor设计优化

#### 1. 避免阻塞

```scala
// ❌ 错误：阻塞Actor
Behaviors.receiveMessage {
  case FetchData(id) =>
    val data = database.query(id)  // 阻塞！
    sender ! data
    Behaviors.same
}

// ✅ 正确：使用Future + pipeToSelf
Behaviors.receiveMessage {
  case FetchData(id) =>
    context.pipeToSelf(Future {
      database.query(id)  // 在Future中执行
    }(blockingDispatcher)) {
      case Success(data) => DataFetched(data)
      case Failure(ex) => FetchFailed(ex)
    }
    Behaviors.same
}
```

#### 2. 批量处理

```scala
// 批量处理消息
def batching(buffer: List[Item], batchSize: Int): Behavior[Command] = {
  Behaviors.withTimers { timers =>
    Behaviors.receiveMessage {
      case AddItem(item) =>
        val newBuffer = buffer :+ item
        
        if (newBuffer.size >= batchSize) {
          processBatch(newBuffer)
          batching(List.empty, batchSize)
        } else {
          if (buffer.isEmpty) {
            timers.startSingleTimer(FlushBatch, 1.second)
          }
          batching(newBuffer, batchSize)
        }
      
      case FlushBatch if buffer.nonEmpty =>
        processBatch(buffer)
        batching(List.empty, batchSize)
    }
  }
}
```

#### 3. 消息聚合

```scala
// Aggregator模式：收集多个响应
def aggregating(
  remaining: Int,
  responses: List[Response]
): Behavior[Command] = {
  Behaviors.receiveMessage {
    case resp: Response =>
      val newResponses = responses :+ resp
      val newRemaining = remaining - 1
      
      if (newRemaining == 0) {
        // 所有响应收集完成
        processAllResponses(newResponses)
        Behaviors.stopped
      } else {
        aggregating(newRemaining, newResponses)
      }
  }
}
```

---

## Streams性能优化

### 1. 批量处理

```scala
// ❌ 逐条处理
Source(records)
  .mapAsync(1)(record => database.insert(record))  // 慢！
  .runWith(Sink.ignore)

// ✅ 批量处理
Source(records)
  .grouped(1000)                                   // 批量
  .mapAsync(4)(batch => database.batchInsert(batch))  // 并发
  .runWith(Sink.ignore)

// 性能提升：50-100x
```

### 2. 异步边界

```scala
// ❌ 单线程
Source(data)
  .map(cpuIntensive1)  // 线程1
  .map(cpuIntensive2)  // 线程1
  .runWith(Sink.ignore)

// ✅ 多线程
Source(data)
  .map(cpuIntensive1)
  .async  // 异步边界
  .map(cpuIntensive2)
  .async
  .runWith(Sink.ignore)

// 性能提升：多核利用
```

### 3. 缓冲区调优

```scala
// 增大缓冲区
Source(data)
  .withAttributes(
    Attributes.inputBuffer(
      initial = 128,
      max = 256
    )
  )
  .map(transform)
  .runWith(Sink.ignore)

// 性能提升：20-30%
```

### 4. mapAsync并发度

```scala
// 调优并发度
Source(urls)
  .mapAsync(parallelism = 16) { url =>  // 根据场景调整
    Http().singleRequest(HttpRequest(uri = url))
  }
  .runWith(Sink.ignore)

// 经验值：
// CPU密集：cores
// IO密集：cores * 2-4
// 网络请求：10-20
```

### 5. 背压策略

```scala
// 选择合适的背压策略
Source.tick(0.seconds, 10.millis, 1)
  .buffer(1000, OverflowStrategy.dropHead)  // 丢弃旧数据
  .map(process)
  .runWith(Sink.ignore)

// 策略：
// backpressure: 阻塞上游（默认）
// dropHead: 丢弃最旧
// dropTail: 丢弃最新
// dropNew: 丢弃新数据
// fail: 失败
```

### 性能对比

| 优化 | 场景 | 提升 |
|-----|------|------|
| grouped(1000) | 数据库批量写入 | **50-100x** |
| mapAsync(16) | HTTP并发请求 | **10-20x** |
| async边界 | CPU密集计算 | **2-4x** |
| buffer增大 | 高吞吐流 | **20-30%** |

---

## 集群性能调优

### 1. 序列化优化

```hocon
# 使用高效序列化
pekko.actor {
  serializers {
    jackson-cbor = "org.apache.pekko.serialization.jackson.JacksonCborSerializer"
    kryo = "io.altoo.akka.serialization.kryo.KryoSerializer"
  }
  
  serialization-bindings {
    "cn.xuyinyin.magic.CborSerializable" = jackson-cbor
    "cn.xuyinyin.magic.KryoSerializable" = kryo
  }
}

# Kryo配置（最快）
pekko-kryo-serialization {
  type = "graph"
  id-strategy = "explicit"
  implicit-registration-logging = true
  kryo-trace = false
}
```

**性能对比**：
- Java序列化：慢、体积大
- Jackson CBOR：快、体积中等
- Kryo：最快、体积最小

### 2. Sharding优化

```hocon
pekko.cluster.sharding {
  # Shard数量（建议10 * 节点数）
  number-of-shards = 100
  
  # 再平衡策略
  least-shard-allocation-strategy {
    rebalance-threshold = 2
    max-simultaneous-rebalance = 3
  }
  
  # 心跳间隔
  coordinator-state {
    write-majority-plus = 3
  }
  
  # Passivation（闲置Entity清理）
  passivate-idle-entity-after = 2.minutes
}
```

### 3. 网络优化

```hocon
pekko.remote.artery {
  # 传输层
  transport = tcp
  
  canonical {
    hostname = "0.0.0.0"
    port = 2551
  }
  
  # TCP优化
  advanced {
    # 最大帧大小
    maximum-frame-size = 256000b
    
    # 缓冲区大小
    send-buffer-size = 256000b
    receive-buffer-size = 256000b
    
    # 连接池
    outbound-message-queue-size = 3072
    
    # 压缩
    compression {
      enabled = off  # 通常不需要
    }
  }
}
```

### 4. 集群监控

```hocon
pekko.cluster {
  # 心跳间隔
  failure-detector {
    threshold = 12.0
    acceptable-heartbeat-pause = 5s
    heartbeat-interval = 1s
  }
  
  # Gossip优化
  gossip-interval = 1s
  gossip-time-to-live = 2s
  
  # 领导者选举
  leader-actions-interval = 1s
}
```

---

## JVM调优

### GC配置

```bash
# G1GC（推荐）
java -Xms4g -Xmx4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:InitiatingHeapOccupancyPercent=45 \
  -XX:G1HeapRegionSize=16m \
  -XX:+ParallelRefProcEnabled \
  -XX:+UseStringDeduplication \
  -jar pekko-server.jar

# ZGC（低延迟）
java -Xms4g -Xmx4g \
  -XX:+UseZGC \
  -XX:ZCollectionInterval=5 \
  -jar pekko-server.jar
```

### JVM参数

```bash
# 性能监控
-XX:+PrintGCDetails \
-XX:+PrintGCDateStamps \
-Xloggc:gc.log \
-XX:+HeapDumpOnOutOfMemoryError \
-XX:HeapDumpPath=/tmp/heapdump.hprof

# 优化参数
-XX:+AggressiveOpts \
-XX:+UseFastAccessorMethods \
-XX:+OptimizeStringConcat \
-XX:+UseCompressedOops

# 线程参数
-XX:ThreadStackSize=512k \
-XX:ActiveProcessorCount=16
```

### 堆内存配置

```
# 堆大小建议
小型应用：2-4GB
中型应用：8-16GB
大型应用：32-64GB

# 原则：
1. Xms = Xmx（避免动态调整）
2. 预留30%给操作系统
3. 避免超过32GB（压缩指针失效）
```

---

## 压测与基准测试

### Gatling压测

```scala
class FullLoadTest extends Simulation {
  
  val httpProtocol = http
    .baseUrl("http://localhost:8080")
    .acceptHeader("application/json")
  
  // 场景1：创建工作流
  val createWorkflow = scenario("CreateWorkflow")
    .exec(
      http("创建")
        .post("/api/v1/workflows")
        .body(StringBody("""{"id": "wf-${id}"}"""))
        .check(status.is(200))
    )
  
  // 场景2：执行工作流
  val executeWorkflow = scenario("ExecuteWorkflow")
    .exec(
      http("执行")
        .post("/api/v1/workflows/wf-${workflowId}/execute")
        .check(status.is(200))
    )
  
  // 场景3：查询状态
  val queryStatus = scenario("QueryStatus")
    .exec(
      http("查询")
        .get("/api/v1/workflows/wf-${workflowId}/status")
        .check(status.is(200))
    )
  
  setUp(
    createWorkflow.inject(rampUsers(100) during (10.seconds)),
    executeWorkflow.inject(
      rampUsers(500) during (30.seconds),
      constantUsersPerSec(200) during (300.seconds)
    ),
    queryStatus.inject(constantUsersPerSec(100) during (300.seconds))
  ).protocols(httpProtocol)
  .assertions(
    global.responseTime.percentile(95).lt(100),  // P95 < 100ms
    global.successfulRequests.percent.gt(99)      // 成功率 > 99%
  )
}
```

### 运行压测

```bash
# Gatling
sbt "gatling:testOnly FullLoadTest"

# 生成报告
open target/gatling/fullloadtest-{timestamp}/index.html
```

### 结果分析

```
压测结果示例：

全局指标：
- 请求总数: 1,000,000
- 成功率: 99.98%
- 吞吐量: 3,333 req/s

响应时间分布：
- P50: 15ms
- P75: 28ms
- P95: 65ms
- P99: 98ms
- Max: 250ms

错误分析：
- Timeout: 150 (0.015%)
- 5xx: 50 (0.005%)
```

---

## 监控与分析

### Prometheus指标

```scala
// 使用Kamon + Prometheus
libraryDependencies += "io.kamon" %% "kamon-prometheus" % "2.5.9"

// 暴露指标
Kamon.init()

// 自定义指标
val workflowCounter = Kamon.counter("workflow.execution.total")
val workflowTimer = Kamon.timer("workflow.execution.duration")
val activeWorkflows = Kamon.gauge("workflow.active.count")

// 记录指标
workflowCounter.withTag("status", "success").increment()
val span = workflowTimer.start()
try {
  executeWorkflow()
} finally {
  span.stop()
}
```

### Grafana Dashboard

```json
{
  "dashboard": {
    "title": "Pekko Scheduler Dashboard",
    "panels": [
      {
        "title": "工作流吞吐量",
        "targets": [{
          "expr": "rate(workflow_execution_total[1m])"
        }]
      },
      {
        "title": "响应时间P99",
        "targets": [{
          "expr": "histogram_quantile(0.99, workflow_execution_duration_bucket)"
        }]
      },
      {
        "title": "Actor Mailbox Size",
        "targets": [{
          "expr": "pekko_actor_mailbox_size"
        }]
      },
      {
        "title": "集群节点数",
        "targets": [{
          "expr": "pekko_cluster_members"
        }]
      }
    ]
  }
}
```

### 关键监控指标

| 类别 | 指标 | 告警阈值 |
|-----|------|---------|
| **吞吐量** | workflow.execution.rate | < 100/s |
| **延迟** | workflow.execution.p99 | > 500ms |
| **错误率** | workflow.failure.rate | > 1% |
| **Actor** | actor.mailbox.size | > 10000 |
| **集群** | cluster.unreachable.members | > 0 |
| **JVM** | jvm.memory.used.percent | > 85% |
| **GC** | jvm.gc.pause.max | > 1s |

---

## 生产环境经验

### 性能清单

**部署前检查**：

- [ ] 启用G1GC或ZGC
- [ ] 配置合适的堆内存（Xms=Xmx）
- [ ] 使用高效序列化（Kryo/CBOR）
- [ ] 配置Dispatcher（避免阻塞默认Dispatcher）
- [ ] 启用Cluster Sharding
- [ ] 配置Split Brain Resolver
- [ ] 设置合理的Mailbox容量
- [ ] 启用Prometheus监控
- [ ] 配置Grafana告警

### 常见性能问题

#### 1. Actor Mailbox溢出

**症状**：OOM、响应变慢

**解决**：
```hocon
bounded-mailbox {
  mailbox-capacity = 10000
  mailbox-push-timeout-time = 100ms
}
```

#### 2. 阻塞默认Dispatcher

**症状**：整个系统卡住

**解决**：使用独立Dispatcher处理阻塞操作

#### 3. 序列化瓶颈

**症状**：集群间通信慢

**解决**：使用Kryo替代Java序列化

#### 4. GC频繁

**症状**：吞吐量下降、延迟增加

**解决**：
- 增大堆内存
- 优化对象分配
- 使用ZGC低延迟GC

#### 5. Shard分布不均

**症状**：某些节点负载过高

**解决**：
```hocon
pekko.cluster.sharding {
  least-shard-allocation-strategy {
    rebalance-threshold = 2
  }
}
```

### 性能优化案例

**案例1：MySQL批量写入**
```
优化前：逐条INSERT，100 req/s
优化后：批量INSERT(1000条)，5000 req/s
提升：50x
```

**案例2：Streams并发**
```
优化前：mapAsync(1)，200 req/s
优化后：mapAsync(16) + async边界，3200 req/s
提升：16x
```

**案例3：集群序列化**
```
优化前：Java序列化，500 msg/s
优化后：Kryo序列化，8000 msg/s
提升：16x
```

---

## 总结

### 核心要点

1. **测试先行**
   - 基准测试
   - 负载测试
   - 压力测试
   - 浸泡测试

2. **Actor调优**
   - 自定义Dispatcher
   - 批量处理
   - 避免阻塞

3. **Streams调优**
   - grouped批量（50-100x）
   - mapAsync并发（10-20x）
   - async边界（2-4x）

4. **集群调优**
   - 高效序列化
   - Sharding配置
   - 网络优化

5. **JVM调优**
   - G1GC/ZGC
   - 堆内存配置
   - GC参数

6. **监控告警**
   - Prometheus指标
   - Grafana可视化
   - 关键告警

### 性能提升总结

| 优化项 | 提升倍数 |
|-------|---------|
| 批量处理 | **50-100x** |
| 并发优化 | **10-20x** |
| 序列化 | **10-16x** |
| 多线程 | **2-4x** |
| 缓冲区 | **1.2-1.3x** |

### 下一步

- **第七篇：生产篇** - 监控、运维与最佳实践

---

**项目地址**: https://github.com/Xuxiaotuan/pekko-reference

**作者**: Xuxiaotuan  
**日期**: 2024年11月

---

*下一篇：《生产篇：监控运维与上线最佳实践》（完结篇）*
