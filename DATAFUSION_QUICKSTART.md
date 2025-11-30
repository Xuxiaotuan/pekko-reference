# DataFusion SQL查询节点 - 快速开始指南

## 🚀 快速开始

本指南将帮助您在5分钟内启动并测试DataFusion SQL查询节点。

## 📋 前置要求

### 必需
- Rust 1.70+ (用于DataFusion Service)
- Java 11+ (用于Pekko应用)
- SBT 1.9+ (用于构建Scala项目)

### 可选
- Docker (用于容器化部署)
- Prometheus + Grafana (用于监控)

## 🔧 步骤1: 启动DataFusion Service

### 方式A: 直接运行（推荐用于开发）

```bash
# 进入DataFusion Service目录
cd datafusion-service

# 构建并运行
cargo run --release

# 或者使用开发配置
RUST_LOG=info cargo run -- --config config-dev.toml
```

服务将在 `localhost:50051` 启动。

### 方式B: 使用Docker

```bash
cd datafusion-service

# 构建Docker镜像
docker build -t datafusion-service:latest .

# 运行容器
docker run -p 50051:50051 datafusion-service:latest
```

### 验证服务

```bash
# 使用验证脚本
./datafusion-service/verify.sh

# 或者手动测试
curl http://localhost:50051/health
```

## 🔧 步骤2: 配置Pekko应用

### 2.1 启用DataFusion集成

在 `pekko-server/src/main/resources/application.conf` 中添加：

```hocon
include "datafusion.conf"

datafusion {
  enabled = true
  host = "localhost"
  port = 50051
  
  pool {
    maxTotal = 10
    maxIdle = 5
    minIdle = 2
  }
}
```

### 2.2 编译项目

```bash
# 在项目根目录
sbt compile

# 运行测试
sbt test
```

## 🎯 步骤3: 创建SQL查询工作流

### 3.1 简单示例

创建文件 `examples/sql-query-example.json`:

```json
{
  "id": "sql-example-workflow",
  "name": "SQL Query Example",
  "version": "1.0",
  "nodes": [
    {
      "id": "source-1",
      "type": "source",
      "nodeType": "sequence.numbers",
      "label": "Generate Numbers",
      "config": {
        "start": 1,
        "end": 100,
        "step": 1
      }
    },
    {
      "id": "sql-1",
      "type": "transform",
      "nodeType": "sql.query",
      "label": "Filter with SQL",
      "config": {
        "sql": "SELECT * FROM input WHERE value > :threshold",
        "batchSize": 10,
        "timeout": 30,
        "parameters": {
          "threshold": 50
        }
      }
    },
    {
      "id": "sink-1",
      "type": "sink",
      "nodeType": "console.log",
      "label": "Print Results",
      "config": {}
    }
  ],
  "edges": [
    {
      "from": "source-1",
      "to": "sql-1"
    },
    {
      "from": "sql-1",
      "to": "sink-1"
    }
  ]
}
```

### 3.2 复杂示例（聚合查询）

```json
{
  "id": "sql-aggregation-workflow",
  "name": "SQL Aggregation Example",
  "nodes": [
    {
      "id": "source-1",
      "type": "source",
      "nodeType": "file.csv",
      "label": "Load CSV",
      "config": {
        "path": "data/sales.csv"
      }
    },
    {
      "id": "sql-1",
      "type": "transform",
      "nodeType": "sql.query",
      "label": "Aggregate Sales",
      "config": {
        "sql": "SELECT category, SUM(amount) as total, COUNT(*) as count FROM input GROUP BY category HAVING total > {{min_total}}",
        "batchSize": 1000,
        "parameters": {
          "min_total": 10000
        }
      }
    },
    {
      "id": "sink-1",
      "type": "sink",
      "nodeType": "file.text",
      "label": "Save Results",
      "config": {
        "path": "output/sales-summary.json"
      }
    }
  ],
  "edges": [
    {"from": "source-1", "to": "sql-1"},
    {"from": "sql-1", "to": "sink-1"}
  ]
}
```

## 📊 步骤4: 监控和日志

### 4.1 查看Prometheus指标

访问 `http://localhost:9090/metrics` 查看指标：

```
# 查询执行时间
datafusion_query_duration_seconds

# 查询总数
datafusion_query_total

# 连接池状态
datafusion_pool_connections
```

### 4.2 查看结构化日志

日志以JSON格式输出，便于分析：

```json
{
  "event": "query_complete",
  "timestamp": 1234567890,
  "nodeId": "sql-1",
  "sql": "SELECT * FROM input WHERE value > 50",
  "durationMs": 150,
  "rows": 50,
  "bytes": 2500,
  "throughput": 333
}
```

## 🧪 步骤5: 测试功能

### 5.1 运行单元测试

