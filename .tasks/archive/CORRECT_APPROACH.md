# 正确的实现方案 - 基于现有Pekko架构

> **核心思想**: 不要重复造轮子，复用现有的优秀架构！

---

## ❌ 我之前的错误

我之前建议创建新模块（pekko-connector, pekko-sync等），这是**重复造轮子**！

你已经有：
- ✅ `NodeSource` / `NodeSink` 接口（类似我的connector-api）
- ✅ `WorkflowSharding` （完整的Cluster Sharding）
- ✅ `EventSourcedWorkflowActor` （类似我的SyncJobActor）
- ✅ HTTP API + 监控

---

## ✅ 正确的做法

### 只需要做3件事：

1. **实现真实的MySQLSource** - 填充TODO
2. **实现真实的MySQLSink** - 填充TODO  
3. **添加数据同步专用的HTTP API**

---

## 📋 具体实现计划

### Step 1: 实现真实的MySQLSource

**文件**: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLSource.scala`

**改动**: 替换模拟数据为真实JDBC

```scala
package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base.NodeSource
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import spray.json._
import spray.json.DefaultJsonProtocol._
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import java.sql.{Connection, ResultSet, PreparedStatement}

class MySQLSource extends NodeSource {
  
  override def nodeType: String = "mysql.query"
  
