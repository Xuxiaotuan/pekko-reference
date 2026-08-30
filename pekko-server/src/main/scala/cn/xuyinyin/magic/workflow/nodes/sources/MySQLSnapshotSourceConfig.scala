package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import spray.json._

final case class MySQLSnapshotSourceConfig(
  host: String,
  port: Int,
  database: String,
  username: String,
  password: String,
  table: String,
  columns: Vector[String],
  primaryKey: String,
  chunkSize: Int
)

object MySQLSnapshotSourceConfig {
  private val Identifier = "[A-Za-z_][A-Za-z0-9_]*".r

  def parse(node: WorkflowDSL.Node): MySQLSnapshotSourceConfig = {
    val fields = node.config.fields
    val table = identifier(fields, "table")
    val columns = identifiers(fields, "columns")
    val primaryKey = identifier(fields, "primaryKey")

    MySQLSnapshotSourceConfig(
      host = optionalString(fields, "host", "localhost"),
      port = optionalPositiveInt(fields, "port", 3306),
      database = requiredString(fields, "database"),
      username = requiredString(fields, "username"),
      password = requiredString(fields, "password"),
      table = table,
      columns = columns,
      primaryKey = primaryKey,
      chunkSize = requiredPositiveInt(fields, "chunkSize")
    )
  }

  private def requiredString(fields: Map[String, JsValue], key: String): String =
    fields.get(key) match {
      case Some(JsString(value)) if value.nonEmpty => value
      case Some(JsString(_)) => throw new IllegalArgumentException(s"$key must not be empty")
      case Some(_) => throw new IllegalArgumentException(s"$key must be a string")
      case None => throw new IllegalArgumentException(s"missing $key configuration")
    }

  private def optionalString(fields: Map[String, JsValue], key: String, default: String): String =
    fields.get(key) match {
      case Some(JsString(value)) if value.nonEmpty => value
      case Some(JsString(_)) => throw new IllegalArgumentException(s"$key must not be empty")
      case Some(_) => throw new IllegalArgumentException(s"$key must be a string")
      case None => default
    }

  private def requiredPositiveInt(fields: Map[String, JsValue], key: String): Int =
    fields.get(key) match {
      case Some(JsNumber(value)) if value.isValidInt && value.toInt > 0 => value.toInt
      case Some(JsNumber(_)) => throw new IllegalArgumentException(s"$key must be a positive integer")
      case Some(_) => throw new IllegalArgumentException(s"$key must be a number")
      case None => throw new IllegalArgumentException(s"missing $key configuration")
    }

  private def optionalPositiveInt(fields: Map[String, JsValue], key: String, default: Int): Int =
    fields.get(key) match {
      case None => default
      case _ => requiredPositiveInt(fields, key)
    }

  private def identifier(fields: Map[String, JsValue], key: String): String = {
    val value = requiredString(fields, key)
    if (Identifier.pattern.matcher(value).matches()) value
    else throw new IllegalArgumentException(s"$key must be a valid identifier")
  }

  private def identifiers(fields: Map[String, JsValue], key: String): Vector[String] =
    fields.get(key) match {
      case Some(JsArray(values)) if values.nonEmpty =>
        values.toVector.map {
          case JsString(value) if Identifier.pattern.matcher(value).matches() => value
          case JsString(_) => throw new IllegalArgumentException(s"$key must contain valid identifiers")
          case _ => throw new IllegalArgumentException(s"$key must contain strings")
        }
      case Some(JsArray(_)) => throw new IllegalArgumentException(s"$key must not be empty")
      case Some(_) => throw new IllegalArgumentException(s"$key must be an array")
      case None => throw new IllegalArgumentException(s"missing $key configuration")
    }
}
