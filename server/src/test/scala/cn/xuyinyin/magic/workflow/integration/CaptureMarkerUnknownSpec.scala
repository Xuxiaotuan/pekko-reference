package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.connectors.mysql.source._
import cn.xuyinyin.magic.connectors.mysql.source.capture._
import cn.xuyinyin.magic.workflow.engine.{CdcExecutionException,ExecutionFailure}
import cn.xuyinyin.magic.tags.ExternalIntegration
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files,Path}
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.{ConcurrentLinkedQueue,CountDownLatch,Executors,TimeUnit}
import scala.concurrent.{Await,ExecutionContext,Future}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import spray.json._

class CaptureMarkerUnknownSpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._
  "a source marker with a lost real COMMIT reply" should {
    "pause without rewriting B and retain release-unknown classification" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_CAPTURE_ADMISSION_MATRIX").contains("1"))cancel("exclusive MySQL lease required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").exists(_.nonEmpty))
      val source=Settings.environment();val state=source.copy(port=sys.env("CAPTURE_STATE_PORT").toInt)
      require(!Set(3306,13365,13366).contains(source.port) && !Set(3306,13365,13366).contains(state.port))
      def uuid(s:Settings)=withConnection(s,"")(query(_,"SELECT @@server_uuid AS uuid")).elements.head.asJsObject.fields("uuid").convertTo[String]
      val lineage=uuid(source);require(lineage==sys.env("CAPTURE_EXPECT_SOURCE_UUID") && uuid(state)==sys.env("CAPTURE_EXPECT_STATE_UUID"))
      val suffix=UUID.randomUUID().toString.replace("-","").take(12);val schema="pekko_test_cdc_window_"+suffix;val id="marker_"+suffix
      val out=Path.of(sys.env("CAPTURE_ADMISSION_EVIDENCE")).resolve(id);Files.createDirectories(out)
      initialize(source,schema);initialize(state,schema)
      def sql(s:Settings,q:String)=withConnection(s,schema)(executeSql(_,q))
      sql(source,"CREATE TABLE a LIKE source_rows");sql(source,"INSERT INTO a SELECT * FROM source_rows WHERE id<=4")
      sql(state,"CREATE TABLE target_a LIKE source_rows");sql(state,"INSERT INTO target_a SELECT * FROM source_rows WHERE id<=4")
      withConnection(state,schema){c=>
        val in=getClass.getClassLoader.getResourceAsStream("db/mysql/pekko-sync-cdc-group-ledger-schema.sql")
        val ddl=try scala.io.Source.fromInputStream(in).mkString finally in.close()
        ddl.split(";").map(_.trim).filter(_.nonEmpty).foreach(executeSql(c,_))
      }
      val events=new ConcurrentLinkedQueue[JsObject]();val committed=new CountDownLatch(1)
      def event(k:String,v:JsObject):Unit={events.add(JsObject(v.fields+ ("kind"->JsString(k))));if(k=="PROXY_COMMIT_OK_HELD")committed.countDown()}
      val proxy=new MySQLCommitResponseProxy(source.host,source.port,event)
      val store=new MySQLCaptureStore(()=>DriverManager.getConnection(state.url(schema),state.user,state.password),id,lineage,64L*1024*1024)
      store.initialize()
      val cfg=MySQLCdcStateConfig(state.url(schema),state.user,state.password,"capture_offsets","capture_history",0)
      val capture=new SharedMySQLCapture(MySQLCdcSourceConfig(id,source.host,source.port,schema,"a",source.user,source.password,
        (id.hashCode.toLong & 0x7fffffffL)+100000,128,100),cfg,store,DebeziumEngineFactory.real,event) {
        override protected def sourceConnection():java.sql.Connection=DriverManager.getConnection(
          source.copy(port=proxy.port).url(schema)+"&connectTimeout=5000&socketTimeout=5000",source.user,source.password)
      }
      val executor=Executors.newSingleThreadExecutor();implicit val ec:ExecutionContext=ExecutionContext.fromExecutorService(executor)
      val offset=MySQLCdcSourceNode.connectorStateTables(cfg,id)._1
      def controls=withConnection(source,schema)(query(_,"SELECT * FROM pekko_capture_control ORDER BY operation_id"))
      def snapshot():JsObject=JsObject("source"->withConnection(source,schema)(query(_,"SELECT * FROM a ORDER BY id")),
        "target"->withConnection(state,schema)(query(_,"SELECT * FROM target_a ORDER BY id")),"controls"->controls,"L"->store.evidence,
        "D"->withConnection(state,schema)(query(_,s"SELECT offset_key,offset_val FROM `$offset`")),
        "T"->withConnection(state,schema)(query(_,"SELECT * FROM pekko_sync_cdc_group_ledger ORDER BY group_id")),
        "J"->withConnection(state,schema)(query(_,"SELECT persistence_id,sequence_number,event_ser_manifest,HEX(event_payload) AS payload_hex FROM event_journal ORDER BY ordering")))
      try {
        capture.start();awaitCondition("production admission",90.seconds)(capture.startupEvidence.phase=="ADMISSION_READY")
        write(out,"before.json",snapshot())
        proxy.arm("C2",JsObject("stage"->JsString("SOURCE_B_COMMIT")))
        val command=Future(scala.util.Try(capture.subscribe("a","a","g1")))
        committed.await(20,TimeUnit.SECONDS) shouldBe true
        controls.elements.size shouldBe 1
        write(out,"actual-commit-reply-held.json",snapshot())
        proxy.dropHeldResponse()
        val failure=Await.result(command,20.seconds).failed.get
        failure shouldBe a[CdcExecutionException]
        val classified=ExecutionFailure.from(failure,"test")
        classified.code shouldBe ExecutionFailure.ReleaseUnknown
        classified.releaseConfirmed shouldBe false
        classified.stage shouldBe "source-marker:MARKER_COMMIT_UNKNOWN"
        capture.startupEvidence.phase shouldBe "PAUSED_UNPROVEN"
        val before=controls
        intercept[Throwable](capture.subscribe("again","a","g2"))
        controls shouldBe before;store.subscription("again") shouldBe None
        val finalState=snapshot();finalState.fields("source") shouldBe finalState.fields("target")
        finalState.fields("T").asInstanceOf[JsArray].elements shouldBe empty
        finalState.fields("J").asInstanceOf[JsArray].elements shouldBe empty
        write(out,"result.json",JsObject("status"->JsString("SAFE_PAUSED"),"failureCode"->JsString(classified.code),
          "failureStage"->JsString(classified.stage),"releaseConfirmed"->JsBoolean(false),"state"->finalState,
          "events"->JsArray(events.iterator().asScala.toVector)))
      }finally {capture.close();proxy.close();executor.shutdownNow()}
    }
  }
}
