# PekkoSync 模块化项目架构设计

> **设计原则**: 单一职责、高内聚低耦合、易于扩展

---

## 🎯 架构目标

### 当前问题
- ❌ pekko-server 职责过多（集群、工作流、API、监控等）
- ❌ 模块边界不清晰
- ❌ 难以独立测试和部署
- ❌ 未来扩展困难

### 设计目标
- ✅ 每个模块单一职责
- ✅ 模块可独立编译和测试
- ✅ 清晰的依赖关系
- ✅ 易于扩展新功能

---

## 📦 模块划分

### 1. pekko-core（核心基础模块）

**职责**: 提供通用的Actor、Stream工具类

```
pekko-core/
├── actor/
│   ├── ActorUtils.scala          # Actor工具类
│   ├── SupervisorStrategies.scala # 监督策略
│   └── MessageProtocol.scala     # 通用消息协议
│
├── stream/
│   ├── StreamUtils.scala         # Stream工具类
│   ├── BackpressureMonitor.scala # 背压监控
│   └── FlowMetrics.scala         # 流指标
│
├── config/
│   ├── ConfigLoader.scala        # 配置加载
│   └── Settings.scala            # 通用配置
│
└── util/
    ├── JsonSupport.scala         # JSON序列化
    └── Logging.scala             # 日志工具
```

**依赖**: 
- Pekko Actor
- Pekko Stream
- Typesafe Config

**被依赖**: 所有其他模块

---

### 2. pekko-connector（连接器模块）

**职责**: 提供各种数据源和目标的连接器

```
pekko-connector/
│
├── connector-api/                # 连接器API（核心接口）
│   ├── SourceConnector.scala
│   ├── SinkConnector.scala
│   ├── DataRecord.scala
│   ├── Schema.scala
│   └── ConnectorRegistry.scala
│
├── connector-mysql/              # MySQL连接器
│   ├── MySQLSourceConnector.scala
│   ├── MySQLSinkConnector.scala
│   ├── MySQLConfig.scala
│   └── MySQLDialect.scala
│
├── connector-postgresql/         # PostgreSQL连接器
│   ├── PostgreSQLSourceConnector.scala
│   ├── PostgreSQLSinkConnector.scala
│   └── PostgreSQLConfig.scala
│
├── connector-kafka/              # Kafka连接器（未来）
│   ├── KafkaSourceConnector.scala
│   └── KafkaSinkConnector.scala
│
└── connector-file/               # 文件连接器（未来）
    ├── CSVSourceConnector.scala
    └── ParquetSinkConnector.scala
```

**依赖**:
- pekko-core
- HikariCP（JDBC连接器）
- Kafka Client（Kafka连接器）

**被依赖**: pekko-sync

**build.sbt结构**:
```scala
// connector-api
lazy val connectorApi = project.in(file("pekko-connector/connector-api"))

// connector-mysql
lazy val connectorMysql = project.in(file("pekko-connector/connector-mysql"))
  .dependsOn(connectorApi)

// connector-postgresql  
lazy val connectorPostgresql = project.in(file("pekko-connector/connector-postgresql"))
  .dependsOn(connectorApi)
```

---

### 3. pekko-sync（同步引擎模块）

**职责**: 数据同步的核心逻辑

```
pekko-sync/
├── actor/
│   ├── SyncJobActor.scala        # 同步任务Actor
│   ├── SyncCoordinator.scala     # 同步协调器
│   └── BatchProcessor.scala      # 批量处理器
│
├── pipeline/
│   ├── PipelineBuilder.scala     # Pipeline构建器
│   ├── FieldMapper.scala         # 字段映射
│   ├── DataTransformer.scala     # 数据转换
│   └── ErrorHandler.scala        # 错误处理
│
├── strategy/
│   ├── SyncStrategy.scala        # 同步策略接口
│   ├── FullSyncStrategy.scala    # 全量同步
│   ├── IncrementalSyncStrategy.scala # 增量同步
│   └── CDCSyncStrategy.scala     # CDC同步（未来）
│
└── model/
    ├── SyncJob.scala             # 同步任务模型
    ├── SyncConfig.scala          # 同步配置
    └── SyncStatus.scala          # 同步状态
```

**依赖**:
- pekko-core
- pekko-connector（connector-api）

**被依赖**: pekko-server

**核心能力**:
- 管理同步任务生命周期
- 构建Source → Transform → Sink Pipeline
- 错误处理和重试
- 性能优化（批量、并行）

---

### 4. pekko-monitor（监控模块）

**职责**: 实时监控和指标收集

