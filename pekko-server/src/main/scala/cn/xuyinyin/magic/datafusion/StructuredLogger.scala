package cn.xuyinyin.magic.datafusion

import com.typesafe.scalalogging.Logger
import spray.json._
import DefaultJsonProtocol._

/**
 * 结构化日志工具
 * 
 * 提供JSON格式的结构化日志记录
 */
object StructuredLogger {
  
  private val logger = Logger("DataFusion")
  
  /**
   * 记录查询开始
   */
  def logQueryStart(
    nodeId: String,
    sql: String,
    batchSize: Int,
    parameters: Map[String, Any] = Map.empty
  ): Unit = {
    val logEntry = QueryStartLog(
      event = "query_start",
      timestamp = System.currentTimeMillis(),
      nodeId = nodeId,
      sql = sql.take(500), // 限制SQL长度
      batchSize = batchSize,
      parameters = parameters
    )
    
    logger.info(logEntry.toJson)
  }
  
  /**
   * 记录查询完成
   */
  def logQueryComplete(
    nodeId: String,
    sql: String,
    durationMs: Long,
    rows: Long,
    bytes: Long
  ): Unit = {
    val logEntry = QueryCompleteLog(
      event = "query_complete",
      timestamp = System.currentTimeMillis(),
      nodeId = nodeId,
      sql = sql.take(500),
      durationMs = durationMs,
      rows = rows,
      bytes = bytes,
      throughput = if (durationMs > 0) (rows * 1000.0 / durationMs).toLong else 0
    )
    
    logger.info(logEntry.toJson)
  }
  
  /**
   * 记录查询失败
   */
  def logQueryFailed(
    nodeId: String,
    sql: String,
    durationMs: Long,
    error: String,
    errorType: String,
    stackTrace: Option[String] = None
  ): Unit = {
    val logEntry = QueryFailedLog(
      event = "query_failed",
      timestamp = System.currentTimeMillis(),
      nodeId = nodeId,
      sql = sql.take(500),
      durationMs = durationMs,
      error = error,
      errorType = errorType,
      stackTrace = stackTrace
    )
    
    logger.error(logEntry.toJson)
  }
  
  /**
   * 记录连接池事件
   */
  def logPoolEvent(
    event: String,
    active: Int,
    idle: Int,
    total: Int,
    waitTimeMs: Option[Long] = None
  ): Unit = {
    val logEntry = PoolEventLog(
      event = event,
      timestamp = System.currentTimeMillis(),
      active = active,
      idle = idle,
      total = total,
      waitTimeMs = waitTimeMs
    )
    
    logger.debug(logEntry.toJson)
  }
  
  /**
   * 记录数据转换事件
   */
  def logDataConversion(
    event: String,
    sourceFormat: String,
    targetFormat: String,
    recordCount: Long,
    durationMs: Long,
    success: Boolean
  ): Unit = {
    val logEntry = DataConversionLog(
      event = event,
      timestamp = System.currentTimeMillis(),
      sourceFormat = sourceFormat,
      targetFormat = targetFormat,
      recordCount = recordCount,
      durationMs = durationMs,
      success = success
    )
    
    if (success) {
      logger.debug(logEntry.toJson)
    } else {
      logger.warn(logEntry.toJson)
    }
  }
}

/**
 * 查询开始日志
 */
