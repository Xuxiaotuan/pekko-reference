# PekkoSync - 下一代分布式数据同步引擎

<div align="center">

![PekkoSync Logo](docs/images/logo.png)

**分布式 · 实时 · 容错 · 高性能**

基于Actor模型的企业级数据同步平台

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Scala Version](https://img.shields.io/badge/scala-2.13.12-red.svg)](https://www.scala-lang.org)
[![Pekko Version](https://img.shields.io/badge/pekko-1.0.2-green.svg)](https://pekko.apache.org)
[![Build Status](https://img.shields.io/github/workflow/status/xuxiaotuan/pekko-sync/CI)](https://github.com/xuxiaotuan/pekko-sync/actions)

[English](README.md) | [中文](README_CN.md)

[快速开始](#-快速开始) · [文档](docs/) · [示例](examples/) · [性能对比](#-性能对比)

</div>

---

## 📖 简介

**PekkoSync**是一个基于Apache Pekko（原Akka）构建的高性能分布式数据同步引擎，提供实时CDC、批量ETL、数据库迁移等能力。

### 为什么选择PekkoSync？

与传统数据同步工具（DataX、SeaTunnel）相比，PekkoSync具有：

| 特性 | DataX | SeaTunnel | **PekkoSync** |
|------|-------|-----------|---------------|
| **架构** | 单机 | 批处理为主 | 原生分布式 |
| **实时性** | ❌ | 部分支持 | ✅ 毫秒级CDC |
| **容错** | 任务重试 | 任务重试 | ✅ Actor自愈 |
| **背压** | ❌ | 部分支持 | ✅ 原生Reactive Streams |
| **扩展性** | 单机瓶颈 | 需外部调度 | ✅ 自动Sharding |
| **性能** | 中等 | 中等 | ✅ 10-30万行/秒 |
| **可观测** | 基础 | 基础 | ✅ Prometheus+分布式追踪 |

### 核心特性

#### 🚀 实时CDC
- MySQL Binlog实时捕获（基于Debezium）
- PostgreSQL WAL实时监听
- MongoDB Change Stream
- 毫秒级延迟，数据零丢失

#### 💪 高性能
- 单节点：**10万行/秒**
- 3节点集群：**30万行/秒**
- 零拷贝传输（Arrow Flight）
- 批量优化与并行化

#### 🛡️ 高可用
- 自动故障转移（< 10秒）
- 99.95%可用性保证
- Event Sourcing完整历史
- 断点续传与精确一次语义

#### 📊 可视化
- DAG拖拽式配置
- 实时监控面板
- 数据血缘分析
- Grafana集成

#### 🔧 易扩展
- 插件式连接器架构
- 自定义转换函数
- 丰富的数据源支持

---

## 🎯 支持的数据源

### 数据库

| 数据库 | Source | Sink | CDC | 状态 |
|--------|--------|------|-----|------|
| **MySQL** | ✅ | ✅ | ✅ | 生产就绪 |
| **PostgreSQL** | ✅ | ✅ | ✅ | 生产就绪 |
| **Oracle** | ✅ | ✅ | 🚧 | 开发中 |
| **SQL Server** | ✅ | ✅ | 🚧 | 开发中 |
| **MongoDB** | ✅ | ✅ | ✅ | 生产就绪 |
| **Redis** | ✅ | ✅ | ❌ | 计划中 |
| **ClickHouse** | ❌ | ✅ | ❌ | 生产就绪 |
| **Elasticsearch** | ❌ | ✅ | ❌ | 计划中 |

### 消息队列

| 消息队列 | Source | Sink | 状态 |
|---------|--------|------|------|
| **Kafka** | ✅ | ✅ | 生产就绪 |
| **RabbitMQ** | 🚧 | 🚧 | 开发中 |
| **Pulsar** | 🚧 | 🚧 | 计划中 |

### 文件系统

| 存储 | Source | Sink | 格式 | 状态 |
|------|--------|------|------|------|
| **S3** | ✅ | ✅ | CSV, JSON, Parquet | 生产就绪 |
| **HDFS** | ✅ | ✅ | CSV, JSON, Parquet | 生产就绪 |
| **Local/NFS** | ✅ | ✅ | CSV, JSON, Text | 生产就绪 |
| **FTP/SFTP** | 🚧 | 🚧 | CSV, JSON, Text | 开发中 |

---

## 🚀 快速开始

### 前置要求

- JDK 11+
- Scala 2.13.12
- Docker（可选，用于快速部署）

### 方式1：Docker快速部署（推荐）

```bash
# 拉取镜像
docker pull pekkosync/pekkosync:latest

# 启动单节点
docker run -p 8080:8080 \
  -v /path/to/config:/app/config \
  -v /path/to/data:/app/data \
  pekkosync/pekkosync:latest

# 或使用Docker Compose启动3节点集群
docker-compose up -d
```

访问 http://localhost:8080 查看Web UI。

### 方式2：从源码构建

```bash
# 克隆仓库
git clone https://github.com/xuxiaotuan/pekko-sync.git
cd pekko-sync

# 编译
sbt "project sync-server" assembly

# 启动单节点
java -jar sync-server/target/scala-2.13/pekko-sync-server.jar
```

### 创建第一个同步任务

#### 场景1：MySQL → PostgreSQL（批量同步）

```bash
curl -X POST http://localhost:8080/api/v1/sync/jobs \
  -H "Content-Type: application/json" \
  -d '{
    "name": "mysql-to-postgres",
    "description": "同步订单数据",
    "source": {
      "type": "mysql",
      "config": {
        "host": "mysql-source:3306",
        "database": "ecommerce",
        "table": "orders",
        "username": "root",
        "password": "password",
        "fetchSize": 1000
      }
    },
    "sink": {
      "type": "postgresql",
      "config": {
        "host": "postgres-target:5432",
        "database": "analytics",
        "table": "orders",
        "username": "postgres",
        "password": "password",
        "batchSize": 1000,
        "writeMode": "upsert"
      }
    },
    "transform": {
      "type": "sql",
      "sql": "SELECT id, user_id, amount, created_at FROM input WHERE amount > 100"
    },
    "schedule": {
      "type": "cron",
      "expression": "0 */10 * * * ?"
    }
  }'
```

#### 场景2：MySQL CDC → ClickHouse（实时同步）

```bash
curl -X POST http://localhost:8080/api/v1/sync/jobs \
  -H "Content-Type: application/json" \
  -d '{
    "name": "mysql-cdc-to-clickhouse",
    "description": "实时订单数据到数据仓库",
    "source": {
      "type": "mysql-cdc",
      "config": {
        "host": "mysql-source:3306",
        "database": "ecommerce",
        "tables": ["orders", "order_items"],
        "username": "debezium",
        "password": "dbz",
        "serverId": 85744,
        "serverName": "mysql-server-1"
      }
    },
    "sink": {
      "type": "clickhouse",
      "config": {
        "host": "clickhouse:8123",
        "database": "analytics",
        "batchSize": 10000
      }
    },
    "mode": "cdc"
  }'
```

#### 场景3：Kafka → MongoDB

```bash
curl -X POST http://localhost:8080/api/v1/sync/jobs \
  -H "Content-Type: application/json" \
  -d '{
    "name": "kafka-to-mongodb",
    "description": "用户行为数据入库",
    "source": {
      "type": "kafka",
      "config": {
        "bootstrapServers": "kafka:9092",
        "topic": "user-events",
        "groupId": "pekkosync-consumer",
        "offsetReset": "earliest"
      }
    },
    "sink": {
      "type": "mongodb",
      "config": {
        "connectionString": "mongodb://mongo:27017",
        "database": "analytics",
        "collection": "user_events"
      }
    }
  }'
```

### 查看任务状态

```bash
# 列出所有任务
curl http://localhost:8080/api/v1/sync/jobs

# 查看任务详情
curl http://localhost:8080/api/v1/sync/jobs/{jobId}

# 查看任务指标
curl http://localhost:8080/api/v1/sync/jobs/{jobId}/metrics
```

---

## 🏗️ 系统架构

### 高层架构

```
                          ┌─────────────────────────────────┐
                          │         Web UI / API            │
                          └────────────┬────────────────────┘
                                       │
                          ┌────────────▼────────────────────┐
                          │    PekkoSync Cluster            │
                          │  ┌───────────────────────────┐  │
                          │  │  Sync Coordinator Node    │  │
                          │  │  - Job Scheduling         │  │
                          │  │  - Cluster Management     │  │
                          │  └───────────────────────────┘  │
                          │  ┌───────────────────────────┐  │
                          │  │  Sync Worker Nodes (N)    │  │
                          │  │  - Data Pipeline Exec     │  │
                          │  │  - Backpressure Control   │  │
                          │  │  - Checkpoint Management  │  │
                          │  └───────────────────────────┘  │
                          └─────────────────────────────────┘
                                       │
             ┌─────────────────────────┼─────────────────────────┐
             │                         │                         │
    ┌────────▼────────┐       ┌───────▼────────┐      ┌────────▼────────┐
    │  Source         │       │  Transform      │      │  Sink           │
    │  Connectors     │       │  Engine         │      │  Connectors     │
    │  - MySQL CDC    │       │  - SQL          │      │  - PostgreSQL   │
    │  - Kafka        │       │  - Filter       │      │  - ClickHouse   │
    │  - S3           │       │  - Map          │      │  - MongoDB      │
    └─────────────────┘       └─────────────────┘      └─────────────────┘
```

### 核心组件

#### 1. Cluster Sharding（集群分片）
- 自动任务分片到多个节点
- 动态负载均衡
- 故障自动迁移

#### 2. Reactive Streams（响应式流）
- 自动背压控制
- 批量处理优化
- 流批统一处理

#### 3. Event Sourcing（事件溯源）
- 完整同步历史
- 精确一次语义
- 断点续传

#### 4. CDC Engine（变更数据捕获）
- Debezium Embedded
- Offset管理
- Schema演进

---

## 📊 性能对比

### Benchmark环境

- **机器**: 3节点 × (4vCPU, 8GB RAM)
- **网络**: 1Gbps
- **数据集**: TPC-H 10GB

### MySQL → PostgreSQL 批量同步

| 工具 | 吞吐量 | CPU占用 | 内存占用 | 延迟(P99) |
|------|--------|---------|----------|-----------|
| DataX | 5万行/秒 | 80% | 2GB | - |
| SeaTunnel | 8万行/秒 | 70% | 3GB | - |
| **PekkoSync** | **12万行/秒** | **60%** | **4GB** | **< 100ms** |

### MySQL CDC → ClickHouse 实时同步

| 指标 | SeaTunnel CDC | **PekkoSync CDC** |
|------|---------------|-------------------|
| 延迟(P50) | 500ms | **50ms** |
| 延迟(P99) | 2s | **200ms** |
| 吞吐量 | 2万行/秒 | **5万行/秒** |
| 故障恢复 | 30秒 | **< 10秒** |

### 扩展性测试

| 节点数 | 吞吐量 | 扩展因子 |
|--------|--------|----------|
| 1 | 10万行/秒 | 1.0x |
| 2 | 18万行/秒 | 1.8x |
| 3 | 24万行/秒 | 2.4x |
| 5 | 40万行/秒 | 4.0x |

详细性能测试报告：[BENCHMARK.md](docs/BENCHMARK.md)

---

## 📚 文档

### 用户指南

- [快速开始](docs/QUICKSTART.md) - 5分钟快速上手
- [安装部署](docs/DEPLOYMENT.md) - 单机/集群/K8s部署
- [配置指南](docs/CONFIGURATION.md) - 完整配置说明
- [连接器使用](docs/CONNECTORS.md) - 所有连接器详细文档
- [CDC指南](docs/CDC_GUIDE.md) - CDC最佳实践

### 开发者指南

- [架构设计](docs/ARCHITECTURE.md) - 系统架构详解
- [连接器开发](docs/CONNECTOR_DEV.md) - 如何开发自定义连接器
- [性能调优](docs/PERFORMANCE_TUNING.md) - 性能优化指南
- [监控运维](docs/MONITORING.md) - Prometheus+Grafana监控

### API参考

- [REST API](docs/API_REFERENCE.md) - 完整API文档
- [Connector API](docs/CONNECTOR_API.md) - 连接器API规范

---

## 🎨 典型应用场景

### 1. 数据库迁移

**场景**：MySQL → PostgreSQL 零停机迁移

```
阶段1：全量同步（批量）
  MySQL Source → Transform → PostgreSQL Sink
  
阶段2：增量同步（CDC）
  MySQL CDC → 实时增量 → PostgreSQL Sink
  
阶段3：切换
  业务流量切到PostgreSQL
```

### 2. 实时数据湖

**场景**：业务库 → 数据湖实时同步

```
MySQL CDC → Transform → S3 Parquet
  ↓                        ↓
MongoDB CDC           Hive/Presto/Spark
  ↓
PostgreSQL CDC
```

### 3. 实时数仓

**场景**：OLTP → OLAP实时同步

```
MySQL CDC → Kafka → Transform → ClickHouse
             ↓
         数据质量检查
             ↓
         Schema Registry
```

### 4. 异构数据同步

**场景**：多数据源整合

```
Oracle → 
MySQL  →  Transform  →  MongoDB
PostgreSQL →  (JOIN/Filter)   →  Elasticsearch
```

---

## 🛠️ 技术栈

### 核心框架

- **Apache Pekko** 1.0.2 - Actor并发模型
- **Pekko Streams** - Reactive Streams实现
- **Pekko Cluster Sharding** - 分布式分片
- **Pekko Persistence** - Event Sourcing
- **Pekko HTTP** - REST API

### 连接器

- **JDBC** - HikariCP连接池
- **Debezium** - CDC引擎
- **Kafka Connect** - Kafka集成
- **MongoDB Scala Driver** - MongoDB连接
- **Apache Arrow** - 列式数据格式

### 监控

- **Prometheus** - 指标收集
- **Grafana** - 可视化
- **OpenTelemetry** - 分布式追踪

---

## 🤝 贡献指南

我们欢迎所有形式的贡献！

### 如何贡献

1. **Fork项目**
2. **创建特性分支** (`git checkout -b feature/AmazingFeature`)
3. **提交更改** (`git commit -m 'Add some AmazingFeature'`)
4. **推送到分支** (`git push origin feature/AmazingFeature`)
5. **创建Pull Request**

### 贡献类型

- 🐛 **Bug修复** - 提交Issue并附上修复PR
- ✨ **新特性** - 讨论后提交实现
- 📚 **文档改进** - 修复错别字、补充示例
- 🔧 **连接器开发** - 贡献新的数据源连接器
- 🧪 **测试用例** - 增加测试覆盖率

详细贡献指南：[CONTRIBUTING.md](CONTRIBUTING.md)

---

## 📋 路线图

### v1.0 (当前版本)

- ✅ MySQL/PostgreSQL/MongoDB连接器
- ✅ MySQL CDC支持（Debezium）
- ✅ 基础Web UI
- ✅ Cluster Sharding
- ✅ Event Sourcing

### v1.1 (Q2 2024)

- 🚧 Oracle/SQL Server连接器
- 🚧 PostgreSQL CDC支持
- 🚧 RabbitMQ/Pulsar连接器
- 🚧 数据血缘可视化
- 🚧 Schema注册中心

### v1.2 (Q3 2024)

- 📋 Flink SQL转换引擎
- 📋 数据质量检查
- 📋 告警规则引擎
- 📋 任务模板市场

### v2.0 (Q4 2024)

- 📋 Machine Learning集成
- 📋 自动Schema映射
- 📋 智能性能调优
- 📋 云原生部署（Operator）

---

## 🌟 社区

### 获取帮助

- 📖 [文档](https://docs.pekkosync.io)
- 💬 [Discord频道](https://discord.gg/pekkosync)
- 🐦 [Twitter](https://twitter.com/pekkosync)
- 📧 Email: support@pekkosync.io

### 贡献者

感谢所有贡献者！

<a href="https://github.com/xuxiaotuan/pekko-sync/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=xuxiaotuan/pekko-sync" />
</a>

---

## 📄 许可证

本项目采用 [Apache License 2.0](LICENSE) 许可证。

---

## 🙏 致谢

- [Apache Pekko](https://pekko.apache.org) - 优秀的Actor框架
- [Debezium](https://debezium.io) - CDC引擎
- [SeaTunnel](https://seatunnel.apache.org) - 提供灵感
- [DataX](https://github.com/alibaba/DataX) - 先行者

---

<div align="center">

**[⬆ 返回顶部](#pekkosync---下一代分布式数据同步引擎)**

Made with ❤️ by [Xuxiaotuan](https://github.com/xuxiaotuan)

Star ⭐ 这个项目如果它对你有帮助！

</div>
