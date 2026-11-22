package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.connectors.mysql.source.capture.{MySQLCaptureStore,CaptureConsumerIdentity,CaptureGcRetention}
import cn.xuyinyin.magic.tags.ExternalIntegration
import java.nio.file.{Files,Path}
import java.net.URLClassLoader
import java.io.File
import java.sql.{Connection,DriverManager}
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import spray.json._

/** Real Reader, JDBC Actor Journal and MySQL target; independent worker JVMs and durable cut evidence. */
class CaptureGcMatrixSpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._
  "L protected-prefix GC" should {
    ((1 to 16).map(i=>f"LG$i%02d") :+ "LG16-capacity").filter(x=>sys.env.get("CAPTURE_GC_CASES").forall(_.split(",").contains(x))).foreach {scenario=>
      s"preserve evidence and full-field equality for $scenario" taggedAs ExternalIntegration in {
        if(!sys.env.get("RUN_CAPTURE_GC_MATRIX").contains("1"))cancel("isolated GC MySQL required")
        require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("capture-gc"))
        Class.forName("com.mysql.cj.jdbc.Driver")
        val source=Settings.environment();val state=source.copy(port=sys.env("CAPTURE_STATE_PORT").toInt)
        require(source.port==25821 && state.port==25832)
        val suffix=UUID.randomUUID().toString.replace("-","").take(12);val schema="pekko_gc_"+suffix;val capture="gc_"+suffix;val writer="lg_"+suffix
        val dir=Path.of(sys.env("CAPTURE_GC_EVIDENCE"),scenario+"-"+suffix);Files.createDirectories(dir)
        def sql(s:Settings,q:String):Unit=withConnection(s,schema)(executeSql(_,q))
        def rows(q:String):JsArray=withConnection(state,schema)(query(_,q))
        def scalar(q:String):String=rows(q).elements.head.asJsObject.fields.values.head.convertTo[String]
        initialize(source,schema);initialize(state,schema)
        Vector("a","b","c").foreach {t=>sql(source,s"CREATE TABLE $t LIKE source_rows");sql(source,s"INSERT INTO $t SELECT * FROM source_rows");sql(state,s"CREATE TABLE target_$t LIKE source_rows")}
        withConnection(state,schema){c=>
          val in=getClass.getClassLoader.getResourceAsStream("db/mysql/pekko-sync-cdc-group-ledger-schema.sql")
          val ddl=try scala.io.Source.fromInputStream(in).mkString finally in.close()
          ddl.split(";").map(_.trim).filter(_.nonEmpty).foreach(executeSql(c,_))
          Vector("update"->"UPDATE","delete"->"DELETE").foreach {case(n,op)=>executeSql(c,s"CREATE TRIGGER pekko_ledger_no_$n BEFORE $op ON pekko_sync_cdc_group_ledger FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ledger immutable'")}
        }
        val lineage=withConnection(source,schema)(query(_,"SELECT @@server_uuid AS uuid")).elements.head.asJsObject.fields("uuid").convertTo[String]
        val store=new MySQLCaptureStore(()=>DriverManager.getConnection(state.url(schema),state.user,state.password),capture,lineage,128L*1024*1024)
        val children=scala.collection.mutable.ArrayBuffer.empty[Child]
        def start(label:String):Child={
          val loaders=Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(_.getParent match {case null=>None;case p=>Some(p)})).takeWhile(_.nonEmpty).flatten
            .collect{case l:URLClassLoader=>l.getURLs.iterator}.flatten.filter(_.getProtocol=="file").map(x=>Path.of(x.toURI).toString)
          val cp=(loaders++System.getProperty("java.class.path","").split(File.pathSeparator).iterator).filter(_.nonEmpty).toVector.distinct.mkString(File.pathSeparator)
          val p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx512m","-Duser.timezone=UTC","-cp",cp,
            BootstrapMatrixWorker.getClass.getName.stripSuffix("$"),schema,capture,"2")
          p.environment().put("CAPTURE_GC_WRITER",writer)
          if(scenario=="LG16-capacity")p.environment().put("CAPTURE_LOG_CAPACITY","90000")
          p.redirectErrorStream(true)
          val child=new Child(p.start(),dir.resolve(label+".log"));children+=child;child
        }
        def command(c:Child,s:String,event:String):JsObject={c.send(s);val result=c.awaitOneOf(Set(event,"COMMAND_FAILED"));require(result.fields("kind")==JsString(event),result.toString);result}
        def boot(c:Child,t:String="a"):Unit={command(c,s"BOOT $t $t g1","BOOT_ACCEPTED");ready(c,t)}
        def ready(c:Child,t:String="a"):Unit=awaitCondition("streaming "+t){
          val status=command(c,"STATUS","STATUS")
          status.fields("captureReadiness")==JsString("ADMISSION_READY") &&
            status.fields("actors").asJsObject.fields.get(t).exists(_.asJsObject.fields("phase")==JsString("STREAMING"))}
        def comparison(t:String):JsObject={
          val l=withConnection(source,schema)(query(_,s"SELECT * FROM $t ORDER BY id"));val r=rows(s"SELECT * FROM target_$t ORDER BY id")
          val lm=l.elements.map(_.asJsObject).map(v=>v.fields("id")->v).toMap;val rm=r.elements.map(_.asJsObject).map(v=>v.fields("id")->v).toMap
          val diff=(lm.keySet intersect rm.keySet).toVector.flatMap(k=>lm(k).fields.collect {case(f,v) if rm(k).fields.get(f)!=Some(v)=>JsObject("id"->k,"field"->JsString(f),"source"->v,"target"->rm(k).fields.getOrElse(f,JsNull))})
          JsObject("source"->l,"target"->r,"equal"->JsBoolean(l==r),"fieldsCompared"->JsNumber(l.elements.map(_.asJsObject.fields.size).sum),
            "differences"->JsArray(diff),"missing"->JsArray((lm.keySet--rm.keySet).toVector),"extra"->JsArray((rm.keySet--lm.keySet).toVector))
        }
        def equal(t:String="a"):Unit=awaitCondition("full-field "+t){comparison(t).fields("equal")==JsBoolean(true)}
        def mutate(label:String,t:String="a"):Unit={
          sql(source,s"UPDATE $t SET label='$label',revision=revision+1,amount=amount+0.1250,note='',updated_at='2026-11-12 01:02:03.123456' WHERE id=1")
        }
        def save(c:Child,label:String):Unit=write(dir,label+".json",JsObject("scenario"->JsString(scenario),"pid"->JsNumber(c.pid),"worker"->c.state(),
          "comparisons"->JsObject(Vector("a","b","c").map(t=>t->comparison(t)).toMap),
          "pins"->rows("SELECT * FROM pekko_capture_gc_pins_v1"),"retention"->rows("SELECT * FROM pekko_capture_gc_retention_v1"),
          "proofs"->rows("SELECT * FROM pekko_capture_gc_checkpoints_v1"),"operations"->rows("SELECT * FROM pekko_capture_gc_operations_v1"),
          "recoveredFrames"->rows("SELECT * FROM pekko_capture_gc_frames_v1"),"T"->rows("SELECT * FROM pekko_sync_cdc_group_ledger ORDER BY group_id"),
          "stateConnections"->rows(s"SELECT ID,DB,COMMAND,STATE FROM information_schema.processlist WHERE DB='$schema'"),
          "stateTransactions"->rows("SELECT trx_mysql_thread_id,trx_state,trx_started,trx_query FROM information_schema.innodb_trx"),
          "sourceTransactions"->withConnection(source,schema)(query(_,"SELECT trx_mysql_thread_id,trx_state,trx_started,trx_query FROM information_schema.innodb_trx"))))
        def seal(c:Child,t:String="a"):String={
          command(c,"GC_SEAL "+t,"GC_SEAL_REQUESTED");c.awaitEvent("GC_SEALED")
          command(c,"GC_REMEMBER "+t,"GC_REMEMBERED").fields("proofId").convertTo[String]
        }
        def activate(c:Child,p:String):Unit={command(c,"GC_ACTIVATE "+p,"GC_ACTIVE");()}
        def delete(c:Child,op:String="main"):Unit={
          command(c,"GC_PLAN "+op,"GC_PLANNED")
          var done=false;var batches=0
          while(!done && batches<128){done=command(c,s"GC_DELETE $op 2","GC_DELETED").fields("phase")==JsString("DONE");batches+=1}
          require(done,"GC fixed plan exceeded test bound")
        }
        def toNextGroup(c:Child,op:String="main"):Unit={
          var next=scalar(s"SELECT next_seq FROM pekko_capture_gc_operations_v1 WHERE operation_id='$op'").toLong
          val group=scalar(s"SELECT MIN(seq) AS n FROM pekko_capture_log WHERE kind='GROUP' AND seq>=$next AND seq<(SELECT to_exclusive FROM pekko_capture_gc_operations_v1 WHERE operation_id='$op')").toLong
          while(next<group){
            command(c,s"GC_DELETE $op ${math.min(128L,group-next).toInt}","GC_DELETED")
            next=scalar(s"SELECT next_seq FROM pekko_capture_gc_operations_v1 WHERE operation_id='$op'").toLong
          }
          require(next==group,"fault injection must start on an original GROUP")
        }
        var child:Child=null;var lock:Connection=null
        try {
          child=start("initial");child.awaitEvent("PROCESS_READY")
          awaitCondition("source schema"){store.schema("a").nonEmpty}
          boot(child)
          // Multiple source transactions give more than one bounded deletion batch.
          (1 to 6).foreach(i=>mutate("before-gc-"+i));equal()
          val initialProof=seal(child);if(scenario!="LG05")activate(child,initialProof)
          scenario match {
            case "LG02"|"LG03"=>
              command(child,"ARM "+(if(scenario=="LG02")"REPLAY_T"else"J_AFTER_GROUP"),"ARMED")
              mutate("fault-before-J-projection");child.awaitEvent("CUTPOINT")
              val before=scalar("SELECT COUNT(*) AS n FROM pekko_capture_log WHERE kind='GROUP'").toLong
              save(child,"cut-before-recovery");delete(child)
              scalar("SELECT COUNT(*) AS n FROM pekko_capture_log WHERE kind='GROUP'").toLong should be >= 1L
              require(before>0);child.kill();child=start("recovered");child.awaitEvent("PROCESS_READY");boot(child);equal()
            case "LG04"=>
              mutate("new-unsealed-position");equal();command(child,"ARM GC_AUDIT_COMPLETE","ARMED");command(child,"GC_SEAL a","GC_SEAL_REQUESTED");val cut=child.awaitEvent("CUTPOINT")
              val candidate=cn.xuyinyin.magic.cdc.MySQLCdcGroup.hash(cn.xuyinyin.magic.cdc.MySQLCdcGroup.canonical(cut.fields("proof")))
              scalar(s"SELECT COUNT(*) AS n FROM pekko_capture_gc_checkpoints_v1 WHERE proof_id='$candidate'") shouldBe "0"
              save(child,"audit-before-J");child.kill();child=start("recovered");child.awaitEvent("PROCESS_READY");boot(child);equal();delete(child)
            case "LG05"|"LG06"=>
              if(scenario=="LG06"){command(child,"GC_PLAN main","GC_PLANNED");toNextGroup(child)}
              val before=rows("SELECT * FROM pekko_capture_gc_retention_v1")
              command(child,"ARM_GC C1","ARMED")
              child.send(if(scenario=="LG05")"GC_ACTIVATE "+initialProof else "GC_DELETE main 2")
              child.awaitEvent("PROXY_COMMIT_OK_HELD")
              if(scenario=="LG06")scalar("SELECT deleted_rows FROM pekko_capture_gc_operations_v1 WHERE operation_id='main'").toLong should be > 0L
              write(dir,"lost-reply-durable.json",JsObject("before"->before,"after"->rows("SELECT * FROM pekko_capture_gc_retention_v1"),"operation"->rows("SELECT * FROM pekko_capture_gc_operations_v1")))
              child.kill();child=start("recovered");child.awaitEvent("PROCESS_READY");boot(child);equal()
              if(scenario=="LG05"){val prior=rows("SELECT * FROM pekko_capture_gc_retention_v1");activate(child,initialProof);rows("SELECT * FROM pekko_capture_gc_retention_v1") shouldBe prior}
              delete(child)
            case "LG07"=>
              command(child,"GC_PLAN main","GC_PLANNED");toNextGroup(child);command(child,"GC_DELETE main 1","GC_DELETED")
              scalar("SELECT deleted_rows FROM pekko_capture_gc_operations_v1 WHERE operation_id='main'").toLong shouldBe 1L
              save(child,"between-batches");child.kill();child=start("recovered");child.awaitEvent("PROCESS_READY");boot(child);equal();delete(child)
            case "LG08"=>
              command(child,"ARM_ACK BEFORE_FINISHED","ARMED");mutate("actual-D-lags");child.awaitEvent("CUTPOINT")
              val v=command(child,"GC_INSPECT","GC_VIEW");v.fields("actualAck").convertTo[Long] should be < store.lastAckSequence;write(dir,"actual-D-window.json",v)
              save(child,"ack-before-finish");delete(child);command(child,"RELEASE","GATE_RELEASED");equal()
            case "LG09"=>delete(child)
              intercept[IllegalArgumentException](store.registerConsumer(CaptureConsumerIdentity.bootstrap(capture,"a","g1"),"BOOTSTRAP","a","bootstrap",0))
              save(child,"deleted-ACK-references");child.kill();child=start("recovered");child.awaitEvent("PROCESS_READY");boot(child);equal()
            case "LG10"=>
              boot(child,"b");mutate("slow-b","b");equal("b")
              command(child,"ARM REPLAY_T b","ARMED");mutate("slow-cut","b");child.awaitEvent("CUTPOINT")
              val slowPin=rows("SELECT * FROM pekko_capture_gc_pins_v1 WHERE pin_id='subscription:b'")
              mutate("fast-a");equal();val p=seal(child);activate(child,p);delete(child)
              rows("SELECT * FROM pekko_capture_gc_pins_v1 WHERE pin_id='subscription:b'") shouldBe slowPin
              save(child,"slow-table-protected");command(child,"RELEASE","GATE_RELEASED");equal("b")
            case "LG11"=>
              command(child,"GC_PLAN main","GC_PLANNED");toNextGroup(child)
              command(child,"ARM GC_HEAD_LOCKED","ARMED")
              command(child,"GC_DELETE_ASYNC main 2","GC_DELETE_ACCEPTED");child.awaitEvent("CUTPOINT")
              command(child,"BOOT b b g1","BOOT_ACCEPTED")
              scalar("SELECT COUNT(*) AS n FROM pekko_capture_subscriptions WHERE request_id='b'") shouldBe "0"
              command(child,"RELEASE","GATE_RELEASED");child.awaitEvent("GC_DELETED")
              ready(child,"b");delete(child);mutate("new-dynamic-b","b");equal("b")
            case "LG12"=>
              command(child,"ARM READ_CLOSED c","ARMED");child.send("BOOT c c g1");child.awaitEvent("BOOT_ACCEPTED");child.awaitEvent("CUTPOINT")
              delete(child);command(child,"CANCEL c","CANCEL_RESULT");save(child,"cancel-before-K");command(child,"RELEASE","GATE_RELEASED")
              awaitCondition("cancel completes"){command(child,"STATUS","STATUS").fields("actors").asJsObject.fields("c").asJsObject.fields("phase")==JsString("CANCELED")}
            case "LG13"=>
              child.kill()
              val end=scalar("SELECT next_seq-1 AS n FROM pekko_capture_readers").toLong
              val archiveRoot=store.sealArchiveRange(1,end)
              child=start("archived-before-GC");child.awaitEvent("PROCESS_READY");boot(child)
              delete(child);store.verifyArchiveRange(1,end) shouldBe archiveRoot
              val archive=rows("SELECT seq,payload FROM pekko_capture_archive_frames_v1 ORDER BY seq LIMIT 1").elements.head.asJsObject.fields
              val jdbc=new cn.xuyinyin.magic.cdc.bootstrap.BootstrapJdbc(()=>DriverManager.getConnection(state.url(schema),state.user,state.password))
              sql(state,"UPDATE pekko_capture_archive_frames_v1 SET payload='corrupt' WHERE seq=1")
              intercept[IllegalArgumentException](store.evidence)
              jdbc.connection(c=>jdbc.update(c,"UPDATE pekko_capture_archive_frames_v1 SET payload=? WHERE seq=1",archive("payload").convertTo[String]))
              val frame=rows("SELECT * FROM pekko_capture_gc_frames_v1 ORDER BY seq LIMIT 1").elements.head.asJsObject.fields
              val proof=frame("proof_id").convertTo[String]
              sql(state,s"UPDATE pekko_capture_gc_checkpoints_v1 SET phase='PENDING_TARGET' WHERE proof_id='$proof'")
              intercept[IllegalArgumentException](store.evidence)
              child.kill();val rejected=start("inactive-proof-restart")
              rejected.awaitEvent("START_FAILED").fields("error").convertTo[String] should include ("CHECKPOINT_UNCOVERED")
              require(rejected.exit()!=0)
              sql(state,s"UPDATE pekko_capture_gc_checkpoints_v1 SET phase='ACTIVE' WHERE proof_id='$proof'")
              child=start("proof-restored");child.awaitEvent("PROCESS_READY");boot(child);equal()
              store.evidence
              val sequence=scalar(s"SELECT journal_sequence FROM pekko_capture_gc_checkpoints_v1 WHERE proof_id='$proof'")
              val journal=scalar(s"SELECT journal_id FROM pekko_capture_gc_checkpoints_v1 WHERE proof_id='$proof'")
              sql(state,s"UPDATE event_journal SET deleted=TRUE WHERE persistence_id='$journal' AND sequence_number=$sequence")
              intercept[IllegalArgumentException](store.evidence)
              sql(state,s"UPDATE event_journal SET deleted=FALSE WHERE persistence_id='$journal' AND sequence_number=$sequence")
              sql(state,"RENAME TABLE pekko_sync_cdc_group_ledger TO gc_held_ledger")
              intercept[java.sql.SQLException](store.evidence)
              sql(state,"RENAME TABLE gc_held_ledger TO pekko_sync_cdc_group_ledger")
              store.evidence
              write(dir,"corruption-rejected.json",JsObject("archiveCorruptionRejected"->JsBoolean(true),"restartWithInactiveProofRejected"->JsBoolean(true),"inactiveCertificateRejected"->JsBoolean(true),"deletedJournalRejected"->JsBoolean(true),"missingLedgerRejected"->JsBoolean(true)))
            case "LG14"=>
              val schemaId=store.schema("c").get.id;store.register("unknown_b","c","g1",schemaId)
              val before=rows("SELECT * FROM pekko_capture_gc_frames_v1")
              command(child,"GC_PLAN unknown","COMMAND_FAILED").fields("error").convertTo[String] should include ("unknown B blocks GC")
              rows("SELECT * FROM pekko_capture_gc_frames_v1") shouldBe before
              sql(state,"UPDATE pekko_capture_gc_state_v1 SET discovery_status='UNKNOWN'")
              command(child,"GC_PLAN incomplete","COMMAND_FAILED").fields("error").convertTo[String] should include ("namespace discovery is incomplete")
              rows("SELECT * FROM pekko_capture_gc_frames_v1") shouldBe before
              sql(state,"UPDATE pekko_capture_gc_state_v1 SET discovery_status='COMPLETE'")
              save(child,"unknown-B-safe-pause")
            case "LG15"=>
              val conflict=start("conflicting-owner");conflict.awaitEvent("START_FAILED");require(conflict.exit()!=0)
              val before=rows("SELECT * FROM pekko_capture_gc_frames_v1")
              val foreign=state.copy(port=25822)
              val foreignUrl=foreign.url("pekko_test_shared_state")
              val misplaced=new CaptureGcRetention(()=>DriverManager.getConnection(foreignUrl,state.user,state.password),store,"capture_offsets")
              intercept[IllegalArgumentException](misplaced.plan("wrong-database")).getMessage should include ("GC state database identity changed")
              write(dir,"database-identity-rejected.json",JsObject("state"->rows("SELECT @@server_uuid AS uuid,DATABASE() AS db"),
                "foreign"->withConnection(foreign,"")(query(_,"SELECT @@server_uuid AS uuid")),"rejected"->JsBoolean(true)))
              sql(state,s"UPDATE pekko_capture_readers SET lineage='wrong' WHERE capture_id='$capture'")
              command(child,"GC_PLAN identity-change","COMMAND_FAILED");rows("SELECT * FROM pekko_capture_gc_frames_v1") shouldBe before
              sql(state,s"UPDATE pekko_capture_readers SET lineage='$lineage' WHERE capture_id='$capture'");child.kill();child=start("identity-restored");child.awaitEvent("PROCESS_READY");boot(child);equal();delete(child)
            case "LG16-capacity"=>
              delete(child)
              (1 to 100).foreach(i=>mutate("capacity-overflow-"+i))
              awaitCondition("real Reader refuses full L"){
                command(child,"STATUS","STATUS").fields("captureFailure").toString.contains("capacity exhausted")}
              awaitCondition("table pauses with retained Journal context"){
                command(child,"STATUS","STATUS").fields("actors").asJsObject.fields("a").asJsObject.fields("phase")==JsString("PAUSED")}
              comparison("a").fields("equal") shouldBe JsBoolean(false)
              scalar("SELECT used_bytes FROM pekko_capture_readers").toLong should be <= 90000L
              val view=command(child,"GC_INSPECT","GC_VIEW");view.fields("actualAck") should not be JsNull
              write(dir,"capacity-safe-pause.json",JsObject("view"->view,"converged"->JsBoolean(false),"comparison"->comparison("a")))
            case "LG16"=>
              lock=DriverManager.getConnection(state.url(schema),state.user,state.password);lock.setAutoCommit(false)
              query(lock,"SELECT * FROM pekko_capture_readers FOR UPDATE")
              command(child,"GC_PLAN locked","COMMAND_FAILED");lock.rollback();lock.close();lock=null
              delete(child)
            case _=>delete(child)
          }
          if(!Set("LG14","LG16-capacity")(scenario)){
            mutate("after-gc");equal()
            if(!Set("LG02","LG03","LG04","LG05","LG06","LG07","LG09")(scenario)){
              child.kill();child=start("post-GC-restart");child.awaitEvent("PROCESS_READY");boot(child);equal()
            }
            mutate("after-restart");equal()
          }
          val charge="OCTET_LENGTH(payload)+CHAR_LENGTH(COALESCE(resume_json,''))+256"
          val accounting=rows(s"SELECT used_bytes,(SELECT COALESCE(SUM($charge),0) FROM pekko_capture_log)+(SELECT COALESCE(SUM($charge+64),0) FROM pekko_capture_gc_frames_v1) AS recomputed FROM pekko_capture_readers")
          val a=accounting.elements.head.asJsObject.fields;a("used_bytes") shouldBe a("recomputed")
          write(dir,"logical-budget.json",JsObject("rows"->accounting,"databaseFilesShrunk"->JsBoolean(false)))
          if(scenario=="LG16"){
            // Capacity refusal must roll back its metadata too; the actual Reader/J/T remain live.
            val before=rows("SELECT next_seq,used_bytes FROM pekko_capture_readers")
            val tiny=new MySQLCaptureStore(()=>DriverManager.getConnection(state.url(schema),state.user,state.password),capture,lineage,1)
            intercept[IllegalArgumentException](tiny.rememberSchema("capacity_probe","{}"))
            rows("SELECT next_seq,used_bytes FROM pekko_capture_readers") shouldBe before
            scalar("SELECT COUNT(*) AS n FROM pekko_capture_schemas WHERE table_name='capacity_probe'") shouldBe "0"
            val p=seal(child);activate(child,p);command(child,"GC_PLAN close-unknown","GC_PLANNED");toNextGroup(child,"close-unknown")
            command(child,"ARM_GC_CLOSE","ARMED");val failure=command(child,"GC_DELETE close-unknown 1","COMMAND_FAILED")
            write(dir,"close-unknown.json",failure)
            val conflict=start("close-unknown-conflict");conflict.awaitEvent("START_FAILED");require(conflict.exit()!=0)
            equal()
          }
          save(child,"final")
          write(dir,"acceptance.json",JsObject("scenario"->JsString(scenario),"status"->JsString(if(scenario=="LG16-capacity")"passed_safe_paused"else"passed"),"schema"->JsString(schema),
            "sourceManifest"->JsString(sys.env.getOrElse("CAPTURE_GC_MANIFEST","unfrozen")),"fullFieldA"->comparison("a"),
            "unknownBUnchanged"->JsBoolean(true),"deletedGroups"->JsNumber(scalar("SELECT COUNT(*) AS n FROM pekko_capture_gc_frames_v1").toLong)))
        }catch {case e:Throwable=>write(dir,"failure.json",JsObject("scenario"->JsString(scenario),"status"->JsString("failed"),"error"->JsString(e.toString)));throw e}
        finally {
          if(lock!=null){try lock.rollback()finally lock.close()}
          children.foreach(_.kill())
          withConnection(state,"")(executeSql(_,s"DROP USER IF EXISTS '$writer'@'127.0.0.1'"))
        }
      }
    }
  }
}
