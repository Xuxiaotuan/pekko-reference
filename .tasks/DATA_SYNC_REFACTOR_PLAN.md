# Pekko Data Sync - 开源数据同步引擎重构计划

> **目标**: 打造比SeaTunnel/DataX更强的分布式数据同步引擎
> 
> **定位**: 高性能、分布式、实时+批量一体化的数据同步平台
>
> **技术优势**: Actor模型 + Reactive Streams + Cluster Sharding

---

## 🎯 项目定位

### 核心差异化竞争力

| 特性 | SeaTunnel | DataX | **我们** |
|-----|-----------|-------|---------|
| **架构** | 批处理为主 | 批处理 | 流批一体 |
| **分布式** | 需外部调度 | 单机 | 原生分布式 |
| **实时性** | 有限支持 | 不支持 | 毫秒级CDC |
| **容错** | 任务重试 | 任务重试 | Actor自愈+Event Sourcing |
| **背压** | 部分支持 | 无 | 原生Reactive Streams |
| **扩展性** | 水平扩展复杂 | 单机 | 自动Sharding |
| **性能** | 中等 | 中等 | 高（Actor+零拷贝） |
| **可观测性** | 基础 | 基础 | Prometheus+分布式追踪 |

### 目标用户

1. **企业数据团队** - 需要稳定可靠的数据同步
2. **数据工程师** - 需要实时CDC + 可视化
3. **云原生团队** - K8s部署，自动扩缩容
4. **开源爱好者** - Scala/JVM生态开发者

---

## 📋 第一阶段：项目重构基础 (Week 1-2)

### 1.1 项目重命名

**新名称候选**：
- ✅ **PekkoSync** (推荐) - 简洁，突出技术栈
- StreamFlow - 强调流式处理
- DataBridge - 数据桥梁概念
- SyncMaster - 同步大师

**品牌定位语**：
```
PekkoSync - 下一代分布式数据同步引擎
Distributed, Real-time, Fault-tolerant Data Synchronization powered by Pekko
```

### 1.2 项目结构重组

```
pekko-sync/
├── sync-core/                    # 核心引擎
│   ├── connector-api/           # 连接器API
│   ├── sync-engine/             # 同步引擎
│   ├── cdc-core/                # CDC核心
│   └── schema-registry/         # Schema注册
│
├── sync-connectors/              # 连接器模块
│   ├── connector-jdbc/          # JDBC通用
│   │   ├── mysql/
│   │   ├── postgresql/
│   │   └── oracle/
│   ├── connector-nosql/
│   │   ├── mongodb/
│   │   ├── redis/
│   │   └── elasticsearch/
│   ├── connector-messaging/
│   │   ├── kafka/
│   │   └── rabbitmq/
│   └── connector-storage/
│       ├── s3/
│       ├── hdfs/
│       └── local/
│
├── sync-server/                  # 服务端（重构自pekko-server）
│   ├── cluster/                 # 集群管理
│   ├── api/                     # REST API
│   ├── monitoring/              # 监控
│   └── scheduler/               # 调度器
│
├── sync-ui/                      # Web UI（重构自xxt-ui）
│   ├── pipeline-editor/         # DAG编辑器
│   ├── monitoring/              # 监控面板
│   └── lineage/                 # 血缘分析
│
├── sync-benchmark/               # 性能基准测试
│   ├── vs-seatunnel/
│   ├── vs-datax/
│   └── reports/
│
└── docs/
    ├── README.md                # 新README
    ├── QUICKSTART.md
    ├── ARCHITECTURE.md          # 架构设计
    ├── CONNECTOR_DEV.md         # 连接器开发
    ├── CDC_GUIDE.md             # CDC指南
    └── BENCHMARK.md             # 性能对比
```

### 1.3 核心抽象层设计

**Connector API** - 统一连接器接口

