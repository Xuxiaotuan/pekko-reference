# Pekko Streams：统一流批处理的利器

> **系列文章：构建下一代任务调度平台**  
> 第四篇：流处理篇 - Pekko Streams深度应用

---

## 📋 目录

- [引言](#引言)
- [Reactive Streams基础](#reactive-streams基础)
- [Source Flow Sink三剑客](#source-flow-sink三剑客)
- [背压机制深度解析](#背压机制深度解析)
- [流批统一处理](#流批统一处理)
- [与Actor模型集成](#与actor模型集成)
- [实战案例](#实战案例)
- [性能优化与最佳实践](#性能优化与最佳实践)

---

## 引言

在[第三篇集群篇](./03_cluster_sharding_practice.md)中，我们构建了分布式的任务调度系统。本文将聚焦**数据处理层**，探讨如何利用Pekko Streams实现高效的流批一体化数据处理。

### 为什么需要Streams？

传统任务调度系统的数据处理痛点：

```
❌ 传统方式：
1. 全量加载数据到内存
   → OOM风险
2. 逐条处理数据
   → 性能低下
3. 流和批分离
   → 代码重复、维护成本高
4. 无背压控制
   → 下游过载

✅ Pekko Streams解决方案：
1. 流式处理
   → 恒定内存占用
2. 批量处理
   → 高吞吐量
3. 统一API
   → 流批复用
4. 背压机制
   → 自动流控
```

### 本文目标

- 📊 **理解Streams**：Reactive Streams规范
- 🔧 **掌握API**：Source/Flow/Sink组合
- 🚰 **背压机制**：自动流量控制
- 🔄 **流批统一**：同一套代码处理流和批
- 🎯 **实战应用**：MySQL、Kafka、文件处理

---

## Reactive Streams基础

### 什么是Reactive Streams？

Reactive Streams是一个**异步流处理标准**，解决背压问题：

```scala
// Reactive Streams规范的4个接口
trait Publisher[T] {
  def subscribe(s: Subscriber[T]): Unit
}

trait Subscriber[T] {
  def onSubscribe(s: Subscription): Unit
  def onNext(t: T): Unit
  def onError(t: Throwable): Unit
  def onComplete(): Unit
}

trait Subscription {
  def request(n: Long): Unit  // 请求n个元素
  def cancel(): Unit          // 取消订阅
}

trait Processor[T, R] extends Subscriber[T] with Publisher[R]
```

**核心思想**：
- **Pull-based**：下游主动请求数据（demand）
- **背压传播**：从Sink到Source的反向信号
- **异步边界**：不同组件可以在不同线程/节点

### Pekko Streams架构

```
         Materialization（物化）
                ↓
Blueprint (DSL) → RunnableGraph → Running Stream
  (图描述)          (可执行图)       (运行中的流)

Source → Flow → Flow → Sink
  ↓       ↓      ↓       ↓
数据源   转换   转换    目标

背压信号 ←←←←←←←←←←←←←←←←
```

---

## Source Flow Sink三剑客

### Source：数据源

Source是数据流的起点，产生元素：

```scala
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.NotUsed

// 1. 从集合创建
val source1: Source[Int, NotUsed] = Source(1 to 100)

// 2. 从单个元素
val source2 = Source.single("hello")

// 3. 从Iterator
val source3 = Source.fromIterator(() => Iterator.from(1))

// 4. 定时生成
val source4 = Source.tick(
  initialDelay = 0.seconds,
  interval = 1.second,
  tick = "tick"
)

// 5. 无限流
val source5 = Source.repeat("repeat")

// 6. Future包装
val source6 = Source.future(Future.successful(42))
```

#### 项目中的MySQL Source

```@/Users/xujiawei/magic/scala-workbench/pekko-reference/pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLSource.scala#36:77
override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = {
  // 解析配置
  val host = node.config.fields.get("host").map(_.convertTo[String]).getOrElse("localhost")
  val database = node.config.fields.get("database").map(_.convertTo[String])
    .getOrElse(throw new IllegalArgumentException("MySQL source缺少database配置"))
  val sql = node.config.fields.get("sql").map(_.convertTo[String])
    .getOrElse(throw new IllegalArgumentException("MySQL source缺少sql配置"))
  val fetchSize = node.config.fields.get("fetchSize").map(_.convertTo[Int]).getOrElse(1000)
  
  onLog(s"连接MySQL: $host/$database")
  onLog(s"执行查询: $sql")
  
  // 生产实现（伪代码）：
  // Source.fromIterator(() => new Iterator[String] {
  //   val resultSet = statement.executeQuery(sql)
  //   def hasNext = resultSet.next()
  //   def next() = resultSetToString(resultSet)
  // })
  
  // 当前：模拟数据
  Source(List(
    "id,name,age,email",
    "1,Alice,25,alice@example.com",
    "2,Bob,30,bob@example.com",
    "3,Charlie,35,charlie@example.com"
  ))
}
```

**设计要点**：
- ✅ 流式查询（setFetchSize）避免OOM
- ✅ Iterator惰性加载
- ✅ 恒定内存占用

### Flow：转换管道

Flow是Source和Sink之间的转换：

```scala
import org.apache.pekko.stream.scaladsl.Flow

// 1. map：1对1转换
val flowMap = Flow[Int].map(_ * 2)

// 2. filter：过滤
val flowFilter = Flow[Int].filter(_ % 2 == 0)

// 3. flatMapConcat：1对多（顺序）
val flowFlatMap = Flow[Int].flatMapConcat(i => 
  Source(List(i, i * 2, i * 3))
)

// 4. grouped：分批
val flowGrouped = Flow[Int].grouped(100)  // 100个一批

// 5. mapAsync：异步转换
val flowAsync = Flow[Int].mapAsync(parallelism = 4) { i =>
  Future {
    // 异步操作，如HTTP请求
    i * 2
  }
}

// 6. buffer：缓冲
val flowBuffer = Flow[Int]
  .buffer(1000, OverflowStrategy.backpressure)

// 7. throttle：限流
val flowThrottle = Flow[Int]
  .throttle(100, 1.second)  // 每秒最多100个
```

#### 实战：数据清洗Flow

```scala
// CSV解析和清洗
val csvCleanFlow = Flow[String]
  .drop(1)  // 跳过header
  .map(_.split(","))
  .filter(_.length >= 4)  // 至少4列
  .map { fields =>
    CleansedRecord(
      id = fields(0).toInt,
      name = fields(1).trim,
      age = fields(2).toInt,
      email = fields(3).toLowerCase
    )
  }
  .filter(_.age > 0 && _.age < 150)  // 年龄合法性
```

### Sink：数据终点

Sink消费数据流：

```scala
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.Done

// 1. foreach：逐个处理
val sink1: Sink[Int, Future[Done]] = 
  Sink.foreach(println)

// 2. fold：聚合
val sink2: Sink[Int, Future[Int]] = 
  Sink.fold(0)(_ + _)  // 求和

// 3. reduce：归约
val sink3: Sink[Int, Future[Int]] = 
  Sink.reduce[Int](_ + _)

// 4. seq：收集到序列
val sink4: Sink[Int, Future[Seq[Int]]] = 
  Sink.seq

// 5. head：取第一个
val sink5: Sink[Int, Future[Int]] = 
  Sink.head

// 6. ignore：丢弃所有
val sink6: Sink[Int, Future[Done]] = 
  Sink.ignore
```

#### 项目中的MySQL Sink

```@/Users/xujiawei/magic/scala-workbench/pekko-reference/pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sinks/MySQLSink.scala#40:84
override def createSink(node: WorkflowDSL.Node, onLog: String => Unit)
                      (implicit ec: ExecutionContext): Sink[String, Future[Done]] = {
  val database = node.config.fields.get("database").map(_.convertTo[String])
    .getOrElse(throw new IllegalArgumentException("MySQL sink缺少database配置"))
  val table = node.config.fields.get("table").map(_.convertTo[String])
    .getOrElse(throw new IllegalArgumentException("MySQL sink缺少table配置"))
  val batchSize = node.config.fields.get("batchSize").map(_.convertTo[Int]).getOrElse(1000)
  
  onLog(s"连接MySQL: $database")
  onLog(s"写入表: $table (批量: $batchSize)")
  
  // 生产实现（伪代码）：
  // Flow[String]
  //   .grouped(batchSize)  // 分批
  //   .mapAsync(1) { batch =>
  //     Future {
  //       // JDBC批量插入
  //       val statement = connection.prepareStatement(sql)
  //       batch.foreach { row =>
  //         statement.addBatch()
  //       }
  //       statement.executeBatch()
  //     }
  //   }
  //   .toMat(Sink.ignore)(Keep.right)
  
  // 当前：模拟写入
  var count = 0
  Sink.foreach[String] { row =>
    count += 1
    if (count % batchSize == 0) {
      onLog(s"已写入 $count 行到 $table")
    }
  }.mapMaterializedValue(_ => Future.successful(Done))
}
```

**设计要点**：
- ✅ grouped批量处理
- ✅ mapAsync并发写入
- ✅ 批量INSERT性能优化

### 组合：构建完整流

```scala
// 完整的ETL流
Source(1 to 1000000)                          // 100万条数据
  .map(i => s"$i,name$i,$i")                  // 转CSV
  .via(csvCleanFlow)                          // 清洗
  .filter(_.age >= 18)                        // 过滤
  .map(recordToSQL)                           // 转SQL
  .grouped(1000)                              // 批量
  .mapAsync(4)(batchInsert)                   // 并发写入
  .runWith(Sink.ignore)                       // 运行

// 分叉：一个Source多个Sink
val source = Source(1 to 100)

val graph = RunnableGraph.fromGraph(GraphDSL.create() { implicit builder =>
  import GraphDSL.Implicits._
  
  val bcast = builder.add(Broadcast[Int](2))
  
  source ~> bcast
           bcast ~> Flow[Int].filter(_ % 2 == 0) ~> Sink.foreach(println)
           bcast ~> Flow[Int].map(_ * 2) ~> Sink.ignore
  
  ClosedShape
})

graph.run()
```

---

## 背压机制深度解析

### 背压原理

```
快速Producer  →  慢速Consumer
     ↓                 ↓
  产生1000/s      处理100/s
     
无背压：
Consumer被淹没 → OOM

有背压：
Consumer: "我只能处理100，等等！"
Producer: "收到，我降速到100/s"
```

### 背压策略

```scala
// 1. 反压（默认）：阻塞上游
Flow[Int]
  .buffer(100, OverflowStrategy.backpressure)

// 2. 丢弃最新：缓冲满时丢弃新元素
Flow[Int]
  .buffer(100, OverflowStrategy.dropNew)

// 3. 丢弃最旧：缓冲满时丢弃老元素
Flow[Int]
  .buffer(100, OverflowStrategy.dropHead)

// 4. 失败：缓冲满时失败
Flow[Int]
  .buffer(100, OverflowStrategy.fail)
```

### 异步边界

```scala
// async边界：不同阶段在不同线程
Source(1 to 100)
  .map(_ * 2)                    // 线程1
  .async                         // 异步边界
  .map(_ + 1)                    // 线程2
  .mapAsync(4)(asyncOperation)   // 4个线程池
  .async                         // 异步边界
  .runWith(Sink.foreach(println)) // 线程3

// 优点：
// 1. 利用多核
// 2. 慢阶段不阻塞快阶段
// 3. 背压跨异步边界传播
```

### 背压监控

```scala
// 添加监控
Source(1 to 1000000)
  .wireTap(i => println(s"Source produced: $i"))
  .via(slowFlow)
  .wireTap(i => println(s"Flow processed: $i"))
  .runWith(Sink.ignore)

// 使用Attributes配置
Source(1 to 100)
  .withAttributes(
    Attributes.inputBuffer(initial = 1, max = 16)
  )
  .runWith(Sink.ignore)
```

---

## 流批统一处理

### 核心思想

```scala
// 同一套代码，流和批都能用！

// 处理逻辑
def processData: Flow[String, Result, NotUsed] = 
  Flow[String]
    .map(parse)
    .filter(validate)
    .map(transform)

// 批处理
Source(readBatchFile("data.csv"))
  .via(processData)
  .runWith(Sink.seq)

// 流处理
KafkaSource(kafkaConfig)
  .via(processData)  // 复用相同逻辑！
  .runWith(KafkaSink(kafkaConfig))
```

### 批处理模式

```scala
// 全量数据处理
def batchETL(inputFile: String, outputFile: String): Future[Done] = {
  FileIO.fromPath(Paths.get(inputFile))
    .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 1024))
    .map(_.utf8String)
    .via(csvCleanFlow)
    .map(_.toCsv)
    .map(s => ByteString(s + "\n"))
    .runWith(FileIO.toPath(Paths.get(outputFile)))
}

// 特点：
// - 有界数据集
// - 有明确开始和结束
// - 可以多次运行
```

### 流处理模式

```scala
// 无界流处理
def streamETL(kafkaConfig: Config): Future[Done] = {
  Consumer.plainSource(kafkaConfig, Subscriptions.topics("input-topic"))
    .map(_.value())
    .via(csvCleanFlow)
    .map(r => ProducerRecord("output-topic", r.id.toString, r.toJson))
    .runWith(Producer.plainSink(producerConfig))
}

// 特点：
// - 无界数据流
// - 持续运行
// - 实时处理
```

### 窗口聚合

```scala
// 时间窗口
Source.tick(0.seconds, 100.millis, 1)
  .groupedWithin(100, 1.second)  // 100个或1秒，先到触发
  .map(_.sum)
  .runWith(Sink.foreach(println))

// 滑动窗口
Source(1 to 100)
  .sliding(5, step = 1)  // 窗口大小5，步长1
  .map(_.sum)
  .runWith(Sink.seq)

// 会话窗口（基于间隔）
Source.tick(0.seconds, Random.nextInt(1000).millis, 1)
  .groupedWithin(Int.MaxValue, 5.seconds)  // 5秒无数据则关闭窗口
  .map(_.sum)
  .runWith(Sink.foreach(println))
```

---

## 与Actor模型集成

### Actor作为Source

```scala
import org.apache.pekko.stream.typed.scaladsl.ActorSource
import org.apache.pekko.stream.OverflowStrategy

// 创建ActorSource
val source: Source[String, ActorRef[String]] = 
  ActorSource.actorRef[String](
    completionMatcher = {
      case "complete" => CompletionStrategy.draining
    },
    failureMatcher = PartialFunction.empty,
    bufferSize = 100,
    overflowStrategy = OverflowStrategy.dropHead
  )

// 物化
val (actorRef, stream) = source
  .toMat(Sink.foreach(println))(Keep.both)
  .run()

// 发送消息到Stream
actorRef ! "hello"
actorRef ! "world"
actorRef ! "complete"
```

### Actor作为Sink

```scala
import org.apache.pekko.stream.typed.scaladsl.ActorSink

// 目标Actor
val targetActor = system.spawn(
  Behaviors.receiveMessage[String] {
    case msg =>
      println(s"Received: $msg")
      Behaviors.same
  },
  "target"
)

// 创建ActorSink
val sink = ActorSink.actorRef[String](
  ref = targetActor,
  onCompleteMessage = "stream-completed",
  onFailureMessage = (ex: Throwable) => s"stream-failed: ${ex.getMessage}"
)

// 运行
Source(List("a", "b", "c"))
  .runWith(sink)
```

### 集成示例：WorkflowActor + Streams

```scala
object WorkflowActor {
  
  case class StreamData(data: String) extends Command
  case object StreamCompleted extends Command
  case class StreamFailed(ex: Throwable) extends Command
  
  def executing(/*...*/): Behavior[Command] = {
    Behaviors.setup { context =>
      
      // 创建Stream并连接到Actor
      val source = Source(dataList)
      val sink = ActorSink.actorRef[StreamData](
        ref = context.self,
        onCompleteMessage = StreamCompleted,
        onFailureMessage = StreamFailed
      )
      
      source
        .map(StreamData)
        .runWith(sink)(context.system)
      
      Behaviors.receiveMessage {
        case StreamData(data) =>
          // 处理流数据
          context.log.info(s"Processing: $data")
          Behaviors.same
        
        case StreamCompleted =>
          context.log.info("Stream completed")
          completed(/*...*/)
        
        case StreamFailed(ex) =>
          context.log.error("Stream failed", ex)
          failed(/*...*/)
      }
    }
  }
}
```

---

## 实战案例

### 案例1：MySQL → Transform → MySQL

```scala
// 完整的ETL流
def mysqlToMysqlETL(): Future[Done] = {
  // Source: 从MySQL读取
  val source = Source.fromIterator(() => {
    val conn = getConnection(sourceDB)
    val rs = conn.createStatement().executeQuery(sql)
    new Iterator[String] {
      def hasNext = rs.next()
      def next() = resultSetToCSV(rs)
    }
  })
  
  // Transform: 数据转换
  val transform = Flow[String]
    .map(_.split(","))
    .filter(_.length >= 3)
    .map { fields =>
      // 业务逻辑
      s"${fields(0)},${fields(1).toUpperCase},${fields(2).toInt * 2}"
    }
  
  // Sink: 写入MySQL
  val sink = Flow[String]
    .grouped(1000)  // 批量
    .mapAsync(4) { batch =>
      Future {
        val conn = getConnection(targetDB)
        val stmt = conn.prepareStatement(insertSQL)
        batch.foreach { row =>
          // 设置参数
          stmt.addBatch()
        }
        stmt.executeBatch()
        Done
      }
    }
    .toMat(Sink.ignore)(Keep.right)
  
  // 运行
  source
    .via(transform)
    .runWith(sink)
}
```

### 案例2：Kafka → Window → Kafka

```scala
// 实时窗口聚合
def kafkaWindowAggregation(): Future[Done] = {
  Consumer.plainSource(consumerSettings, Subscriptions.topics("input"))
    .map(record => parseEvent(record.value()))
    .groupedWithin(1000, 10.seconds)  // 1000个或10秒
    .map { events =>
      // 窗口聚合
      val grouped = events.groupBy(_.userId)
      grouped.map { case (userId, userEvents) =>
        AggregatedEvent(
          userId = userId,
          count = userEvents.size,
          totalAmount = userEvents.map(_.amount).sum,
          windowEnd = Instant.now()
        )
      }
    }
    .mapConcat(identity)  // 展平
    .map(event => ProducerRecord("output", event.userId, event.toJson))
    .runWith(Producer.plainSink(producerSettings))
}
```

### 案例3：文件监控 → 实时处理

```scala
// 监控目录，实时处理新文件
def watchAndProcess(directory: Path): Future[Done] = {
  // 使用DirectoryChangesSource（需要额外库）
  DirectoryChangesSource(directory, pollInterval = 1.second, maxBufferSize = 1000)
    .filter(_.path.toString.endsWith(".csv"))
    .mapAsync(parallelism = 4) { path =>
      // 处理每个文件
      FileIO.fromPath(path.path)
        .via(Framing.delimiter(ByteString("\n"), 1024))
        .map(_.utf8String)
        .via(processFlow)
        .runWith(Sink.seq)
    }
    .runWith(Sink.ignore)
}
```

---

## 性能优化与最佳实践

### 1. 合理设置缓冲区

```scala
// 默认缓冲区太小
Source(1 to 1000000)
  .map(expensiveOperation)  // 默认buffer=16
  .runWith(Sink.ignore)

// 优化：增大缓冲区
Source(1 to 1000000)
  .withAttributes(Attributes.inputBuffer(initial = 128, max = 256))
  .map(expensiveOperation)
  .runWith(Sink.ignore)

// 提升：可能提高20-30%吞吐量
```

### 2. 批量处理

```scala
// ❌ 逐条处理
Source(records)
  .mapAsync(1)(record => database.insert(record))

// ✅ 批量处理
Source(records)
  .grouped(1000)
  .mapAsync(4)(batch => database.batchInsert(batch))

// 提升：10-100倍性能
```

### 3. 异步边界

```scala
// ❌ 单线程
Source(data)
  .map(cpuIntensive1)   // 阻塞
  .map(cpuIntensive2)   // 阻塞
  .runWith(Sink.ignore)

// ✅ 多线程
Source(data)
  .map(cpuIntensive1)
  .async  // 异步边界
  .map(cpuIntensive2)
  .async
  .runWith(Sink.ignore)

// 提升：充分利用多核
```

### 4. mapAsync并发度

```scala
// 调优并发度
Source(urls)
  .mapAsync(parallelism = 8) { url =>  // 根据IO密集度调整
    Http().singleRequest(HttpRequest(uri = url))
  }
  .runWith(Sink.ignore)

// 经验值：
// CPU密集：核心数
// IO密集：核心数 * 2-4
// 网络请求：10-20
```

### 5. 监控与调试

```scala
// 添加监控点
Source(data)
  .wireTap(x => metrics.increment("source.produced"))
  .map(transform)
  .wireTap(x => metrics.increment("transform.completed"))
  .runWith(Sink.foreach(x => metrics.increment("sink.consumed")))

// 日志调试
Source(data)
  .log("source")
  .withAttributes(Attributes.logLevels(onElement = LogLevels.Info))
  .runWith(Sink.ignore)
```

### 最佳实践总结

| 场景 | 建议 | 原因 |
|-----|------|------|
| 大文件处理 | 使用Framing分行 | 避免OOM |
| 数据库操作 | grouped + mapAsync | 批量+并发 |
| HTTP请求 | mapAsync(10-20) | 网络IO并发 |
| CPU密集 | 使用async边界 | 多核利用 |
| 背压敏感 | buffer配置 | 流控优化 |
| 调试 | wireTap + log | 问题定位 |

---

## 总结

### 核心要点

1. **Streams优势**
   - 流批统一API
   - 自动背压控制
   - 恒定内存占用
   - 组合式构建

2. **三大组件**
   - Source：数据源（MySQL、Kafka、File）
   - Flow：转换管道（map、filter、grouped）
   - Sink：数据终点（写入、聚合、收集）

3. **背压机制**
   - Pull-based模型
   - 自动流量控制
   - 防止下游过载
   - 异步边界传播

4. **流批统一**
   - 相同处理逻辑
   - 不同数据源
   - 窗口聚合
   - 实时+离线

5. **性能优化**
   - 批量处理（10-100x）
   - 异步边界（多核）
   - mapAsync并发
   - 缓冲区调优

### 性能对比

| 场景 | 传统方式 | Pekko Streams | 提升 |
|-----|---------|--------------|------|
| 100万行CSV | 全量加载OOM | 流式处理恒定2GB | 内存 ∞ |
| MySQL导入 | 逐条INSERT | 批量1000条 | **50x** |
| 并发HTTP | 同步请求 | mapAsync(20) | **20x** |
| 实时聚合 | 轮询DB | 流式窗口 | 延迟 **100x** |

### 下一步

- **第五篇：持久化篇** - Event Sourcing与状态恢复
- **第六篇：性能篇** - 调优与压测
- **第七篇：生产篇** - 监控运维与最佳实践

---

**项目地址**: https://github.com/Xuxiaotuan/pekko-reference

**作者**: Xuxiaotuan  
**日期**: 2024年11月

---

*下一篇：《持久化篇：Event Sourcing让系统永不丢失状态》*
