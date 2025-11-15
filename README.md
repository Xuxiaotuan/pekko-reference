# Pekko DataFusion Arrow 分布式大数据处理架构

基于 **Pekko + DataFusion + Apache Arrow** 的高性能、低延迟分布式大数据处理平台，通过 Actor 模型实现任务并行调度，利用 Arrow 零拷贝内存传输优化数据流处理，使用 DataFusion 执行 SQL 查询和表达式计算。

## 🎯 项目状态

**开发进度**: 🟢 Day 2 任务调度完成 + 数据执行引擎  
**当前版本**: v0.3  
**JDK版本**: Java 11  
**构建状态**: ✅ 编译通过，测试正常

### ✅ Week 1 核心成就
- 🏗️ **完整集群架构**: 基于Pekko Cluster的分布式系统基础
- 🚀 **PekkoGc组件**: 全局唯一的GC清理机制，支持Leader选举
- 🌐 **多节点支持**: 验证通过的双节点集群环境
- 🔧 **JDK 11升级**: 完整的兼容性解决和优化配置
- 📊 **监控体系**: HTTP API、健康检查、系统指标
- 🛡️ **高可用设计**: 故障转移、优雅关闭、状态管理

### 🆕 Day 2 重大更新 (2024-11-15)

#### 📦 任务调度系统
- ✅ **插件式任务执行器**: 模块化设计，易于扩展新任务类型
- ✅ **Actor隔离执行**: 每个任务独立Actor，自动并发处理
- ✅ **状态持久化**: TaskScheduler持久化任务状态，即使Actor停止也可查询
- ✅ **HTTP API**: 完整的REST接口（提交任务、查询状态、统计信息）
- ✅ **内置执行器**: SQL、数据处理、文件传输
- ✅ **模块化代码结构**: `executors/`目录，每个执行器独立文件

#### 🌊 Pekko Stream数据执行引擎 (NEW!)
- ✅ **类Spark/Flink API**: 熟悉的Dataset抽象和操作
- ✅ **流式处理**: 支持无限数据流和批处理
- ✅ **自动背压**: 处理快速生产者和慢速消费者
- ✅ **多种数据源**: 文件、内存、流式、数据库（扩展中）
- ✅ **丰富的操作**: map、filter、groupBy、join、distinct、sort等
- ✅ **多种数据汇**: 文件输出、控制台、内存收集、聚合
- ✅ **可组合**: 通过Flow组合构建复杂数据管道

## 🚀 快速开始

### 环境要求
- **JDK**: 11+ (推荐 Amazon Corretto 11)
- **Scala**: 2.13.12
- **SBT**: 1.9.8+
- **内存**: 最少 2GB，推荐 4GB+

### 单节点快速启动
```bash
# 1. 克隆项目
git clone <repository-url>
cd pekko-reference

# 2. 设置JDK 11环境
export JAVA_HOME=/path/to/jdk11
export PATH=$JAVA_HOME/bin:$PATH

# 3. 编译项目
sbt "project pekko-server" compile

# 4. 启动单节点集群
sbt "project pekko-server" "runMain cn.xuyinyin.magic.PekkoServer"
```

### 多节点集群启动
```bash
# 节点1 - Seed节点 (端口2551)
sbt "project pekko-server" "runMain cn.xuyinyin.magic.PekkoServer 2551"

# 节点2 - 工作节点 (端口2552) 
sbt "project pekko-server" "runMain cn.xuyinyin.magic.PekkoServer 2552"

# 节点3 - 工作节点 (端口2553)
sbt "project pekko-server" "runMain cn.xuyinyin.magic.PekkoServer 2553"
```

### 🌐 HTTP端点验证

启动成功后，可以通过以下端点验证服务：

| 端点 | 方法 | 功能 | 
|------|------|------|
| **/** | GET | API文档 |
| **/api/v1/status** | GET | API状态检查 |
| **/api/v1/tasks** | POST | 提交任务 ⭐ NEW |
| **/api/v1/tasks/statistics** | GET | 任务统计 ⭐ NEW |
| **/api/v1/tasks/{taskId}** | GET | 查询任务状态 ⭐ NEW |
| **/health** | GET | 整体健康状态 |
| **/health/live** | GET | 存活探针 |
| **/health/ready** | GET | 就绪探针 |
| **/monitoring/cluster/status** | GET | 集群状态 |
| **/monitoring/metrics** | GET | 系统指标 |

```bash
# 快速健康检查
curl http://localhost:8080/health
# 输出: Overall Health: OK

# 检查集群状态  
curl http://localhost:8080/monitoring/cluster/status
# 输出: 集群成员、Leader、角色等信息

# API状态检查
curl http://localhost:8080/api/v1/status
# 输出: API Status with Task Scheduling features
```

### 🎯 任务调度系统快速使用

#### 1. 提交SQL任务
```bash
curl -X POST http://localhost:8080/api/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{
    "taskType": "sql",
    "sql": "SELECT * FROM users WHERE age > 18",
    "database": "production",
    "priority": 5
  }'

# 响应: {"taskId": "xxx", "message": "Task submitted successfully"}
```

#### 2. 提交数据处理任务
```bash
curl -X POST http://localhost:8080/api/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{
    "taskType": "data_process",
    "inputPath": "/data/input.csv",
    "outputPath": "/data/output.csv",
    "operation": "filter",
    "priority": 7
  }'
```

#### 3. 查询任务状态
```bash
curl http://localhost:8080/api/v1/tasks/{taskId}

# 响应示例:
# {
#   "taskId": "xxx",
#   "status": "completed",
#   "progress": 100.0,
#   "message": "Task completed successfully"
# }
```

#### 4. 查询任务统计
```bash
curl http://localhost:8080/api/v1/tasks/statistics

# 响应示例:
# {
#   "totalTasks": 10,
#   "runningTasks": 2,
#   "completedTasks": 7,
#   "failedTasks": 1,
#   "successRate": 70.0
# }
```

### 🌊 数据执行引擎快速使用

#### 编程API示例
```scala
import cn.xuyinyin.magic.stream.engine.{DataExecutionEngine, DataSink}

// 1. 从集合创建数据集
val numbers = DataExecutionEngine.fromCollection(1 to 100)

// 2. 数据转换管道
val result = numbers
  .filter(_ % 2 == 0)      // 过滤偶数
  .map(_ * 2)              // 乘以2
  .limit(10)               // 取前10个
  .collect()               // 执行并收集结果

// 3. 文件操作
val dataset = DataExecutionEngine.readCSV("data.csv")
val processed = dataset
  .filter(row => row.nonEmpty)
  .map(row => row.map(_.toUpperCase))
processed.writeTo(DataSink.File.csv("output.csv"))

// 4. 分组聚合
val sales = DataExecutionEngine.fromCollection(salesData)
val totals = sales
  .groupBy(_.category)
  .sum(_.amount)
```

查看完整示例：
```bash
# 运行数据引擎示例
sbt "project pekko-server" "runMain cn.xuyinyin.magic.stream.engine.EXAMPLE_DataExecutionEngineUsage"
```

### 📊 PekkoGc组件验证

PekkoGc是Week 1的核心组件，提供全局唯一的GC清理功能：

**Leader节点日志**：
```
[PekkoGuardian] - Leadership check - Current leader: Some(pekko://pekko-cluster-system@127.0.0.1:2551), Is leader: true
[PekkoGuardian] - This node is the leader, starting PekkoGc
[PekkoGc] - PekkoGc starting as cluster singleton
[PekkoGc] - PekkoGc updating GC limit from 0 to 10
[PekkoGc] - PekkoGc GC triggered. gcCount=0, gcLimit=10
[PekkoGc] - PekkoGc remove cache. Today Gc times 1, gcLimit: 10
```

**Follower节点日志**：
```
[PekkoGuardian] - Leadership check - Current leader: Some(pekko://pekko-cluster-system@127.0.0.1:2551), Is leader: false
[PekkoGuardian] - PekkoGc not running on this follower node
```

### 🔧 开发环境配置

#### application.conf 核心配置
```hocon
pekko {
  pekko-sys = "pekko-cluster-system"
  project-version = "0.2"
  
  # GC限制配置
  global-task-limit = 10
  
  actor.provider = "cluster"
  cluster {
    seed-nodes = ["pekko://pekko-cluster-system@127.0.0.1:2551"]
    roles = ["coordinator", "worker", "api-gateway"]
    min-nr-of-members = 1
  }
}
```

#### JVM GC日志配置
```bash
# 本地开发GC日志
-Xlog:gc*:file=logs/gc.log:time,uptime,level,tags
-Xlog:gc+heap=trace:file=logs/gc-heap.log:time,uptime
-Xlog:gc+ref=debug:file=logs/gc-ref.log:time,uptime
-XX:+UseGCLogFileRotation
-XX:NumberOfGCLogFiles=5
-XX:GCLogFileSize=10M
```

### 🐳 Docker运行 (可选)