```scala
package cn.xuyinyin.pekkosync.connector.api

import org.apache.pekko.stream.scaladsl.{Source, Sink}
import org.apache.pekko.NotUsed

/**
 * 数据源连接器接口
 */
trait SourceConnector {
  /**
   * 连接器类型（如 "mysql", "postgresql"）
   */
  def connectorType: String
  
  /**
   * 连接器版本
   */
  def version: String
  
  /**
   * 创建数据源
   * 
   * @param config 连接器配置
   * @return 数据流
   */
  def createSource(config: SourceConfig): Source[DataRecord, NotUsed]
  
  /**
   * 验证配置
   */
  def validateConfig(config: SourceConfig): Either[ConfigError, Unit]
  
  /**
   * 获取Schema信息
   */
  def getSchema(config: SourceConfig): Schema
}

/**
 * 数据目标连接器接口
 */
trait SinkConnector {
  def connectorType: String
  def version: String
  
  def createSink(config: SinkConfig): Sink[DataRecord, Future[SyncResult]]
  def validateConfig(config: SinkConfig): Either[ConfigError, Unit]
}

/**
 * CDC连接器接口（扩展SourceConnector）
 */
trait CDCConnector extends SourceConnector {
  /**
   * 创建CDC数据源
   * 
   * @param config CDC配置（包含位置信息）
   * @return 变更数据流
   */
  def createCDCSource(config: CDCConfig): Source[ChangeEvent, NotUsed]
  
  /**
   * 保存CDC位置（Checkpoint）
   */
  def savePosition(position: CDCPosition): Future[Unit]
  
  /**
   * 加载CDC位置（用于恢复）
   */
  def loadPosition(): Future[Option[CDCPosition]]
}

/**
 * 统一数据记录
 */
case class DataRecord(
  schema: Schema,           // Schema信息
  values: Map[String, Any], // 数据值
  metadata: RecordMetadata  // 元数据（来源、时间戳等）
)

/**
 * CDC变更事件
 */
case class ChangeEvent(
  operation: Operation,     // INSERT/UPDATE/DELETE
  before: Option[DataRecord], // 变更前（UPDATE/DELETE有值）
  after: Option[DataRecord],  // 变更后（INSERT/UPDATE有值）
  source: SourceInfo,       // 来源信息（表名、binlog位置等）
  timestamp: Long           // 事件时间戳
)

sealed trait Operation
object Operation {
  case object INSERT extends Operation
  case object UPDATE extends Operation
  case object DELETE extends Operation
  case object SNAPSHOT extends Operation // 全量快照
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

sealed trait DataType
object DataType {
  case object INT extends DataType
  case object BIGINT extends DataType
  case object STRING extends DataType
  case object BOOLEAN extends DataType
  case object DOUBLE extends DataType
  case object TIMESTAMP extends DataType
  case object BINARY extends DataType
  // ... 更多类型
}
```

---

## 📋 第二阶段：连接器实现 (Week 3-5)

### 2.1 JDBC连接器框架

**通用JDBC连接器基类**