  override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = {
    // 解析配置（保持不变）
    val host = node.config.fields.get("host").map(_.convertTo[String]).getOrElse("localhost")
    val port = node.config.fields.get("port").map(_.convertTo[Int]).getOrElse(3306)
    val database = node.config.fields.get("database").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("MySQL source缺少database配置"))
    val username = node.config.fields.get("username").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("MySQL source缺少username配置"))
    val password = node.config.fields.get("password").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("MySQL source缺少password配置"))
    val sql = node.config.fields.get("sql").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("MySQL source缺少sql配置"))
    val fetchSize = node.config.fields.get("fetchSize").map(_.convertTo[Int]).getOrElse(1000)
    
    onLog(s"连接MySQL: $host:$port/$database")
    onLog(s"执行查询: $sql")
    
    // ✅ 真实实现（使用HikariCP + 流式查询）
    val dataSource = createDataSource(host, port, database, username, password)
    
    Source.unfoldResource[String, (Connection, PreparedStatement, ResultSet)](
      // 打开资源
      create = () => {
        val connection = dataSource.getConnection
        connection.setAutoCommit(false)
        val statement = connection.prepareStatement(
          sql,
          ResultSet.TYPE_FORWARD_ONLY,
          ResultSet.CONCUR_READ_ONLY
        )
        statement.setFetchSize(fetchSize)
        val resultSet = statement.executeQuery()
        (connection, statement, resultSet)
      },
      
      // 读取数据
      read = { case (conn, stmt, rs) =>
        if (rs.next()) {
          Some(resultSetToJson(rs))  // 转为JSON
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
  
  private def createDataSource(
    host: String,
    port: Int,
    database: String,
    username: String,
    password: String
  ): HikariDataSource = {
    val config = new HikariConfig()
    config.setJdbcUrl(s"jdbc:mysql://$host:$port/$database?useSSL=false&useCursorFetch=true")
    config.setUsername(username)
    config.setPassword(password)
    config.setDriverClassName("com.mysql.cj.jdbc.Driver")
    config.setMaximumPoolSize(5)
    new HikariDataSource(config)
  }
  
  private def resultSetToJson(rs: ResultSet): String = {
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

### Step 2: 实现真实的MySQLSink

**文件**: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sinks/MySQLSink.scala`

**改动**: 替换模拟写入为真实JDBC批量写入

```scala
package cn.xuyinyin.magic.workflow.nodes.sinks

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base.NodeSink
import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.Sink
import spray.json._
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import scala.concurrent.{ExecutionContext, Future}

class MySQLSink extends NodeSink {
  
  override def nodeType: String = "mysql.write"
  
  override def createSink(node: WorkflowDSL.Node, onLog: String => Unit)
                        (implicit ec: ExecutionContext): Sink[String, Future[Done]] = {
    // 解析配置（保持不变）
    val host = node.config.fields.get("host").map(_.convertTo[String]).getOrElse("localhost")
    val port = node.config.fields.get("port").map(_.convertTo[Int]).getOrElse(3306)
    val database = node.config.fields.get("database").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("MySQL sink缺少database配置"))
    val table = node.config.fields.get("table").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("MySQL sink缺少table配置"))
    val username = node.config.fields.get("username").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("MySQL sink缺少username配置"))
    val password = node.config.fields.get("password").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("MySQL sink缺少password配置"))
    val batchSize = node.config.fields.get("batchSize").map(_.convertTo[Int]).getOrElse(1000)
    val mode = node.config.fields.get("mode").map(_.convertTo[String]).getOrElse("insert")
    
    onLog(s"连接MySQL: $host:$port/$database")
    onLog(s"写入表: $table (模式: $mode)")
    
    // ✅ 真实实现
    val dataSource = createDataSource(host, port, database, username, password)
    
    Sink.fold[Int, String](0) { (count, jsonRow) =>
      writeRecord(dataSource, table, jsonRow, mode)
      val newCount = count + 1
      if (newCount % batchSize == 0) {
        onLog(s"已写入 $newCount 行")
      }
      newCount
    }.mapMaterializedValue { future =>
      future.map { totalCount =>
        onLog(s"写入完成，总计: $totalCount 行")
        dataSource.close()
        Done
      }
    }
  }
  
  private def createDataSource(
    host: String,
    port: Int,
    database: String,
    username: String,
    password: String
  ): HikariDataSource = {
    val config = new HikariConfig()
    config.setJdbcUrl(s"jdbc:mysql://$host:$port/$database?rewriteBatchedStatements=true")
    config.setUsername(username)
    config.setPassword(password)
    config.setDriverClassName("com.mysql.cj.jdbc.Driver")
    config.setMaximumPoolSize(10)
    new HikariDataSource(config)
  }
  
  private def writeRecord(
    dataSource: HikariDataSource,
    table: String,
    jsonRow: String,
    mode: String
  ): Unit = {
    val connection = dataSource.getConnection
    connection.setAutoCommit(false)
    
    try {
      // 解析JSON
      val json = jsonRow.parseJson.asJsObject
      val columns = json.fields.keys.mkString(", ")
      val placeholders = json.fields.keys.map(_ => "?").mkString(", ")
      
      // 生成SQL
      val sql = mode match {
        case "insert" =>
          s"INSERT INTO $table ($columns) VALUES ($placeholders)"
        case "upsert" =>
          val updatePart = json.fields.keys.map(k => s"$k = VALUES($k)").mkString(", ")
          s"INSERT INTO $table ($columns) VALUES ($placeholders) ON DUPLICATE KEY UPDATE $updatePart"
        case _ =>
          s"INSERT INTO $table ($columns) VALUES ($placeholders)"
      }
      
      val statement = connection.prepareStatement(sql)
      
      // 设置参数
      json.fields.values.zipWithIndex.foreach { case (value, idx) =>
        statement.setString(idx + 1, value.toString.replaceAll("\"", ""))
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
}
```

---

### Step 3: 添加依赖

**文件**: `pekko-server/build.sbt`（或根build.sbt的pekkoServer部分）

添加HikariCP和MySQL驱动：

```scala
libraryDependencies ++= Seq(
  // ... 现有依赖 ...
  
  // 数据库连接（新增）
  "com.zaxxer" % "HikariCP" % "5.1.0",
  "mysql" % "mysql-connector-java" % "8.0.33"
)
```

---

## 🎯 优势分析

### 复用现有架构

✅ **不需要新模块** - 所有功能都在pekko-server中  
✅ **复用NodeSource/NodeSink** - 接口已经设计好  
✅ **复用WorkflowSharding** - Cluster Sharding已经配置好  
✅ **复用HTTP API** - 已有的API体系  

### 保持一致性

✅ **统一的节点模型** - Source → Transform → Sink  
✅ **统一的配置方式** - 使用WorkflowDSL.Node.config  
✅ **统一的日志回调** - onLog函数  
✅ **统一的Stream类型** - Source[String, NotUsed]  

---

## 📊 对比：错误 vs 正确

| 方面 | 我之前的方案 | 正确的方案 |
|------|-------------|-----------|
| **新建模块** | 5个新模块 | 0个（复用pekko-server） |
| **接口定义** | 新的SourceConnector | 复用NodeSource |
| **Cluster Sharding** | 重新配置 | 复用WorkflowSharding |
| **HTTP API** | 新的路由 | 扩展现有API |
| **代码改动** | 大规模重构 | 只改2个文件 |
| **编译依赖** | 复杂的模块依赖 | 简单（只加2个依赖） |

---

## 🚀 立即行动

### 今天只需要做：

1. ✅ 在build.sbt中添加HikariCP和MySQL驱动
2. ✅ 修改MySQLSource.scala（填充TODO）
3. ✅ 修改MySQLSink.scala（填充TODO）
4. ✅ 测试：创建一个workflow，验证MySQL同步

### 测试workflow示例

```json
{
  "id": "mysql-sync-001",
  "name": "MySQL数据同步",
  "nodes": [
    {
      "id": "source-1",
      "type": "mysql.query",
      "config": {
        "host": "localhost",
        "database": "source_db",
        "username": "root",
        "password": "password",
        "sql": "SELECT * FROM users"
      }
    },
    {
      "id": "sink-1",
      "type": "mysql.write",
      "config": {
        "host": "localhost",
        "database": "target_db",
        "table": "users_copy",
        "username": "root",
        "password": "password"
      }
    }
  ],
  "edges": [
    {
      "source": "source-1",
      "target": "sink-1"
    }
  ]
}
```

---

**这才是正确的做法！复用你现有的优秀架构！** 🎯