```bash
# 构建Docker镜像
sbt "project pekko-server" docker:publishLocal

# 运行单节点容器
docker run -p 8080:8080 -p 2551:2551 pekko-reference:0.1

# 运行多节点集群
docker-compose up -d
```

## 🏗️ 架构概览

### 系统组件架构

| 层级 | 组件 | 功能 |
|------|------|------|
| **1️⃣ 调度层** | Pekko Cluster | 分布式任务调度、Actor 间通信、容错与事件回放 |
| **2️⃣ 计算层** | DataFusion Worker | 执行 SQL / 表达式计算（基于 Arrow 内核） |
| **3️⃣ 通信层** | Arrow Flight / gRPC | 统一数据传输协议，传输 Arrow RecordBatch 数据 |
| **4️⃣ 状态层** | EventSourcing + Arrow Snapshot | 任务容错、任务恢复与血缘关系 |
| **5️⃣ 元数据层** | GraphQL / Catalog / GraphDB | 存储血缘信息、Schema、表依赖关系 |
| **6️⃣ 展示层** | 前端 UI / 控制台 | SQL 控制台、DAG 编辑器、血缘图可视化、任务监控 |

### 核心架构图

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                         🌐 Pekko DataFusion Arrow 分布式架构                       │
│                                                                                    │
│  ┌──────────────────────┐    ┌──────────────────────┐    ┌──────────────────────┐  │
│  │   🎛️ 控制平面 (UI)    │    │   🧠 元数据层        │    │   💾 存储层          │  │
│  │──────────────────────│    │──────────────────────│    │──────────────────────│  │
│  │ • SQL Console        │◄──►│ • GraphQL API        │◄──►│ • Event Store        │  │
│  │ • DAG Builder        │    │ • Schema Catalog     │    │ • Arrow Snapshots    │  │
│  │ • 血缘可视化         │    │ • 血缘图存储         │    │ • Parquet Files      │  │
│  │ • 监控面板           │    │ • 配置中心           │    │ • Log Files          │  │
│  │ • REST/gRPC          │    │ • 元数据索引         │    │ • Checkpoint Store   │  │
│  └──────────────────────┘    └──────────────────────┘    └──────────────────────┘  │
│           │  HTTP/WebSocket          │  GraphQL/REST         │  EventSourcing      │
│           └─────────────┬─────────────┴─────────────┬─────────────┘              │
│                         │                           │                            │
│                         ▼                           ▼                            │
│  ┌────────────────────────────────────────────────────────────────────────────┐  │
│  │                           🚀 Pekko 集群调度层                              │  │
│  │────────────────────────────────────────────────────────────────────────────│  │
│  │  ┌──────────────────────────────────────────────────────────────────┐    │  │
│  │  │                 👑 Coordinator Driver Actor                     │    │  │
│  │  │------------------------------------------------------------------│    │  │
│  │  │ 📥 接收请求: SQL/DAG/Graph (HTTP/gRPC/GraphQL)                   │    │  │
│  │  │ 🧠 解析优化: Calcite Parser → LogicalPlan → PhysicalPlan         │    │  │
│  │  │ 📋 任务分解: 按表/算子/分区分解为子任务                          │    │  │
│  │  │ 🚚 智能分发: 基于数据本地性、负载均衡、网络拓扑                 │    │  │
│  │  │ 🔄 状态管理: EventSourcing + Arrow Snapshot 恢复                │    │  │
│  │  │ 📊 血缘追踪: 记录任务依赖、执行路径、数据变换                   │    │  │
│  │  │ ⚡ 容错处理: 故障检测、任务重新调度、节点隔离                   │    │  │
│  │  │ 📈 监控告警: 实时指标、性能分析、资源监控                       │    │  │
│  │  └──────────────────────────────────────────────────────────────────┘    │  │
│  │                                      │ Actor Messages                    │  │
│  │         ┌────────────────────────────┼────────────────────────────┐       │  │
│  │         │                            │                            │       │  │
│  │         ▼ Cluster Sharding           ▼ Cluster Sharding           ▼       │  │
│  │  ┌─────────────┐              ┌─────────────┐              ┌─────────────┐ │  │
│  │  │ Actor Node  │              │ Actor Node  │              │ Actor Node  │ │  │
│  │  │   Worker A  │              │   Worker B  │              │   Worker C  │ │  │
│  │  │─────────────│              │─────────────│              │─────────────│ │  │
│  │  │ 🎯 任务执行  │              │ 🎯 任务执行  │              │ 🎯 任务执行  │ │  │
│  │  │ 📦 状态管理  │              │ 📦 状态管理  │              │ 📦 状态管理  │ │  │
│  │  │ 🔄 故障恢复  │              │ 🔄 故障恢复  │              │ 🔄 故障恢复  │ │  │
│  │  │ 📡 通信协调  │              │ 📡 通信协调  │              │ 📡 通信协调  │ │  │
│  │  │ 📊 监控上报  │              │ 📊 监控上报  │              │ 📊 监控上报  │ │  │
│  │  └─────────────┘              └─────────────┘              └─────────────┘ │  │
│  └────────────────────────────────────────────────────────────────────────────┘  │
│                    │ Arrow Flight/gRPC          │ Arrow Flight/gRPC          │   │
│                    ▼                            ▼                            ▼   │
│  ┌────────────────────────────────────────────────────────────────────────────┐  │
│  │                        ⚙️ DataFusion 计算层 (Rust)                       │  │
│  │────────────────────────────────────────────────────────────────────────────│  │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐  ┌───────┐ │  │
│  │  │ DataFusion      │  │ DataFusion      │  │ DataFusion      │  │ Arrow │ │  │
│  │  │ Worker 1        │  │ Worker 2        │  │ Worker N        │  │ Flight│ │  │
│  │  │────────────────-│  │────────────────-│  │────────────────-│  │ Server │ │  │
│  │  │ 🔍 SQL 执行引擎 │  │ 🔍 SQL 执行引擎 │  │ 🔍 SQL 执行引擎 │  │───────│ │  │
│  │  │ 📊 表达式计算   │  │ 📊 表达式计算   │  │ 📊 表达式计算   │  │ 🚀 gRPC│ │  │
│  │  │ 📂 Parquet读取  │  │ 📂 Parquet读取  │  │ 📂 Parquet读取  │  │ 🔄 流式 │ │  │
│  │  │ 🎯 向量化执行   │  │ 🎯 向量化执行   │  │ 🎯 向量化执行   │  │ 📦 传输│ │  │
│  │  │ 💾 内存管理     │  │ 💾 内存管理     │  │ 💾 内存管理     │  │ ⚡ 零拷│ │  │
│  │  │ 🗜️ 压缩优化    │  │ 🗜️ 压缩优化    │  │ 🗜️ 压缩优化    │  │ 📡 网络│ │  │
│  │  └─────────────────┘  └─────────────────┘  └─────────────────┘  └───────┘ │  │
│  └────────────────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────────────────┘
                              │ Arrow RecordBatch
                              ▼ 零拷贝传输
                    ┌─────────────────────┐
                    │   📊 数据存储层      │
                    │─────────────────────│
                    │ • Parquet 数据湖    │
                    │ • Arrow 内存文件    │
                    │ • 分布式文件系统    │
                    │ • 对象存储 (S3/GCS) │
                    │ • Kafka Event Log  │
                    └─────────────────────┘
```

### 🔧 技术栈与协议说明

| 层级 | 核心技术 | 通信协议 | 数据格式 | 关键特性 |
|------|----------|----------|----------|----------|
| **控制平面** | React/Vue.js, WebSocket | HTTP/HTTPS, WebSocket | JSON | 实时交互, SQL编辑器 |
| **元数据层** | GraphQL, Neo4j, Elasticsearch | GraphQL, REST API | JSON, Graph | 血缘追踪, 全文搜索 |
| **存储层** | Kafka, S3, HDFS | gRPC, S3 API | Arrow, Parquet | EventSourcing, 数据湖 |
| **调度层** | Apache Pekko, Akka Cluster | Actor Messages, Cluster Sharding | Serialized Objects | 分布式协调, 容错恢复 |
| **计算层** | DataFusion, Arrow Flight | Arrow Flight, gRPC | Arrow RecordBatch | 零拷贝, 向量化执行 |

### 📊 数据流与交互模式

```
用户请求 → [UI层] → HTTP/WebSocket → [Coordinator] → Actor Messages → [Worker] 
    ↓                                                        ↓
[GraphQL] ← 血缘查询 ← [元数据层] ← EventSourcing ← [存储层] ← Arrow Flight ← [DataFusion]
    ↓                                                        ↓
