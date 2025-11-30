package cn.xuyinyin.magic.datafusion

import org.apache.arrow.vector.types.pojo.Schema
import spray.json._
import scala.concurrent.duration._

/**
 * SQL节点配置
 *
 * @param sql SQL查询语句
 * @param schema 可选的预定义Schema
 * @param batchSize 批处理大小
 * @param timeout 查询超时时间
 * @param parameters 查询参数
 */
case class SQLNodeConfig(
  sql: String,
  schema: Option[Schema] = None,
  batchSize: Int = 1000,
  timeout: FiniteDuration = 30.seconds,
  parameters: Map[String, Any] = Map.empty
) {
  
  /**
   * 验证配置
   */
  def validate(): Either[String, Unit] = {
    if (sql.trim.isEmpty) {
      Left("SQL query cannot be empty")
    } else if (batchSize <= 0) {
      Left(s"Batch size must be positive, got: $batchSize")
    } else if (timeout.toMillis <= 0) {
      Left(s"Timeout must be positive, got: $timeout")
    } else {
      Right(())
    }
  }
  
  /**
   * 替换SQL中的参数占位符
   */
  def bindParameters(): String = {
    parameters.foldLeft(sql) { case (currentSql, (key, value)) =>
      val placeholder = s"{{$key}}"
      val replacement = value match {
        case s: String => s"'$s'"
        case n: Number => n.toString
        case b: Boolean => b.toString
        case other => other.toString
      }
      currentSql.replace(placeholder, replacement)
    }
  }
}

object SQLNodeConfig {
  
  /**
   * 从JSON配置解析
   */
  def fromJson(json: JsObject): Either[String, SQLNodeConfig] = {
    try {
      val sql = json.fields.get("sql") match {
        case Some(JsString(s)) => s
        case _ => return Left("Missing or invalid 'sql' field")
      }
      
      val batchSize = json.fields.get("batchSize") match {
        case Some(JsNumber(n)) => n.intValue
        case None => 1000
        case _ => return Left("Invalid 'batchSize' field")
      }
      
      val timeout = json.fields.get("timeout") match {
        case Some(JsNumber(n)) => n.intValue.seconds
        case None => 30.seconds
        case _ => return Left("Invalid 'timeout' field")
      }
      
      val parameters = json.fields.get("parameters") match {
        case Some(JsObject(params)) =>
          params.map { case (k, v) =>
            k -> (v match {
              case JsString(s) => s
              case JsNumber(n) => n
              case JsBoolean(b) => b
              case other => other.toString
            })
          }.toMap
        case None => Map.empty[String, Any]
        case _ => return Left("Invalid 'parameters' field")
      }
      
      val config = SQLNodeConfig(
        sql = sql,
        schema = None, // Schema从JSON解析较复杂，暂时不支持
        batchSize = batchSize,
        timeout = timeout,
        parameters = parameters
      )
      
      config.validate().map(_ => config)
    } catch {
      case e: Exception => Left(s"Failed to parse config: ${e.getMessage}")
    }
  }
  
  /**
   * 转换为JSON
   */
  def toJson(config: SQLNodeConfig): JsObject = {
    val fields = Map(
      "sql" -> JsString(config.sql),
      "batchSize" -> JsNumber(config.batchSize),
      "timeout" -> JsNumber(config.timeout.toSeconds)
    )
    
    val fieldsWithParams = if (config.parameters.nonEmpty) {
      val params = config.parameters.map { case (k, v) =>
        k -> (v match {
          case s: String => JsString(s)
          case n: Int => JsNumber(n)
          case n: Long => JsNumber(n)
          case n: Double => JsNumber(n)
          case b: Boolean => JsBoolean(b)
          case other => JsString(other.toString)
        })
      }
      fields + ("parameters" -> JsObject(params))
    } else {
      fields
    }
    
    JsObject(fieldsWithParams)
  }
}

/**
 * SQL节点统计信息
 */
case class SQLNodeStats(
  queriesExecuted: Long = 0,
  totalRows: Long = 0,
  totalBytes: Long = 0,
  totalDuration: Long = 0, // 毫秒
  errors: Long = 0
) {
  
  def avgDuration: Double = if (queriesExecuted > 0) {
    totalDuration.toDouble / queriesExecuted
  } else 0.0
  
  def avgRows: Double = if (queriesExecuted > 0) {
    totalRows.toDouble / queriesExecuted
  } else 0.0
  
  def successRate: Double = if (queriesExecuted > 0) {
    ((queriesExecuted - errors).toDouble / queriesExecuted) * 100
  } else 100.0
  
  def +(other: SQLNodeStats): SQLNodeStats = {
    SQLNodeStats(
      queriesExecuted = this.queriesExecuted + other.queriesExecuted,
      totalRows = this.totalRows + other.totalRows,
      totalBytes = this.totalBytes + other.totalBytes,
      totalDuration = this.totalDuration + other.totalDuration,
      errors = this.errors + other.errors
    )
  }
  
  override def toString: String = {
    s"SQLNodeStats(queries=$queriesExecuted, rows=$totalRows, bytes=$totalBytes, " +
      s"avgDuration=${avgDuration.formatted("%.2f")}ms, " +
      s"successRate=${successRate.formatted("%.1f")}%)"
  }
}