```
pekko-monitor/
├── actor/
│   ├── MonitoringActor.scala     # 监控Actor
│   ├── MetricsCollector.scala    # 指标收集器
│   └── EventRecorder.scala       # 事件记录器
│
├── metrics/
│   ├── SyncMetrics.scala         # 同步指标
│   ├── ConnectorMetrics.scala    # 连接器指标
│   └── PrometheusExporter.scala  # Prometheus导出
│
├── websocket/
│   ├── MonitorWebSocket.scala    # WebSocket推送
│   └── MessageFormatter.scala    # 消息格式化
│
└── model/
    ├── RecordMessage.scala       # 数据记录消息
    ├── MetricsMessage.scala      # 指标消息
    └── EventMessage.scala        # 事件消息
```

**依赖**:
- pekko-core
- pekko-sync（监控同步任务）

**被依赖**: pekko-server

**核心能力**:
- 实时数据流监控
- WebSocket推送
- Prometheus指标导出
- 事件记录和查询

---

### 5. pekko-server（HTTP服务器模块）

**职责**: 对外提供HTTP API和WebSocket，启动和协调各模块

```
pekko-server/
├── api/
│   ├── routes/
│   │   ├── SyncJobRoutes.scala   # 同步任务API
│   │   ├── MonitorRoutes.scala   # 监控API
│   │   ├── ConnectorRoutes.scala # 连接器API
│   │   └── HealthRoutes.scala    # 健康检查
│   │
│   └── dto/
│       ├── SyncJobRequest.scala  # API请求DTO
│       └── SyncJobResponse.scala # API响应DTO
│
├── websocket/
│   └── MonitorWebSocketRoutes.scala # WebSocket路由
│
├── bootstrap/
│   ├── PekkoSyncServer.scala     # 服务器启动类
│   ├── ModuleRegistry.scala      # 模块注册
│   └── DependencyInjection.scala # 依赖注入
│
└── config/
    └── application.conf           # 应用配置
```

**依赖**:
- pekko-core
- pekko-sync
- pekko-monitor
- pekko-connector（可选，用于connector管理API）
- Pekko HTTP

**核心职责**:
- 启动HTTP服务器
- 路由请求到各个模块
- WebSocket连接管理
- 健康检查和监控端点
- **不包含任何业务逻辑！**

---

### 6. pekko-ui（前端模块）

**职责**: 提供Web监控界面

```
pekko-ui/
├── src/
│   ├── pages/
│   │   ├── SyncJobList.tsx       # 任务列表页
│   │   ├── SyncJobMonitor.tsx    # 实时监控页
│   │   └── ConnectorManage.tsx   # 连接器管理页
│   │
│   ├── components/
│   │   ├── MetricsCard.tsx       # 指标卡片
│   │   ├── DataFlowTable.tsx     # 数据流表格
│   │   └── RateChart.tsx         # 速率图表
│   │
│   └── services/
│       ├── api.ts                # API客户端
│       └── websocket.ts          # WebSocket客户端
│
└── package.json
```

**依赖**: React, TypeScript, Ant Design

---

## 🔄 模块依赖关系图

```
┌─────────────────────────────────────────────────────────────┐
│                       pekko-ui (前端)                        │
│                      React + WebSocket                       │
└────────────────────────┬────────────────────────────────────┘
                         │ HTTP/WebSocket
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                    pekko-server (HTTP服务)                   │
│           REST API + WebSocket + 启动协调                     │
└────────┬──────────────────────────┬─────────────────────────┘
         │                          │
         ▼                          ▼
┌─────────────────┐      ┌──────────────────────┐
│  pekko-sync     │      │  pekko-monitor       │
│  (同步引擎)      │      │  (监控模块)           │
└────────┬────────┘      └──────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│                    pekko-connector                           │
│  ┌──────────────┐  ┌───────────────┐  ┌──────────────┐    │
│  │ connector-api│  │connector-mysql│  │connector-pg  │    │
│  └──────────────┘  └───────────────┘  └──────────────┘    │
└────────┬────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│                      pekko-core                              │
│              Actor工具 + Stream工具 + 配置                   │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
    Pekko (Actor + Stream + HTTP)
```

**依赖层次**（从底到上）:
1. **Pekko框架**
2. **pekko-core** - 基础工具
3. **pekko-connector** - 连接器（依赖core）
4. **pekko-sync** + **pekko-monitor** - 业务逻辑（依赖core + connector）
5. **pekko-server** - HTTP服务（依赖sync + monitor）
6. **pekko-ui** - 前端（调用server）

---

## 📝 build.sbt结构