[监控面板] ← 指标聚合 ← [Metrics Collector] ← Actor Events ← [Actor System] ← 执行结果
```

### 🔍 关键节点详细架构图

#### 1. 🧠 Coordinator Driver Actor 详细架构
```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    👑 Coordinator Driver Actor                           │
│─────────────────────────────────────────────────────────────────────────────│
│                                                                             │
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐        │
│  │   📥 请求接收器  │    │   🧠 查询解析器  │    │   📋 计划生成器  │        │
│  │─────────────────│    │─────────────────│    │─────────────────│        │
│  │ • REST API      │───►│ • Calcite Parser│───►│ • LogicalPlan   │        │
│  │ • GraphQL API   │    │ • SQL Validator │    │ • PhysicalPlan  │        │
│  │ • gRPC Gateway  │    │ • Syntax Check  │    │ • Cost Optimizer│        │
│  │ • Message Queue │    │ • Schema Lookup │    │ • Partition Plan│        │
│  └─────────────────┘    └─────────────────┘    └─────────────────┘        │
│           │                       │                       │               │
│           └───────────────────────┼───────────────────────┘               │
│                                   ▼                                       │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        🚀 任务调度引擎                              │   │
│  │─────────────────────────────────────────────────────────────────────│   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐ │   │
│  │  │ 🎯 任务分解  │  │ ⚖️ 负载均衡  │  │ 📊 依赖管理  │  │ 🔄 故障处理 │ │   │
│  │  │─────────────│  │─────────────│  │─────────────│  │─────────────│ │   │
│  │  │ • 表级分解  │  │ • 节点选择  │  │ • DAG构建   │  │ • 健康检查  │ │   │
│  │  │ • 算子分解  │  │ • 数据本地性│  │ • 拓扑排序  │  │ • 重试机制  │ │   │
│  │  │ • 并行度计算│  │ • 资源评估  │  │ • 循环检测  │  │ • 任务迁移  │ │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘ │   │
│  └─────────────────────────────────────────────────────────────────────────┘   │
│                                   │                                           │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │                        📊 状态与血缘管理                                │   │
│  │─────────────────────────────────────────────────────────────────────────│   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐ │   │
│  │  │ 📝 EventLog │  │ 📸 Snapshot │  │ 🧬 Lineage  │  │ 📈 Metrics  │ │   │
│  │  │─────────────│  │─────────────│  │─────────────│  │─────────────│ │   │
│  │  │ • 命令存储  │  │ • 状态快照  │  │ • 数据血缘  │  │ • 性能指标  │ │   │
│  │  │ • 事件溯源  │  │ • 增量备份  │  │ • 依赖追踪  │  │ • 监控告警  │ │   │
│  │  │ • 回放机制  │  │ • 恢复点    │  │ • 影响分析  │  │ • 日志聚合  │ │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘ │   │
│  └─────────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### 2. ⚙️ DataFusion Worker 详细架构
```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    ⚙️ DataFusion Worker (Rust)                             │
│─────────────────────────────────────────────────────────────────────────────│
│                                                                             │
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐        │
│  │   🚀 Flight服务  │    │   🔍 查询引擎   │    │   📊 执行引擎    │        │
│  │─────────────────│    │─────────────────│    │─────────────────│        │
│  │ • gRPC Server   │───►│ • SQL Parser    │───►│ • PhysicalExec  │        │
│  │ • Arrow Flight  │    │ • Query Planner │    │ • ExpressionEval│        │
│  │ • Stream Handler│    │ • Optimizer     │    │ • AggregateExec │        │
│  │ • Auth/Security │    │ • Analyzer      │    │ • JoinExec      │        │
│  └─────────────────┘    └─────────────────┘    └─────────────────┘        │
│           │                       │                       │               │
│           └───────────────────────┼───────────────────────┘               │
│                                   ▼                                       │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        📦 数据管理层                                 │   │
│  │─────────────────────────────────────────────────────────────────────│   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐ │   │
│  │  │ 📂 Parquet  │  │ 🎯 Arrow    │  │ 💾 Memory   │  │ 🗂️  Cache   │ │   │
│  │  │ Reader      │  │ Arrays      │  │ Pool        │  │ Manager     │ │   │
│  │  │─────────────│  │─────────────│  │─────────────│  │─────────────│ │   │
│  │  │ • 列式读取  │  │ • 零拷贝    │  │ • Buffer池  │  │ • LRU缓存   │ │   │
│  │  │ • 谓词下推  │  │ • 向量化    │  │ • 内存映射  │  │ • 热点数据  │ │   │
│  │  │ • 分区裁剪  │  │ • SIMD优化  │  │ • 垃圾回收  │  │ • 预取策略  │ │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘ │   │
│  └─────────────────────────────────────────────────────────────────────────┘   │
│                                   │                                           │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │                        🔄 结果输出层                                     │   │
│  │─────────────────────────────────────────────────────────────────────────│   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐ │   │
│  │  │ 📤 Stream   │  │ 🗜️  Compression│ 📡 Network  │  │ 🛡️  Security│ │   │
│  │  │ Output      │  │ Engine      │  │ I/O         │  │ Manager     │ │   │
│  │  │─────────────│  │─────────────│  │─────────────│  │─────────────│ │   │
│  │  │ • 流式返回  │  │ • Snappy/Zstd│  │ • 异步I/O   │  │ • TLS加密   │ │   │
│  │  │ • 批量优化  │  │ • 列式压缩  │  │ • 连接池    │  │ • 认证授权  │ │   │
│  │  │ • 背压控制  │  │ • 内存节省  │  │ • 心跳检测  │  │ • 审计日志  │ │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘ │   │
│  └─────────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### 3. 🧬 元数据与血缘管理架构
```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    🧬 元数据与血缘管理系统                                 │
│─────────────────────────────────────────────────────────────────────────────│
│                                                                             │
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐        │
│  │   🗂️ Schema     │    │   🔍 GraphQL    │    │   🧬 Lineage    │        │
│  │   Catalog       │    │   API           │    │   Engine        │        │
│  │─────────────────│    │─────────────────│    │─────────────────│        │
│  │ • Table Registry│───►│ • Query Layer   │───►│ • Dependency    │        │
│  │ • Version Mgmt  │    │ • Schema Builder│    │   Analysis      │        │
│  │ • Column Stats  │    │ • Resolvers     │    │ • Graph Builder │        │
│  │ • Index Info    │    │ • Mutations     │    │ • Impact Analysis│        │
│  └─────────────────┘    └─────────────────┘    └─────────────────┘        │
│           │                       │                       │               │
│           └───────────────────────┼───────────────────────┘               │
│                                   ▼                                       │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        💾 存储与索引层                                │   │
│  │─────────────────────────────────────────────────────────────────────│   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐ │   │
│  │  │ 📊 Graph DB │  │ 🗃️  Document │  │ 🔍 Search   │  │ 📈 Time-Series│ │   │
│  │  │ (Neo4j)     │  │ Store       │  │ Engine      │  │ Database    │ │   │
│  │  │─────────────│  │ (MongoDB)   │  │ (Elastic)   │  │ (InfluxDB)  │ │   │
│  │  │ • 血缘图存储│  │ • 配置文档  │  │ • 全文搜索  │  │ • 指标存储  │ │   │
│  │  │ • 图遍历优化│  │ • JSON存储  │  │ • 元数据索引│  │ • 时序数据  │ │   │
│  │  │ • 关系查询  │  │ • 灵活Schema│  │ • 聚合分析  │  │ • 监控数据  │ │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘ │   │
│  └─────────────────────────────────────────────────────────────────────────┘   │
│                                   │                                           │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │                        🎨 可视化与API层                                  │   │
│  │─────────────────────────────────────────────────────────────────────────│   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐ │   │
│  │  │ 🎨 Web UI   │  │ 📊 REST API │  │ 🔗 GraphQL  │  │ 📡 WebSocket│ │   │
│  │  │ Dashboard   │  │ Gateway     │  │ Gateway     │  │ Streaming   │ │   │
│  │  │─────────────│  │─────────────│  │─────────────│  │─────────────│ │   │
│  │  │ • 血缘图可视化│  │ • CRUD操作  │  │ • 查询接口  │  │ • 实时更新  │ │   │
│  │  │ • 依赖分析   │  │ • 批量导入  │  │ • 订阅机制  │  │ • 事件推送  │ │   │
│  │  │ • 影响评估   │  │ • 权限控制  │  │ • 缓存策略  │  │ • 状态同步  │ │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘ │   │
│  └─────────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### 4. 🛡️ 容错与状态管理架构
```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    🛡️ 容错与状态管理系统                                   │
│─────────────────────────────────────────────────────────────────────────────│
│                                                                             │
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐        │
│  │   📝 Event      │    │   📸 Snapshot   │    │   🔍 Health     │        │
│  │   Sourcing      │    │   Manager       │    │   Monitor       │        │
│  │─────────────────│    │─────────────────│    │─────────────────│        │
│  │ • Command Store │───►│ • State Capture │───►│ • Node Detection│        │
│  │ • Event Log     │    │ • Incremental  │    │ • Heartbeat     │        │
│  │ • Time Travel   │    │ • Compression  │    │ • Failure Alert │        │
│  │ • Replay Engine │    │ • Restore Point │    │ • Metrics Collect│        │
│  └─────────────────┘    └─────────────────┘    └─────────────────┘        │
│           │                       │                       │               │
│           └───────────────────────┼───────────────────────┘               │
│                                   ▼                                       │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        🔄 恢复与迁移层                                 │   │
│  │─────────────────────────────────────────────────────────────────────│   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐ │   │
│  │  │ 🔄 Recovery │  │ 🚛 Migration │  │ ⚖️ Consistency│  │ 🔒 Lock     │ │   │
│  │  │ Manager     │  │ Manager     │  │ Manager     │  │ Manager     │ │   │
│  │  │─────────────│  │─────────────│  │─────────────│  │─────────────│ │   │
│  │  │ • 自动恢复  │  │ • 数据迁移  │  │ • 一致性检查│  │ • 分布式锁  │ │   │
│  │  │ • 状态重建  │  │ • 负载均衡  │  │ • 冲突解决  │  │ • 死锁检测  │ │   │
│  │  │ • 断点续传  │  │ • 节点替换  │  │ • 事务保证  │  │ • 乐观并发  │ │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘ │   │
│  └─────────────────────────────────────────────────────────────────────────┘   │
│                                   │                                           │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │                        📊 监控与告警层                                    │   │
│  │─────────────────────────────────────────────────────────────────────────│   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐ │   │
│  │  │ 📈 Metrics  │  │ 🚨 Alerting │  │ 📊 Dashboard│  │ 📝 Audit    │ │   │
│  │  │ Collector   │  │ Engine      │  │ UI          │  │ Log         │ │   │
│  │  │─────────────│  │─────────────│  │─────────────│  │─────────────│ │   │
│  │  │ • 性能指标  │  │ • 阈值告警  │  │ • 实时监控  │  │ • 操作日志  │ │   │
│  │  │ • 资源使用  │  │ • 故障通知  │  │ • 历史趋势  │  │ • 变更记录  │ │   │
│  │  │ • 自定义指标│  │ • 升级告警  │  │ • 告警管理  │  │ • 合规审计  │ │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘ │   │
│  └─────────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### 5. 📊 数据流处理架构
```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    📊 数据流处理架构                                        │
│─────────────────────────────────────────────────────────────────────────────│
│                                                                             │
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐        │
│  │   📥 Data       │    │   🔄 Stream     │    │   📤 Sink       │        │
│  │   Ingestion     │    │   Processing    │    │   Distribution  │        │
│  │─────────────────│    │─────────────────│    │─────────────────│        │
│  │ • Kafka Connect │───►│ • Pekko Streams │───►│ • Flight Export │        │
│  │ • File Watcher  │    │ • Backpressure  │    │ • HTTP/API      │        │
│  │ • CDC Capture   │    │ • Windowing     │    │ • Database Write│        │
│  │ • Batch Import  │    │ • Stateful Ops  │    │ • Object Storage│        │
│  └─────────────────┘    └─────────────────┘    └─────────────────┘        │
│           │                       │                       │               │
│           └───────────────────────┼───────────────────────┘               │
│                                   ▼                                       │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        🎯 数据转换层                                   │   │
│  │─────────────────────────────────────────────────────────────────────│   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐ │   │
│  │  │ 🔍 Filter   │  │ 🗺️  Transform│  │ 📊 Aggregate │  │ 🔗 Join     │ │   │
│  │  │ Engine      │  │ Engine      │  │ Engine      │  │ Engine      │ │   │
│  │  │─────────────│  │─────────────│  │─────────────│  │─────────────│ │   │
│  │  │ • 谓词下推  │  │ • 字段映射  │  │ • Group By   │  │ • Inner Join│ │   │
│  │  │ • 条件过滤  │  │ • 类型转换  │  │ • Window Fun │  │ • Outer Join│ │   │
│  │  │ • 数据清洗  │  │ • 格式标准化│  │ • Distinct   │  │ • Stream Join│ │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘ │   │
│  └─────────────────────────────────────────────────────────────────────────┘   │
│                                   │                                           │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │                        💾 存储优化层                                      │   │
│  │─────────────────────────────────────────────────────────────────────────│   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐ │   │
│  │  │ 📂 Partition│  │ 🗜️  Compression│ 🎯 Indexing │  │ 🔄 Caching  │ │   │
│  │  │ Strategy    │  │ Engine      │  │ Engine      │  │ Layer       │ │   │
│  │  │─────────────│  │─────────────│  │─────────────│  │─────────────│ │   │
│  │  │ • 时间分区  │  │ • 列式压缩  │  │ • B-Tree索引 │  │ • 多级缓存  │ │   │
│  │  │ • 哈希分区  │  │ • 字典编码  │  │ • 位图索引  │  │ • 预计算    │ │   │
│  │  │ • 范围分区  │  │ • 增量压缩  │  │ • 全文索引  │  │ • 结果缓存  │ │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘ │   │
│  └─────────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