```bash
# 运行所有DataFusion相关测试
sbt "testOnly *datafusion*"

# 运行特定测试
sbt "testOnly *SQLQueryNodeSpec"
sbt "testOnly *ParameterizedQuerySpec"
sbt "testOnly *DataFusionMetricsSpec"
```

### 5.2 手动测试

使用Scala REPL测试：

```scala
import cn.xuyinyin.magic.datafusion._
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors

implicit val system = ActorSystem(Behaviors.empty, "test")
implicit val ec = system.executionContext

// 创建客户端配置
val config = FlightClientConfig(host = "localhost", port = 50051)
val poolConfig = FlightClientPoolConfig(maxTotal = 5)

// 创建连接池
val pool = FlightClientPool(config, poolConfig)

// 测试连接
val client = pool.borrowClient()
println(s"Client connected: ${client.isConnected}")
pool.returnClient(client)

// 清理
pool.close()
system.terminate()
```

## 🔍 故障排查

### 问题1: 无法连接到DataFusion Service

**症状**: `ServiceUnavailableException: DataFusion Service is unavailable`

**解决方案**:
1. 确认DataFusion Service正在运行
2. 检查端口是否正确（默认50051）
3. 检查防火墙设置

```bash
# 检查服务是否运行
lsof -i :50051

# 测试连接
telnet localhost 50051
```

### 问题2: SQL语法错误

**症状**: `SQLSyntaxException: SQL syntax error`

**解决方案**:
1. 验证SQL语法是否正确
2. 检查表名和列名
3. 使用参数化查询避免注入

```scala
// ❌ 错误
"SELECT * FORM users"  // FORM拼写错误

// ✅ 正确
"SELECT * FROM users"
```

### 问题3: 连接池耗尽

**症状**: `ConnectionPoolExhaustedException: Connection pool exhausted`

**解决方案**:
1. 增加连接池大小
2. 检查是否有连接泄漏
3. 调整超时时间

```hocon
datafusion {
  pool {
    maxTotal = 20  # 增加最大连接数
    maxIdle = 10
    minIdle = 5
  }
}
```

### 问题4: 查询超时

**症状**: `QueryTimeoutException: Query execution timeout`

**解决方案**:
1. 增加超时时间
2. 优化SQL查询
3. 减少批处理大小

```json
{
  "config": {
    "sql": "SELECT * FROM large_table",
    "timeout": 60,  // 增加到60秒
    "batchSize": 500  // 减少批处理大小
  }
}
```

## 📚 更多资源

### 文档
- [完整总结](DATAFUSION_INTEGRATION_SUMMARY.md)
- [进度跟踪](DATAFUSION_INTEGRATION_PROGRESS.md)
- [任务列表](.kiro/specs/datafusion-integration/tasks.md)

### 示例
- [SQL查询示例](examples/sql-query-example.json)
- [聚合查询示例](examples/sql-aggregation-example.json)

### API参考
- [SQLNodeConfig](pekko-server/src/main/scala/cn/xuyinyin/magic/datafusion/SQLNodeConfig.scala)
- [ParameterizedQuery](pekko-server/src/main/scala/cn/xuyinyin/magic/datafusion/ParameterizedQuery.scala)
- [DataFusionMetrics](pekko-server/src/main/scala/cn/xuyinyin/magic/datafusion/DataFusionMetrics.scala)

## 🎯 下一步

1. **尝试更复杂的SQL查询**
   - JOIN操作
   - 窗口函数
   - 子查询

2. **集成到现有工作流**
   - 替换现有的数据转换节点
   - 利用SQL的强大功能

3. **监控和优化**
   - 查看Prometheus指标
   - 分析查询性能
   - 调整配置参数

4. **生产部署**
   - 使用Docker部署
   - 配置Kubernetes
   - 设置监控告警

## 💡 最佳实践

### 1. 使用参数化查询
```scala
// ✅ 推荐
"SELECT * FROM users WHERE name = :name"

// ❌ 不推荐（SQL注入风险）
s"SELECT * FROM users WHERE name = '$name'"
```

### 2. 合理设置批处理大小
```json
{
  "batchSize": 1000  // 根据数据量调整
}
```

### 3. 监控查询性能
```scala
// 使用QueryTimer
val timer = QueryTimer("my-node")
// ... 执行查询 ...
timer.recordSuccess(rowCount, bytesReceived)
```

### 4. 处理错误
```scala
try {
  // 执行查询
} catch {
  case e: SQLSyntaxException => 
    // 处理SQL语法错误
  case e: QueryTimeoutException => 
    // 处理超时
  case e: ServiceUnavailableException => 
    // 处理服务不可用
}
```

## 🎉 恭喜！

您已经成功设置并测试了DataFusion SQL查询节点！现在可以在您的工作流中使用强大的SQL查询功能了。

如有问题，请查看文档或提交Issue。