```scala
package cn.xuyinyin.pekkosync.connector.jdbc

import org.apache.pekko.stream.scaladsl.{Source, Sink}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import java.sql.{Connection, ResultSet, PreparedStatement}

/**
 * JDBC通用Source连接器
 * 
 * 特性：
 * - HikariCP连接池
 * - 流式查询（fetchSize优化）
 * - 分区并行读取
 * - Schema自动推断
 */
abstract class JDBCSourceConnector extends SourceConnector {
  
  /**
   * JDBC驱动类名
   */
  def driverClass: String
  
  /**
   * 构建JDBC URL
   */
  def buildJdbcUrl(config: JDBCSourceConfig): String
  
  /**
   * 创建连接池
   */
  protected def createDataSource(config: JDBCSourceConfig): HikariDataSource = {
    val hikariConfig = new HikariConfig()
    hikariConfig.setJdbcUrl(buildJdbcUrl(config))
    hikariConfig.setUsername(config.username)
    hikariConfig.setPassword(config.password)
    hikariConfig.setDriverClassName(driverClass)
    
    // 连接池配置
    hikariConfig.setMaximumPoolSize(config.poolSize.getOrElse(10))
    hikariConfig.setMinimumIdle(config.minIdle.getOrElse(2))
    hikariConfig.setConnectionTimeout(30000)
    hikariConfig.setIdleTimeout(600000)
    hikariConfig.setMaxLifetime(1800000)
    
    new HikariDataSource(hikariConfig)
  }
  
  /**
   * 创建流式数据源
   */
  def createSource(config: SourceConfig): Source[DataRecord, NotUsed] = {
    val jdbcConfig = config.asInstanceOf[JDBCSourceConfig]
    val dataSource = createDataSource(jdbcConfig)
    
    // 获取Schema
    val schema = inferSchema(dataSource, jdbcConfig)
    
    // 创建流式Source
    createStreamingSource(dataSource, jdbcConfig, schema)
  }
  
  /**
   * 流式查询实现
   */
  protected def createStreamingSource(
    dataSource: HikariDataSource,
    config: JDBCSourceConfig,
    schema: Schema
  ): Source[DataRecord, NotUsed] = {
    
    Source.unfoldResource[DataRecord, (Connection, PreparedStatement, ResultSet)](
      // 创建资源
      create = () => {
        val connection = dataSource.getConnection
        connection.setAutoCommit(false)
        
        val statement = connection.prepareStatement(
          config.query,
          ResultSet.TYPE_FORWARD_ONLY,
          ResultSet.CONCUR_READ_ONLY
        )
        statement.setFetchSize(config.fetchSize.getOrElse(1000))
        
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
        rs.close()
        stmt.close()
        conn.close()
      }
    )
  }
  
  /**
   * ResultSet转DataRecord
   */
  protected def resultSetToDataRecord(rs: ResultSet, schema: Schema): DataRecord = {
    val values = schema.fields.map { field =>
      val value = field.dataType match {
        case DataType.INT => rs.getInt(field.name)
        case DataType.BIGINT => rs.getLong(field.name)
        case DataType.STRING => rs.getString(field.name)
        case DataType.BOOLEAN => rs.getBoolean(field.name)
        case DataType.DOUBLE => rs.getDouble(field.name)
        case DataType.TIMESTAMP => rs.getTimestamp(field.name)
        // ... 更多类型
      }
      field.name -> (if (rs.wasNull()) null else value)
    }.toMap
    
    DataRecord(
      schema = schema,
      values = values,
      metadata = RecordMetadata(
        source = "jdbc",
        timestamp = System.currentTimeMillis()
      )
    )
  }
  
  /**
   * Schema推断
   */
  protected def inferSchema(
    dataSource: HikariDataSource,
    config: JDBCSourceConfig
  ): Schema = {
    val connection = dataSource.getConnection
    try {
      val metadata = connection.getMetaData
      val rs = metadata.getColumns(
        connection.getCatalog,
        config.schema.orNull,
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
  protected def sqlTypeToDataType(sqlType: Int): DataType = {
    import java.sql.Types._
    sqlType match {
      case INTEGER | SMALLINT | TINYINT => DataType.INT
      case BIGINT => DataType.BIGINT
      case VARCHAR | CHAR | LONGVARCHAR => DataType.STRING
      case BOOLEAN | BIT => DataType.BOOLEAN
      case DOUBLE | FLOAT | REAL => DataType.DOUBLE
      case TIMESTAMP | DATE | TIME => DataType.TIMESTAMP
      case _ => DataType.STRING // 默认String
    }
  }
}

/**
 * JDBC通用Sink连接器
 * 
 * 特性：
 * - 批量插入优化
 * - Upsert支持
 * - 事务管理
 * - 冲突处理
 */
abstract class JDBCSinkConnector extends SinkConnector {
  
  def driverClass: String
  def buildJdbcUrl(config: JDBCSinkConfig): String
  
  /**
   * 生成INSERT语句
   */
  protected def generateInsertSQL(
    table: String,
    fields: List[Field]
  ): String = {
    val columns = fields.map(_.name).mkString(", ")
    val placeholders = fields.map(_ => "?").mkString(", ")
    s"INSERT INTO $table ($columns) VALUES ($placeholders)"
  }
  
  /**
   * 生成UPSERT语句（各数据库语法不同）
   */
  protected def generateUpsertSQL(
    table: String,
    fields: List[Field],
    primaryKeys: List[String]
  ): String
  
  /**
   * 创建Sink
   */
  def createSink(config: SinkConfig): Sink[DataRecord, Future[SyncResult]] = {
    val jdbcConfig = config.asInstanceOf[JDBCSinkConfig]
    val dataSource = createDataSource(jdbcConfig)
    
    val batchSize = jdbcConfig.batchSize.getOrElse(1000)
    
    Sink.foreachAsync[DataRecord](parallelism = 1) { record =>
      Future {
        // 批量插入逻辑
        executeBatch(dataSource, jdbcConfig, List(record))
      }(jdbcConfig.executionContext)
    }.grouped(batchSize)
      .mapAsync(1) { batch =>
        Future {
          executeBatch(dataSource, jdbcConfig, batch)
          SyncResult(success = batch.size, failed = 0)
        }(jdbcConfig.executionContext)
      }
  }
  
  /**
   * 批量执行
   */
  protected def executeBatch(
    dataSource: HikariDataSource,
    config: JDBCSinkConfig,
    records: Seq[DataRecord]
  ): Unit = {
    val connection = dataSource.getConnection
    connection.setAutoCommit(false)
    
    try {
      val sql = config.writeMode match {
        case WriteMode.INSERT => 
          generateInsertSQL(config.table, records.head.schema.fields)
        case WriteMode.UPSERT => 
          generateUpsertSQL(
            config.table,
            records.head.schema.fields,
            getPrimaryKeys(records.head.schema)
          )
      }
      
      val statement = connection.prepareStatement(sql)
      
      records.foreach { record =>
        record.schema.fields.zipWithIndex.foreach { case (field, idx) =>
          val value = record.values.get(field.name)
          setParameter(statement, idx + 1, value, field.dataType)
        }
        statement.addBatch()
      }
      
      statement.executeBatch()
      connection.commit()
      
      statement.close()
    } catch {
      case e: Exception =>
        connection.rollback()
        throw e
    } finally {
      connection.close()
    }
  }
  
  protected def setParameter(
    stmt: PreparedStatement,
    index: Int,
    value: Option[Any],
    dataType: DataType
  ): Unit = {
    value match {
      case None => stmt.setNull(index, sqlTypeForDataType(dataType))
      case Some(v) => dataType match {
        case DataType.INT => stmt.setInt(index, v.asInstanceOf[Int])
        case DataType.BIGINT => stmt.setLong(index, v.asInstanceOf[Long])
        case DataType.STRING => stmt.setString(index, v.asInstanceOf[String])
        case DataType.BOOLEAN => stmt.setBoolean(index, v.asInstanceOf[Boolean])
        case DataType.DOUBLE => stmt.setDouble(index, v.asInstanceOf[Double])
        case DataType.TIMESTAMP => stmt.setTimestamp(index, v.asInstanceOf[java.sql.Timestamp])
        // ... 更多类型
      }
    }
  }
}
```

