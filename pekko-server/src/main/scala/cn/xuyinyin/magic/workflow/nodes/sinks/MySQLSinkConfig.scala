package cn.xuyinyin.magic.workflow.nodes.sinks

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.JdbcPasswordResolver
import spray.json._

final case class MySQLSinkConfig(
  host: String,
  port: Int,
  database: String,
  table: String,
  username: String,
  password: String,
  batchSize: Int,
  mode: String
)

object MySQLSinkConfig {
  def parse(
    node: WorkflowDSL.Node,
    getenv: String => Option[String] = sys.env.get
  ): MySQLSinkConfig = {
    val fields = node.config.fields

    def getString(key: String, default: Option[String] = None): String =
      fields.get(key) match {
        case Some(JsString(value)) => value
        case None => default.getOrElse(throw new IllegalArgumentException(s"MySQL sink缺少${key}配置"))
        case _ => throw new IllegalArgumentException(s"${key}必须是字符串类型")
      }

    def getInt(key: String, default: Int): Int =
      fields.get(key) match {
        case Some(JsNumber(value)) => value.toInt
        case None => default
        case _ => throw new IllegalArgumentException(s"${key}必须是数字类型")
      }

    val config = MySQLSinkConfig(
      getString("host", Some("localhost")),
      getInt("port", 3306),
      getString("database"),
      getString("table"),
      getString("username"),
      JdbcPasswordResolver.resolve(fields, getenv),
      getInt("batchSize", 1000),
      getString("mode", Some("insert"))
    )
    require(config.batchSize > 0, "MySQL sink的batchSize必须大于0")
    config
  }
}