## ✨ 创新亮点

| 特性 | 说明 |
|------|------|
| 🔹 **跨语言支持** | Pekko (Scala) + DataFusion (Rust) + Arrow 支持跨语言互操作，Python、JavaScript 客户端也可使用 |
| 🔹 **零拷贝数据流** | 通过 Arrow RecordBatch 和 Arrow Flight，数据传输过程避免了传统的数据拷贝和序列化，提高了性能 |
| 🔹 **分布式可扩展性** | Pekko 的 Actor 模型天然支持分布式计算，同时 DataFusion 支持并行执行，能够扩展到大规模数据处理 |
| 🔹 **可视化血缘追踪** | 通过 GraphQL 和血缘图，提供对计算任务的可视化追踪，帮助用户理解数据流和任务依赖 |
| 🔹 **容错恢复** | 使用 EventSourcing 和 Arrow Snapshot 确保任务在发生故障时能够恢复，提高系统的容错能力 |

## 🚀 快速开始

### 环境要求

- **JDK 8+**
- **Scala 2.13**
- **Docker & Docker Compose**
- **Rust (用于 DataFusion Worker)**

### 构建和运行

```bash
# 克隆项目
git clone <repository-url>
cd pekko-reference

# 构建项目
sbt clean compile

# Docker 方式启动集群
docker-compose up -d

# 本地开发模式
sbt "pekko-server/run"
```

### 集群节点配置

项目支持多节点集群部署，通过环境变量配置不同角色：

- **seed**: 种子节点，负责集群初始化
- **worker**: 工作节点，执行计算任务
- **coordinator**: 协调节点，负责任务调度

## 📊 执行流程示例

### SQL 查询处理流程

1. **用户提交查询**
   ```sql
   SELECT city, SUM(amount) FROM orders GROUP BY city
   ```

2. **Pekko Driver Actor 解析**
   - 解析 SQL 生成逻辑计划
   - 转换为物理执行计划
   - 分发任务到 Worker Actor

3. **Worker Actor 执行**
   - 通过 Arrow Flight 调用 DataFusion Worker
   - 传递 Arrow RecordBatch 数据
   - 异步执行计算任务

4. **DataFusion Worker 计算**
   - 读取 Parquet/Arrow 数据
   - 执行聚合、过滤等操作
   - 通过 Flight 流式返回结果

5. **结果汇总与血缘记录**
   - Pekko 聚合所有 Worker 结果
   - 记录执行状态和血缘信息
   - 支持任务恢复和监控

## 🛠️ 技术栈

### 核心依赖

- **Scala 2.13** - 主要开发语言
- **Apache Pekko** - Actor 模型分布式框架
  - Pekko-Actor / Pekko-Cluster / Pekko-Sharding
  - Pekko-HTTP / Pekko-Stream
- **Apache Calcite** - SQL 解析和优化
- **Apache Arrow** - 列式内存格式和零拷贝传输
- **DataFusion** - Rust 高性能 SQL 查询引擎

### 运行时环境

- **JDK 8+**
- **Docker** - 容器化部署
- **gRPC** - 跨语言服务通信
- **GraphQL** - 元数据查询接口

## 📋 详细开发路线图

### 🎯 总体目标与技术愿景
构建基于 **Pekko + DataFusion + Apache Arrow** 的企业级分布式大数据处理平台，实现：
- **高性能**：单节点 > 100万QPS，集群线性扩展
- **低延迟**：P99延迟 < 100ms，支持实时分析
- **高可用**：99.99%可用性，故障自动恢复
- **易扩展**：支持PB级数据，千节点集群

---

### ✅ 第一阶段：基础架构完成 (2024-06 ~ 2024-09) ✅

**🏗️ 集群与调度层 (6周)**
- ✅ **Pekko 集群基础架构**
  - ✅ 集群监听器和成员管理 (`ClusterListener`)
  - ✅ 多角色节点支持 (dispatcher/worker/http) (`NodeRole`)
  - ✅ 集群配置和种子节点管理 (`PekkoConfig`)
  - ✅ Actor 系统守护进程 (`PekkoGuardian`)
  - ✅ 集群分片和负载均衡策略

