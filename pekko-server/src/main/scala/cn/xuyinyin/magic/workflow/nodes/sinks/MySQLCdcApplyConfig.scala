package cn.xuyinyin.magic.workflow.nodes.sinks

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.JdbcPasswordResolver
import spray.json._

final case class MySQLCdcApplyConfig(
  host: String,
  port: Int,
  database: String,
  table: String,
  username: String,
  password: String
) {
  override def toString: String =
    s"MySQLCdcApplyConfig($host,$port,$database,$table,$username,<redacted>)"
}

object MySQLCdcApplyConfig {
  private val Identifier = "[A-Za-z_][A-Za-z0-9_]*".r

  def parse(
    node: WorkflowDSL.Node,
    getenv: String => Option[String] = sys.env.get
  ): MySQLCdcApplyConfig = {
    val fields = node.config.fields
    if (fields.contains("password")) {
      throw new IllegalArgumentException("password is not supported for MySQL CDC apply; passwordEnv is required")
    }
    if (fields.contains("mode")) {
      throw new IllegalArgumentException("mode is not supported for MySQL CDC apply")
    }
    fromFields(fields, JdbcPasswordResolver.resolve(fields, getenv))
  }

  private[sinks] def parseTrustedRuntime(node: WorkflowDSL.Node): MySQLCdcApplyConfig = {
    val fields = node.config.fields
    if (fields.contains("passwordEnv")) {
      throw new IllegalArgumentException("trusted MySQL CDC apply runtime configuration must not contain passwordEnv")
    }
    if (fields.contains("mode")) {
      throw new IllegalArgumentException("mode is not supported for MySQL CDC apply")
    }
    fromFields(fields, requiredString(fields, "password"))
  }

  private def fromFields(fields: Map[String, JsValue], password: String): MySQLCdcApplyConfig =
    MySQLCdcApplyConfig(
      host = requiredString(fields, "host"),
      port = port(fields),
      database = identifier(fields, "database"),
      table = identifier(fields, "table"),
      username = requiredString(fields, "username"),
      password = password
    )

  private def requiredString(fields: Map[String, JsValue], key: String): String = fields.get(key) match {
    case Some(JsString(value)) if value.nonEmpty => value
    case Some(JsString(_)) => throw new IllegalArgumentException(s"$key must not be empty")
    case Some(_) => throw new IllegalArgumentException(s"$key must be a string")
    case None => throw new IllegalArgumentException(s"missing $key configuration")
  }

  private def identifier(fields: Map[String, JsValue], key: String): String = {
    val value = requiredString(fields, key)
    if (Identifier.pattern.matcher(value).matches()) value
    else throw new IllegalArgumentException(s"$key must be a valid identifier")
  }

  private def port(fields: Map[String, JsValue]): Int = fields.get("port") match {
    case Some(JsNumber(value)) if value.isValidInt && value.toInt >= 1 && value.toInt <= 65535 => value.toInt
    case Some(JsNumber(_)) => throw new IllegalArgumentException("port must be between 1 and 65535")
    case Some(_) => throw new IllegalArgumentException("port must be a number")
    case None => 3306
  }
}
