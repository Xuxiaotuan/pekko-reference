# 设计文档：DataFusion SQL查询节点集成

## 概述

本设计文档描述了如何将Apache Arrow DataFusion集成到工作流引擎中，通过Arrow Flight RPC协议实现高性能的SQL查询节点。

### 设计目标

1. **高性能**: 利用DataFusion的向量化执行引擎，提供10-100倍的查询性能提升
2. **解耦架构**: DataFusion Service独立部署，可独立扩展和升级
3. **零拷贝传输**: 使用Arrow Flight协议，避免数据序列化开销
4. **易于扩展**: 标准化的接口设计，便于添加新的SQL功能
5. **生产就绪**: 完整的错误处理、监控和资源管理

### 技术选型

- **DataFusion**: 0.40+ (Rust SQL查询引擎)
- **Arrow Flight**: 50.0+ (高性能RPC协议)
- **Tonic**: 0.11+ (Rust gRPC框架)
- **Arrow Java**: 15.0+ (Java Arrow库)
- **Pekko Streams**: 1.0+ (流处理框架)

## 架构

### 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│  Pekko Workflow Engine (Scala/JVM)                          │
│                                                              │
│  ┌────────────┐    ┌────────────┐    ┌────────────┐        │
│  │  Source    │───▶│  SQL Node  │───▶│   Sink     │        │
│  │   Node     │    │            │    │   Node     │        │
│  └────────────┘    └──────┬─────┘    └────────────┘        │
│                           │                                  │
│                           │ Arrow Flight RPC                 │
│                           │ (gRPC + Arrow)                   │
└───────────────────────────┼──────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  DataFusion Service (Rust)                                  │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Arrow Flight Server                                  │  │
│  │  - DoGet: 执行SQL查询                                │  │
│  │  - DoPut: 上传数据（可选）                           │  │
│  │  - GetFlightInfo: 查询元数据                         │  │
│  └────────────────────┬─────────────────────────────────┘  │
│                       │                                      │
│                       ▼                                      │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  DataFusion Query Engine                             │  │
│  │  - SQL解析和优化                                     │  │
│  │  - 向量化执行                                        │  │
│  │  - 内存管理                                          │  │
│  └────────────────────┬─────────────────────────────────┘  │
│                       │                                      │
│                       ▼                                      │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Arrow RecordBatch                                    │  │
│  │  - 列式存储                                          │  │
│  │  - 零拷贝返回                                        │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### 数据流

```
JSON String (Pekko Stream)
    │
    ▼
┌─────────────────────┐
│ JSON → Arrow        │  (Scala)
│ Schema推断/预定义   │
└─────────────────────┘
    │
    ▼
Arrow RecordBatch
    │
    ▼
┌─────────────────────┐
│ Arrow Flight        │  (gRPC)
│ 零拷贝传输          │
└─────────────────────┘
    │
    ▼
DataFusion Query Engine (Rust)
    │
    ▼
Arrow RecordBatch (结果)
    │
    ▼
┌─────────────────────┐
│ Arrow Flight        │  (gRPC)
│ 流式返回            │
└─────────────────────┘
    │
    ▼
┌─────────────────────┐
│ Arrow → JSON        │  (Scala)
│ 类型转换            │
└─────────────────────┘
    │
    ▼
JSON String (Pekko Stream)
```

## 组件和接口

### 1. DataFusion Service (Rust)

#### 1.1 Arrow Flight Server

```rust
// datafusion-service/src/flight_server.rs

pub struct DataFusionFlightService {
    ctx: Arc<SessionContext>,
    config: ServiceConfig,
}

impl FlightService for DataFusionFlightService {
    // 执行SQL查询
    async fn do_get(
        &self,
        request: Request<Ticket>,
    ) -> Result<Response<Self::DoGetStream>, Status> {
        let ticket = request.into_inner();
        let query = parse_query(&ticket)?;
        
        // 执行查询
        let df = self.ctx.sql(&query.sql).await?;
        let batches = df.collect().await?;
        
        // 转换为Flight数据流
        let stream = batches_to_flight_stream(batches);
        Ok(Response::new(stream))
    }
    
    // 获取查询元数据
    async fn get_flight_info(
        &self,
        request: Request<FlightDescriptor>,
    ) -> Result<Response<FlightInfo>, Status> {
        // 返回Schema和统计信息
    }
    
    // 健康检查
    async fn list_flights(
        &self,
        request: Request<Criteria>,
    ) -> Result<Response<Self::ListFlightsStream>, Status> {
        // 返回服务状态
    }
}
```

