# Actor系统的性能剖析

> **深度分析系列** - 第十三篇：深入Actor系统的性能测量与优化

---

## 📋 目录

- [引言](#引言)
- [性能指标](#性能指标)
- [Mailbox性能](#mailbox性能)
- [Dispatcher调优](#dispatcher调优)
- [消息序列化](#消息序列化)
- [JMH基准测试](#jmh基准测试)
- [性能瓶颈定位](#性能瓶颈定位)
- [最佳实践](#最佳实践)
- [总结](#总结)

---

## 引言

性能优化的前提是**准确测量**。

```
常见误区：
❌ 凭感觉优化
❌ 过早优化
❌ 优化错误的地方

正确方法：
✓ 建立基准测试
✓ 测量关键指标
✓ 定位瓶颈
✓ 针对性优化
✓ 验证效果
```

---

## 性能指标

### 核心指标

**1. Throughput（吞吐量）**
```
定义：单位时间处理的消息数
单位：messages/second

示例：
100万条消息，耗时10秒
Throughput = 1,000,000 / 10 = 100,000 msg/s
```

**2. Latency（延迟）**
```
定义：单条消息处理时间
单位：milliseconds

关键指标：
- P50：50%的请求延迟
- P95：95%的请求延迟
- P99：99%的请求延迟
- P99.9：99.9%的请求延迟

示例：
P50 = 5ms   → 一半请求<5ms
P95 = 20ms  → 95%请求<20ms
P99 = 50ms  → 99%请求<50ms
```

**3. CPU利用率**
```
指标：CPU使用百分比
目标：70-80%（充分利用，保留缓冲）

过低：资源浪费
过高：可能过载、延迟增加
```

**4. 内存占用**
```
关键指标：
- Heap使用量
- GC频率和时间
- Mailbox积压

监控：
- Minor GC：<100ms
- Full GC：避免或<1s
```

### Throughput vs Latency权衡

```
矛盾关系：
高吞吐 ↔ 低延迟

高吞吐策略：
- 批量处理
- 异步I/O
- 减少上下文切换
→ 可能增加延迟

低延迟策略：
- 立即处理
- 减少队列
- 更多线程
→ 可能降低吞吐

选择：根据业务需求
```

---

## Mailbox性能

### Mailbox类型对比

```scala
// 1. UnboundedMailbox（默认）
val unbounded = MailboxSelector.fromConfig("unbounded")

特点：
- 无限容量
- MPSC无锁队列
- 高吞吐
- 可能OOM

性能：
- 入队：~10ns
- 出队：~10ns
- 吞吐：~100M msg/s（单核）

// 2. BoundedMailbox
val bounded = MailboxSelector.bounded(capacity = 1000)

特点：
- 有限容量
- 背压机制
- 防止OOM

性能：
- 入队：~50ns（阻塞检查）
- 出队：~10ns
- 吞吐：~20M msg/s

// 3. PriorityMailbox
class MyPriorityMailbox extends UnboundedPriorityMailbox {
  def priority(msg: Any): Int = msg match {
    case HighPriority => 0
    case NormalPriority => 1
    case LowPriority => 2
  }
}

特点：
- 优先级队列
- 自动排序

性能：
- 入队：~100ns（排序开销）
- 出队：~20ns
- 吞吐：~10M msg/s
```

### 基准测试

```scala
import org.openjdk.jmh.annotations._
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class MailboxBenchmark {
  
  val unboundedQueue = new MpscUnboundedArrayQueue[String](1024)
  val boundedQueue = new MpscBoundedQueue[String](1024)
  
  @Benchmark
  def unboundedEnqueue(): Boolean = {
    unboundedQueue.offer("message")
  }
  
  @Benchmark
  def unboundedDequeue(): String = {
    unboundedQueue.poll()
  }
  
  @Benchmark
  def boundedEnqueue(): Boolean = {
    boundedQueue.offer("message")
  }
  
  @Benchmark
  def boundedDequeue(): String = {
    boundedQueue.poll()
  }
}

// 结果（消息/秒）：
// unboundedEnqueue:  100,000,000 ops/s
// unboundedDequeue:  100,000,000 ops/s
// boundedEnqueue:     20,000,000 ops/s
// boundedDequeue:     80,000,000 ops/s
```

### 选择建议

| 场景 | 推荐Mailbox | 原因 |
|-----|------------|------|
| 高吞吐 | Unbounded | 最快 |
| 防止OOM | Bounded | 背压 |
| 优先级 | Priority | 业务需求 |
| 流控 | Bounded | 限制速率 |

---

## Dispatcher调优

### Dispatcher类型

**1. Fork-Join Dispatcher（默认）**
```hocon
default-dispatcher {
  type = Dispatcher
  executor = "fork-join-executor"
  
  fork-join-executor {
    parallelism-min = 8      # 最小线程数
    parallelism-factor = 3.0  # 因子×CPU核心数
    parallelism-max = 64     # 最大线程数
  }
  
  throughput = 5  # 每次处理5条消息
}

计算：
CPU核心数 = 8
线程数 = min(max(8, 8×3), 64) = min(24, 64) = 24
```

**2. Thread Pool Dispatcher**
```hocon
blocking-io-dispatcher {
  type = Dispatcher
  executor = "thread-pool-executor"
  
  thread-pool-executor {
    fixed-pool-size = 32  # 固定线程数
  }
  
  throughput = 1  # 每次1条（快速响应）
}

适用：阻塞I/O操作
```

### Throughput参数

```scala
// throughput = 每次处理多少条消息后切换线程

throughput = 1:
  取1条 → 处理 → 切换线程
  优势：低延迟
  劣势：高上下文切换开销

throughput = 10:
  取10条 → 处理10条 → 切换线程
  优势：减少切换，高吞吐
  劣势：延迟增加

throughput = 100:
  取100条 → 处理100条 → 切换线程
  优势：最高吞吐
  劣势：延迟更高，不公平
```

### 基准测试

```scala
@State(Scope.Benchmark)
class DispatcherBenchmark {
  
  implicit val system: ActorSystem = ActorSystem("bench")
  
  @Setup
  def setup(): Unit = {
    // 创建不同throughput配置的Actor
  }
  
  @Benchmark
  def throughput1(): Unit = {
    // throughput = 1
    sendMessages(actor1, 1000)
  }
  
  @Benchmark
  def throughput10(): Unit = {
    // throughput = 10
    sendMessages(actor10, 1000)
  }
  
  @Benchmark
  def throughput100(): Unit = {
    // throughput = 100
    sendMessages(actor100, 1000)
  }
}

// 结果：
// throughput=1:   10,000 msg/s, 延迟 P50=5ms
// throughput=10:  50,000 msg/s, 延迟 P50=10ms
// throughput=100: 80,000 msg/s, 延迟 P50=50ms
```

### 调优建议

```hocon
# CPU密集型
cpu-dispatcher {
  type = Dispatcher
  executor = "fork-join-executor"
  fork-join-executor {
    parallelism-min = 8
    parallelism-max = 64
  }
  throughput = 10  # 高吞吐
}

# I/O密集型
io-dispatcher {
  type = Dispatcher
  executor = "thread-pool-executor"
  thread-pool-executor {
    fixed-pool-size = 100  # 更多线程
  }
  throughput = 1  # 低延迟
}

# 低延迟要求
low-latency-dispatcher {
  type = Dispatcher
  executor = "fork-join-executor"
  fork-join-executor {
    parallelism-min = 16
    parallelism-max = 32
  }
  throughput = 1  # 最低延迟
}
```

---

## 消息序列化

### 序列化开销

```scala
// Java序列化（默认）
case class User(id: String, name: String, age: Int)

// 测试
val user = User("123", "Alice", 30)

// Java序列化
val javaBytes = serializeJava(user)
println(s"Java: ${javaBytes.length} bytes")  // ~200 bytes

// JSON序列化
val jsonBytes = serializeJson(user)
println(s"JSON: ${jsonBytes.length} bytes")  // ~50 bytes

// Protobuf序列化
val pbBytes = serializeProtobuf(user)
println(s"Protobuf: ${pbBytes.length} bytes")  // ~20 bytes
```

### 序列化性能对比

```scala
@Benchmark
def javaSerialize(): Array[Byte] = {
  val baos = new ByteArrayOutputStream()
  val oos = new ObjectOutputStream(baos)
  oos.writeObject(user)
  baos.toByteArray
}

@Benchmark
def jsonSerialize(): Array[Byte] = {
  Jackson.toJson(user).getBytes
}

@Benchmark
def protobufSerialize(): Array[Byte] = {
  UserProto.toByteArray(user)
}

// 结果（操作/秒）：
// Java:     10,000 ops/s
// JSON:    100,000 ops/s
// Protobuf: 500,000 ops/s

// 大小对比：
// Java:     200 bytes
// JSON:      50 bytes
// Protobuf:  20 bytes
```

### 优化建议

**1. 使用高效序列化**
```hocon
pekko.actor {
  serializers {
    jackson = "org.apache.pekko.serialization.jackson.JacksonJsonSerializer"
    proto = "org.apache.pekko.serialization.ProtobufSerializer"
  }
  
  serialization-bindings {
    "com.example.MyMessage" = jackson
    "com.example.LargeMessage" = proto
  }
}
```

**2. 避免序列化大对象**
```scala
// ❌ 避免
case class HugeMessage(data: Array[Byte])  // 1MB数据

// ✓ 推荐
case class MessageRef(dataId: String)  // 只传递引用
// 接收方从缓存/数据库获取实际数据
```

**3. 压缩**
```scala
// 大消息压缩
def compress(data: Array[Byte]): Array[Byte] = {
  val compressor = new GZIPOutputStream(...)
  compressor.write(data)
  compressed
}

// 适用：>1KB的消息
```

---

## JMH基准测试

### 测试框架

```scala
// build.sbt
libraryDependencies += "org.openjdk.jmh" % "jmh-core" % "1.36"
libraryDependencies += "org.openjdk.jmh" % "jmh-generator-annprocess" % "1.36"
```

### Actor吞吐量测试

```scala
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(1)
class ActorThroughputBenchmark {
  
  implicit var system: ActorSystem[_] = _
  var testActor: ActorRef[Int] = _
  
  @Setup
  def setup(): Unit = {
    system = ActorSystem(Behaviors.empty, "bench")
    
    testActor = system.systemActorOf(
      Behaviors.receiveMessage[Int] { msg =>
        // 简单处理
        Behaviors.same
      },
      "test-actor"
    )
  }
  
  @TearDown
  def teardown(): Unit = {
    system.terminate()
    Await.result(system.whenTerminated, 10.seconds)
  }
  
  @Benchmark
  def sendMessages(): Unit = {
    (1 to 1000).foreach { i =>
      testActor ! i
    }
  }
}

// 运行：
// sbt "Jmh/run -i 10 -wi 5 -f 1 ActorThroughputBenchmark"

// 结果示例：
// Benchmark                              Mode  Cnt      Score   Error  Units
// ActorThroughputBenchmark.sendMessages  thrpt   10  50000.123 ± 100  ops/s
```

### Actor延迟测试

```scala
@BenchmarkMode(Array(Mode.SampleTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
class ActorLatencyBenchmark {
  
  @Benchmark
  def measureLatency(): Unit = {
    val promise = Promise[Int]()
    
    testActor ! Request(promise)
    
    Await.result(promise.future, 1.second)
  }
}

// 结果示例：
// Benchmark                           Mode    Cnt   Score   Error  Units
// ActorLatencyBenchmark.measureLatency sample 1000   5.2  ± 0.5  us/op
// p50  =   4.5 us
// p95  =  10.2 us
// p99  =  25.8 us
// p99.9 = 100.3 us
```

---

## 性能瓶颈定位

### 1. 系统监控

```scala
// Kamon集成
libraryDependencies += "io.kamon" %% "kamon-bundle" % "2.6.0"
libraryDependencies += "io.kamon" %% "kamon-prometheus" % "2.6.0"

// 启用
Kamon.init()

// 监控指标
val actorProcessingTime = Kamon.histogram("actor.processing.time")
val mailboxSize = Kamon.gauge("actor.mailbox.size")

// 记录
actorProcessingTime.record(processingTimeMs)
mailboxSize.update(mailbox.size)
```

### 2. JFR（Java Flight Recorder）

```bash
# 启动时启用JFR
java -XX:StartFlightRecording=duration=60s,filename=recording.jfr \
     -jar myapp.jar

# 分析
jfr print recording.jfr

# 关键指标：
# - GC时间和频率
# - 线程状态
# - 锁竞争
# - CPU使用
```

### 3. 异步Profiler

```bash
# 下载：https://github.com/jvm-profiling-tools/async-profiler

# CPU profiling
./profiler.sh -d 30 -f cpu-profile.html <pid>

# 内存分配
./profiler.sh -d 30 -e alloc -f alloc-profile.html <pid>

# 火焰图：直观显示热点
```

### 4. 自定义监控

```scala
// Actor处理时间监控
object MonitoredActor {
  
  def apply(): Behavior[Command] = {
    Behaviors.receive { (ctx, msg) =>
      val start = System.nanoTime()
      
      try {
        // 处理消息
        handleMessage(msg)
      } finally {
        val duration = (System.nanoTime() - start) / 1000000.0
        
        // 记录
        metrics.recordProcessingTime(duration)
        
        // 告警
        if (duration > 100) {
          ctx.log.warn(s"Slow message processing: ${duration}ms")
        }
      }
      
      Behaviors.same
    }
  }
}
```

### 常见瓶颈

**1. Mailbox积压**
```
症状：mailbox.size持续增长
原因：处理速度<接收速度
解决：
- 增加Actor实例
- 优化处理逻辑
- 使用BoundedMailbox背压
```

**2. 频繁GC**
```
症状：Minor GC频繁（<1秒）
原因：大量临时对象
解决：
- 对象池
- 减少消息拷贝
- 增加堆内存
```

**3. 线程竞争**
```
症状：CPU利用率低但延迟高
原因：锁竞争
解决：
- 检查共享状态
- 使用无锁数据结构
- 减少同步
```

**4. 序列化瓶颈**
```
症状：网络消息延迟高
原因：序列化慢
解决：
- 使用高效序列化（Protobuf）
- 减小消息大小
- 压缩大消息
```

---

## 最佳实践

### 1. 建立性能基准

```scala
// 定期运行基准测试
object PerformanceBaseline {
  
  def runBaseline(): BaselineReport = {
    val throughputTest = new ThroughputTest()
    val latencyTest = new LatencyTest()
    
    BaselineReport(
      throughput = throughputTest.run(),  // 50,000 msg/s
      p50Latency = latencyTest.p50(),     // 5ms
      p99Latency = latencyTest.p99()      // 20ms
    )
  }
  
  // 比较
  def compare(current: BaselineReport, baseline: BaselineReport): Unit = {
    val throughputChange = (current.throughput - baseline.throughput) / baseline.throughput
    
    if (throughputChange < -0.1) {
      alert("Throughput decreased by ${throughputChange * 100}%")
    }
  }
}
```

### 2. 持续监控

```scala
// 生产环境监控
val metrics = MetricsCollector()

// 吞吐量
metrics.meter("actor.messages.processed").mark()

// 延迟（直方图）
metrics.histogram("actor.processing.latency").update(latencyMs)

// Mailbox大小
metrics.gauge("actor.mailbox.size").set(mailbox.size)

// 告警规则
if (mailbox.size > 10000) {
  alerting.send("Mailbox overload", severity = High)
}

if (p99Latency > 100) {
  alerting.send("High latency", severity = Medium)
}
```

### 3. 性能测试环境

```
要求：
1. 独立环境（避免干扰）
2. 生产级配置
3. 真实负载
4. 可重复
5. 自动化

工具：
- JMH：微基准测试
- Gatling：负载测试
- Grafana：可视化
```

### 4. 优化检查清单

```
□ Dispatcher配置合理
□ Mailbox类型正确
□ throughput参数优化
□ 序列化高效
□ 无频繁GC
□ CPU利用率70-80%
□ Mailbox无积压
□ 延迟在SLA内
□ 有性能监控
□ 有告警机制
```

---

## 总结

### 核心要点

**1. 性能指标**
- Throughput：吞吐量
- Latency：P50/P95/P99
- CPU利用率：70-80%
- GC：Minor<100ms

**2. Mailbox选择**
- Unbounded：高吞吐（默认）
- Bounded：防OOM
- Priority：业务需求

**3. Dispatcher调优**
- Fork-Join：CPU密集
- Thread-Pool：I/O密集
- throughput：平衡延迟吞吐

**4. 序列化优化**
- Protobuf最快
- 避免大对象
- 压缩>1KB消息

**5. 瓶颈定位**
- Kamon监控
- JFR分析
- 异步Profiler
- 自定义埋点

### 性能对比表

| 组件 | 默认 | 优化后 | 提升 |
|-----|------|-------|------|
| Mailbox | Unbounded | 根据场景 | - |
| Dispatcher | throughput=5 | 调整 | 2-5x |
| 序列化 | Java | Protobuf | 50x |
| 监控 | 无 | Kamon | - |

### 下一篇预告

**《集群性能优化与网络调优》**
- 网络序列化优化
- Gossip性能调优
- 集群大小与性能
- 跨数据中心优化

---

**项目地址**: https://github.com/Xuxiaotuan/pekko-reference

**作者**: Xuxiaotuan  
**日期**: 2024年11月
