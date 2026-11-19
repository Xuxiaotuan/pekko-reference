package cn.xuyinyin.magic.connectors.mysql.source

import org.apache.kafka.connect.source.SourceRecord
import java.nio.file.{Files, Path, StandardOpenOption}
import java.sql.DriverManager
import scala.jdk.CollectionConverters._

/** Disposable-database protocol observation only; not a capture implementation. */
object SharedCaptureWireProbe {
  def main(args: Array[String]): Unit = {
    require(sys.env.get("RUN_SHARED_CAPTURE_WIRE").contains("1"))
    val url = sys.env("CAPTURE_TEST_URL")
    val user = sys.env("CDC_WINDOW_MYSQL_USER"); val password = sys.env("CDC_WINDOW_MYSQL_PASSWORD")
    val schema = "pekko_test_shared_wire"
    val state = MySQLCdcStateConfig(sys.env("CAPTURE_STATE_URL"),user,password,"wire_offsets","wire_history",0)
    val source = MySQLCdcSourceConfig("shared_wire", "127.0.0.1",13365,schema,"rows",user,password,310001,1,100)
    val props = MySQLCdcSourceNode.connectorProperties(source,state)
    props.setProperty("snapshot.mode","no_data"); props.setProperty("table.include.list",s"$schema\\..*")
    props.setProperty("include.schema.changes","true"); props.setProperty("provide.transaction.metadata","true")
    val out=Path.of("target/shared-wire.jsonl"); Files.createDirectories(out.getParent)
    val engine=DebeziumEngineFactory.real.create(props)
    engine.start(new DebeziumBatchConsumer {
      def handleBatch(records:Vector[SourceRecord],h:CdcBatchCommitHandle):Unit={
        records.foreach { r => Files.writeString(out,s"schema=${r.valueSchema()} offset=${r.sourceOffset()} value=${r.value()}\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND) }
        h.markProcessedAndFinished()
      }
    })
    try {
      Thread.sleep(5000)
      val c=DriverManager.getConnection(url,user,password)
      try {val q=c.createStatement();try {
        q.executeUpdate("INSERT INTO pekko_capture_control VALUES ('wire-2','SUBSCRIBE','rows','g1')")
        q.executeUpdate("INSERT INTO `rows` VALUES (1,'first')")
        q.executeUpdate("UPDATE `rows` SET id=2 WHERE id=1")
      } finally q.close()} finally c.close()
      Thread.sleep(5000)
    } finally engine.close()
  }
}