#### 1.2 查询执行器

```rust
// datafusion-service/src/executor.rs

pub struct QueryExecutor {
    ctx: Arc<SessionContext>,
    metrics: Arc<Metrics>,
}

impl QueryExecutor {
    pub async fn execute_query(
        &self,
        sql: &str,
        params: Vec<ScalarValue>,
    ) -> Result<Vec<RecordBatch>> {
        let start = Instant::now();
        
        // 参数化查询
        let df = self.ctx.sql(sql).await?;
        let df = self.bind_parameters(df, params)?;
        
        // 执行查询
        let batches = df.collect().await?;
        
        // 记录指标
        let duration = start.elapsed();
        self.metrics.record_query(duration, batches.len());
        
        Ok(batches)
    }
}
```

### 2. Flight Client (Scala)

#### 2.1 连接管理

```scala
// FlightClientPool.scala

class FlightClientPool(config: FlightClientConfig) {
  private val pool = new GenericObjectPool[FlightClient](
    new FlightClientFactory(config)
  )
  
  pool.setMaxTotal(config.maxConnections)
  pool.setMaxIdle(config.maxIdleConnections)
  pool.setMinIdle(config.minIdleConnections)
  
  def withClient[T](f: FlightClient => T): T = {
    val client = pool.borrowObject()
    try {
      f(client)
    } finally {
      pool.returnObject(client)
    }
  }
  
  def close(): Unit = pool.close()
}
```

#### 2.2 查询客户端

```scala
// DataFusionClient.scala

class DataFusionClient(pool: FlightClientPool) {
  
  def executeQuery(
    sql: String,
    params: Map[String, Any] = Map.empty
  )(implicit ec: ExecutionContext): Future[Iterator[RecordBatch]] = {
    Future {
      pool.withClient { client =>
        // 构建查询请求
        val ticket = buildTicket(sql, params)
        
        // 执行查询
        val stream = client.getStream(ticket)
        
        // 返回RecordBatch迭代器
        stream.iterator
      }
    }
  }
  
  def executeQueryAsJson(
    sql: String,
    params: Map[String, Any] = Map.empty
  )(implicit ec: ExecutionContext): Future[Source[String, NotUsed]] = {
    executeQuery(sql, params).map { batches =>
      Source.fromIterator(() => batches)
        .mapConcat(recordBatchToJson)
    }
  }
}
```

### 3. SQL Node (Scala)

#### 3.1 节点定义

```scala
// SQLNode.scala

case class SQLNodeConfig(
  sql: String,
  schema: Option[Schema] = None,
  batchSize: Int = 1000,
  timeout: FiniteDuration = 30.seconds
)

class SQLNode(
  client: DataFusionClient,
  config: SQLNodeConfig
) extends NodeTransform {
  
  override def nodeType: String = "sql.query"
  
  override def createTransform(
    node: WorkflowDSL.Node,
    onLog: String => Unit
  )(implicit ec: ExecutionContext): Flow[String, String, NotUsed] = {
    
    Flow[String]
      .grouped(config.batchSize)
      .mapAsync(1) { batch =>
        // 转换为Arrow
        val recordBatch = jsonToRecordBatch(batch, config.schema)
        
        // 上传数据（可选）
        client.uploadData(recordBatch)
        
        // 执行查询
        client.executeQueryAsJson(config.sql)
      }
      .flatMapConcat(identity)
  }
}
```

#### 3.2 数据转换

