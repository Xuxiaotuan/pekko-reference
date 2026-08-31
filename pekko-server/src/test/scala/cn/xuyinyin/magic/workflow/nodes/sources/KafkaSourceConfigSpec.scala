package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.testkit.STSpec
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import java.net.URI
import spray.json._
import scala.concurrent.duration._

class KafkaSourceConfigSpec extends STSpec {
  private val validGravitino = JsObject(
    "uri" -> JsString("http://gravitino:8090"),
    "metalake" -> JsString("pekko"),
    "catalog" -> JsString("bigdata-kafka")
  )
  private val required = Map[String, JsValue](
    "topic" -> JsString("events"),
    "chunkSize" -> JsNumber(10),
    "maxRecords" -> JsNumber(50),
    "maxDurationSeconds" -> JsNumber(120)
  )
  private def node(entries: (String, JsValue)*): WorkflowDSL.Node =
    WorkflowDSL.Node("source-1", "source", "kafka.consumer", "Kafka", WorkflowDSL.Position(0, 0), JsObject(required ++ entries))
  private def validNode(entries: (String, JsValue)*): WorkflowDSL.Node =
    node((Seq("brokers" -> JsString("kafka:9092")) ++ entries): _*)

  "KafkaSourceConfig" should {
    "parse direct and Gravitino modes" in {
      KafkaSourceConfig.parse(node("brokers" -> JsString("kafka:9092"))).connection shouldBe
        DirectKafkaConnection("kafka:9092")

      KafkaSourceConfig.parse(node("gravitino" -> JsObject(
        "uri" -> JsString("http://gravitino:8090"),
        "metalake" -> JsString("pekko"),
        "catalog" -> JsString("bigdata-kafka")
      ))).connection shouldBe
        GravitinoKafkaConnection(URI.create("http://gravitino:8090"), "pekko", "bigdata-kafka")
    }

    "reject zero or multiple connection modes and non-positive limits" in {
      intercept[IllegalArgumentException](KafkaSourceConfig.parse(node())).getMessage should include("exactly one")
      intercept[IllegalArgumentException](KafkaSourceConfig.parse(node(
        "brokers" -> JsString("kafka:9092"),
        "gravitino" -> validGravitino
      ))).getMessage should include("exactly one")
      intercept[IllegalArgumentException](KafkaSourceConfig.parse(validNode("chunkSize" -> JsNumber(0))))
        .getMessage should include("chunkSize")
      intercept[IllegalArgumentException](KafkaSourceConfig.parse(validNode("maxRecords" -> JsNumber(0))))
        .getMessage should include("maxRecords")
      intercept[IllegalArgumentException](KafkaSourceConfig.parse(validNode("maxDurationSeconds" -> JsNumber(0))))
        .getMessage should include("maxDurationSeconds")
    }

    "default offset reset to earliest and reject unknown policies" in {
      KafkaSourceConfig.parse(validNode()).offsetReset shouldBe KafkaOffsetReset.Earliest
      intercept[IllegalArgumentException](KafkaSourceConfig.parse(validNode("offsetReset" -> JsString("middle"))))
        .getMessage should include("offsetReset")
    }

    "trim required connection strings" in {
      KafkaSourceConfig.parse(node(
        "topic" -> JsString(" events "),
        "brokers" -> JsString(" kafka:9092 ")
      )) shouldBe KafkaSourceConfig(
        "events",
        DirectKafkaConnection("kafka:9092"),
        KafkaOffsetReset.Earliest,
        10,
        50,
        120.seconds
      )
    }

    "reject workflow auto-commit configuration" in {
      intercept[IllegalArgumentException](KafkaSourceConfig.parse(validNode("enable.auto.commit" -> JsBoolean(false))))
        .getMessage should include("enable.auto.commit")
    }
  }
}
