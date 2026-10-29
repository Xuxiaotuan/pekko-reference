package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.tags.ExternalIntegration
import cn.xuyinyin.magic.workflow.cdc.{CdcPlatformLimits,CdcTaskRequest,CdcTaskStore}
import cn.xuyinyin.magic.workflow.scheduling._
import com.typesafe.config.{ConfigFactory,ConfigRenderOptions}
import java.io.File
import java.net.{URI,URLClassLoader}
import java.net.http.{HttpClient,HttpRequest,HttpResponse}
import java.nio.file.{Files,Path}
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import java.time.Duration
import java.util.{Base64,UUID}
import java.util.concurrent.ConcurrentLinkedQueue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Try
import spray.json._

/** Real guardian and real CDC kernel, with candidate wiring confined to the test worker. */
class CdcManagedUnknownBSpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._
  import SnapshotGuardianWire._
  "managed unknown B recovery" should {
    for(mode<-Vector("C0","C1","LATE_COMMIT","LATE_ROLLBACK","GUARDIAN_LOST","GUARDIAN_UNREACHABLE")) {
      s"preserve isolation and complete field reconciliation for $mode" taggedAs ExternalIntegration in {
        if(!sys.env.get("RUN_CDC_MANAGED_B_MATRIX").contains("1"))cancel("dedicated dual MySQL fixture required")
        require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").exists(_.nonEmpty))
        val source=Settings.environment();val state=source.copy(port=sys.env("CAPTURE_STATE_PORT").toInt)
        require(source.port!=state.port && !Set(3306,13365,13366)(source.port) && !Set(3306,13365,13366)(state.port))
        def uuid(s:Settings)=withConnection(s,"")(query(_,"SELECT @@server_uuid AS uuid")).elements.head.asJsObject.fields("uuid").convertTo[String]
        val sourceUuid=uuid(source);val stateUuid=uuid(state)
        require(sourceUuid==sys.env("CAPTURE_EXPECT_SOURCE_UUID") && stateUuid==sys.env("CAPTURE_EXPECT_STATE_UUID") && sourceUuid!=stateUuid)
        val suffix=UUID.randomUUID().toString.replace("-","").take(12);val schema="pekko_test_cdc_window_"+suffix
        val capture="managed_"+suffix;val srcUser="cs_"+suffix;val sdkUser="ds_"+suffix
        val dir=Path.of(sys.env("CDC_MANAGED_B_EVIDENCE"),mode+"-"+suffix).toAbsolutePath;Files.createDirectories(dir)
        val paths=Files.walk(Path.of("server/src/main/scala"))
        val production=try paths.iterator().asScala.filter(p=>Files.isRegularFile(p) && p.toString.endsWith(".scala")).toVector.sorted finally paths.close()
        val testFiles=Vector("server/src/test/scala/cn/xuyinyin/magic/workflow/cdc/CdcManagedMatrixWorker.scala",
          "server/src/test/scala/cn/xuyinyin/magic/workflow/integration/CdcManagedMatrixGuardian.scala",
          "server/src/test/scala/cn/xuyinyin/magic/workflow/integration/CdcManagedUnknownBSpec.scala",
          "server/src/test/scala/cn/xuyinyin/magic/workflow/integration/MySQLCommitResponseProxy.scala").map(Path.of(_))
        val sourceManifest=(production++testFiles).map {p=>
          val hash=java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)).map(b=>f"${b & 0xff}%02x").mkString
          hash+"  "+p.toString
        }.mkString("\n")+"\n"
        Files.writeString(dir.resolve("source-sha256.txt"),sourceManifest)
        write(dir,"environment.json",JsObject("mode"->JsString(mode),"schema"->JsString(schema),"capture"->JsString(capture),
          "sourceUuid"->JsString(sourceUuid),"stateUuid"->JsString(stateUuid),"sourcePort"->JsNumber(source.port),"statePort"->JsNumber(state.port),
          "sourceAccount"->JsString(srcUser),"sdkAccount"->JsString(sdkUser),"java"->JsString(System.getProperty("java.version")),
          "scope"->JsString("test worker explicitly wires candidate recovery; independent guardian JVM")))
        val anchor=Files.createTempDirectory("mb-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val events=new ConcurrentLinkedQueue[JsObject]()
        val proxy=new MySQLCommitResponseProxy(source.host,source.port,(kind,value)=>events.add(JsObject(value.fields+("kind"->JsString(kind)))))
        def sql(s:Settings,v:String):Unit=withConnection(s,schema)(executeSql(_,v))
        def account(s:Settings,user:String,sourceRole:Boolean):Unit=withConnection(s,"") {c=>
          val p=s.password.replace("\\","\\\\").replace("'","\\'")
          executeSql(c,s"CREATE USER '$user'@'%' IDENTIFIED BY '$p'")
          executeSql(c,s"GRANT ALL PRIVILEGES ON `$schema`.* TO '$user'@'%'")
          if(sourceRole)executeSql(c,s"GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO '$user'@'%'")
        }
        try {
          initialize(source,schema);initialize(state,schema)
          sql(source,"CREATE TABLE a LIKE source_rows");sql(source,"INSERT INTO a SELECT * FROM source_rows")
          sql(state,"CREATE TABLE target_a LIKE source_rows")
          withConnection(state,schema){c=>
            Vector("db/mysql/pekko-sync-cdc-group-ledger-schema.sql","db/mysql/pekko-table-scheduling-schema.sql",
              "db/mysql/pekko-snapshot-ownership-schema.sql","db/mysql/pekko-snapshot-supervisor-schema.sql").foreach {name=>
              val input=getClass.getClassLoader.getResourceAsStream(name);require(input!=null,name)
              val text=try scala.io.Source.fromInputStream(input).mkString finally input.close()
              text.split(";").map(_.trim).filter(_.nonEmpty).foreach(executeSql(c,_))
            }
          }
          account(source,srcUser,sourceRole=true);account(state,sdkUser,sourceRole=false)
        }catch {case e:Throwable=>
          Try(proxy.close());write(dir,"fixture-failure.json",JsObject("class"->JsString(e.getClass.getName),"message"->JsString(Option(e.getMessage).getOrElse("").replace(source.password,"[REDACTED]"))))
          throw e
        }
        val keys=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val publicKey=Base64.getEncoder.encodeToString(keys.getPublic.getEncoded)
        val secret=new Array[Byte](32);new java.security.SecureRandom().nextBytes(secret)
        Files.write(anchor.resolve("auth.key"),secret);Files.writeString(anchor.resolve("sign.key"),Base64.getEncoder.encodeToString(keys.getPrivate.getEncoded))
        val sourceRoute=source.copy(port=proxy.port).url(schema)
        val instances=Vector(Map[String,AnyRef]("url"->sourceRoute,"username"->source.user,"password"->source.password).asJava).asJava
        val common=Map[String,AnyRef](
          "pekko.workflow.snapshot-ha.enabled"->java.lang.Boolean.TRUE,"pekko.workflow.snapshot-ha.deployment-id"->("cdc-test-"+suffix),"pekko.workflow.snapshot-ha.instances"->instances,
          "pekko.workflow.table-scheduling.enabled"->java.lang.Boolean.TRUE,"pekko.workflow.table-scheduling.jdbc.url"->state.url(schema),
          "pekko.workflow.table-scheduling.jdbc.username"->state.user,"pekko.workflow.table-scheduling.jdbc.password"->state.password,
          "pekko.workflow.table-scheduling.max-active-tables"->Int.box(2),"pekko.workflow.table-scheduling.max-active-tables-per-node"->Int.box(1),
          "pekko.workflow.table-scheduling.max-connections-per-instance"->Int.box(8),"pekko.workflow.table-scheduling.max-concurrent-starts"->Int.box(1),
          "pekko.workflow.table-scheduling.max-page-reservation"->"24 KiB","pekko.workflow.table-scheduling.candidate-page"->Int.box(16),
          "pekko-persistence-jdbc.shared-databases.slick.profile"->"slick.jdbc.MySQLProfile$","pekko-persistence-jdbc.shared-databases.slick.db.driver"->"com.mysql.cj.jdbc.Driver",
          "pekko-persistence-jdbc.shared-databases.slick.db.url"->state.url(schema),"pekko-persistence-jdbc.shared-databases.slick.db.user"->state.user,
          "pekko-persistence-jdbc.shared-databases.slick.db.password"->state.password)
        val captureConfig=Map[String,AnyRef]("id"->capture,"host"->"127.0.0.1","port"->Int.box(proxy.port),"database"->schema,
          "username"->srcUser,"password"->source.password,"server-id"->Long.box(250000+(suffix.hashCode.toLong & 0x7fffffffL)),
          "source-lineage"->sourceUuid,"state-lineage"->stateUuid,"exclusive-sdk-accounts"->java.lang.Boolean.TRUE,
          "sdk-state-username"->sdkUser,"sdk-state-password"->state.password,"source-observer-username"->source.user,"source-observer-password"->source.password).asJava
        val workerSettings=common++Map[String,AnyRef]("pekko.actor.provider"->"local","pekko.cluster.downing-provider-class"->"",
          "pekko.cluster.auto-down-unreachable-after"->"off","pekko.workflow.cdc-platform.enabled"->java.lang.Boolean.TRUE,
          "pekko.workflow.cdc-platform.captures"->Vector(captureConfig).asJava,"pekko.workflow.cdc-platform.snapshot-steps"->Int.box(1),
          "pekko.workflow.cdc-platform.tail-steps"->Int.box(1),"pekko.workflow.cdc-platform.poll-interval-ms"->Long.box(50),
          "pekko.workflow.mysql-cdc.state-jdbc.url"->state.url(schema),"pekko.workflow.mysql-cdc.state-jdbc.username"->state.user,
          "pekko.workflow.mysql-cdc.state-jdbc.password"->state.password,"pekko.workflow.mysql-cdc.state-jdbc.offset-table"->"capture_offsets",
          "pekko.workflow.mysql-cdc.state-jdbc.history-table"->"capture_history","pekko.workflow.mysql-cdc.state-jdbc.offset-flush-interval-ms"->Int.box(0))
        val worker=anchor.resolve("worker.conf")
        Files.writeString(worker,ConfigFactory.parseMap(workerSettings.asJava).withFallback(ConfigFactory.load("application-test")).resolve().root.render(ConfigRenderOptions.concise()))
        val supervisorSettings=common++Map[String,AnyRef]("pekko.workflow.snapshot-supervisor.host-id"->suffix,"pekko.workflow.snapshot-supervisor.guardian-boot"->UUID.randomUUID().toString,
          "pekko.workflow.snapshot-supervisor.public-key"->publicKey,"pekko.workflow.snapshot-supervisor.max-workers"->Int.box(1),
          "pekko.workflow.snapshot-supervisor.socket"->anchor.resolve("guardian.sock").toString,"pekko.workflow.snapshot-supervisor.auth-key-file"->anchor.resolve("auth.key").toString,
          "pekko.workflow.snapshot-supervisor.private-key-file"->anchor.resolve("sign.key").toString,"pekko.workflow.snapshot-supervisor.guardian-lock"->anchor.resolve("guardian.lock").toString,
          "pekko.workflow.snapshot-supervisor.supervisor-lock"->anchor.resolve("supervisor.lock").toString,"pekko.workflow.snapshot-supervisor.worker-config-file"->worker.toString,
          "pekko.workflow.snapshot-supervisor.worker-xmx-mib"->Int.box(768))
        val config=anchor.resolve("supervisor.conf");Files.writeString(config,ConfigFactory.parseMap(supervisorSettings.asJava).root.render(ConfigRenderOptions.concise()))
        Vector("worker.conf","supervisor.conf","auth.key","sign.key").foreach(n=>Files.setPosixFilePermissions(anchor.resolve(n),PosixFilePermissions.fromString("rw-------")))
        val loaders=Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(l=>Option(l.getParent))).takeWhile(_.nonEmpty).flatten.collect{case l:URLClassLoader=>l.getURLs.iterator}.flatten.filter(_.getProtocol=="file").map(u=>Path.of(u.toURI).toString)
        val cp=(loaders++System.getProperty("java.class.path").split(File.pathSeparator).iterator).toVector.distinct.mkString(File.pathSeparator)
        val process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx256m","-cp",cp,
          CdcManagedMatrixGuardian.getClass.getName.stripSuffix("$"),config.toString,worker.toString,dir.toString).redirectErrorStream(true).start()
        val guardian=new Child(process,dir.resolve("guardian.log"))
        val supervisor=try new SnapshotSupervisorSettings(config)catch {case e:Throwable=>
          guardian.kill();Try(proxy.close());write(dir,"fixture-failure.json",JsObject("class"->JsString(e.getClass.getName),"stage"->JsString("supervisor-setup")));throw e
        }
        var control:SupervisorControl=null;var client:SnapshotGuardianWire.Client=null
        val store=new CdcTaskStore(()=>DriverManager.getConnection(state.url(schema),state.user,state.password),CdcPlatformLimits(snapshotSteps=1,tailSteps=1))
        def eventFile():JsArray=JsArray(events.asScala.toVector)
        def db(s:Settings,q:String)=withConnection(s,schema)(query(_,q))
        def dump(label:String):Unit={
          val src=db(source,"SELECT * FROM a ORDER BY id");val dst=db(state,"SELECT * FROM target_a ORDER BY id")
          val left=src.elements.map(_.asJsObject).map(r=>r.fields("id")->r).toMap
          val right=dst.elements.map(_.asJsObject).map(r=>r.fields("id")->r).toMap
          val differences=(left.keySet intersect right.keySet).toVector.flatMap {id=>
            (left(id).fields.keySet++right(id).fields.keySet).toVector.collect {case field if left(id).fields.get(field)!=right(id).fields.get(field)=>
              JsObject("id"->id,"field"->JsString(field),"source"->left(id).fields.getOrElse(field,JsNull),"target"->right(id).fields.getOrElse(field,JsNull))
            }
          }
          val offsetTable=cn.xuyinyin.magic.connectors.mysql.source.MySQLCdcSourceNode.connectorStateTables(
            cn.xuyinyin.magic.connectors.mysql.source.MySQLCdcStateConfig(state.url(schema),state.user,state.password,"capture_offsets","capture_history",0),capture)._1
          val proof=JsObject("owners"->db(state,"SELECT * FROM pekko_snapshot_owners"),"instances"->db(state,"SELECT * FROM pekko_snapshot_owner_instances"),
            "sessions"->db(state,"SELECT * FROM pekko_snapshot_owner_sessions"),"launches"->db(state,"SELECT * FROM pekko_snapshot_worker_launches"))
          write(dir,label+".json",JsObject("owner"->db(state,"SELECT * FROM pekko_cdc_owner_v1"),"grants"->db(state,"SELECT * FROM pekko_cdc_grants_v1"),
            "managedOwners"->db(state,"SELECT * FROM pekko_cdc_managed_owner_v1"),"resourceManifests"->db(state,"SELECT * FROM pekko_cdc_managed_manifest_v1"),
            "recoveryPermits"->db(state,"SELECT * FROM pekko_cdc_b_recovery_v1"),
            "tasks"->db(state,"SELECT * FROM pekko_cdc_tasks_v1"),"J"->db(state,"SELECT persistence_id,sequence_number,event_ser_id,event_ser_manifest,HEX(event_payload) AS payload_hex FROM event_journal ORDER BY ordering"),
            "D"->db(state,s"SELECT offset_key,offset_val FROM `$offsetTable`"),"L"->db(state,"SELECT seq,kind,subscription_id,source_key,payload,digest,point_json,resume_json FROM pekko_capture_log ORDER BY seq"),
            "T"->db(state,"SELECT * FROM pekko_sync_cdc_group_ledger"),"baselineT"->db(state,"SELECT * FROM pekko_bootstrap_receipts"),"subscriptions"->db(state,"SELECT * FROM pekko_capture_subscriptions"),
            "controls"->db(source,"SELECT * FROM pekko_capture_control"),"source"->src,"target"->dst,"equal"->JsBoolean(src==dst),
            "fieldsCompared"->JsNumber(src.elements.map(_.asJsObject.fields.size).sum),"missing"->JsArray((left.keySet--right.keySet).toVector),
            "extra"->JsArray((right.keySet--left.keySet).toVector),"fieldDifferences"->JsArray(differences),"guardian"->proof,"proxy"->eventFile(),
            "sourceTransactions"->withConnection(source,"")(query(_,"SELECT trx_id,trx_state,trx_mysql_thread_id FROM information_schema.innodb_trx")),
            "sourceSessions"->withConnection(source,"")(query(_,"SELECT ID,USER,COMMAND,STATE FROM information_schema.processlist WHERE USER NOT IN ('event_scheduler','system user')")),
            "stateTransactions"->withConnection(state,"")(query(_,"SELECT trx_id,trx_state,trx_mysql_thread_id FROM information_schema.innodb_trx")),
            "stateSessions"->withConnection(state,"")(query(_,"SELECT ID,USER,COMMAND,STATE FROM information_schema.processlist WHERE USER NOT IN ('event_scheduler','system user')"))))
        }
        val http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        def request(port:Int,method:String,path:String,body:Option[JsValue]=None):(Int,JsValue)={
          val b=HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/api/v1/$path")).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json")
          val req=if(method=="GET")b.GET().build()else b.POST(HttpRequest.BodyPublishers.ofString(body.get.compactPrint)).build()
          val response=http.send(req,HttpResponse.BodyHandlers.ofString());response.statusCode()->response.body().parseJson
        }
        def ready(boot:String,guardianMayBeDead:Boolean=false):JsObject={val file=dir.resolve(boot+"-ready.json");awaitCondition("managed worker ready",90.seconds)(Files.exists(file) || (!guardianMayBeDead && !guardian.alive));require(Files.exists(file),"worker failed before ready");Files.readString(file).parseJson.asJsObject}
        var succeeded=false;var hiddenSocket:Option[Path]=None
        try {
          awaitCondition("guardian socket",30.seconds)(Files.exists(supervisor.socket) || !guardian.alive);require(guardian.alive && Files.exists(supervisor.socket),"guardian failed")
          control=supervisor.metadata.claimController(UUID.randomUUID().toString);client=new SnapshotGuardianWire.Client(supervisor.socket,secret,control)
          val coordinator=new SnapshotSupervisorRuntime(supervisor.metadata,control,client,supervisor.observer.observe,supervisor.profileHash,100,
            needsIsolation=boot=>store.managedOwnerNeedsIsolation(boot),wake=supervisor.wakeCdc)
          coordinator.tick();val oldBoot=coordinator.workerBoot.get;val oldReady=ready(oldBoot)
          oldReady.fields("ownerState") shouldBe JsString("ACTIVE")
          val oldPort=oldReady.fields("port").convertTo[Int]
          awaitCondition("shared capture admission ready",120.seconds){Try(request(oldPort,"GET","cdc-captures")._2.toString.contains("ADMISSION_READY")).getOrElse(false)}
          val cutMode=if(mode.startsWith("GUARDIAN_"))"LATE_COMMIT"else mode
          proxy.arm(cutMode,JsObject("stage"->JsString("B")))
          val submitted=request(oldPort,"POST","cdc-tasks",Some(JsObject("requestId"->JsString("a"),"captureId"->JsString(capture),"table"->JsString("a"),"generation"->JsString("g1"),"targetTable"->JsString("target_a"))))
          submitted._1 shouldBe 202
          val task=CdcTaskRequest("a",capture,"a","g1","target_a").taskId
          awaitCondition("source marker transaction cut",90.seconds)(events.asScala.exists(_.fields.get("kind").contains(JsString("CUTPOINT"))))
          awaitCondition("durable UNKNOWN grant",90.seconds)(store.counts.getOrElse("UNKNOWN",0L)==1L)
          val pending=db(state,s"SELECT request_id,status FROM pekko_capture_subscriptions WHERE request_id='$task'")
          pending.elements.size shouldBe 1
          val markerStatus=pending.elements.head.asJsObject.fields("status")
          if(mode=="C1")Set[JsValue](JsString("B_PENDING"),JsString("CAPTURE_READY")) should contain (markerStatus)
          else markerStatus shouldBe JsString("B_PENDING")
          val intent=db(state,s"SELECT kind FROM pekko_capture_log WHERE subscription_id='$task' AND kind='B_INTENT'")
          intent.elements.size shouldBe 1
          val cut=events.asScala.find(_.fields.get("kind").contains(JsString("CUTPOINT"))).get
          if(cutMode.startsWith("LATE_")){
            val connection=cut.fields("connectionId").convertTo[Long]
            val held=withConnection(source,"")(query(_,s"SELECT trx_mysql_thread_id FROM information_schema.innodb_trx WHERE trx_mysql_thread_id=$connection"))
            held.elements.size shouldBe 1
          }
          dump("01-unknown");coordinator.tick()
          awaitCondition("old worker isolated",40.seconds){coordinator.tick();supervisor.metadata.launch(oldBoot).exists(_.proof.nonEmpty)}
          val old=supervisor.tableStore.ownership.get.owner(oldBoot).get
          Set("fenced","drain_wait","db_drained") should contain (old.phase)
          dump("02-isolated")
          if(cutMode.startsWith("LATE_")){
            old.phase should not be "db_drained";supervisor.metadata.launch(oldBoot).get.phase should not be "retired"
            if(mode.startsWith("GUARDIAN_"))Files.writeString(dir.resolve("block-recovery"),"block")
            proxy.resolveHeldTransaction()
          }else proxy.dropHeldResponse()
          awaitCondition("old owner drained and replacement forked",150.seconds){coordinator.tick();supervisor.metadata.launch(oldBoot).exists(_.phase=="retired") && coordinator.workerBoot.exists(_!=oldBoot)}
          val replacementBoot=coordinator.workerBoot.get
          if(mode.startsWith("GUARDIAN_")){
            awaitCondition("replacement before recovery",60.seconds)(Files.exists(dir.resolve(replacementBoot+"-recovery-gated")))
            if(mode=="GUARDIAN_LOST")guardian.kill()
            else {val hidden=anchor.resolve("guardian-hidden.sock");Files.move(supervisor.socket,hidden);hiddenSocket=Some(hidden)}
            Files.writeString(dir.resolve("release-recovery"),"release")
            val replacement=ready(replacementBoot,guardianMayBeDead=true);replacement.fields("ownerState") should not be JsString("ACTIVE")
            store.ownerStatus shouldBe "UNKNOWN"
            store.counts.getOrElse("UNKNOWN",0L) shouldBe 1L
            dump("03-guardian-lost")
          }else {
            val replacement=ready(replacementBoot);replacement.fields("ownerState") shouldBe JsString("ACTIVE")
            val replacementPort=replacement.fields("port").convertTo[Int]
            awaitCondition("task returns to STREAMING",180.seconds){Try(request(replacementPort,"GET","cdc-tasks/"+task)._2.asJsObject.fields("phase")==JsString("STREAMING")).getOrElse(false)}
            dump("03-recovered");val result=Files.readString(dir.resolve("03-recovered.json")).parseJson.asJsObject
            result.fields("equal") shouldBe JsBoolean(true);result.fields("fieldsCompared") shouldBe JsNumber(384)
            result.fields("missing") shouldBe JsArray();result.fields("extra") shouldBe JsArray();result.fields("fieldDifferences") shouldBe JsArray()
          }
          succeeded=true;write(dir,"result.json",JsObject("mode"->JsString(mode),"status"->JsString("DATA_CONVERGED_CLEANUP_PENDING"),"oldBoot"->JsString(oldBoot),"replacementBoot"->JsString(replacementBoot),"taskId"->JsString(task)))
        } catch {case e:Throwable=>
          Try(dump("failure-state"))
          write(dir,"failure.json",JsObject("mode"->JsString(mode),"class"->JsString(e.getClass.getName),"message"->JsString(Option(e.getMessage).getOrElse("").replace(source.password,"[REDACTED]")),"proxy"->eventFile()))
          throw e
        } finally {
          Try(proxy.resolveHeldTransaction());Try(proxy.dropHeldResponse())
          hiddenSocket.foreach(p=>Try(Files.move(p,supervisor.socket)))
          val guardianWasAlive=guardian.alive
          val drained=if(client!=null && guardianWasAlive)Try {
            supervisor.metadata.pending.foreach {t=>
              if(t.identity.nonEmpty){
                val op=supervisor.metadata.operation(control,t.bootId);val ha=supervisor.tableStore.ownership.get
                ha.owner(t.bootId).foreach(_=>ha.beginRevocation(t.bootId,op))
                val raw=text(client.request("STOP",JsObject("boot"->JsString(t.bootId),"identity"->t.identity.get.toJson,"operation"->JsString(op))),"proof")
                val receipt=verifyReceipt(raw,supervisor.binding,t.identity.get,op)
                supervisor.metadata.recordProof(control,t.bootId,raw){if(ha.owner(t.bootId).isEmpty)require(ha.recordFailedStart(receipt))else require(ha.confirmIsolation(receipt))}
                awaitCondition("test worker database drain",180.seconds){ha.instances(t.bootId).foreach {i=>supervisor.wakeCdc(t.bootId,i);ha.recordObservation(t.bootId,supervisor.observer.observe(t.bootId,i),100)};ha.owner(t.bootId).exists(o=>Set("db_drained","startup_failed")(o.phase))}
                ha.releaseDrained(t.bootId,100);supervisor.metadata.retire(control,t.bootId);client.request("RETIRE",JsObject("boot"->JsString(t.bootId)))
              }else client.request("CANCEL",JsObject("boot"->JsString(t.bootId)))
            }
          }.isSuccess else false
          supervisor.metadata.pending.flatMap(_.identity).foreach {id=>
            val h=ProcessHandle.of(id.pid)
            if(h.isPresent && h.get.info().startInstant().isPresent && h.get.info().startInstant().get().toString==id.startIdentity){h.get.destroyForcibly();Try(h.get.onExit().get(10,java.util.concurrent.TimeUnit.SECONDS))}
          }
          guardian.kill();Try(proxy.close());supervisor.close()
          val sessionsGone=Try(awaitCondition("test capture sessions closed",180.seconds){
            withConnection(source,"")(query(_,s"SELECT ID FROM information_schema.processlist WHERE USER='$srcUser'")).elements.isEmpty &&
              withConnection(state,"")(query(_,s"SELECT ID FROM information_schema.processlist WHERE USER='$sdkUser'")).elements.isEmpty
          }).isSuccess
          val clean=(drained || mode=="GUARDIAN_LOST") && sessionsGone && !guardian.alive
          write(dir,"cleanup.json",JsObject("guardianAlive"->JsBoolean(guardian.alive),"guardianDrainConfirmed"->JsBoolean(drained),"sessionsGone"->JsBoolean(sessionsGone),"dataAssertionsPassed"->JsBoolean(succeeded),"clean"->JsBoolean(clean)))
          if(succeeded){
            val p=dir.resolve("result.json");val prior=Files.readString(p).parseJson.asJsObject
            write(dir,"result.json",JsObject(prior.fields.updated("status",JsString(if(clean)"PASS"else"DATA_CONVERGED_CLEANUP_UNCONFIRMED"))))
            require(clean,"test worker resources remain unconfirmed")
          }
        }
      }
    }
  }
}
