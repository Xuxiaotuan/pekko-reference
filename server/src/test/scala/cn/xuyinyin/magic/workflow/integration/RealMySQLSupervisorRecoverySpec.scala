package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.tags.ExternalIntegration
import cn.xuyinyin.magic.workflow.scheduling._
import com.typesafe.config.{ConfigFactory,ConfigRenderOptions}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import spray.json._
import java.nio.file.{Files,Path,StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import java.util.{Base64,UUID}
import java.net.URLClassLoader
import java.io.File
import scala.jdk.CollectionConverters._
import scala.concurrent.{Await,ExecutionContext}
import scala.concurrent.duration._

/** Three independent JVM roles; production control runtime plus test-only timing/SQL fault wrappers. */
class RealMySQLSupervisorRecoverySpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._
  import SnapshotGuardianWire._
  private val scenarios=Vector("S1_READ","S2_PREPARED","S2_FORK_PENDING","S3_ACK_LOST","S4_PRE_BOOTSTRAP",
    "S5_TARGET_TX","S6_T_BEFORE_J","S7_J_AFTER_STORE","S8_COMMITTED_UNKNOWN","S8_UNCOMMITTED_UNKNOWN",
    "S9_PROOF_REPLY_LOST","S9_FENCE_REPLY_LOST","S10_SOURCE_RESIDUAL","S10_TARGET_RESIDUAL","S10_JOURNAL_RESIDUAL","S10_SNAPSHOT_RESIDUAL",
    "S10_OBSERVATION_RESTART","S10_OBSERVER_DENIED","S11_EPOCH_REPLY_LOST","S11_TICKET_REPLY_LOST","S11_DISPATCH_REPLY_LOST",
    "S11_QUOTA_REPLY_LOST","S11_RETIRE_REPLY_LOST","S11_PERMIT_REPLY_LOST","S12_RECOVERY_JOURNAL","S12_T_BEFORE_J",
    "S13_LOCK_CONTENDER","S13_SIGSTOP","S14_AUTH_IDENTITY","S15_GUARDIAN_DEAD","S15_CAPABILITY_LOST","S15_AFTER_PROOF")
  private def cp:String={
    val urls=Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(l=>Option(l.getParent))).takeWhile(_.nonEmpty).flatten
      .collect{case l:URLClassLoader=>l.getURLs.iterator}.flatten.filter(_.getProtocol=="file").map(u=>Path.of(u.toURI).toString)
    (urls++System.getProperty("java.class.path","").split(File.pathSeparator)).filter(_.nonEmpty).toVector.distinct.mkString(File.pathSeparator)
  }
  private def event(c:Child,kind:String):JsObject={
    awaitCondition("JVM event "+kind,60.seconds){c.events.exists(_.fields("kind")==JsString(kind)) || !c.alive}
    c.events.find(_.fields("kind")==JsString(kind)).getOrElse(throw new AssertionError("JVM exited before "+kind))
  }
  private def workerEvent(dir:Path,boot:String,kind:String):Unit=awaitCondition("worker "+kind,60.seconds){
    val p=dir.resolve(boot+".worker.log");Files.exists(p) && Files.readString(p).contains("\"kind\":\""+kind+"\"")
  }
  private def process(pid:Long):JsObject={val h=ProcessHandle.of(pid);JsObject("pid"->JsNumber(pid),"alive"->JsBoolean(h.isPresent && h.get.isAlive),"start"->(if(h.isPresent && h.get.info().startInstant().isPresent)JsString(h.get.info().startInstant().get().toString)else JsNull))}
  private def journalPages(state:JsObject):Vector[JsObject]={
    val mapper=new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.cbor.CBORFactory())
    state.fields("data").asJsObject.fields("J").asInstanceOf[JsArray].elements.filter(_.asJsObject.fields("persistence_id")==JsString("workflow-table-00")).map { row=>
      mapper.readTree(Base64.getMimeDecoder.decode(row.asJsObject.fields("payload_base64").convertTo[String])).toString.parseJson.asJsObject
    }.filter(_.fields.get("update").exists(_.asJsObject.fields.get("_type").exists(_.convertTo[String].endsWith(".CommitSnapshotPage")))).toVector
  }
  private def rejected(socket:Path,secret:Array[Byte],binding:GuardianBinding,request:JsObject,log:Path,badMac:Boolean=false):Unit={
    val raw=request.compactPrint;val mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(secret,"HmacSHA256"))
    val envelope=JsObject("payload"->JsString(raw),"mac"->JsString(if(badMac)"invalid" else Base64.getEncoder.encodeToString(mac.doFinal(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)))))
    val c=java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX)
    try {
      c.connect(java.net.UnixDomainSocketAddress.of(socket));c.configureBlocking(false)
      val out=java.nio.ByteBuffer.wrap((envelope.compactPrint+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8))
      val end=System.nanoTime()+10000000000L
      while(out.hasRemaining && System.nanoTime()<end)c.write(out)
      val bytes=new java.io.ByteArrayOutputStream;val buf=java.nio.ByteBuffer.allocate(4096)
      while(!bytes.toString("UTF-8").contains("\n") && bytes.size()<1048576 && System.nanoTime()<end){
        buf.clear();val n=c.read(buf);require(n>=0);if(n>0)bytes.write(buf.array(),0,n)else Thread.sleep(5)
      }
      val response=bytes.toString("UTF-8").trim.parseJson.asJsObject;SupervisorRecoveryTest.append(log,JsObject("request"->envelope,"response"->response))
      val verified=SnapshotGuardianWire.verified(response,binding.publicKey)
      verified.fields("request") shouldBe request;verified.fields("ok") shouldBe JsBoolean(false)
    }finally c.close()
  }
  "production supervisor JVM recovery" should {
    scenarios.foreach { scenario=>
      s"preserve original execution, T/J and full field evidence for $scenario" taggedAs ExternalIntegration in {
        if(!sys.env.get("RUN_MYSQL_SUPERVISOR_RECOVERY").contains("1"))cancel("external_blocked: explicit disposable loopback MySQL required")
        val s=Settings.environment();val suffix=UUID.randomUUID().toString.replace("-","").take(12)
        val schema="pekko_test_cdc_window_"+suffix;val gate="owner-probe-"+suffix
        val dir=Path.of("target","supervisor-recovery",scenario+"-"+suffix).toAbsolutePath;Files.createDirectories(dir)
        val anchor=Path.of("/tmp","sr-"+suffix);Files.createDirectory(anchor,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();val publicKey=Base64.getEncoder.encodeToString(keys.getPublic.getEncoded)
        val secret=new Array[Byte](32);new java.security.SecureRandom().nextBytes(secret)
        Files.write(anchor.resolve("auth.key"),secret);Files.writeString(anchor.resolve("signing.key"),Base64.getEncoder.encodeToString(keys.getPrivate.getEncoded))
        Vector("auth.key","signing.key").foreach(n=>Files.setPosixFilePermissions(anchor.resolve(n),PosixFilePermissions.fromString("rw-------")))
        Files.writeString(anchor.resolve("worker.conf"),"# fixed test worker configuration; actual worker uses production snapshot bootstrap\n")
        val binding=GuardianBinding("takeover-experiment","host-"+suffix,UUID.randomUUID().toString,publicKey)
        val cfg=Map[String,AnyRef](
          "pekko.workflow.snapshot-ha.deployment-id"->binding.deploymentId,"pekko.workflow.snapshot-ha.instances"->java.util.Collections.emptyList[AnyRef](),
          "pekko.workflow.snapshot-supervisor.host-id"->binding.hostId,"pekko.workflow.snapshot-supervisor.guardian-boot"->binding.bootId,"pekko.workflow.snapshot-supervisor.public-key"->publicKey,
          "pekko.workflow.snapshot-supervisor.max-workers"->Int.box(1),"pekko.workflow.snapshot-supervisor.socket"->anchor.resolve("rpc.sock").toString,
          "pekko.workflow.snapshot-supervisor.auth-key-file"->anchor.resolve("auth.key").toString,"pekko.workflow.snapshot-supervisor.private-key-file"->anchor.resolve("signing.key").toString,
          "pekko.workflow.snapshot-supervisor.guardian-lock"->anchor.resolve("guardian.lock").toString,"pekko.workflow.snapshot-supervisor.supervisor-lock"->anchor.resolve("supervisor.lock").toString,
          "pekko.workflow.snapshot-supervisor.worker-config-file"->anchor.resolve("worker.conf").toString,"pekko.workflow.snapshot-supervisor.worker-xmx-mib"->Int.box(512),
          "pekko.workflow.table-scheduling.jdbc.url"->s.url(schema),"pekko.workflow.table-scheduling.jdbc.username"->s.user,"pekko.workflow.table-scheduling.jdbc.password"->s.password,
          "pekko.workflow.table-scheduling.max-active-tables"->Int.box(2),"pekko.workflow.table-scheduling.max-active-tables-per-node"->Int.box(1),
          "pekko.workflow.table-scheduling.max-connections-per-instance"->Int.box(4),"pekko.workflow.table-scheduling.max-concurrent-starts"->Int.box(1),
          "pekko.workflow.table-scheduling.max-page-reservation"->"24 KiB","pekko.workflow.table-scheduling.candidate-page"->Int.box(16),
          "pekko-persistence-jdbc.shared-databases.slick.db.url"->s.url(schema),"pekko-persistence-jdbc.shared-databases.slick.db.user"->s.user,"pekko-persistence-jdbc.shared-databases.slick.db.password"->s.password)
        val config=anchor.resolve("supervisor.conf");Files.writeString(config,ConfigFactory.parseMap(cfg.asJava).root.render(ConfigRenderOptions.concise()));Files.setPosixFilePermissions(config,PosixFilePermissions.fromString("rw-------"))
        OwnerIsolationProbeSupport.provision(s,schema,gate)
        withConnection(s,schema){c=>
          Vector("db/mysql/pekko-snapshot-ownership-schema.sql","db/mysql/pekko-snapshot-supervisor-schema.sql").foreach { name=>
            val in=getClass.getClassLoader.getResourceAsStream(name);val sql=try scala.io.Source.fromInputStream(in).mkString finally in.close()
            sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(executeSql(c,_))
          }
          executeSql(c,"CREATE TABLE supervisor_journal_writes (sequence_number BIGINT,owner_id VARCHAR(128),permit_id VARCHAR(64),generation BIGINT,admission_sequence BIGINT) ENGINE=InnoDB")
          executeSql(c,"""CREATE TRIGGER supervisor_journal_write AFTER INSERT ON event_journal FOR EACH ROW BEGIN IF NEW.persistence_id='workflow-table-00' THEN
            INSERT INTO supervisor_journal_writes SELECT NEW.sequence_number,
            (SELECT ATTR_VALUE FROM performance_schema.session_connect_attrs WHERE PROCESSLIST_ID=CONNECTION_ID() AND ATTR_NAME='pekko_owner'),
            (SELECT permit_id FROM pekko_snapshot_writer_permissions WHERE workflow_id='table-00'),
            (SELECT generation FROM pekko_snapshot_writer_permissions WHERE workflow_id='table-00'),
            (SELECT queue_sequence FROM pekko_table_admission_lock WHERE id=1); END IF; END""")
          if(scenario=="S10_SNAPSHOT_RESIDUAL")executeSql(c,s"CREATE TRIGGER supervisor_snapshot_wait BEFORE INSERT ON snapshot FOR EACH ROW BEGIN IF NEW.persistence_id='workflow-table-00' AND (SELECT id FROM owner_probe_journal_gate WHERE id=1)=1 THEN SET @snapshot_wait=GET_LOCK('$gate',90); DO RELEASE_LOCK('$gate'); END IF; END")
        }
        val residual=scenario.startsWith("S10_") && scenario.endsWith("_RESIDUAL")
        val proxy=if(residual)Some(new SupervisorResidualProxy(s.host,s.port))else None
        proxy.foreach(p=>Files.writeString(dir.resolve("proxy-port"),p.port.toString))
        val settings=new SnapshotSupervisorSettings(config);implicit val ec:ExecutionContext=ExecutionContext.global
        val proxyRole=if(scenario=="S10_TARGET_RESIDUAL")"target" else if(scenario=="S10_SOURCE_RESIDUAL")"source" else "journal"
        val execution=Await.result(new TableSchedulingRuntime(settings.tableStore,"harness").submit(SupervisorRecoveryTest.workflow(s,schema,proxy.map(_.port),proxyRole),"takeover-one"),10.seconds).executionId.get
        val children=scala.collection.mutable.ArrayBuffer.empty[(String,Child)]
        def start(name:String,main:String,args:String*):Child={
          val p=new ProcessBuilder((Vector(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx256m","-Duser.timezone=UTC","-cp",cp,main)++args).toArray:_*).redirectErrorStream(true).start()
          val child=new Child(p,dir.resolve(name+".log"));children+=name->child;write(dir,name+"-process.json",process(child.pid));child
        }
        def supervisor(mode:String)=start(mode,SupervisorRecoverySupervisor.getClass.getName.stripSuffix("$"),config.toString,dir.toString,schema,scenario,mode)
        def state=SupervisorRecoveryTest.capture(settings,schema)
        def release(name:String):Unit=Files.writeString(dir.resolve(name+".release"),"release")
        var guardian:Child=null;var suspended:Child=null;var oldBoot="";var completed=false
        val holder=java.sql.DriverManager.getConnection(s.url(schema)+"&connectionAttributes=pekko_owner:harness,pekko_role:observer",s.user,s.password)
        try {
          if(residual)query(holder,s"SELECT GET_LOCK('$gate',0) AS acquired").elements.head.asJsObject.fields("acquired") shouldBe JsString("1")
          if(scenario=="S12_RECOVERY_JOURNAL")query(holder,s"SELECT GET_LOCK('${gate}-recovery',0) AS acquired")
          guardian=start("guardian",SupervisorRecoveryGuardian.getClass.getName.stripSuffix("$"),config.toString,dir.toString,schema,scenario,gate)
          event(guardian,"GUARDIAN_READY");write(dir,"guardian-binding.json",binding.toJson)
          val a=supervisor("A");val bound=event(a,"CONTROLLER_BOUND").fields("controller").convertTo[SupervisorControl]
          if(scenario.startsWith("S11_TICKET") || scenario.startsWith("S11_DISPATCH"))event(a,"CONTROL_READBACK_WAIT")
          else event(a,"LAUNCH_PREPARED")
          oldBoot=settings.metadata.pending.head.bootId;Files.writeString(dir.resolve("old-boot"),oldBoot)
          if(scenario=="S2_FORK_PENDING")event(guardian,"BEFORE_FORK")
          else if(scenario=="S2_PREPARED" || scenario.startsWith("S11_TICKET") || scenario.startsWith("S11_DISPATCH"))()
          else if(scenario=="S4_PRE_BOOTSTRAP")workerEvent(dir,oldBoot,"BEFORE_BOOTSTRAP")
          else if(scenario=="S10_JOURNAL_RESIDUAL") {
            workerEvent(dir,oldBoot,"BEFORE_JOURNAL_PAGE")
            executeSql(holder,"UPDATE owner_probe_journal_gate SET armed=true WHERE id=1")
            Files.writeString(dir.resolve(oldBoot+".commands"),"RELEASE\n",StandardOpenOption.APPEND)
          }else if(scenario!="S10_SNAPSHOT_RESIDUAL" && scenario!="S10_SOURCE_RESIDUAL")workerEvent(dir,oldBoot,"CUTPOINT")
          def database=withConnection(s,schema)(query(_,s"SELECT p.ID,p.STATE,p.INFO,a.ATTR_VALUE AS owner,r.ATTR_VALUE AS role,t.trx_id,t.trx_state FROM information_schema.processlist p LEFT JOIN performance_schema.session_connect_attrs a ON a.PROCESSLIST_ID=p.ID AND a.ATTR_NAME='pekko_owner' LEFT JOIN performance_schema.session_connect_attrs r ON r.PROCESSLIST_ID=p.ID AND r.ATTR_NAME='pekko_role' LEFT JOIN information_schema.innodb_trx t ON t.trx_mysql_thread_id=p.ID WHERE p.DB='$schema' AND p.ID<>CONNECTION_ID() ORDER BY p.ID"))
          if(residual)awaitCondition("actual old role transaction waits in server GET_LOCK",30.seconds){
            val rows=database;write(dir,"database-wait.json",rows)
            rows.elements.exists(r=>{val f=r.asJsObject.fields;f("owner")==JsString(oldBoot) && f("trx_id")!=JsNull && f("STATE").toString.toLowerCase.contains("user lock") && f("role")==JsString(proxyRole)})
          }
          val cut=state;write(dir,"cut.json",cut)
          val initialPages=cut.fields("data").asJsObject.fields("T").asInstanceOf[JsArray].elements.size
          if(Set("S6_T_BEFORE_J","S7_J_AFTER_STORE","S8_COMMITTED_UNKNOWN").contains(scenario))initialPages shouldBe 1
          if(Set("S5_TARGET_TX","S8_UNCOMMITTED_UNKNOWN").contains(scenario))initialPages shouldBe 0
          if(Set("S5_TARGET_TX","S6_T_BEFORE_J","S8_COMMITTED_UNKNOWN","S8_UNCOMMITTED_UNKNOWN").contains(scenario))journalPages(cut).size shouldBe 0
          if(scenario=="S7_J_AFTER_STORE")journalPages(cut).size shouldBe 1
          if(scenario.startsWith("S13_")) {
            if(scenario=="S13_SIGSTOP"){OwnerIsolationProbeSupport.signal(a,"STOP");suspended=a}
            val c=supervisor("contender");event(c,"LOCK_BLOCKED");c.exit() shouldBe 0
            val after=state;after.fields("controller") shouldBe cut.fields("controller");write(dir,"contender-blocked.json",after)
            if(suspended!=null){OwnerIsolationProbeSupport.signal(a,"CONT");suspended=null}
          }
          if(scenario=="S14_AUTH_IDENTITY") {
            val log=dir.resolve("negative-wire.jsonl")
            val replay=Files.readAllLines(dir.resolve("A-wire.jsonl")).get(0).parseJson.asJsObject.fields("request").asJsObject
            rejected(settings.socket,secret,binding,text(replay,"payload").parseJson.asJsObject,log)
            val negative=JsObject("control"->bound.toJson,"sequence"->JsNumber(1000000000L),"method"->JsString("INVENTORY"),"args"->JsObject.empty)
            rejected(settings.socket,secret,binding,negative,log,badMac=true)
            val foreign=new ProcessBuilder("/bin/cat").start()
            try {
              val id=settings.metadata.launch(oldBoot).get.identity.get;val op=settings.metadata.operation(bound,oldBoot)
              val wrong=id.copy(pid=foreign.pid(),startIdentity=foreign.info().startInstant().orElseThrow().toString)
              rejected(settings.socket,secret,binding,JsObject(negative.fields.updated("method",JsString("STOP")).updated("args",JsObject("boot"->JsString(oldBoot),"operation"->JsString(op),"identity"->wrong.toJson))),log)
              foreign.isAlive shouldBe true;write(dir,"foreign-process-survived.json",process(foreign.pid()))
            }finally foreign.destroyForcibly().waitFor()
            val other=KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val wrongControl=bound.copy(guardian=binding.copy(publicKey=Base64.getEncoder.encodeToString(other.getPublic.getEncoded)))
            val wrongClient=new SnapshotGuardianWire.Client(settings.socket,secret,wrongControl,frame=>SupervisorRecoveryTest.append(dir.resolve("wrong-pin-wire.jsonl"),frame))
            intercept[IllegalArgumentException](wrongClient.request("BIND"))
          }
          a.kill();write(dir,"supervisor-dead.json",JsObject("supervisor"->process(a.pid),"guardian"->process(guardian.pid),"state"->state))
          if(scenario=="S2_FORK_PENDING")release("BEFORE_FORK")
          if(scenario=="S3_ACK_LOST")release("LAUNCH_REPLY")
          if(scenario=="S15_GUARDIAN_DEAD")guardian.kill()
          if(scenario=="S15_CAPABILITY_LOST")Files.writeString(dir.resolve("lose-capability"),"lose original manager")
          var beforeB=state;val b=supervisor("B")
          var finalSupervisor=b
          if(scenario.startsWith("S15_")) {
            if(scenario=="S15_AFTER_PROOF") {
              event(b,"AFTER_PROOF_GATE");beforeB=state;write(dir,"before-guardian-loss.json",beforeB)
              guardian.kill();release("AFTER_PROOF_GATE")
            }
            event(b,"SAFE_PAUSE");Thread.sleep(300)
            val after=state;write(dir,"paused.json",after)
            Vector("owners","permissions","launches").foreach(k=>after.fields(k) shouldBe beforeB.fields(k))
            Vector("T","J","control","audit").foreach(k=>after.fields("data").asJsObject.fields(k) shouldBe beforeB.fields("data").asJsObject.fields(k))
            val comparison=after.fields("data").asJsObject.fields("comparison").asJsObject
            comparison.fields("equal") shouldBe JsBoolean(false);comparison.fields("targetCount") shouldBe JsNumber(0)
          }else {
            if(scenario=="S9_PROOF_REPLY_LOST") {event(b,"PROOF_RECEIVED_GATE");write(dir,"before-second-restart.json",state);b.kill();finalSupervisor=supervisor("C")}
            else if(Set("S9_FENCE_REPLY_LOST","S11_EPOCH_REPLY_LOST","S11_QUOTA_REPLY_LOST","S11_RETIRE_REPLY_LOST").contains(scenario)) {
              event(b,"CONTROL_READBACK_WAIT");write(dir,"before-second-restart.json",state);b.kill();finalSupervisor=supervisor("C")
            }else if(scenario=="S10_OBSERVATION_RESTART") {
              event(b,"FIRST_OBSERVATION_GATE");val first=state;write(dir,"first-observation.json",first)
              first.fields("instances").asInstanceOf[JsArray].elements.filter(_.asJsObject.fields("owner_id")==JsString(oldBoot)).foreach(_.asJsObject.fields("empty_rounds") shouldBe JsString("1"))
              b.kill();finalSupervisor=supervisor("C")
            }else if(residual || scenario=="S10_OBSERVER_DENIED") {
              awaitCondition("proof recorded but old quota still charged",30.seconds){settings.tableStore.ownership.get.owner(oldBoot).exists(o=>Set("fenced","drain_wait").contains(o.phase))}
              Thread.sleep(350);val waiting=state;write(dir,"blocked-before-database-release.json",waiting)
              settings.tableStore.get("table-00").get.grantId shouldBe cut.fields("data").asJsObject.fields("control").asInstanceOf[JsArray].elements.head.asJsObject.fields.get("grant_id").filter(_!=JsNull).map(_.convertTo[String])
              settings.metadata.pending.map(_.bootId) shouldBe Vector(oldBoot)
              if(residual){
                val afterExit=database;write(dir,"database-after-worker-exit.json",afterExit)
                afterExit.elements.exists(r=>r.asJsObject.fields("owner")==JsString(oldBoot) && r.asJsObject.fields("trx_id")!=JsNull) shouldBe true
                proxy.foreach(_.releaseHeld())
                query(holder,s"SELECT RELEASE_LOCK('$gate')")
              }else Files.writeString(dir.resolve("observer.release"),"allow observation")
            }else if(scenario=="S11_PERMIT_REPLY_LOST" || scenario.startsWith("S12_")) {
              val started=event(b,"WORKER_STARTED");val second=text(started,"value")
              workerEvent(dir,second,if(scenario=="S11_PERMIT_REPLY_LOST")"PERMIT_READBACK_WAIT" else if(scenario=="S12_RECOVERY_JOURNAL")"RECOVERY_JOURNAL_WAIT" else "CUTPOINT")
              write(dir,"before-second-restart.json",state);b.kill();finalSupervisor=supervisor("C")
              if(scenario=="S12_RECOVERY_JOURNAL") {
                awaitCondition("second owner fenced before releasing old Journal server request",30.seconds){settings.tableStore.ownership.get.owner(second).exists(o=>Set("fenced","drain_wait").contains(o.phase))}
                write(dir,"second-owner-drain-wait.json",state);query(holder,s"SELECT RELEASE_LOCK('${gate}-recovery')")
              }
            }
            awaitCondition("original execution completed automatically",90.seconds){settings.tableStore.get("table-00").exists(_.state=="completed")}
            val after=state;write(dir,"final.json",after)
            val data=after.fields("data").asJsObject;data.fields("comparison").asJsObject.fields("equal") shouldBe JsBoolean(true)
            data.fields("comparison").asJsObject.fields("sourceCount") shouldBe JsNumber(10)
            data.fields("T").asInstanceOf[JsArray].elements.size shouldBe 5;data.fields("audit").asInstanceOf[JsArray].elements.size shouldBe 10
            data.fields("audit").asInstanceOf[JsArray].elements.foreach(_.asJsObject.fields("op") shouldBe JsString("I"))
            val ledger=data.fields("T").asInstanceOf[JsArray].elements.map(_.asJsObject)
            val pages=journalPages(after);pages.size shouldBe 5
            pages.foreach { page=>
              val cp=page.fields("update").asJsObject.fields("checkpoint").asJsObject
              val row=ledger.find(_.fields("batch_id")==cp.fields("batchId")).get
              cp.fields("cursor").asJsObject.fields("value") shouldBe row.fields("cursor_value")
              cp.fields("cursor").asJsObject.fields("upperBound") shouldBe row.fields("upper_bound")
              page.fields("executionId") shouldBe JsString(execution)
            }
            settings.tableStore.get("table-00").get.executionId shouldBe Some(execution)
            val audit=after.fields("audit").asInstanceOf[JsArray].elements.map(_.asJsObject)
            val writes=withConnection(s,schema)(query(_,"SELECT * FROM supervisor_journal_writes ORDER BY sequence_number"));write(dir,"journal-writer-permits.json",writes)
            writes.elements should not be empty
            writes.elements.foreach { row=>val f=row.asJsObject.fields
              val permission=audit.find(a=>a.fields("event_type")==JsString("recovery_permitted") && a.fields("owner_id")==f("owner_id") && a.fields("details").convertTo[String].parseJson.asJsObject.fields("permit")==f("permit_id")).get
              f("admission_sequence").convertTo[String].toLong should be >= permission.fields("event_sequence").convertTo[String].toLong
              f("generation").convertTo[String].toLong shouldBe permission.fields("details").convertTo[String].parseJson.asJsObject.fields("generation").convertTo[Long]
            }
            val finalPermit=settings.tableStore.ownership.get.permit("table-00").get
            if(scenario=="S11_PERMIT_REPLY_LOST" || scenario.startsWith("S12_")) {
              val controller=event(finalSupervisor,"CONTROLLER_BOUND").fields("controller").asJsObject
              after.fields("controller").asInstanceOf[JsArray].elements.head.asJsObject.fields("controller_boot") shouldBe controller.fields("bootId")
              finalPermit.generation shouldBe 3L
              after.fields("launches").asInstanceOf[JsArray].elements.count(_.asJsObject.fields("phase")==JsString("retired")) shouldBe 2
            }
            val permitSeq=audit.find(a=>a.fields("event_type")==JsString("recovery_permitted") && a.fields("details").convertTo[String].parseJson.asJsObject.fields("permit")==JsString(finalPermit.id)).get.fields("event_sequence").convertTo[String].toLong
            audit.filter(_.fields("event_type")==JsString("old_quota_released")).foreach(a=>a.fields("event_sequence").convertTo[String].toLong should be < permitSeq)
            if(scenario=="S6_T_BEFORE_J") {
              val log=Files.readString(dir.resolve(finalPermit.owner+".worker.log"));log should include("\"replay\":true")
            }
            if(scenario=="S14_AUTH_IDENTITY") {
              rejected(settings.socket,secret,binding,JsObject("control"->bound.toJson,"sequence"->JsNumber(1000000001L),"method"->JsString("INVENTORY"),"args"->JsObject.empty),dir.resolve("stale-epoch-wire.jsonl"))
              state.fields("permissions") shouldBe after.fields("permissions")
            }
          }
          write(dir,"result.json",JsObject("scenario"->JsString(scenario),"schema"->JsString(schema),"outcome"->JsString(if(scenario.startsWith("S15_"))"SAFE_PAUSE" else "CONVERGED"),"originalExecution"->JsString(execution),"D"->JsString("N/A"),"productionSupervisorProtocol"->JsBoolean(true),"runtime"->JsString(sys.env.getOrElse("SUPERVISOR_RECOVERY_RUNTIME","unfrozen"))))
          completed=true
        }catch{case e:Throwable=>
          write(dir,"failure.json",JsObject("errorClass"->JsString(e.getClass.getName),"message"->JsString(Option(e.getMessage).getOrElse("").replace(s.password,"<redacted>")),"state"->state));throw e
        }finally {
          try {
            if(suspended!=null)OwnerIsolationProbeSupport.signal(suspended,"CONT")
            children.filterNot(_._1=="guardian").foreach(_._2.kill())
            query(holder,s"SELECT RELEASE_LOCK('$gate')");query(holder,s"SELECT RELEASE_LOCK('${gate}-recovery')")
            val files=Files.list(dir);try files.forEach(p=>if(p.toString.endsWith(".commands"))Files.writeString(p,"STOP_TEST\n",StandardOpenOption.APPEND))finally files.close()
            awaitCondition("test-owned worker cleanup",20.seconds){
              val c=settings.dataSource.getConnection
              try query(c,"SELECT process_id,start_identity FROM pekko_snapshot_worker_launches WHERE process_id IS NOT NULL").elements.forall { row=>
                val f=row.asJsObject.fields;val p=ProcessHandle.of(f("process_id").convertTo[String].toLong)
                !p.isPresent || !p.get.isAlive || (p.get.info().startInstant().isPresent && p.get.info().startInstant().get.toString!=f("start_identity").convertTo[String])
              }finally c.close()
            }
          }finally {try {if(guardian!=null)guardian.kill()}finally {try {proxy.foreach(_.close());holder.close()}finally settings.close()}}
          write(dir,"cleanup.json",JsObject("scenarioCompleted"->JsBoolean(completed),"cleanupIsNotIsolationProof"->JsBoolean(true),"processes"->JsArray(children.toVector.map{case(role,c)=>JsObject("role"->JsString(role),"pid"->JsNumber(c.pid),"alive"->JsBoolean(c.alive))})))
          awaitCondition("schema database clients ended",20.seconds){
            val row=withConnection(s,schema)(query(_,s"SELECT COUNT(*) AS clients FROM information_schema.processlist WHERE DB='$schema' AND ID<>CONNECTION_ID()"));write(dir,"database-cleanup.json",row)
            row.elements.head.asJsObject.fields("clients")==JsString("0")
          }
        }
      }
    }
  }
}