```scala
// ================================
// 核心模块
// ================================
lazy val pekkoCore = (project in file("pekko-core"))
  .settings(commonSettings)
  .settings(
    name := "pekko-core",
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
      "com.typesafe" % "config" % configVersion
    )
  )

// ================================
// 连接器模块
// ================================
lazy val connectorApi = (project in file("pekko-connector/connector-api"))
  .settings(commonSettings)
  .settings(
    name := "pekko-connector-api"
  )
  .dependsOn(pekkoCore)

lazy val connectorMysql = (project in file("pekko-connector/connector-mysql"))
  .settings(commonSettings)
  .settings(
    name := "pekko-connector-mysql",
    libraryDependencies ++= Seq(
      "com.zaxxer" % "HikariCP" % hikariVersion,
      "mysql" % "mysql-connector-java" % mysqlVersion
    )
  )
  .dependsOn(connectorApi)

lazy val connectorPostgresql = (project in file("pekko-connector/connector-postgresql"))
  .settings(commonSettings)
  .settings(
    name := "pekko-connector-postgresql",
    libraryDependencies ++= Seq(
      "com.zaxxer" % "HikariCP" % hikariVersion,
      "org.postgresql" % "postgresql" % postgresVersion
    )
  )
  .dependsOn(connectorApi)

// ================================
// 同步引擎模块
// ================================
lazy val pekkoSync = (project in file("pekko-sync"))
  .settings(commonSettings)
  .settings(
    name := "pekko-sync"
  )
  .dependsOn(pekkoCore, connectorApi)

// ================================
// 监控模块
// ================================
lazy val pekkoMonitor = (project in file("pekko-monitor"))
  .settings(commonSettings)
  .settings(
    name := "pekko-monitor",
    libraryDependencies ++= Seq(
      "io.prometheus" % "simpleclient" % prometheusVersion
    )
  )
  .dependsOn(pekkoCore, pekkoSync)

// ================================
// HTTP服务器模块
// ================================
lazy val pekkoServer = (project in file("pekko-server"))
  .settings(commonSettings)
  .settings(
    name := "pekko-server",
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion
    )
  )
  .dependsOn(
    pekkoCore,
    pekkoSync,
    pekkoMonitor,
    connectorMysql,      // 运行时依赖具体连接器
    connectorPostgresql
  )

// ================================
// 根项目
// ================================
lazy val root = (project in file("."))
  .settings(commonSettings)
  .aggregate(
    pekkoCore,
    connectorApi,
    connectorMysql,
    connectorPostgresql,
    pekkoSync,
    pekkoMonitor,
    pekkoServer
  )
```

---

## ✅ 优势分析

### 1. 单一职责
- ✅ 每个模块职责清晰
- ✅ pekko-server只做HTTP服务，不包含业务逻辑
- ✅ 连接器独立，易于扩展新数据源

### 2. 可测试性
- ✅ 每个模块可独立测试
- ✅ 不需要启动HTTP服务器就能测试sync逻辑
- ✅ Connector可以单独跑集成测试

### 3. 可扩展性
- ✅ 新增连接器：只需在pekko-connector下新建子项目
- ✅ 新增同步策略：只需修改pekko-sync
- ✅ 新增监控指标：只需修改pekko-monitor

### 4. 可部署性
- ✅ 可以只部署pekko-server（包含所有依赖）
- ✅ 可以将connector打包成独立插件
- ✅ 未来可以拆分成微服务

### 5. 团队协作
- ✅ 不同团队负责不同模块
- ✅ 减少代码冲突
- ✅ 清晰的接口边界

---

## 🚀 迁移计划

### Phase 1: 创建新模块结构（今天）

```bash
# 1. 创建核心模块
mkdir -p pekko-core/src/main/scala/cn/xuyinyin/pekko/core

# 2. 创建连接器模块
mkdir -p pekko-connector/connector-api/src/main/scala
mkdir -p pekko-connector/connector-mysql/src/main/scala
mkdir -p pekko-connector/connector-postgresql/src/main/scala

# 3. 创建同步引擎模块
mkdir -p pekko-sync/src/main/scala/cn/xuyinyin/pekko/sync

# 4. 创建监控模块
mkdir -p pekko-monitor/src/main/scala/cn/xuyinyin/pekko/monitor

# 5. 清理pekko-server（移除业务逻辑）
# 只保留API routes和启动类
```

### Phase 2: 迁移代码（Day 1-2）

- Day 1: 创建connector-api和connector-mysql
- Day 2: 创建pekko-sync和pekko-monitor

### Phase 3: 重构pekko-server（Day 3）

- 移除所有业务逻辑
- 只保留HTTP路由和启动代码

---

## 📊 对比：旧 vs 新

| 方面 | 旧架构 | 新架构 |
|------|--------|--------|
| **模块数** | 2个（pekko-server + xxt-ui） | 7个（细粒度） |
| **pekko-server职责** | 全部功能 | 只做HTTP服务 |
| **可测试性** | 难以单独测试 | 每个模块独立测试 |
| **新增连接器** | 修改pekko-server | 新建connector子项目 |
| **代码复用** | 低 | 高（connector可被其他项目复用） |
| **团队协作** | 代码冲突多 | 清晰的模块边界 |

---

**这就是我建议的新架构！你觉得怎么样？** 🎯
