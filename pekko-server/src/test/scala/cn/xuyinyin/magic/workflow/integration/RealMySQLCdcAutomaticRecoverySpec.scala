package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.tags.ExternalIntegration
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._
import java.io.File
import java.net.URLClassLoader
import java.nio.file.{Files, Path}
import java.util.UUID
import scala.concurrent.duration._

/** Real database, real embedded reader, real JDBC Actor Journal, real transactional Sink.
  * Only fault timing, transport loss and observation are test fixtures.
  */
class RealMySQLCdcAutomaticRecoverySpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._
  private val scenarios = Vector(
    ("AUTO-A","16:16"), ("AUTO-B","16:1"),
    ("LATE_COMMIT","16:1"), ("LATE_ROLLBACK","16:1"),
    ("AUTO-D","1:128"), ("AUTO-E","16:1"), ("AUTO-F","1:1"),
    ("R1","16:1"), ("R2","16:1"), ("R3","16:1"), ("R4","16:1"),
    ("R5","16:1"), ("R6","16:1"), ("R7-PK","16:1"), ("R7-D","16:1"),
    ("REJECT-J","16:1"), ("REJECT-J-RETRY","16:1"))

  "bounded automatic CDC recovery" should {
    for ((scenario,sizes) <- scenarios) {
      s"retain real evidence for $scenario" taggedAs ExternalIntegration in {
        if (!sys.env.get("RUN_MYSQL_CDC_RECOVERY_WINDOWS").contains("1")) cancel("external_blocked: isolated loopback MySQL required")
        val settings=Settings.environment()
        val suffix=UUID.randomUUID().toString.replace("-", "").take(12)
        val schema=s"pekko_test_cdc_window_$suffix";val connector=s"auto_$suffix"
        val dir=Path.of("target","cdc-automatic-recovery",s"$scenario-$suffix").toAbsolutePath
        Files.createDirectories(dir);initialize(settings,schema)
        withConnection(settings,schema) { c =>
          val in=getClass.getClassLoader.getResourceAsStream("db/mysql/pekko-sync-cdc-group-ledger-schema.sql")
          val sql=try scala.io.Source.fromInputStream(in).mkString finally in.close()
          sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(executeSql(c,_))
        }
        val lineage=withConnection(settings,schema)(query(_,"SELECT @@server_uuid AS uuid")).elements.head.asJsObject.fields("uuid").convertTo[String]
        write(dir,"environment.json",JsObject("schema"->JsString(schema),"connector"->JsString(connector),
          "lineage"->JsString(lineage),"scenario"->JsString(scenario),"sizes"->JsString(sizes),
          "mysql"->withConnection(settings,schema)(query(_,"SELECT VERSION() AS version,@@binlog_format AS format,@@binlog_row_image AS row_image,@@gtid_mode AS gtid_mode"))))
        var boot:Child=null;var original:Child=null;var restarted:Child=null
        def start(mode:String,size:String,fault:String,name:String):Child={
          val loaders=Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(l=>Option(l.getParent)))
            .takeWhile(_.nonEmpty).flatten.collect {case l:URLClassLoader=>l.getURLs.iterator}.flatten
            .filter(_.getProtocol=="file").map(u=>Path.of(u.toURI).toString)
          val cp=(loaders++System.getProperty("java.class.path", "").split(File.pathSeparator).iterator).filter(_.nonEmpty).toVector.distinct.mkString(File.pathSeparator)
          val b=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx512m","-Duser.timezone=UTC","-cp",cp,
            RealMySQLCdcGroupRecoveryProcess.getClass.getName.stripSuffix("$"),mode,schema,connector,size,fault,lineage)
          b.redirectError(dir.resolve(name+".stderr").toFile)
          b.environment().put("CDC_WINDOW_MYSQL_HOST",settings.host);b.environment().put("CDC_WINDOW_MYSQL_PORT",settings.port.toString)
          b.environment().put("CDC_WINDOW_MYSQL_USER",settings.user);b.environment().put("CDC_WINDOW_MYSQL_PASSWORD",settings.password)
          new Child(b.start(),dir.resolve(name+".log"))
        }
        def matches(j:JsObject):Boolean=j.fields("journal").asInstanceOf[JsArray].elements.exists(_.asJsObject.fields("checkpoint")!=JsNull) && offsetState(settings,schema,connector).elements.exists(r=>
          r.asJsObject.fields("offset_val").convertTo[String].parseJson==journalCheckpoint(j).fields("cursor").asJsObject.fields("offset"))
        def capture(c:Child):JsObject=JsObject("T"->withConnection(settings,schema)(query(_,"SELECT * FROM pekko_sync_cdc_group_ledger ORDER BY group_id")),
          "J"->c.state(),"D"->offsetState(settings,schema,connector),"comparison"->comparison(settings,schema),
          "audit"->withConnection(settings,schema)(query(_,"SELECT * FROM target_audit ORDER BY audit_id")))
        def waitEqual(c:Child):Unit=awaitCondition("six-field equality and actual durable D",180.seconds) {
          comparison(settings,schema).fields("equal")==JsBoolean(true) && matches(c.state())
        }
        try {
          boot=start("bootstrap","16","none","bootstrap");boot.awaitEvent("PROCESS_READY")
          awaitCondition("initial snapshot",180.seconds){comparison(settings,schema).fields("equal")==JsBoolean(true)}
          withConnection(settings,schema)(executeSql(_,"UPDATE source_rows SET label='trusted-streaming-baseline' WHERE id=64"))
          waitEqual(boot);write(dir,"trusted-baseline.json",evidence(settings,schema,connector,boot.state()))
          boot.send("CRASH");boot.exit() shouldBe 86
          original=start("fresh",sizes,scenario,"original");original.awaitEvent("PROCESS_READY");original.awaitEvent("ENGINE_CONFIG")
          withConnection(settings,schema)(executeSql(_,"UPDATE source_rows SET note='v2-baseline' WHERE id=64"));waitEqual(original)
          val baseline=capture(original);write(dir,"baseline.json",baseline)
          val execution=baseline.fields("J").asJsObject.fields("executionId")
          original.send("ARM");original.awaitEvent("ARMED")
          val pkOnly=scenario=="AUTO-F"
          val workload=if(pkOnly)Vector("UPDATE source_rows SET id=150,label='pk-window-🙂' WHERE id=50") else Vector(
            "UPDATE source_rows SET revision=1,label=CONCAT('window-',id),amount=amount+0.0100,note=IF(MOD(id,2)=0,NULL,'窗口-🙂'),updated_at='2026-10-19 12:34:56.123456'",
            "UPDATE source_rows SET label='repeat-a' WHERE id=1","UPDATE source_rows SET label='repeat-b' WHERE id=1",
            "UPDATE source_rows SET label='repeat-a' WHERE id=1","UPDATE source_rows SET label='repeat-b' WHERE id=1","UPDATE source_rows SET id=150 WHERE id=50")
          Files.writeString(dir.resolve("workload.sql"),workload.mkString("BEGIN;\n",";\n",";\nCOMMIT;\n"))
          withConnection(settings,schema){c=>c.setAutoCommit(false);workload.foreach(executeSql(c,_));c.commit()}
          val cut=original.awaitEvent(if(scenario=="R4")"J_REPLY_HELD" else if(scenario=="REJECT-J")"J_REJECTION_READY" else "CUTPOINT")
          write(dir,"cut-event.json",cut)
          val atCut=capture(original);write(dir,"cutpoint.json",atCut)
          if(scenario!="R4") {
            journalCheckpoint(atCut.fields("J").asJsObject) shouldBe journalCheckpoint(baseline.fields("J").asJsObject)
            atCut.fields("D") shouldBe baseline.fields("D")
          }
          if(scenario=="R6") withConnection(settings,schema) {c=>
            val id=cut.fields("batch").asJsObject.fields("rows").asInstanceOf[JsArray].elements.head.asJsObject.fields("groupId").convertTo[String]
            executeSql(c,s"UPDATE pekko_sync_cdc_group_ledger SET evidence='tampered-for-negative-test' WHERE group_id='$id'")
          }
          if(scenario=="R7-PK")withConnection(settings,schema)(executeSql(_,"ALTER TABLE pekko_sync_cdc_group_ledger DROP PRIMARY KEY"))
          if(scenario=="R7-D")withConnection(settings,schema){c=>
            val tables=query(c,"SHOW TABLES").elements.map(_.asJsObject.fields.values.head.convertTo[String]).filter(_.startsWith("debezium_offset_storage_"))
            tables.size shouldBe 1;executeSql(c,s"DELETE FROM `${tables.head}`")
          }
          if(scenario.startsWith("LATE_")) {
            awaitCondition("old reader closed"){original.events.exists(_.fields("kind")==JsString("ENGINE_CLOSED"))}
            awaitCondition("retry waits on actual old InnoDB transaction",30.seconds) {
              withConnection(settings,schema)(query(_,s"SELECT trx_id,trx_state,trx_mysql_thread_id,trx_query FROM information_schema.INNODB_TRX WHERE trx_state='LOCK WAIT' AND trx_query LIKE '%pekko_sync_cdc_group_ledger%'")).elements.nonEmpty
            }
            write(dir,"old-transaction-lock-wait.json",JsObject("transactions"->withConnection(settings,schema)(query(_,s"SELECT trx_id,trx_state,trx_mysql_thread_id,trx_query FROM information_schema.INNODB_TRX WHERE trx_mysql_thread_id IN (SELECT ID FROM information_schema.PROCESSLIST WHERE DB='$schema')"))))
            original.send("RESOLVE_TX");original.awaitEvent(if(scenario=="LATE_COMMIT")"PROXY_LATE_COMMIT_OK" else "PROXY_LATE_ROLLBACK_DISCONNECTED")
            val oldId=cut.fields("connectionId").convertTo[Long]
            awaitCondition("old server transaction ended") {withConnection(settings,schema)(query(_,s"SELECT trx_id FROM information_schema.INNODB_TRX WHERE trx_mysql_thread_id=$oldId")).elements.isEmpty}
            write(dir,"old-transaction-ended.json",JsObject("connectionId"->JsNumber(oldId),"transactions"->withConnection(settings,schema)(query(_,s"SELECT trx_id FROM information_schema.INNODB_TRX WHERE trx_mysql_thread_id=$oldId"))))
          } else if(scenario=="R4")original.send("DROP_J") else if(scenario=="REJECT-J")original.send("REJECT_J") else original.send("DROP_RESPONSE")
          if(scenario=="REJECT-J-RETRY") {
            val probe=original.awaitEvent("J_REJECTION_READY")
            write(dir,"rejection-probe.json",probe)
            val before=capture(original);write(dir,"before-rejection.json",before)
            before.fields("J").asJsObject.fields("recovery").asJsObject.fields("attemptsStarted") shouldBe JsNumber(1)
            original.send("REJECT_J")
          }
          var live=original
          if(scenario=="R1" || scenario=="R2") {
            if(scenario=="R1") {
              awaitCondition("durable waiting recovery"){val s=original.state();s.fields("recovery")!=JsNull && s.fields("recovery").asJsObject.fields("phase")==JsString("waiting")}
              val waiting=capture(original);write(dir,"before-restart.json",waiting)
              waiting.fields("J").asJsObject.fields("recovery").asJsObject.fields("notBefore").convertTo[Long] should be > System.currentTimeMillis()
            } else { awaitCondition("persisted attempt launch held"){original.events.exists(_.fields("kind")==JsString("ATTEMPT_LAUNCH_HELD"))};write(dir,"before-restart.json",capture(original)) }
            original.send("CRASH");original.exit() shouldBe 86
            restarted=start("recover","1","none","restarted");restarted.awaitEvent("PROCESS_READY");live=restarted
          }
          val negative=Set("R3","R5","R6","R7-PK","R7-D","REJECT-J","REJECT-J-RETRY").contains(scenario)
          if(negative){
            awaitCondition("durable safe pause",90.seconds){live.state().fields("status")==JsString("recovery_blocked")}
            val blocked=capture(live);write(dir,"blocked.json",blocked)
            blocked.fields("J").asJsObject.fields("executionId") shouldBe execution
            val r=blocked.fields("J").asJsObject.fields("recovery").asJsObject
            val expected=if(scenario=="R3")"automatic_recovery_exhausted" else if(scenario=="R5")"local_release_unconfirmed" else if(scenario=="R7-D")"unclassified_failure" else "protocol_conflict"
            r.fields("reasonCode") shouldBe JsString(expected)
            if(scenario.startsWith("REJECT-J")) {
              r.fields("attemptsStarted") shouldBe JsNumber(if(scenario=="REJECT-J")0 else 1)
              r.fields("failureCode") shouldBe JsString("protocol_conflict")
              original.events.exists(_.fields("kind")==JsString("J_UNEXPECTED_ACCEPTANCE")) shouldBe false
              original.events.exists(_.fields("kind")==JsString("J_EXPLICIT_REJECTION")) shouldBe true
              journalCheckpoint(blocked.fields("J").asJsObject) shouldBe journalCheckpoint(baseline.fields("J").asJsObject)
              blocked.fields("D") shouldBe baseline.fields("D")
              original.events.count(_.fields("kind")==JsString("ENGINE_CONFIG")) shouldBe (if(scenario=="REJECT-J")1 else 2)
            }
            if(scenario=="R3" || scenario.startsWith("REJECT-J")){
              if(scenario=="R3")r.fields("attemptsStarted") shouldBe JsNumber(3)
              original.send("CRASH");original.exit() shouldBe 86
              restarted=start("recover","1","none","restarted");restarted.awaitEvent("PROCESS_READY");live=restarted
              live.state().fields("status") shouldBe JsString("recovery_blocked")
              live.events.exists(_.fields("kind")==JsString("READER_RESUME")) shouldBe false
            }
            if(scenario=="R5") {
              original.events.count(_.fields("kind")==JsString("ENGINE_CONFIG")) shouldBe 1
              original.send("RELEASE_CLOSE");awaitCondition("old reader closed"){original.events.exists(_.fields("kind")==JsString("ENGINE_CLOSED"))};original.send("AWAIT_RELEASE")
              original.awaitEvent("CANCEL_RESULT").fields("status") shouldBe JsString("Released")
              original.state().fields("status") shouldBe JsString("recovery_blocked")
            }
            if(scenario.startsWith("R7"))original.events.count(_.fields("kind")==JsString("ENGINE_CONFIG")) shouldBe 1
          } else {
            waitEqual(live)
            val replay=capture(live);write(dir,"after-replay.json",replay)
            replay.fields("J").asJsObject.fields("executionId") shouldBe execution
            replay.fields("audit").asInstanceOf[JsArray].elements.size shouldBe (if(pkOnly)68 else 136)
            if(scenario=="AUTO-D") {
              val committed=atCut.fields("T").asInstanceOf[JsArray].elements.map(_.asJsObject.fields("group_id")).toSet
              val replayBatches=original.events.filter(e=>e.fields("kind")==JsString("SOURCE_BATCH") && e.fields("reader").convertTo[Int]>1)
              replayBatches.exists { e =>
                val ids=e.fields("batch").asJsObject.fields("rows").asInstanceOf[JsArray].elements.map(_.asJsObject.fields("groupId"))
                ids.exists(committed) && ids.exists(id => !committed(id))
              } shouldBe true
            }
            if(scenario=="AUTO-F") {
              val raw=original.events.filter(_.fields("kind")==JsString("RAW_CALLBACK"))
              val d=raw.find(_.fields("records").compactPrint.contains("__debezium.newkey")).get
              val c=raw.find(e=>e.fields("reader")==d.fields("reader") && e.fields("callback")==JsNumber(d.fields("callback").convertTo[Long]+1)).get
              c.fields("records").compactPrint should include("__debezium.oldkey")
            }
            if(scenario=="R4"){awaitCondition("stale progress rejected"){original.events.exists(_.fields("kind")==JsString("STALE_PROGRESS_REJECTED"))};original.events.exists(_.fields("kind")==JsString("STALE_PROGRESS_ACCEPTED")) shouldBe false}
            withConnection(settings,schema)(executeSql(_,"UPDATE source_rows SET revision=revision+1,note='automatic-followup-🙂' WHERE id=150"));waitEqual(live)
            live.state().fields("executionId") shouldBe execution
          }
          val result=capture(live);write(dir,"final.json",result)
          write(dir,"outcome.json",JsObject("scenario"->JsString(scenario),"injectionEstablished"->JsBoolean(true),
            "automaticRecovery"->JsString(if(negative)"SAFE_BLOCK" else "PASS"),"fullFieldConvergence"->result.fields("comparison").asJsObject.fields("equal")))
          println(s"CDC_AUTOMATIC_RECOVERY_OBSERVED scenario=$scenario evidence=$dir")
        }catch{case e:Throwable=>write(dir,"harness-failure.json",JsObject("error"->JsString(e.toString)));throw e}
        finally Vector(boot,original,restarted).filter(_!=null).foreach(_.kill())
      }
    }
  }
}
