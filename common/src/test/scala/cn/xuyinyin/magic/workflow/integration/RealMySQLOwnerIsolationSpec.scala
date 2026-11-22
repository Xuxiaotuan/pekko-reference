package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.tags.ExternalIntegration
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import spray.json._
import java.nio.file.{Files,Path}
import java.util.UUID
import scala.concurrent.duration._
import scala.collection.mutable.ArrayBuffer

/** Characterizes prerequisites only. The production owner recovery protocol is intentionally unchanged. */
class RealMySQLOwnerIsolationSpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._
  import OwnerIsolationProbeSupport._
  val scenarios=Vector("SIGSTOP_READ","KILL_READ","KILL_TARGET_TX","KILL_T_BEFORE_J","KILL_J_AFTER_STORE",
    "KILL_DB_TARGET_WAIT","KILL_DB_JOURNAL_WAIT","OBSERVER_DENIED")
  "independent JVM isolation with real MySQL Source, Actor Journal and Sink" should {
    scenarios.foreach { scenario=>
      s"retain the actual process and database boundary for $scenario" taggedAs ExternalIntegration in {
        if(!sys.env.get("RUN_MYSQL_OWNER_ISOLATION").contains("1"))cancel("external_blocked: explicit flag and disposable loopback MySQL required")
        val s=Settings.environment();val suffix=UUID.randomUUID().toString.replace("-","").take(12)
        val schema="pekko_test_cdc_window_"+suffix;val gate="owner-probe-"+suffix
        val dir=Path.of("target","owner-isolation",scenario+"-"+suffix).toAbsolutePath;Files.createDirectories(dir)
        provision(s,schema,gate)
        write(dir,"environment.json",JsObject("scenario"->JsString(scenario),"schema"->JsString(schema),
          "scope"->JsString("test/operator isolation boundary; no production takeover implementation"),
          "D"->JsString("N/A"),"sourceConsistency"->JsString("quiescent")))
        var a:Child=null;var b:Child=null
        val holder=connection(s,schema,"observer","gate")
        val observations=ArrayBuffer.empty[JsValue]
        var ids=Vector.empty[Long];var owner="";var established=false;var serverWorkAfterDeath=false
        var temporaryObserver=Option.empty[String]
        def event(child:Child,kind:String):JsObject=child.events.find(_.fields("kind")==JsString(kind)).getOrElse(child.awaitEvent(kind))
        def observe(stage:String):JsObject={
          val db=observed(s,schema,owner,ids)
          if(db.fields.get("complete").contains(JsBoolean(true))) {
            ids=(ids++db.fields("knownIds").asInstanceOf[JsArray].elements.map(_.convertTo[Long])).distinct
          }
          observations+=JsObject("stage"->JsString(stage),"process"->OwnerIsolationProbeSupport.process(a),"database"->db)
          db
        }
        def releaseGate():Unit={query(holder,s"SELECT RELEASE_LOCK('$gate') AS released");()}
        try {
          if(scenario.startsWith("KILL_DB_"))query(holder,s"SELECT GET_LOCK('$gate',0) AS acquired").elements.head.asJsObject.fields("acquired") shouldBe JsString("1")
          a=start(s,schema,"fresh",scenario,gate,dir.resolve("worker-a.log"))
          val ready=event(a,"WORKER_READY");write(dir,"worker-a-identity.json",ready)
          owner=ready.fields("owner").convertTo[String]
          if(scenario=="KILL_DB_JOURNAL_WAIT") {
            event(a,"BEFORE_JOURNAL_PAGE")
            withConnection(s,schema)(executeSql(_,"UPDATE owner_probe_journal_gate SET armed=true WHERE id=1"))
            a.send("RELEASE")
          } else event(a,"CUTPOINT")
          if(scenario.startsWith("KILL_DB_")) {
            awaitCondition("actual server statement waiting inside the fixed gate",15.seconds) {
              val db=observe("waiting-for-server-gate")
              db.fields("oldSessions").asInstanceOf[JsArray].elements.exists { r=>
                val f=r.asJsObject.fields
                f("state").toString.toLowerCase.contains("user lock") || f("info").toString.contains("GET_LOCK")
              } && db.fields("oldTransactions").asInstanceOf[JsArray].elements.nonEmpty
            }
          }
          val cutDb=observe("fixed-cut-before-isolation")
          cutDb.fields("complete") shouldBe JsBoolean(true)
          val roles=cutDb.fields("oldSessions").asInstanceOf[JsArray].elements.map(_.asJsObject.fields("role"))
          roles should contain(JsString("journal"));roles should contain(JsString("control"))
          // Snapshot page acquisition can already have closed its physical source connection.
          // Require actual read evidence, not an invented continuously-open source session.
          a.events.exists(_.fields("kind")==JsString("PAGE_READ")) shouldBe true
          established=true
          val cut=capture(s,schema);write(dir,"cut.json",cut)
          val task=cut.fields("control").asInstanceOf[JsArray].elements.head.asJsObject
          val grant=task.fields("grant_id").convertTo[String]
          task.fields("owner_id") shouldBe JsString(owner)
          task.fields("phase") shouldBe JsString("running")
          if(Set("KILL_TARGET_TX","KILL_DB_TARGET_WAIT").contains(scenario)) {
            cutDb.fields("oldTransactions").asInstanceOf[JsArray].elements should not be empty
            cut.fields("T").asInstanceOf[JsArray].elements shouldBe empty
          }
          if(Set("KILL_T_BEFORE_J","KILL_J_AFTER_STORE","KILL_DB_JOURNAL_WAIT").contains(scenario))
            cut.fields("T").asInstanceOf[JsArray].elements should have size 1
          // B is an independent JVM but has no workflow Actor until the TEST operator sends START.
          b=start(s,schema,"candidate",scenario,gate,dir.resolve("worker-b.log"))
          write(dir,"worker-b-identity.json",event(b,"WORKER_READY"))
          b.send("TRY_OLD_BIND");event(b,"OLD_BIND_RESULT").fields("allowed") shouldBe JsBoolean(false)
          val beforeRejectedAttempt=capture(s,schema)
          intercept[IllegalArgumentException]{requeue(s,schema,owner,grant,verdict(dead=false,established=true,cutDb))}
          capture(s,schema) shouldBe beforeRejectedAttempt
          if(scenario=="SIGSTOP_READ") {
            signal(a,"STOP")
            val begin=System.nanoTime()
            while(System.nanoTime()-begin<2.seconds.toNanos) {observe("sigstop-process-still-live");Thread.sleep(250)}
            a.alive shouldBe true
            val p=OwnerIsolationProbeSupport.process(a);write(dir,"sigstop-process.json",p)
            p.fields("ps").convertTo[String] should include("T")
            val paused=observe("sigstop-verdict")
            verdict(false,established,paused).permitsTestRequeue shouldBe false
            capture(s,schema) shouldBe beforeRejectedAttempt
          }
          a.kill();a.alive shouldBe false
          write(dir,"process-after-kill.json",OwnerIsolationProbeSupport.process(a))
          val afterDeath=observe("process-dead-first-observation")
          serverWorkAfterDeath=afterDeath.fields.get("oldSessions").exists(_.asInstanceOf[JsArray].elements.nonEmpty) ||
            afterDeath.fields.get("oldTransactions").exists(_.asInstanceOf[JsArray].elements.nonEmpty)
          if(scenario.startsWith("KILL_DB_")) {
            withClue("evidence_incomplete: intended post-exit server session and transaction were not observed: ") {
              afterDeath.fields("complete") shouldBe JsBoolean(true)
              afterDeath.fields("oldSessions").asInstanceOf[JsArray].elements should not be empty
              afterDeath.fields("oldTransactions").asInstanceOf[JsArray].elements should not be empty
            }
            val proof=verdict(true,established,afterDeath)
            proof.permitsTestRequeue shouldBe false
            intercept[IllegalArgumentException]{requeue(s,schema,owner,grant,proof)}
            write(dir,"dead-process-server-work.json",capture(s,schema))
          }
          if(scenario=="OBSERVER_DENIED") {
            val username="opi_"+suffix
            // Scoped test principal; actual credential is never written to evidence.
            withConnection(s,schema){c=>
              executeSql(c,s"CREATE USER '$username'@'%' IDENTIFIED BY '${s.password.replace("'","''")}'")
              temporaryObserver=Some(username)
              executeSql(c,s"GRANT SELECT ON `$schema`.* TO '$username'@'%'")
            }
            val denied=observed(s.copy(user=username),schema,owner,ids)
            write(dir,"observer-denied.json",denied)
            denied.fields("complete") shouldBe JsBoolean(false)
            val before=capture(s,schema)
            intercept[IllegalArgumentException]{requeue(s,schema,owner,grant,verdict(true,established,denied))}
            capture(s,schema) shouldBe before
          }
          releaseGate()
          var proof=ReleaseObservation(true,established,false,-1,-1,-1)
          var consecutive=0
          awaitCondition("old attributed sessions and transactions ended, with two complete observations",30.seconds) {
            proof=verdict(true,established,observe("waiting-for-confirmed-database-release"))
            consecutive=if(proof.permitsTestRequeue)consecutive+1 else 0
            if(consecutive<2)Thread.sleep(250)
            consecutive>=2
          }
          withConnection(s,schema)(executeSql(_,"UPDATE owner_probe_journal_gate SET armed=false WHERE id=1"))
          val beforeRequeue=capture(s,schema);write(dir,"before-test-requeue.json",beforeRequeue)
          requeue(s,schema,owner,grant,proof) shouldBe 1
          write(dir,"test-operator-requeue.json",JsObject("automatic"->JsBoolean(false),"productionProtocolChanged"->JsBoolean(false),
            "oldOwner"->JsString(owner),"oldGrant"->JsString(grant),"processDead"->JsBoolean(true),"completeDatabaseDrainObservations"->JsNumber(consecutive)))
          b.send("START");event(b,"WORKFLOW_DISPATCH_ENABLED")
          awaitCondition("B finished the original execution",90.seconds){
            val t=withConnection(s,schema)(query(_,"SELECT phase FROM pekko_table_tasks WHERE workflow_id='table-00'"))
            t.elements.head.asJsObject.fields("phase")==JsString("completed")
          }
          val end=capture(s,schema);write(dir,"final.json",end)
          end.fields("comparison").asJsObject.fields("equal") shouldBe JsBoolean(true)
          val audit=end.fields("audit").asInstanceOf[JsArray].elements.map(_.asJsObject)
          audit should have size 10
          all(audit.map(_.fields("op"))) shouldBe JsString("I")
          val started=b.events.filter(_.fields("kind")==JsString("ENGINE_STARTED"))
          started.map(_.fields("executionId")).distinct shouldBe a.events.filter(_.fields("kind")==JsString("ENGINE_STARTED")).map(_.fields("executionId")).distinct
          if(scenario=="KILL_T_BEFORE_J") b.events.exists(e=>e.fields("kind")==JsString("T_CONFIRMED")&&e.fields("replay")==JsBoolean(true)) shouldBe true
          write(dir,"result.json",JsObject("passed"->JsBoolean(true),"schema"->JsString(schema),"scenario"->JsString(scenario),
            "serverWorkObservedAfterProcessDeath"->JsBoolean(serverWorkAfterDeath),"allFieldsEqual"->JsBoolean(true),
            "automaticTakeover"->JsBoolean(false),"D"->JsString("N/A")))
        } catch {case scala.util.control.NonFatal(e)=>
          write(dir,"failure.json",JsObject("class"->JsString(e.getClass.getName),"message"->JsString(Option(e.getMessage).getOrElse("").replace(s.password,"<redacted>"))))
          scala.util.Try(capture(s,schema)).foreach(v=>write(dir,"failure-state.json",v));throw e
        } finally {
          Option(a).foreach(_.kill());Option(b).foreach(_.kill())
          try {
            releaseGate()
            temporaryObserver.foreach { username=>
              require(username=="opi_"+suffix)
              withConnection(s,schema)(executeSql(_,s"DROP USER '$username'@'%'"))
            }
            write(dir,"principal-cleanup.json",JsObject("passed"->JsBoolean(true),"temporaryPrincipalsRemoved"->JsNumber(temporaryObserver.size),"schemasRetained"->JsBoolean(true)))
          } finally {
            holder.close()
            write(dir,"workers-after-cleanup.json",JsObject("a"->Option(a).map(OwnerIsolationProbeSupport.process).getOrElse(JsNull),"b"->Option(b).map(OwnerIsolationProbeSupport.process).getOrElse(JsNull)))
            write(dir,"observations.json",JsArray(observations.toVector))
            write(dir,"events.json",JsObject("a"->JsArray(Option(a).toVector.flatMap(_.events)),"b"->JsArray(Option(b).toVector.flatMap(_.events))))
          }
        }
      }
    }
  }
}
