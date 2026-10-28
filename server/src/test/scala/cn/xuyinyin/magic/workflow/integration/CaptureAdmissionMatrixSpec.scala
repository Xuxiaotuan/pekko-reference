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
    "keep the command gate closed when a held SDK callback returns after close" taggedAs ExternalIntegration in {
      val (schema,id,dir,store)=fixture()
      val events=new java.util.concurrent.ConcurrentLinkedQueue[JsObject]()
      def record(k:String,v:JsObject):Unit=events.add(JsObject(v.fields+("kind"->JsString(k))))
      val factory=new CaptureAdmissionProbeFactory(1000,record,true)
      val c=session(schema,id,store,factory,record)
      try {
        c.start()
        def observed=events.toArray.toVector.map(_.asInstanceOf[JsObject])
        awaitCondition("real callback held before initial readiness",90.seconds)(observed.exists(_.fields.get("kind").contains(JsString("TEST_CALLBACK_HELD"))))
        c.cachedStartupEvidence.phase shouldBe "STARTING"
        val first=c.requestClose();c.requestClose() shouldBe first
        val close=Await.result(first,15.seconds)
        close.reusable shouldBe false
        write(dir,"callback-held-close.json",JsObject("startup"->evidence(c.cachedStartupEvidence),"local"->JsString(close.localTermination),
          "server"->JsString(close.serverRelease),"ownerReleased"->JsBoolean(close.ownerReleased),"events"->JsArray(observed)))
        factory.releaseGate()
        val callbackReturned=scala.util.Try(awaitCondition("held callback completed or failed",60.seconds)(observed.exists(e=>
          e.fields.get("kind").exists(k=>k==JsString("ORIGINAL_CALLBACK_RETURNED") || k==JsString("ORIGINAL_CALLBACK_FAILED")))))
        val rejection=scala.util.Try(c.subscribe("late","a","g1"))
        val idleResult=scala.util.Try(awaitCondition("close after held callback physical idle",180.seconds)(idle()))
        write(dir,"late-callback-result.json",JsObject("returned"->JsBoolean(callbackReturned.isSuccess),"idle"->JsBoolean(idleResult.isSuccess),
          "rejection"->rejection.failed.toOption.map(e=>JsString(e.toString)).getOrElse(JsNull),
          "startup"->evidence(c.cachedStartupEvidence),"subscriptionAbsent"->JsBoolean(store.subscription("late").isEmpty),
          "events"->JsArray(observed),"state"->dump(schema,id,store)))
        callbackReturned.get;idleResult.get
        rejection.isFailure shouldBe true
        c.cachedStartupEvidence.phase shouldBe "STOPPING"
        store.subscription("late") shouldBe None
      }finally {factory.releaseGate();scala.util.Try(c.close())}
    }
    "close while the actual MySQL SDK task is starting" taggedAs ExternalIntegration in {
      val (schema,id,dir,store)=fixture()
      val events=new java.util.concurrent.ConcurrentLinkedQueue[JsObject]()
      val factory=new DebeziumEngineFactory {def create(p:java.util.Properties):DebeziumEngineAccess={
        p.setProperty("connector.class",classOf[TaskStartingGateConnector].getName)
        DebeziumEngineFactory.real.create(p)
      }}
      val c=session(schema,id,store,factory,(k,v)=>events.add(JsObject(v.fields+("kind"->JsString(k)))))
      def resources(s:Settings):JsObject=withConnection(s,"")(db=>JsObject(
        "sessions"->query(db,"SELECT ID,USER,HOST,DB,COMMAND,STATE FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID()"),
        "transactions"->query(db,"SELECT trx_id,trx_state,trx_mysql_thread_id FROM information_schema.INNODB_TRX")))
      try {
        c.start();TaskStartingGate.entered.await(60,TimeUnit.SECONDS) shouldBe true
        val first=c.requestClose();c.requestClose() shouldBe first
        val result=Await.result(first,15.seconds)
        write(dir,"sdk-task-start-held.json",JsObject("local"->JsString(result.localTermination),"server"->JsString(result.serverRelease),
          "ownerReleased"->JsBoolean(result.ownerReleased),"reusable"->JsBoolean(result.reusable),"operation"->JsString(result.operationId),
          "startup"->evidence(c.cachedStartupEvidence),"source"->resources(settings),"state"->resources(stateSettings),
          "L"->store.evidence,"events"->JsArray(events.toArray.toVector.map(_.asInstanceOf[JsObject]))))
        result.reusable shouldBe false
        TaskStartingGate.release.countDown()
        val released=scala.util.Try(awaitCondition("sdk task-start close physical release",180.seconds)(idle()))
        write(dir,"sdk-task-start-result.json",JsObject("idle"->JsBoolean(released.isSuccess),"error"->released.failed.toOption.map(e=>JsString(e.toString)).getOrElse(JsNull),
          "startup"->evidence(c.cachedStartupEvidence),"source"->resources(settings),"state"->resources(stateSettings),
          "L"->store.evidence,"events"->JsArray(events.toArray.toVector.map(_.asInstanceOf[JsObject]))))
        c.cachedStartupEvidence.phase shouldBe "STOPPING"
        released.get
      }finally {TaskStartingGate.release.countDown();scala.util.Try(c.close())}
    }
    "preserve a direct SDK factory startup failure with real MySQL state" taggedAs ExternalIntegration in {
      val (schema,id,dir,store)=fixture();val creates=new AtomicInteger()
      val factory=new DebeziumEngineFactory {def create(p:java.util.Properties):DebeziumEngineAccess={
        creates.incrementAndGet();throw new IllegalStateException("b05-injected-factory-start-failure")
      }}
      val c=session(schema,id,store,factory,(_,_)=>())
      try {
        val failure=intercept[IllegalStateException](c.start())
        failure.getMessage should include ("b05-injected-factory-start-failure")
        creates.get() shouldBe 1
        val first=c.requestClose();c.requestClose() shouldBe first
        val close=Await.result(first,10.seconds)
        awaitCondition("startup failure physical idle",30.seconds)(idle())
        val controls=withConnection(settings,schema)(query(_,"SELECT * FROM pekko_capture_control"))
        controls.elements shouldBe empty
        write(dir,"result.json",JsObject("status"->JsString("START_FAILED"),"error"->JsString(failure.toString),
          "engineCreations"->JsNumber(creates.get()),"local"->JsString(close.localTermination),"server"->JsString(close.serverRelease),
          "reusable"->JsBoolean(close.reusable),"controls"->controls,"L"->store.evidence,
          "scope"->JsString("injected factory exception with real MySQL; no real SDK or Actor/Sink execution")))
      }finally scala.util.Try(c.close())
    }
    "bound close while a real-MySQL capture factory is held during start" taggedAs ExternalIntegration in {
      val (schema,id,dir,store)=fixture()
      val entered=new CountDownLatch(1);val release=new CountDownLatch(1);val creates=new AtomicInteger()
      val factory=new DebeziumEngineFactory {def create(p:java.util.Properties):DebeziumEngineAccess={
        creates.incrementAndGet();entered.countDown();release.await();DebeziumEngineFactory.real.create(p)
      }}
      val events=new java.util.concurrent.ConcurrentLinkedQueue[JsObject]()
      val c=session(schema,id,store,factory,(k,v)=>events.add(JsObject(v.fields+("kind"->JsString(k)))))
      val executor=Executors.newSingleThreadExecutor();implicit val ec:ExecutionContext=ExecutionContext.fromExecutorService(executor)
      try {
        val started=Future(scala.util.Try(c.start()))
        entered.await(30,TimeUnit.SECONDS) shouldBe true
        val first=c.requestClose();c.requestClose() shouldBe first
        val result=Await.result(first,10.seconds)
        result.localTermination shouldBe "UNKNOWN";result.reusable shouldBe false
        write(dir,"start-held-close.json",JsObject("local"->JsString(result.localTermination),"server"->JsString(result.serverRelease),
          "reusable"->JsBoolean(result.reusable),"operation"->JsString(result.operationId),"creates"->JsNumber(creates.get()),
          "events"->JsArray(events.toArray.toVector.map(_.asInstanceOf[JsObject]))))
        release.countDown()
        val outcome=Await.result(started,30.seconds)
        scala.util.Try(c.close())
        awaitCondition("start/close physical release",180.seconds)(idle())
        c.cachedStartupEvidence.phase shouldBe "STOPPING"
        write(dir,"result.json",JsObject("status"->JsString("STOPPING"),"creates"->JsNumber(creates.get()),
          "startReturned"->JsBoolean(outcome.isSuccess),"startup"->evidence(c.cachedStartupEvidence),
          "events"->JsArray(events.toArray.toVector.map(_.asInstanceOf[JsObject])),
          "scope"->JsString("test factory gate before real SDK creation; not Debezium tasks-starting")))
      }finally {release.countDown();scala.util.Try(c.close());executor.shutdownNow()}
    }
    "refuse a start after an unstarted real-MySQL capture was closed" taggedAs ExternalIntegration in {
      val (schema,id,dir,store)=fixture()
      val creates=new AtomicInteger()
      val factory=new DebeziumEngineFactory {def create(p:java.util.Properties):DebeziumEngineAccess={creates.incrementAndGet();throw new AssertionError("engine created after close")}}
      val c=session(schema,id,store,factory,(_,_)=>())
      try {
        val first=c.requestClose();c.requestClose() shouldBe first
        Await.result(first,10.seconds)
        intercept[CaptureAdmissionException](c.start()).code shouldBe "CAPTURE_STOPPING"
        creates.get() shouldBe 0
        awaitCondition("unstarted physical idle",30.seconds)(idle())
        val controls=withConnection(settings,schema)(query(_,"SHOW TABLES LIKE 'pekko_capture_control'"))
        controls.elements shouldBe empty
        write(dir,"result.json",JsObject("status"->JsString("REJECTED_NO_EFFECT"),"engineCreations"->JsNumber(creates.get()),
          "controlTable"->controls,"L"->store.evidence,"startup"->evidence(c.cachedStartupEvidence)))
      }finally scala.util.Try(c.close())
    }
    "restore completed legacy Journal and capture history without rewriting their retained prefixes" taggedAs ExternalIntegration in {
      val (schema,id,dir,store)=fixture()
      def worker(cp:String,name:String):Child={
        val builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx512m","-Duser.timezone=UTC","-cp",cp,
          "cn.xuyinyin.magic.workflow.integration.CaptureStartupRecoveryWorker",schema,id,"128","NONE")
        builder.redirectErrorStream(true);new Child(builder.start(),dir.resolve(name+".log"))
      }
      val old=worker(sys.env("CAPTURE_LEGACY_CLASSPATH"),"a16-legacy")
      var current:Child=null
      def completedD:Boolean={
        val rows=dump(schema,id,store).fields("D").asInstanceOf[JsArray].elements
        rows.size==1 && {val value=rows.head.asJsObject.fields("offset_val").convertTo[String]
          !value.contains("INITIAL") && scala.util.Try(store.verifyOffset(value)).isSuccess}
      }
      def actorHasCheckpoint(c:Child):Boolean=c.state().fields("actors").asJsObject.fields.get("a").exists(
        _.asJsObject.fields("checkpoints").asInstanceOf[JsArray].elements.nonEmpty)
      try {
        old.awaitEvent("PROCESS_READY")
        awaitCondition("legacy complete covered D and schema",90.seconds)(store.schema("a").nonEmpty && completedD)
        old.send("SUBSCRIBE a a g1");old.awaitEvent("SUBSCRIBE_ACCEPTED")
        awaitCondition("legacy B bound",30.seconds)(store.subscription("a").exists(_.b.nonEmpty))
        old.send("APPLY a a");old.awaitEvent("APPLY_READY")
        sql(settings,schema,"UPDATE a SET label='legacy-event',amount=amount+0.1250,revision=revision+1,note='',updated_at='2026-10-27 01:02:03.123456' WHERE id=1")
        awaitCondition("legacy actor applies source",30.seconds){val s=dump(schema,id,store);s.fields("source")==s.fields("target") && actorHasCheckpoint(old)}
        write(dir,"a16-before-crash.json",JsObject("worker"->old.state(),"state"->dump(schema,id,store)))
        old.send("CRASH");old.exit() shouldBe 86
        awaitCondition("legacy physical release",180.seconds)(idle())
        val before=dump(schema,id,store)
        before.fields("J").asInstanceOf[JsArray].elements should not be empty
        completedD shouldBe true
        write(dir,"a16-before-restart.json",before)
        current=worker(System.getProperty("java.class.path"),"a16-current")
        current.awaitEvent("PROCESS_READY")
        awaitCondition("current admission",90.seconds){current.send("CAPTURE_ADMISSION");current.awaitEvent("CAPTURE_ADMISSION").fields("phase")==JsString("ADMISSION_READY")}
        current.send("RECOVER_APPLY a a");current.awaitEvent("APPLY_READY")
        awaitCondition("old actor checkpoint recovered",30.seconds)(actorHasCheckpoint(current))
        sql(settings,schema,"UPDATE a SET label='current-event',amount=amount+0.1250,revision=revision+1,note=NULL,updated_at='2026-10-28 01:02:03.654321' WHERE id=1")
        awaitCondition("current actor applies source",30.seconds){val s=dump(schema,id,store);s.fields("source")==s.fields("target")}
        val after=dump(schema,id,store)
        val oldJ=before.fields("J").asInstanceOf[JsArray].elements
        val newJ=after.fields("J").asInstanceOf[JsArray].elements
        newJ.take(oldJ.size) shouldBe oldJ
        val oldL=before.fields("L").asJsObject.fields("log").asInstanceOf[JsArray].elements
        val newL=after.fields("L").asJsObject.fields("log").asInstanceOf[JsArray].elements
        newL.take(oldL.size) shouldBe oldL
        write(dir,"result.json",JsObject("status"->JsString("CONVERGED"),"oldJournalRows"->JsNumber(oldJ.size),
          "oldLogRows"->JsNumber(oldL.size),"oldJournalPrefixUnchanged"->JsBoolean(true),"oldLogPrefixUnchanged"->JsBoolean(true),
          "worker"->current.state(),"state"->after))
      }finally {old.kill();if(current!=null)current.kill()}
    }
    "retain the original callback error and ACK prefix when the real SDK stops" taggedAs ExternalIntegration in {
      val (schema,id,dir,store)=fixture()
      val armed=new java.util.concurrent.atomic.AtomicBoolean(false)
      val injected=new java.util.concurrent.atomic.AtomicReference[JsValue]()
      val events=new java.util.concurrent.ConcurrentLinkedQueue[JsObject]()
      val c=session(schema,id,store,DebeziumEngineFactory.real,(k,v)=>{
        events.add(JsObject(v.fields+("kind"->JsString(k))))
        if(k=="RAW_CALLBACK" && armed.compareAndSet(true,false)){
          injected.set(v.fields("id"));throw new IllegalStateException("b04-injected-callback-failure")
        }
      })
      def observed=events.toArray.toVector.map(_.asInstanceOf[JsObject])
      try {
        c.start();awaitCondition("capture ready",90.seconds)(c.startupEvidence.phase=="ADMISSION_READY")
        c.subscribe("a","a","g1")
        awaitCondition("B bound",30.seconds)(store.subscription("a").exists(_.b.nonEmpty))
        armed.set(true)
        sql(settings,schema,"UPDATE a SET label='b04-fault',revision=revision+1 WHERE id=1")
        awaitCondition("real SDK callback failure",60.seconds)(c.failure.nonEmpty)
        val error=c.failure.get
        val errorChain=Iterator.iterate(Option(error))(_.flatMap(e=>Option(e.getCause))).takeWhile(_.nonEmpty).flatten.toVector
        val thrown=injected.get();require(thrown!=null,"fault callback was not identified")
        val acknowledged=observed.filter(_.fields.get("kind").contains(JsString("RAW_ACK_RETURNED"))).map(_.fields("id"))
        val first=c.requestClose();c.requestClose() shouldBe first
        val close=Await.result(first,15.seconds)
        write(dir,"b04-after-failure.json",JsObject("errorChain"->JsArray(errorChain.map(e=>JsString(e.toString))),
          "injectedCallback"->thrown,"acknowledged"->JsArray(acknowledged),"localTermination"->JsString(close.localTermination),
          "serverRelease"->JsString(close.serverRelease),"state"->dump(schema,id,store),"events"->JsArray(observed)))
        errorChain.exists(e=>Option(e.getMessage).exists(_.contains("b04-injected-callback-failure"))) shouldBe true
        acknowledged should not contain thrown
        close.reusable shouldBe false
        awaitCondition("callback failure physical release",180.seconds)(idle())
        write(dir,"result.json",JsObject("status"->JsString("CALLBACK_FAILURE_RETAINED"),"injectedCallback"->thrown,
          "acknowledged"->JsArray(acknowledged),"localTermination"->JsString(close.localTermination),
          "serverRelease"->JsString(close.serverRelease),"state"->dump(schema,id,store),"events"->JsArray(observed)))
      }finally scala.util.Try(c.close())
    }
    "pause before engine startup when source B committed but remains unbound with INITIAL D" taggedAs ExternalIntegration in {
      val (schema,id,dir,store)=fixture()
      val builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx512m","-Duser.timezone=UTC","-cp",sys.env("CAPTURE_LEGACY_CLASSPATH"),
        "cn.xuyinyin.magic.workflow.integration.CaptureStartupRecoveryWorker",schema,id,"128","SCHEMA_AND_CALLBACK")
      builder.redirectErrorStream(true)
      val child=new Child(builder.start(),dir.resolve("a07-legacy.log"))
      try {
        child.awaitEvent("PROCESS_READY");child.awaitEvent("SDK_CUTPOINT");child.awaitEvent("SDK_CALLBACK_HELD")
        awaitCondition("old schema and actual INITIAL",30.seconds){
          store.schema("a").nonEmpty && dump(schema,id,store).fields("D").asInstanceOf[JsArray].elements.exists(
            _.asJsObject.fields("offset_val").convertTo[String].contains("INITIAL"))
        }
        child.send("RELEASE_SDK");child.awaitEvent("SDK_GATE_RELEASED")
        child.send("SUBSCRIBE a a g1");child.awaitEvent("SOURCE_CONTROL_COMMIT_RETURNED")
        val cut=dump(schema,id,store)
        cut.fields("controls").asInstanceOf[JsArray].elements.size shouldBe 1
        store.subscription("a").get.status shouldBe "B_PENDING"
        store.subscription("a").get.b shouldBe None
        cut.fields("D").asInstanceOf[JsArray].elements.exists(_.asJsObject.fields("offset_val").convertTo[String].contains("INITIAL")) shouldBe true
        write(dir,"a07-committed-unbound-cut.json",JsObject("state"->cut,"events"->JsArray(child.events)))
        child.send("CRASH");child.exit() shouldBe 86
        awaitCondition("old worker physically released",180.seconds)(idle())
        val before=dump(schema,id,store);val creates=new AtomicInteger()
        val never=new DebeziumEngineFactory {def create(p:java.util.Properties):DebeziumEngineAccess={creates.incrementAndGet();throw new AssertionError("unsafe Engine creation")}}
        val c=session(schema,id,store,never,(_,_)=>())
        try {
          intercept[CaptureAdmissionException](c.start()).code shouldBe "UNBOUND_MARKER_UNPROVEN"
          creates.get() shouldBe 0
          dump(schema,id,store) shouldBe before
          write(dir,"result.json",JsObject("status"->JsString("SAFE_PAUSED"),"reason"->JsString("UNBOUND_MARKER_UNPROVEN"),
            "engineCreations"->JsNumber(creates.get()),"state"->before))
        }finally c.close()
      }finally child.kill()
    }
    "restart a schema-only snapshot without an old B and deliver the first new subscription" taggedAs ExternalIntegration in {
      val (schema,id,dir,store)=fixture()
      def worker(cp:String,gate:String,name:String):Child={
        val builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx512m","-Duser.timezone=UTC","-cp",cp,
          "cn.xuyinyin.magic.workflow.integration.CaptureStartupRecoveryWorker",schema,id,"128",gate)
        builder.redirectErrorStream(true)
        new Child(builder.start(),dir.resolve(name+".log"))
      }
      val original=worker(sys.env("CAPTURE_LEGACY_CLASSPATH"),"SCHEMA_EMITTED","schema-only-original")
      var recovered:Child=null
      try {
        original.awaitEvent("PROCESS_READY");original.awaitEvent("SDK_CUTPOINT")
        awaitCondition("initial checkpoint and schema",30.seconds){
          val d=dump(schema,id,store)
          d.fields("D").asInstanceOf[JsArray].elements.exists(_.asJsObject.fields("offset_val").convertTo[String].contains("INITIAL")) &&
            Vector("a","b","c","d","e","f","g").exists(t=>store.schema(t).nonEmpty)
        }
        val before=dump(schema,id,store)
        before.fields("controls").asInstanceOf[JsArray].elements shouldBe empty
        store.subscription("a") shouldBe None
        write(dir,"a04-before-crash.json",JsObject("worker"->original.state(),"state"->before))
        original.send("CRASH");original.exit() shouldBe 86
        awaitCondition("original physical release",180.seconds)(idle())
        recovered=worker(System.getProperty("java.class.path"),"NONE","schema-only-recovered")
        recovered.awaitEvent("PROCESS_READY")
        awaitCondition("recovered admission",90.seconds){
          recovered.send("CAPTURE_ADMISSION")
          recovered.awaitEvent("CAPTURE_ADMISSION").fields("phase")==JsString("ADMISSION_READY")
        }
        recovered.send("SUBSCRIBE a a g1");recovered.awaitEvent("SUBSCRIBE_ACCEPTED")
        awaitCondition("first B bound",30.seconds)(store.subscription("a").exists(_.b.nonEmpty))
        recovered.send("APPLY a a");recovered.awaitEvent("APPLY_READY")
        sql(settings,schema,"UPDATE a SET label='a04-recovered',amount=amount+0.1250,revision=revision+1,note='',updated_at='2026-10-27 01:02:03.123456' WHERE id=1")
        awaitCondition("source and sink all fields",30.seconds){val d=dump(schema,id,store);d.fields("source")==d.fields("target")}
        awaitCondition("actor checkpoint",30.seconds){
          val d=dump(schema,id,store)
          val actors=recovered.state().fields("actors").asJsObject.fields
          d.fields("T").asInstanceOf[JsArray].elements.nonEmpty && d.fields("J").asInstanceOf[JsArray].elements.nonEmpty &&
            actors.get("a").exists(_.asJsObject.fields("checkpoints").asInstanceOf[JsArray].elements.nonEmpty)
        }
        write(dir,"result.json",JsObject("status"->JsString("CONVERGED"),"noOldB"->JsBoolean(true),
          "worker"->recovered.state(),"state"->dump(schema,id,store)))
      }finally {original.kill();if(recovered!=null)recovered.kill()}
    }
    "keep admission closed after snapshot completion while actual INITIAL D is locked" taggedAs ExternalIntegration in {
      val (schema,id,dir,store)=fixture()
      val events=new java.util.concurrent.ConcurrentLinkedQueue[JsObject]()
      val factory=new CaptureStartupRecoveryFactory((k,v)=>events.add(JsObject(v.fields+("kind"->JsString(k)))),true)
      val c=session(schema,id,store,factory,(k,v)=>events.add(JsObject(v.fields+("kind"->JsString(k)))))
      val offset=MySQLCdcSourceNode.connectorStateTables(MySQLCdcStateConfig(stateSettings.url(schema),stateSettings.user,stateSettings.password,"capture_offsets","capture_history",0),id)._1
      var blocker:java.sql.Connection=null
      def actualD=withConnection(stateSettings,schema)(query(_,s"SELECT offset_key,offset_val FROM `$offset`"))
      def initial=actualD.elements.exists(_.asJsObject.fields("offset_val").convertTo[String].contains("INITIAL"))
      try {
        c.start()
        awaitCondition("snapshot gate and actual INITIAL",30.seconds)(
          events.toArray.exists(_.asInstanceOf[JsObject].fields("kind")==JsString("SDK_CUTPOINT")) && initial)
        blocker=DriverManager.getConnection(stateSettings.url(schema),stateSettings.user,stateSettings.password)
        blocker.setAutoCommit(false)
        query(blocker,s"SELECT offset_key,offset_val FROM `$offset` FOR UPDATE").elements should not be empty
        factory.release()
        awaitCondition("snapshot completed and original callback acknowledged",45.seconds){
          val x=c.startupEvidence
          x.snapshot=="COMPLETED" && x.confirmedAck>x.ackFloor
        }
        val locked=c.startupEvidence
        initial shouldBe true
        locked.phase shouldBe "STARTING"
        intercept[CaptureAdmissionException](c.subscribe("a","a","g1")).code shouldBe "CAPTURE_STARTING"
        store.subscription("a") shouldBe None
        write(dir,"initial-d-locked.json",JsObject("startup"->evidence(locked),"D"->actualD,
          "snapshot"->JsString(locked.snapshot),"ackFloor"->JsNumber(locked.ackFloor),
          "confirmedAck"->JsNumber(locked.confirmedAck),"state"->dump(schema,id,store),
          "events"->JsArray(events.toArray.toVector.map(_.asInstanceOf[JsObject]))))
        blocker.rollback();blocker.close();blocker=null
        awaitCondition("actual D advances and admission opens",90.seconds)(c.startupEvidence.phase=="ADMISSION_READY")
        initial shouldBe false
        write(dir,"result.json",JsObject("status"->JsString("READY_AFTER_D_UNLOCK"),"startup"->evidence(c.startupEvidence),
          "state"->dump(schema,id,store),"events"->JsArray(events.toArray.toVector.map(_.asInstanceOf[JsObject]))))
      } finally {
        if(blocker!=null){blocker.rollback();blocker.close()}
        factory.release()
        val last=scala.util.Try(dump(schema,id,store))
        write(dir,"a03-final-observation.json",JsObject("state"->last.getOrElse(JsNull),
          "observationError"->last.failed.toOption.map(e=>JsString(e.getClass.getName)).getOrElse(JsNull),
          "events"->JsArray(events.toArray.toVector.map(_.asInstanceOf[JsObject]))))
        c.close()
      }
    }
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
            val originalOffset=before.fields("D").asInstanceOf[JsArray].elements.head.asJsObject.fields
            val originalKey=originalOffset("offset_key").convertTo[String]
            val originalValue=originalOffset("offset_val").convertTo[String]
            def replaceOffset(key:String,value:String):Unit=withConnection(stateSettings,schema){c=>
              val q=c.prepareStatement(s"UPDATE `$offset` SET offset_key=?,offset_val=?")
              try {q.setString(1,key);q.setString(2,value);q.executeUpdate() shouldBe 1}finally q.close()
            }
            for((label,key,value)<-Vector(
              ("invalid-json",originalKey,"{invalid"),
              ("wrong-partition",JsArray(JsString(id),JsObject("server"->JsString(id+"_other"))).compactPrint,originalValue))) {
              try {
                replaceOffset(key,value)
                reject(label,"CHECKPOINT_INVALID")
                val kept=dump(schema,id,store).fields("D").asInstanceOf[JsArray].elements.head.asJsObject.fields
                kept("offset_key") shouldBe JsString(key);kept("offset_val") shouldBe JsString(value)
              }finally replaceOffset(originalKey,originalValue)
              dump(schema,id,store) shouldBe before
            }
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