### 2.2 MySQL连接器实现

```scala
package cn.xuyinyin.pekkosync.connector.mysql

/**
 * MySQL Source连接器
 */
class MySQLSourceConnector extends JDBCSourceConnector {
  
  override def connectorType: String = "mysql"
  override def version: String = "8.0"
  override def driverClass: String = "com.mysql.cj.jdbc.Driver"
  
  override def buildJdbcUrl(config: JDBCSourceConfig): String = {
    val host = config.host
    val port = config.port.getOrElse(3306)
    val database = config.database
    
    s"jdbc:mysql://$host:$port/$database?useSSL=false&serverTimezone=UTC&useCursorFetch=true"
  }
}

/**
 * MySQL Sink连接器
 */
class MySQLSinkConnector extends JDBCSinkConnector {
  
  override def connectorType: String = "mysql"
  override def version: String = "8.0"
  override def driverClass: String = "com.mysql.cj.jdbc.Driver"
  
  override def buildJdbcUrl(config: JDBCSinkConfig): String = {
    val host = config.host
    val port = config.port.getOrElse(3306)
    val database = config.database
    
    s"jdbc:mysql://$host:$port/$database?useSSL=false&serverTimezone=UTC&rewriteBatchedStatements=true"
  }
  
  /**
   * MySQL的UPSERT语法：INSERT ... ON DUPLICATE KEY UPDATE
   */
  override protected def generateUpsertSQL(
    table: String,
    fields: List[Field],
    primaryKeys: List[String]
  ): String = {
    val columns = fields.map(_.name).mkString(", ")
    val placeholders = fields.map(_ => "?").mkString(", ")
    
    val updateClauses = fields
      .filterNot(f => primaryKeys.contains(f.name))
      .map(f => s"${f.name} = VALUES(${f.name})")
      .mkString(", ")
    
    s"""INSERT INTO $table ($columns) 
       |VALUES ($placeholders)
       |ON DUPLICATE KEY UPDATE $updateClauses""".stripMargin
  }
}
```

