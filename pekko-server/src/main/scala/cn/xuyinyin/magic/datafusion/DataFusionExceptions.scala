package cn.xuyinyin.magic.datafusion

/**
 * DataFusion异常基类
 * 
 * 所有DataFusion相关的异常都继承此类
 */
sealed abstract class DataFusionException(
  message: String,
  cause: Throwable = null
) extends RuntimeException(message, cause) {
  
  /**
   * 异常类型标识
   */
  def errorType: String
  
  /**
   * 是否可重试
   */
  def isRetryable: Boolean
  
  /**
   * 获取详细的错误上下文
   */
  def context: Map[String, Any] = Map.empty
  
  /**
   * 转换为JSON格式的错误信息
   */
  def toJson: String = {
    val contextStr = context.map { case (k, v) => s""""$k":"$v"""" }.mkString(",")
    s"""{"errorType":"$errorType","message":"$message","retryable":$isRetryable,"context":{$contextStr}}"""
  }
}

/**
 * 服务不可用异常
 * 
 * 当DataFusion Service无法连接或不可用时抛出
 */
case class ServiceUnavailableException(
  message: String,
  host: String,
  port: Int,
  cause: Throwable = null
) extends DataFusionException(message, cause) {
  
  override def errorType: String = "SERVICE_UNAVAILABLE"
  
  override def isRetryable: Boolean = true
  
  override def context: Map[String, Any] = Map(
    "host" -> host,
    "port" -> port,
    "service" -> "DataFusion"
  )
}

object ServiceUnavailableException {
  def apply(host: String, port: Int): ServiceUnavailableException = {
    new ServiceUnavailableException(
      s"DataFusion Service is unavailable at $host:$port",
      host,
      port
    )
  }
  
  def apply(host: String, port: Int, cause: Throwable): ServiceUnavailableException = {
    new ServiceUnavailableException(
      s"Failed to connect to DataFusion Service at $host:$port: ${cause.getMessage}",
      host,
      port,
      cause
    )
  }
}

/**
 * SQL语法错误异常
 * 
 * 当SQL查询包含语法错误时抛出
 */
case class SQLSyntaxException(
  message: String,
  sql: String,
  position: Option[Int] = None,
  cause: Throwable = null
) extends DataFusionException(message, cause) {
  
  override def errorType: String = "SQL_SYNTAX_ERROR"
  
  override def isRetryable: Boolean = false
  
  override def context: Map[String, Any] = {
    val base = Map(
      "sql" -> sql.take(200), // 限制SQL长度
      "sqlLength" -> sql.length
    )
    position match {
      case Some(pos) => base + ("position" -> pos)
      case None => base
    }
  }
}

object SQLSyntaxException {
  def apply(sql: String): SQLSyntaxException = {
    new SQLSyntaxException(
      s"SQL syntax error in query: ${sql.take(100)}...",
      sql
    )
  }
  
  def apply(sql: String, cause: Throwable): SQLSyntaxException = {
    new SQLSyntaxException(
      s"SQL syntax error: ${cause.getMessage}",
      sql,
      None,
      cause
    )
  }
  
  def apply(sql: String, position: Int, errorMsg: String): SQLSyntaxException = {
    new SQLSyntaxException(
      s"SQL syntax error at position $position: $errorMsg",
      sql,
      Some(position)
    )
  }
}

/**
 * 数据格式异常
 * 
 * 当数据格式转换失败时抛出
 */
case class DataFormatException(
  message: String,
  sourceFormat: String,
  targetFormat: String,
  data: Option[String] = None,
  cause: Throwable = null
) extends DataFusionException(message, cause) {
  
  override def errorType: String = "DATA_FORMAT_ERROR"
  
  override def isRetryable: Boolean = false
  
  override def context: Map[String, Any] = {
    val base = Map(
      "sourceFormat" -> sourceFormat,
      "targetFormat" -> targetFormat
    )
    data match {
      case Some(d) => base + ("dataSample" -> d.take(100))
      case None => base
    }
  }
}

object DataFormatException {
  def jsonToArrow(data: String, cause: Throwable): DataFormatException = {
    new DataFormatException(
      s"Failed to convert JSON to Arrow: ${cause.getMessage}",
      "JSON",
      "Arrow",
      Some(data),
      cause
    )
  }
  
  def arrowToJson(cause: Throwable): DataFormatException = {
    new DataFormatException(
      s"Failed to convert Arrow to JSON: ${cause.getMessage}",
      "Arrow",
      "JSON",
      None,
      cause
    )
  }
  
  def schemaInference(data: String, cause: Throwable): DataFormatException = {
    new DataFormatException(
      s"Failed to infer schema from data: ${cause.getMessage}",
      "JSON",
      "Arrow Schema",
      Some(data),
      cause
    )
  }
}

/**
 * 查询超时异常
 * 
 * 当查询执行超过指定时间时抛出
 */
case class QueryTimeoutException(
  message: String,
  sql: String,
  timeoutMs: Long,
  elapsedMs: Long,
  cause: Throwable = null
) extends DataFusionException(message, cause) {
  
  override def errorType: String = "QUERY_TIMEOUT"
  
  override def isRetryable: Boolean = true
  
  override def context: Map[String, Any] = Map(
    "sql" -> sql.take(200),
    "timeoutMs" -> timeoutMs,
    "elapsedMs" -> elapsedMs,
    "timeoutSeconds" -> (timeoutMs / 1000.0),
    "elapsedSeconds" -> (elapsedMs / 1000.0)
  )
}

object QueryTimeoutException {
  def apply(sql: String, timeoutMs: Long, elapsedMs: Long): QueryTimeoutException = {
    new QueryTimeoutException(
      s"Query execution timeout after ${elapsedMs}ms (limit: ${timeoutMs}ms): ${sql.take(100)}...",
      sql,
      timeoutMs,
      elapsedMs
    )
  }
}

/**
 * 连接池耗尽异常
 * 
 * 当连接池中没有可用连接时抛出
 */
case class ConnectionPoolExhaustedException(
  message: String,
  maxConnections: Int,
  activeConnections: Int,
  waitTimeMs: Long,
  cause: Throwable = null
) extends DataFusionException(message, cause) {
  
  override def errorType: String = "CONNECTION_POOL_EXHAUSTED"
  
  override def isRetryable: Boolean = true
  
  override def context: Map[String, Any] = Map(
    "maxConnections" -> maxConnections,
    "activeConnections" -> activeConnections,
    "waitTimeMs" -> waitTimeMs
  )
}

object ConnectionPoolExhaustedException {
  def apply(maxConnections: Int, activeConnections: Int, waitTimeMs: Long): ConnectionPoolExhaustedException = {
    new ConnectionPoolExhaustedException(
      s"Connection pool exhausted: $activeConnections/$maxConnections active, waited ${waitTimeMs}ms",
      maxConnections,
      activeConnections,
      waitTimeMs
    )
  }
}

/**
 * 配置错误异常
 * 
 * 当配置无效或缺失时抛出
 */
case class ConfigurationException(
  message: String,
  configKey: String,
  configValue: Option[String] = None,
  cause: Throwable = null
) extends DataFusionException(message, cause) {
  
  override def errorType: String = "CONFIGURATION_ERROR"
  
  override def isRetryable: Boolean = false
  
  override def context: Map[String, Any] = {
    val base = Map("configKey" -> configKey)
    configValue match {
      case Some(v) => base + ("configValue" -> v)
      case None => base
    }
  }
}

object ConfigurationException {
  def missingConfig(key: String): ConfigurationException = {
    new ConfigurationException(
      s"Missing required configuration: $key",
      key
    )
  }
  
  def invalidConfig(key: String, value: String, reason: String): ConfigurationException = {
    new ConfigurationException(
      s"Invalid configuration for $key='$value': $reason",
      key,
      Some(value)
    )
  }
}

/**
 * 异常工具类
 */
object DataFusionExceptions {
  
  /**
   * 判断异常是否可重试
   */
  def isRetryable(throwable: Throwable): Boolean = throwable match {
    case e: DataFusionException => e.isRetryable
    case _: java.net.ConnectException => true
    case _: java.net.SocketTimeoutException => true
    case _: java.io.IOException => true
    case _ => false
  }
  
  /**
   * 获取异常的错误类型
   */
  def getErrorType(throwable: Throwable): String = throwable match {
    case e: DataFusionException => e.errorType
    case _: java.net.ConnectException => "CONNECTION_ERROR"
    case _: java.net.SocketTimeoutException => "SOCKET_TIMEOUT"
    case _: java.io.IOException => "IO_ERROR"
    case _: IllegalArgumentException => "INVALID_ARGUMENT"
    case _: IllegalStateException => "INVALID_STATE"
    case _ => "UNKNOWN_ERROR"
  }
  
  /**
   * 包装异常为DataFusionException
   */
  def wrap(throwable: Throwable, context: String): DataFusionException = throwable match {
    case e: DataFusionException => e
    case e: java.net.ConnectException =>
      ServiceUnavailableException("localhost", 50051, e)
    case e: java.util.concurrent.TimeoutException =>
      QueryTimeoutException("", 30000, 30000)
    case e =>
      new DataFusionException(s"$context: ${e.getMessage}", e) {
        override def errorType: String = getErrorType(e)
        override def isRetryable: Boolean = DataFusionExceptions.isRetryable(e)
      }
  }
}
