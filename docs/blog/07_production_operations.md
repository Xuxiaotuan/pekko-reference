# 生产环境实战：从上线到运维的完整指南

> **系列文章：构建下一代任务调度平台**  
> 第七篇：生产篇 - 监控运维与最佳实践（完结篇）

---

## 📋 目录

- [引言](#引言)
- [可观测性体系](#可观测性体系)
- [监控系统](#监控系统)
- [日志系统](#日志系统)
- [分布式追踪](#分布式追踪)
- [告警策略](#告警策略)
- [上线检查清单](#上线检查清单)
- [故障处理](#故障处理)
- [最佳实践](#最佳实践)
- [系列总结](#系列总结)

---

## 引言

🎉 **完结篇！**前面6篇我们构建了完整的分布式任务调度系统。本文分享生产环境实战经验，确保系统稳定运行。

### 生产环境挑战

```
开发 vs 生产：
✗ 单节点 → ✓ 多节点集群
✗ 测试数据 → ✓ 海量真实数据
✗ 可重启 → ✓ 7x24小时
✗ 有时间调试 → ✓ 秒级恢复
```

---

## 可观测性体系

### Observability = Metrics + Logging + Tracing

```
用户：系统慢了！
   ↓
Metrics: 发现问题（P99延迟↑）
   ↓
Tracing: 定位问题（哪里慢？）
   ↓
Logging: 分析问题（具体错误）
```

| 支柱 | 作用 | 工具 |
|-----|------|------|
| **Metrics** | 系统健康度量 | Prometheus |
| **Logging** | 详细事件记录 | ELK/Loki |
| **Tracing** | 请求链路追踪 | Jaeger |

---

## 监控系统

### Prometheus + Grafana

```scala
// Kamon集成
libraryDependencies += "io.kamon" %% "kamon-prometheus" % "2.6.0"

// 初始化
Kamon.init()

// 定义指标
val workflowCounter = Kamon.counter("workflow.execution.total")
val workflowTimer = Kamon.timer("workflow.execution.duration")
val activeGauge = Kamon.gauge("workflow.active.count")

// 使用
val span = workflowTimer.start()
try {
  executeWorkflow()
  workflowCounter.withTag("status", "success").increment()
} finally {
  span.stop()
}
```

### 核心Dashboard

**面板配置**：
1. **吞吐量**：`rate(workflow_execution_total[1m])`
2. **P99延迟**：`histogram_quantile(0.99, workflow_execution_duration_bucket)`
3. **错误率**：`rate(workflow_execution_total{status="failure"}[5m])`
4. **活跃数**：`workflow_active_count`
5. **Mailbox**：`pekko_actor_mailbox_size`
6. **JVM内存**：`jvm_memory_used_bytes / jvm_memory_max_bytes`

---

## 日志系统

### 结构化日志

```scala
import com.typesafe.scalalogging.Logger
import org.slf4j.MDC

class WorkflowExecutor {
  private val logger = Logger(getClass)
  
  def execute(workflow: Workflow, execId: String): Future[Result] = {
    MDC.put("workflow_id", workflow.id)
    MDC.put("execution_id", execId)
    
    try {
      logger.info(s"Starting: name=${workflow.name}, tasks=${workflow.tasks.size}")
      val result = doExecute(workflow)
      logger.info(s"Completed: duration=${result.duration}ms")
      result
    } catch {
      case ex: Exception =>
        logger.error(s"Failed: ${ex.getMessage}", ex)
        throw ex
    } finally {
      MDC.clear()
    }
  }
}
```

### Logback配置

```xml
<configuration>
  <appender name="JSON" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>/var/log/pekko-scheduler/app.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>app.%d{yyyy-MM-dd}.log</fileNamePattern>
      <maxHistory>30</maxHistory>
    </rollingPolicy>
    <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
  </appender>
  
  <root level="INFO">
    <appender-ref ref="JSON"/>
  </root>
</configuration>
```

---

## 分布式追踪

### OpenTelemetry

```scala
// 初始化
val jaegerExporter = JaegerGrpcSpanExporter.builder()
  .setEndpoint("http://jaeger:14250")
  .build()

val openTelemetry = OpenTelemetrySdk.builder()
  .setTracerProvider(/*...*/)
  .build()

// 使用
val tracer = openTelemetry.getTracer("pekko-scheduler")

def execute(workflow: Workflow): Unit = {
  val span = tracer.spanBuilder("workflow.execute")
    .setAttribute("workflow.id", workflow.id)
    .startSpan()
  
  try {
    val scope = span.makeCurrent()
    try {
      workflow.tasks.foreach(executeTask)  // 自动创建子Span
    } finally {
      scope.close()
    }
  } finally {
    span.end()
  }
}
```

**追踪链路示例**：
```
POST /workflows/{id}/execute [200ms]
  ├─ WorkflowSupervisor [5ms]
  ├─ WorkflowActor [190ms]
  │   ├─ SourceExecutor [50ms]
  │   │   └─ MySQL.query [45ms]
  │   ├─ TransformExecutor [80ms]
  │   └─ SinkExecutor [55ms]
  └─ Response [5ms]
```

---

## 告警策略

### 告警分级

| 级别 | 说明 | 响应时间 |
|-----|------|---------|
| **P0** | 服务不可用 | 5分钟 |
| **P1** | 核心功能受影响 | 15分钟 |
| **P2** | 性能下降 | 1小时 |
| **P3** | 潜在问题 | 工作日 |

### 关键告警规则

```yaml
# prometheus/alert.rules.yml
groups:
  - name: pekko_scheduler
    rules:
      # P0: 高错误率
      - alert: HighErrorRate
        expr: rate(workflow_execution_total{status="failure"}[5m]) / rate(workflow_execution_total[5m]) > 0.05
        for: 5m
        labels:
          severity: critical
      
      # P1: 高延迟
      - alert: HighLatency
        expr: histogram_quantile(0.99, rate(workflow_execution_duration_bucket[5m])) > 1000
        for: 5m
        labels:
          severity: warning
      
      # P1: 集群节点不可达
      - alert: ClusterNodeUnreachable
        expr: pekko_cluster_unreachable_members > 0
        for: 1m
        labels:
          severity: critical
      
      # P2: 内存使用过高
      - alert: HighMemoryUsage
        expr: jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 0.85
        for: 5m
        labels:
          severity: warning
```

---

## 上线检查清单

```markdown
## 📋 部署前检查

### 代码准备
- [ ] 所有测试通过
- [ ] 代码Review完成
- [ ] 版本号已更新

### 配置检查
- [ ] 生产环境配置正确
- [ ] JVM参数已优化（-Xms=Xmx, G1GC）
- [ ] Kryo/CBOR序列化已启用

### 集群配置
- [ ] Seed节点配置（至少2个）
- [ ] Split Brain Resolver已启用
- [ ] Cluster Sharding配置正确

### 监控告警
- [ ] Prometheus已配置
- [ ] Grafana Dashboard已创建
- [ ] 告警规则已配置
- [ ] Slack/PagerDuty集成完成

### 备份恢复
- [ ] 数据库备份策略
- [ ] Event Journal备份
- [ ] 回滚方案已准备

### 容量规划
- [ ] 预估QPS已确认
- [ ] 节点数量已规划
- [ ] 磁盘/网络容量确认
```

### 灰度发布

```
灰度流程：
1. 部署1个Canary实例（5%流量）
2. 观察30分钟
3. 扩展到20%流量
4. 再观察30分钟
5. 全量替换
6. 保留旧版本1小时
```

---

## 故障处理

### 常见故障及处理

#### 1. 节点宕机

**处理**：
```bash
# 确认状态
kubectl get pods -l app=pekko-scheduler

# 查看日志
kubectl logs pekko-scheduler-2

# 删除Pod（自动重建）
kubectl delete pod pekko-scheduler-2

# Shard自动re-balance
```

#### 2. 内存泄漏

**处理**：
```bash
# 生成堆转储
jmap -dump:live,format=b,file=heap.hprof <pid>

# 分析（VisualVM/MAT）
# 找出内存占用对象

# 临时：重启
kubectl rollout restart deployment/pekko-scheduler
```

**预防**：
- 定期压测
- 监控内存趋势
- 设置告警

#### 3. 数据库连接耗尽

**处理**：
```sql
-- 查看连接
SHOW PROCESSLIST;

-- Kill慢查询
KILL <thread_id>;
```

```hocon
# 调整连接池
db {
  maxConnections = 50
  connectionTimeout = 5s
}
```

#### 4. Kafka消费延迟

**处理**：
```bash
# 查看Lag
kafka-consumer-groups.sh --describe --group pekko-scheduler

# 扩容Consumer
kubectl scale deployment pekko-worker --replicas=10
```

---

## 最佳实践

### 部署架构

```
生产环境推荐配置：

节点配置：
- COORDINATOR: 3-5个（奇数，容错）
- WORKER: 根据负载弹性扩展
- STORAGE: 3个（副本）
- API_GATEWAY: 2+（负载均衡）

资源配置：
- CPU: 4核+
- 内存: 8GB+
- 磁盘: SSD, 100GB+
- 网络: 1Gbps+
```

### 性能优化清单

- [ ] 使用G1GC或ZGC
- [ ] Xms=Xmx避免动态调整
- [ ] Kryo序列化
- [ ] 批量处理（grouped）
- [ ] mapAsync并发优化
- [ ] async异步边界
- [ ] Cluster Sharding
- [ ] 合理Dispatcher配置

### 监控清单

| 指标 | 告警阈值 |
|-----|---------|
| 吞吐量 | < 100/s |
| P99延迟 | > 500ms |
| 错误率 | > 1% |
| Mailbox | > 10000 |
| 内存 | > 85% |
| 集群不可达 | > 0 |

### 运维清单

**日常**：
- [ ] 查看Dashboard
- [ ] 检查告警
- [ ] 检查日志错误
- [ ] 检查磁盘空间

**每周**：
- [ ] 查看性能趋势
- [ ] 清理旧日志
- [ ] 检查备份

**每月**：
- [ ] 容量规划评估
- [ ] 性能压测
- [ ] 依赖升级

---

## 系列总结

### 🎉 7篇完整旅程

1. **战略篇**：为什么选择Pekko？DolphinScheduler局限分析
2. **架构篇**：Actor模型深度解析，WorkflowActor设计
3. **集群篇**：Cluster Sharding分布式架构
4. **流处理篇**：Pekko Streams流批统一，性能提升50-100x
5. **持久化篇**：Event Sourcing永不丢失状态
6. **性能篇**：压测调优，性能优化10-100x
7. **生产篇**：监控运维，上线最佳实践

### 核心成果

**架构创新**：
- ✅ Actor模型 → 无锁并发
- ✅ Cluster Sharding → 真正分布式
- ✅ Streams → 流批统一
- ✅ Event Sourcing → 完整历史

**性能提升**：
- ✅ 触发延迟：5秒 → <10ms（500x）
- ✅ 吞吐量：1000/s → 10000/s（10x）
- ✅ 故障恢复：30-60s → <1s（50x）
- ✅ 批量处理：逐条 → 批量1000（50-100x）

**生产能力**：
- ✅ 可观测性：Metrics + Logging + Tracing
- ✅ 高可用：Split Brain + 自动恢复
- ✅ 弹性伸缩：Kubernetes HPA
- ✅ 完整监控：Prometheus + Grafana

### 技术栈总结

```
核心框架：
- Pekko Typed Actors
- Pekko Cluster Sharding
- Pekko Streams
- Pekko Persistence

存储：
- PostgreSQL（元数据）
- Cassandra（Event Journal）
- Redis（缓存）

消息队列：
- Kafka

监控：
- Prometheus
- Grafana
- Jaeger
- ELK Stack

部署：
- Kubernetes
- Docker
- Helm
```

### 下一步发展

**功能增强**：
- [ ] Web UI可视化编排
- [ ] 多租户支持
- [ ] 工作流版本管理
- [ ] A/B测试支持

**性能优化**：
- [ ] 更多节点类型
- [ ] 智能调度算法
- [ ] 资源隔离

**企业特性**：
- [ ] SSO集成
- [ ] 审计日志
- [ ] 数据血缘
- [ ] 成本核算

---

## 致谢

感谢阅读完整个系列！🙏

从战略愿景到生产实践，我们一起构建了一个**真正可用的分布式任务调度系统**。

希望这个系列能帮助你：
- 💡 理解分布式系统设计
- 🛠️ 掌握Pekko/Akka技术栈
- 🚀 构建高性能调度系统
- 📈 优化生产环境

---

**项目地址**: https://github.com/Xuxiaotuan/pekko-reference

**作者**: Xuxiaotuan  
**系列完成日期**: 2024年11月16日

**License**: Apache 2.0

---

## 📚 系列文章索引

1. [战略篇：从DolphinScheduler到Pekko](./01_strategy_pekko_vs_dolphinscheduler.md)
2. [架构篇：Actor模型深度解析](./02_actor_model_architecture.md)
3. [集群篇：Cluster Sharding实战](./03_cluster_sharding_practice.md)
4. [流处理篇：Pekko Streams统一流批](./04_streams_unified_processing.md)
5. [持久化篇：Event Sourcing实践](./05_event_sourcing_persistence.md)
6. [性能篇：让系统飞起来](./06_performance_tuning.md)
7. [生产篇：监控运维最佳实践](./07_production_operations.md)（本篇）

---

🎉 **系列完结！感谢一路相伴！** 🎉

*如果觉得有帮助，欢迎Star项目！*
