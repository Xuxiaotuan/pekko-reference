# 基于pekko-server的模块拆分方案

> **核心思想**: 保留pekko-server的核心架构和接口，将具体实现拆分到独立模块

---

## 🎯 拆分原则

### ✅ 保留在pekko-server
- 核心接口定义（NodeSource / NodeSink / NodeTransform）
- WorkflowSharding（Cluster Sharding配置）
- EventSourcedWorkflowActor（工作流执行）
- HTTP API路由
- 监控和日志

### ✅ 拆分到新模块
- 具体的连接器实现（MySQL、PostgreSQL等）
- 数据同步专用逻辑
- 监控数据推送（WebSocket）
- 前端UI

---

## 📦 新的模块结构

```
pekko-reference/
│
├── pekko-server/                    ← 核心引擎（保留）
│   ├── workflow/
│   │   ├── nodes/base/              ← NodeSource/NodeSink接口 ✅
│   │   ├── actors/                  ← WorkflowActor ✅
│   │   ├── sharding/                ← WorkflowSharding ✅
│   │   └── engine/                  ← WorkflowExecutionEngine ✅
│   ├── api/http/                    ← HTTP API ✅
│   ├── cluster/                     ← 集群管理 ✅
│   └── monitoring/                  ← 基础监控 ✅
│
├── pekko-connectors/                ← 连接器实现（新建）
│   ├── connector-mysql/
│   │   └── MySQLSourceNode.scala   ← 实现NodeSource接口
│   │   └── MySQLSinkNode.scala     ← 实现NodeSink接口
│   │
│   ├── connector-postgresql/
│   │   └── PostgreSQLSourceNode.scala
│   │   └── PostgreSQLSinkNode.scala
│   │
│   └── connector-kafka/
│       └── KafkaSourceNode.scala
│       └── KafkaSinkNode.scala
│
├── pekko-sync-monitor/              ← 同步监控（新建）
│   ├── MonitoringWebSocket.scala   ← 实时数据推送
│   ├── MetricsCollector.scala      ← 指标收集
│   └── SyncStatsActor.scala        ← 统计Actor
│
└── pekko-ui/                        ← 前端（已有）
    └── React监控界面
```

---

## 🔗 模块依赖关系

```
pekko-server (核心)
    ↑
    ├── pekko-connectors (依赖pekko-server的接口)
    ├── pekko-sync-monitor (依赖pekko-server的Actor)
    └── pekko-ui (调用HTTP API)
```

### 关键点
- ✅ pekko-server是核心，定义接口
- ✅ pekko-connectors依赖pekko-server，实现接口
- ✅ pekko-sync-monitor依赖pekko-server，扩展监控
- ✅ 所有模块共享pekko-server的Cluster Sharding

---

## 💻 具体实现

### 1. pekko-server保持不变

**保留这些接口定义**：

```scala
// pekko-server/src/.../workflow/nodes/base/NodeSource.scala
trait NodeSource {
  def nodeType: String
  def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed]
}

// pekko-server/src/.../workflow/nodes/base/NodeSink.scala
trait NodeSink {
  def nodeType: String
  def createSink(node: WorkflowDSL.Node, onLog: String => Unit)
                (implicit ec: ExecutionContext): Sink[String, Future[Done]]
}
```

**删除模拟实现**：
- ❌ 删除 `MySQLSource.scala`（移到pekko-connectors）
- ❌ 删除 `MySQLSink.scala`（移到pekko-connectors）
- ✅ 保留 `NodeSource/NodeSink` 接口定义

---

### 2. 创建pekko-connectors模块

**目录结构**：
```
pekko-connectors/
├── build.sbt
└── src/main/scala/cn/xuyinyin/magic/connectors/
    ├── mysql/
    │   ├── MySQLSourceNode.scala
    │   ├── MySQLSinkNode.scala
    │   └── MySQLConfig.scala
    │
    └── postgresql/
        ├── PostgreSQLSourceNode.scala
        └── PostgreSQLSinkNode.scala
```

**build.sbt**：
```scala
name := "pekko-connectors"
version := "0.1.0"

libraryDependencies ++= Seq(
  // 依赖pekko-server（获取接口定义）
  // 在根build.sbt中配置 .dependsOn(pekkoServer)
  
  // 连接器特定依赖
  "com.zaxxer" % "HikariCP" % "5.1.0",
  "mysql" % "mysql-connector-java" % "8.0.33",
  "org.postgresql" % "postgresql" % "42.7.0"
)
```

**实现示例**：