case class QueryStartLog(
  event: String,
  timestamp: Long,
  nodeId: String,
  sql: String,
  batchSize: Int,
  parameters: Map[String, Any]
) {
  def toJson: String = {
    val paramsJson = parameters.map { case (k, v) => s""""$k":"$v"""" }.mkString(",")
    s"""{"event":"$event","timestamp":$timestamp,"nodeId":"$nodeId","sql":"${escapeJson(sql)}","batchSize":$batchSize,"parameters":{$paramsJson}}"""
  }
  
  private def escapeJson(s: String): String = {
    s.replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
  }
}

/**
 * 查询完成日志
 */
case class QueryCompleteLog(
  event: String,
  timestamp: Long,
  nodeId: String,
  sql: String,
  durationMs: Long,
  rows: Long,
  bytes: Long,
  throughput: Long // rows per second
) {
  def toJson: String = {
    s"""{"event":"$event","timestamp":$timestamp,"nodeId":"$nodeId","sql":"${escapeJson(sql)}","durationMs":$durationMs,"rows":$rows,"bytes":$bytes,"throughput":$throughput}"""
  }
  
  private def escapeJson(s: String): String = {
    s.replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
  }
}

/**
 * 查询失败日志
 */
case class QueryFailedLog(
  event: String,
  timestamp: Long,
  nodeId: String,
  sql: String,
  durationMs: Long,
  error: String,
  errorType: String,
  stackTrace: Option[String]
) {
  def toJson: String = {
    val stackTraceJson = stackTrace match {
      case Some(st) => s""","stackTrace":"${escapeJson(st.take(1000))}""""
      case None => ""
    }
    s"""{"event":"$event","timestamp":$timestamp,"nodeId":"$nodeId","sql":"${escapeJson(sql)}","durationMs":$durationMs,"error":"${escapeJson(error)}","errorType":"$errorType"$stackTraceJson}"""
  }
  
  private def escapeJson(s: String): String = {
    s.replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
  }
}

/**
 * 连接池事件日志
 */
case class PoolEventLog(
  event: String,
  timestamp: Long,
  active: Int,
  idle: Int,
  total: Int,
  waitTimeMs: Option[Long]
) {
  def toJson: String = {
    val waitTimeJson = waitTimeMs match {
      case Some(wt) => s""","waitTimeMs":$wt"""
      case None => ""
    }
    s"""{"event":"$event","timestamp":$timestamp,"active":$active,"idle":$idle,"total":$total$waitTimeJson}"""
  }
}

/**
 * 数据转换日志
 */
case class DataConversionLog(
  event: String,
  timestamp: Long,
  sourceFormat: String,
  targetFormat: String,
  recordCount: Long,
  durationMs: Long,
  success: Boolean
) {
  def toJson: String = {
    s"""{"event":"$event","timestamp":$timestamp,"sourceFormat":"$sourceFormat","targetFormat":"$targetFormat","recordCount":$recordCount,"durationMs":$durationMs,"success":$success}"""
  }
}

/**
 * 日志事件类型
 */
object LogEvents {
  val QUERY_START = "query_start"
  val QUERY_COMPLETE = "query_complete"
  val QUERY_FAILED = "query_failed"
  val POOL_CONNECTION_ACQUIRED = "pool_connection_acquired"
  val POOL_CONNECTION_RELEASED = "pool_connection_released"
  val POOL_CONNECTION_CREATED = "pool_connection_created"
  val POOL_CONNECTION_DESTROYED = "pool_connection_destroyed"
  val DATA_CONVERSION_START = "data_conversion_start"
  val DATA_CONVERSION_COMPLETE = "data_conversion_complete"
}

/**
 * 查询日志上下文
 * 
 * 用于在查询执行过程中收集日志信息
 */
class QueryLogContext(nodeId: String, sql: String) {
  private val startTime = System.currentTimeMillis()
  private var rowCount: Long = 0
  private var byteCount: Long = 0
  
  /**
   * 记录开始
   */
  def logStart(batchSize: Int, parameters: Map[String, Any] = Map.empty): Unit = {
    StructuredLogger.logQueryStart(nodeId, sql, batchSize, parameters)
  }
  
  /**
   * 更新统计信息
   */
  def updateStats(rows: Long, bytes: Long): Unit = {
    rowCount += rows
    byteCount += bytes
  }
  
  /**
   * 记录成功完成
   */
  def logSuccess(): Unit = {
    val duration = System.currentTimeMillis() - startTime
    StructuredLogger.logQueryComplete(nodeId, sql, duration, rowCount, byteCount)
  }
  
  /**
   * 记录失败
   */
  def logFailure(error: Throwable): Unit = {
    val duration = System.currentTimeMillis() - startTime
    val errorType = DataFusionExceptions.getErrorType(error)
    val stackTrace = error.getStackTrace.take(10).mkString("\n")
    
    StructuredLogger.logQueryFailed(
      nodeId,
      sql,
      duration,
      error.getMessage,
      errorType,
      Some(stackTrace)
    )
  }
  
  /**
   * 获取已经过的时间（毫秒）
   */
  def elapsedMs: Long = System.currentTimeMillis() - startTime
}

object QueryLogContext {
  def apply(nodeId: String, sql: String): QueryLogContext = {
    new QueryLogContext(nodeId, sql)
  }
}