**🧠 SQL 解析层 (4周)**
- ✅ **Apache Calcite 集成**
  - ✅ MySQL 方言支持 (`CalciteMysqlSqlParser`)
  - ✅ SQL 解析和表名提取功能
  - ✅ 自定义 Schema 和 Table 实现
  - ✅ 查询验证和语法检查

**📊 数据处理框架 (5周)**
- ✅ **CDC 数据处理组件**
  - ✅ 水位线算法实现 (`WatermarkManager`)
  - ✅ 全量/增量同步处理器
  - ✅ CDC 事件模拟器 (`CdcDataSimulator`)
  - ✅ 事件时间处理和延迟处理

**🌊 流处理基础 (3周)**
- ✅ **Pekko Stream 组件**
  - ✅ 图形化流处理 (`PekkoStreamUtils`)
  - ✅ 优先级工作池实现
  - ✅ 背压控制和流量调节

**🐳 基础设施 (2周)**
- ✅ **容器化与构建**
  - ✅ Docker 多节点集群部署
  - ✅ SBT 多模块构建配置
  - ✅ 日志和序列化配置

---

### 🚧 第二阶段：DataFusion 集成 (2024-10 ~ 2024-12) 🚧

**⚙️ Rust DataFusion Worker (8周)**
- 🔄 **DataFusion 核心集成** (3周)
  - 🔄 创建 Rust 项目结构 (`datafusion-workers/`)
  - 🔄 DataFusion 上下文和会话管理
  - 🔄 基础 SQL 执行引擎
  - 🔄 Parquet 文件读取支持
  - 📋 **技术方案**：
    ```rust
    // 核心架构设计
    pub struct DataFusionWorker {
        context: SessionContext,
        flight_service: FlightServiceServer,
        memory_pool: Arc<dyn MemoryPool>,
    }
    
    // SQL 执行接口
    impl DataFusionWorker {
        pub async fn execute_sql(&self, sql: &str) -> Result<RecordBatch> {
            let df = self.context.sql(sql).await?;
            df.collect().await
        }
    }
    ```

**🚀 Arrow Flight 通信 (6周)**
- 🔄 **跨语言 RPC 通信** (4周)
  - 🔄 Arrow Flight 服务端实现 (Rust)
  - 🔄 Arrow Flight 客户端实现 (Scala)
  - 🔄 gRPC 服务定义和代码生成
  - 🔄 RecordBatch 零拷贝传输
  - 📋 **技术方案**：
    ```protobuf
    // Flight 服务定义
    service FlightService {
      rpc GetFlightInfo(FlightDescriptor) returns (FlightInfo);
      rpc DoGet(Ticket) returns (stream FlightData);
      rpc DoPut(stream FlightData) returns (PutResult);
    }
    ```
- 📋 **性能优化** (2周)
  - 📋 零拷贝内存共享机制
  - 📋 批量传输优化
  - 📋 压缩算法集成 (Snappy/Zstd)

**📦 数据序列化 (4周)**
- 📋 **Arrow 数据格式** (3周)
  - 📋 Arrow Schema 管理
  - 📋 RecordBatch 创建和操作
  - 📋 内存映射和零拷贝优化
- 📋 **序列化优化** (1周)
  - 📋 自定义序列化器
  - 📋 版本兼容性处理

**🎯 里程碑交付物**：
- ✅ Rust DataFusion Worker 可执行程序
- ✅ Arrow Flight 跨语言通信原型
- ✅ 基础 SQL 查询执行测试
- ✅ 性能基准测试报告

---

### 🎯 第三阶段：分布式计算引擎 (2025-01 ~ 2025-03)

**🧮 物理执行计划 (6周)**
- 📋 **查询优化器** (4周)
  - 📋 逻辑计划到物理计划转换
  - 📋 基于成本的查询优化 (CBO)
  - 📋 分区策略和数据本地化
  - 📋 谓词下推和投影下推
  - 📋 **技术方案**：
    ```scala
    // 查询优化器接口
    trait QueryOptimizer {
      def optimize(logicalPlan: LogicalPlan): PhysicalPlan
      def estimateCost(plan: PhysicalPlan): Cost
    }
    
    // 物理执行计划
    case class DistributedExecutionPlan(
      stages: List[ExecutionStage],
      dependencies: Map[StageId, List[StageId]],
      resourceRequirements: ResourceRequirements
    )
    ```

**📋 任务调度系统 (5周)**
- 📋 **分布式任务管理** (4周)
  - 📋 Coordinator Actor 任务分发
  - 📋 Worker Actor 任务执行
  - 📋 任务依赖关系管理 (DAG调度)
  - 📋 负载均衡和故障转移
  - 📋 **技术方案**：
    ```scala
    // 任务调度器
    class TaskScheduler(
      cluster: Cluster,
      resourceManager: ResourceManager
    ) {
      def scheduleTasks(plan: ExecutionPlan): Future[ExecutionResult]
      def handleTaskFailure(taskId: TaskId): Future[RescheduleResult]
    }
    ```
- 📋 **调度策略优化** (1周)
  - 📋 公平调度算法
  - 📋 优先级调度机制

**💾 内存管理 (4周)**
- 📋 **Arrow 内存优化** (3周)
  - 📋 Arrow Buffer 池化管理
  - 📋 垃圾回收优化
  - 📋 内存泄漏检测
  - 📋 **技术方案**：
    ```scala
    // 内存池管理
    class ArrowMemoryPool(
      maxMemory: Long,
      allocationStrategy: AllocationStrategy
    ) extends MemoryPool {
      def allocate(size: Long): ByteBuffer
      def release(buffer: ByteBuffer): Unit
      def getUsageStats(): MemoryUsage
    }
    ```
- 📋 **内存监控** (1周)
  - 📋 实时内存使用监控
  - 📋 内存压力预警

**🎯 里程碑交付物**：
- 📋 分布式查询执行引擎
- 📋 任务调度和资源管理系统
- 📋 内存管理和监控工具
- 📋 查询性能优化报告

---

### 🛡️ 第四阶段：容错与状态管理 (2025-04 ~ 2025-06)

**📝 EventSourcing 系统 (6周)**
- 📋 **事件持久化** (4周)
  - 📋 事件日志存储 (Kafka/文件系统)
  - 📋 事件序列化和反序列化
  - 📋 事件回放机制
  - 📋 **技术方案**：
    ```scala
    // 事件溯源接口
    trait EventSourcing[State, Event] {
      def persist(event: Event): Future[Unit]
      def replay(fromVersion: Long): Future[List[Event]]
      def recover(currentState: State, events: List[Event]): State
    }
    ```
- 📋 **事件存储优化** (2周)
  - 📋 分区存储策略
  - 📋 事件压缩和归档

**📸 快照机制 (4周)**
- 📋 **Arrow Snapshot** (3周)
  - 📋 定期状态快照
  - 📋 增量快照策略
  - 📋 快照压缩和存储
  - 📋 **技术方案**：
    ```scala
    // 快照管理器
    class SnapshotManager(
      storage: SnapshotStorage,
      compression: CompressionCodec
    ) {
      def createSnapshot(state: ArrowState): Future[SnapshotId]
      def restoreSnapshot(snapshotId: SnapshotId): Future[ArrowState]
    }
    ```
- 📋 **快照优化** (1周)
  - 📋 快照恢复优化
  - 📋 并发快照处理

**🔄 故障恢复 (5周)**
- 📋 **容错策略** (3周)
  - 📋 节点故障检测 (心跳机制)
  - 📋 任务重新调度
  - 📋 数据一致性保证
  - 📋 **技术方案**：
    ```scala
    // 故障检测器
    class FailureDetector(
      heartbeatInterval: FiniteDuration,
      timeoutThreshold: FiniteDuration
    ) {
      def monitorNode(nodeId: NodeId): Future[NodeStatus]
      def handleNodeFailure(nodeId: NodeId): Future[RecoveryAction]
    }
    ```
- 📋 **恢复策略** (2周)
  - 📋 自动恢复机制
  - 📋 数据修复算法

**🎯 里程碑交付物**：
- 📋 EventSourcing 持久化系统
- 📋 Arrow Snapshot 快照机制
- 📋 故障检测和自动恢复系统
- 📋 容错测试报告

---

### 🧠 第五阶段：元数据与血缘 (2025-07 ~ 2025-09)

**📚 Schema Catalog (5周)**
- 📋 **元数据管理** (4周)
  - 📋 表 Schema 注册和查询
  - 📋 版本控制和变更追踪
  - 📋 数据源连接管理
  - 📋 **技术方案**：
    ```scala
    // Schema 注册中心
    trait SchemaCatalog {
      def registerTable(schema: TableSchema): Future[TableId]
      def getTable(tableId: TableId): Future[Option[TableSchema]]
      def listTables(namespace: String): Future[List[TableInfo]]
    }
    ```