```scala
// ArrowConverter.scala

object ArrowConverter {
  
  def jsonToRecordBatch(
    jsonLines: Seq[String],
    schema: Option[Schema]
  ): RecordBatch = {
    val actualSchema = schema.getOrElse(inferSchema(jsonLines))
    
    val root = VectorSchemaRoot.create(actualSchema, allocator)
    val writer = new JsonWriter(root)
    
    jsonLines.foreach { json =>
      writer.write(json)
    }
    
    writer.finish()
    root.getRecordBatch
  }
  
  def recordBatchToJson(batch: RecordBatch): Seq[String] = {
    val reader = new JsonReader(batch)
    reader.readAll().map(_.toString)
  }
  
  def inferSchema(jsonLines: Seq[String]): Schema = {
    // 从JSON推断Schema
    val samples = jsonLines.take(100)
    SchemaInferrer.infer(samples)
  }
}
```

### 4. 节点注册

```scala
// SQLNodeRegistry.scala

object SQLNodeRegistry {
  
  def register(
    registry: NodeRegistry,
    client: DataFusionClient
  ): Unit = {
    registry.registerTransform(
      "sql.query",
      (node, onLog) => new SQLNode(client, parseConfig(node)).createTransform(node, onLog)
    )
  }
  
  private def parseConfig(node: WorkflowDSL.Node): SQLNodeConfig = {
    val config = node.config
    SQLNodeConfig(
      sql = config.fields("sql").convertTo[String],
      schema = config.fields.get("schema").map(parseSchema),
      batchSize = config.fields.get("batchSize").map(_.convertTo[Int]).getOrElse(1000),
      timeout = config.fields.get("timeout").map(_.convertTo[Int].seconds).getOrElse(30.seconds)
    )
  }
}
```

## 数据模型

### 1. 查询请求

```json
{
  "sql": "SELECT * FROM input WHERE value > 100",
  "params": {
    "threshold": 100
  },
  "schema": {
    "fields": [
      {"name": "id", "type": "int64"},
      {"name": "value", "type": "float64"},
      {"name": "name", "type": "utf8"}
    ]
  }
}
```

### 2. 工作流节点配置

```json
{
  "id": "sql-1",
  "type": "transform",
  "nodeType": "sql.query",
  "label": "SQL查询",
  "position": {"x": 200, "y": 100},
  "config": {
    "sql": "SELECT id, name, value * 2 as doubled FROM input WHERE value > 100",
    "batchSize": 1000,
    "timeout": 30
  }
}
```

### 3. Arrow Schema

```json
{
  "fields": [
    {
      "name": "id",
      "nullable": false,
      "type": {"name": "int", "bitWidth": 64, "isSigned": true}
    },
    {
      "name": "name",
      "nullable": true,
      "type": {"name": "utf8"}
    },
    {
      "name": "value",
      "nullable": true,
      "type": {"name": "floatingpoint", "precision": "DOUBLE"}
    }
  ]
}
```

## 正确性属性

*属性是一个特征或行为，应该在系统的所有有效执行中保持为真——本质上是关于系统应该做什么的正式陈述。属性作为人类可读规范和机器可验证正确性保证之间的桥梁。*


### 属性1: Arrow Flight查询传输协议一致性

*对于任何*SQL查询请求，系统应该使用Arrow Flight的DoGet方法传输查询，并以Arrow RecordBatch流的形式接收结果。

**验证: Requirements 2.1, 2.2**

### 属性2: 数据格式Round-Trip一致性

*对于任何*有效的JSON数据，经过JSON → Arrow → SQL查询 → Arrow → JSON的完整流程后，数据的语义应该保持一致（考虑SQL查询的转换逻辑）。

**验证: Requirements 3.2, 3.3, 4.2**

### 属性3: SQL节点配置有效性

*对于任何*SQL节点配置，如果配置无效（如SQL语法错误、缺少必需字段），系统应该在工作流验证阶段拒绝并返回明确的错误信息。

**验证: Requirements 3.5**

### 属性4: 参数化查询安全性

*对于任何*包含参数的SQL查询，系统应该使用参数化查询机制，确保参数值不会被解释为SQL代码，防止SQL注入攻击。

**验证: Requirements 3.4**

### 属性5: Schema推断正确性

*对于任何*有效的JSON数据集，如果未提供预定义Schema，系统应该能够自动推断出正确的Arrow Schema，使得所有数据都能被正确解析。

**验证: Requirements 4.1**

### 属性6: 连接池资源管理