```scala
// pekko-connectors/src/main/scala/cn/xuyinyin/magic/connectors/mysql/MySQLSourceNode.scala

package cn.xuyinyin.magic.connectors.mysql

import cn.xuyinyin.magic.workflow.nodes.base.NodeSource  // ← 复用pekko-server的接口
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import spray.json._
import DefaultJsonProtocol._

/**
 * MySQL Source实现
 * 
 * 实现pekko-server定义的NodeSource接口
 */
class MySQLSourceNode extends NodeSource {
  
  override def nodeType: String = "mysql.query"
  
  override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = {
    // 解析配置
    val host = node.config.fields.get("host").map(_.convertTo[String]).getOrElse("localhost")
    val port = node.config.fields.get("port").map(_.convertTo[Int]).getOrElse(3306)
    val database = node.config.fields.get("database").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("database不能为空"))
    val username = node.config.fields.get("username").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("username不能为空"))
    val password = node.config.fields.get("password").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("password不能为空"))
    val sql = node.config.fields.get("sql").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("sql不能为空"))
    val fetchSize = node.config.fields.get("fetchSize").map(_.convertTo[Int]).getOrElse(1000)
    
    onLog(s"[MySQL Source] 连接: $host:$port/$database")
    onLog(s"[MySQL Source] 查询: $sql")
    
    // 创建连接池
    val dataSource = createDataSource(host, port, database, username, password)
    
    // 使用unfoldResource流式读取
    Source.unfoldResource[String, (java.sql.Connection, java.sql.PreparedStatement, java.sql.ResultSet)](
      create = () => {
        val connection = dataSource.getConnection
        connection.setAutoCommit(false)
        val statement = connection.prepareStatement(
          sql,
          java.sql.ResultSet.TYPE_FORWARD_ONLY,
          java.sql.ResultSet.CONCUR_READ_ONLY
        )
        statement.setFetchSize(fetchSize)
        val resultSet = statement.executeQuery()
        (connection, statement, resultSet)
      },
      
      read = { case (conn, stmt, rs) =>
        if (rs.next()) {
          Some(resultSetToJson(rs))
        } else {
          None
        }
      },
      
      close = { case (conn, stmt, rs) =>
        try { rs.close() } catch { case _: Exception => }
        try { stmt.close() } catch { case _: Exception => }
        try { conn.close() } catch { case _: Exception => }
        try { dataSource.close() } catch { case _: Exception => }
      }
    )
  }
  
  private def createDataSource(
    host: String, port: Int, database: String, 
    username: String, password: String
  ): HikariDataSource = {
    val config = new HikariConfig()
    config.setJdbcUrl(s"jdbc:mysql://$host:$port/$database?useSSL=false&useCursorFetch=true")
    config.setUsername(username)
    config.setPassword(password)
    config.setDriverClassName("com.mysql.cj.jdbc.Driver")
    config.setMaximumPoolSize(5)
    new HikariDataSource(config)
  }
  
  private def resultSetToJson(rs: java.sql.ResultSet): String = {
    val metadata = rs.getMetaData
    val columnCount = metadata.getColumnCount
    val values = (1 to columnCount).map { i =>
      val columnName = metadata.getColumnName(i)
      val value = rs.getObject(i)
      columnName -> (if (value == null) JsNull else JsString(value.toString))
    }.toMap
    JsObject(values).compactPrint
  }
}
```

---

### 3. 创建pekko-sync-monitor模块

**目录结构**：
```
pekko-sync-monitor/
├── build.sbt
└── src/main/scala/cn/xuyinyin/magic/sync/monitor/
    ├── MonitoringWebSocket.scala
    ├── SyncStatsActor.scala
    └── MetricsCollector.scala
```

**功能**：
- 实时推送同步数据到WebSocket
- 收集同步指标
- 提供监控API

---

### 4. 更新根build.sbt

