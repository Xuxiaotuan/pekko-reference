package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.tags.ExternalIntegration
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import spray.json._
import java.nio.file.{Path,Files}
import java.net.URLClassLoader
import java.io.File
import java.util.UUID
import scala.concurrent.duration._

class RealMySQLTableSchedulingSpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._
  import TableSchedulingExperimentSupport._
  private val scenarios=Vector("BASELINE","HELD_REDELIVERY","COORDINATOR_RESTART","T_J_CRASH","J_REPLY_LOST","READ_T","STOP_READ_T","CLOSE_UNKNOWN","RELEASE_REPLY_LOST","GRANT_REPLY_LOST","BIND_REPLY_LOST","RELEASE_REPLY_HELD","STALE_DISPOSITION","STOP_RELEASE_RETRY")
  "bounded multi-table scheduling with real MySQL Source/Journal/Sink and Cluster Sharding" should {
    scenarios.foreach { scenario=>
      s"retain full-field and resource evidence for $scenario" taggedAs ExternalIntegration in {
        if(!sys.env.get("RUN_MYSQL_TABLE_SCHEDULING").contains("1"))cancel("external_blocked: isolated loopback MySQL and explicit flag required")
        val settings=Settings.environment();val suffix=UUID.randomUUID().toString.replace("-","").take(12);val schema=s"pekko_test_cdc_window_$suffix"
        val dir=Path.of("target","table-scheduling",s"$scenario-$suffix").toAbsolutePath;Files.createDirectories(dir)
        provision(settings,schema)
        write(dir,"environment.json",JsObject("schema"->JsString(schema),"scenario"->JsString(scenario),"tables"->JsNumber(TableCount),
          "D"->JsString("N/A"),"mysql"->withConnection(settings,schema)(query(_,"SELECT VERSION() AS version,@@server_uuid AS server_uuid"))))
        var original:Child=null;var recovered:Child=null
        def start(mode:String,name:String):Child={
          val loaders=Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(l=>Option(l.getParent))).takeWhile(_.nonEmpty).flatten
            .collect{case l:URLClassLoader=>l.getURLs.iterator}.flatten.filter(_.getProtocol=="file").map(u=>Path.of(u.toURI).toString)
          val cp=(loaders++System.getProperty("java.class.path","").split(File.pathSeparator)).filter(_.nonEmpty).toVector.distinct.mkString(File.pathSeparator)
          val builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx768m","-Duser.timezone=UTC","-cp",cp,
            RealMySQLTableSchedulingProcess.getClass.getName.stripSuffix("$"),schema,mode,scenario)
          builder.redirectErrorStream(true)
          builder.environment().put("CDC_WINDOW_MYSQL_HOST",settings.host);builder.environment().put("CDC_WINDOW_MYSQL_PORT",settings.port.toString)
          builder.environment().put("CDC_WINDOW_MYSQL_USER",settings.user);builder.environment().put("CDC_WINDOW_MYSQL_PASSWORD",settings.password)
          new Child(builder.start(),dir.resolve(name+".log"))
        }
        def tasks(child:Child)=child.state().fields("tasks").asInstanceOf[JsArray].elements.map(_.asJsObject)
        try {
          original=start("fresh","original");val ready=original.awaitEvent("PROCESS_READY")
          val cold=original.events.find(_.fields("kind")==JsString("COLD_QUEUED")).get
          cold.fields("readers") shouldBe JsNumber(0);cold.fields("engines") shouldBe JsNumber(0)
          val gated=Set("HELD_REDELIVERY","COORDINATOR_RESTART","T_J_CRASH","J_REPLY_LOST","READ_T","STOP_READ_T","RELEASE_REPLY_HELD","STALE_DISPOSITION","STOP_RELEASE_RETRY").contains(scenario)
          if(gated){
            val cutEvent=original.events.find(_.fields("kind")==JsString("CUTPOINT")).getOrElse(original.awaitEvent("CUTPOINT"))
            if(Set("READ_T","STOP_READ_T","STOP_RELEASE_RETRY").contains(scenario)){
              original.awaitEvent("READ_FAILURE_DURING_T");Thread.sleep(500)
              val early=original.events.filter(e=>e.fields("kind")==JsString("J_CONFIRMED")&&e.fields("workflow")==JsString("table-00"))
              early.exists(e=>Set("RetrySnapshotSplit","BlockSnapshotSplit").contains(e.fields("update").convertTo[String])) shouldBe false
            }
            if(scenario=="RELEASE_REPLY_HELD"){
              cutEvent.fields("otherTablesCompleted") shouldBe JsNumber(15)
              awaitCondition("next window progresses before the old control reply returns",10.seconds){
                val events=original.events
                events.drop(events.indexWhere(_.fields("kind")==JsString("CUTPOINT"))+1)
                  .exists(e=>e.fields("kind")==JsString("T_CONFIRMED") && e.fields("workflow")==JsString("table-00"))
              }
            }
            if(scenario=="STALE_DISPOSITION") {
              original.awaitEvent("NEXT_WINDOW_READ_HELD")
              original.awaitEvent("STALE_RETRY_RETURNED")
              val current=tasks(original).find(_.fields("workflow_id")==JsString("table-00")).get
              current.fields("phase") shouldBe JsString("running")
              current.fields("grant_id") should not be cutEvent.fields("releasedGrant")
              write(dir,"stale-disposition-after-retry.json",capture(settings,schema,original))
            }
            write(dir,"cut.json",capture(settings,schema,original));write(dir,"cut-event.json",cutEvent)
            scenario match {
              case "HELD_REDELIVERY"=>original.send("START_DISPATCHER")
              case "COORDINATOR_RESTART"=>original.send("RESTART_DISPATCHER");original.awaitEvent("DISPATCHER_RESTARTED");write(dir,"after-coordinator-restart.json",capture(settings,schema,original));original.send("RELEASE")
              case "T_J_CRASH"=>
                val ids=withConnection(settings,schema)(query(_,"SELECT ID FROM information_schema.processlist WHERE DB='"+schema+"' AND ID<>CONNECTION_ID()"))
                write(dir,"connections-before-kill.json",ids)
                original.kill();original.alive shouldBe false
                val numeric=ids.elements.map(_.asJsObject.fields("ID").convertTo[String]).filter(_.forall(_.isDigit))
                if(numeric.nonEmpty)awaitCondition("old JVM database connections physically gone",30.seconds){withConnection(settings,schema)(query(_,"SELECT ID FROM information_schema.processlist WHERE ID IN ("+numeric.mkString(",")+")")).elements.isEmpty}
                // Deliberate test/operator action after actual process termination, never TTL-based takeover.
                val oldOwner=ready.fields("owner").convertTo[String]
                val changed=withConnection(settings,schema){c=>
                  c.setAutoCommit(false);query(c,"SELECT queue_sequence FROM pekko_table_admission_lock WHERE id=1 FOR UPDATE")
                  val ps=c.prepareStatement("UPDATE pekko_table_tasks SET phase='ready',grant_id=NULL,owner_id=NULL WHERE owner_id=? AND phase='running'")
                  ps.setString(1,oldOwner);val n=try ps.executeUpdate() finally ps.close();c.commit();n
                }
                write(dir,"confirmed-owner-stop-requeue.json",JsObject("automatic"->JsBoolean(false),"pid"->JsNumber(original.pid),"processTerminated"->JsBoolean(true),"oldConnectionsGone"->JsBoolean(true),"rows"->JsNumber(changed)))
                recovered=start("recover","recovered");recovered.awaitEvent("PROCESS_READY")
              case "J_REPLY_LOST"=>original.send("DROP_J")
              case "STOP_READ_T" | "STOP_RELEASE_RETRY"=>
                original.send("STOP_TABLE")
                if(scenario=="STOP_RELEASE_RETRY")original.awaitEvent("CANCEL_STARTED")
                Thread.sleep(500);write(dir,"stopping-before-release.json",capture(settings,schema,original));original.send("RELEASE")
              case _=>original.send("RELEASE")
            }
          }
          val live=Option(recovered).getOrElse(original)
          val snapshots=Vector.newBuilder[JsValue]
          awaitCondition("all tables terminal or deliberately quarantined",180.seconds){
            val state=live.state()
            val physical=withConnection(settings,schema)(query(_,"SELECT COMMAND,COUNT(*) AS n FROM information_schema.processlist WHERE DB='"+schema+"' GROUP BY COMMAND"))
            snapshots+=JsObject(state.fields + ("physicalConnectionsIncludingObserver"->physical))
            val rows=state.fields("tasks").asInstanceOf[JsArray].elements.map(_.asJsObject)
            if(scenario!="CLOSE_UNKNOWN"&&rows.exists(r=>Set("blocked","unknown").contains(r.fields("phase").convertTo[String])))
              throw new AssertionError("unexpected blocked/unknown table; see failure.json")
            rows.size==TableCount&&rows.forall(r=>r.fields("phase")==JsString("completed") || (scenario=="CLOSE_UNKNOWN"&&r.fields("workflow_id")==JsString("table-00")&&r.fields("phase")==JsString("unknown")))
          }
          write(dir,"state-observations.json",JsArray(snapshots.result()))
          awaitCondition("managed completed entities passivated",30.seconds){live.state().fields("entities").convertTo[Int] <= (if(scenario=="CLOSE_UNKNOWN")1 else 0)}
          val finalEvidence=capture(settings,schema,live);write(dir,"final.json",finalEvidence)
          write(dir,"events.json",JsObject("original"->JsArray(original.events),"recovered"->JsArray(Option(recovered).toVector.flatMap(_.events))))
          val control=finalEvidence.fields("control").asJsObject
          control.fields("maxReaders").convertTo[Int] should be <= 8
          control.fields("maxAttached").convertTo[Int] should be <= 4
          control.fields("attached").convertTo[Int] shouldBe (if(scenario=="CLOSE_UNKNOWN")1 else 0)
          control.fields("readers") shouldBe JsNumber(0)
          control.fields("sourcePoolsClosed") shouldBe JsBoolean(true);control.fields("targetPoolsClosed") shouldBe JsBoolean(true)
          val comparisons=finalEvidence.fields("comparison").asInstanceOf[JsArray].elements.map(_.asJsObject)
          comparisons.foreach { c=>
            if(scenario=="CLOSE_UNKNOWN"&&c.fields("table")==JsNumber(0))c.fields("equal") shouldBe JsBoolean(false)
            else c.fields("equal") shouldBe JsBoolean(true)
          }
          val audit=finalEvidence.fields("audit").asInstanceOf[JsArray].elements.map(_.asJsObject)
          audit.forall(_.fields("op")==JsString("I")) shouldBe true
          comparisons.foreach{c=>val index=c.fields("table").convertTo[Int];audit.count(_.fields("table_id")==JsString(index.toString)) shouldBe c.fields("targetCount").convertTo[Int]}
          if(scenario=="T_J_CRASH")recovered.events.exists(e=>e.fields("kind")==JsString("T_CONFIRMED")&&e.fields("workflow")==JsString("table-00")&&e.fields("replay")==JsBoolean(true)) shouldBe true
          if(Set("RELEASE_REPLY_LOST","GRANT_REPLY_LOST","BIND_REPLY_LOST").contains(scenario))original.events.exists(_.fields("kind")==JsString("CONTROL_COMMIT_RESULT_LOST")) shouldBe true
          write(dir,"result.json",JsObject("passed"->JsBoolean(true),"classification"->JsString(if(scenario=="CLOSE_UNKNOWN")"one_table_quarantined_others_converged" else "all_tables_converged"),"schema"->JsString(schema)))
        }catch{case scala.util.control.NonFatal(e)=>
          write(dir,"failure-message.json",JsString(e.toString))
          Option(recovered).orElse(Option(original)).filter(_.alive).foreach(c=>scala.util.Try(capture(settings,schema,c)).foreach(v=>write(dir,"failure.json",v)))
          throw e
        }finally{Option(original).foreach(_.kill());Option(recovered).foreach(_.kill())}
      }
    }
  }
}