*对于任何*查询执行，系统应该从连接池获取连接，使用后正确归还，确保连接不会泄漏。

**验证: Requirements 5.2**

### 属性7: 网络故障自动重试

*对于任何*因网络临时故障导致的查询失败，系统应该自动重试最多3次，只有在所有重试都失败后才返回错误。

**验证: Requirements 6.4**

### 属性8: 错误信息明确性

*对于任何*查询执行失败（SQL语法错误、数据格式错误、服务不可用等），系统应该返回明确的错误类型和错误信息，帮助用户快速定位问题。

**验证: Requirements 1.3, 4.5, 6.2**

### 属性9: 查询执行监控完整性

*对于任何*SQL查询执行，系统应该记录完整的监控指标，包括查询执行时间、传输数据量（行数和字节数）、成功/失败状态。

**验证: Requirements 7.1, 7.2, 7.3**

### 属性10: 负载均衡均匀性

*对于任何*配置了多个DataFusion Service实例的部署，查询请求应该在所有健康实例间均匀分布，避免某些实例过载。

**验证: Requirements 8.4**

### 属性11: SQL功能完整性 - SELECT操作

*对于任何*包含投影、过滤、排序、限制的SELECT查询，系统应该能够正确执行并返回符合SQL语义的结果。

**验证: Requirements 9.1**

### 属性12: SQL功能完整性 - 聚合操作

*对于任何*包含GROUP BY、HAVING、聚合函数（SUM、AVG、COUNT等）的查询，系统应该能够正确执行并返回符合SQL语义的聚合结果。

**验证: Requirements 9.2**

### 属性13: SQL功能完整性 - JOIN操作

*对于任何*包含INNER JOIN、LEFT JOIN、RIGHT JOIN的查询，系统应该能够正确执行并返回符合SQL语义的连接结果。

**验证: Requirements 9.3**

### 属性14: SQL功能完整性 - 窗口函数

*对于任何*包含窗口函数（ROW_NUMBER、RANK、LAG、LEAD等）的查询，系统应该能够正确执行并返回符合SQL语义的结果。

**验证: Requirements 9.4**

### 属性15: SQL功能完整性 - 子查询

*对于任何*包含嵌套子查询或CTE（WITH子句）的查询，系统应该能够正确执行并返回符合SQL语义的结果。

**验证: Requirements 9.5**

## 错误处理

### 1. 服务不可用

```scala
case class ServiceUnavailableException(
  message: String,
  serviceAddress: String,
  cause: Option[Throwable] = None
) extends RuntimeException(message, cause.orNull)

// 处理逻辑
try {
  client.executeQuery(sql)
} catch {
  case _: ConnectException =>
    throw ServiceUnavailableException(
      s"DataFusion Service不可用: $serviceAddress",
      serviceAddress
    )
}
```

### 2. SQL语法错误

```scala
case class SQLSyntaxException(
  message: String,
  sql: String,
  position: Option[Int] = None
) extends RuntimeException(message)

// DataFusion返回的错误会包含位置信息
// 例如: "SQL parse error: Expected identifier, found keyword 'FROM' at position 15"
```

### 3. 数据格式错误

```scala
case class DataFormatException(
  message: String,
  data: String,
  expectedSchema: Option[Schema] = None
) extends RuntimeException(message)

// 转换失败时抛出
try {
  ArrowConverter.jsonToRecordBatch(json, schema)
} catch {
  case e: JsonParseException =>
    throw DataFormatException(
      s"JSON格式错误: ${e.getMessage}",
      json.take(100)
    )
}
```

### 4. 超时错误

```scala
case class QueryTimeoutException(
  message: String,
  sql: String,
  timeout: FiniteDuration
) extends RuntimeException(message)

// 使用Future.timeout处理
Future.timeout(
  client.executeQuery(sql),
  config.timeout
).recover {
  case _: TimeoutException =>
    throw QueryTimeoutException(
      s"查询超时 (${config.timeout})",
      sql,
      config.timeout
    )
}
```

### 5. 重试策略

