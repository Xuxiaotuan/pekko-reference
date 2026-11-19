# PekkoSync MVP 第1周详细执行计划

> **目标**: 7天内完成MySQL→MySQL实时同步MVP，界面能看到每条数据

---

## 📅 整体时间表

| 天数 | 任务 | 产出 | 验收标准 |
|------|------|------|----------|
| **准备** | 清理DataFusion | 干净的代码库 | 编译通过，无DataFusion依赖 |
| **Day 1-2** | MySQL连接器 | 生产级Source/Sink | 能读写MySQL，测试通过 |
| **Day 3-4** | 同步引擎 | SyncJobActor + Pipeline | 能完整同步1000行 |
| **Day 5-6** | 实时监控 | WebSocket + API | 界面能看到数据流动 |
| **Day 7** | 前端界面 | React监控组件 | 完整的监控面板 |

---

## 🧹 准备阶段：清理DataFusion（今天完成）

### 任务清单

#### 1. 移除DataFusion目录

```bash
# 删除Rust服务
rm -rf datafusion-service/

# 删除相关配置
rm -f .env.datafusion.example
rm -f docker-compose-datafusion.yml
rm -f test-datafusion-integration.sh
```

#### 2. 清理Scala代码中的DataFusion引用

需要删除或注释的文件：

```bash
# 查找所有DataFusion相关文件
find pekko-server/src -name "*DataFusion*" -o -name "*datafusion*"
```

**需要删除的文件**：
```
pekko-server/src/main/scala/cn/xuyinyin/magic/datafusion/
├── DataFusionClient.scala
├── DataFusionConfig.scala
├── DataFusionExceptions.scala
├── ParameterizedQuery.scala
├── SQLNodeConfig.scala
├── SQLNodeRegistry.scala
└── StructuredLogger.scala

pekko-server/src/test/scala/cn/xuyinyin/magic/datafusion/
├── DataFusionClientSpec.scala
├── DataFusionExceptionsSpec.scala
├── DataFusionIntegrationSpec.scala
├── ParameterizedQuerySpec.scala
└── StructuredLoggerSpec.scala
```

**需要修改的文件**：
```
pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/transforms/SQLQueryNode.scala
→ 删除或注释DataFusion集成部分

pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/registry/NodeRegistry.scala
→ 移除SQLQueryNode注册
```

#### 3. 清理build.sbt依赖

```scala
// build.sbt
// 移除这些依赖：

// ❌ 删除
// "org.apache.arrow" % "arrow-memory-netty" % "13.0.0",
// "org.apache.arrow" % "arrow-vector" % "13.0.0",
// "org.apache.arrow" % "flight-core" % "13.0.0",
// "org.apache.arrow" % "flight-grpc" % "13.0.0",
```

#### 4. 清理文档引用

```bash
# 需要更新的文档
README.md                           # 移除DataFusion相关描述
DATAFUSION_*.md                     # 删除这些文档
docs/deep-dive/*.md                 # 检查是否有DataFusion引用
```

#### 5. 验证清理

```bash
# 重新编译
sbt clean
sbt compile

# 应该能成功编译，无DataFusion相关错误
```

---

## 📋 Day 1-2: MySQL连接器实现

### 目标

实现生产级的MySQLSourceConnector和MySQLSinkConnector

### 任务分解

#### Day 1 上午：项目结构搭建

```bash
# 1. 创建新模块目录
mkdir -p sync-mvp/src/main/scala/cn/xuyinyin/pekkosync/connector/{api,mysql}
mkdir -p sync-mvp/src/test/scala/cn/xuyinyin/pekkosync/connector/mysql

# 2. 创建build.sbt
cat > sync-mvp/build.sbt << 'EOF'
name := "pekko-sync-mvp"
version := "0.1.0-SNAPSHOT"
scalaVersion := "2.13.12"

libraryDependencies ++= Seq(
  // Pekko核心
  "org.apache.pekko" %% "pekko-actor-typed" % "1.0.2",
  "org.apache.pekko" %% "pekko-stream" % "1.0.2",
  "org.apache.pekko" %% "pekko-http" % "1.0.1",
  
  // 数据库连接
  "com.zaxxer" % "HikariCP" % "5.1.0",
  "mysql" % "mysql-connector-java" % "8.0.33",
  
  // JSON
  "org.apache.pekko" %% "pekko-http-spray-json" % "1.0.1",
  
  // 日志
  "ch.qos.logback" % "logback-classic" % "1.4.14",
  
  // 测试
  "org.scalatest" %% "scalatest" % "3.2.17" % Test,
  "org.apache.pekko" %% "pekko-stream-testkit" % "1.0.2" % Test
)
EOF

# 3. 更新根build.sbt
echo 'lazy val syncMvp = project.in(file("sync-mvp"))' >> build.sbt
```

