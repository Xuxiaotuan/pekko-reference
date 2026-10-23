package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.tags.ExternalIntegration
import cn.xuyinyin.magic.workflow.scheduling._
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import spray.json._
import java.nio.file.{Files,Path,StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import java.net.URLClassLoader
import java.io.File
import java.util.UUID
import scala.concurrent.{Await,ExecutionContext}
import scala.concurrent.duration._

/** This is a laboratory control protocol, not production supervisor automatic recovery. */
class RealMySQLGuardianRestartSpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._
  import GuardianProbeWire.{text,number,identityFormat}
  private val scenarios=Vector("S1_READ","S2_PREPARED","S2_FORK_PENDING","S3_ACK_LOST","S4_PRE_BOOTSTRAP",
    "S13_LOCK_CONTENDER","S13_SIGSTOP","S14_AUTH_IDENTITY","S15_GUARDIAN_DEAD","S15_CAPABILITY_LOST")
  private def cp:String={
    val urls=Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(l=>Option(l.getParent))).takeWhile(_.nonEmpty).flatten
      .collect{case l:URLClassLoader=>l.getURLs.iterator}.flatten.filter(_.getProtocol=="file").map(u=>Path.of(u.toURI).toString)
    (urls++System.getProperty("java.class.path","").split(File.pathSeparator)).filter(_.nonEmpty).toVector.distinct.mkString(File.pathSeparator)
  }
  private def event(c:Child,k:String):JsObject={awaitCondition("process event "+k,45.seconds){c.events.exists(_.fields("kind")==JsString(k))};c.events.find(_.fields("kind")==JsString(k)).get}
  private def workerEvent(dir:Path,boot:String,kind:String):Unit=awaitCondition("worker "+kind,45.seconds){
    val p=dir.resolve(boot+".worker.log");Files.exists(p) && Files.readString(p).contains("\"kind\":\""+kind+"\"")
  }
  private def observedProcess(pid:Long):JsObject={
    val h=ProcessHandle.of(pid)
    JsObject("pid"->JsNumber(pid),"alive"->JsBoolean(h.isPresent && h.get.isAlive),
      "start"->(if(h.isPresent && h.get.info().startInstant().isPresent)JsString(h.get.info().startInstant().get().toString)else JsNull))
  }
  "separate supervisor and guardian JVMs" should {
    scenarios.foreach { scenario=>
      s"preserve identity, quota and full data evidence for $scenario" taggedAs ExternalIntegration in {
        if(!sys.env.get("RUN_MYSQL_GUARDIAN_PROBE").contains("1"))cancel("external_blocked: explicit isolated loopback MySQL flag required")
        val s=Settings.environment();val suffix=UUID.randomUUID().toString.replace("-","").take(12);val schema="pekko_test_cdc_window_"+suffix
        val dir=Path.of("target","guardian-restart",scenario+"-"+suffix).toAbsolutePath;Files.createDirectories(dir)
        val anchor=Path.of("/tmp","gp-"+suffix);Files.createDirectory(anchor,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val secret=new Array[Byte](32);new java.security.SecureRandom().nextBytes(secret)
        Files.write(anchor.resolve("auth.key"),secret);Files.setPosixFilePermissions(anchor.resolve("auth.key"),PosixFilePermissions.fromString("rw-------"))
        OwnerIsolationProbeSupport.provision(s,schema,"owner-probe-"+suffix)
        withConnection(s,schema){c=>
          val in=getClass.getClassLoader.getResourceAsStream("db/mysql/pekko-snapshot-ownership-schema.sql")
          val sql=try scala.io.Source.fromInputStream(in).mkString finally in.close()
          sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(executeSql(c,_))
          executeSql(c,"CREATE TABLE guardian_probe_controller (id INT PRIMARY KEY,epoch BIGINT NOT NULL,controller VARCHAR(64),guardian VARCHAR(64)) ENGINE=InnoDB")
          executeSql(c,"INSERT INTO guardian_probe_controller VALUES (1,0,NULL,NULL)")
          executeSql(c,"CREATE TABLE guardian_probe_launches (boot VARCHAR(64) PRIMARY KEY,epoch BIGINT NOT NULL,guardian VARCHAR(64) NOT NULL,phase VARCHAR(24) NOT NULL) ENGINE=InnoDB")
          executeSql(c,"CREATE TABLE guardian_probe_journal_writes (sequence_number BIGINT,owner_id VARCHAR(128),permit_id VARCHAR(64),generation BIGINT,admission_sequence BIGINT) ENGINE=InnoDB")
          executeSql(c,"""CREATE TRIGGER guardian_probe_journal_write AFTER INSERT ON event_journal FOR EACH ROW
            BEGIN IF NEW.persistence_id='workflow-table-00' THEN INSERT INTO guardian_probe_journal_writes
            SELECT NEW.sequence_number,
            (SELECT ATTR_VALUE FROM performance_schema.session_connect_attrs WHERE PROCESSLIST_ID=CONNECTION_ID() AND ATTR_NAME='pekko_owner'),
            (SELECT permit_id FROM pekko_snapshot_writer_permissions WHERE workflow_id='table-00'),
            (SELECT generation FROM pekko_snapshot_writer_permissions WHERE workflow_id='table-00'),
            (SELECT queue_sequence FROM pekko_table_admission_lock WHERE id=1); END IF; END""")
        }
        val db=new GuardianProbeStore(schema,"probe-harness")
        implicit val ec:ExecutionContext=ExecutionContext.global
        val expectedExecution=Await.result(new TableSchedulingRuntime(db.store,"probe-harness").submit(OwnerIsolationProbeSupport.workflow(s,schema),"takeover-one"),10.seconds).executionId.get
        val children=scala.collection.mutable.ArrayBuffer.empty[(String,Child)]
        def start(name:String,main:String,args:String*):Child={
          val b=new ProcessBuilder((Vector(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx256m","-Duser.timezone=UTC","-cp",cp,main)++args).toArray:_*)
          b.redirectErrorStream(true);val child=new Child(b.start(),dir.resolve(name+".log"));children+=name->child
          write(dir,name+"-process.json",observedProcess(child.pid));child
        }
        def supervisor(mode:String):Child=start(mode,GuardianProbeSupervisor.getClass.getName.stripSuffix("$"),schema,anchor.toString,dir.toString,mode,scenario)
        var guardian:Child=null;var oldBoot="";var suspended:Child=null
        var completed=false
        try {
          guardian=start("guardian",GuardianProbeGuardian.getClass.getName.stripSuffix("$"),schema,anchor.toString,dir.toString)
          event(guardian,"GUARDIAN_READY")
          val pin=GuardianProbeWire.jsonFile(anchor.resolve("pin.json"));write(dir,"guardian-pin.json",pin)
          withConnection(s,schema)(executeSql(_,s"UPDATE guardian_probe_controller SET guardian='${text(pin,"guardian")}' WHERE id=1"))
          val a=supervisor("A");val bound=event(a,"CONTROLLER_BOUND")
          oldBoot=text(event(a,"PREPARED"),"boot")
          if(scenario=="S2_FORK_PENDING")event(guardian,"BEFORE_FORK")
          else if(scenario!="S2_PREPARED") {
            event(guardian,"LAUNCH_CREATED")
            workerEvent(dir,oldBoot,if(scenario=="S4_PRE_BOOTSTRAP")"BEFORE_BOOTSTRAP" else "CUTPOINT")
          }
          val originalIdentity=if(Files.exists(dir.resolve(oldBoot+".identity.json")))Some(GuardianProbeWire.jsonFile(dir.resolve(oldBoot+".identity.json")).convertTo[SnapshotOwnerIdentity])else None
          val cut=db.capture;write(dir,"cut.json",cut)
          if(scenario.startsWith("S13_")) {
            if(scenario=="S13_SIGSTOP") {
              require(a.alive);require(new ProcessBuilder("/bin/kill","-STOP",a.pid.toString).start().waitFor()==0);suspended=a
            }
            val c=supervisor("C");event(c,"LOCK_BLOCKED");c.exit() shouldBe 0
            Thread.sleep(500);a.alive shouldBe true
            db.capture.fields("data").asJsObject.fields("T") shouldBe cut.fields("data").asJsObject.fields("T")
            db.capture.fields("controller") shouldBe cut.fields("controller")
            write(dir,"contender-blocked.json",db.capture)
            if(suspended!=null){require(new ProcessBuilder("/bin/kill","-CONT",a.pid.toString).start().waitFor()==0);suspended=null}
          }
          if(scenario=="S14_AUTH_IDENTITY") {
            val client=new GuardianProbeWire.Client(anchor,dir.resolve("negative-wire.jsonl"),text(bound,"controller"),number(bound,"epoch"))
            val id=originalIdentity.get;val arguments=JsObject("identity"->id.toJson,"operation"->JsString("negative-op"))
            val firstRequest=Files.readAllLines(dir.resolve("A-wire.jsonl")).get(0).parseJson.asJsObject.fields("request").asJsObject
            client.replay(firstRequest).fields("ok") shouldBe JsBoolean(false)
            intercept[IllegalArgumentException](client.request("STOP",arguments,Some("invalid-authenticator")))
            val pinned=Files.readString(anchor.resolve("pin.json"))
            val other=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val wrongPin=JsObject(pin.fields.updated("publicKey",JsString(java.util.Base64.getEncoder.encodeToString(other.getPublic.getEncoded))))
            try {
              Files.writeString(anchor.resolve("pin.json"),wrongPin.prettyPrint)
              val wrongClient=new GuardianProbeWire.Client(anchor,dir.resolve("wrong-pin-wire.jsonl"),text(bound,"controller"),number(bound,"epoch"))
              intercept[IllegalArgumentException](wrongClient.request("INVENTORY"))
              write(dir,"wrong-pin-rejected.json",wrongPin)
            }finally Files.writeString(anchor.resolve("pin.json"),pinned)
            val foreign=new ProcessBuilder("/bin/cat").start()
            try {
              val wrong=id.copy(pid=foreign.pid(),startIdentity=foreign.info().startInstant().orElseThrow().toString)
              intercept[IllegalArgumentException](client.request("STOP",JsObject("identity"->wrong.toJson,"operation"->JsString("wrong-id"))))
              foreign.isAlive shouldBe true
              write(dir,"foreign-process-survived.json",observedProcess(foreign.pid()))
            }finally foreign.destroyForcibly().waitFor()
            observedProcess(id.pid).fields("alive") shouldBe JsBoolean(true)
            db.capture.fields("permissions") shouldBe cut.fields("permissions")
            db.capture.fields("data").asJsObject.fields("J") shouldBe cut.fields("data").asJsObject.fields("J")
          }
          if(scenario=="S15_CAPABILITY_LOST") {
            val client=new GuardianProbeWire.Client(anchor,dir.resolve("capability-wire.jsonl"),text(bound,"controller"),number(bound,"epoch"))
            client.request("LOSE_CAPABILITY")
          }
          a.alive shouldBe true;a.kill()
          if(scenario=="S15_GUARDIAN_DEAD")guardian.kill()
          if(scenario=="S2_FORK_PENDING") {
            Files.writeString(dir.resolve("fork.release"),"release");event(guardian,"LAUNCH_CREATED")
            workerEvent(dir,oldBoot,"CUTPOINT")
          }
          if(scenario=="S3_ACK_LOST")Files.writeString(dir.resolve("reply.release"),"release")
          originalIdentity.foreach { id=>
            observedProcess(id.pid).fields("alive") shouldBe JsBoolean(true)
            write(dir,"after-supervisor-death-worker-alive.json",observedProcess(id.pid))
          }
          write(dir,"supervisor-dead.json",JsObject("supervisorPid"->JsNumber(a.pid),"supervisorAlive"->JsBoolean(a.alive),
            "guardianPid"->JsNumber(guardian.pid),"guardianAlive"->JsBoolean(guardian.alive),"state"->db.capture))
          val beforeB=db.capture;val b=supervisor("B")
          if(scenario.startsWith("S15_")) {
            event(b,"SAFE_PAUSE");b.exit() shouldBe 0
            val after=db.capture;write(dir,"paused.json",after)
            Vector("owners","permissions","launches").foreach(k=>after.fields(k) shouldBe beforeB.fields(k))
            Vector("T","J","control","audit").foreach(k=>after.fields("data").asJsObject.fields(k) shouldBe beforeB.fields("data").asJsObject.fields(k))
            originalIdentity.foreach(id=>observedProcess(id.pid).fields("alive") shouldBe JsBoolean(true))
            intercept[IllegalArgumentException](db.prepare(UUID.randomUUID().toString,2))
            val comparison=after.fields("data").asJsObject.fields("comparison").asJsObject
            comparison.fields("sourceCount") shouldBe JsNumber(10);comparison.fields("targetCount") shouldBe JsNumber(0)
            comparison.fields("equal") shouldBe JsBoolean(false)
          } else {
            event(b,"RECOVERY_COMPLETED");b.exit() shouldBe 0
            val finalState=db.capture;write(dir,"final.json",finalState)
            val data=finalState.fields("data").asJsObject
            data.fields("comparison").asJsObject.fields("equal") shouldBe JsBoolean(true)
            data.fields("comparison").asJsObject.fields("sourceCount") shouldBe JsNumber(10)
            data.fields("audit").asInstanceOf[JsArray].elements.size shouldBe 10
            data.fields("T").asInstanceOf[JsArray].elements.size shouldBe 5
            db.store.get("table-00").get.executionId shouldBe Some(expectedExecution)
            val journalWrites=withConnection(s,schema)(query(_,"SELECT * FROM guardian_probe_journal_writes ORDER BY sequence_number"))
            write(dir,"journal-writer-permits.json",journalWrites)
            val audit=finalState.fields("audit").asInstanceOf[JsArray].elements.map(_.asJsObject)
            val oldReturns=audit.filter(_.fields("event_type")==JsString("old_quota_released")).map(_.fields("event_sequence").convertTo[String].toLong)
            val finalPermit=db.ha.permit("table-00").get
            val newPermission=audit.find(r=>r.fields("event_type")==JsString("recovery_permitted") &&
              r.fields("details").convertTo[String].parseJson.asJsObject.fields("permit")==JsString(finalPermit.id)).get
            if(oldReturns.nonEmpty)oldReturns.max should be < newPermission.fields("event_sequence").convertTo[String].toLong
            finalPermit.generation shouldBe (if(beforeB.fields("permissions").asInstanceOf[JsArray].elements.nonEmpty)2L else 1L)
            journalWrites.elements should not be empty
            journalWrites.elements.foreach { row=>
              val r=row.asJsObject
              val permission=audit.find(a=>a.fields("event_type")==JsString("recovery_permitted") && a.fields("owner_id")==r.fields("owner_id") &&
                a.fields("details").convertTo[String].parseJson.asJsObject.fields("permit")==r.fields("permit_id")).get
              val details=permission.fields("details").convertTo[String].parseJson.asJsObject
              r.fields("generation").convertTo[String].toLong shouldBe details.fields("generation").convertTo[Long]
              r.fields("admission_sequence").convertTo[String].toLong should be >= permission.fields("event_sequence").convertTo[String].toLong
            }
            originalIdentity.foreach { id=>
              val events=audit.filter(_.fields("owner_id")==JsString(id.bootId))
              if(scenario=="S4_PRE_BOOTSTRAP")db.ha.owner(id.bootId).get.phase shouldBe "startup_failed"
              else {
                db.ha.owner(id.bootId).get.phase shouldBe "db_drained"
                def seq(k:String)=events.find(_.fields("event_type")==JsString(k)).get.fields("event_sequence").convertTo[String].toLong
                seq("fenced") should be < seq("db_drained")
                seq("db_drained") should be < seq("old_quota_released")
              }
            }
            if(scenario=="S14_AUTH_IDENTITY") {
              val stale=new GuardianProbeWire.Client(anchor,dir.resolve("stale-epoch-wire.jsonl"),text(bound,"controller"),number(bound,"epoch"))
              intercept[IllegalArgumentException](stale.request("INVENTORY"))
              db.capture.fields("permissions") shouldBe finalState.fields("permissions")
            }
            if(scenario=="S2_PREPARED") {
              val controller=event(b,"CONTROLLER_BOUND")
              val client=new GuardianProbeWire.Client(anchor,dir.resolve("late-launch-wire.jsonl"),text(controller,"controller"),number(controller,"epoch"))
              intercept[IllegalArgumentException](client.request("LAUNCH",JsObject("boot"->JsString(oldBoot))))
            }
          }
          write(dir,"result.json",JsObject("scenario"->JsString(scenario),"schema"->JsString(schema),"outcome"->JsString(if(scenario.startsWith("S15_"))"SAFE_PAUSE" else "CONVERGED"),
            "originalExecution"->JsString(expectedExecution),"D"->JsString("N/A"),"productionSupervisorProtocol"->JsBoolean(false),
            "runtime"->JsString(sys.env.getOrElse("GUARDIAN_PROBE_RUNTIME","unfrozen"))))
          completed=true
        }catch{case e:Throwable=>
          write(dir,"failure.json",JsObject("class"->JsString(e.getClass.getName),"message"->JsString(Option(e.getMessage).getOrElse("").replace(s.password,"<redacted>")),"state"->db.capture));throw e
        }finally {
          var workerCleanupError=Option.empty[Throwable]
          try {
            if(suspended!=null)new ProcessBuilder("/bin/kill","-CONT",suspended.pid.toString).start().waitFor()
            children.filterNot(_._1=="guardian").foreach(_._2.kill())
            val stream=Files.list(dir)
            try stream.forEach { p=>if(p.toString.endsWith(".commands"))Files.writeString(p,"STOP_TEST\n",StandardOpenOption.APPEND) }finally stream.close()
            awaitCondition("test workers self-halted without granting recovery authority",20.seconds) {
              val ids=Files.list(dir)
              try {val it=ids.iterator();var aliveOrUnknown=false;while(it.hasNext){val p=it.next();if(p.toString.endsWith(".identity.json")){
                val id=GuardianProbeWire.jsonFile(p).convertTo[SnapshotOwnerIdentity];val process=ProcessHandle.of(id.pid)
                if(process.isPresent && process.get.isAlive) {
                  val started=process.get.info().startInstant()
                  if(!started.isPresent || started.get().toString==id.startIdentity)aliveOrUnknown=true
                }
              }};!aliveOrUnknown}finally ids.close()
            }
          }catch{case e:Exception=>workerCleanupError=Some(e)}
          finally {try {if(guardian!=null)guardian.kill()}finally db.close()}
          workerCleanupError.foreach(e=>write(dir,"worker-cleanup-unconfirmed.json",JsObject("errorClass"->JsString(e.getClass.getName),"confirmed"->JsBoolean(false))))
          write(dir,"cleanup.json",JsObject("testOnlySelfHalt"->JsBoolean(true),"cleanupIsNotIsolationProof"->JsBoolean(true),"scenarioCompleted"->JsBoolean(completed),
            "processes"->JsArray(children.toVector.map{case(n,c)=>JsObject("role"->JsString(n),"pid"->JsNumber(c.pid),"alive"->JsBoolean(c.alive))})))
          awaitCondition("test schema database clients ended",20.seconds){
            val state=withConnection(s,schema)(query(_,s"SELECT COUNT(*) AS clients FROM information_schema.processlist WHERE DB='$schema' AND ID<>CONNECTION_ID()"))
            write(dir,"database-cleanup.json",state);state.elements.head.asJsObject.fields("clients")==JsString("0")
          }
          if(completed)workerCleanupError.foreach(throw _)
        }
      }
    }
  }
}
