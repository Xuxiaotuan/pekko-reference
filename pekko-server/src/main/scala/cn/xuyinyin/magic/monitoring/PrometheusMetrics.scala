package cn.xuyinyin.magic.monitoring

import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}
import io.prometheus.client.hotspot.DefaultExports

/**
 * Prometheus指标管理器
 * 
 * 提供工作流引擎的关键指标：
 * - workflow_entity_count: 当前活跃的工作流Entity数量
 * - workflow_routing_latency_seconds: 工作流路由延迟
 * - workflow_failover_total: 工作流故障转移总次数
 * - workflow_rebalance_total: 工作流再平衡总次数
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-28
 */
object PrometheusMetrics {
  
  // 默认的CollectorRegistry
  val registry: CollectorRegistry = CollectorRegistry.defaultRegistry
  
  // 初始化JVM指标（内存、GC、线程等）
  DefaultExports.initialize()
  
  /**
   * 工作流Entity数量
   * 
   * 当前活跃的工作流Entity数量
   */
  val workflowEntityCount: Gauge = Gauge.build()
    .name("workflow_entity_count")
    .help("Current number of active workflow entities")
    .labelNames("shard_id")
    .register(registry)
  
  /**
   * 工作流路由延迟
   * 
   * 从HTTP请求到达到工作流Actor处理的延迟（秒）
   */
  val workflowRoutingLatency: Histogram = Histogram.build()
    .name("workflow_routing_latency_seconds")
    .help("Workflow routing latency in seconds")
    .labelNames("operation", "workflow_id")
    .buckets(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0)
    .register(registry)
  
  /**
   * 工作流故障转移总次数
   * 
   * 工作流因节点故障而转移到其他节点的总次数
   */
  val workflowFailoverTotal: Counter = Counter.build()
    .name("workflow_failover_total")
    .help("Total number of workflow failovers")
    .labelNames("workflow_id", "from_node", "to_node")
    .register(registry)
  
  /**
   * 工作流再平衡总次数
   * 
   * 集群再平衡导致工作流迁移的总次数
   */
  val workflowRebalanceTotal: Counter = Counter.build()
    .name("workflow_rebalance_total")
    .help("Total number of workflow rebalances")
    .labelNames("shard_id", "from_node", "to_node")
    .register(registry)
  
  /**
   * 工作流创建总次数
   */
  val workflowCreatedTotal: Counter = Counter.build()
    .name("workflow_created_total")
    .help("Total number of workflows created")
    .labelNames("status")
    .register(registry)
  
  /**
   * 工作流执行总次数
   */
  val workflowExecutionTotal: Counter = Counter.build()
    .name("workflow_execution_total")
    .help("Total number of workflow executions")
    .labelNames("workflow_id", "status")
    .register(registry)
  
  /**
   * 工作流执行持续时间
   */
  val workflowExecutionDuration: Histogram = Histogram.build()
    .name("workflow_execution_duration_seconds")
    .help("Workflow execution duration in seconds")
    .labelNames("workflow_id", "status")
    .buckets(0.1, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0)
    .register(registry)
  
  /**
   * HTTP请求总次数
   */
  val httpRequestsTotal: Counter = Counter.build()
    .name("http_requests_total")
    .help("Total number of HTTP requests")
    .labelNames("method", "path", "status")
    .register(registry)
  
  /**
   * HTTP请求持续时间
   */
  val httpRequestDuration: Histogram = Histogram.build()
    .name("http_request_duration_seconds")
    .help("HTTP request duration in seconds")
    .labelNames("method", "path")
    .buckets(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0)
    .register(registry)
  
  /**
   * 集群成员数量
   */
  val clusterMemberCount: Gauge = Gauge.build()
    .name("cluster_member_count")
    .help("Current number of cluster members")
    .labelNames("status")
    .register(registry)
  
  /**
   * 分片数量
   */
  val shardCount: Gauge = Gauge.build()
    .name("shard_count")
    .help("Current number of shards")
    .labelNames("entity_type", "status")
    .register(registry)
  
  /**
   * 记录工作流路由延迟
   */
  def recordRoutingLatency(operation: String, workflowId: String)(block: => Unit): Unit = {
    val timer = workflowRoutingLatency.labels(operation, workflowId).startTimer()
    try {
      block
    } finally {
      timer.observeDuration()
    }
  }
  
  /**
   * 记录工作流故障转移
   */
  def recordFailover(workflowId: String, fromNode: String, toNode: String): Unit = {
    workflowFailoverTotal.labels(workflowId, fromNode, toNode).inc()
  }
  
  /**
   * 记录工作流再平衡
   */
  def recordRebalance(shardId: String, fromNode: String, toNode: String): Unit = {
    workflowRebalanceTotal.labels(shardId, fromNode, toNode).inc()
  }
  
  /**
   * 更新Entity数量
   */
  def updateEntityCount(shardId: String, count: Double): Unit = {
    workflowEntityCount.labels(shardId).set(count)
  }
  
  /**
   * 记录工作流创建
   */
  def recordWorkflowCreated(status: String): Unit = {
    workflowCreatedTotal.labels(status).inc()
  }
  
  /**
   * 记录工作流执行
   */
  def recordWorkflowExecution(workflowId: String, status: String, durationSeconds: Double): Unit = {
    workflowExecutionTotal.labels(workflowId, status).inc()
    workflowExecutionDuration.labels(workflowId, status).observe(durationSeconds)
  }
  
  /**
   * 记录HTTP请求
   */
  def recordHttpRequest(method: String, path: String, status: String): Unit = {
    httpRequestsTotal.labels(method, path, status).inc()
  }
  
  /**
   * 记录HTTP请求持续时间
   */
  def recordHttpRequestDuration(method: String, path: String)(block: => Unit): Unit = {
    val timer = httpRequestDuration.labels(method, path).startTimer()
    try {
      block
    } finally {
      timer.observeDuration()
    }
  }
  
  /**
   * 更新集群成员数量
   */
  def updateClusterMemberCount(status: String, count: Double): Unit = {
    clusterMemberCount.labels(status).set(count)
  }
  
  /**
   * 更新分片数量
   */
  def updateShardCount(entityType: String, status: String, count: Double): Unit = {
    shardCount.labels(entityType, status).set(count)
  }
}
