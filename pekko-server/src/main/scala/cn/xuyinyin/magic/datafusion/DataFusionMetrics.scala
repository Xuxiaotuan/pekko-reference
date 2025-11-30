package cn.xuyinyin.magic.datafusion

import io.prometheus.client._
import com.typesafe.scalalogging.Logger

/**
 * DataFusion Prometheus指标收集器
 * 
 * 提供以下指标：
 * - datafusion_query_duration_seconds: 查询执行时间（直方图）
 * - datafusion_query_total: 查询总数（计数器）
 * - datafusion_query_errors_total: 查询错误总数（计数器）
 * - datafusion_data_transferred_bytes: 数据传输字节数（计数器）
 * - datafusion_pool_connections: 连接池状态（仪表盘）
 * - datafusion_pool_wait_time_seconds: 连接池等待时间（直方图）
 */
object DataFusionMetrics {
  
  private val logger = Logger(getClass)
  
  // 查询执行时间（直方图）
  val queryDuration: Histogram = Histogram.build()
    .name("datafusion_query_duration_seconds")
    .help("DataFusion query execution duration in seconds")
    .labelNames("node_id", "status") // status: success, error, timeout
    .buckets(0.01, 0.05, 0.1, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0)
    .register()
  
  // 查询总数（计数器）
  val queryTotal: Counter = Counter.build()
    .name("datafusion_query_total")
    .help("Total number of DataFusion queries executed")
    .labelNames("node_id", "status") // status: success, error, timeout
    .register()
  
  // 查询错误总数（计数器）
  val queryErrors: Counter = Counter.build()
    .name("datafusion_query_errors_total")
    .help("Total number of DataFusion query errors")
    .labelNames("node_id", "error_type") // error_type: sql_syntax, timeout, connection, etc.
    .register()
  
  // 数据传输字节数（计数器）
  val dataTransferred: Counter = Counter.build()
    .name("datafusion_data_transferred_bytes")
    .help("Total bytes transferred to/from DataFusion")
    .labelNames("node_id", "direction") // direction: sent, received
    .register()
  
  // 连接池状态（仪表盘）
  val poolConnections: Gauge = Gauge.build()
    .name("datafusion_pool_connections")
    .help("Current number of connections in the pool")
    .labelNames("state") // state: active, idle, total
    .register()
  
  // 连接池等待时间（直方图）
  val poolWaitTime: Histogram = Histogram.build()
    .name("datafusion_pool_wait_time_seconds")
    .help("Time spent waiting for a connection from the pool")
    .buckets(0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1.0, 5.0)
    .register()
  
  // 查询行数（直方图）
  val queryRows: Histogram = Histogram.build()
    .name("datafusion_query_rows")
    .help("Number of rows returned by DataFusion queries")
    .labelNames("node_id")
    .buckets(1, 10, 100, 1000, 10000, 100000, 1000000)
    .register()
  
  /**
   * 记录查询执行
   */
  def recordQuery(
    nodeId: String,
    durationSeconds: Double,
    status: String,
    rowCount: Long = 0,
    bytesReceived: Long = 0
  ): Unit = {
    try {
      queryDuration.labels(nodeId, status).observe(durationSeconds)
      queryTotal.labels(nodeId, status).inc()
      
      if (rowCount > 0) {
        queryRows.labels(nodeId).observe(rowCount.toDouble)
      }
      
      if (bytesReceived > 0) {
        dataTransferred.labels(nodeId, "received").inc(bytesReceived.toDouble)
      }
      
      logger.debug(s"Recorded query metrics: node=$nodeId, duration=${durationSeconds}s, status=$status, rows=$rowCount")
    } catch {
      case e: Exception =>
        logger.error(s"Failed to record query metrics: ${e.getMessage}", e)
    }
  }
  
  /**
   * 记录查询成功
   */
  def recordQuerySuccess(
    nodeId: String,
    durationSeconds: Double,
    rowCount: Long = 0,
    bytesReceived: Long = 0
  ): Unit = {
    recordQuery(nodeId, durationSeconds, "success", rowCount, bytesReceived)
  }
  
  /**
   * 记录查询错误
   */
  def recordQueryError(
    nodeId: String,
    durationSeconds: Double,
    errorType: String
  ): Unit = {
    try {
      recordQuery(nodeId, durationSeconds, "error")
      queryErrors.labels(nodeId, errorType).inc()
      
      logger.debug(s"Recorded query error: node=$nodeId, errorType=$errorType")
    } catch {
      case e: Exception =>
        logger.error(s"Failed to record query error metrics: ${e.getMessage}", e)
    }
  }
  
  /**
   * 记录查询超时
   */
  def recordQueryTimeout(
    nodeId: String,
    durationSeconds: Double
  ): Unit = {
    try {
      recordQuery(nodeId, durationSeconds, "timeout")
      queryErrors.labels(nodeId, "timeout").inc()
      
      logger.debug(s"Recorded query timeout: node=$nodeId")
    } catch {
      case e: Exception =>
        logger.error(s"Failed to record query timeout metrics: ${e.getMessage}", e)
    }
  }
  
