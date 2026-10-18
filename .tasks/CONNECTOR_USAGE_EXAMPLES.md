# Connector使用示例

> 完整的MySQL连接器使用示例和测试场景

---

## 📚 基础使用

### 1. MySQL Source - 读取数据

```scala
import cn.xuyinyin.pekko.connector.mysql._
import cn.xuyinyin.pekko.connector.api._
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink

implicit val system = ActorSystem("MySyncSystem")
implicit val ec = system.dispatcher

// 配置数据源
val sourceConfig = MySQLSourceConfig(
  host = "localhost",
  port = 3306,
  database = "source_db",
  table = "users",
  username = "root",
  password = "password",
  fetchSize = 1000  // 每次读取1000行
)

// 创建连接器
val sourceConnector = new MySQLSourceConnector()

// 创建Stream Source
val source = sourceConnector.createSource(sourceConfig)

// 消费数据（打印到控制台）
source
  .runForeach { record =>
    println(s"读取到数据: ${record.toJsonString}")
  }
```

---

### 2. MySQL Sink - 写入数据

```scala
import cn.xuyinyin.pekko.connector.mysql._
import scala.concurrent.ExecutionContext

implicit val ec: ExecutionContext = system.dispatcher

// 配置数据目标
val sinkConfig = MySQLSinkConfig(
  host = "localhost",
  port = 3306,
  database = "target_db",
  table = "users_copy",
  username = "root",
  password = "password",
  batchSize = 1000,
  writeMode = WriteMode.INSERT
)

// 创建连接器
val sinkConnector = new MySQLSinkConnector()

// 创建Stream Sink
val sink = sinkConnector.createSink(sinkConfig)

// 写入数据
source
  .runWith(sink)
  .onComplete {
    case Success(result) =>
      println(s"同步完成: ${result.success}成功, ${result.failed}失败")
      println(s"吞吐量: ${result.throughput} 行/秒")
    case Failure(ex) =>
      println(s"同步失败: ${ex.getMessage}")
  }
```

---

### 3. 完整的同步Pipeline

```scala
import cn.xuyinyin.pekko.connector.mysql._
import org.apache.pekko.stream.scaladsl.Flow

// Source配置
val sourceConfig = MySQLSourceConfig(
  host = "source-db.example.com",
  database = "production",
  table = "orders",
  username = "reader",
  password = "read_password"
)

// Sink配置
val sinkConfig = MySQLSinkConfig(
  host = "target-db.example.com",
  database = "warehouse",
  table = "orders",
  username = "writer",
  password = "write_password",
  writeMode = WriteMode.UPSERT  // UPSERT模式（幂等）
)

// 创建连接器
val sourceConnector = new MySQLSourceConnector()
val sinkConnector = new MySQLSinkConnector()

// 构建Pipeline
sourceConnector
  .createSource(sourceConfig)
  
  // 字段映射
  .map { record =>
    record.mapFields(Map(
      "order_id" -> "id",
      "customer_name" -> "customer",
      "order_amount" -> "amount"
    ))
  }
  
  // 数据过滤
  .filter { record =>
    val amount = record.getAs[Double]("amount")
    amount.exists(_ > 100.0)  // 只同步金额>100的订单
  }
  
  // 数据转换
  .map { record =>
    // 添加时间戳
    record.copy(
      metadata = record.metadata.withExtra("sync_time", System.currentTimeMillis().toString)
    )
  }
  
  // 写入目标
  .runWith(sinkConnector.createSink(sinkConfig))
  .onComplete {
    case Success(result) =>
      println(s"""
        |同步完成:
        | - 成功: ${result.success}
        | - 失败: ${result.failed}
        | - 总计: ${result.total}
        | - 成功率: ${result.successRate}%
        | - 耗时: ${result.durationMs}ms
        | - 吞吐量: ${result.throughput} 行/秒
      """.stripMargin)
      
    case Failure(ex) =>
      println(s"同步失败: ${ex.getMessage}")
      ex.printStackTrace()
  }
```

---

## 🔧 高级用法

### 1. 自定义SQL查询

```scala
val sourceConfig = MySQLSourceConfig(
  host = "localhost",
  database = "production",
  table = "orders",  // table参数在有query时会被忽略
  username = "root",
  password = "password",
  query = Some("""
    SELECT 
      o.order_id,
      o.customer_id,
      o.order_amount,
      c.customer_name,
      c.customer_email
    FROM orders o
    JOIN customers c ON o.customer_id = c.id
    WHERE o.created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
      AND o.status = 'completed'
    ORDER BY o.order_id
  """)
)
```

### 2. 分区查询（并行读取）

```scala
// 分区1: id 1-1000000
val partition1Config = MySQLSourceConfig(
  host = "localhost",
  database = "bigdata",
  table = "events",
  username = "root",
  password = "password",
  query = Some("SELECT * FROM events WHERE id >= 1 AND id < 1000000")
)

// 分区2: id 1000000-2000000
val partition2Config = MySQLSourceConfig(
  host = "localhost",
  database = "bigdata",
  table = "events",
  username = "root",
  password = "password",
  query = Some("SELECT * FROM events WHERE id >= 1000000 AND id < 2000000")
)

// 并行读取
val source1 = new MySQLSourceConnector().createSource(partition1Config)
val source2 = new MySQLSourceConnector().createSource(partition2Config)

// 合并流
import org.apache.pekko.stream.scaladsl.Source

Source.combine(source1, source2)(Merge(_))
  .runWith(sink)
```

