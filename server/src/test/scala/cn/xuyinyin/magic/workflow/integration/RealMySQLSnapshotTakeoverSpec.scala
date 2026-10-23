package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.tags.ExternalIntegration
import cn.xuyinyin.magic.workflow.scheduling._
import com.zaxxer.hikari.{HikariConfig,HikariDataSource}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import spray.json._
import java.nio.file.{Files,Path}
import java.net.URLClassLoader
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration._

/** Same-cluster independent JVMs. Fault injection never rewrites task state or manually enables candidate dispatch. */
class RealMySQLSnapshotTakeoverSpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._
  import TakeoverTestWire.grantFormat
  private val scenarios=Vector("SIGSTOP_READ","KILL_READ","KILL_TARGET_TX","KILL_T_BEFORE_J","KILL_J_AFTER_STORE",
    "KILL_DB_TARGET_WAIT","KILL_DB_JOURNAL_WAIT","KILL_DB_SNAPSHOT_WAIT","OBSERVER_DENIED","COORDINATOR_RESTART","COMPETING_CANDIDATES","STALE_GRANT","B_RECOVERY_CRASH","FENCE_COMMIT_REPLY_LOST","DRAIN_COMMIT_REPLY_LOST","QUOTA_COMMIT_REPLY_LOST","REVOKE_COMMIT_REPLY_LOST","WRONG_ISOLATION_RECEIPT","OBSERVER_SEPARATE_ACCOUNT","CRON_RECOVERY","KILL_BOOTSTRAP","STARTUP_COMMIT_REPLY_LOST")
  private def cp:String={
    val urls=Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(l=>Option(l.getParent))).takeWhile(_.nonEmpty).flatten
      .collect{case l:URLClassLoader=>l.getURLs.iterator}.flatten.filter(_.getProtocol=="file").map(u=>Path.of(u.toURI).toString)
    (urls++System.getProperty("java.class.path","").split(File.pathSeparator)).filter(_.nonEmpty).toVector.distinct.mkString(File.pathSeparator)
  }
  "automatic managed snapshot takeover" should {
    scenarios.foreach { scenario=>
      s"preserve physical isolation, authorization and full rows for $scenario" taggedAs ExternalIntegration in {
        if(!sys.env.get("RUN_MYSQL_SNAPSHOT_TAKEOVER").contains("1"))cancel("external_blocked: disposable loopback MySQL and explicit flag required")
        val settings=Settings.environment();val suffix=UUID.randomUUID().toString.replace("-","").take(12)
        val schema="pekko_test_cdc_window_"+suffix;val gate="owner-probe-"+suffix
        val dir=Path.of("target","snapshot-takeover",scenario+"-"+suffix).toAbsolutePath;Files.createDirectories(dir)
        OwnerIsolationProbeSupport.provision(settings,schema,gate)
        withConnection(settings,schema) { c=>
          val in=getClass.getClassLoader.getResourceAsStream("db/mysql/pekko-snapshot-ownership-schema.sql")
          val sql=try scala.io.Source.fromInputStream(in).mkString finally in.close()
          sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(executeSql(c,_))
          executeSql(c,"CREATE TABLE takeover_journal_writes (persistence_id VARCHAR(255),sequence_number BIGINT,owner_id VARCHAR(128),permit_id VARCHAR(64),generation BIGINT,admission_sequence BIGINT) ENGINE=InnoDB")
          executeSql(c,"""CREATE TRIGGER takeover_journal_write AFTER INSERT ON event_journal FOR EACH ROW
            BEGIN IF NEW.persistence_id='workflow-table-00' THEN
              INSERT INTO takeover_journal_writes SELECT NEW.persistence_id,NEW.sequence_number,
                (SELECT ATTR_VALUE FROM performance_schema.session_connect_attrs WHERE PROCESSLIST_ID=CONNECTION_ID() AND ATTR_NAME='pekko_owner'),
                (SELECT permit_id FROM pekko_snapshot_writer_permissions WHERE workflow_id='table-00'),
                (SELECT generation FROM pekko_snapshot_writer_permissions WHERE workflow_id='table-00'),
                (SELECT queue_sequence FROM pekko_table_admission_lock WHERE id=1);
            END IF; END""")
          if(scenario=="KILL_DB_SNAPSHOT_WAIT")executeSql(c,s"""CREATE TRIGGER owner_probe_snapshot_wait BEFORE INSERT ON snapshot FOR EACH ROW
            BEGIN IF NEW.persistence_id='workflow-table-00' AND (SELECT id FROM owner_probe_journal_gate WHERE id=1)=1 THEN SET @snapshot_wait=GET_LOCK('$gate',90); DO RELEASE_LOCK('$gate'); END IF; END""")
        }
        val cfg=new HikariConfig();cfg.setJdbcUrl(settings.url(schema)+"&connectionAttributes=pekko_owner:supervisor,pekko_role:control")
        cfg.setUsername(settings.user);cfg.setPassword(settings.password);cfg.setMaximumPoolSize(4);cfg.setMinimumIdle(0)
        val ds=new HikariDataSource(cfg)
        val marker=scenario match {
          case "FENCE_COMMIT_REPLY_LOST"=>"SET phase='fenced'"
          case "DRAIN_COMMIT_REPLY_LOST"=>"SET complete=?"
          case "QUOTA_COMMIT_REPLY_LOST"=>"SET phase='ready',grant_id=NULL"
          case "REVOKE_COMMIT_REPLY_LOST"=>"SET phase='revoking'"
          case "STARTUP_COMMIT_REPLY_LOST"=>"'startup_failed'"
          case _=>"never-injected"
        }
        val replyLoss=new TakeoverCommitReplyLoss(ds,marker)
        val store=new TableSchedulingStore(replyLoss,SchedulingLimits(2,1,4,1,24576,16),ownershipEnabled=true)
        val ownership=store.ownership.get
        val manager=new ManagedJvmIsolation("takeover-experiment",3)
        val denied=new AtomicBoolean(false)
        val restricted="takeover_"+suffix
        var temporaryUser=false
        var unknownHolder:java.sql.Connection=null
        if(scenario=="OBSERVER_SEPARATE_ACCOUNT")withConnection(settings,schema) { c=>
          executeSql(c,s"CREATE USER '$restricted'@'%' IDENTIFIED BY '${settings.password.replace("'","''")}'");temporaryUser=true
          executeSql(c,s"GRANT PROCESS ON *.* TO '$restricted'@'%'")
          executeSql(c,s"GRANT SELECT ON performance_schema.* TO '$restricted'@'%'")
        }
        def openObserver(instance:String)= {
          val user=if(denied.get() || scenario=="OBSERVER_SEPARATE_ACCOUNT")restricted else settings.user
          // An observer needs server inventory privileges, not access to the business schema.
          java.sql.DriverManager.getConnection(settings.url(schema).replace("/"+schema+"?","/?")+"&connectTimeout=3000&socketTimeout=5000&connectionAttributes=pekko_owner:observer,pekko_role:observer",user,settings.password)
        }
        val observer=new MySqlOwnerDrainObserver(ownership,openObserver)
        var coordinator=new SnapshotTakeoverCoordinator(ownership,manager,observer.observe,100)
        var loop:AutoCloseable=coordinator.start(100)
        val children=scala.collection.mutable.ArrayBuffer.empty[(ManagedSnapshotChild,Child)]
        val holder=openObserver("")
        def event(child:Child,kind:String)=child.events.find(_.fields("kind")==JsString(kind)).getOrElse(child.awaitEvent(kind))
        def start(mode:String,seed:String):(ManagedSnapshotChild,Child)={
          val boot=UUID.randomUUID().toString
          val builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx512m","-Duser.timezone=UTC","-cp",cp,
            RealMySQLSnapshotTakeoverProcess.getClass.getName.stripSuffix("$"),schema,boot,mode,scenario,gate,seed)
          builder.redirectErrorStream(true)
          Map("RUN_MYSQL_SNAPSHOT_TAKEOVER"->"1","CDC_WINDOW_MYSQL_HOST"->settings.host,"CDC_WINDOW_MYSQL_PORT"->settings.port.toString,"CDC_WINDOW_MYSQL_USER"->settings.user,"CDC_WINDOW_MYSQL_PASSWORD"->settings.password).foreach{case(k,v)=>builder.environment().put(k,v)}
          val managed=manager.launch(boot,builder)
          val child=new Child(managed.process,dir.resolve(s"worker-${children.size}.log"));children+=managed->child
          write(dir,s"worker-${children.size}-identity.json",JsObject("owner"->JsString(boot),"pid"->JsNumber(managed.identity.pid),"startIdentity"->JsString(managed.identity.startIdentity)))
          event(child,"WORKER_READY");managed->child
        }
        def control:JsObject=JsObject(
          "owners"->withConnection(settings,schema)(query(_,"SELECT * FROM pekko_snapshot_owners ORDER BY owner_id")),
          "instances"->withConnection(settings,schema)(query(_,"SELECT * FROM pekko_snapshot_owner_instances ORDER BY owner_id,instance_id")),
          "sessions"->withConnection(settings,schema)(query(_,"SELECT * FROM pekko_snapshot_owner_sessions ORDER BY owner_id,instance_id,connection_id")),
          "permissions"->withConnection(settings,schema)(query(_,"SELECT * FROM pekko_snapshot_writer_permissions ORDER BY workflow_id")),
          "audit"->withConnection(settings,schema)(query(_,"SELECT * FROM pekko_snapshot_ownership_audit ORDER BY event_sequence")),
          "journalWrites"->withConnection(settings,schema)(query(_,"SELECT * FROM takeover_journal_writes ORDER BY sequence_number")),
          "scheduleTriggers"->withConnection(settings,schema)(query(_,"SELECT * FROM pekko_snapshot_schedule_triggers ORDER BY scheduled_at")),
          "tasks"->withConnection(settings,schema)(query(_,"SELECT * FROM pekko_table_tasks ORDER BY workflow_id")))
        def capture=JsObject("ha"->control,"data"->OwnerIsolationProbeSupport.capture(settings,schema))
        def releaseGate():Unit={query(holder,s"SELECT RELEASE_LOCK('$gate') AS released");()}
        try {
          if(scenario=="STARTUP_COMMIT_REPLY_LOST") {
            val failed=manager.launch(UUID.randomUUID().toString,new ProcessBuilder("/bin/sh","-c","sleep 0.1; exit 1"))
            replyLoss.armed.set(true);failed.process.waitFor()
            awaitCondition("a committed failed-start tombstone retires the exact child after its reply was lost",15.seconds) {
              ownership.owner(failed.identity.bootId).exists(_.phase=="startup_failed") && !manager.managed.exists(_.identity==failed.identity)
            }
            replyLoss.injected.get() shouldBe true
            write(dir,"startup-reply-lost.json",control)
          }
          if(scenario.startsWith("KILL_DB_"))query(holder,s"SELECT GET_LOCK('$gate',0) AS acquired").elements.head.asJsObject.fields("acquired") shouldBe JsString("1")
          if(scenario=="B_RECOVERY_CRASH")query(holder,s"SELECT GET_LOCK('${gate}-recovery',0) AS acquired")
          val (old,a)=start("fresh","self")
          val aReady=event(a,"WORKER_READY")
          if(scenario=="KILL_DB_JOURNAL_WAIT") {
            event(a,"BEFORE_JOURNAL_PAGE")
            withConnection(settings,schema)(executeSql(_,"UPDATE owner_probe_journal_gate SET armed=true WHERE id=1"));a.send("RELEASE")
          }else if(scenario!="KILL_DB_SNAPSHOT_WAIT")event(a,"CUTPOINT")
          if(scenario=="CRON_RECOVERY") {a.send("CRON_OVERLAP");event(a,"CRON_OVERLAP_ACK")}
          if(scenario.startsWith("KILL_DB_"))awaitCondition("the exact target/Journal server statement waits inside GET_LOCK with its own transaction",20.seconds) {
            val observations=ownership.instances(old.identity.bootId).map(i=>observer.observe(old.identity.bootId,i))
            write(dir,"gate-before-kill.json",JsArray(observations.map(_.evidence.parseJson)))
            observations.exists { observation=>
              val evidence=observation.evidence.parseJson.asJsObject.fields
              val waiting=evidence.get("sessions").collect {case JsArray(rows)=>rows}.getOrElse(Vector.empty).map(_.asJsObject.fields).filter { row=>
                row.get("state").exists(_.toString.toLowerCase.contains("user lock")) &&
                  row.get("role").contains(JsString(if(scenario=="KILL_DB_TARGET_WAIT")"target" else "journal"))
              }.map(_("id").convertTo[String]).toSet
              observation.complete && evidence.get("transactions").collect {case JsArray(rows)=>rows}.getOrElse(Vector.empty)
                .exists(row=>waiting(row.asJsObject.fields("trx_mysql_thread_id").convertTo[String]))
            }
          }
          write(dir,"cut.json",capture)
          val oldTask=store.get("table-00").get
          if(scenario=="KILL_DB_SNAPSHOT_WAIT")Set("recovering","running") should contain(oldTask.state) else oldTask.state shouldBe "running"
          oldTask.owner shouldBe Some(old.identity.bootId)
          if(scenario=="KILL_BOOTSTRAP") {
            val (bootstrapping,child)=start("bootstrap-gated","self")
            ownership.instances(bootstrapping.identity.bootId) should not be empty
            write(dir,"bootstrap-owner-and-manifest.json",control)
            bootstrapping.process.destroyForcibly().waitFor()
            awaitCondition("a bootstrapped worker with no ActorSystem still fences and drains",15.seconds) {
              ownership.owner(bootstrapping.identity.bootId).exists(_.phase=="db_drained") && !manager.managed.exists(_.identity==bootstrapping.identity)
            }
            write(dir,"bootstrap-drained.json",control)
          }
          val (nextWorker,b)=start(if(scenario=="B_RECOVERY_CRASH")"recovery-gated" else "candidate",aReady.fields("address").convertTo[String])
          b.events.exists(_.fields("kind")==JsString("ENGINE_STARTED")) shouldBe false
          if(scenario=="COMPETING_CANDIDATES")start("candidate",aReady.fields("address").convertTo[String])
          if(scenario=="SIGSTOP_READ") {
            require(new ProcessBuilder("/bin/kill","-STOP",old.process.pid().toString).start().waitFor()==0)
            Thread.sleep(1500)
            old.process.isAlive shouldBe true
            ownership.owner(old.identity.bootId).get.phase shouldBe "active"
            store.get("table-00").get.grantId shouldBe oldTask.grantId
            write(dir,"stopped-live.json",capture)
          }
          if(scenario=="OBSERVER_DENIED") {
            withConnection(settings,schema){c=>
              executeSql(c,s"CREATE USER '$restricted'@'%' IDENTIFIED BY '${settings.password.replace("'","''")}'")
              temporaryUser=true;executeSql(c,s"GRANT SELECT ON `$schema`.* TO '$restricted'@'%'")
            };denied.set(true)
          }
          if(scenario=="WRONG_ISOLATION_RECEIPT") {
            loop.close()
            val operation=ownership.beginRevocation(old.identity.bootId,"wrong-receipt-check")
            ownership.confirmIsolation(SnapshotIsolationReceipt(operation,old.identity.copy(startIdentity="different-process"),"managed-child-jvm",System.currentTimeMillis(),"{}")) shouldBe false
            ownership.owner(old.identity.bootId).get.phase shouldBe "revoking"
            write(dir,"wrong-receipt-rejected.json",capture)
            loop=new SnapshotTakeoverCoordinator(ownership,manager,observer.observe,100).start(100)
          }
          if(scenario=="OBSERVER_SEPARATE_ACCOUNT")unknownHolder=java.sql.DriverManager.getConnection(settings.url(schema),settings.user,settings.password)
          replyLoss.armed.set(scenario.endsWith("COMMIT_REPLY_LOST"))
          old.process.destroyForcibly().waitFor()
          awaitCondition("production coordinator persisted the exact isolation proof",15.seconds) {
            ownership.owner(old.identity.bootId).exists(o=>Set("fenced","drain_wait","db_drained").contains(o.phase))
          }
          if(scenario.startsWith("KILL_DB_")) {
            val observations=ownership.instances(old.identity.bootId).map(i=>observer.observe(old.identity.bootId,i))
            write(dir,"post-exit-observations.json",JsArray(observations.map(_.evidence.parseJson)))
            withClue("evidence_incomplete: no actual residual old transaction after process exit: ") {
              observations.exists(o=>o.complete && o.sessions>0 && o.transactions>0) shouldBe true
            }
            store.get("table-00").get.grantId shouldBe oldTask.grantId
            b.events.exists(_.fields("kind")==JsString("ENGINE_STARTED")) shouldBe false
            write(dir,"dead-with-server-work.json",capture)
          }
          if(scenario=="OBSERVER_DENIED") {
            Thread.sleep(500)
            ownership.owner(old.identity.bootId).get.phase shouldBe "drain_wait"
            store.get("table-00").get.grantId shouldBe oldTask.grantId
            write(dir,"denied-held.json",capture);denied.set(false)
          }
          if(scenario=="OBSERVER_SEPARATE_ACCOUNT") {
            Thread.sleep(500)
            ownership.owner(old.identity.bootId).get.phase shouldBe "drain_wait"
            store.get("table-00").get.grantId shouldBe oldTask.grantId
            val observed=ownership.instances(old.identity.bootId).map(i=>observer.observe(old.identity.bootId,i))
            observed.exists(o=>o.complete && o.unattributed>0) shouldBe true
            write(dir,"separate-observer-unattributed.json",JsArray(observed.map(_.evidence.parseJson)))
            unknownHolder.close();unknownHolder=null
          }
          if(scenario=="COORDINATOR_RESTART") {
            loop.close();write(dir,"coordinator-restart.json",capture)
            coordinator=new SnapshotTakeoverCoordinator(ownership,manager,observer.observe,100);loop=coordinator.start(100)
          }
          releaseGate()
          if(scenario=="STALE_GRANT") {
            event(b,"CANDIDATE_READ_GATE")
            val beforeTask=store.get("table-00").get;val beforePermission=ownership.permit("table-00")
            val before=OwnerIsolationProbeSupport.capture(settings,schema)
            b.send("STALE "+TableGrant(oldTask,oldTask.grantId.get).toJson.compactPrint)
            event(b,"STALE_PROCESSED_STATUS")
            val after=OwnerIsolationProbeSupport.capture(settings,schema)
            after.fields("T") shouldBe before.fields("T");after.fields("J") shouldBe before.fields("J")
            store.get("table-00") shouldBe Some(beforeTask);ownership.permit("table-00") shouldBe beforePermission
            write(dir,"stale-grant-before.json",before);write(dir,"stale-grant-after.json",after)
            b.send("RELEASE")
          }
          if(scenario=="B_RECOVERY_CRASH") {
            event(b,"RECOVERY_JOURNAL_WAIT")
            store.get("table-00").get.state shouldBe "recovering"
            ownership.permit("table-00").get.generation shouldBe 2
            write(dir,"second-owner-recovering.json",capture)
            start("candidate",event(b,"WORKER_READY").fields("address").convertTo[String])
            nextWorker.process.destroyForcibly().waitFor()
            awaitCondition("second owner fenced while its recovery resources remain",15.seconds) {
              ownership.owner(nextWorker.identity.bootId).exists(o=>Set("fenced","drain_wait").contains(o.phase))
            }
            write(dir,"second-owner-dead.json",capture)
            query(holder,s"SELECT RELEASE_LOCK('${gate}-recovery') AS released")
          }
          awaitCondition("automatic same-cluster recovery completed without requeue or START",90.seconds) {
            store.get("table-00").exists(_.state=="completed")
          }
          if(scenario.endsWith("COMMIT_REPLY_LOST"))replyLoss.injected.get() shouldBe true
          val finalResult=capture
          write(dir,"final.json",finalResult)
          val comparison=finalResult.fields("data").asJsObject.fields("comparison").asJsObject
          comparison.fields("equal") shouldBe JsBoolean(true)
          comparison.fields("sourceCount") shouldBe JsNumber(10)
          val records=finalResult.fields("data").asJsObject.fields("audit").asInstanceOf[JsArray].elements
          records.size shouldBe 10
          val audit=control.fields("audit").asInstanceOf[JsArray].elements.map(_.asJsObject)
          def sequence(kind:String)=audit.filter(_.fields("event_type")==JsString(kind)).map(_.fields("event_sequence").convertTo[String].toLong)
          sequence("fenced").size shouldBe (if(Set("B_RECOVERY_CRASH","KILL_BOOTSTRAP")(scenario))2 else 1)
          sequence("old_quota_released").size shouldBe (if(scenario=="B_RECOVERY_CRASH")2 else 1)
          val writes=control.fields("journalWrites").asInstanceOf[JsArray].elements.map(_.asJsObject)
          writes should not be empty
          writes.foreach { r=>
            val permit=audit.find(p=>p.fields("event_type")==JsString("recovery_permitted") && p.fields("owner_id")==r.fields("owner_id") &&
              p.fields("details").convertTo[String].parseJson.asJsObject.fields("permit")==r.fields("permit_id")).get
            r.fields("admission_sequence").convertTo[String].toLong should be >= permit.fields("event_sequence").convertTo[String].toLong
          }
          audit.filter(_.fields("event_type")==JsString("fenced")).foreach { fence=>
            val owner=fence.fields("owner_id")
            def ownedSequence(kind:String)=audit.filter(r=>r.fields("owner_id")==owner && r.fields("event_type")==JsString(kind)).map(_.fields("event_sequence").convertTo[String].toLong)
            fence.fields("event_sequence").convertTo[String].toLong should be < ownedSequence("db_drained").min
            if(ownedSequence("old_quota_released").nonEmpty)ownedSequence("db_drained").max should be < ownedSequence("old_quota_released").min
          }
          val permission=ownership.permit("table-00").get
          permission.generation shouldBe (if(scenario=="B_RECOVERY_CRASH")3 else 2)
          permission.executionId shouldBe oldTask.executionId.get
          store.release(TableGrant(oldTask,oldTask.grantId.get),"completed",true,Some(old.identity.bootId)) shouldBe false
          children.drop(1).exists(_._2.events.exists(_.fields("kind")==JsString("ENGINE_STARTED"))) shouldBe true
          if(scenario=="KILL_T_BEFORE_J")children.drop(1).flatMap(_._2.events).count(_.fields.get("replay").contains(JsBoolean(true))) shouldBe 1
          if(scenario=="KILL_J_AFTER_STORE") {
            val reader=children.drop(1).flatMap(_._2.events).find(_.fields("kind")==JsString("READER_OPEN")).get
            reader.fields("resume") shouldBe JsString("1")
          }
          if(scenario=="CRON_RECOVERY") {
            val before=control
            b.send("COLD_QUERY");event(b,"COLD_QUERY_RESULT")
            b.send("CRON_DUPLICATE");event(b,"CRON_DUPLICATE_ACK")
            Thread.sleep(500)
            control.fields("permissions") shouldBe before.fields("permissions")
            control.fields("journalWrites") shouldBe before.fields("journalWrites")
            val triggers=control.fields("scheduleTriggers").asInstanceOf[JsArray].elements.map(_.asJsObject)
            triggers.map(_.fields("disposition")) shouldBe Vector(JsString("accepted"),JsString("skipped"))
            write(dir,"cron-final.json",capture)
          }
          write(dir,"result.json",JsObject("scenario"->JsString(scenario),"schema"->JsString(schema),"equal"->JsBoolean(true),
            "rows"->JsNumber(10),"fields"->JsNumber(6),"D"->JsString("N/A"),"automaticTakeover"->JsBoolean(true),"sameCluster"->JsBoolean(true),
            "runtime"->JsString(sys.env.getOrElse("SNAPSHOT_TAKEOVER_RUNTIME","unfrozen"))))
        } finally {
          if(unknownHolder!=null)unknownHolder.close()
          releaseGate();if(scenario=="B_RECOVERY_CRASH")query(holder,s"SELECT RELEASE_LOCK('${gate}-recovery') AS released");holder.close();denied.set(false)
          children.foreach { case(_,child)=>child.kill() }
          try loop.close()finally {
            if(temporaryUser)withConnection(settings,schema)(executeSql(_,s"DROP USER '$restricted'@'%';"))
            ds.close()
            write(dir,"workers-after-cleanup.json",JsArray(children.toVector.map { case (managed,_)=>
              JsObject("owner"->JsString(managed.identity.bootId),"pid"->JsNumber(managed.identity.pid),"alive"->JsBoolean(managed.process.isAlive))
            }))
            awaitCondition("all experiment schema sessions and transactions ended",15.seconds) {
              val remaining=withConnection(settings,schema)(query(_,s"SELECT COUNT(*) AS clients FROM information_schema.processlist WHERE DB='$schema' AND ID<>CONNECTION_ID()"))
              write(dir,"database-cleanup.json",remaining)
              remaining.elements.head.asJsObject.fields("clients")==JsString("0")
            }
          }
        }
      }
    }
  }
}