### 2.3 PostgreSQL连接器实现

```scala
package cn.xuyinyin.pekkosync.connector.postgresql

/**
 * PostgreSQL Source连接器
 */
class PostgreSQLSourceConnector extends JDBCSourceConnector {
  
  override def connectorType: String = "postgresql"
  override def version: String = "14"
  override def driverClass: String = "org.postgresql.Driver"
  
  override def buildJdbcUrl(config: JDBCSourceConfig): String = {
    val host = config.host
    val port = config.port.getOrElse(5432)
    val database = config.database
    
    s"jdbc:postgresql://$host:$port/$database"
  }
}

/**
 * PostgreSQL Sink连接器
 * 
 * 优化：使用COPY协议批量导入（比INSERT快10倍）
 */
class PostgreSQLSinkConnector extends JDBCSinkConnector {
  
  override def connectorType: String = "postgresql"
  override def version: String = "14"
  override def driverClass: String = "org.postgresql.Driver"
  
  override def buildJdbcUrl(config: JDBCSinkConfig): String = {
    val host = config.host
    val port = config.port.getOrElse(5432)
    val database = config.database
    
    s"jdbc:postgresql://$host:$port/$database"
  }
  
  /**
   * PostgreSQL的UPSERT语法：INSERT ... ON CONFLICT DO UPDATE
   */
  override protected def generateUpsertSQL(
    table: String,
    fields: List[Field],
    primaryKeys: List[String]
  ): String = {
    val columns = fields.map(_.name).mkString(", ")
    val placeholders = fields.map(_ => "?").mkString(", ")
    
    val conflictColumns = primaryKeys.mkString(", ")
    val updateClauses = fields
      .filterNot(f => primaryKeys.contains(f.name))
      .map(f => s"${f.name} = EXCLUDED.${f.name}")
      .mkString(", ")
    
    s"""INSERT INTO $table ($columns) 
       |VALUES ($placeholders)
       |ON CONFLICT ($conflictColumns) 
       |DO UPDATE SET $updateClauses""".stripMargin
  }
  
  /**
   * COPY批量导入优化
   */
  def createCopySink(config: PostgreSQLSinkConfig): Sink[DataRecord, Future[SyncResult]] = {
    // 使用PostgreSQL的COPY协议
    // 性能可提升10-100倍
    ???
  }
}
```

### 2.4 MongoDB连接器实现