### 3. UPSERT模式（幂等写入）

```scala
val sinkConfig = MySQLSinkConfig(
  host = "localhost",
  database = "warehouse",
  table = "dim_users",
  username = "root",
  password = "password",
  writeMode = WriteMode.UPSERT  // INSERT ON DUPLICATE KEY UPDATE
)

// 多次运行同一个同步任务，数据不会重复
// 前提：表需要有唯一键或主键
```

### 4. 配置验证

```scala
val config = MySQLSourceConfig(
  host = "localhost",
  database = "",  // 错误：database为空
  table = "users",
  username = "root",
  password = "password"
)

// 验证配置
config.validate() match {
  case Left(error) => println(s"配置错误: $error")
  case Right(_) => println("配置正确")
}

// 测试连接
val connector = new MySQLSourceConnector()
connector.testConnection(config) match {
  case Left(error) => println(s"连接失败: $error")
  case Right(_) => println("连接成功")
}
```

---

## 🎯 实际场景示例

### 场景1：数据仓库ETL

```scala
/**
 * 场景：每天凌晨从生产库同步订单数据到数据仓库
 */
val etlPipeline = sourceConnector
  .createSource(MySQLSourceConfig(
    host = "prod-db.company.com",
    database = "ecommerce",
    table = "orders",
    username = "etl_user",
    password = "***",
    query = Some("""
      SELECT * FROM orders 
      WHERE DATE(created_at) = CURDATE() - INTERVAL 1 DAY
    """)
  ))
  .map { record =>
    // 数据清洗和转换
    record
  }
  .runWith(sinkConnector.createSink(MySQLSinkConfig(
    host = "warehouse-db.company.com",
    database = "dwh",
    table = "fact_orders",
    username = "etl_writer",
    password = "***",
    writeMode = WriteMode.INSERT
  )))
```

### 场景2：数据库迁移

```scala
/**
 * 场景：从旧数据库迁移到新数据库
 */
val migrationTables = List("users", "orders", "products", "categories")

migrationTables.foreach { table =>
  println(s"开始迁移表: $table")
  
  new MySQLSourceConnector()
    .createSource(MySQLSourceConfig(
      host = "old-db.company.com",
      database = "old_system",
      table = table,
      username = "migration",
      password = "***"
    ))
    .runWith(new MySQLSinkConnector().createSink(MySQLSinkConfig(
      host = "new-db.company.com",
      database = "new_system",
      table = table,
      username = "migration",
      password = "***",
      writeMode = WriteMode.INSERT
    )))
    .onComplete {
      case Success(result) =>
        println(s"$table 迁移完成: ${result.success}行")
      case Failure(ex) =>
        println(s"$table 迁移失败: ${ex.getMessage}")
    }
}
```

### 场景3：实时数据备份

```scala
/**
 * 场景：实时备份关键表到备份库
 */
import scala.concurrent.duration._

// 每5分钟增量备份
system.scheduler.scheduleAtFixedRate(
  initialDelay = 0.seconds,
  interval = 5.minutes
) { () =>
  new MySQLSourceConnector()
    .createSource(MySQLSourceConfig(
      host = "master-db.company.com",
      database = "production",
      table = "critical_data",
      username = "backup",
      password = "***",
      query = Some("""
        SELECT * FROM critical_data 
        WHERE updated_at >= NOW() - INTERVAL 5 MINUTE
      """)
    ))
    .runWith(new MySQLSinkConnector().createSink(MySQLSinkConfig(
      host = "backup-db.company.com",
      database = "backup",
      table = "critical_data",
      username = "backup",
      password = "***",
      writeMode = WriteMode.UPSERT
    )))
}
```

---

## ⚡ 性能优化建议

### 1. 调整fetchSize

```scala
// 大数据量：增大fetchSize
val config = MySQLSourceConfig(
  // ...
  fetchSize = 10000  // 默认1000，增大到10000
)
```

### 2. 调整batchSize

```scala
// 批量写入：增大batchSize
val config = MySQLSinkConfig(
  // ...
  batchSize = 5000  // 默认1000，增大到5000
)
```

### 3. 增加连接池

```scala
val config = MySQLSinkConfig(
  // ...
  maxConnections = 20  // 默认10，增大到20
)
```

### 4. 使用索引查询

```scala
// 在分区字段上创建索引
val config = MySQLSourceConfig(
  // ...
  query = Some("""
    SELECT * FROM orders 
    WHERE id >= ? AND id < ?  -- id字段需要有索引
  """)
)
```

---

## 🧪 测试用例

### 单元测试示例

```scala
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MySQLConnectorSpec extends AnyFlatSpec with Matchers {
  
  "MySQLSourceConfig" should "验证配置正确性" in {
    val config = MySQLSourceConfig(
      host = "localhost",
      database = "test",
      table = "users",
      username = "root",
      password = "password"
    )
    
    config.validate() shouldBe Right(())
  }
  
  it should "检测配置错误" in {
    val config = MySQLSourceConfig(
      host = "localhost",
      database = "",  // 空database
      table = "users",
      username = "root",
      password = "password"
    )
    
    config.validate() shouldBe Left("database不能为空")
  }
  
  "MySQLSourceConnector" should "成功读取数据" in {
    // 集成测试（需要MySQL实例）
    // ...
  }
}
```

---

**这就是MySQL Connector的完整使用文档！** 🎉
