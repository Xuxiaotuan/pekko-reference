package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.testkit.STSpec

class DebeziumDependencySpec extends STSpec {
  "the CDC runtime" should {
    "load the embedded engine, MySQL connector, and JDBC stores" in {
      Class.forName("io.debezium.engine.DebeziumEngine") should not be null
      Class.forName("io.debezium.connector.mysql.MySqlConnector") should not be null
      Class.forName("io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore") should not be null
      Class.forName("io.debezium.storage.jdbc.history.JdbcSchemaHistory") should not be null
    }
  }
}
