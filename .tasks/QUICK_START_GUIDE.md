# PekkoSync 改造快速开始指南

> **从现在开始，立即行动！**

---

## 🎯 第一周任务清单

### Day 1: 项目重命名

#### 1. 创建新分支

```bash
cd /Users/xujiawei/magic/scala-workbench/pekko-reference
git checkout -b feature/data-sync-refactor
```

#### 2. 重命名核心包

```bash
# 方案1: 使用IDE重构功能（推荐）
# IntelliJ IDEA: 右键包名 → Refactor → Rename

# 方案2: 手动重命名
mv pekko-server/src/main/scala/cn/xuyinyin/magic \
   pekko-server/src/main/scala/cn/xuyinyin/pekkosync
```

#### 3. 更新build.sbt

```scala
// build.sbt
lazy val syncCore = (project in file("sync-core"))
  .settings(
    name := "pekko-sync-core",
    version := "1.0.0-SNAPSHOT",
    scalaVersion := "2.13.12",
    // ... 其他配置
  )

lazy val syncServer = (project in file("sync-server"))
  .settings(
    name := "pekko-sync-server",
    // ... 其他配置
  )
  .dependsOn(syncCore)
```

#### 4. 更新README.md

```bash
cp .tasks/NEW_README.md README.md
```

**验收**: 项目能编译通过 `sbt compile`

---

### Day 2-3: Connector API设计

#### 1. 创建核心API包

```bash
mkdir -p sync-core/src/main/scala/cn/xuyinyin/pekkosync/connector/api
```

#### 2. 实现Connector接口

从`.tasks/DATA_SYNC_REFACTOR_PLAN.md`复制以下文件：

```scala
// sync-core/src/main/scala/cn/xuyinyin/pekkosync/connector/api/SourceConnector.scala
trait SourceConnector {
  def connectorType: String
  def version: String
  def createSource(config: SourceConfig): Source[DataRecord, NotUsed]
  def validateConfig(config: SourceConfig): Either[ConfigError, Unit]
  def getSchema(config: SourceConfig): Schema
}

// sync-core/src/main/scala/cn/xuyinyin/pekkosync/connector/api/SinkConnector.scala
// sync-core/src/main/scala/cn/xuyinyin/pekkosync/connector/api/CDCConnector.scala
// sync-core/src/main/scala/cn/xuyinyin/pekkosync/connector/api/DataRecord.scala
// sync-core/src/main/scala/cn/xuyinyin/pekkosync/connector/api/Schema.scala
```

#### 3. 创建测试

```scala
// sync-core/src/test/scala/cn/xuyinyin/pekkosync/connector/api/ConnectorAPISpec.scala
class ConnectorAPISpec extends AnyFlatSpec with Matchers {
  "SourceConnector" should "validate config correctly" in {
    // 测试代码
  }
}
```

**验收**: 所有测试通过 `sbt test`

---

### Day 4-5: MySQL连接器重构

#### 1. 创建JDBC框架

```bash
mkdir -p sync-connectors/connector-jdbc/src/main/scala/cn/xuyinyin/pekkosync/connector/jdbc
```

#### 2. 实现JDBCSourceConnector

从`.tasks/DATA_SYNC_REFACTOR_PLAN.md`复制：

```scala
// sync-connectors/connector-jdbc/src/main/scala/.../JDBCSourceConnector.scala
abstract class JDBCSourceConnector extends SourceConnector {
  // ... 完整实现
}
```

#### 3. 实现MySQLSourceConnector

```scala
// sync-connectors/connector-jdbc/src/main/scala/.../MySQLSourceConnector.scala
class MySQLSourceConnector extends JDBCSourceConnector {
  override def connectorType: String = "mysql"
  override def version: String = "8.0"
  override def driverClass: String = "com.mysql.cj.jdbc.Driver"
  // ... 完整实现
}
```

#### 4. 添加HikariCP依赖

```scala
// sync-connectors/connector-jdbc/build.sbt
libraryDependencies ++= Seq(
  "com.zaxxer" % "HikariCP" % "5.1.0",
  "mysql" % "mysql-connector-java" % "8.0.33"
)
```

#### 5. 集成测试

```scala
// 需要Docker + MySQL
class MySQLSourceConnectorSpec extends AnyFlatSpec with Matchers {
  "MySQLSourceConnector" should "read data correctly" in {
    // 测试代码
  }
}
```

**验收**: MySQL连接器能正确读取数据

---

### Day 6-7: PostgreSQL连接器实现

#### 1. 实现PostgreSQLSourceConnector

```scala
// 类似MySQL实现
class PostgreSQLSourceConnector extends JDBCSourceConnector {
  override def connectorType: String = "postgresql"
  // ...
}
```

#### 2. 实现PostgreSQLSinkConnector

```scala
class PostgreSQLSinkConnector extends JDBCSinkConnector {
  // UPSERT语法: ON CONFLICT DO UPDATE
  override protected def generateUpsertSQL(...): String = {
    // ...
  }
}
```

#### 3. 添加依赖

```scala
libraryDependencies += "org.postgresql" % "postgresql" % "42.7.1"
```

**验收**: 
- MySQL → PostgreSQL同步测试通过
- 性能达到5万行/秒

---

## 📊 第一周里程碑

完成后你将拥有：

- ✅ 统一的Connector API
- ✅ 生产级MySQL连接器（HikariCP连接池）
- ✅ 生产级PostgreSQL连接器
- ✅ 完整的单元测试
- ✅ 可运行的集成测试