#### Day 1 下午：Connector API定义

**文件**: `sync-mvp/src/main/scala/cn/xuyinyin/pekkosync/connector/api/Package.scala`

```scala
package cn.xuyinyin.pekkosync.connector

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Source, Sink}
import scala.concurrent.Future

package object api {
  
  /**
   * 数据记录
   */
  case class DataRecord(
    schema: Schema,
    values: Map[String, Any],
    metadata: RecordMetadata
  )
  
  object DataRecord {
    def empty: DataRecord = DataRecord(
      Schema("", List.empty),
      Map.empty,
      RecordMetadata("", System.currentTimeMillis())
    )
  }
  
  /**
   * Schema定义
   */
  case class Schema(
    name: String,
    fields: List[Field]
  )
  
  case class Field(
    name: String,
    dataType: DataType,
    nullable: Boolean,
    isPrimaryKey: Boolean = false
  )
  
  /**
   * 数据类型
   */
  sealed trait DataType
  object DataType {
    case object INT extends DataType
    case object BIGINT extends DataType
    case object STRING extends DataType
    case object BOOLEAN extends DataType
    case object DOUBLE extends DataType
    case object TIMESTAMP extends DataType
    case object BINARY extends DataType
  }
  
  /**
   * 记录元数据
   */
  case class RecordMetadata(
    source: String,
    timestamp: Long
  )
  
  /**
   * Source连接器接口
   */
  trait SourceConnector {
    def connectorType: String
    def version: String
    def createSource(config: SourceConfig): Source[DataRecord, NotUsed]
  }
  
  /**
   * Sink连接器接口
   */
  trait SinkConnector {
    def connectorType: String
    def version: String
    def createSink(config: SinkConfig): Sink[DataRecord, Future[SyncResult]]
  }
  
  /**
   * 配置基类
   */
  trait SourceConfig
  trait SinkConfig
  
  /**
   * 同步结果
   */
  case class SyncResult(
    success: Long,
    failed: Long
  )
}
```

#### Day 2 上午：MySQLSourceConnector

**文件**: `sync-mvp/src/main/scala/cn/xuyinyin/pekkosync/connector/mysql/MySQLSourceConnector.scala`