```scala
package cn.xuyinyin.pekkosync.connector.mongodb

import org.mongodb.scala._
import org.mongodb.scala.model.changestream.ChangeStreamDocument
import org.apache.pekko.stream.scaladsl.Source

/**
 * MongoDB Source连接器
 * 
 * 支持：
 * 1. 普通查询
 * 2. Change Stream（CDC）
 */
class MongoDBSourceConnector extends SourceConnector with CDCConnector {
  
  override def connectorType: String = "mongodb"
  override def version: String = "4.4"
  
  /**
   * 普通查询Source
   */
  override def createSource(config: SourceConfig): Source[DataRecord, NotUsed] = {
    val mongoConfig = config.asInstanceOf[MongoDBSourceConfig]
    val client = MongoClient(mongoConfig.connectionString)
    val database = client.getDatabase(mongoConfig.database)
    val collection = database.getCollection(mongoConfig.collection)
    
    // 转换为Pekko Stream
    Source.fromPublisher(
      collection.find()
    ).map(doc => mongoDocToDataRecord(doc))
  }
  
  /**
   * Change Stream CDC
   */
  override def createCDCSource(config: CDCConfig): Source[ChangeEvent, NotUsed] = {
    val mongoConfig = config.asInstanceOf[MongoDBCDCConfig]
    val client = MongoClient(mongoConfig.connectionString)
    val database = client.getDatabase(mongoConfig.database)
    val collection = database.getCollection(mongoConfig.collection)
    
    // MongoDB Change Stream
    Source.fromPublisher(
      collection.watch()
    ).map(changeDoc => mongoChangeToChangeEvent(changeDoc))
  }
  
  private def mongoDocToDataRecord(doc: Document): DataRecord = {
    // Document转DataRecord
    ???
  }
  
  private def mongoChangeToChangeEvent(
    changeDoc: ChangeStreamDocument[Document]
  ): ChangeEvent = {
    val operation = changeDoc.getOperationType.getValue match {
      case "insert" => Operation.INSERT
      case "update" => Operation.UPDATE
      case "delete" => Operation.DELETE
      case _ => Operation.SNAPSHOT
    }
    
    ChangeEvent(
      operation = operation,
      before = Option(changeDoc.getFullDocumentBeforeChange).map(mongoDocToDataRecord),
      after = Option(changeDoc.getFullDocument).map(mongoDocToDataRecord),
      source = SourceInfo(
        database = changeDoc.getNamespace.getDatabaseName,
        collection = changeDoc.getNamespace.getCollectionName,
        timestamp = changeDoc.getClusterTime.getValue
      ),
      timestamp = System.currentTimeMillis()
    )
  }
}
```

---

## 📋 第三阶段：CDC集成 (Week 6-8)

### 3.1 Debezium Embedded集成

**依赖配置** (`build.sbt`)

```scala
libraryDependencies ++= Seq(
  // Debezium Embedded
  "io.debezium" % "debezium-embedded" % "2.5.0.Final",
  "io.debezium" % "debezium-connector-mysql" % "2.5.0.Final",
  "io.debezium" % "debezium-connector-postgres" % "2.5.0.Final",
  
  // Kafka Connect API（Debezium依赖）
  "org.apache.kafka" % "connect-api" % "3.6.0",
  "org.apache.kafka" % "connect-json" % "3.6.0",
  
  // Offset存储
  "org.apache.kafka" % "connect-file" % "3.6.0"
)
```

**MySQL CDC连接器实现**