- 📋 **元数据缓存** (1周)
  - 📋 多级缓存策略
  - 📋 缓存一致性保证

**🔍 GraphQL API (4周)**
- 📋 **元数据查询接口** (3周)
  - 📋 GraphQL Schema 设计
  - 📋 血缘关系查询
  - 📋 表依赖关系分析
  - 📋 **技术方案**：
    ```graphql
    # GraphQL Schema 定义
    type Table {
      id: ID!
      name: String!
      schema: TableSchema!
      lineage: [LineageRelation!]!
    }
    
    type Query {
      table(id: ID!): Table
      tables(filter: TableFilter): [Table!]!
      lineage(tableId: ID!): LineageGraph!
    }
    ```
- 📋 **查询优化** (1周)
  - 📋 GraphQL 查询优化
  - 📋 数据预加载策略

**🧬 血缘追踪 (6周)**
- 📋 **数据血缘系统** (4周)
  - 📋 SQL 解析血缘提取
  - 📋 血缘图构建算法
  - 📋 血缘数据存储
  - 📋 **技术方案**：
    ```scala
    // 血缘追踪器
    trait LineageTracker {
      def extractLineage(sql: String): Future[LineageGraph]
      def updateLineage(operation: DataOperation): Future[Unit]
      def analyzeImpact(tableId: TableId): Future[ImpactAnalysis]
    }
    ```
- 📋 **血缘分析** (2周)
  - 📋 影响分析算法
  - 📋 血缘可视化数据

**🎯 里程碑交付物**：
- 📋 Schema Catalog 元数据管理系统
- 📋 GraphQL API 查询接口
- 📋 血缘追踪和分析系统
- 📋 元数据管理文档

---

### 🎨 第六阶段：用户界面 (2025-10 ~ 2025-12)

**💻 SQL 控制台 (5周)**
- 📋 **Web SQL 编辑器** (4周)
  - 📋 SQL 语法高亮和自动补全
  - 📋 查询结果可视化
  - 📋 查询历史管理
  - 📋 **技术方案**：
    ```typescript
    // SQL 编辑器组件
    interface SQLEditor {
      setSQL(sql: string): void;
      getSQL(): string;
      validateSQL(): Promise<ValidationResult>;
      executeSQL(): Promise<QueryResult>;
    }
    ```
- 📋 **编辑器优化** (1周)
  - 📋 性能优化 (大文件处理)
  - 📋 用户体验优化

**🎯 DAG 可视化 (6周)**
- 📋 **数据流编辑器** (4周)
  - 📋 拖拽式 DAG 构建
  - 📋 实时执行监控
  - 📋 DAG 模板管理
  - 📋 **技术方案**：
    ```typescript
    // DAG 编辑器
    interface DAGEditor {
      addNode(node: DAGNode): void;
      addEdge(from: NodeId, to: NodeId): void;
      validateDAG(): ValidationResult;
      executeDAG(): Promise<ExecutionResult>;
    }
    ```
- 📋 **可视化优化** (2周)
  - 📋 大型 DAG 渲染优化
  - 📋 交互体验优化

**📊 监控面板 (4周)**
- 📋 **系统监控** (3周)
  - 📋 集群节点状态监控
  - 📋 任务执行进度追踪
  - 📋 性能指标可视化
  - 📋 **技术方案**：
    ```typescript
    // 监控面板
    interface MonitoringDashboard {
      getClusterStatus(): ClusterStatus;
      getTaskMetrics(): TaskMetrics;
      getPerformanceMetrics(): PerformanceMetrics;
    }
    ```
- 📋 **告警系统** (1周)
  - 📋 实时告警配置
  - 📋 告警通知机制

**🎯 里程碑交付物**：
- 📋 Web SQL 控制台
- 📋 DAG 可视化编辑器
- 📋 系统监控面板
- 📋 用户操作手册

---

### 📈 第七阶段：性能优化与扩展 (2026-01 ~ 2026-03)

**⚡ 查询优化 (5周)**
- 📋 **高级优化技术** (4周)
  - 📋 向量化执行优化
  - 📋 索引下推优化
  - 📋 谓词下推优化
  - 📋 **技术方案**：
    ```scala
    // 查询优化器增强
    class AdvancedQueryOptimizer extends QueryOptimizer {
      def vectorizePlan(plan: PhysicalPlan): VectorizedPlan
      def pushdownPredicates(plan: PhysicalPlan): OptimizedPlan
      def applyIndexes(plan: PhysicalPlan): IndexedPlan
    }
    ```
- 📋 **性能调优** (1周)
  - 📋 查询计划缓存
  - 📋 统计信息收集

**🌐 扩展性增强 (5周)**
- 📋 **水平扩展** (4周)
  - 📋 动态节点加入和退出
  - 📋 数据重平衡机制
  - 📋 弹性伸缩支持
  - 📋 **技术方案**：
    ```scala
    // 弹性伸缩管理器
    class AutoScalingManager(
      clusterManager: ClusterManager,
      metricsCollector: MetricsCollector
    ) {
      def scaleOut(targetNodes: Int): Future[ScaleResult]
      def scaleIn(targetNodes: Int): Future[ScaleResult]
      def rebalanceData(): Future[RebalanceResult]
    }
    ```
- 📋 **扩展优化** (1周)
  - 📋 跨区域部署
  - 📋 多租户支持

**🔧 性能调优 (4周)**
- 📋 **系统优化** (3周)
  - 📋 JVM 参数调优
  - 📋 网络通信优化
  - 📋 存储引擎优化
  - 📋 **技术方案**：
    ```scala
    // 性能调优器
    class PerformanceTuner {
      def optimizeJVM(settings: JVMSettings): OptimizedSettings
      def optimizeNetwork(config: NetworkConfig): OptimizedConfig
      def optimizeStorage(config: StorageConfig): OptimizedConfig
    }
    ```
- 📋 **基准测试** (1周)
  - 📋 TPC-DS/TPC-H 测试
  - 📋 性能回归测试

**🎯 里程碑交付物**：
- 📋 高性能查询引擎
- 📋 弹性伸缩系统
- 📋 性能调优工具集
- 📋 性能基准测试报告

## 📊 当前进度总览

| 阶段 | 名称 | 状态 | 完成度 | 预计完成时间 | 关键依赖 |
|------|------|------|--------|-------------|----------|
| 第一阶段 | 基础架构 | ✅ 已完成 | 100% | 2024-09 | - |
| 第二阶段 | DataFusion 集成 | 🚧 进行中 | 15% | 2024-12 | Rust生态, gRPC |
| 第三阶段 | 分布式计算引擎 | 📋 计划中 | 0% | 2025-03 | 第二阶段完成 |
| 第四阶段 | 容错与状态管理 | 📋 计划中 | 0% | 2025-06 | 第三阶段完成 |
| 第五阶段 | 元数据与血缘 | 📋 计划中 | 0% | 2025-09 | 第四阶段完成 |
| 第六阶段 | 用户界面 | 📋 计划中 | 0% | 2025-12 | 第五阶段完成 |
| 第七阶段 | 性能优化 | 📋 计划中 | 0% | 2026-03 | 第六阶段完成 |

---

## ⚠️ 风险评估与缓解策略

### 🔴 高风险项

| 风险项 | 影响程度 | 发生概率 | 缓解策略 | 负责人 |
|--------|----------|----------|----------|--------|
| **Rust生态兼容性** | 高 | 中 | 提前验证关键库版本,准备备选方案 | 架构团队 |
| **跨语言通信性能** | 高 | 中 | 建立性能基准,优化序列化策略 | 性能团队 |
| **分布式一致性** | 高 | 低 | 采用成熟的EventSourcing模式,充分测试 | 后端团队 |
| **内存管理复杂性** | 中 | 中 | 引入内存监控工具,建立内存使用规范 | 工程团队 |

### 🟡 中风险项

| 风险项 | 影响程度 | 发生概率 | 缓解策略 | 负责人 |
|--------|----------|----------|----------|--------|
| **Arrow版本升级** | 中 | 中 | 锁定稳定版本,建立升级测试流程 | 运维团队 |
| **集群规模扩展** | 中 | 低 | 渐进式扩展,建立自动化部署 | DevOps团队 |
| **第三方依赖变更** | 中 | 中 | 依赖版本管理,建立兼容性测试 | 质量团队 |

---

## 🎯 近期重点任务 (2024年11月)

### 🔥 高优先级任务 (本周完成)

**1. 搭建 Rust DataFusion Worker 项目 (3天)**
```bash
# 项目结构
datafusion-workers/
├── Cargo.toml
├── src/
│   ├── lib.rs
│   ├── worker/
│   │   ├── mod.rs
│   │   ├── executor.rs      # SQL执行引擎
│   │   └── flight_server.rs # Arrow Flight服务
│   └── memory/
│       ├── mod.rs
│       └── pool.rs          # 内存池管理
└── tests/
    └── integration_tests.rs
```
- [ ] 创建项目目录结构和基础配置
- [ ] 配置核心依赖 (datafusion, arrow-flight, tokio)
- [ ] 实现基础的 SessionContext 和 SQL 执行
- [ ] 搭建 Arrow Flight 服务端框架