```scala
package cn.xuyinyin.pekkosync.connector.mysql

import cn.xuyinyin.pekkosync.connector.api._
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import java.sql.{Connection, ResultSet, PreparedStatement, Types}

/**
 * MySQL Source配置
 */
case class MySQLSourceConfig(
  host: String,
  port: Int = 3306,
  database: String,
  table: String,
  username: String,
  password: String,
  query: Option[String] = None,
  fetchSize: Int = 1000
) extends SourceConfig

/**
 * MySQL Source连接器
 */
class MySQLSourceConnector extends SourceConnector {
  
  override def connectorType: String = "mysql"
  override def version: String = "8.0"
  
  override def createSource(config: SourceConfig): Source[DataRecord, NotUsed] = {
    val mysqlConfig = config.asInstanceOf[MySQLSourceConfig]
    
    // 创建连接池
    val dataSource = createDataSource(mysqlConfig)
    
    // 获取Schema
    val schema = inferSchema(dataSource, mysqlConfig)
    
    // 构建SQL查询
    val sql = mysqlConfig.query.getOrElse(
      s"SELECT * FROM ${mysqlConfig.database}.${mysqlConfig.table}"
    )
    
    // 创建流式Source
    Source.unfoldResource[DataRecord, (Connection, PreparedStatement, ResultSet)](
      // 创建资源
      create = () => {
        val connection = dataSource.getConnection
        connection.setAutoCommit(false)
        
        val statement = connection.prepareStatement(
          sql,
          ResultSet.TYPE_FORWARD_ONLY,
          ResultSet.CONCUR_READ_ONLY
        )
        statement.setFetchSize(mysqlConfig.fetchSize)
        
        val resultSet = statement.executeQuery()
        (connection, statement, resultSet)
      },
      
      // 读取数据
      read = { case (conn, stmt, rs) =>
        if (rs.next()) {
          Some(resultSetToDataRecord(rs, schema))
        } else {
          None
        }
      },
      
      // 关闭资源
      close = { case (conn, stmt, rs) =>
        try { rs.close() } catch { case _: Exception => }
        try { stmt.close() } catch { case _: Exception => }
        try { conn.close() } catch { case _: Exception => }
        try { dataSource.close() } catch { case _: Exception => }
      }
    )
  }
  
  /**
   * 创建HikariCP连接池
   */
  private def createDataSource(config: MySQLSourceConfig): HikariDataSource = {
    val hikariConfig = new HikariConfig()
    
    hikariConfig.setJdbcUrl(
      s"jdbc:mysql://${config.host}:${config.port}/${config.database}?useSSL=false&serverTimezone=UTC&useCursorFetch=true"
    )
    hikariConfig.setUsername(config.username)
    hikariConfig.setPassword(config.password)
    hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver")
    
    // 连接池配置
    hikariConfig.setMaximumPoolSize(5)
    hikariConfig.setMinimumIdle(1)
    hikariConfig.setConnectionTimeout(30000)
    hikariConfig.setIdleTimeout(600000)
    hikariConfig.setMaxLifetime(1800000)
    
    new HikariDataSource(hikariConfig)
  }
  
  /**
   * 推断Schema
   */
  private def inferSchema(
    dataSource: HikariDataSource,
    config: MySQLSourceConfig
  ): Schema = {
    val connection = dataSource.getConnection
    try {
      val metadata = connection.getMetaData
      val rs = metadata.getColumns(
        config.database,
        null,
        config.table,
        null
      )
      
      val fields = scala.collection.mutable.ListBuffer[Field]()
      while (rs.next()) {
        val columnName = rs.getString("COLUMN_NAME")
        val sqlType = rs.getInt("DATA_TYPE")
        val nullable = rs.getString("IS_NULLABLE") == "YES"
        
        fields += Field(
          name = columnName,
          dataType = sqlTypeToDataType(sqlType),
          nullable = nullable
        )
      }
      
      Schema(name = config.table, fields = fields.toList)
    } finally {
      connection.close()
    }
  }
  
  /**
   * SQL类型转换
   */
  private def sqlTypeToDataType(sqlType: Int): DataType = {
    sqlType match {
      case Types.INTEGER | Types.SMALLINT | Types.TINYINT => DataType.INT
      case Types.BIGINT => DataType.BIGINT
      case Types.VARCHAR | Types.CHAR | Types.LONGVARCHAR => DataType.STRING
      case Types.BOOLEAN | Types.BIT => DataType.BOOLEAN
      case Types.DOUBLE | Types.FLOAT | Types.REAL => DataType.DOUBLE
      case Types.TIMESTAMP | Types.DATE | Types.TIME => DataType.TIMESTAMP
      case _ => DataType.STRING
    }
  }
  
  /**
   * ResultSet转DataRecord
   */
  private def resultSetToDataRecord(rs: ResultSet, schema: Schema): DataRecord = {
    val values = schema.fields.map { field =>
      val value = field.dataType match {
        case DataType.INT => rs.getInt(field.name)
        case DataType.BIGINT => rs.getLong(field.name)
        case DataType.STRING => rs.getString(field.name)
        case DataType.BOOLEAN => rs.getBoolean(field.name)
        case DataType.DOUBLE => rs.getDouble(field.name)
        case DataType.TIMESTAMP => rs.getTimestamp(field.name)
        case _ => rs.getString(field.name)
      }
      field.name -> (if (rs.wasNull()) null else value)
    }.toMap
    
    DataRecord(
      schema = schema,
      values = values,
      metadata = RecordMetadata(
        source = "mysql",
        timestamp = System.currentTimeMillis()
      )
    )
  }
}
```

#### Day 2 下午：MySQLSinkConnector + 测试

**文件**: `sync-mvp/src/main/scala/cn/xuyinyin/pekkosync/connector/mysql/MySQLSinkConnector.scala`

