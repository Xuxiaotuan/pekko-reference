package cn.xuyinyin.magic.connectors.mysql.source

import cn.xuyinyin.magic.connectors.mysql.source.capture._
import cn.xuyinyin.magic.tags.ExternalIntegration
import java.lang.reflect.{InvocationHandler,InvocationTargetException,Method,Proxy}
import java.nio.file.{Files,Path}
import java.sql.{Connection,DriverManager}
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import spray.json._

/** Test-only connection routing; no production versioned permit or cross-database atomicity. */
class MarkerSameConnectionProbeSpec extends AnyWordSpec with Matchers {
  "a Reader marker using the version-lock connection" should {
    "write and capture B without waiting on its own version lock" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_MARKER_SAME_CONNECTION").contains("1"))cancel("private dual MySQL fixture required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("marker-same-connection"))
      val src=sys.env("CAPTURE_TEST_URL");val dst=sys.env("CAPTURE_STATE_URL");val port=sys.env("CDC_WINDOW_MYSQL_PORT").toInt
      require(port!=3306 && src.contains("pekko_test_shared_wire") && dst.contains("pekko_test_shared_state"))
      val user=sys.env("CDC_WINDOW_MYSQL_USER");val password=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      def open(url:String):Connection=DriverManager.getConnection(url,user,password)
      def exec(c:Connection,sql:String):Unit={val q=c.createStatement();try {q.execute(sql);()}finally q.close()}
      def sql(s:String):Unit={val c=open(src);try exec(c,s)finally c.close()}
      def scalar(c:Connection,s:String):String={val q=c.createStatement();try {val r=q.executeQuery(s);try {require(r.next());r.getString(1)}finally r.close()}finally q.close()}
      val suffix=UUID.randomUUID().toString.replace("-","").take(10);val capture="same_"+suffix;val table="orders_"+suffix
      sql(s"CREATE TABLE $table(id BIGINT PRIMARY KEY,label VARCHAR(64))")
      val guard=open(src);val lineage=scalar(guard,"SELECT @@server_uuid")
      val store=new MySQLCaptureStore(()=>open(dst),capture,lineage,10000000);store.initialize()
      val config=MySQLCdcSourceConfig(capture,"127.0.0.1",port,"pekko_test_shared_wire",table,user,password,319003,1,100)
      val state=MySQLCdcStateConfig(dst,user,password,"capture_offsets","capture_history",0)
      val events=new ConcurrentLinkedQueue[JsObject]()
      def emit(k:String,v:JsObject):Unit=events.add(JsObject(v.fields+("kind"->JsString(k))))
      val observed=new CaptureObservedFactory(emit)
      val factory=new DebeziumEngineFactory {def create(p:java.util.Properties):DebeziumEngineAccess={
        p.setProperty("table.include.list",java.util.regex.Pattern.quote(config.database)+"\\.(?!pekko_same_version$).*");observed.create(p)}}
      val shared=new AtomicBoolean(false)
      val borrowed=Proxy.newProxyInstance(classOf[Connection].getClassLoader,Array(classOf[Connection]),new InvocationHandler {
        def invoke(o:Any,m:Method,args:Array[AnyRef]):AnyRef={
          if(m.getName=="close")null
          else try m.invoke(guard,Option(args).getOrElse(Array.empty[AnyRef]):_*)catch{case e:InvocationTargetException=>throw e.getCause}
        }
      }).asInstanceOf[Connection]
      val reader=new SharedMySQLCapture(config,state,store,factory,emit){
        override protected def sourceConnection():Connection=if(shared.get())borrowed else super.sourceConnection()
      }
      def await(label:String)(ok: => Boolean):Unit={val end=System.nanoTime()+45.seconds.toNanos
        while(!ok && reader.failure.isEmpty && System.nanoTime()<end)Thread.sleep(20)
        withClue(label){ok shouldBe true}}
      try {
        reader.start();await("admission"){store.schema(table).nonEmpty && reader.startupEvidence.phase=="ADMISSION_READY"}
        sql("CREATE TABLE pekko_same_version(id INT PRIMARY KEY,version BIGINT NOT NULL) ENGINE=InnoDB")
        sql("INSERT INTO pekko_same_version VALUES(1,0)")
        sql("CREATE TRIGGER pekko_same_after_insert AFTER INSERT ON pekko_capture_control FOR EACH ROW UPDATE pekko_same_version SET version=version+1 WHERE id=1")
        guard.setAutoCommit(false);val before=scalar(guard,"SELECT version FROM pekko_same_version WHERE id=1 FOR UPDATE")
        val connectionId=scalar(guard,"SELECT CONNECTION_ID()")
        shared.set(true);reader.subscribe("same",table,"g1")
        await("marker B bound"){store.subscription("same").exists(_.status=="CAPTURE_READY")}
        val check=open(src);val after=try scalar(check,"SELECT version FROM pekko_same_version WHERE id=1")finally check.close()
        val output=Path.of(sys.env("MARKER_SAME_CONNECTION_EVIDENCE"));Files.createDirectories(output)
        val evidence=JsObject("sourceUuid"->JsString(lineage),"connectionId"->JsString(connectionId),"before"->JsString(before),"after"->JsString(after),
          "status"->JsString(store.subscription("same").get.status),"readerFailure"->reader.failure.map(e=>JsString(e.toString)).getOrElse(JsNull),
          "scope"->JsString("production Reader with test-only borrowed connection and trigger; no versioned permit, guardian, Journal, Sink, restart, actual D, or atomic cross-database recovery"))
        Files.writeString(output.resolve("result.json"),evidence.prettyPrint)
        Files.writeString(output.resolve("events.jsonl"),events.iterator().asScala.map(_.compactPrint).mkString("","\n","\n"))
        before shouldBe "0";after shouldBe "1";reader.failure shouldBe empty
      }finally {shared.set(false);try guard.rollback()finally guard.close();reader.close()}
    }
  }
}
