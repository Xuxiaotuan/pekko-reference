package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import java.net.URI
import scala.concurrent.duration._
import spray.json._

sealed trait KafkaConnectionMode

final case class DirectKafkaConnection(bootstrapServers: String) extends KafkaConnectionMode

final case class GravitinoKafkaConnection(uri: URI, metalake: String, catalog: String) extends KafkaConnectionMode

sealed trait KafkaOffsetReset

object KafkaOffsetReset {
  case object Earliest extends KafkaOffsetReset
  case object Latest extends KafkaOffsetReset
}

final case class KafkaSourceConfig(
  topic: String,
  connection: KafkaConnectionMode,
  offsetReset: KafkaOffsetReset,
  chunkSize: Int,
  maxRecords: Long,
  maxDuration: FiniteDuration
)

object KafkaSourceConfig {
  def parse(node: WorkflowDSL.Node): KafkaSourceConfig = {
    val fields = node.config.fields
    rejectWorkflowAutoCommit(fields)

    KafkaSourceConfig(
      topic = requiredString(fields, "topic"),
      connection = connection(fields),
      offsetReset = offsetReset(fields),
      chunkSize = requiredPositiveInt(fields, "chunkSize"),
      maxRecords = requiredPositiveLong(fields, "maxRecords"),
      maxDuration = requiredPositiveLong(fields, "maxDurationSeconds").seconds
    )
  }

  private def rejectWorkflowAutoCommit(fields: Map[String, JsValue]): Unit =
    if (fields.contains("enable.auto.commit"))
      throw new IllegalArgumentException("enable.auto.commit must not be configured by a workflow")

  private def connection(fields: Map[String, JsValue]): KafkaConnectionMode = {
    val configuredModes = Seq("brokers", "gravitino").count(fields.contains)
    if (configuredModes != 1)
      throw new IllegalArgumentException("exactly one Kafka connection mode must be configured")

    fields.get("brokers") match {
      case Some(JsString(brokers)) => DirectKafkaConnection(trimmedRequiredString(brokers, "brokers"))
      case Some(_) => throw new IllegalArgumentException("brokers must be a string")
      case None => gravitinoConnection(fields("gravitino"))
    }
  }

  private def gravitinoConnection(value: JsValue): GravitinoKafkaConnection = value match {
    case JsObject(fields) =>
      GravitinoKafkaConnection(
        URI.create(requiredString(fields, "uri")),
        requiredString(fields, "metalake"),
        requiredString(fields, "catalog")
      )
    case _ => throw new IllegalArgumentException("gravitino must be an object")
  }

  private def offsetReset(fields: Map[String, JsValue]): KafkaOffsetReset = fields.get("offsetReset") match {
    case None => KafkaOffsetReset.Earliest
    case Some(JsString("earliest")) => KafkaOffsetReset.Earliest
    case Some(JsString("latest")) => KafkaOffsetReset.Latest
    case _ => throw new IllegalArgumentException("offsetReset must be earliest or latest")
  }

  private def requiredString(fields: Map[String, JsValue], key: String): String = fields.get(key) match {
    case Some(JsString(value)) => trimmedRequiredString(value, key)
    case Some(_) => throw new IllegalArgumentException(s"$key must be a string")
    case None => throw new IllegalArgumentException(s"missing $key configuration")
  }

  private def trimmedRequiredString(value: String, key: String): String = {
    val trimmed = value.trim
    if (trimmed.nonEmpty) trimmed
    else throw new IllegalArgumentException(s"$key must not be empty")
  }

  private def requiredPositiveInt(fields: Map[String, JsValue], key: String): Int = fields.get(key) match {
    case Some(JsNumber(value)) if value.isValidInt && value.toInt > 0 => value.toInt
    case Some(JsNumber(_)) => throw new IllegalArgumentException(s"$key must be a positive integer")
    case Some(_) => throw new IllegalArgumentException(s"$key must be a number")
    case None => throw new IllegalArgumentException(s"missing $key configuration")
  }

  private def requiredPositiveLong(fields: Map[String, JsValue], key: String): Long = fields.get(key) match {
    case Some(JsNumber(value)) if value.isValidLong && value.toLong > 0 => value.toLong
    case Some(JsNumber(_)) => throw new IllegalArgumentException(s"$key must be a positive long")
    case Some(_) => throw new IllegalArgumentException(s"$key must be a number")
    case None => throw new IllegalArgumentException(s"missing $key configuration")
  }
}
