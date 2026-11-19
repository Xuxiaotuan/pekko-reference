package cn.xuyinyin.magic.connectors.mysql.source

import cn.xuyinyin.magic.connectors.mysql.source.capture._
import cn.xuyinyin.magic.tags.ExternalIntegration
import java.nio.file.{Files, Path}
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import spray.json._

/** Real Debezium and production Reader; the trigger exists only in a private test schema. */
class MarkerTransactionProbeSpec extends AnyWordSpec with Matchers {
  "a control marker with a same-transaction directory write" should {
    "record actual Debezium transaction metadata and Reader outcome" taggedAs ExternalIntegration in {
      if (!sys.env.get("RUN_MARKER_TRANSACTION_PROBE").contains("1")) cancel("private dual MySQL fixture required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("marker-probe"))
      val user=sys.env("CDC_WINDOW_MYSQL_USER"); val password=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      val sourceUrl=sys.env("CAPTURE_TEST_URL"); val stateUrl=sys.env("CAPTURE_STATE_URL")
      val port=sys.env("CDC_WINDOW_MYSQL_PORT").toInt
      require(port != 3306 && sourceUrl.contains("pekko_test_shared_wire"))
      val evidence=Path.of(sys.env("MARKER_PROBE_EVIDENCE")); Files.createDirectories(evidence)
      val events=new ConcurrentLinkedQueue[JsObject]()
      def emit(kind:String, value:JsObject):Unit=events.add(JsObject(value.fields+("kind"->JsString(kind))))
      def sql(query:String):Unit={val c=DriverManager.getConnection(sourceUrl,user,password)
        try {val s=c.createStatement(); try {s.execute(query);()} finally s.close()} finally c.close()}
      val suffix=UUID.randomUUID().toString.replace("-","").take(10)
      val id="marker_"+suffix; val table="orders_"+suffix; val other="other_"+suffix
      sql(s"CREATE TABLE $table(id BIGINT PRIMARY KEY,label VARCHAR(64))")
      sql(s"CREATE TABLE $other(id BIGINT PRIMARY KEY,label VARCHAR(64))")
      val c=DriverManager.getConnection(sourceUrl,user,password)
      val lineage=try {val s=c.createStatement(); try {val r=s.executeQuery("SELECT @@server_uuid");try {r.next();r.getString(1)}finally r.close()}finally s.close()}finally c.close()
      val store=new MySQLCaptureStore(()=>DriverManager.getConnection(stateUrl,user,password),id,lineage,10000000)
      store.initialize()
      val source=MySQLCdcSourceConfig(id,"127.0.0.1",port,"pekko_test_shared_wire",table,user,password,310002,1,100)
      val state=MySQLCdcStateConfig(stateUrl,user,password,"capture_offsets","capture_history",0)
      val reader=new SharedMySQLCapture(source,state,store,new CaptureObservedFactory(emit),emit)
      def await(label:String)(done: => Boolean):Unit={val deadline=System.nanoTime()+45.seconds.toNanos
        while(!done && reader.failure.isEmpty && System.nanoTime()<deadline) Thread.sleep(20)
        withClue(label){done shouldBe true}}
      try {
        reader.start()
        await("schema and admission"){store.schema(table).nonEmpty && store.schema(other).nonEmpty && reader.startupEvidence.phase=="ADMISSION_READY"}
        reader.subscribe("baseline",table,"g1")
        await("baseline B"){store.subscription("baseline").exists(_.status=="CAPTURE_READY")}
        sql("CREATE TABLE pekko_marker_directory(operation_id VARCHAR(128) PRIMARY KEY) ENGINE=InnoDB")
        sql("CREATE TRIGGER marker_directory AFTER INSERT ON pekko_capture_control FOR EACH ROW INSERT INTO pekko_marker_directory VALUES(NEW.operation_id)")
        reader.subscribe("triggered",other,"g2")
        val deadline=System.nanoTime()+45.seconds.toNanos
        while(reader.failure.isEmpty && !store.subscription("triggered").exists(_.status=="CAPTURE_READY") && System.nanoTime()<deadline) Thread.sleep(20)
        val observed=events.iterator().asScala.toVector
        val endRecords=observed.filter(e=>e.fields.get("kind").contains(JsString("ORIGINAL_RECORD")) &&
          e.fields.get("schema").exists(_.toString.contains("TransactionMetadataValue")) && e.fields.get("data").exists(_.toString.contains("END")))
        val output=JsObject("baseline"->JsString(store.subscription("baseline").get.status),
          "triggered"->JsString(store.subscription("triggered").map(_.status).getOrElse("missing")),
          "readerFailure"->reader.failure.map(e=>JsString(e.toString)).getOrElse(JsNull),
          "transactionEndRecords"->JsArray(endRecords))
        Files.writeString(evidence.resolve("result.json"),output.prettyPrint)
        Files.writeString(evidence.resolve("events.jsonl"),observed.map(_.compactPrint).mkString("","\n","\n"))
        withClue("triggered transaction reached Reader or failed"){reader.failure.nonEmpty || store.subscription("triggered").exists(_.status=="CAPTURE_READY") shouldBe true}
        endRecords.nonEmpty shouldBe true
      } finally reader.close()
    }
  }
}
