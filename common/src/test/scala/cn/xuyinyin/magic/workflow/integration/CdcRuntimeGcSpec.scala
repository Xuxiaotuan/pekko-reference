package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.cdc.platform.CdcTaskRequest
import cn.xuyinyin.magic.connectors.mysql.source.{MySQLCdcSourceNode,MySQLCdcStateConfig}
import cn.xuyinyin.magic.tags.ExternalIntegration
import java.nio.file.{Files,Path}
import java.net.{URI,URLClassLoader}
import java.net.http.{HttpClient,HttpRequest,HttpResponse}
import java.sql.{Connection,DriverManager}
import java.io.File
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import spray.json._

class CdcRuntimeGcSpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._
  "standalone Runtime GC" should {
    Vector("RG01-enable-restart","RG02-slow-table","RG03-unknown-B","RG04-orphan-Journal","RG05-unsafe-account","RG06-inventory-rollback","RG07-unclean-restart").filter(x=>sys.env.get("RUNTIME_GC_CASES").forall(_.split(",").contains(x))).foreach {scenario=>
      s"preserve durable boundaries and evidence for $scenario" taggedAs ExternalIntegration in {
        if(!sys.env.get("RUN_RUNTIME_GC_MATRIX").contains("1"))cancel("isolated dual MySQL required")
        val source=Settings.environment();val state=source.copy(port=sys.env("CAPTURE_STATE_PORT").toInt)
        require(source.port==25821 && state.port==25832)
        Class.forName("com.mysql.cj.jdbc.Driver")
        val suffix=UUID.randomUUID().toString.replace("-","").take(12);val schema="pekko_test_cdc_window_"+suffix;val capture="runtime_"+suffix
        val src="rs_"+suffix;val sdk="rd_"+suffix;val gc="rg_"+suffix
        val dir=Path.of(sys.env("RUNTIME_GC_EVIDENCE"),scenario+"-"+suffix);Files.createDirectories(dir)
        initialize(source,schema);initialize(state,schema)
        def sql(s:Settings,q:String):Unit=withConnection(s,schema)(executeSql(_,q))
        def rows(q:String):JsArray=withConnection(state,schema)(query(_,q))
        def scalar(q:String):String=rows(q).elements.head.asJsObject.fields.values.head.convertTo[String]
        def createUser(s:Settings,user:String):Unit=withConnection(s,""){c=>val q=c.prepareStatement(s"CREATE USER '$user'@'127.0.0.1' IDENTIFIED BY ?");try {q.setString(1,s.password);q.executeUpdate()}finally q.close()}
        withConnection(state,schema){c=>
          val in=getClass.getClassLoader.getResourceAsStream("db/mysql/pekko-sync-cdc-group-ledger-schema.sql")
          val ddl=try scala.io.Source.fromInputStream(in).mkString finally in.close()
          ddl.split(";").map(_.trim).filter(_.nonEmpty).foreach(executeSql(c,_))
        }
        Vector("a","b").foreach {t=>sql(source,s"CREATE TABLE $t LIKE source_rows");sql(source,s"INSERT INTO $t SELECT * FROM source_rows");sql(state,s"CREATE TABLE target_$t LIKE source_rows")}
        createUser(source,src);createUser(state,sdk);createUser(state,gc)
        withConnection(source,""){c=>executeSql(c,s"GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO '$src'@'127.0.0.1'");executeSql(c,s"GRANT SELECT,INSERT,UPDATE,DELETE,CREATE ON `$schema`.* TO '$src'@'127.0.0.1'")}
        val sdkTables=Vector("pekko_capture_readers","pekko_capture_log","pekko_capture_subscriptions","pekko_capture_fences","pekko_capture_schemas","pekko_capture_gc_state_v1","pekko_capture_consumers_v1","pekko_capture_gc_retention_v1","pekko_capture_gc_pins_v1")
        val gcTables=sdkTables++Vector("pekko_capture_gc_checkpoints_v1","pekko_capture_gc_frames_v1","pekko_capture_gc_operations_v1")
        val sdkConfig=MySQLCdcStateConfig(state.url(schema),sdk,state.password,"capture_offsets","capture_history",0)
        val (offset,history)=MySQLCdcSourceNode.connectorStateTables(sdkConfig,capture)
        sql(state,s"GRANT SELECT,CREATE ON `$schema`.* TO '$sdk'@'127.0.0.1'")
        sql(state,s"GRANT SELECT ON `$schema`.* TO '$gc'@'127.0.0.1'")
        sdkTables.++(Vector(offset,history)).foreach(t=>sql(state,s"GRANT INSERT,UPDATE,DELETE,CREATE ON `$schema`.`$t` TO '$sdk'@'127.0.0.1'"))
        val sourceUuid=withConnection(source,schema)(query(_,"SELECT @@server_uuid AS uuid")).elements.head.asJsObject.fields("uuid").convertTo[String]
        val stateUuid=scalar("SELECT @@server_uuid AS uuid")
        val children=scala.collection.mutable.ArrayBuffer.empty[Child];var port=0;var lock:Connection=null
        val http=HttpClient.newHttpClient()
        def request(path:String,body:Option[JsObject]=None):JsObject={
          val builder=HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/api/v1/$path")).timeout(java.time.Duration.ofSeconds(15))
          body.foreach(v=>builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(v.compactPrint)))
          val result=http.send(builder.build(),HttpResponse.BodyHandlers.ofString())
          require(result.statusCode()<400,s"HTTP ${result.statusCode()}: ${result.body()}")
          result.body().parseJson.asJsObject
        }
        def start(enabled:Boolean,label:String):Child={
          val loaders=Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(l=>Option(l.getParent))).takeWhile(_.nonEmpty).flatten
            .collect{case l:URLClassLoader=>l.getURLs.iterator}.flatten.filter(_.getProtocol=="file").map(u=>Path.of(u.toURI).toString)
          val cp=(loaders++System.getProperty("java.class.path","").split(File.pathSeparator).iterator).filter(_.nonEmpty).toVector.distinct.mkString(File.pathSeparator)
          val p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx512m","-Duser.timezone=UTC","-cp",cp,
            CdcRuntimeGcWorker.getClass.getName.stripSuffix("$"),schema,capture,src,sdk,gc,sourceUuid,stateUuid,enabled.toString)
          p.redirectErrorStream(true);val c=new Child(p.start(),dir.resolve(label+".log"));children+=c
          port=c.awaitEvent("PROCESS_READY").fields("httpPort").convertTo[Int]
          awaitCondition("Runtime owner active"){request("cdc-status").fields("ownerState")==JsString("ACTIVE")}
          awaitCondition("capture ready"){request("cdc-captures").fields("items").asInstanceOf[JsArray].elements.head.asJsObject.fields("readiness")==JsString("ADMISSION_READY")}
          c
        }
        def stop(c:Child):Unit={c.send("STOP");c.awaitEvent("PROCESS_STOPPED");c.exit() shouldBe 0;scalar("SELECT status FROM pekko_cdc_owner_v1") shouldBe "STOPPED"}
        def task(t:String)=CdcTaskRequest(t,capture,t,"g1","target_"+t)
        def boot(t:String):Unit={val r=task(t);request("cdc-tasks",Some(JsObject("requestId"->JsString(t),"captureId"->JsString(capture),"table"->JsString(t),"generation"->JsString("g1"),"targetTable"->JsString("target_"+t))))
          awaitCondition("streaming "+t){request("cdc-tasks/"+r.taskId).fields("phase")==JsString("STREAMING")}}
        def mutate(t:String,label:String):Unit=sql(source,s"UPDATE $t SET label='$label',revision=revision+1,amount=amount+0.1250,note='',updated_at='2026-11-12 02:03:04.123456' WHERE id=1")
        def compare(t:String):JsObject={
          val l=withConnection(source,schema)(query(_,s"SELECT * FROM $t ORDER BY id"));val r=rows(s"SELECT * FROM target_$t ORDER BY id")
          val left=l.elements.map(_.asJsObject).map(x=>x.fields("id")->x).toMap;val right=r.elements.map(_.asJsObject).map(x=>x.fields("id")->x).toMap
          val diff=(left.keySet intersect right.keySet).toVector.flatMap(k=>left(k).fields.collect{case(f,v)if right(k).fields.get(f)!=Some(v)=>JsObject("id"->k,"field"->JsString(f),"source"->v,"target"->right(k).fields.getOrElse(f,JsNull))})
          JsObject("source"->l,"target"->r,"equal"->JsBoolean(l==r),"fieldsCompared"->JsNumber(left.values.map(_.fields.size).sum),"differences"->JsArray(diff),"missing"->JsArray((left.keySet--right.keySet).toVector),"extra"->JsArray((right.keySet--left.keySet).toVector))
        }
        def equal(t:String):Unit=awaitCondition("full-field "+t){compare(t).fields("equal")==JsBoolean(true)}
        def optionalRows(table:String):JsValue=if(scalar(s"SELECT COUNT(*) AS n FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='$table'")=="0")JsNull else rows(s"SELECT * FROM `$table`")
        def snapshot(label:String):Unit=write(dir,label+".json",JsObject("scenario"->JsString(scenario),"status"->request("cdc-status"),"gc"->(try request("cdc-gc")catch {case _:Throwable=>JsObject("unavailableDuringStartupFailure"->JsBoolean(true))}),
          "comparisons"->JsObject("a"->compare("a"),"b"->compare("b")),"owner"->rows("SELECT * FROM pekko_cdc_owner_v1"),
          "tasks"->rows("SELECT * FROM pekko_cdc_tasks_v1"),"grants"->rows("SELECT * FROM pekko_cdc_grants_v1"),
          "namespace"->optionalRows("pekko_capture_gc_namespace_v1"),"pins"->optionalRows("pekko_capture_gc_pins_v1"),"audits"->optionalRows("pekko_capture_gc_audits_v1"),
          "operations"->optionalRows("pekko_capture_gc_operations_v1"),"schedule"->optionalRows("pekko_capture_gc_schedule_v1"),"checkpoints"->optionalRows("pekko_capture_gc_checkpoints_v1"),
          "L"->rows("SELECT * FROM pekko_capture_log ORDER BY seq"),"recoveredFrames"->optionalRows("pekko_capture_gc_frames_v1"),"D"->rows(s"SELECT * FROM `$offset`"),
          "J"->rows("SELECT persistence_id,sequence_number,deleted,event_ser_id,event_ser_manifest,HEX(event_payload) AS payload FROM event_journal ORDER BY ordering"),
          "T"->optionalRows("pekko_sync_cdc_group_ledger"),"baselineT"->optionalRows("pekko_bootstrap_receipts")))
        try {
          var child=start(false,"old-runtime");boot("a");if(scenario=="RG02-slow-table")boot("b")
          (1 to 6).foreach(i=>mutate("a","legacy-"+i));equal("a");snapshot("before-enable");stop(child)
          val captureStore=new cn.xuyinyin.magic.connectors.mysql.source.capture.MySQLCaptureStore(()=>DriverManager.getConnection(state.url(schema),state.user,state.password),capture,sourceUuid,128L*1024*1024)
          new cn.xuyinyin.magic.connectors.mysql.source.capture.CaptureGcRetention(()=>DriverManager.getConnection(state.url(schema),state.user,state.password),captureStore,offset).initialize()
          gcTables.foreach(t=>sql(state,s"GRANT INSERT,UPDATE,DELETE ON `$schema`.`$t` TO '$gc'@'127.0.0.1'"))
          val oldJ=rows("SELECT persistence_id,sequence_number,HEX(event_payload) AS payload FROM event_journal ORDER BY ordering")
          if(scenario=="RG03-unknown-B"){
            val schemaId=scalar(s"SELECT schema_id FROM pekko_capture_schemas WHERE table_name='b' AND capture_id='$capture'")
            sql(state,s"INSERT INTO pekko_capture_subscriptions(capture_id,request_id,table_name,generation,status,schema_id) VALUES('$capture','unknown_b','b','g2','B_PENDING','$schemaId')")
          }
          if(scenario=="RG04-orphan-Journal"){
            sql(state,"INSERT INTO event_journal(persistence_id,sequence_number,writer,write_timestamp,adapter_manifest,event_payload,event_ser_id,event_ser_manifest) SELECT 'foreign-legacy-actor',1,writer,write_timestamp,adapter_manifest,event_payload,event_ser_id,event_ser_manifest FROM event_journal LIMIT 1")
          }
          if(scenario=="RG06-inventory-rollback")sql(state,"CREATE TRIGGER probe_gc_pin_failure BEFORE INSERT ON pekko_capture_gc_pins_v1 FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='probe inventory rollback'")
          if(scenario=="RG05-unsafe-account")sql(state,s"GRANT UPDATE ON `$schema`.* TO '$sdk'@'127.0.0.1'")
          if(scenario=="RG03-unknown-B"){
            // Admission must fail safely; HTTP binding itself does not imply Reader readiness.
            val loaders=Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(l=>Option(l.getParent))).takeWhile(_.nonEmpty).flatten.collect{case l:URLClassLoader=>l.getURLs.iterator}.flatten.filter(_.getProtocol=="file").map(u=>Path.of(u.toURI).toString)
            val cp=(loaders++System.getProperty("java.class.path","").split(File.pathSeparator).iterator).filter(_.nonEmpty).toVector.distinct.mkString(File.pathSeparator)
            val pb=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx512m","-cp",cp,CdcRuntimeGcWorker.getClass.getName.stripSuffix("$"),schema,capture,src,sdk,gc,sourceUuid,stateUuid,"true").redirectErrorStream(true)
            child=new Child(pb.start(),dir.resolve("unknown-B-runtime.log"));children+=child;port=child.awaitEvent("PROCESS_READY").fields("httpPort").convertTo[Int]
            awaitCondition("unknown B does not become active"){Set("FAILED","UNKNOWN")(request("cdc-status").fields("ownerState").convertTo[String])}
            scalar("SELECT COUNT(*) AS n FROM pekko_capture_gc_frames_v1") shouldBe "0"
            rows("SELECT persistence_id,sequence_number,HEX(event_payload) AS payload FROM event_journal WHERE persistence_id NOT LIKE 'foreign%' ORDER BY ordering") shouldBe oldJ
            snapshot("unknown-B-paused")
          }else {
            child=start(true,"enabled-runtime")
            if(scenario=="RG06-inventory-rollback"){
              awaitCondition("inventory fault blocked"){request("cdc-gc").fields("items").asInstanceOf[JsArray].elements.head.asJsObject.fields("phase")==JsString("BLOCKED")}
              scalar("SELECT COUNT(*) AS n FROM pekko_capture_gc_namespace_v1") shouldBe "0"
              scalar("SELECT discovery_status FROM pekko_capture_gc_state_v1") shouldBe "UNKNOWN"
              scalar("SELECT COUNT(*) AS n FROM pekko_capture_gc_pins_v1") shouldBe "0"
              snapshot("inventory-rolled-back");stop(child);sql(state,"DROP TRIGGER probe_gc_pin_failure")
              child=start(true,"rescan-runtime")
            }
            if(Set("RG04-orphan-Journal","RG05-unsafe-account")(scenario)){
              awaitCondition("GC blocked"){request("cdc-gc").fields("items").asInstanceOf[JsArray].elements.head.asJsObject.fields("phase")==JsString("BLOCKED")}
              scalar("SELECT COUNT(*) AS n FROM pekko_capture_gc_frames_v1") shouldBe "0"
              mutate("a","gc-blocked-business-still-live");equal("a");snapshot("blocked-final");stop(child)
            }else {
              awaitCondition("old namespace certified"){scalar("SELECT phase FROM pekko_capture_gc_namespace_v1")=="COMPLETE"}
              if(scenario=="RG02-slow-table"){
                mutate("b","slow-baseline");equal("b")
                awaitCondition("b active certificate"){scalar(s"SELECT COUNT(*) AS n FROM pekko_capture_gc_checkpoints_v1 WHERE subscription_id='${task("b").taskId}' AND phase='ACTIVE'")!="0"}
                lock=DriverManager.getConnection(state.url(schema),state.user,state.password);lock.setAutoCommit(false);query(lock,"SELECT * FROM target_b WHERE id=1 FOR UPDATE")
                val pin=scalar(s"SELECT required_from FROM pekko_capture_gc_pins_v1 WHERE pin_id='subscription:${task("b").taskId}'").toLong
                mutate("b","slow-blocked");(1 to 4).foreach(i=>mutate("a","fast-"+i));equal("a")
                rows("SELECT to_exclusive FROM pekko_capture_gc_operations_v1").elements.foreach(r=>r.asJsObject.fields("to_exclusive").convertTo[String].toLong should be <= pin)
                snapshot("slow-table-window");lock.rollback();lock.close();lock=null;equal("b")
              }
              awaitCondition("actual GROUP deletion"){scalar("SELECT COUNT(*) AS n FROM pekko_capture_gc_frames_v1").toLong>0}
              snapshot("partial-GC")
              if(scenario=="RG07-unclean-restart"){
                child.send("CRASH");child.exit() shouldBe 86
                val before=rows("SELECT * FROM pekko_cdc_owner_v1")
                val loaders=Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(l=>Option(l.getParent))).takeWhile(_.nonEmpty).flatten.collect{case l:URLClassLoader=>l.getURLs.iterator}.flatten.filter(_.getProtocol=="file").map(u=>Path.of(u.toURI).toString)
                val cp=(loaders++System.getProperty("java.class.path","").split(File.pathSeparator).iterator).filter(_.nonEmpty).toVector.distinct.mkString(File.pathSeparator)
                val pb=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx512m","-cp",cp,CdcRuntimeGcWorker.getClass.getName.stripSuffix("$"),schema,capture,src,sdk,gc,sourceUuid,stateUuid,"true").redirectErrorStream(true)
                child=new Child(pb.start(),dir.resolve("unclean-restart-runtime.log"));children+=child
                port=child.awaitEvent("PROCESS_READY").fields("httpPort").convertTo[Int]
                awaitCondition("unclean owner is not guessed released"){request("cdc-status").fields("ownerState")==JsString("UNKNOWN")}
                rows("SELECT * FROM pekko_cdc_owner_v1") shouldBe before
                equal("a");snapshot("unclean-safe-paused")
                child.send("STOP");child.awaitEvent("PROCESS_STOPPED");child.exit() shouldBe 0
              }else {
              stop(child)
              val oldOps=rows("SELECT operation_id,from_seq,to_exclusive,next_seq FROM pekko_capture_gc_operations_v1")
              write(dir,"restart-plan.json",JsObject("operations"->oldOps))
              child=start(true,"restarted-runtime");mutate("a","after-runtime-restart");equal("a")
              if(scenario=="RG02-slow-table"){mutate("b","after-runtime-restart");equal("b")}
              awaitCondition("GC persists after restart"){scalar("SELECT COUNT(*) AS n FROM pekko_capture_gc_operations_v1 WHERE phase='DONE'").toLong>0}
              oldOps.elements.foreach {v=>val before=v.asJsObject.fields;val id=before("operation_id").convertTo[String]
                val after=rows(s"SELECT from_seq,to_exclusive,next_seq FROM pekko_capture_gc_operations_v1 WHERE operation_id='$id'").elements.head.asJsObject.fields
                after("from_seq") shouldBe before("from_seq");after("to_exclusive") shouldBe before("to_exclusive")
                after("next_seq").convertTo[String].toLong should be >= before("next_seq").convertTo[String].toLong
              }
              snapshot("final");stop(child)
              }
            }
          }
          write(dir,"acceptance.json",JsObject("scenario"->JsString(scenario),"status"->JsString(if(Set("RG03-unknown-B","RG07-unclean-restart")(scenario))"passed_safe_paused"else"passed"),"schema"->JsString(schema),"sourceManifest"->JsString(sys.env.getOrElse("RUNTIME_GC_MANIFEST","unfrozen")),"comparisonA"->compare("a")))
        }catch {case e:Throwable=>try snapshot("failure-state")catch{case _:Throwable=>()};write(dir,"failure.json",JsObject("scenario"->JsString(scenario),"error"->JsString(e.toString)));throw e}
        finally {if(lock!=null){try lock.rollback()finally lock.close()};children.foreach(_.kill())
          withConnection(state,"")(c=>Vector(sdk,gc).foreach(u=>executeSql(c,s"DROP USER IF EXISTS '$u'@'127.0.0.1'")))
          withConnection(source,"")(executeSql(_,s"DROP USER IF EXISTS '$src'@'127.0.0.1'"))}
      }
    }
  }
}