```scala
package cn.xuyinyin.pekkosync.connector.mysql

import cn.xuyinyin.pekkosync.connector.api._
import org.apache.pekko.stream.scaladsl.Sink
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import scala.concurrent.{Future, ExecutionContext}
import java.sql.{Connection, PreparedStatement}

/**
 * MySQL Sink配置
 */
case class MySQLSinkConfig(
  host: String,
  port: Int = 3306,
  database: String,
  table: String,
  username: String,
  password: String,
  batchSize: Int = 1000,
  writeMode: String = "insert"  // insert | upsert
) extends SinkConfig

/**
 * MySQL Sink连接器
 */
class MySQLSinkConnector extends SinkConnector {
  
  override def connectorType: String = "mysql"
  override def version: String = "8.0"
  
  override def createSink(config: SinkConfig): Sink[DataRecord, Future[SyncResult]] = {
    val mysqlConfig = config.asInstanceOf[MySQLSinkConfig]
    val dataSource = createDataSource(mysqlConfig)
    
    Sink.fold[SyncResult, DataRecord](SyncResult(0, 0)) { (result, record) =>
      try {
        writeRecord(dataSource, mysqlConfig, record)
        result.copy(success = result.success + 1)
      } catch {
        case ex: Exception =>
          println(s"Failed to write record: ${ex.getMessage}")
          result.copy(failed = result.failed + 1)
      }
    }.mapMaterializedValue { future =>
      future.andThen { case _ =>
        dataSource.close()
      }(ExecutionContext.global)
    }
  }
  
  private def createDataSource(config: MySQLSinkConfig): HikariDataSource = {
    val hikariConfig = new HikariConfig()
    
    hikariConfig.setJdbcUrl(
      s"jdbc:mysql://${config.host}:${config.port}/${config.database}?rewriteBatchedStatements=true"
    )
    hikariConfig.setUsername(config.username)
    hikariConfig.setPassword(config.password)
    hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver")
    
    hikariConfig.setMaximumPoolSize(10)
    hikariConfig.setMinimumIdle(2)
    
    new HikariDataSource(hikariConfig)
  }
  
  private def writeRecord(
    dataSource: HikariDataSource,
    config: MySQLSinkConfig,
    record: DataRecord
  ): Unit = {
    val connection = dataSource.getConnection
    connection.setAutoCommit(false)
    
    try {
      val sql = generateInsertSQL(config.table, record.schema.fields)
      val statement = connection.prepareStatement(sql)
      
      record.schema.fields.zipWithIndex.foreach { case (field, idx) =>
        val value = record.values.get(field.name)
        setParameter(statement, idx + 1, value, field.dataType)
      }
      
      statement.executeUpdate()
      connection.commit()
      statement.close()
    } catch {
      case ex: Exception =>
        connection.rollback()
        throw ex
    } finally {
      connection.close()
    }
  }
  
  private def generateInsertSQL(table: String, fields: List[Field]): String = {
    val columns = fields.map(_.name).mkString(", ")
    val placeholders = fields.map(_ => "?").mkString(", ")
    s"INSERT INTO $table ($columns) VALUES ($placeholders)"
  }
  
  private def setParameter(
    stmt: PreparedStatement,
    index: Int,
    value: Option[Any],
    dataType: DataType
  ): Unit = {
    value match {
      case None | Some(null) =>
        stmt.setNull(index, java.sql.Types.NULL)
      case Some(v) => dataType match {
        case DataType.INT => stmt.setInt(index, v.asInstanceOf[Int])
        case DataType.BIGINT => stmt.setLong(index, v.asInstanceOf[Long])
        case DataType.STRING => stmt.setString(index, v.toString)
        case DataType.BOOLEAN => stmt.setBoolean(index, v.asInstanceOf[Boolean])
        case DataType.DOUBLE => stmt.setDouble(index, v.asInstanceOf[Double])
        case DataType.TIMESTAMP => stmt.setTimestamp(index, v.asInstanceOf[java.sql.Timestamp])
        case _ => stmt.setString(index, v.toString)
      }
    }
  }
}
```

**Day 2验收**：

```bash
# 运行测试
sbt "project sync-mvp" test

# 应该能通过：
# ✅ MySQLSourceConnectorSpec - 能读取数据
# ✅ MySQLSinkConnectorSpec - 能写入数据
```

---

## 📋 Day 3-4: 同步引擎实现

### Day 3：SyncJobActor

从 `.tasks/MVP_MYSQL_TO_MYSQL.md` 复制完整的SyncJobActor代码

**验收**：
- 能启动同步任务
- 能停止同步任务
- 能查询状态

### Day 4：MonitoringActor

从 `.tasks/MVP_MYSQL_TO_MYSQL.md` 复制MonitoringActor代码

**验收**：
- 能接收数据更新
- 能推送到WebSocket

---

## 📋 Day 5-6: 实时监控

### Day 5：WebSocket + REST API

**验收**：
```bash
# 启动服务
sbt "project sync-mvp" run

# 测试WebSocket
wscat -c ws://localhost:8080/ws/monitor

# 应该能收到实时消息
```

### Day 6：集成测试

**验收**：
- 同步1000行数据
- WebSocket推送1000条消息
- 统计准确

---

## 📋 Day 7: React界面

从 `.tasks/MVP_MYSQL_TO_MYSQL.md` 复制React组件

**验收**：
- 界面显示统计
- 数据流表格滚动
- 速率图表更新

---

## ✅ 最终验收标准

### 功能验收

- [ ] MySQL→MySQL同步成功
- [ ] 界面实时显示每条数据
- [ ] 统计准确（总数、成功、失败、速率）
- [ ] 速率图表动态更新

### 性能验收

- [ ] 10万行 < 20秒
- [ ] WebSocket延迟 < 100ms
- [ ] 吞吐量 > 5000行/秒

---

**这是你的1周作战计划！准备好开始了吗？** 🚀