---

## 🚀 第二周任务（CDC集成）

### 准备工作

#### 1. 添加Debezium依赖

```scala
// sync-cdc/build.sbt
libraryDependencies ++= Seq(
  "io.debezium" % "debezium-embedded" % "2.5.0.Final",
  "io.debezium" % "debezium-connector-mysql" % "2.5.0.Final",
  "org.apache.kafka" % "connect-api" % "3.6.0",
  "org.apache.kafka" % "connect-json" % "3.6.0"
)
```

#### 2. 创建CDC包

```bash
mkdir -p sync-cdc/src/main/scala/cn/xuyinyin/pekkosync/cdc/debezium
```

### 实现MySQL CDC

#### 1. DebeziumCDCEngine

从`.tasks/DATA_SYNC_REFACTOR_PLAN.md`复制完整实现：

```scala
// sync-cdc/src/main/scala/.../DebeziumCDCEngine.scala
class DebeziumCDCEngine {
  def createMySQLCDCSource(config: MySQLCDCConfig): Source[ChangeEvent, NotUsed]
  // ...
}
```

#### 2. 测试CDC

```scala
// 需要MySQL + Binlog开启
class DebeziumCDCEngineSpec extends AnyFlatSpec {
  "DebeziumCDCEngine" should "capture INSERT events" in {
    // 测试代码
  }
}
```

### 实现端到端CDC同步

#### 示例: MySQL CDC → PostgreSQL

```scala
// examples/MySQLCDCToPostgresExample.scala
object MySQLCDCToPostgresExample extends App {
  
  // 创建CDC Source
  val cdcSource = debeziumEngine.createMySQLCDCSource(cdcConfig)
  
  // 创建PostgreSQL Sink
  val postgresSink = postgresSinkConnector.createSink(sinkConfig)
  
  // 连接Pipeline
  cdcSource
    .map(changeEventToDataRecord)
    .runWith(postgresSink)
}
```

**验收**: 
- MySQL binlog事件能被捕获
- 延迟P99 < 200ms
- 数据零丢失

---

## 📝 第三周任务（文档与测试）

### 1. 完善文档

```bash
# 复制模板
cp .tasks/NEW_README.md README.md

# 创建文档目录
mkdir -p docs/{quickstart,architecture,connectors,cdc}

# 编写文档
docs/QUICKSTART.md
docs/ARCHITECTURE.md
docs/CONNECTOR_DEV.md
docs/CDC_GUIDE.md
```

### 2. 性能测试

```bash
# 运行基准测试
sbt "project sync-benchmark" run

# 生成报告
./scripts/generate-benchmark-report.sh
```

### 3. 准备发布

```bash
# 构建Docker镜像
docker build -t pekkosync/pekkosync:1.0.0-beta .

# 发布到Docker Hub
docker push pekkosync/pekkosync:1.0.0-beta
```

---

## 🎯 第一个月目标

### 技术目标

- ✅ 10个连接器
- ✅ MySQL CDC支持
- ✅ 批量吞吐 > 10万行/秒
- ✅ CDC延迟P99 < 200ms
- ✅ 完整文档

### 社区目标

- ✅ GitHub发布
- ✅ 500+ Stars
- ✅ 10+ 贡献者
- ✅ 5+ 企业用户
- ✅ 4篇技术博客

---

## 🛠️ 开发工具推荐

### IDE

```
IntelliJ IDEA Ultimate
- Scala插件
- SBT插件
- Docker插件
```

### 数据库工具

```
DBeaver / DataGrip
- MySQL连接
- PostgreSQL连接
- 数据对比
```

### 测试工具

```
Docker Compose
- MySQL
- PostgreSQL
- Kafka
```

### 性能分析

```
JProfiler / YourKit
- CPU分析
- 内存分析
- GC分析
```

---

## 📞 需要帮助？

### 技术问题

- 查看 `.tasks/DATA_SYNC_REFACTOR_PLAN.md`
- 搜索 Pekko文档
- 提Issue到GitHub

### 设计问题

- 参考 Debezium架构
- 参考 Airbyte设计
- 查看 Apache Kafka Connect

### 其他问题

- Discord: #development频道
- Email: support@pekkosync.io

---

## ✅ 每日检查清单

### 开发流程

- [ ] 拉取最新代码 `git pull`
- [ ] 创建特性分支 `git checkout -b feature/xxx`
- [ ] 编写代码
- [ ] 运行测试 `sbt test`
- [ ] 提交代码 `git commit -m "feat: xxx"`
- [ ] 推送分支 `git push`
- [ ] 创建PR

### 质量保证

- [ ] 代码格式化 `sbt scalafmt`
- [ ] 静态检查 `sbt scalafix`
- [ ] 测试覆盖 `sbt coverage`
- [ ] 文档更新
- [ ] Changelog更新

---

## 🎉 开始你的第一个任务！

```bash
# 1. 创建分支
git checkout -b feature/connector-api

# 2. 创建核心API
mkdir -p sync-core/src/main/scala/cn/xuyinyin/pekkosync/connector/api

# 3. 复制Connector API代码
# 从 .tasks/DATA_SYNC_REFACTOR_PLAN.md

# 4. 编译测试
sbt compile
sbt test

# 5. 提交代码
git add .
git commit -m "feat: add connector API"
git push origin feature/connector-api
```

**加油！你正在创造一个伟大的开源项目！** 🚀