```scala
// ================================
// pekko-server (核心引擎)
// ================================
lazy val pekkoServer = (project in file("pekko-server"))
  .settings(commonSettings)
  .settings(
    name := "pekko-server",
    // 原有依赖保持不变
    // 注意：移除HikariCP和MySQL驱动（移到pekko-connectors）
  )

// ================================
// pekko-connectors (连接器实现)
// ================================
lazy val pekkoConnectors = (project in file("pekko-connectors"))
  .settings(commonSettings)
  .settings(
    name := "pekko-connectors",
    libraryDependencies ++= Seq(
      "com.zaxxer" % "HikariCP" % "5.1.0",
      "mysql" % "mysql-connector-java" % "8.0.33",
      "org.postgresql" % "postgresql" % "42.7.0"
    )
  )
  .dependsOn(pekkoServer)  // ← 依赖pekko-server的接口

// ================================
// pekko-sync-monitor (同步监控)
// ================================
lazy val pekkoSyncMonitor = (project in file("pekko-sync-monitor"))
  .settings(commonSettings)
  .settings(
    name := "pekko-sync-monitor",
    libraryDependencies ++= Seq(
      "io.prometheus" % "simpleclient" % "0.16.0"
    )
  )
  .dependsOn(pekkoServer)  // ← 依赖pekko-server的Actor

// ================================
// 打包模块（运行时包含所有依赖）
// ================================
lazy val pekkoSyncApp = (project in file("pekko-sync-app"))
  .settings(commonSettings)
  .settings(
    name := "pekko-sync-app",
    mainClass := Some("cn.xuyinyin.magic.PekkoServer")
  )
  .dependsOn(
    pekkoServer,
    pekkoConnectors,      // ← 运行时依赖
    pekkoSyncMonitor      // ← 运行时依赖
  )

// ================================
// 根项目
// ================================
lazy val root = (project in file("."))
  .settings(commonSettings)
  .aggregate(
    pekkoServer,
    pekkoConnectors,
    pekkoSyncMonitor,
    pekkoSyncApp
  )
```

---

## 🎯 关键优势

### 1. 复用现有架构

✅ **NodeSource/NodeSink接口** - pekko-server定义，其他模块实现  
✅ **WorkflowSharding** - 所有模块共享  
✅ **EventSourcedWorkflowActor** - 核心执行逻辑不变  
✅ **HTTP API** - 路由和监控保留  

### 2. 模块化拆分

✅ **pekko-connectors** - 独立开发连接器  
✅ **pekko-sync-monitor** - 独立开发监控  
✅ **清晰的依赖关系** - 单向依赖，无循环  

### 3. 灵活部署

✅ **开发时** - 只编译需要的模块  
✅ **运行时** - pekko-sync-app打包所有依赖  
✅ **扩展时** - 新增connector只需新建模块  

---

## 📋 迁移步骤

### Step 1: 创建pekko-connectors模块

```bash
mkdir -p pekko-connectors/src/main/scala/cn/xuyinyin/magic/connectors/mysql
mkdir -p pekko-connectors/src/test/scala
```

### Step 2: 移动MySQL实现

```bash
# 从pekko-server移动到pekko-connectors
mv pekko-server/src/.../MySQLSource.scala \
   pekko-connectors/src/.../MySQLSourceNode.scala

mv pekko-server/src/.../MySQLSink.scala \
   pekko-connectors/src/.../MySQLSinkNode.scala
```

### Step 3: 更新import语句

在移动的文件中：
```scala
// 保持import pekko-server的接口
import cn.xuyinyin.magic.workflow.nodes.base.NodeSource
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
```

### Step 4: 更新build.sbt

添加pekko-connectors模块配置

### Step 5: 注册新的Node实现

在pekko-server中创建NodeRegistry：
```scala
// pekko-server/src/.../workflow/nodes/registry/NodeRegistry.scala

object NodeRegistry {
  // 在运行时加载pekko-connectors中的实现
  def loadConnectors(): Map[String, NodeSource] = {
    Map(
      "mysql.query" -> new cn.xuyinyin.magic.connectors.mysql.MySQLSourceNode()
    )
  }
}
```

---

## 🔄 与之前方案的对比

| 方面 | 完全新建模块 | **基于pekko-server拆分** |
|------|------------|------------------------|
| 接口定义 | 新建connector-api | ✅ 复用NodeSource/NodeSink |
| Cluster Sharding | 重新配置 | ✅ 复用WorkflowSharding |
| 工作流引擎 | 新建SyncJobActor | ✅ 复用EventSourcedWorkflowActor |
| HTTP API | 新建路由 | ✅ 复用现有API |
| 依赖关系 | 复杂的模块间依赖 | ✅ 简单单向依赖 |
| 代码复用 | 低 | ✅ 高 |

---

## 🎉 最终效果

### 开发时
```bash
# 只开发MySQL connector
sbt "project pekkoConnectors" compile

# 只开发监控
sbt "project pekkoSyncMonitor" compile
```

### 运行时
```bash
# 打包运行（包含所有模块）
sbt "project pekkoSyncApp" run

# 或者Docker部署
sbt "project pekkoSyncApp" docker:publishLocal
```

### 扩展时
```bash
# 新增PostgreSQL connector
mkdir -p pekko-connectors/src/.../postgresql
# 实现NodeSource接口即可
```

---

**这样既实现了模块化，又完全复用了你的Pekko架构！** 🎯