```scala
package cn.xuyinyin.pekkosync.cdc.debezium

import io.debezium.engine.{DebeziumEngine, RecordChangeEvent}
import io.debezium.engine.format.Json
import org.apache.kafka.connect.source.SourceRecord
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.{Materializer, OverflowStrategy}
import org.apache.pekko.stream.scaladsl.SourceQueueWithComplete

import java.util.Properties
import scala.concurrent.{ExecutionContext, Future}

/**
 * Debezium CDC引擎封装
 * 
 * 特性：
 * - 自动Binlog监听
 * - 断点续传（Offset持久化）
 * - Schema变更检测
 * - 过滤和转换
 */
class DebeziumCDCEngine(implicit ec: ExecutionContext, mat: Materializer) {
  
  /**
   * 创建MySQL CDC Source
   */
  def createMySQLCDCSource(config: MySQLCDCConfig): Source[ChangeEvent, NotUsed] = {
    
    // Debezium配置
    val props = new Properties()
    
    // 基础配置
    props.setProperty("name", config.name)
    props.setProperty("connector.class", "io.debezium.connector.mysql.MySQLConnector")
    
    // MySQL连接
    props.setProperty("database.hostname", config.host)
    props.setProperty("database.port", config.port.toString)
    props.setProperty("database.user", config.username)
    props.setProperty("database.password", config.password)
    props.setProperty("database.server.id", config.serverId.toString)
    props.setProperty("database.server.name", config.serverName)
    
    // 监听的数据库和表
    props.setProperty("database.include.list", config.databases.mkString(","))
    props.setProperty("table.include.list", config.tables.mkString(","))
    
    // Offset存储（使用文件或内存）
    props.setProperty("offset.storage", config.offsetStorage.storageClass)
    props.setProperty("offset.storage.file.filename", config.offsetStorage.filePath)
    props.setProperty("offset.flush.interval.ms", "60000")
    
    // Schema历史存储
    props.setProperty("schema.history.internal", config.schemaHistory.storageClass)
    props.setProperty("schema.history.internal.file.filename", config.schemaHistory.filePath)
    
    // Binlog配置
    props.setProperty("snapshot.mode", config.snapshotMode.value)
    props.setProperty("snapshot.locking.mode", "none")
    
    // 性能优化
    props.setProperty("max.batch.size", "2048")
    props.setProperty("max.queue.size", "8192")
    props.setProperty("poll.interval.ms", "1000")
    
    // 创建Pekko Stream Source
    createDebeziumSource(props)
  }
  
  /**
   * 通用Debezium Source创建
   */
  private def createDebeziumSource(props: Properties): Source[ChangeEvent, NotUsed] = {
    
    Source.queue[ChangeEvent](
      bufferSize = 10000,
      overflowStrategy = OverflowStrategy.backpressure
    ).mapMaterializedValue { queue =>
      
      // 创建Debezium Engine
      val engine = DebeziumEngine.create(classOf[Json])
        .using(props)
        .notifying(new DebeziumEngine.ChangeConsumer[RecordChangeEvent[String]] {
          override def handleBatch(
            records: java.util.List[RecordChangeEvent[String]],
            committer: DebeziumEngine.RecordCommitter[RecordChangeEvent[String]]
          ): Unit = {
            
            import scala.jdk.CollectionConverters._
            
            records.asScala.foreach { record =>
              val changeEvent = parseDebeziumRecord(record.record())
              
              // 将事件放入队列
              queue.offer(changeEvent)
            }
            
            // 提交Offset
            committer.markBatchFinished()
          }
        })
        .using((success, message, error) => {
          if (!success) {
            println(s"Debezium Engine stopped: $message", error)
            queue.complete()
          }
        })
        .build()
      
      // 在单独线程运行Debezium Engine
      val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
      executor.execute(engine)
      
      NotUsed
    }
  }
  
  /**
   * 解析Debezium SourceRecord为ChangeEvent
   */
  private def parseDebeziumRecord(record: SourceRecord): ChangeEvent = {
    import spray.json._
    
    val value = record.value().asInstanceOf[String]
    val json = value.parseJson.asJsObject
    
    val payload = json.fields("payload").asJsObject
    val op = payload.fields("op").convertTo[String]
    
    val operation = op match {
      case "c" => Operation.INSERT  // create
      case "u" => Operation.UPDATE  // update
      case "d" => Operation.DELETE  // delete
      case "r" => Operation.SNAPSHOT // read (snapshot)
      case _ => throw new IllegalArgumentException(s"Unknown operation: $op")
    }
    
    val before = payload.fields.get("before").flatMap {
      case JsNull => None
      case obj: JsObject => Some(jsonToDataRecord(obj))
    }
    
    val after = payload.fields.get("after").flatMap {
      case JsNull => None
      case obj: JsObject => Some(jsonToDataRecord(obj))
    }
    
    val source = payload.fields("source").asJsObject
    
    ChangeEvent(
      operation = operation,
      before = before,
      after = after,
      source = SourceInfo(
        database = source.fields("db").convertTo[String],
        table = source.fields("table").convertTo[String],
        binlogFile = source.fields.get("file").map(_.convertTo[String]),
        binlogPosition = source.fields.get("pos").map(_.convertTo[Long]),
        gtid = source.fields.get("gtid").map(_.convertTo[String]),
        timestamp = source.fields("ts_ms").convertTo[Long]
      ),
      timestamp = System.currentTimeMillis()
    )
  }
  
  private def jsonToDataRecord(json: JsObject): DataRecord = {
    // JSON转DataRecord
    ???
  }
}

/**
 * MySQL CDC配置
 */
case class MySQLCDCConfig(
  name: String,
  host: String,
  port: Int = 3306,
  username: String,
  password: String,
  serverId: Int = 85744,  // 必须唯一
  serverName: String,     // 逻辑服务器名
  databases: List[String],
  tables: List[String],
  offsetStorage: OffsetStorageConfig,
  schemaHistory: SchemaHistoryConfig,
  snapshotMode: SnapshotMode = SnapshotMode.INITIAL
)

/**
 * Offset存储配置
 */
case class OffsetStorageConfig(
  storageClass: String,
  filePath: String
)

object OffsetStorageConfig {
  def fileStorage(path: String): OffsetStorageConfig = {
    OffsetStorageConfig(
      storageClass = "org.apache.kafka.connect.storage.FileOffsetBackingStore",
      filePath = path
    )
  }
  
  def memoryStorage(): OffsetStorageConfig = {
    OffsetStorageConfig(
      storageClass = "org.apache.kafka.connect.storage.MemoryOffsetBackingStore",
      filePath = ""
    )
  }
}

/**
 * Schema历史存储配置
 */
case class SchemaHistoryConfig(
  storageClass: String,
  filePath: String
)

object SchemaHistoryConfig {
  def fileStorage(path: String): SchemaHistoryConfig = {
    SchemaHistoryConfig(
      storageClass = "io.debezium.storage.file.history.FileSchemaHistory",
      filePath = path
    )
  }
}

/**
 * 快照模式
 */
sealed trait SnapshotMode {
  def value: String
}

object SnapshotMode {
  // 初始快照（首次全量同步）
  case object INITIAL extends SnapshotMode {
    val value = "initial"
  }
  
  // 仅增量（不做快照）
  case object SCHEMA_ONLY extends SnapshotMode {
    val value = "schema_only"
  }
  
  // 总是做快照
  case object ALWAYS extends SnapshotMode {
    val value = "always"
  }
  
  // 从不做快照
  case object NEVER extends SnapshotMode {
    val value = "never"
  }
}
```