```scala
object RetryPolicy {
  
  def withRetry[T](
    maxRetries: Int = 3,
    backoff: FiniteDuration = 1.second
  )(f: => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    
    def attempt(retriesLeft: Int): Future[T] = {
      f.recoverWith {
        case e: IOException if retriesLeft > 0 =>
          // 网络错误，重试
          logger.warn(s"查询失败，剩余重试次数: $retriesLeft", e)
          after(backoff, system.scheduler)(attempt(retriesLeft - 1))
          
        case e: ServiceUnavailableException if retriesLeft > 0 =>
          // 服务不可用，重试
          logger.warn(s"服务不可用，剩余重试次数: $retriesLeft", e)
          after(backoff, system.scheduler)(attempt(retriesLeft - 1))
          
        case e =>
          // 其他错误，不重试
          Future.failed(e)
      }
    }
    
    attempt(maxRetries)
  }
}
```

## 测试策略

### 1. 单元测试

#### 1.1 数据转换测试

```scala
class ArrowConverterSpec extends AnyFlatSpec with Matchers {
  
  "ArrowConverter" should "convert JSON to Arrow RecordBatch" in {
    val json = """{"id": 1, "name": "test", "value": 3.14}"""
    val batch = ArrowConverter.jsonToRecordBatch(Seq(json), None)
    
    batch.getSchema.getFields.size shouldBe 3
    batch.getRowCount shouldBe 1
  }
  
  it should "infer correct schema from JSON" in {
    val json = Seq(
      """{"id": 1, "name": "test", "value": 3.14}""",
      """{"id": 2, "name": "test2", "value": 2.71}"""
    )
    val schema = ArrowConverter.inferSchema(json)
    
    schema.findField("id").getType shouldBe ArrowType.Int(64, true)
    schema.findField("name").getType shouldBe ArrowType.Utf8()
    schema.findField("value").getType shouldBe ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)
  }
}
```

#### 1.2 连接池测试

```scala
class FlightClientPoolSpec extends AnyFlatSpec with Matchers {
  
  "FlightClientPool" should "reuse connections" in {
    val pool = new FlightClientPool(config)
    
    val client1 = pool.withClient(identity)
    val client2 = pool.withClient(identity)
    
    // 应该是同一个连接（从池中复用）
    client1 shouldBe client2
  }
  
  it should "limit max connections" in {
    val pool = new FlightClientPool(config.copy(maxConnections = 2))
    
    val futures = (1 to 5).map { _ =>
      Future {
        pool.withClient { client =>
          Thread.sleep(100)
          client
        }
      }
    }
    
    // 应该能够完成，但会排队等待
    Await.result(Future.sequence(futures), 10.seconds)
  }
}
```

### 2. 属性测试

#### 2.1 Round-Trip属性测试

```scala
class DataFusionPropertySpec extends AnyPropSpec with Matchers with ScalaCheckPropertyChecks {
  
  property("JSON -> Arrow -> JSON round-trip preserves data") {
    forAll(jsonDataGen) { jsonData =>
      // 生成随机JSON数据
      val original = jsonData
      
      // 转换为Arrow
      val batch = ArrowConverter.jsonToRecordBatch(Seq(original), None)
      
      // 转换回JSON
      val result = ArrowConverter.recordBatchToJson(batch).head
      
      // 解析并比较（忽略字段顺序）
      parseJson(result) shouldBe parseJson(original)
    }
  }
  
  // JSON数据生成器
  lazy val jsonDataGen: Gen[String] = for {
    id <- Gen.posNum[Int]
    name <- Gen.alphaStr
    value <- Gen.choose(0.0, 1000.0)
  } yield s"""{"id": $id, "name": "$name", "value": $value}"""
}
```

#### 2.2 SQL功能属性测试

```scala
property("SELECT with WHERE filter returns correct subset") {
  forAll(dataSetGen, thresholdGen) { (dataSet, threshold) =>
    // 生成随机数据集和过滤条件
    val sql = s"SELECT * FROM input WHERE value > $threshold"
    
    // 执行查询
    val result = executeQuery(sql, dataSet)
    
    // 验证结果：所有行的value都应该大于threshold
    result.forall(_.value > threshold) shouldBe true
    
    // 验证完整性：不应该遗漏任何满足条件的行
    val expected = dataSet.filter(_.value > threshold)
    result.size shouldBe expected.size
  }
}
```

