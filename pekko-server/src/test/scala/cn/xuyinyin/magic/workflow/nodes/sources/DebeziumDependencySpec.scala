package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.testkit.STSpec

class DebeziumDependencySpec extends STSpec {
  "the CDC runtime" should {
    "preserve safe transient classification through the real completion adapter" in {
      import cn.xuyinyin.magic.workflow.engine.{CdcExecutionException,ExecutionFailure}
      import scala.concurrent.Await
      import scala.concurrent.duration._
      val access=new RealDebeziumEngineAccess(new java.util.Properties(), (_,callback) =>
        new io.debezium.engine.DebeziumEngine[io.debezium.engine.RecordChangeEvent[org.apache.kafka.connect.source.SourceRecord]] {
          def run():Unit=callback.handle(false,"secret",new IllegalStateException("secret",new java.sql.SQLRecoverableException("secret","08S01")))
          def close():Unit=()
        })
      val result=access.start(new DebeziumBatchConsumer {
        def handleBatch(r:Vector[org.apache.kafka.connect.source.SourceRecord],h:CdcBatchCommitHandle):Unit=()
      })
      try {
        val failure=Await.result(result.failed,3.seconds).asInstanceOf[CdcExecutionException]
        failure.failure.code shouldBe ExecutionFailure.Transient
        failure.toString should not include "secret"
        failure.getCause.getCause shouldBe null
      } finally access.close()
    }
    "load the embedded engine, MySQL connector, and JDBC stores" in {
      Class.forName("io.debezium.engine.DebeziumEngine") should not be null
      Class.forName("io.debezium.connector.mysql.MySqlConnector") should not be null
      Class.forName("io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore") should not be null
      Class.forName("io.debezium.storage.jdbc.history.JdbcSchemaHistory") should not be null
    }
  }
}
