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
class FilteredMarkerTransactionProbeSpec extends AnyWordSpec with Matchers {
  "a control marker with a same-transaction directory write" should {
    "record actual Debezium transaction metadata and Reader outcome" taggedAs ExternalIntegration in {
      if (!sys.env.get("RUN_FILTERED_MARKER_TRANSACTION_PROBE").contains("1")) cancel("private dual MySQL fixture required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("marker-probe"))
      val user=sys.env("CDC_WINDOW_MYSQL_USER"); val password=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      val sourceUrl=sys.env("CAPTURE_TEST_URL"); val stateUrl=sys.env("CAPTURE_STATE_URL")
      val port=sys.env("CDC_WINDOW_MYSQL_PORT").toInt
      require(port != 3306 && sourceUrl.contains("pekko_test_shared_wire"))
      val evidence=Path.of(sys.env("FILTERED_MARKER_PROBE_EVIDENCE")); Files.createDirectories(evidence)
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
      val observed=new CaptureObservedFactory(emit)
      val factory=new DebeziumEngineFactory {
        def create(properties:java.util.Properties):DebeziumEngineAccess={
          properties.setProperty("table.include.list",java.util.regex.Pattern.quote(source.database)+"\\.(?!pekko_marker_directory$).*")
          observed.create(properties)
        }
      }
      val reader=new SharedMySQLCapture(source,state,store,factory,emit)
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
        withClue("filtered marker should bind"){store.subscription("triggered").get.status shouldBe "CAPTURE_READY"}
        reader.failure shouldBe empty
        sql(s"INSERT INTO $table VALUES(1,'first')")
        sql(s"INSERT INTO $other VALUES(2,'second')")
        await("business changes from both tables"){store.read("baseline",0,100).nonEmpty && store.read("triggered",0,100).nonEmpty}
        val observed=events.iterator().asScala.toVector
        val endRecords=observed.filter(e=>e.fields.get("kind").contains(JsString("ORIGINAL_RECORD")) &&
          e.fields.get("schema").exists(_.toString.contains("TransactionMetadataValue")) && e.fields.get("data").exists(_.toString.contains("END")))
        val output=JsObject("baseline"->JsString(store.subscription("baseline").get.status),
          "triggered"->JsString(store.subscription("triggered").map(_.status).getOrElse("missing")),
          "readerFailure"->reader.failure.map(e=>JsString(e.toString)).getOrElse(JsNull),
          "baselineGroups"->JsNumber(store.read("baseline",0,100).size),
          "triggeredGroups"->JsNumber(store.read("triggered",0,100).size),
          "transactionEndRecords"->JsArray(endRecords))
        Files.writeString(evidence.resolve("result.json"),output.prettyPrint)
        Files.writeString(evidence.resolve("events.jsonl"),observed.map(_.compactPrint).mkString("","\n","\n"))
        withClue("triggered transaction reached Reader or failed"){reader.failure.nonEmpty || store.subscription("triggered").exists(_.status=="CAPTURE_READY") shouldBe true}
        endRecords.nonEmpty shouldBe true
        val markerEnd=endRecords.filter(e=>e.fields("data").asJsObject.fields("data_collections").toString.contains("pekko_capture_control")).last
        markerEnd.fields("data").asJsObject.fields("event_count") shouldBe JsNumber(1)
        val directoryRecords=observed.filter(e=>e.fields.get("kind").contains(JsString("ORIGINAL_RECORD")) &&
          e.fields.get("data").exists { case data:JsObject => data.fields.get("source").exists {
            case src:JsObject => src.fields.get("table").contains(JsString("pekko_marker_directory")) && data.fields.get("op").contains(JsString("c"))
            case _ => false
          }; case _ => false })
        directoryRecords shouldBe empty
      } finally reader.close()
    }
  }
}