#### 2.3 参数化查询安全性测试

```scala
property("Parameterized queries prevent SQL injection") {
  forAll(sqlInjectionGen) { maliciousInput =>
    // 生成SQL注入尝试
    val sql = "SELECT * FROM input WHERE name = ?"
    val params = Map("name" -> maliciousInput)
    
    // 执行查询不应该抛出异常或执行恶意代码
    noException should be thrownBy {
      executeQuery(sql, params)
    }
    
    // 结果应该将输入作为字面值处理
    val result = executeQuery(sql, params)
    result.forall(_.name == maliciousInput) shouldBe true
  }
}

// SQL注入尝试生成器
lazy val sqlInjectionGen: Gen[String] = Gen.oneOf(
  "'; DROP TABLE users; --",
  "' OR '1'='1",
  "admin'--",
  "' UNION SELECT * FROM passwords --"
)
```

#### 2.4 错误处理属性测试

```scala
property("Invalid SQL returns clear error message") {
  forAll(invalidSqlGen) { invalidSql =>
    // 生成各种无效的SQL
    val result = Try(executeQuery(invalidSql))
    
    result shouldBe a[Failure[_]]
    result.failed.get shouldBe a[SQLSyntaxException]
    
    // 错误信息应该包含SQL和位置
    val error = result.failed.get.asInstanceOf[SQLSyntaxException]
    error.sql shouldBe invalidSql
    error.position shouldBe defined
  }
}

// 无效SQL生成器
lazy val invalidSqlGen: Gen[String] = Gen.oneOf(
  "SELECT FROM table",  // 缺少列
  "SELECT * FORM table",  // 拼写错误
  "SELECT * FROM",  // 不完整
  "SELECT * FROM table WHERE",  // 不完整的WHERE
  "SELECT * FROM table GROUP BY"  // 不完整的GROUP BY
)
```

### 3. 集成测试

#### 3.1 端到端工作流测试

```scala
class DataFusionIntegrationSpec extends AnyFlatSpec with Matchers {
  
  "SQL Node in workflow" should "execute query and transform data" in {
    // 创建包含SQL节点的工作流
    val workflow = Workflow(
      id = "test-workflow",
      name = "SQL Test",
      nodes = List(
        Node(
          id = "source-1",
          `type` = "source",
          nodeType = "generator.sequence",
          config = JsObject("count" -> JsNumber(100))
        ),
        Node(
          id = "sql-1",
          `type` = "transform",
          nodeType = "sql.query",
          config = JsObject(
            "sql" -> JsString("SELECT id, value * 2 as doubled FROM input WHERE value > 50")
          )
        ),
        Node(
          id = "sink-1",
          `type` = "sink",
          nodeType = "collector",
          config = JsObject()
        )
      ),
      edges = List(
        Edge("e1", "source-1", "sql-1"),
        Edge("e2", "sql-1", "sink-1")
      )
    )
    
    // 执行工作流
    val result = executeWorkflow(workflow)
    
    // 验证结果
    result.success shouldBe true
    result.rowsProcessed shouldBe Some(50)  // 只有50行满足条件
  }
}
```

#### 3.2 DataFusion Service集成测试

```scala
class DataFusionServiceSpec extends AnyFlatSpec with Matchers {
  
  "DataFusion Service" should "accept SQL queries via Arrow Flight" in {
    // 启动DataFusion Service
    val service = startDataFusionService()
    
    // 创建Flight Client
    val client = new FlightClient(service.address)
    
    // 执行查询
    val sql = "SELECT 1 as id, 'test' as name"
    val result = client.executeQuery(sql)
    
    // 验证结果
    result should have size 1
    result.head.getInt("id") shouldBe 1
    result.head.getString("name") shouldBe "test"
    
    // 清理
    client.close()
    service.stop()
  }
}
```

### 4. 性能测试

