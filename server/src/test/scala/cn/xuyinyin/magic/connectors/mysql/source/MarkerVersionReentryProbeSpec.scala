package cn.xuyinyin.magic.connectors.mysql.source

import cn.xuyinyin.magic.connectors.mysql.source.capture._
import cn.xuyinyin.magic.tags.ExternalIntegration
import java.nio.file.{Files,Path}
import java.sql.{Connection,DriverManager}
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import spray.json._

/** Reproduces the production Reader opening a second source connection for marker writes. */
class MarkerVersionReentryProbeSpec extends AnyWordSpec with Matchers {
  "the existing Reader marker write" should {
    "block when another connection holds the trigger's version row lock" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_MARKER_VERSION_REENTRY").contains("1"))cancel("private dual MySQL fixture required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("marker-version-reentry"))
      val user=sys.env("CDC_WINDOW_MYSQL_USER");val password=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      val src=sys.env("CAPTURE_TEST_URL");val dst=sys.env("CAPTURE_STATE_URL");val port=sys.env("CDC_WINDOW_MYSQL_PORT").toInt
      require(port!=3306 && src.contains("pekko_test_shared_wire") && dst.contains("pekko_test_shared_state"))
      def open(url:String)=DriverManager.getConnection(url,user,password)
      def execute(c:Connection,sql:String):Unit={val q=c.createStatement();try {q.execute(sql);()}finally q.close()}
      def sql(sql:String):Unit={val c=open(src);try execute(c,sql)finally c.close()}
      def scalar(c:Connection,sql:String):String={val q=c.createStatement();try {val r=q.executeQuery(sql);try {require(r.next());r.getString(1)}finally r.close()}finally q.close()}
      val suffix=UUID.randomUUID().toString.replace("-","").take(10);val capture="reentry_"+suffix;val table="orders_"+suffix
      sql(s"CREATE TABLE $table(id BIGINT PRIMARY KEY,label VARCHAR(64))")
      val sourceConnection=open(src);val lineage=try scalar(sourceConnection,"SELECT @@server_uuid")finally sourceConnection.close()
      val store=new MySQLCaptureStore(()=>open(dst),capture,lineage,10000000);store.initialize()
      val source=MySQLCdcSourceConfig(capture,"127.0.0.1",port,"pekko_test_shared_wire",table,user,password,319002,1,100)
      val state=MySQLCdcStateConfig(dst,user,password,"capture_offsets","capture_history",0)
      val events=new ConcurrentLinkedQueue[JsObject]()
      def emit(kind:String,value:JsObject):Unit=events.add(JsObject(value.fields+("kind"->JsString(kind))))
      val observed=new CaptureObservedFactory(emit)
      val factory=new DebeziumEngineFactory {def create(p:java.util.Properties):DebeziumEngineAccess={
        p.setProperty("table.include.list",java.util.regex.Pattern.quote(source.database)+"\\.(?!pekko_reentry_version$).*");observed.create(p)}}
      val reader=new SharedMySQLCapture(source,state,store,factory,emit){
        override protected def sourceConnection():Connection={val c=super.sourceConnection();execute(c,"SET SESSION innodb_lock_wait_timeout=1");c}
      }
      def await(label:String)(ok: => Boolean):Unit={val end=System.nanoTime()+45.seconds.toNanos
        while(!ok && reader.failure.isEmpty && System.nanoTime()<end)Thread.sleep(20)
        withClue(label){ok shouldBe true}}
      val held=open(src)
      try {
        reader.start();await("admission"){store.schema(table).nonEmpty && reader.startupEvidence.phase=="ADMISSION_READY"}
        sql("CREATE TABLE pekko_reentry_version(id INT PRIMARY KEY,version BIGINT NOT NULL) ENGINE=InnoDB")
        sql("INSERT INTO pekko_reentry_version VALUES(1,0)")
        sql("CREATE TRIGGER pekko_reentry_after_insert AFTER INSERT ON pekko_capture_control FOR EACH ROW UPDATE pekko_reentry_version SET version=version+1 WHERE id=1")
        held.setAutoCommit(false);val locked=scalar(held,"SELECT version FROM pekko_reentry_version WHERE id=1 FOR UPDATE")
        val thrown=intercept[Throwable](reader.subscribe("reentry",table,"g1"))
        val status=store.subscription("reentry").map(_.status).getOrElse("missing")
        val cause=Iterator.iterate(Option(thrown))(_.flatMap(e=>Option(e.getCause))).takeWhile(_.nonEmpty).flatten.map(_.toString).toVector
        val output=Path.of(sys.env("MARKER_VERSION_REENTRY_EVIDENCE"));Files.createDirectories(output)
        val evidence=JsObject("sourceUuid"->JsString(lineage),"lockedVersion"->JsString(locked),"status"->JsString(status),
          "readerFailure"->reader.failure.map(e=>JsString(e.toString)).getOrElse(JsNull),"exceptionChain"->JsArray(cause.map(JsString(_))),
          "scope"->JsString("production Reader and real Debezium; test-installed source version trigger and held source lock; no guardian, Journal, Sink, actual D or versioned permit"))
        Files.writeString(output.resolve("result.json"),evidence.prettyPrint)
        Files.writeString(output.resolve("events.jsonl"),events.iterator().asScala.map(_.compactPrint).mkString("","\n","\n"))
        status shouldBe "B_PENDING"
        cause.exists(_.contains("Lock wait timeout")) shouldBe true
      }finally {try held.rollback()finally held.close();reader.close()}
    }
  }
}
