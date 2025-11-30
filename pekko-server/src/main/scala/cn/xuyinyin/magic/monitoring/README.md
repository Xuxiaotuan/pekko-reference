# 监控和可观测性

本目录包含分布式工作流引擎的监控和可观测性组件。

## 组件

### PrometheusMetrics
Prometheus指标管理器，提供以下指标：

**核心工作流指标：**
- `workflow_entity_count`: 当前活跃的工作流Entity数量
- `workflow_routing_latency_seconds`: 工作流路由延迟
- `workflow_failover_total`: 工作流故障转移总次数
- `workflow_rebalance_total`: 工作流再平衡总次数

**访问端点：** GET http://localhost:8080/metrics

### ClusterEventLogger
结构化事件记录器，记录以下事件：

**事件类型：**
1. **workflow_migration**: 工作流迁移事件
2. **shard_rebalance**: 分片再平衡事件
3. **workflow_failover**: 工作流故障转移事件
4. **entity_started**: Entity启动事件
5. **entity_stopped**: Entity停止事件
6. **member_event**: 集群成员变化事件
7. **leader_changed**: Leader变更事件
8. **unreachable_member**: 不可达节点事件
9. **reachable_member**: 可达节点恢复事件

**日志格式：**
所有事件都以JSON格式记录，例如：
```json
{
  "timestamp": "2024-11-28T15:30:00Z",
  "event_type": "workflow_migration",
  "workflow_id": "wf-123",
  "from_node": "pekko://system@node1:2551",
  "to_node": "pekko://system@node2:2551",
  "reason": "rebalance",
  "severity": "INFO"
}
```

**日志配置：**
在logback.xml中配置了专门的logger：
```xml
<logger name="ClusterEvents" level="INFO" additivity="false">
    <appender-ref ref="CLUSTER_EVENTS"/>
</logger>
```

### ClusterEventListener
自动监听并记录所有集群事件的Actor。

在PekkoGuardian启动时自动创建，无需手动配置。

## 使用示例

### 查询Prometheus指标
```bash
curl http://localhost:8080/metrics
```

### 查询集群状态
```bash
# 查看Shard分布
curl http://localhost:8080/api/v1/cluster/shards

# 查看集群统计
curl http://localhost:8080/api/v1/cluster/stats

# 查看工作流位置
curl http://localhost:8080/api/v1/cluster/workflow/wf-123/location
```

### 查看结构化日志
集群事件日志会自动输出到控制台，可以通过日志聚合工具（如ELK、Loki）收集和分析。

过滤集群事件：
```bash
# 查看所有集群事件
grep "CLUSTER_EVENT:" logs/application.log

# 查看故障转移事件
grep "workflow_failover" logs/application.log | jq .

# 查看再平衡事件
grep "shard_rebalance" logs/application.log | jq .
```

## Grafana仪表板

推荐的Grafana查询：

**工作流Entity数量：**
```promql
workflow_entity_count
```

**工作流路由延迟（P95）：**
```promql
histogram_quantile(0.95, rate(workflow_routing_latency_seconds_bucket[5m]))
```

**故障转移率：**
```promql
rate(workflow_failover_total[5m])
```

**再平衡率：**
```promql
rate(workflow_rebalance_total[5m])
```

## 告警规则

推荐的Prometheus告警规则：

```yaml
groups:
  - name: workflow_alerts
    rules:
      - alert: HighFailoverRate
        expr: rate(workflow_failover_total[5m]) > 0.1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High workflow failover rate"
          
      - alert: HighRoutingLatency
        expr: histogram_quantile(0.95, rate(workflow_routing_latency_seconds_bucket[5m])) > 1.0
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High workflow routing latency"
```