```scala
class DataFusionPerformanceSpec extends AnyFlatSpec with Matchers {
  
  "SQL Node" should "process 10000 rows in less than 1 second" in {
    val dataSet = generateDataSet(10000)
    val sql = "SELECT * FROM input WHERE value > 500"
    
    val start = System.currentTimeMillis()
    val result = executeQuery(sql, dataSet)
    val duration = System.currentTimeMillis() - start
    
    duration should be < 1000L
    result.size should be > 0
  }
  
  it should "handle large result sets without memory overflow" in {
    val dataSet = generateDataSet(1000000)
    val sql = "SELECT * FROM input"
    
    // 应该能够处理大数据集而不会OOM
    noException should be thrownBy {
      val result = executeQueryStreaming(sql, dataSet)
      result.foreach(_ => ())  // 消费流
    }
  }
}
```

## 配置

### 1. DataFusion Service配置

```toml
# datafusion-service/config.toml

[server]
host = "0.0.0.0"
port = 50051
max_connections = 100

[query]
max_memory_mb = 4096
batch_size = 8192
timeout_seconds = 300

[tls]
enabled = false
cert_path = "/path/to/cert.pem"
key_path = "/path/to/key.pem"

[logging]
level = "info"
format = "json"
```

### 2. Scala Client配置

```hocon
# application.conf

datafusion {
  service {
    # DataFusion Service地址
    host = "localhost"
    host = ${?DATAFUSION_HOST}
    
    port = 50051
    port = ${?DATAFUSION_PORT}
    
    # 连接池配置
    pool {
      max-connections = 20
      max-idle-connections = 10
      min-idle-connections = 2
      idle-timeout = 5 minutes
    }
    
    # 查询配置
    query {
      timeout = 30 seconds
      retry-attempts = 3
      retry-backoff = 1 second
    }
    
    # TLS配置
    tls {
      enabled = false
      trust-cert-path = "/path/to/ca.pem"
    }
  }
  
  # SQL节点默认配置
  node {
    batch-size = 1000
    timeout = 30 seconds
  }
}
```

### 3. 环境变量

```bash
# DataFusion Service地址
export DATAFUSION_HOST=datafusion-service.example.com
export DATAFUSION_PORT=50051

# TLS配置
export DATAFUSION_TLS_ENABLED=true
export DATAFUSION_TLS_CERT=/path/to/ca.pem

# 连接池配置
export DATAFUSION_MAX_CONNECTIONS=50
```

## 部署

### 1. Docker Compose部署

```yaml
# docker-compose.yml

version: '3.8'

services:
  datafusion-service:
    image: datafusion-service:latest
    ports:
      - "50051:50051"
    environment:
      - RUST_LOG=info
      - MAX_MEMORY_MB=4096
    volumes:
      - ./config.toml:/app/config.toml
    healthcheck:
      test: ["CMD", "grpc_health_probe", "-addr=:50051"]
      interval: 10s
      timeout: 5s
      retries: 3
  
  workflow-engine:
    image: workflow-engine:latest
    ports:
      - "8080:8080"
    environment:
      - DATAFUSION_HOST=datafusion-service
      - DATAFUSION_PORT=50051
    depends_on:
      - datafusion-service
```

### 2. Kubernetes部署

```yaml
# datafusion-service-deployment.yaml

apiVersion: apps/v1
kind: Deployment
metadata:
  name: datafusion-service
spec:
  replicas: 3
  selector:
    matchLabels:
      app: datafusion-service
  template:
    metadata:
      labels:
        app: datafusion-service
    spec:
      containers:
      - name: datafusion-service
        image: datafusion-service:latest
        ports:
        - containerPort: 50051
          name: grpc
        env:
        - name: RUST_LOG
          value: "info"
        - name: MAX_MEMORY_MB
          value: "4096"
        resources:
          requests:
            memory: "2Gi"
            cpu: "1000m"
          limits:
            memory: "4Gi"
            cpu: "2000m"
        livenessProbe:
          exec:
            command: ["/bin/grpc_health_probe", "-addr=:50051"]
          initialDelaySeconds: 10
          periodSeconds: 10
        readinessProbe:
          exec:
            command: ["/bin/grpc_health_probe", "-addr=:50051"]
          initialDelaySeconds: 5
          periodSeconds: 5

---
apiVersion: v1
kind: Service
metadata:
  name: datafusion-service
spec:
  selector:
    app: datafusion-service
  ports:
  - port: 50051
    targetPort: 50051
    name: grpc
  type: ClusterIP
```

