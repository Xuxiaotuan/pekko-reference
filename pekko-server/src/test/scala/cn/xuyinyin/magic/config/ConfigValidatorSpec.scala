package cn.xuyinyin.magic.config

import cn.xuyinyin.magic.testkit.STSpec
import cn.xuyinyin.magic.workflow.nodes.sources.MySQLCdcStateConfig
import com.typesafe.config.{Config, ConfigFactory}

class ConfigValidatorSpec extends STSpec {
  private val secret = "state-password-should-not-leak"
  private val validState =
    s"""
       |url = "jdbc:mysql://mysql:3306/pekko_workflow"
       |username = "pekko_workflow"
       |password = "$secret"
       |offset-table = "debezium_offset_storage"
       |history-table = "debezium_database_history"
       |offset-flush-interval-ms = 0
       |""".stripMargin

  private def cdcOnlyConfig(state: String): Config =
    ConfigFactory.parseString(
      s"""
         |pekko.workflow.mysql-cdc {
         |  enabled = true
         |  state-jdbc {
         |    $state
         |  }
         |}
         |""".stripMargin
    ).resolve()

  private def enabledConfig(state: String): Config =
    cdcOnlyConfig(state).withFallback(ConfigFactory.load()).resolve()

  private def validationErrors(state: String): List[String] =
    ConfigValidator.validate(cdcOnlyConfig(state)).getOrElse(fail("expected CDC validation errors"))

  private def assertRedacted(state: String): List[String] = {
    val errors = validationErrors(state)
    errors.mkString("; ") should not include secret
    errors
  }

  "MySQL CDC state configuration" should {
    "load the durable MySQL state settings" in {
      MySQLCdcStateConfig.load(enabledConfig(validState)) shouldBe MySQLCdcStateConfig(
        jdbcUrl = "jdbc:mysql://mysql:3306/pekko_workflow",
        username = "pekko_workflow",
        password = secret,
        offsetTable = "debezium_offset_storage",
        historyTable = "debezium_database_history",
        offsetFlushIntervalMillis = 0
      )
    }

    "leave legacy configurations valid when CDC is disabled" in {
      ConfigValidator.validate(ConfigFactory.load()) shouldBe None
    }

    "reject a missing or blank JDBC URL without exposing the password" in {
      assertRedacted(validState.replace("url = \"jdbc:mysql://mysql:3306/pekko_workflow\"", ""))
        .mkString("; ") should include("url")
      assertRedacted(validState.replace("url = \"jdbc:mysql://mysql:3306/pekko_workflow\"", "url = \" \""))
        .mkString("; ") should include("url")
    }

    "reject a non-JDBC URL without exposing the password" in {
      assertRedacted(validState.replace("jdbc:mysql://mysql:3306/pekko_workflow", "mysql://mysql/pekko_workflow"))
        .mkString("; ") should include("jdbc:")
    }

    "reject missing state credentials without exposing the password" in {
      assertRedacted(validState.replace("username = \"pekko_workflow\"", "username = \"\""))
        .mkString("; ") should include("username")
      assertRedacted(validState.replace(s"password = \"$secret\"", "password = \"\""))
        .mkString("; ") should include("password")
    }

    "reject unsafe state table names without exposing the password" in {
      assertRedacted(validState.replace("debezium_offset_storage", "offsets; drop table x"))
        .mkString("; ") should include("offset-table")
      assertRedacted(validState.replace("debezium_database_history", "history-table.invalid"))
        .mkString("; ") should include("history-table")
    }

    "reject a negative offset flush interval without exposing the password" in {
      assertRedacted(validState.replace("offset-flush-interval-ms = 0", "offset-flush-interval-ms = -1"))
        .mkString("; ") should include("offset-flush-interval-ms")
    }

    "reject an H2 state URL when CDC is enabled without exposing the password" in {
      assertRedacted(validState.replace("jdbc:mysql://mysql:3306/pekko_workflow", "jdbc:h2:file:target/cdc-state"))
        .mkString("; ") should include("jdbc:mysql:")
    }
  }
}