**2. 实现跨语言通信原型 (4天)**
```protobuf
// 通信协议定义
syntax = "proto3";
service DataFusionService {
  rpc ExecuteQuery(QueryRequest) returns (stream QueryResult);
  rpc GetSchema(TableRequest) returns (SchemaResponse);
  rpc HealthCheck(HealthRequest) returns (HealthResponse);
}
```
- [ ] 定义 gRPC 服务接口和消息格式
- [ ] Scala 端实现 Arrow Flight 客户端
- [ ] Rust 端实现 Arrow Flight 服务端
- [ ] 建立基础的连接测试和性能基准

### 🟡 中优先级任务 (2周内完成)

**3. 优化集群配置和部署 (1周)**
- [ ] 完善 Docker Compose 多服务编排
- [ ] 添加环境变量配置支持
- [ ] 实现健康检查和服务发现
- [ ] 建立集群监控基础

**4. SQL 解析器增强 (1周)**
- [ ] 支持更多 SQL 函数和操作符
- [ ] 改进错误消息和调试信息
- [ ] 添加查询计划缓存机制
- [ ] 实现基础的性能统计

### 📋 具体实施计划

**Day 1-2: 项目初始化**
```bash
# 创建 Rust 项目
cargo new --lib datafusion-workers
cd datafusion-workers

# 配置 Cargo.toml
[dependencies]
datafusion = "32.0.0"
arrow-flight = "47.0.0"
tokio = { version = "1.0", features = ["full"] }
tonic = "0.10"
prost = "0.12"
```

**Day 3-4: 核心功能实现**
- 实现 DataFusion SessionContext
- 创建基础的 SQL 执行接口
- 搭建 Arrow Flight 服务框架

**Day 5-7: 跨语言通信**
- 定义 protobuf 服务接口
- 实现 Scala 客户端连接
- 建立 Rust 服务端处理逻辑

**Day 8-10: 集成测试**
- 端到端功能测试
- 性能基准测试
- 错误处理验证

---

## 📈 成功指标与验收标准

### 第二阶段验收标准
- ✅ **功能完整性**：支持基础 SQL 查询执行 (SELECT, WHERE, GROUP BY)
- ✅ **性能指标**：单节点 QPS > 10万，P99延迟 < 50ms
- ✅ **稳定性**：连续运行24小时无内存泄漏
- ✅ **兼容性**：Arrow 格式完全兼容，支持 Schema 演进

### 质量门禁
- **代码覆盖率** > 80%
- **文档完整性** > 90%
- **性能回归** < 5%
- **安全扫描** 无高危漏洞

---

## 📞 团队协作与沟通

### 🗓️ 定期会议
- **每日站会**：09:30-09:45 (进度同步, 风险识别)
- **周度回顾**：周五 16:00-17:00 (里程碑检查, 计划调整)
- **月度规划**：月末最后一个工作日 (下月目标, 资源分配)

### 📋 文档规范
- **技术设计文档**：每个功能模块必须有详细设计文档
- **API 文档**：使用 OpenAPI/Swagger 自动生成
- **变更日志**：记录每次重要变更和影响范围

### 🔍 代码审查
- **强制审查**：所有代码变更必须经过至少一人审查
- **审查重点**：架构设计、性能影响、安全考虑
- **工具支持**：使用 GitHub Actions 自动化检查

## 📋 开发路线图与TODO

### 🚀 核心功能开发

#### Phase 1: 基础架构搭建
- [ ] **Pekko Cluster 集群管理**
  - [ ] 实现 Coordinator Driver Actor
  - [ ] 配置 Cluster Sharding 分片策略
  - [ ] 添加节点发现和故障检测
  - [ ] 实现集群状态同步机制

- [ ] **DataFusion Worker 集成**
  - [ ] 创建 Rust DataFusion Worker 项目结构
  - [ ] 实现 Arrow Flight gRPC 服务
  - [ ] 添加 SQL 执行引擎接口
  - [ ] 实现内存管理和资源池

- [ ] **通信协议实现**
  - [ ] 定义 Actor 消息协议
  - [ ] 实现 Arrow Flight 数据传输
  - [ ] 添加序列化/反序列化支持
  - [ ] 实现背压控制机制

#### Phase 2: 数据处理核心
- [ ] **SQL 解析与优化**
  - [ ] 集成 Apache Calcite 解析器
  - [ ] 实现 LogicalPlan 生成
  - [ ] 添加 PhysicalPlan 优化器
  - [ ] 支持常见 SQL 函数和操作符

- [ ] **数据源连接器**
  - [ ] 实现 Parquet 文件读取器
  - [ ] 添加 Kafka 流数据源
  - [ ] 支持 JDBC 数据源连接
  - [ ] 实现分区裁剪和谓词下推

- [ ] **执行引擎**
  - [ ] 实现分布式任务调度
  - [ ] 添加表达式计算引擎
  - [ ] 支持聚合和 Join 操作
  - [ ] 实现流式批处理混合模式

#### Phase 3: 元数据与血缘
- [ ] **元数据管理系统**
  - [ ] 设计 Schema Catalog 数据模型
  - [ ] 实现 GraphQL API 接口
  - [ ] 添加表版本管理
  - [ ] 支持列统计信息收集

- [ ] **血缘追踪引擎**
  - [ ] 实现依赖关系分析
  - [ ] 构建血缘图存储
  - [ ] 添加影响分析功能
  - [ ] 支持血缘可视化查询

- [ ] **图数据库集成**
  - [ ] 集成 Neo4j 图数据库
  - [ ] 实现血缘图遍历优化
  - [ ] 添加图查询语言支持
  - [ ] 实现图数据缓存机制

#### Phase 4: 容错与状态管理
- [ ] **EventSourcing 实现**
  - [ ] 设计事件存储模型
  - [ ] 实现命令和事件分离
  - [ ] 添加事件回放机制
  - [ ] 支持时间旅行查询

- [ ] **快照管理**
  - [ ] 实现 Arrow 状态快照
  - [ ] 添加增量备份策略
  - [ ] 支持快照压缩和存储
  - [ ] 实现快照恢复点管理

- [ ] **故障恢复**
  - [ ] 实现健康检查机制
  - [ ] 添加自动故障检测
  - [ ] 支持任务重新调度
  - [ ] 实现节点隔离和恢复

#### Phase 5: 监控与运维
- [ ] **指标收集系统**
  - [ ] 实现性能指标收集
  - [ ] 添加资源使用监控
  - [ ] 支持自定义业务指标
  - [ ] 实现指标聚合和存储

- [ ] **告警系统**
  - [ ] 设计告警规则引擎
  - [ ] 实现多渠道告警通知
  - [ ] 添加告警升级机制
  - [ ] 支持告警抑制和静默

- [ ] **日志管理**
  - [ ] 实现分布式日志收集
  - [ ] 添加日志聚合和索引
  - [ ] 支持日志搜索和分析
  - [ ] 实现审计日志追踪

#### Phase 6: 用户界面
- [ ] **Web 控制台**
  - [ ] 实现 SQL 查询编辑器
  - [ ] 添加 DAG 可视化编辑器
  - [ ] 实现血缘图可视化
  - [ ] 支持实时监控面板

- [ ] **API 网关**
  - [ ] 实现 REST API 接口
  - [ ] 添加 GraphQL 端点
  - [ ] 支持 WebSocket 实时通信
  - [ ] 实现认证和授权

- [ ] **移动端适配**
  - [ ] 响应式界面设计
  - [ ] 移动端监控面板
  - [ ] 推送通知支持
  - [ ] 离线数据缓存

## 📁 项目结构 (Day 2 更新)

