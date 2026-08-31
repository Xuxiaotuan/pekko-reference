package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.JdbcPasswordResolver
import spray.json._

final case class MySQLCdcSourceConfig(
  connectorId: String,
  host: String,
  port: Int,
  database: String,
  table: String,
  username: String,
  password: String,
  serverId: Long,
  maxBatchSize: Int,
  pollIntervalMillis: Int
)

object MySQLCdcSourceConfig {
  private val Identifier = "[A-Za-z_][A-Za-z0-9_]*".r
  private val ConnectorId = "[A-Za-z_][A-Za-z0-9_-]*".r
  private val MaxServerId = 4294967295L

  def parse(
    node: WorkflowDSL.Node,
    getenv: String => Option[String] = sys.env.get
  ): MySQLCdcSourceConfig = {
    val fields = node.config.fields
    if (fields.contains("password")) {
      throw new IllegalArgumentException("password is not supported for MySQL CDC; passwordEnv is required")
    }
    fromFields(fields, JdbcPasswordResolver.resolve(fields, getenv))
  }

  private[sources] def parseTrustedRuntime(node: WorkflowDSL.Node): MySQLCdcSourceConfig = {
    val fields = node.config.fields
    if (fields.contains("passwordEnv")) {
      throw new IllegalArgumentException("trusted MySQL CDC runtime configuration must not contain passwordEnv")
    }
    fromFields(fields, requiredString(fields, "password"))
  }

  private def fromFields(fields: Map[String, JsValue], password: String): MySQLCdcSourceConfig =
    MySQLCdcSourceConfig(
      connectorId = connectorId(fields),
      host = requiredString(fields, "host"),
      port = requiredPositiveInt(fields, "port"),
      database = identifier(fields, "database"),
      table = identifier(fields, "table"),
      username = requiredString(fields, "username"),
      password = password,
      serverId = serverId(fields),
      maxBatchSize = requiredPositiveInt(fields, "maxBatchSize"),
      pollIntervalMillis = requiredPositiveInt(fields, "pollIntervalMillis")
    )

  private def requiredString(fields: Map[String, JsValue], key: String): String =
    fields.get(key) match {
      case Some(JsString(value)) if value.nonEmpty => value
      case Some(JsString(_)) => throw new IllegalArgumentException(s"$key must not be empty")
      case Some(_) => throw new IllegalArgumentException(s"$key must be a string")
      case None => throw new IllegalArgumentException(s"missing $key configuration")
    }

  private def requiredPositiveInt(fields: Map[String, JsValue], key: String): Int =
    fields.get(key) match {
      case Some(JsNumber(value)) if value.isValidInt && value.toInt > 0 => value.toInt
      case Some(JsNumber(_)) => throw new IllegalArgumentException(s"$key must be a positive integer")
      case Some(_) => throw new IllegalArgumentException(s"$key must be a number")
      case None => throw new IllegalArgumentException(s"missing $key configuration")
    }

  private def connectorId(fields: Map[String, JsValue]): String = {
    val value = requiredString(fields, "connectorId")
    if (ConnectorId.pattern.matcher(value).matches()) value
    else throw new IllegalArgumentException("connectorId must contain only letters, digits, underscores, and hyphens")
  }

  private def identifier(fields: Map[String, JsValue], key: String): String = {
    val value = requiredString(fields, key)
    if (Identifier.pattern.matcher(value).matches()) value
    else throw new IllegalArgumentException(s"$key must be a valid identifier")
  }

  private def serverId(fields: Map[String, JsValue]): Long =
    fields.get("serverId") match {
      case Some(JsNumber(value)) if value.isValidLong && value.toLong > 0 && value.toLong <= MaxServerId => value.toLong
      case Some(JsNumber(_)) => throw new IllegalArgumentException(s"serverId must be between 1 and $MaxServerId")
      case Some(_) => throw new IllegalArgumentException("serverId must be a number")
      case None => throw new IllegalArgumentException("missing serverId configuration")
    }
}
