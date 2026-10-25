package cn.xuyinyin.magic.connectors.mysql.source

import cn.xuyinyin.magic.connectors.mysql.source.capture._
import cn.xuyinyin.magic.tags.ExternalIntegration
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import java.sql.DriverManager
import java.util.UUID
import scala.concurrent.duration._
import spray.json._

class SharedCaptureReaderSpec extends AnyWordSpec with Matchers {
  "the real shared Reader" should {
    "fence commands when source connection release is uncertain" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_SHARED_CAPTURE_MATRIX").contains("1"))cancel("isolated MySQL required")
      val user=sys.env("CDC_WINDOW_MYSQL_USER");val pw=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      val sourceUrl=sys.env("CAPTURE_TEST_URL");val stateUrl=sys.env("CAPTURE_STATE_URL")
      val id="release_"+UUID.randomUUID().toString.replace("-","").take(10)
      val c=DriverManager.getConnection(sourceUrl,user,pw)
      val lineage=try {val q=c.createStatement();try {val r=q.executeQuery("SELECT @@server_uuid");try {r.next();r.getString(1)}finally r.close()}finally q.close()}finally c.close()
      val store=new MySQLCaptureStore(()=>DriverManager.getConnection(stateUrl,user,pw),id,lineage,10000000)
      store.initialize();store.rememberSchema("missing","""{"columns":[],"primaryKeyColumnNames":[]}""")
      val failClose=new java.util.concurrent.atomic.AtomicBoolean(false)
      val opened=new java.util.concurrent.atomic.AtomicInteger(0)
      val done=scala.concurrent.Promise[org.apache.pekko.Done]()
      val factory=new DebeziumEngineFactory {
        def create(properties:java.util.Properties):DebeziumEngineAccess=new DebeziumEngineAccess {
          def start(consumer:DebeziumBatchConsumer)=done.future
          def close():Unit={done.trySuccess(org.apache.pekko.Done);()}
        }
      }
      val source=MySQLCdcSourceConfig(id,"127.0.0.1",13365,"pekko_test_shared_wire","missing",user,pw,310002,1,100)
      val session=new SharedMySQLCapture(source,MySQLCdcStateConfig(stateUrl,user,pw,"capture_offsets","capture_history",0),store,factory) {
        override protected def sourceConnection():java.sql.Connection={
          opened.incrementAndGet();val real=super.sourceConnection()
          java.lang.reflect.Proxy.newProxyInstance(classOf[java.sql.Connection].getClassLoader,Array(classOf[java.sql.Connection]),new java.lang.reflect.InvocationHandler {
            def invoke(o:Any,m:java.lang.reflect.Method,a:Array[AnyRef]):AnyRef={
              val value=try m.invoke(real,Option(a).getOrElse(Array.empty[AnyRef]):_*)catch {case e:java.lang.reflect.InvocationTargetException=>throw e.getCause}
              if(m.getName=="close" && failClose.get())throw new java.sql.SQLException("injected release uncertainty")
              value
            }
          }).asInstanceOf[java.sql.Connection]
        }
      }
      session.start();failClose.set(true)
      try {
        intercept[IllegalArgumentException](session.subscribe("request","missing","g1"))
        session.failure should not be empty
        val before=opened.get()
        intercept[Throwable](session.subscribe("again","missing","g1"))
        opened.get() shouldBe before
      }finally session.close()
    }
    "prepare schema and commit B before capturing changes, without declaring initialization" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_SHARED_CAPTURE_MATRIX").contains("1"))cancel("isolated MySQL required")
      val user=sys.env("CDC_WINDOW_MYSQL_USER");val pw=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      val sourceUrl=sys.env("CAPTURE_TEST_URL"); val stateUrl=sys.env("CAPTURE_STATE_URL")
      val suffix=UUID.randomUUID().toString.replace("-","").take(10);val id="reader_"+suffix;val table="orders_"+suffix
      def sql(s:String):Unit={val c=DriverManager.getConnection(sourceUrl,user,pw);try {val q=c.createStatement();try q.execute(s)finally q.close()}finally c.close()}
      sql(s"CREATE TABLE $table(id BIGINT PRIMARY KEY,label VARCHAR(64))");sql(s"INSERT INTO $table VALUES(1,'baseline')")
      val c=DriverManager.getConnection(sourceUrl,user,pw)
      val lineage=try {val q=c.createStatement();try {val r=q.executeQuery("SELECT @@server_uuid");try {r.next();r.getString(1)}finally r.close()}finally q.close()}finally c.close()
      val store=new MySQLCaptureStore(()=>DriverManager.getConnection(stateUrl,user,pw),id,lineage,10000000)
      store.initialize()
      val source=MySQLCdcSourceConfig(id,"127.0.0.1",13365,"pekko_test_shared_wire",table,user,pw,310002,1,100)
      val state=MySQLCdcStateConfig(stateUrl,user,pw,"capture_offsets","capture_history",0)
      val session=new SharedMySQLCapture(source,state,store)
      val misplaced=new MySQLCaptureStore(()=>DriverManager.getConnection(sourceUrl,user,pw),id+"_misplaced",lineage,10000000)
      misplaced.initialize()
      val invalid=new SharedMySQLCapture(source.copy(connectorId=misplaced.captureId),state,misplaced)
      try intercept[IllegalArgumentException](invalid.start()) finally invalid.close()
      def await(label:String)(p: =>Boolean):Unit={val end=System.nanoTime()+45.seconds.toNanos
        while(!p && System.nanoTime()<end){session.failure.foreach(throw _);Thread.sleep(20)}
        withClue(label){p shouldBe true}}
      session.start()
      try {
        await("schema callback")(store.schema(table).nonEmpty)
        session.subscribe("request",table,"g1")
        await("durable B")(store.subscription("request").exists(_.status=="CAPTURE_READY"))
        store.subscription("request").get.initialized shouldBe false
        store.read("request",0,100) shouldBe empty
        sql(s"UPDATE $table SET label='updated' WHERE id=1");sql(s"UPDATE $table SET id=2 WHERE id=1")
        await("complete groups")(store.read("request",0,100).size==2)
        store.read("request",0,100).last.group.members.size shouldBe 2
        val log=new CapturedLogSource(store,source.database)
        val batch=log.readBatch("request",0,100,"source","execution",0).get
        batch.cursor.kind shouldBe "mysql.capture.groups.v1"
        log.resumePosition("request",batch.cursor) shouldBe store.read("request",0,100).last.sequence
        intercept[IllegalArgumentException](log.resumePosition("other",batch.cursor))
        val skipped=JsObject(batch.cursor.value.parseJson.asJsObject.fields+("sequence"->JsNumber(999999)))
        intercept[IllegalArgumentException](log.resumePosition("request",batch.cursor.copy(value=skipped.compactPrint)))
        session.unsubscribe("request")
        await("durable K")(store.subscription("request").exists(_.status=="CAPTURE_STOPPED"))
        sql(s"INSERT INTO $table VALUES(3,'after stop')")
        Thread.sleep(200)
        store.read("request",0,100).size shouldBe 2
        sql(s"ALTER TABLE $table MODIFY label VARCHAR(96)")
        await("unselected schema advances")(store.schema(table).exists(_.id!=store.subscription("request").get.schemaId))
        log.resumePosition("request",batch.cursor) shouldBe store.read("request",0,100).last.sequence
        log.readBatch("request",0,100,"source","execution",0).get.rows shouldBe batch.rows
        session.subscribe("request2",table,"g2")
        await("second generation B")(store.subscription("request2").exists(_.b.nonEmpty))
        sql(s"ALTER TABLE $table MODIFY label VARCHAR(128)")
        await("same-JDBC-type DDL must pause capture")(session.failure.nonEmpty)
      } finally session.close()
    }
  }
}