  /**
   * 记录数据发送
   */
  def recordDataSent(nodeId: String, bytes: Long): Unit = {
    try {
      dataTransferred.labels(nodeId, "sent").inc(bytes.toDouble)
    } catch {
      case e: Exception =>
        logger.error(s"Failed to record data sent metrics: ${e.getMessage}", e)
    }
  }
  
  /**
   * 更新连接池状态
   */
  def updatePoolConnections(active: Int, idle: Int, total: Int): Unit = {
    try {
      poolConnections.labels("active").set(active.toDouble)
      poolConnections.labels("idle").set(idle.toDouble)
      poolConnections.labels("total").set(total.toDouble)
      
      logger.trace(s"Updated pool connections: active=$active, idle=$idle, total=$total")
    } catch {
      case e: Exception =>
        logger.error(s"Failed to update pool connection metrics: ${e.getMessage}", e)
    }
  }
  
  /**
   * 记录连接池等待时间
   */
  def recordPoolWaitTime(waitTimeSeconds: Double): Unit = {
    try {
      poolWaitTime.observe(waitTimeSeconds)
      
      logger.trace(s"Recorded pool wait time: ${waitTimeSeconds}s")
    } catch {
      case e: Exception =>
        logger.error(s"Failed to record pool wait time metrics: ${e.getMessage}", e)
    }
  }
  
  /**
   * 获取当前指标快照
   */
  def getMetricsSnapshot: MetricsSnapshot = {
    try {
      MetricsSnapshot(
        queryTotal = queryTotal.labels("", "success").get() + queryTotal.labels("", "error").get(),
        querySuccess = queryTotal.labels("", "success").get(),
        queryErrors = queryTotal.labels("", "error").get(),
        activeConnections = poolConnections.labels("active").get().toInt,
        idleConnections = poolConnections.labels("idle").get().toInt,
        totalConnections = poolConnections.labels("total").get().toInt
      )
    } catch {
      case e: Exception =>
        logger.error(s"Failed to get metrics snapshot: ${e.getMessage}", e)
        MetricsSnapshot.empty
    }
  }
  
  /**
   * 重置所有指标（主要用于测试）
   */
  def reset(): Unit = {
    try {
      CollectorRegistry.defaultRegistry.clear()
      logger.info("All metrics have been reset")
    } catch {
      case e: Exception =>
        logger.error(s"Failed to reset metrics: ${e.getMessage}", e)
    }
  }
}

/**
 * 指标快照
 */
case class MetricsSnapshot(
  queryTotal: Double,
  querySuccess: Double,
  queryErrors: Double,
  activeConnections: Int,
  idleConnections: Int,
  totalConnections: Int
) {
  def successRate: Double = if (queryTotal > 0) {
    (querySuccess / queryTotal) * 100
  } else 100.0
  
  override def toString: String = {
    s"MetricsSnapshot(total=$queryTotal, success=$querySuccess, errors=$queryErrors, " +
      s"successRate=${successRate.formatted("%.1f")}%, " +
      s"connections=active:$activeConnections/idle:$idleConnections/total:$totalConnections)"
  }
}

object MetricsSnapshot {
  def empty: MetricsSnapshot = MetricsSnapshot(0, 0, 0, 0, 0, 0)
}

/**
 * 查询计时器
 * 
 * 用于自动记录查询执行时间
 */
class QueryTimer(nodeId: String) {
  private val startTime = System.nanoTime()
  private var completed = false
  
  /**
   * 记录成功完成
   */
  def recordSuccess(rowCount: Long = 0, bytesReceived: Long = 0): Unit = {
    if (!completed) {
      val durationSeconds = (System.nanoTime() - startTime) / 1e9
      DataFusionMetrics.recordQuerySuccess(nodeId, durationSeconds, rowCount, bytesReceived)
      completed = true
    }
  }
  
  /**
   * 记录错误
   */
  def recordError(errorType: String): Unit = {
    if (!completed) {
      val durationSeconds = (System.nanoTime() - startTime) / 1e9
      DataFusionMetrics.recordQueryError(nodeId, durationSeconds, errorType)
      completed = true
    }
  }
  
  /**
   * 记录超时
   */
  def recordTimeout(): Unit = {
    if (!completed) {
      val durationSeconds = (System.nanoTime() - startTime) / 1e9
      DataFusionMetrics.recordQueryTimeout(nodeId, durationSeconds)
      completed = true
    }
  }
  
  /**
   * 获取已经过的时间（秒）
   */
  def elapsedSeconds: Double = (System.nanoTime() - startTime) / 1e9
}

object QueryTimer {
  def apply(nodeId: String): QueryTimer = new QueryTimer(nodeId)
}
