# 迁移指南：从v0.4到v0.5

本文档提供从v0.4（单节点架构）到v0.5（分布式Cluster Sharding架构）的迁移指南。

## 目录

- [迁移概述](#迁移概述)
- [架构变更](#架构变更)
- [迁移前准备](#迁移前准备)
- [迁移步骤](#迁移步骤)
- [数据迁移](#数据迁移)
- [API兼容性](#api兼容性)
- [回滚方案](#回滚方案)
- [常见问题](#常见问题)

## 迁移概述

### 主要变更

v0.5版本引入了以下重大变更：

1. **Cluster Sharding** - 工作流现在分布在多个节点上
2. **Event Sourcing增强** - 改进的快照和恢复机制
3. **监控和可观测性** - 新增Prometheus指标和事件记录
4. **配置管理** - 支持环境变量和多环境配置

### 迁移收益

- ✅ **水平扩展** - 支持多节点集群，线性扩展能力
- ✅ **高可用性** - 自动故障转移，无单点故障
- ✅ **负载均衡** - 工作流自动分布到多个节点
- ✅ **更好的监控** - 完整的指标和事件记录

### 迁移风险

- ⚠️ **配置变更** - 需要更新配置文件
- ⚠️ **数据迁移** - 需要迁移持久化数据
- ⚠️ **停机时间** - 迁移过程需要短暂停机（< 30分钟）

## 架构变更

### v0.4架构（单节点）

```
┌─────────────────────────────────┐
│      Single Node                │
│                                 │
│  ┌──────────────────────────┐  │
│  │  WorkflowSupervisor      │  │
│  │  (Local Actor Manager)   │  │
│  └──────────────────────────┘  │
│              │                  │
│              ▼                  │
│  ┌──────────────────────────┐  │
│  │  WorkflowActor           │  │
│  │  (In-Memory)             │  │
│  └──────────────────────────┘  │
└─────────────────────────────────┘
```

### v0.5架构（分布式集群）

```
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│   Node 1     │  │   Node 2     │  │   Node 3     │
│              │  │              │  │              │
│ ┌──────────┐ │  │ ┌──────────┐ │  │ ┌──────────┐ │
│ │ Sharding │ │  │ │ Sharding │ │  │ │ Sharding │ │
│ │  Region  │ │  │ │  Region  │ │  │ │  Region  │ │
│ └────┬─────┘ │  │ └────┬─────┘ │  │ └────┬─────┘ │
│      │       │  │      │       │  │      │       │
│      ▼       │  │      ▼       │  │      ▼       │
│ ┌──────────┐ │  │ ┌──────────┐ │  │ ┌──────────┐ │
│ │Workflow  │ │  │ │Workflow  │ │  │ │Workflow  │ │
│ │Entity 1  │ │  │ │Entity 2  │ │  │ │Entity 3  │ │
│ └──────────┘ │  │ └──────────┘ │  │ └──────────┘ │
└──────────────┘  └──────────────┘  └──────────────┘
```

## 迁移前准备

### 1. 备份数据

```bash
# 备份Journal
tar -czf journal-backup-$(date +%Y%m%d).tar.gz /var/lib/pekko/journal/

# 备份Snapshots
tar -czf snapshots-backup-$(date +%Y%m%d).tar.gz /var/lib/pekko/snapshots/

# 备份配置
cp /opt/workflow-engine/application.conf application.conf.v04.backup
```

### 2. 检查系统状态

```bash
# 检查当前版本
curl http://localhost:8080/api/v1/version

# 检查运行中的工作流
curl http://localhost:8080/api/v1/workflows | jq '.total'

# 导出工作流列表
curl http://localhost:8080/api/v1/workflows > workflows-backup.json
```

### 3. 准备新环境

```bash
# 准备3个节点（如果是集群部署）
# 确保节点间网络连通
ping node1
ping node2
ping node3

# 检查端口可用性
telnet node1 2551
telnet node2 2551
telnet node3 2551
```

### 4. 通知用户

- 📢 提前通知用户计划的维护窗口
- 📢 建议用户完成正在进行的工作流
- 📢 准备回滚方案

## 迁移步骤

### 方案A：单节点迁移（最简单）

适用于从v0.4单节点迁移到v0.5单节点。

#### 步骤1：停止v0.4服务

```bash
# 停止服务
sudo systemctl stop workflow-engine

# 确认服务已停止
sudo systemctl status workflow-engine
```

#### 步骤2：安装v0.5

```bash
# 备份旧版本
mv /opt/workflow-engine/workflow-engine.jar \
   /opt/workflow-engine/workflow-engine-v04.jar.backup

# 部署新版本
cp workflow-engine-v05.jar /opt/workflow-engine/workflow-engine.jar
```

#### 步骤3：更新配置

```bash
# 创建新配置文件
cat > /opt/workflow-engine/application.conf << 'EOF'
include classpath("application.conf")

pekko {
  cluster {
    seed-nodes = ["pekko://pekko-cluster-system@127.0.0.1:2551"]
    roles = ["coordinator", "worker", "api-gateway"]
  }
  
  remote.artery.canonical {
    hostname = "127.0.0.1"
    port = 2551
  }
  
  cluster.sharding {
    number-of-shards = 100
    role = "worker"
  }
}
EOF
```

#### 步骤4：迁移数据

```bash
# v0.5使用相同的持久化格式，数据可以直接使用
# 只需确保目录权限正确
sudo chown -R workflow:workflow /var/lib/pekko/
```

#### 步骤5：启动v0.5

```bash
# 启动服务
sudo systemctl start workflow-engine

# 查看日志
tail -f /var/log/workflow-engine/application.log

# 等待服务启动（约30秒）
```

#### 步骤6：验证迁移

```bash
# 检查版本
curl http://localhost:8080/api/v1/version
# 应该返回 v0.5

# 检查集群状态
curl http://localhost:8080/api/v1/cluster/stats

# 检查工作流
curl http://localhost:8080/api/v1/workflows

# 测试创建工作流
curl -X POST http://localhost:8080/api/v1/workflows \
  -H "Content-Type: application/json" \
  -d @test-workflow.json
```

### 方案B：单节点到集群迁移（推荐）

适用于从v0.4单节点迁移到v0.5三节点集群。

#### 步骤1：准备集群节点

在每个节点上：

```bash
# 安装v0.5
scp workflow-engine-v05.jar node1:/opt/workflow-engine/workflow-engine.jar
scp workflow-engine-v05.jar node2:/opt/workflow-engine/workflow-engine.jar
scp workflow-engine-v05.jar node3:/opt/workflow-engine/workflow-engine.jar
```

#### 步骤2：迁移数据到Node1

```bash
# 停止v0.4服务
sudo systemctl stop workflow-engine

# 复制数据到Node1
rsync -avz /var/lib/pekko/ node1:/var/lib/pekko/
```

#### 步骤3：配置集群

参考[部署文档](./DEPLOYMENT.md#多节点集群部署)配置三个节点。

#### 步骤4：启动集群

```bash
# 按顺序启动
ssh node1 "sudo systemctl start workflow-engine"
sleep 30
ssh node2 "sudo systemctl start workflow-engine"
sleep 30
ssh node3 "sudo systemctl start workflow-engine"
```

#### 步骤5：验证集群

```bash
# 检查集群成员
curl http://node1:8080/api/v1/cluster/stats

# 应该看到3个成员
{
  "members": 3,
  "status": "Up",
  ...
}

# 检查工作流分布
curl http://node1:8080/api/v1/cluster/shards
```

### 方案C：零停机迁移（高级）

适用于需要零停机的生产环境。

#### 步骤1：部署v0.5集群（与v0.4并行）

```bash
# 在新节点上部署v0.5集群
# 使用不同的端口避免冲突
```

#### 步骤2：配置双写

```bash
# 配置负载均衡器同时转发到v0.4和v0.5
# 新工作流创建在v0.5，旧工作流继续在v0.4
```

#### 步骤3：逐步迁移工作流

```bash
# 使用迁移脚本逐步迁移工作流
./migrate-workflows.sh --from v04 --to v05 --batch-size 100
```

#### 步骤4：切换流量

```bash
# 逐步增加v0.5的流量比例
# 10% -> 50% -> 100%
```

#### 步骤5：下线v0.4

```bash
# 确认所有工作流已迁移
# 停止v0.4服务
```

## 数据迁移

### 持久化数据兼容性

v0.5与v0.4使用相同的Event Sourcing格式，数据可以直接迁移。

### Journal迁移

```bash
# v0.4 Journal位置
/var/lib/pekko/journal/

# v0.5 Journal位置（相同）
/var/lib/pekko/journal/

# 无需特殊处理，直接复制即可
```

### Snapshot迁移

```bash
# v0.4 Snapshot位置
/var/lib/pekko/snapshots/

# v0.5 Snapshot位置（相同）
/var/lib/pekko/snapshots/

# 无需特殊处理，直接复制即可
```

### 数据验证

```bash
# 迁移后验证数据完整性
./scripts/validate-migration.sh

# 检查工作流数量
curl http://localhost:8080/api/v1/workflows | jq '.total'

# 对比迁移前后的数量
```

## API兼容性

### 兼容的API

以下API在v0.5中保持完全兼容：

- ✅ `POST /api/v1/workflows` - 创建工作流
- ✅ `POST /api/v1/workflows/{id}/execute` - 执行工作流
- ✅ `GET /api/v1/workflows/{id}/status` - 查询状态
- ✅ `GET /api/v1/workflows` - 列出工作流

### 新增的API

v0.5新增以下API：

- 🆕 `GET /api/v1/cluster/stats` - 集群统计
- 🆕 `GET /api/v1/cluster/shards` - 分片信息
- 🆕 `GET /api/v1/events` - 事件查询
- 🆕 `GET /metrics` - Prometheus指标

### 废弃的API

无废弃API。

### 客户端更新

客户端代码无需修改，可以直接使用v0.5。

```java
// v0.4代码
WorkflowClient client = new WorkflowClient("http://localhost:8080");
client.createWorkflow(workflow);

// v0.5代码（完全相同）
WorkflowClient client = new WorkflowClient("http://localhost:8080");
client.createWorkflow(workflow);
```

## 回滚方案

### 何时回滚

如果遇到以下情况，建议回滚：

- ❌ 迁移后服务无法启动
- ❌ 数据丢失或损坏
- ❌ 性能严重下降
- ❌ 关键功能不可用

### 回滚步骤

#### 1. 停止v0.5服务

```bash
# 停止所有v0.5节点
sudo systemctl stop workflow-engine
```

#### 2. 恢复v0.4

```bash
# 恢复v0.4 JAR
mv /opt/workflow-engine/workflow-engine-v04.jar.backup \
   /opt/workflow-engine/workflow-engine.jar

# 恢复v0.4配置
cp application.conf.v04.backup /opt/workflow-engine/application.conf
```

#### 3. 恢复数据（如果需要）

```bash
# 恢复Journal
rm -rf /var/lib/pekko/journal/
tar -xzf journal-backup-20241128.tar.gz -C /var/lib/pekko/

# 恢复Snapshots
rm -rf /var/lib/pekko/snapshots/
tar -xzf snapshots-backup-20241128.tar.gz -C /var/lib/pekko/
```

#### 4. 启动v0.4

```bash
# 启动服务
sudo systemctl start workflow-engine

# 验证服务
curl http://localhost:8080/api/v1/version
# 应该返回 v0.4
```

### 回滚时间窗口

- ⏱️ 迁移后24小时内：可以快速回滚
- ⏱️ 迁移后1周内：可以回滚，但需要数据同步
- ⏱️ 迁移后1周以上：不建议回滚

## 常见问题

### Q1: 迁移需要多长时间？

**A:** 取决于数据量和迁移方案：
- 单节点迁移：10-30分钟
- 集群迁移：30-60分钟
- 零停机迁移：2-4小时

### Q2: 迁移过程中会丢失数据吗？

**A:** 不会。v0.5使用相同的持久化格式，数据完全兼容。建议迁移前备份数据。

### Q3: 需要更新客户端代码吗？

**A:** 不需要。API保持完全兼容。

### Q4: 可以从v0.5回滚到v0.4吗？

**A:** 可以，但建议在迁移后24小时内回滚。超过1周后不建议回滚。

### Q5: 单节点v0.5和集群v0.5有什么区别？

**A:** 功能完全相同，但集群版本提供：
- 更高的可用性
- 更好的扩展性
- 自动故障转移

### Q6: 迁移后性能会提升吗？

**A:** 
- 单节点：性能基本相同
- 集群：吞吐量可提升2-3倍（3节点集群）

### Q7: 需要修改现有的工作流定义吗？

**A:** 不需要。工作流定义格式保持不变。

### Q8: 迁移失败怎么办？

**A:** 
1. 查看日志：`/var/log/workflow-engine/application.log`
2. 检查配置：使用ConfigValidator验证
3. 联系支持团队
4. 如果无法解决，执行回滚

### Q9: 可以跳过v0.5直接升级到更高版本吗？

**A:** 不建议。建议按版本顺序升级。

### Q10: 迁移后如何验证成功？

**A:** 执行以下检查：
```bash
# 1. 检查版本
curl http://localhost:8080/api/v1/version

# 2. 检查集群状态
curl http://localhost:8080/api/v1/cluster/stats

# 3. 检查工作流数量
curl http://localhost:8080/api/v1/workflows | jq '.total'

# 4. 测试创建和执行工作流
curl -X POST http://localhost:8080/api/v1/workflows -d @test.json
```

## 迁移检查清单

### 迁移前

- [ ] 备份Journal数据
- [ ] 备份Snapshot数据
- [ ] 备份配置文件
- [ ] 导出工作流列表
- [ ] 通知用户维护窗口
- [ ] 准备回滚方案
- [ ] 测试新版本（在测试环境）

### 迁移中

- [ ] 停止v0.4服务
- [ ] 部署v0.5
- [ ] 更新配置
- [ ] 迁移数据
- [ ] 启动v0.5
- [ ] 验证服务启动

### 迁移后

- [ ] 检查版本
- [ ] 检查集群状态
- [ ] 验证工作流数量
- [ ] 测试API功能
- [ ] 检查监控指标
- [ ] 检查日志
- [ ] 通知用户迁移完成
- [ ] 监控24小时

## 获取帮助

如果在迁移过程中遇到问题：

1. 查看[故障排查文档](./DEPLOYMENT.md#故障排查)
2. 查看[配置文档](./CONFIGURATION.md)
3. 联系技术支持团队
4. 提交Issue到GitHub

## 参考资源

- [部署文档](./DEPLOYMENT.md)
- [配置文档](./CONFIGURATION.md)
- [架构文档](./blog/02_actor_model_architecture.md)
- [Pekko Cluster Sharding](https://pekko.apache.org/docs/pekko/current/typed/cluster-sharding.html)
