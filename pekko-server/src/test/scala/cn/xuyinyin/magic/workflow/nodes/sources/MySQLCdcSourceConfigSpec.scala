package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.testkit.STSpec
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import spray.json._

class MySQLCdcSourceConfigSpec extends STSpec {
  private val secret = "source-password-should-not-leak"
  private val required = Map[String, JsValue](
    "connectorId" -> JsString("orders-cdc-v1"),
    "host" -> JsString("mysql"),
    "port" -> JsNumber(3306),
    "database" -> JsString("pekko_workflow"),
    "table" -> JsString("source_orders"),
    "username" -> JsString("pekko_cdc"),
    "passwordEnv" -> JsString("MYSQL_CDC_PASSWORD"),
    "serverId" -> JsNumber(54001),
    "maxBatchSize" -> JsNumber(100),
    "pollIntervalMillis" -> JsNumber(500)
  )

  private def node(entries: (String, JsValue)*): WorkflowDSL.Node =
    WorkflowDSL.Node(
      "source-1",
      "source",
      "mysql.cdc",
      "MySQL CDC",
      WorkflowDSL.Position(0, 0),
      JsObject(required ++ entries)
    )

  private def parse(entries: (String, JsValue)*): MySQLCdcSourceConfig =
    MySQLCdcSourceConfig.parse(node(entries: _*), name => Option.when(name == "MYSQL_CDC_PASSWORD")(secret))

  private def assertRedacted(entries: (String, JsValue)*): IllegalArgumentException = {
    val exception = intercept[IllegalArgumentException](parse(entries: _*))
    exception.getMessage should not include secret
    exception
  }

  "MySQLCdcSourceConfig" should {
    "parse a complete CDC source node" in {
      parse() shouldBe MySQLCdcSourceConfig(
        connectorId = "orders-cdc-v1",
        host = "mysql",
        port = 3306,
        database = "pekko_workflow",
        table = "source_orders",
        username = "pekko_cdc",
        password = secret,
        serverId = 54001L,
        maxBatchSize = 100,
        pollIntervalMillis = 500
      )
    }

    "reject a missing connector ID without exposing the resolved password" in {
      assertRedacted("connectorId" -> JsString("")) .getMessage should include("connectorId")
    }

    "reject unsafe SQL identifiers without exposing the resolved password" in {
      assertRedacted("database" -> JsString("pekko-workflow")).getMessage should include("database")
      assertRedacted("table" -> JsString("source_orders; drop table x")).getMessage should include("table")
    }

    "reject an invalid connector ID without exposing the resolved password" in {
      assertRedacted("connectorId" -> JsString("orders cdc")).getMessage should include("connectorId")
    }

    "reject an absent password environment value without exposing the resolved password" in {
      val exception = intercept[IllegalArgumentException](
        MySQLCdcSourceConfig.parse(node(), _ => None)
      )
      exception.getMessage should include("passwordEnv")
      exception.getMessage should not include secret
    }

    "reject an inline password without exposing it" in {
      val inlineSecret = "inline-password-should-not-leak"
      val inlineOnlyNode = WorkflowDSL.Node(
        "source-1",
        "source",
        "mysql.cdc",
        "MySQL CDC",
        WorkflowDSL.Position(0, 0),
        JsObject((required - "passwordEnv") + ("password" -> JsString(inlineSecret)))
      )

      val exception = intercept[IllegalArgumentException](
        MySQLCdcSourceConfig.parse(inlineOnlyNode, _ => Some(secret))
      )
      exception.getMessage should include("passwordEnv")
      exception.getMessage should not include inlineSecret
      exception.getMessage should not include secret
    }

    "reject simultaneous password sources without exposing the resolved password" in {
      assertRedacted("password" -> JsString(secret)).getMessage should include("passwordEnv")
    }

    "parse an engine-prepared password only through the trusted runtime entry point" in {
      val runtimeNode = node().copy(config = JsObject(
        (node().config.fields - "passwordEnv") + ("password" -> JsString(secret))
      ))

      val parsed = MySQLCdcSourceConfig.parseTrustedRuntime(runtimeNode)

      parsed.password shouldBe secret
      intercept[IllegalArgumentException](MySQLCdcSourceConfig.parse(runtimeNode)).getMessage should include("passwordEnv")
    }

    "reject server IDs outside the MySQL unsigned integer range without exposing the resolved password" in {
      assertRedacted("serverId" -> JsNumber(0)).getMessage should include("serverId")
      assertRedacted("serverId" -> JsNumber(4294967296L)).getMessage should include("serverId")
    }

    "reject non-positive batch size without exposing the resolved password" in {
      assertRedacted("maxBatchSize" -> JsNumber(0)).getMessage should include("maxBatchSize")
    }

    "reject non-positive poll interval without exposing the resolved password" in {
      assertRedacted("pollIntervalMillis" -> JsNumber(0)).getMessage should include("pollIntervalMillis")
    }
  }
}
