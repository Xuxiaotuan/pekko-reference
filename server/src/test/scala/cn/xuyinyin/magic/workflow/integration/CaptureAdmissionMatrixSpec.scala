package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.connectors.mysql.source._
import cn.xuyinyin.magic.connectors.mysql.source.capture._
import cn.xuyinyin.magic.tags.ExternalIntegration
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files,Path}
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.{CountDownLatch,Executors,TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await,ExecutionContext,Future}
import scala.concurrent.duration._
import spray.json._

/** Real legacy windows + new production guard. Old workers use their immutable pre-fix classpath. */
class CaptureAdmissionMatrixSpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._
  private def enabled():Unit=if(!sys.env.get("RUN_CAPTURE_ADMISSION_MATRIX").contains("1"))cancel("exclusive MySQL lease required")
  private def settings = Settings.environment()
  private def stateSettings = settings.copy(port=sys.env("CAPTURE_STATE_PORT").toInt)
  private def idle():Boolean=Vector(settings,stateSettings).forall(s=>withConnection(s,"")(c=>
    query(c,"SELECT ID FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND USER NOT IN ('event_scheduler','system user')").elements.isEmpty &&
      query(c,"SELECT trx_id FROM information_schema.INNODB_TRX").elements.isEmpty))
  private def fixture():(String,String,Path,MySQLCaptureStore)={
    enabled();require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").exists(_.nonEmpty))
    require(!Set(3306,13365,13366).contains(settings.port) && !Set(3306,13365,13366).contains(stateSettings.port))
    val sourceUuid=withConnection(settings,"")(query(_,"SELECT @@server_uuid AS uuid")).elements.head.asJsObject.fields("uuid").convertTo[String]
    val stateUuid=withConnection(stateSettings,"")(query(_,"SELECT @@server_uuid AS uuid")).elements.head.asJsObject.fields("uuid").convertTo[String]
    require(sourceUuid==sys.env("CAPTURE_EXPECT_SOURCE_UUID") && stateUuid==sys.env("CAPTURE_EXPECT_STATE_UUID") && sourceUuid!=stateUuid,"slot identity mismatch before fixture writes")
    val suffix=UUID.randomUUID().toString.replace("-","").take(12)
    val schema="pekko_test_cdc_window_"+suffix;val id="admission_"+suffix
    val dir=Path.of(sys.env.getOrElse("CAPTURE_ADMISSION_EVIDENCE","target/capture-admission")).resolve(id)
    Files.createDirectories(dir)
    initialize(settings,schema);initialize(stateSettings,schema)
    Vector("a","b","c","d","e","f","g").foreach {t=>
      sql(settings,schema,s"CREATE TABLE $t LIKE source_rows");sql(settings,schema,s"INSERT INTO $t SELECT * FROM source_rows WHERE id<=4")
      sql(stateSettings,schema,s"CREATE TABLE target_$t LIKE source_rows");sql(stateSettings,schema,s"INSERT INTO target_$t SELECT * FROM source_rows WHERE id<=4")
    }
    withConnection(stateSettings,schema){c=>
      val in=getClass.getClassLoader.getResourceAsStream("db/mysql/pekko-sync-cdc-group-ledger-schema.sql")
      val ddl=try scala.io.Source.fromInputStream(in).mkString finally in.close()
      ddl.split(";").map(_.trim).filter(_.nonEmpty).foreach(executeSql(c,_))
    }
    val uuid=withConnection(settings,schema)(query(_,"SELECT @@server_uuid AS uuid")).elements.head.asJsObject.fields("uuid").convertTo[String]
    val store=new MySQLCaptureStore(()=>DriverManager.getConnection(stateSettings.url(schema),stateSettings.user,stateSettings.password),id,uuid,64L*1024*1024)
    store.initialize();(schema,id,dir,store)
  }
  private def sql(s:Settings,schema:String,value:String):Unit=withConnection(s,schema)(executeSql(_,value))
  private def session(schema:String,id:String,store:MySQLCaptureStore,factory:DebeziumEngineFactory,observe:(String,JsObject)=>Unit)=
    new SharedMySQLCapture(MySQLCdcSourceConfig(id,settings.host,settings.port,schema,"a",settings.user,settings.password,
      (id.hashCode.toLong & 0x7fffffffL)+100000,128,100),
      MySQLCdcStateConfig(stateSettings.url(schema),stateSettings.user,stateSettings.password,"capture_offsets","capture_history",0),store,factory,observe)
  private def dump(schema:String,id:String,store:MySQLCaptureStore):JsObject={
    val table=MySQLCdcSourceNode.connectorStateTables(MySQLCdcStateConfig(stateSettings.url(schema),stateSettings.user,stateSettings.password,"capture_offsets","capture_history",0),id)._1
    def rows(s:Settings,t:String)=withConnection(s,schema)(query(_,s"SELECT * FROM $t ORDER BY id"))
    JsObject("L"->store.evidence,"D"->withConnection(stateSettings,schema)(query(_,s"SELECT offset_key,offset_val FROM `$table`")),
      "source"->rows(settings,"a"),"target"->rows(stateSettings,"target_a"),
      "controls"->withConnection(settings,schema)(query(_,"SELECT * FROM pekko_capture_control ORDER BY operation_id")),
      "T"->withConnection(stateSettings,schema)(query(_,"SELECT * FROM pekko_sync_cdc_group_ledger ORDER BY group_id")),
      "J"->withConnection(stateSettings,schema)(query(_,"SELECT persistence_id,sequence_number,event_ser_id,event_ser_manifest,HEX(event_payload) AS payload_hex FROM event_journal ORDER BY ordering")))
  }
  private def evidence(e:CaptureStartupEvidence):JsObject=JsObject("attempt"->JsString(e.runtimeAttemptId),"phase"->JsString(e.phase),
    "reason"->e.reasonCode.map(JsString(_)).getOrElse(JsNull),"loadedCheckpoint"->JsString(e.loadedCheckpoint))

  "the production capture admission guard" should {
    for(bound<-Vector(false,true)) {
      s"pause the real legacy ${if(bound)"B-bound INITIAL" else "prepared INITIAL"} state before creating an Engine" taggedAs ExternalIntegration in {
        val (schema,id,dir,store)=fixture()
        val cp=sys.env("CAPTURE_LEGACY_CLASSPATH")
        val builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx512m","-Duser.timezone=UTC","-cp",cp,
          "cn.xuyinyin.magic.workflow.integration.CaptureStartupRecoveryWorker",schema,id,if(bound)"128"else"1",if(bound)"SCHEMA_AND_CALLBACK"else"SCHEMA_EMITTED")
        builder.redirectErrorStream(true)
        val child=new Child(builder.start(),dir.resolve("legacy.log"))
        val offset=MySQLCdcSourceNode.connectorStateTables(MySQLCdcStateConfig(stateSettings.url(schema),stateSettings.user,stateSettings.password,"capture_offsets","capture_history",0),id)._1
        def initial:Boolean=withConnection(stateSettings,schema)(query(_,s"SELECT offset_val FROM `$offset`")).elements.exists(_.asJsObject.fields("offset_val").convertTo[String].contains("INITIAL"))
        try {
          child.awaitEvent("PROCESS_READY");child.awaitEvent("SDK_CUTPOINT")
          val names=Vector("a","b","c","d","e","f","g")
          // The SDK may retain its final schema record until snapshot completion.
          // Prepared-only needs one real visible schema; the bound probe's callback gate needs all.
          awaitCondition("real schema",20.seconds){if(bound)names.forall(t=>store.schema(t).nonEmpty)else names.exists(t=>store.schema(t).nonEmpty)}
          awaitCondition("actual INITIAL",20.seconds){initial}
          if(bound){
            child.awaitEvent("SDK_CALLBACK_HELD")
            child.send("ARM_ACK B_BOUND");child.awaitEvent("ARMED")
            child.send("RELEASE_SDK");child.awaitEvent("SDK_GATE_RELEASED")
            child.send("SUBSCRIBE a a g1");child.awaitEvent("SUBSCRIBE_ACCEPTED")
            child.awaitEvent("SDK_B_ROW_ENQUEUED")
            child.send("RELEASE_CALLBACK");child.awaitEvent("CALLBACK_GATE_RELEASED")
            child.awaitEvent("CUTPOINT").fields("window") shouldBe JsString("B_COMMITTED_ACK_PENDING")
            initial shouldBe true;store.subscription("a").get.b should not be empty
            child.send("APPLY a a");child.awaitEvent("APPLY_READY")
            sql(settings,schema,"UPDATE a SET label='before-guard-restart',amount=amount+0.1250,revision=revision+1,note='',updated_at='2026-10-27 01:02:03.123456' WHERE id=1")
          }else {val visible=names.find(t=>store.schema(t).nonEmpty).get;child.send(s"PREPARE a $visible g1");child.awaitEvent("PREPARED")}
          write(dir,"legacy-cut.json",child.state());write(dir,"before-crash.json",dump(schema,id,store))
          child.send("CRASH");child.exit() shouldBe 86
          awaitCondition("old worker physical sessions and transactions released",180.seconds)(idle())
          val before=dump(schema,id,store);val creates=new AtomicInteger()
          val never=new DebeziumEngineFactory {def create(p:java.util.Properties):DebeziumEngineAccess={creates.incrementAndGet();throw new AssertionError("unsafe Engine creation")}}
          def reject(label:String,expected:String):Unit={
            val c=session(schema,id,store,never,(_,_)=>())
            try {
              intercept[CaptureAdmissionException](c.start()).code shouldBe expected
              creates.get() shouldBe 0
              c.startupEvidence.phase shouldBe "PAUSED_UNPROVEN"
              write(dir,label+"-guard.json",JsObject("startup"->evidence(c.startupEvidence),"engineCreations"->JsNumber(creates.get()),"state"->dump(schema,id,store)))
            }finally c.close()
          }
          reject("original",if(bound)"INITIAL_WITH_COMMITMENT"else"UNBOUND_MARKER_UNPROVEN")
          dump(schema,id,store) shouldBe before
          if(bound){
            // Destructive injections affect only this new fixture and retain the original bytes/rows.
            sql(stateSettings,schema,s"RENAME TABLE `$offset` TO `${offset}_held`")
            val c=session(schema,id,store,never,(_,_)=>())
            try {intercept[CaptureAdmissionException](c.start()).code shouldBe "CHECKPOINT_MISSING";creates.get() shouldBe 0}
            finally {c.close();sql(stateSettings,schema,s"RENAME TABLE `${offset}_held` TO `$offset`")}
            sql(settings,schema,"RENAME TABLE pekko_capture_control TO pekko_capture_control_held")
            val missing=session(schema,id,store,never,(_,_)=>())
            try {
              intercept[CaptureAdmissionException](missing.start()).code shouldBe "CONTROL_TABLE_MISSING"
              withConnection(settings,schema)(query(_,"SHOW TABLES LIKE 'pekko_capture_control'")).elements shouldBe empty
            }finally {missing.close();sql(settings,schema,"RENAME TABLE pekko_capture_control_held TO pekko_capture_control")}
            dump(schema,id,store) shouldBe before
            Vector("pekko_capture_log","pekko_capture_subscriptions","pekko_capture_schemas","pekko_capture_fences").foreach(t=>
              sql(stateSettings,schema,s"DELETE FROM $t WHERE capture_id='$id'"))
            sql(stateSettings,schema,s"RENAME TABLE `$offset` TO `${offset}_held`")
            sql(settings,schema,"RENAME TABLE pekko_capture_control TO pekko_capture_control_held")
            val lost=session(schema,id,store,never,(_,_)=>())
            try {
              intercept[CaptureAdmissionException](lost.start()).code shouldBe "CAPTURE_LOG_HEAD_INCONSISTENT";creates.get() shouldBe 0
              withConnection(settings,schema)(query(_,"SHOW TABLES LIKE 'pekko_capture_control'")).elements shouldBe empty
            }finally {lost.close();sql(stateSettings,schema,s"RENAME TABLE `${offset}_held` TO `$offset`");sql(settings,schema,"RENAME TABLE pekko_capture_control_held TO pekko_capture_control")}
            write(dir,"injected-lost-frame.json",dump(schema,id,store))
          }
          write(dir,"result.json",JsObject("status"->JsString("SAFE_PAUSED"),"bound"->JsBoolean(bound),"engineCreations"->JsNumber(creates.get())))
        }finally child.kill()
      }
    }
    "reject before ready, retain idempotency, and linearize a close against an admitted source write" taggedAs ExternalIntegration in {
      val (schema,id,dir,store)=fixture()
      val gate=new CountDownLatch(1);val stopped=new CountDownLatch(1)
      val factory=new CaptureStartupRecoveryFactory((k,v)=>emit(k,v.fields.toSeq:_*),true)
      val c=session(schema,id,store,factory,(k,v)=>{
        emit(k,v.fields.toSeq:_*)
        if(k=="SUBSCRIBE_ADMITTED" && v.fields("requestId")==JsString("close-race")){gate.countDown();hold.await()}
        if(k=="CAPTURE_STOP_REQUESTED")stopped.countDown()
      })
      val executor=Executors.newFixedThreadPool(3);implicit val ec:ExecutionContext=ExecutionContext.fromExecutorService(executor)
      try {
        c.start()
        val names=Vector("a","b","c","d","e","f","g")
        awaitCondition("some schema emitted",20.seconds)(names.exists(t=>store.schema(t).nonEmpty))
        val visible=names.find(t=>store.schema(t).nonEmpty).get
        intercept[CaptureAdmissionException](c.subscribe("early",visible,"g1")).code shouldBe "CAPTURE_STARTING"
        store.subscription("early") shouldBe None
        withConnection(settings,schema)(query(_,"SELECT * FROM pekko_capture_control")).elements shouldBe empty
        factory.release()
        awaitCondition("production ready",90.seconds)(c.startupEvidence.phase=="ADMISSION_READY")
        val a=Future(c.subscribe("a","a","g1"));val b=Future(c.subscribe("b","b","g1"))
        Await.result(a,20.seconds);Await.result(b,20.seconds)
        awaitCondition("both B",20.seconds)(store.subscription("a").exists(_.b.nonEmpty) && store.subscription("b").exists(_.b.nonEmpty))
        c.subscribe("a","a","g1").b shouldBe store.subscription("a").get.b
        intercept[IllegalArgumentException](c.subscribe("a","b","g1"))
        write(dir,"ready.json",JsObject("startup"->evidence(c.startupEvidence),"state"->dump(schema,id,store)))
        val admitted=Future(c.subscribe("close-race","c","g1"))
        gate.await(20,TimeUnit.SECONDS) shouldBe true
        val closing=Future(c.close())
        stopped.await(20,TimeUnit.SECONDS) shouldBe true
        val rejected=Future(scala.util.Try(c.subscribe("after-stop","d","g1")))
        hold.countDown()
        Await.result(admitted,20.seconds);Await.result(closing,30.seconds)
        Await.result(rejected,20.seconds).isFailure shouldBe true
        store.subscription("after-stop") shouldBe None
        c.startupEvidence.phase shouldBe "STOPPING"
        awaitCondition("physical release",180.seconds)(idle())
        write(dir,"result.json",JsObject("status"->JsString("PASSED"),"state"->dump(schema,id,store),"startup"->evidence(c.startupEvidence)))
      }finally {hold.countDown();factory.release();c.close();executor.shutdownNow()}
    }
  }
  private val hold=new CountDownLatch(1)
}