### 3.2 CDC使用示例

```scala
package cn.xuyinyin.pekkosync.examples

import cn.xuyinyin.pekkosync.cdc.debezium._
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink

object MySQLCDCExample extends App {
  
  implicit val system = ActorSystem("CDCExample")
  implicit val ec = system.dispatcher
  implicit val mat = system.materializer
  
  val cdcEngine = new DebeziumCDCEngine()
  
  // CDC配置
  val config = MySQLCDCConfig(
    name = "mysql-cdc-connector",
    host = "localhost",
    port = 3306,
    username = "debezium",
    password = "dbz",
    serverId = 85744,
    serverName = "mysql-server-1",
    databases = List("mydb"),
    tables = List("mydb.orders", "mydb.customers"),
    offsetStorage = OffsetStorageConfig.fileStorage("/tmp/offsets.dat"),
    schemaHistory = SchemaHistoryConfig.fileStorage("/tmp/schema_history.dat"),
    snapshotMode = SnapshotMode.INITIAL
  )
  
  // 创建CDC Source
  val cdcSource = cdcEngine.createMySQLCDCSource(config)
  
  // 处理变更事件
  cdcSource
    .runForeach { changeEvent =>
      changeEvent.operation match {
        case Operation.INSERT =>
          println(s"新增记录: ${changeEvent.after.get}")
          
        case Operation.UPDATE =>
          println(s"更新记录:")
          println(s"  Before: ${changeEvent.before.get}")
          println(s"  After: ${changeEvent.after.get}")
          
        case Operation.DELETE =>
          println(s"删除记录: ${changeEvent.before.get}")
          
        case Operation.SNAPSHOT =>
          println(s"快照记录: ${changeEvent.after.get}")
      }
    }
}
```

---

## 📋 第四阶段：重写README (Week 9)

我将创建一个全新的README文档：

