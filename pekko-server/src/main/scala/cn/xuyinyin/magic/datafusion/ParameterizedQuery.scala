package cn.xuyinyin.magic.datafusion

import com.typesafe.scalalogging.Logger

import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

/**
 * 参数化查询处理器
 * 
 * 提供SQL参数化查询功能，防止SQL注入攻击
 */
object ParameterizedQuery {
  
  private val logger = Logger(getClass)
  
  // 参数占位符模式：:paramName 或 {{paramName}}
  private val colonParameterPattern: Regex = """:([a-zA-Z_][a-zA-Z0-9_]*)""".r
  private val braceParameterPattern: Regex = """\{\{([a-zA-Z_][a-zA-Z0-9_]*)\}\}""".r
  
  // 位置参数模式：?
  private val positionalParameterPattern: Regex = """\?""".r
  
  /**
   * 处理参数化查询（命名参数）
   *
   * @param sql 包含参数占位符的SQL
   * @param parameters 参数映射
   * @return 处理后的SQL
   */
  def processQuery(sql: String, parameters: Map[String, Any]): Try[String] = {
    logger.debug(s"Processing parameterized query with ${parameters.size} parameters")
    
    Try {
      // 检查SQL注入风险
      validateSQLSafety(sql, parameters)
      
      // 替换命名参数（支持两种格式）
      val processedSQL = replaceNamedParameters(sql, parameters)
      
      logger.debug(s"Parameterized query processed successfully")
      processedSQL
    }.recoverWith {
      case e: Exception =>
        logger.error(s"Failed to process parameterized query: ${e.getMessage}", e)
        Failure(e)
    }
  }
  
  /**
   * 处理位置参数查询
   *
   * @param sql 包含?占位符的SQL
   * @param parameters 参数列表
   * @return 处理后的SQL
   */
  def processPositionalQuery(sql: String, parameters: List[Any]): Try[String] = {
    logger.debug(s"Processing positional query with ${parameters.size} parameters")
    
    Try {
      // 检查参数数量
      val placeholderCount = positionalParameterPattern.findAllIn(sql).length
      if (placeholderCount != parameters.size) {
        throw new IllegalArgumentException(
          s"Parameter count mismatch: expected $placeholderCount, got ${parameters.size}"
        )
      }
      
      // 验证参数安全性
      parameters.foreach(validateParameterValue)
      
      // 替换位置参数
      var processedSQL = sql
      parameters.foreach { param =>
        val escapedValue = escapeParameterValue(param)
        processedSQL = positionalParameterPattern.replaceFirstIn(processedSQL, escapedValue)
      }
      
      logger.debug(s"Positional query processed successfully")
      processedSQL
    }.recoverWith {
      case e: Exception =>
        logger.error(s"Failed to process positional query: ${e.getMessage}", e)
        Failure(e)
    }
  }
  
  /**
   * 替换命名参数（支持:param和{{param}}两种格式）
   */
  private def replaceNamedParameters(sql: String, parameters: Map[String, Any]): String = {
    // 先处理{{param}}格式
    val afterBrace = braceParameterPattern.replaceAllIn(sql, { m =>
      val paramName = m.group(1)
      parameters.get(paramName) match {
        case Some(value) =>
          validateParameterValue(value)
          Regex.quoteReplacement(escapeParameterValue(value))
        case None =>
          throw new IllegalArgumentException(s"Missing parameter: $paramName")
      }
    })
    
    // 再处理:param格式
    colonParameterPattern.replaceAllIn(afterBrace, { m =>
      val paramName = m.group(1)
      parameters.get(paramName) match {
        case Some(value) =>
          validateParameterValue(value)
          Regex.quoteReplacement(escapeParameterValue(value))
        case None =>
          throw new IllegalArgumentException(s"Missing parameter: $paramName")
      }
    })
  }
  
  /**
   * 验证SQL安全性
   */
  private def validateSQLSafety(sql: String, parameters: Map[String, Any]): Unit = {
    // 检查SQL中是否包含危险关键字（在参数值中）
    val dangerousKeywords = List(
      "DROP", "DELETE", "INSERT", "UPDATE", "CREATE", "ALTER", 
      "TRUNCATE", "EXEC", "EXECUTE", "--", "/*", "*/"
    )
    
    // 检查参数值中是否包含SQL注入尝试
    parameters.values.foreach {
      case s: String =>
        val upperValue = s.toUpperCase
        dangerousKeywords.foreach { keyword =>
          if (upperValue.contains(keyword)) {
            throw new SecurityException(s"Potential SQL injection detected in parameter value: $s")
          }
        }
        // 检查是否包含引号注入
        if (s.contains("';") || s.contains("\";")||s.contains("' OR") || s.contains("\" OR")) {
          throw new SecurityException(s"Potential SQL injection detected in parameter value: $s")
        }
      case _ => // 非字符串参数相对安全
    }
  }
  
