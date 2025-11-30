# 配置指南

本文档描述了分布式工作流引擎的配置选项和最佳实践。

## 目录

- [配置文件](#配置文件)
- [环境变量](#环境变量)
- [集群配置](#集群配置)
- [持久化配置](#持久化配置)
- [Sharding配置](#sharding配置)
- [工作流配置](#工作流配置)
- [配置验证](#配置验证)
- [环境特定配置](#环境特定配置)

## 配置文件

系统使用Typesafe Config库进行配置管理，支持HOCON格式。

### 主配置文件

- `application.conf` - 默认配置
- `application-dev.conf` - 开发环境配置
- `application-test.conf` - 测试环境配置
- `application-prod.conf` - 生产环境配置

### 加载配置

使用JVM参数指定配置文件：

```bash
# 使用开发环境配置
java -Dconfig.resource=application-dev.conf -jar app.jar

# 使用生产环境配置
java -Dconfig.resource=application-prod.conf -jar app.jar
```

## 环境变量

系统支持通过环境变量覆盖配置。环境变量优先级高于配置文件。

### 核心环境变量

| 环境变量 | 描述 | 默认值 | 示例 |
|---------|------|--------|------|
| `PEKKO_HOSTNAME` | 节点主机名 | 127.0.0.1 | node1.example.com |
| `PEKKO_PORT` | 节点端口 | 2551 | 2551 |
| `PEKKO_SEED_NODES` | 种子节点列表 | - | ["pekko://sys@node1:2551"] |
| `PEKKO_ROLES` | 节点角色 | - | ["worker"] |
| `PEKKO_LOG_LEVEL` | 日志级别 | INFO | DEBUG, INFO, WARN, ERROR |

### Sharding环境变量

| 环境变量 | 描述 | 默认值 |
|---------|------|--------|
| `PEKKO_SHARDING_SHARDS` | 分片数量 | 100 |

### 持久化环境变量

| 环境变量 | 描述 | 默认值 |
|---------|------|--------|
| `PEKKO_PERSISTENCE_JOURNAL_PLUGIN` | Journal插件 | leveldb |
| `PEKKO_PERSISTENCE_JOURNAL_DIR` | Journal目录 | /var/lib/pekko/journal |
| `PEKKO_PERSISTENCE_SNAPSHOT_PLUGIN` | Snapshot插件 | local |
| `PEKKO_PERSISTENCE_SNAPSHOT_DIR` | Snapshot目录 | /var/lib/pekko/snapshots |

### 工作流环境变量

| 环境变量 | 描述 | 默认值 |
|---------|------|--------|
| `PEKKO_WORKFLOW_SNAPSHOT_EVERY` | 快照频率 | 100 |
| `PEKKO_WORKFLOW_KEEP_SNAPSHOTS` | 保留快照数 | 3 |

## 集群配置

### 种子节点

种子节点是集群的初始联系点。至少需要配置一个种子节点。

```hocon
pekko.cluster.seed-nodes = [
  "pekko://pekko-cluster-system@node1:2551",
  "pekko://pekko-cluster-system@node2:2551",
  "pekko://pekko-cluster-system@node3:2551"
]
```

**最佳实践：**
- 生产环境至少配置3个种子节点
- 种子节点应该是稳定的、长期运行的节点
- 所有节点应该配置相同的种子节点列表

### 节点角色

节点角色用于区分不同类型的节点。

```hocon
pekko.cluster.roles = ["coordinator", "worker", "api-gateway"]
```

**角色说明：**
- `coordinator` - 协调节点，运行单例Actor
- `worker` - 工作节点，运行工作流Entity
- `api-gateway` - API网关节点，处理HTTP请求

### 故障检测

故障检测器用于检测节点是否可达。

```hocon
pekko.cluster.failure-detector {
  acceptable-heartbeat-pause = 5s
  threshold = 12.0
}
```

**参数说明：**
- `acceptable-heartbeat-pause` - 可接受的心跳暂停时间
- `threshold` - 故障检测阈值（越高越宽容）

**调优建议：**
- 开发环境：`threshold = 8.0`，快速检测
- 生产环境：`threshold = 12.0`，避免误判
- 网络不稳定：增加`acceptable-heartbeat-pause`

### Split Brain Resolver

Split Brain Resolver用于处理网络分区。

```hocon
pekko.cluster {
  downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
  
  split-brain-resolver {
    active-strategy = "keep-majority"
    stable-after = 20s
  }
}
```

**策略选项：**
- `keep-majority` - 保留多数派（推荐）
- `keep-oldest` - 保留最老的节点
- `static-quorum` - 静态法定人数

## 持久化配置

### Journal配置

Journal用于存储事件。

**LevelDB（开发/测试）：**
```hocon
pekko.persistence.journal {
  plugin = "pekko.persistence.journal.leveldb"
  leveldb {
    dir = "target/journal"
    native = false
  }
}
```

**Cassandra（生产推荐）：**
```hocon
pekko.persistence.journal {
  plugin = "pekko.persistence.cassandra.journal"
}

pekko.persistence.cassandra {
  journal {
    keyspace = "pekko"
    table = "messages"
  }
}
```

### Snapshot配置

Snapshot用于存储状态快照。

```hocon
pekko.persistence.snapshot-store {
  plugin = "pekko.persistence.snapshot-store.local"
  local {
    dir = "target/snapshots"
  }
}
```

## Sharding配置

### 分片数量

分片数量决定了工作流的分布粒度。

```hocon
pekko.cluster.sharding.number-of-shards = 100
```

**选择建议：**
- 小集群（< 10节点）：10-50个分片
- 中等集群（10-50节点）：50-200个分片
- 大集群（> 50节点）：200-1000个分片

**注意：** 分片数量一旦确定，不能轻易修改。

### Passivation

Passivation用于自动停止空闲的Entity。

```hocon
pekko.cluster.sharding.passivate-idle-entity-after = 30m
```

**调优建议：**
- 内存充足：设置较长时间（1h-2h）
- 内存紧张：设置较短时间（10m-30m）
- 高频访问：禁用Passivation（off）

### Remember Entities

Remember Entities确保Entity在节点重启后自动恢复。

```hocon
pekko.cluster.sharding {
  remember-entities = on
  remember-entities-store = "eventsourced"
}
```

**存储选项：**
- `eventsourced` - 使用Event Sourcing存储（推荐）
- `ddata` - 使用分布式数据存储

## 工作流配置

### 快照策略

```hocon
pekko.workflow.event-sourcing {
  snapshot-every = 100
  keep-n-snapshots = 3
}
```

**参数说明：**
- `snapshot-every` - 每N个事件保存一次快照
- `keep-n-snapshots` - 保留最近N个快照

**调优建议：**
- 事件量大：减少`snapshot-every`（50-100）
- 事件量小：增加`snapshot-every`（200-500）
- 磁盘空间紧张：减少`keep-n-snapshots`（2-3）

## 配置验证

系统启动时会自动验证配置。

### 验证规则

- 种子节点不能为空
- 节点角色不能为空
- 端口范围：1024-65535
- 故障检测阈值：4.0-20.0
- 分片数量：10-1000
- 快照频率：10-10000
- 保留快照数：1-10

### 手动验证

```scala
import cn.xuyinyin.magic.config.ConfigValidator
import com.typesafe.config.ConfigFactory

val config = ConfigFactory.load()
ConfigValidator.validate(config) match {
  case None => println("Configuration is valid")
  case Some(errors) => 
    println("Configuration errors:")
    errors.foreach(println)
}
```

## 环境特定配置

### 开发环境

```bash
# 使用开发环境配置
java -Dconfig.resource=application-dev.conf \
     -Dpekko.loglevel=DEBUG \
     -jar app.jar
```

**特点：**
- 单节点运行
- 内存持久化
- 详细日志
- 快速故障检测

### 测试环境

```bash
# 使用测试环境配置
java -Dconfig.resource=application-test.conf \
     -jar app.jar
```

**特点：**
- 多节点模拟
- 内存持久化
- 快速超时
- 自动downing

### 生产环境

```bash
# 使用生产环境配置
java -Dconfig.resource=application-prod.conf \
     -DPEKKO_HOSTNAME=node1.example.com \
     -DPEKKO_PORT=2551 \
     -DPEKKO_SEED_NODES='["pekko://sys@node1:2551","pekko://sys@node2:2551"]' \
     -DPEKKO_ROLES='["worker"]' \
     -jar app.jar
```

**特点：**
- 多节点集群
- 持久化存储（Cassandra/PostgreSQL）
- Split Brain Resolver
- 生产级日志

## 配置示例

### 单节点开发环境

```hocon
pekko {
  cluster {
    seed-nodes = ["pekko://sys@127.0.0.1:2551"]
    roles = ["coordinator", "worker", "api-gateway"]
  }
  
  remote.artery.canonical {
    hostname = "127.0.0.1"
    port = 2551
  }
  
  persistence {
    journal.plugin = "pekko.persistence.journal.inmem"
    snapshot-store.plugin = "pekko.persistence.snapshot-store.local"
  }
}
```

### 3节点生产集群

**Node 1:**
```bash
PEKKO_HOSTNAME=node1.example.com \
PEKKO_PORT=2551 \
PEKKO_ROLES='["coordinator","worker"]' \
java -Dconfig.resource=application-prod.conf -jar app.jar
```

**Node 2:**
```bash
PEKKO_HOSTNAME=node2.example.com \
PEKKO_PORT=2551 \
PEKKO_ROLES='["worker"]' \
java -Dconfig.resource=application-prod.conf -jar app.jar
```

**Node 3:**
```bash
PEKKO_HOSTNAME=node3.example.com \
PEKKO_PORT=2551 \
PEKKO_ROLES='["worker","api-gateway"]' \
java -Dconfig.resource=application-prod.conf -jar app.jar
```

## 故障排查

### 常见配置问题

**问题1：节点无法加入集群**
- 检查种子节点配置是否正确
- 检查网络连接和防火墙
- 检查系统名称是否一致

**问题2：Entity无法创建**
- 检查Sharding角色配置
- 检查持久化插件配置
- 检查Journal目录权限

**问题3：性能问题**
- 调整分片数量
- 调整Passivation时间
- 调整快照频率

## 最佳实践

1. **使用环境变量** - 敏感信息和环境特定配置使用环境变量
2. **配置验证** - 启动时验证配置完整性
3. **分层配置** - 使用include机制复用通用配置
4. **文档化** - 记录所有自定义配置及其用途
5. **版本控制** - 配置文件纳入版本控制
6. **监控配置** - 监控关键配置参数的运行时值

## 参考资源

- [Pekko Configuration](https://pekko.apache.org/docs/pekko/current/general/configuration.html)
- [Pekko Cluster](https://pekko.apache.org/docs/pekko/current/typed/cluster.html)
- [Pekko Cluster Sharding](https://pekko.apache.org/docs/pekko/current/typed/cluster-sharding.html)
- [Pekko Persistence](https://pekko.apache.org/docs/pekko/current/typed/persistence.html)