```
pekko-reference/
├── pekko-server/src/main/scala/cn/xuyinyin/magic/
│   ├── cluster/                    # 集群管理
│   │   ├── PekkoGuardian.scala    # 集群守护Actor
│   │   └── HealthChecker.scala    # 健康检查
│   │
│   ├── task/                       # ⭐ 任务调度系统 (NEW)
│   │   ├── TaskModels.scala       # 任务模型定义
│   │   ├── TaskScheduler.scala    # 任务调度器
│   │   ├── TaskActor.scala        # 单任务执行Actor
│   │   ├── TaskExecutionEngine.scala  # 执行引擎门面
│   │   └── executors/             # 执行器目录
│   │       ├── TaskExecutor.scala            # 执行器基类
│   │       ├── SqlTaskExecutor.scala         # SQL执行器
│   │       ├── DataProcessTaskExecutor.scala # 数据处理
│   │       ├── FileTransferTaskExecutor.scala # 文件传输
│   │       ├── ExecutorRegistry.scala        # 注册表
│   │       └── EXAMPLE_KafkaTaskExecutor.scala # 扩展示例
│   │
│   ├── stream/engine/              # ⭐ 数据执行引擎 (NEW)
│   │   ├── DataExecutionEngine.scala  # 核心引擎
│   │   ├── DataSource.scala          # 数据源
│   │   ├── DataSink.scala            # 数据汇
│   │   └── EXAMPLE_DataExecutionEngineUsage.scala # 使用示例
│   │
│   ├── http/                       # HTTP服务
│   │   ├── HttpRoutes.scala       # 路由定义
│   │   └── TaskHttpRoutes.scala   # 任务API (NEW)
│   │
│   ├── server/                     # 服务器
│   │   └── PekkoClusterService.scala
│   │
│   └── single/                     # 单例组件
│       └── PekkoGc.scala          # GC清理
│
├── xxt-ui/                         # 前端项目 (React)
│   ├── src/
│   │   ├── pages/                 # 页面组件
│   │   ├── api/                   # API客户端
│   │   └── components/            # 通用组件
│   └── package.json
│
├── docs/                           # 📚 文档
│   ├── TASK_ARCHITECTURE_V2.md    # 任务系统架构 (NEW)
│   ├── TASK_SCHEDULING_GUIDE.md   # 任务调度指南
│   └── TASK_EXTENSION_GUIDE.md    # 任务扩展指南
│
├── test_task_scheduling.sh         # 测试脚本 (NEW)
└── README.md                       # 本文件
```

## 📚 文档导航

### 核心文档
- **[README.md](./README.md)** - 项目总览（本文件）
- **[TASK_ARCHITECTURE_V2.md](./TASK_ARCHITECTURE_V2.md)** - 任务系统架构详解 ⭐ NEW
- **[TASK_SCHEDULING_GUIDE.md](./TASK_SCHEDULING_GUIDE.md)** - 任务调度使用指南
- **[TASK_EXTENSION_GUIDE.md](./TASK_EXTENSION_GUIDE.md)** - 如何扩展新任务类型

### 前端文档
- **[xxt-ui/GETTING_STARTED.md](./xxt-ui/GETTING_STARTED.md)** - 前端快速开始
- **[xxt-ui/README.md](./xxt-ui/README.md)** - 前端项目说明

### 示例代码
- **[EXAMPLE_DataExecutionEngineUsage.scala](./pekko-server/src/main/scala/cn/xuyinyin/magic/stream/engine/EXAMPLE_DataExecutionEngineUsage.scala)** - 数据引擎使用示例 ⭐ NEW
- **[EXAMPLE_KafkaTaskExecutor.scala](./pekko-server/src/main/scala/cn/xuyinyin/magic/task/executors/EXAMPLE_KafkaTaskExecutor.scala)** - 任务扩展完整示例 ⭐ NEW

## 🎓 学习路径

### 1️⃣ 快速体验（15分钟）
1. 启动服务：`sbt "project pekko-server" "runMain cn.xuyinyin.magic.PekkoServer"`
2. 提交SQL任务（见上面的示例）
3. 查询任务状态
4. 运行测试脚本：`./test_task_scheduling.sh`

### 2️⃣ 深入理解（1小时）
1. 阅读 **TASK_ARCHITECTURE_V2.md** 理解架构设计
2. 查看 **TaskScheduler.scala** 了解调度逻辑
3. 研究 **DataExecutionEngine.scala** 学习数据处理
4. 运行示例程序感受API

### 3️⃣ 动手实践（2小时）
1. 参考 **EXAMPLE_KafkaTaskExecutor.scala** 添加自定义任务类型
2. 使用数据执行引擎处理实际数据
3. 集成外部组件（Spark/Kafka等）
4. 编写单元测试

### 4️⃣ 生产部署（1天）
1. 配置多节点集群
2. 添加监控告警
3. 优化性能参数
4. 编写运维文档

## 💡 使用技巧

### 任务调度最佳实践
1. **合理设置优先级**: 重要任务优先级设为7-10
2. **使用批处理**: 大量小任务使用batch操作
3. **监控任务状态**: 定期查询统计信息
4. **错误处理**: 实现自定义的重试逻辑

### 数据处理最佳实践
1. **流式处理**: 大文件使用流式读取，避免OOM
2. **背压控制**: 让Pekko Stream自动处理背压
3. **分批处理**: 使用`.batch()`分批处理数据
4. **资源清理**: 使用try-finally确保资源释放

### 性能优化建议
1. **并行度调整**: 根据CPU核心数调整并行度
2. **缓冲区大小**: 调整buffer size平衡内存和吞吐
3. **ActorSystem复用**: 全局共享ActorSystem实例
4. **连接池**: 数据库/Kafka使用连接池

### 🔧 技术债务清单

#### 已完成 ✅
- ✅ `TaskScheduler` - 修复death pact问题
- ✅ `TaskActor` - 状态持久化
- ✅ 代码结构重构 - 模块化executors目录
- ✅ 数据执行引擎 - 基于Pekko Stream

#### 需要重构的模块
- [ ] `CalciteMysqlSqlParser` - 简化复杂的字符串替换逻辑
- [ ] `BasicCdcDemo` - 拆分为更小的测试用例
- [ ] `PekkoStreamUtils` - 添加错误处理和监控
- [ ] `ClusterManager` - 优化集群状态同步性能
- [ ] `DataFlowCoordinator` - 重构任务调度算法
- [ ] `ArrowSerializer` - 优化序列化性能
- [ ] `DataProcessTaskExecutor` - 集成真实的ActorSystem传递 ⭐ TODO

#### 配置优化
- [ ] 统一配置文件格式
- [ ] 添加环境变量支持
- [ ] 改进日志配置
- [ ] 实现配置热更新
- [ ] 添加配置验证机制
- [ ] 支持多环境配置管理

#### 测试覆盖
- [ ] 添加单元测试
- [ ] 集成测试自动化
- [ ] 性能基准测试
- [ ] 端到端测试套件
- [ ] 混沌工程测试
- [ ] 压力测试和容量规划

#### 性能优化
- [ ] JVM 调优和内存管理
- [ ] 网络通信优化
- [ ] 数据序列化优化
- [ ] 缓存策略改进
- [ ] 并发性能调优
- [ ] 资源池管理优化

#### 安全加固
- [ ] 实现端到端加密
- [ ] 添加访问控制列表
- [ ] 支持 Kerberos 认证
- [ ] 实现数据脱敏功能
- [ ] 添加安全审计日志
- [ ] 支持合规性检查

### 📚 文档完善

#### 技术文档
- [ ] API 接口文档
- [ ] 架构设计文档
- [ ] 部署运维文档
- [ ] 故障排查手册
- [ ] 性能调优指南
- [ ] 安全配置指南

#### 用户文档
- [ ] 快速入门指南
- [ ] 用户操作手册
- [ ] 常见问题解答
- [ ] 最佳实践指南
- [ ] 示例和教程
- [ ] 视频教程制作

### 🚀 部署与运维

#### 容器化部署
- [ ] Docker 镜像构建
- [ ] Kubernetes 部署文件
- [ ] Helm Chart 包
- [ ] 容器监控配置
- [ ] 自动扩缩容策略
- [ ] 滚动更新机制

#### 生产环境
- [ ] 高可用架构设计
- [ ] 灾备恢复方案
- [ ] 数据备份策略
- [ ] 监控告警配置
- [ ] 日志聚合方案
- [ ] 性能基准测试

### 🧪 研究与探索

#### 新技术调研
- [ ] Rust 异步框架对比
- [ ] 新一代 SQL 优化器研究
- [ ] 云原生存储方案
- [ ] 机器学习集成可能
- [ ] 实时计算框架对比
- [ ] 边缘计算场景分析

#### 架构演进
- [ ] 微服务架构迁移
- [ ] Serverless 架构适配
- [ ] 多云部署策略
- [ ] 混合云架构设计
- [ ] 联邦学习架构
- [ ] 数据网格架构

## 🏗️ 项目结构

```
pekko-reference/
├── pekko-server/                 # 主服务模块
│   ├── src/main/scala/
│   │   ├── cluster/             # Pekko 集群组件
│   │   ├── parser/              # SQL 解析器 (Calcite)
│   │   ├── cdc/                 # CDC 数据处理
│   │   └── stream/              # 流处理组件
│   └── src/main/resources/
├── datafusion-workers/           # DataFusion Worker (Rust) [计划中]
├── web-ui/                      # 前端界面 [计划中]
├── docker-compose.yml           # 集群部署配置
└── build.sbt                    # SBT 构建配置
```

## 🤝 贡献指南

1. Fork 项目
2. 创建特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 开启 Pull Request

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情。

## 🔗 相关链接

- [Apache Pekko 官方文档](https://pekko.apache.org/)
- [Apache Arrow 官方网站](https://arrow.apache.org/)
- [DataFusion GitHub 仓库](https://github.com/apache/arrow-datafusion)
- [Apache Calcite 文档](https://calcite.apache.org/)