## 监控和可观测性

### 1. Prometheus指标

```scala
// DataFusionMetrics.scala

object DataFusionMetrics {
  
  // 查询执行时间
  val queryDuration = Histogram.build()
    .name("datafusion_query_duration_seconds")
    .help("SQL query execution duration in seconds")
    .labelNames("status")
    .register()
  
  // 查询总数
  val queryTotal = Counter.build()
    .name("datafusion_query_total")
    .help("Total number of SQL queries")
    .labelNames("status")
    .register()
  
  // 数据传输量
  val dataTransferred = Counter.build()
    .name("datafusion_data_transferred_bytes")
    .help("Total bytes transferred")
    .labelNames("direction")  // "upload" or "download"
    .register()
  
  // 连接池状态
  val poolConnections = Gauge.build()
    .name("datafusion_pool_connections")
    .help("Number of connections in pool")
    .labelNames("state")  // "active", "idle", "total"
    .register()
}
```

### 2. 结构化日志

```scala
// 查询开始
logger.info(
  "SQL query started",
  "query_id" -> queryId,
  "sql" -> sql.take(100),
  "batch_size" -> batchSize
)

// 查询完成
logger.info(
  "SQL query completed",
  "query_id" -> queryId,
  "duration_ms" -> duration,
  "rows_processed" -> rowCount,
  "bytes_transferred" -> byteCount
)

// 查询失败
logger.error(
  "SQL query failed",
  "query_id" -> queryId,
  "sql" -> sql.take(100),
  "error" -> error.getMessage,
  "error_type" -> error.getClass.getSimpleName
)
```

### 3. Grafana仪表板

```json
{
  "dashboard": {
    "title": "DataFusion Integration",
    "panels": [
      {
        "title": "Query Rate",
        "targets": [
          {
            "expr": "rate(datafusion_query_total[5m])"
          }
        ]
      },
      {
        "title": "Query Duration (P95)",
        "targets": [
          {
            "expr": "histogram_quantile(0.95, datafusion_query_duration_seconds_bucket)"
          }
        ]
      },
      {
        "title": "Data Transfer Rate",
        "targets": [
          {
            "expr": "rate(datafusion_data_transferred_bytes[5m])"
          }
        ]
      },
      {
        "title": "Connection Pool Status",
        "targets": [
          {
            "expr": "datafusion_pool_connections"
          }
        ]
      }
    ]
  }
}
```

## 迁移和兼容性

### 1. 向后兼容性保证

- 现有工作流不使用SQL节点时，完全不受影响
- DataFusion Service是可选依赖，未部署时不影响其他功能
- 工作流DSL格式保持不变，只是新增了`sql.query`节点类型

### 2. 渐进式迁移

```scala
// Phase 1: 部署DataFusion Service
// - 独立部署，不影响现有系统

// Phase 2: 启用SQL节点支持
// - 添加配置，但不强制使用
datafusion.enabled = true

// Phase 3: 创建使用SQL节点的新工作流
// - 逐步迁移数据转换逻辑到SQL节点

// Phase 4: 性能优化
// - 根据实际使用情况调整配置
// - 扩展DataFusion Service实例
```

### 3. 回滚方案

```scala
// 如果需要回滚：
// 1. 禁用SQL节点
datafusion.enabled = false

// 2. 工作流验证会拒绝包含SQL节点的工作流
// 3. 现有不使用SQL节点的工作流继续正常运行
// 4. 可以安全地停止DataFusion Service
```

## 未来扩展

### 1. 分布式查询

- 支持跨多个DataFusion Service实例的分布式查询
- 实现查询分片和结果合并
- 支持大规模数据集的并行处理

### 2. 自定义函数

- 支持注册Scala/Java实现的UDF
- 通过Arrow Flight传递UDF定义
- 在DataFusion中动态加载和执行

### 3. 查询缓存

- 缓存常用查询的结果
- 支持基于时间的缓存失效
- 减少重复查询的开销

### 4. 查询优化

- 收集查询统计信息
- 自动生成查询计划建议
- 支持查询重写和优化

---

**设计版本**: v1.0  
**最后更新**: 2024-11-28  
**作者**: AI Assistant