  /**
   * 验证参数值
   */
  private def validateParameterValue(value: Any): Unit = {
    value match {
      case null => // null值是允许的
      case _: String => // 字符串需要转义
      case _: Int | _: Long | _: Double | _: Float => // 数值类型是安全的
      case _: Boolean => // 布尔值是安全的
      case _: BigDecimal => // BigDecimal是安全的
      case _ =>
        throw new IllegalArgumentException(s"Unsupported parameter type: ${value.getClass.getSimpleName}")
    }
  }
  
  /**
   * 转义参数值
   */
  private def escapeParameterValue(value: Any): String = {
    value match {
      case null => "NULL"
      case s: String => 
        // 转义单引号并用单引号包围
        s"'${s.replace("'", "''")}'" 
      case i: Int => i.toString
      case l: Long => l.toString
      case d: Double => d.toString
      case f: Float => f.toString
      case b: Boolean => if (b) "TRUE" else "FALSE"
      case bd: BigDecimal => bd.toString
      case _ => 
        throw new IllegalArgumentException(s"Cannot escape parameter type: ${value.getClass.getSimpleName}")
    }
  }
  
  /**
   * 提取SQL中的参数名
   */
  def extractParameterNames(sql: String): Set[String] = {
    val colonParams = colonParameterPattern.findAllMatchIn(sql).map(_.group(1)).toSet
    val braceParams = braceParameterPattern.findAllMatchIn(sql).map(_.group(1)).toSet
    colonParams ++ braceParams
  }
  
  /**
   * 检查SQL是否包含参数
   */
  def hasParameters(sql: String): Boolean = {
    colonParameterPattern.findFirstIn(sql).isDefined || 
    braceParameterPattern.findFirstIn(sql).isDefined ||
    positionalParameterPattern.findFirstIn(sql).isDefined
  }
  
  /**
   * 验证参数完整性
   */
  def validateParameters(sql: String, parameters: Map[String, Any]): Try[Unit] = {
    Try {
      val requiredParams = extractParameterNames(sql)
      val providedParams = parameters.keySet
      
      val missingParams = requiredParams -- providedParams
      val extraParams = providedParams -- requiredParams
      
      if (missingParams.nonEmpty) {
        throw new IllegalArgumentException(s"Missing parameters: ${missingParams.mkString(", ")}")
      }
      
      if (extraParams.nonEmpty) {
        logger.warn(s"Extra parameters provided (will be ignored): ${extraParams.mkString(", ")}")
      }
      
      // 验证所有参数值
      parameters.values.foreach(validateParameterValue)
    }
  }
}

/**
 * 参数化查询构建器
 */
class QueryBuilder {
  private var sql: String = ""
  private var parameters: Map[String, Any] = Map.empty
  
  /**
   * 设置SQL语句
   */
  def setSql(sql: String): QueryBuilder = {
    this.sql = sql
    this
  }
  
  /**
   * 添加参数
   */
  def addParameter(name: String, value: Any): QueryBuilder = {
    parameters = parameters + (name -> value)
    this
  }
  
  /**
   * 添加多个参数
   */
  def addParameters(params: Map[String, Any]): QueryBuilder = {
    parameters = parameters ++ params
    this
  }
  
  /**
   * 构建查询
   */
  def build(): Try[String] = {
    if (sql.isEmpty) {
      Failure(new IllegalStateException("SQL not set"))
    } else {
      ParameterizedQuery.processQuery(sql, parameters)
    }
  }
  
  /**
   * 获取当前参数
   */
  def getParameters: Map[String, Any] = parameters
  
  /**
   * 清除参数
   */
  def clearParameters(): QueryBuilder = {
    parameters = Map.empty
    this
  }
}

object QueryBuilder {
  def apply(): QueryBuilder = new QueryBuilder()
  
  def apply(sql: String): QueryBuilder = new QueryBuilder().setSql(sql)
}
