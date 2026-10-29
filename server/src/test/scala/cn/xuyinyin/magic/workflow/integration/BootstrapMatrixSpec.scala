package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.connectors.mysql.source.capture.MySQLCaptureStore
import cn.xuyinyin.magic.tags.ExternalIntegration
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import spray.json._
import java.nio.file.{Files,Path}
import java.sql.{Connection,DriverManager}
import java.net.URLClassLoader
import java.io.File
import java.util.UUID

class BootstrapMatrixSpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._
  protected def markerUnknownContract:Boolean=false
  private val markerUnknownVariants=Set("I10","I10-low-C0","I10-low-C1","I10-low-late-commit","I10-low-late-rollback",
    "I10-F-C1","I10-high-C0","I10-high-late-commit","I10-high-late-rollback","I10-F-C0",
    "I10-F-late-commit","I10-F-late-rollback","I22-late-low")
  "online bootstrap with real MySQL, Actor Journal and Sink" should {
    for(variant<-Vector("I01","I02","I03","I04","I05","I06","I07","I08","I09","I10","I11","I12","I13","I14","I15","I16","I17","I18","I19","I20","I21","I22","I23","I24","I10-low-C0","I10-low-C1","I10-low-late-commit","I10-low-late-rollback","I10-F-C1","I17-C0","I17-late-commit","I17-late-rollback","I13-prefix","I15-raw","I15-schema","I15-L","I18-reject","I18-budget","I18-release","I19-last-window","I20-split","I21-postF","I22-beforeB","I22-target","I22-unknown-B","I23-activation","I24-capacity","I18-replay-timeout","I18-replay-close","I17-replay-C0","I17-replay-C1","I17-replay-late-commit","I17-replay-late-rollback","I15-registry","I22-late-low","I10-high-C0","I10-high-late-commit","I10-high-late-rollback","I10-F-C0","I10-F-late-commit","I10-F-late-rollback","I18-reject-after-T")) {
      if(!markerUnknownContract || markerUnknownVariants(variant)){
      val scenario=variant.take(3)
      val safePauseCase=markerUnknownVariants(variant) &&
        (markerUnknownContract || !sys.env.get("BOOTSTRAP_LEGACY_MARKER_EXPECTATIONS").contains("1"))
      val caseName=if(safePauseCase)s"retain SAFE_PAUSED source-marker-unknown evidence for $variant"
        else s"preserve window, application and acknowledgement evidence for $variant"
      caseName taggedAs ExternalIntegration in {
        if(!sys.env.get("RUN_SHARED_CAPTURE_MATRIX").contains("1"))cancel("dedicated dual MySQL required")
        val source=Settings.environment();val state=source.copy(port=sys.env("CAPTURE_STATE_PORT").toInt)
        val suffix=UUID.randomUUID().toString.replace("-","").take(12);val schema="pekko_test_cdc_window_"+suffix;val captureId="boot_"+suffix
        val dir=Path.of(if(markerUnknownContract)"target/bootstrap-marker-unknown"else"target/bootstrap-matrix",sys.env.getOrElse("BOOTSTRAP_EVIDENCE_RUN","manual"),variant+"-"+suffix).toAbsolutePath;Files.createDirectories(dir)
        initialize(source,schema);initialize(state,schema)
        def sql(s:Settings,q:String)=withConnection(s,schema)(executeSql(_,q))
        val names=if(scenario=="I01")Vector("a","b","c","d","e","f","g")else if(Set("I22-late-low","I22-unknown-B","I24-capacity")(variant))Vector("a","b")else Vector("a")
        names.foreach {t=>sql(source,s"CREATE TABLE $t LIKE source_rows");sql(source,s"INSERT INTO $t SELECT * FROM source_rows");sql(state,s"CREATE TABLE target_$t LIKE source_rows")}
        withConnection(state,schema){c=>val input=getClass.getClassLoader.getResourceAsStream("db/mysql/pekko-sync-cdc-group-ledger-schema.sql");val ddl=try scala.io.Source.fromInputStream(input).mkString finally input.close();ddl.split(";").map(_.trim).filter(_.nonEmpty).foreach(executeSql(c,_))}
        if(scenario=="I02")sql(source,"DELETE FROM a")
        if(scenario=="I03"){sql(source,"DELETE FROM a");sql(source,"INSERT INTO a SELECT * FROM source_rows WHERE id IN (1,2,3)");sql(source,"UPDATE a SET id=-50 WHERE id=1");sql(source,"UPDATE a SET id=200 WHERE id=3")}
        val lineage=withConnection(source,schema)(query(_,"SELECT @@server_uuid AS uuid")).elements.head.asJsObject.fields("uuid").convertTo[String]
        val store=new MySQLCaptureStore(()=>DriverManager.getConnection(state.url(schema),state.user,state.password),captureId,lineage,128L*1024*1024)
        def start(size:Int,label:String):Child={
          val loaders=Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(x=>Option(x.getParent))).takeWhile(_.nonEmpty).flatten.collect{case l:URLClassLoader=>l.getURLs.iterator}.flatten.filter(_.getProtocol=="file").map(x=>Path.of(x.toURI).toString)
          val cp=(loaders++System.getProperty("java.class.path","").split(File.pathSeparator).iterator).filter(_.nonEmpty).toVector.distinct.mkString(File.pathSeparator)
          val p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx512m","-Duser.timezone=UTC","-cp",cp,BootstrapMatrixWorker.getClass.getName.stripSuffix("$"),schema,captureId,size.toString)
          p.redirectErrorStream(true);new Child(p.start(),dir.resolve(label+".log"))
        }
        def comparison(t:String):JsObject={
          val left=withConnection(source,schema)(query(_,s"SELECT * FROM $t ORDER BY id"));val right=withConnection(state,schema)(query(_,s"SELECT * FROM target_$t ORDER BY id"))
          val l=left.elements.map(_.asJsObject).map(r=>r.fields("id")->r).toMap;val r=right.elements.map(_.asJsObject).map(r=>r.fields("id")->r).toMap
          val differences=(l.keySet intersect r.keySet).toVector.flatMap(k=>l(k).fields.toVector.collect{case(f,v)if r(k).fields.get(f)!=Some(v)=>JsObject("id"->k,"field"->JsString(f),"source"->v,"target"->r(k).fields.getOrElse(f,JsNull))})
          JsObject("source"->left,"target"->right,"equal"->JsBoolean(left==right),"fieldsCompared"->JsNumber(l.values.map(_.fields.size).sum),"differences"->JsArray(differences),"missing"->JsArray((l.keySet--r.keySet).toVector),"extra"->JsArray((r.keySet--l.keySet).toVector))
        }
        def equal(t:String)=comparison(t).fields("equal")==JsBoolean(true)
        def mutation(t:String,label:String)=sql(source,s"UPDATE $t SET label='$label',revision=revision+1,amount=amount+0.1250,note='',updated_at='2026-10-25 01:02:03.123456' WHERE id=(SELECT n FROM (SELECT MIN(id) n FROM $t) x)")
        def boot(c:Child,t:String="a",id:String="a",target:Option[String]=None):Unit={c.send(s"BOOT $id $t ${if(id=="new_a")"g2"else"g1"}"+target.fold("")(x=>" "+x));c.awaitEvent("BOOT_ACCEPTED")}
        def status(c:Child):JsObject={c.send("STATUS");c.awaitEvent("STATUS")}
        def phase(c:Child,id:String="a"):String=status(c).fields("actors").asJsObject.fields(id).asJsObject.fields("phase").convertTo[String]
        def ready(c:Child,t:String="a"):Unit=awaitCondition("streaming "+t){phase(c,t)=="STREAMING"}
        def arm(c:Child,k:String):Unit={c.send("ARM "+k);c.awaitEvent("ARMED")}
        def release(c:Child):Unit={c.send("RELEASE");c.awaitEvent("GATE_RELEASED")}
        def crash(c:Child):Unit={c.send("CRASH");c.exit() shouldBe 86}
        def save(label:String,c:Child):Unit={write(dir,label+".json",JsObject("case"->JsString(variant),"pid"->JsNumber(c.pid),"worker"->c.state(),"comparisons"->JsObject(names.map(t=>t->comparison(t)).toMap),
          "sourceControls"->withConnection(source,schema)(query(_,"SELECT * FROM pekko_capture_control ORDER BY operation_id")),
          "T"->withConnection(state,schema)(query(_,"SELECT * FROM pekko_sync_cdc_group_ledger ORDER BY group_id")),
          "baselineT"->withConnection(state,schema)(query(_,"SELECT * FROM pekko_bootstrap_receipts ORDER BY bootstrap_id,operation_id")),
          "sourceTransactions"->withConnection(source,schema)(query(_,"SELECT trx_mysql_thread_id,trx_state,trx_started,trx_query FROM information_schema.innodb_trx")),
          "stateTransactions"->withConnection(state,schema)(query(_,"SELECT trx_mysql_thread_id,trx_state,trx_started,trx_query FROM information_schema.innodb_trx")),
          "sourceConnections"->withConnection(source,schema)(query(_,s"SELECT ID,DB,COMMAND,STATE FROM information_schema.processlist WHERE DB='$schema'")),
          "stateConnections"->withConnection(state,schema)(query(_,s"SELECT ID,DB,COMMAND,STATE FROM information_schema.processlist WHERE DB='$schema'"))))}
        def safeMarkerPause(c:Child):Unit={
          awaitCondition("source marker uncertainty safely pauses"){
            val value=status(c);val actors=value.fields("actors").asJsObject.fields
            value.fields("captureFailure")!=JsNull && actors.values.forall(_.asJsObject.fields("phase")==JsString("PAUSED"))
          }
          val before=c.state();val actors=before.fields("actors").asJsObject.fields
          actors.values.foreach {v=>val f=v.asJsObject.fields
            f("failure") shouldBe JsString("local_release_unconfirmed");f("recoveryAttempts") shouldBe JsNumber(0)}
          before.fields("captureFailureCode") shouldBe JsString("local_release_unconfirmed")
          before.fields("captureFailureStage") shouldBe JsString("source-marker:MARKER_COMMIT_UNKNOWN")
          before.fields("captureReleaseConfirmed") shouldBe JsBoolean(false)
          val action=if(variant.contains("low"))"LOW"else if(variant.contains("F-"))"F"else"HIGH"
          val fenceId=if(action=="F")"a_F"else if(action=="LOW")"a_0_0_L"else"a_0_0_H"
          val attempts=c.events.filter(e=>e.fields.get("kind").contains(JsString("SOURCE_CONTROL_ATTEMPT")) &&
            e.fields.get("table").contains(JsString("a")) && e.fields.get("action").contains(JsString(action)))
          attempts.size shouldBe 1
          attempts.head.fields("operationId") shouldBe JsString(store.fenceOperation(fenceId))
          val targetBefore=withConnection(state,schema)(query(_,"SELECT * FROM pekko_bootstrap_receipts ORDER BY bootstrap_id,operation_id"))
          val groupsBefore=withConnection(state,schema)(query(_,"SELECT * FROM pekko_sync_cdc_group_ledger ORDER BY group_id"))
          targetBefore.elements.filter {r=>val f=r.asJsObject.fields;f("bootstrap_id")==JsString("a") && f("operation_id")==JsString("ACTIVATE")} shouldBe empty
          save("safe-paused-before-retry",c)
          c.send("RETRY_FENCE "+fenceId)
          val rejection=c.awaitEvent("COMMAND_FAILED");rejection.fields("command") shouldBe JsString("RETRY_FENCE")
          val after=c.state()
          after.fields("actors") shouldBe before.fields("actors")
          withConnection(state,schema)(query(_,"SELECT * FROM pekko_bootstrap_receipts ORDER BY bootstrap_id,operation_id")) shouldBe targetBefore
          withConnection(state,schema)(query(_,"SELECT * FROM pekko_sync_cdc_group_ledger ORDER BY group_id")) shouldBe groupsBefore
          c.events.count(e=>e.fields.get("kind").contains(JsString("SOURCE_CONTROL_ATTEMPT")) &&
            e.fields.get("operationId")==Some(JsString(store.fenceOperation(fenceId)))) shouldBe 1
          save("safe-paused-after-retry",c)
          write(dir,"acceptance.json",JsObject("status"->JsString("SAFE_PAUSED"),"converged"->JsBoolean(false),
            "initializationCompleted"->JsBoolean(false),"variant"->JsString(variant),"originalFence"->JsString(fenceId),
            "operationId"->JsString(store.fenceOperation(fenceId)),"sourceWriteAttempts"->JsNumber(1),
            "recoveryAttempts"->JsNumber(0),"releaseConfirmed"->JsBoolean(false)))
        }
        var child:Child=null;var recovered:Child=null;var transaction:Connection=null;var blocker:Connection=null
        write(dir,"environment.json",JsObject("case"->JsString(variant),"acceptanceMode"->JsString(if(safePauseCase)"SAFE_PAUSED_MARKER_UNKNOWN"else"LEGACY_ASSERTION"),"sourceManifestSha256"->JsString(sys.env.getOrElse("BOOTSTRAP_SOURCE_MANIFEST_SHA256","unfrozen")),"schema"->JsString(schema),"captureId"->JsString(captureId),"sourceLineage"->JsString(lineage),"sourceVersion"->withConnection(source,schema)(query(_,"SELECT VERSION() AS version,@@binlog_format AS format,@@binlog_row_image AS row_image")),"targetBaseline"->JsString("exclusive initially empty; business publication only after durable STREAMING")))
        try {
          child=start(if(variant=="I20-split")128 else 1,"original");child.awaitEvent("PROCESS_READY");awaitCondition("schema ready"){names.forall(t=>store.schema(t).nonEmpty)}
          scenario match {
            case "I01"=>
              Vector("a","b","c","d","e").foreach(t=>boot(child,t,t));Vector("a","b","c","d","e").foreach(t=>ready(child,t))
              Vector("f","g").foreach(t=>boot(child,t,t));names.foreach(t=>mutation(t,"dynamic-seven"));names.foreach(t=>ready(child,t));awaitCondition("seven full field equal"){names.forall(equal)}
            case "I02"=>boot(child);ready(child);sql(source,"INSERT INTO a SELECT * FROM source_rows WHERE id=1");awaitCondition("empty F anchor tail"){equal("a")}
            case "I03"|"I04"|"I05"|"I06"|"I07"|"I08"|"I09"=>
              val gate=Map("I03"->"LOW_COVERED","I04"->"READ_OPEN","I05"->"READ_CLOSED","I06"->"READ_OPEN","I07"->"SEALED","I08"->"WINDOW_COMPLETE","I09"->"WINDOW_REGISTERED")(scenario)
              if(scenario=="I06"){transaction=DriverManager.getConnection(source.url(schema),source.user,source.password);transaction.setAutoCommit(false);executeSql(transaction,"UPDATE a SET label='tx-first',revision=revision+1 WHERE id IN (1,2,3)");executeSql(transaction,"UPDATE a SET label='tx-second',note=NULL WHERE id=2")}
              arm(child,gate);boot(child);child.awaitEvent("CUTPOINT");save("cut",child)
              scenario match {
                case "I03"=>sql(source,"INSERT INTO a SELECT -100,label,amount,note,updated_at,revision FROM source_rows WHERE id=1");sql(source,"INSERT INTO a SELECT 250,label,amount,note,updated_at,revision FROM source_rows WHERE id=2")
                case "I04"=>mutation("a","during-view");sql(source,"DELETE FROM a WHERE id=3")
                case "I05"=>mutation("a","after-read");sql(source,"DELETE FROM a WHERE id=3");sql(source,"INSERT INTO a SELECT * FROM source_rows WHERE id=3")
                case "I06"=>transaction.commit();transaction.close();transaction=null
                case "I07"=>mutation("a","after-high");sql(source,"DELETE FROM a WHERE id=64")
                case "I08"=>sql(source,"UPDATE a SET id=1000,label='forward' WHERE id=1");sql(source,"UPDATE a SET id=-40,label='backward' WHERE id=40");child.awaitEvent("DELETE_CALLBACK_RETURNED")
                case "I09"=>sql(source,"DELETE FROM a WHERE id<=32")
              }
              release(child);ready(child);awaitCondition("window mutation full equality"){equal("a")}
            case "I10"|"I17"=>
              val gate=if(variant.contains("replay"))"READ_CLOSED"else if(scenario=="I17")"SEALED"else if(variant.contains("low"))"WINDOW_REGISTERED"else if(variant.contains("F-"))"BEFORE_F"else "READ_CLOSED"
              arm(child,gate);boot(child);child.awaitEvent("CUTPOINT")
              if(variant.contains("replay"))sql(source,"UPDATE a SET label='replay-commit',revision=revision+1 WHERE id IN (1,2,3)")
              val mode=if(variant.endsWith("C0"))"C0"else if(variant.endsWith("late-commit"))"LATE_COMMIT"else if(variant.endsWith("late-rollback"))"LATE_ROLLBACK"else "C1"
              child.send((if(scenario=="I10")"ARM_CONTROL "else if(variant.contains("replay"))"ARM_REPLAY "else "ARM_TARGET ")+mode);child.awaitEvent("ARMED");release(child);child.awaitEvent("CUTPOINT");save("commit-uncertain",child)
              if(mode.startsWith("LATE")){child.send("RESOLVE_TX");child.awaitEvent("TX_RESOLVE_REQUESTED")}else {child.send("DROP_RESPONSE");child.awaitEvent("RESPONSE_RELEASED")}
              if(safePauseCase)safeMarkerPause(child)
              else {ready(child);awaitCondition("uncertain commit recovered"){equal("a")}}
            case "I11"|"I12"|"I14"|"I16"|"I19"|"I20"|"I21"=>
              val gate0=Map("I11"->"RAW_PAGE","I12"->"RAW_PAGE","I14"->"FOLD_GROUP","I16"->"BASELINE_T","I19"->"F_READY","I20"->"REPLAY_T","I21"->"ACTIVATION_T")(scenario)
              val gate=if(variant=="I19-last-window")"LAST_WINDOW_COMPLETE"else if(variant=="I21-postF")"F_READY"else gate0
              if(Set("I14","I20")(scenario)){arm(child,"READ_CLOSED");boot(child);child.awaitEvent("CUTPOINT");sql(source,"UPDATE a SET label='force-replay',revision=revision+1 WHERE id IN (1,2,3,4,5)");child.send("ARM_NEXT "+gate);child.awaitEvent("ARMED");release(child)}
              else {arm(child,gate);boot(child)}
              val cut=child.awaitEvent("CUTPOINT");save("cut",child)
              if(scenario=="I12"){
                val connection=cut.fields("connectionId").convertTo[String];withConnection(source,schema)(executeSql(_,"KILL CONNECTION "+connection));release(child)
                ready(child);awaitCondition("new read view full equality"){equal("a")}
              }else{
                crash(child);mutation("a","source-changed-after-crash");if(scenario=="I11")sql(source,"DELETE FROM a WHERE id=32");recovered=start(if(variant=="I20-split")1 else 128,"recovered");recovered.awaitEvent("PROCESS_READY");boot(recovered);ready(recovered);awaitCondition("recovered full equality"){equal("a")}
                if(scenario=="I11"){
                  val windows=withConnection(state,schema)(query(_,"SELECT spec_json FROM pekko_bootstrap_windows WHERE bootstrap_id='a' ORDER BY window_id")).elements.map(_.asJsObject.fields("spec_json").convertTo[String].parseJson.asJsObject)
                  val first=windows.filter(_.fields("index")==JsNumber(0));first.size should be >= 2
                  first.map(_.fields("upper")).distinct.size shouldBe 1
                }
              }
            case "I13"=>
              if(variant=="I13-prefix"){
                arm(child,"HIGH_BOUND");boot(child);child.awaitEvent("CUTPOINT");save("HIGH-not-covered",child)
                val f=store.fence("a_0_0_H").get;f.sequence.nonEmpty shouldBe true;f.covered shouldBe false
                val manifests=withConnection(state,schema)(query(_,"SELECT COUNT(*) n FROM pekko_bootstrap_windows WHERE manifest_json IS NOT NULL"));manifests.elements.head.asJsObject.fields("n") shouldBe JsString("0")
                release(child);ready(child)
              }else {
                arm(child,"F_BOUND");boot(child);child.awaitEvent("CUTPOINT")
                store.fence("a_F").get.covered shouldBe false
                val digest=java.security.MessageDigest.getInstance("SHA-256").digest(captureId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).map(b=>f"${b&255}%02x").mkString.take(32)
                blocker=DriverManager.getConnection(state.url(schema),state.user,state.password);blocker.setAutoCommit(false);query(blocker,s"SELECT * FROM capture_offsets_$digest FOR UPDATE")
                save("F-bound-ACK-pending-D-held",child);release(child);ready(child);store.fence("a_F").get.covered shouldBe true;equal("a") shouldBe true;save("streaming-with-D-held",child);blocker.rollback();blocker.close();blocker=null
              }
            case "I15"=>
              arm(child,"SEALED");boot(child);child.awaitEvent("CUTPOINT");save("before-corruption",child);crash(child)
              variant match {
                case "I15-registry"=>sql(state,"DELETE FROM pekko_bootstrap_storage WHERE bootstrap_id='a'")
                case "I15-raw"=>sql(state,"UPDATE pekko_bootstrap_pages SET payload='[]' WHERE bootstrap_id='a' AND page_kind='RAW' AND page_index=0")
                case "I15-schema"=>sql(state,s"DELETE FROM pekko_capture_log WHERE capture_id='$captureId' AND kind='SCHEMA' AND table_name='a'")
                case "I15-L"=>sql(state,s"DELETE FROM pekko_capture_log WHERE capture_id='$captureId' AND kind='FENCE'")
                case _=>sql(state,"UPDATE pekko_bootstrap_pages SET payload='[]' WHERE bootstrap_id='a' AND page_kind='FINAL' AND page_index=0")
              }
              recovered=start(128,"recovered")
              if(Set("I15-schema","I15-L")(variant)){recovered.awaitEvent("START_FAILED");recovered.exit() should not be 0}
              else {recovered.awaitEvent("PROCESS_READY");boot(recovered);awaitCondition("corruption safely paused"){phase(recovered)=="PAUSED"}}
            case "I18" if variant=="I18-replay-timeout"=>
              arm(child,"READ_CLOSED");boot(child);child.awaitEvent("CUTPOINT");mutation("a","pending-replay-commit")
              child.send("ARM_REPLAY C1");child.awaitEvent("ARMED");release(child);child.awaitEvent("CUTPOINT");save("replay-COMMIT-response-held",child)
              child.send("CANCEL a");child.awaitEvent("CANCEL_ACCEPTED")
              awaitCondition("pending sink timeout terminal") {Set("PAUSED","CANCELED")(phase(child))}
              phase(child) shouldBe "PAUSED"
              val held=status(child).fields("admission").asJsObject;held.fields("writeAvailable") shouldBe JsNumber(1);held.fields("runAvailable") shouldBe JsNumber(7)
              child.send("DROP_RESPONSE");child.awaitEvent("RESPONSE_RELEASED")
            case "I18" if variant=="I18-replay-close"=>
              arm(child,"READ_CLOSED");boot(child);child.awaitEvent("CUTPOINT");mutation("a","replay-close-unconfirmed")
              child.send("ARM_REPLAY_CLOSE");child.awaitEvent("ARMED");release(child)
              awaitCondition("replay close loss pauses"){phase(child)=="PAUSED"}
              val held=status(child).fields("admission").asJsObject;held.fields("writeAvailable") shouldBe JsNumber(1);held.fields("runAvailable") shouldBe JsNumber(7)
            case "I18"=>
              arm(child,if(variant=="I18-reject-after-T")"BASELINE_T"else"SEALED");boot(child);child.awaitEvent("CUTPOINT");save("before-failure",child)
              variant match {
                case "I18-reject"|"I18-reject-after-T"=>child.send("REJECT_J");child.awaitEvent("ARMED")
                case "I18-release"=>child.send("ARM_CLOSE");child.awaitEvent("ARMED")
                case "I18-budget"=>child.send("ARM_TARGET REPEAT_C0");child.awaitEvent("ARMED")
                case _=>sql(state,"UPDATE pekko_bootstrap_windows SET manifest_hash=REPEAT('0',64) WHERE bootstrap_id='a'")
              }
              release(child)
              if(variant=="I18-budget")(1 to 4).foreach {_=>child.awaitEvent("CUTPOINT");child.send("DROP_RESPONSE");child.awaitEvent("RESPONSE_RELEASED")}
              awaitCondition("failure safely pauses"){phase(child)=="PAUSED"}
              val s=status(child).fields("actors").asJsObject.fields("a").asJsObject
              s.fields("recoveryAttempts") shouldBe JsNumber(if(variant=="I18-budget")3 else 0)
            case "I22" if variant=="I22-unknown-B"=>
              boot(child,"b","b");ready(child,"b")
              arm(child,"BOOTSTRAP_REGISTERED");boot(child);child.awaitEvent("CUTPOINT")
              child.send("ARM_CONTROL LATE_COMMIT");child.awaitEvent("ARMED");release(child);child.awaitEvent("CUTPOINT")
              save("B-transaction-held",child)
              child.send("CANCEL a");val decision=child.awaitEvent("CANCEL_RESULT");save("cancel-requested",child)
              awaitCondition("unknown B pauses runner",scala.concurrent.duration.Duration(90,"seconds"))(phase(child)=="PAUSED")
              store.subscription("a").get.status shouldBe "B_PENDING"
              store.subscription("a").get.b shouldBe None;store.subscription("a").get.k shouldBe None
              write(dir,"B-cancel-decision.json",decision)
              child.send("RESOLVE_TX");child.awaitEvent("TX_RESOLVE_REQUESTED");child.awaitEvent("PROXY_LATE_COMMIT_OK")
              save("B-late-commit",child)
              store.subscription("a").get.status shouldBe "B_PENDING"
              phase(child) shouldBe "PAUSED"
              equal("b") shouldBe true
            case "I22" if variant=="I22-late-low"=>
              boot(child,"b","b");ready(child,"b")
              arm(child,"WINDOW_REGISTERED");boot(child);child.awaitEvent("CUTPOINT")
              child.send("ARM_CONTROL LATE_COMMIT");child.awaitEvent("ARMED");release(child);child.awaitEvent("CUTPOINT");save("LOW-transaction-held",child)
              if(safePauseCase){
                awaitCondition("capture domain pauses before cancel"){
                  val value=status(child);value.fields("captureFailure")!=JsNull &&
                    value.fields("actors").asJsObject.fields.values.forall(_.asJsObject.fields("phase")==JsString("PAUSED"))}
                child.send("CANCEL a")
                val decision=child.awaitEvent("CANCEL_RESULT")
                decision.fields("phase") shouldBe JsString("PAUSED")
                decision.fields("cancelRequested") shouldBe JsBoolean(false)
                child.awaitEvent("CANCEL_NOT_ACCEPTED")
                store.subscription("a").get.k shouldBe None
                save("cancel-not-accepted-no-K",child)
                child.send("RESOLVE_TX");child.awaitEvent("TX_RESOLVE_REQUESTED");child.awaitEvent("PROXY_LATE_COMMIT_OK")
                safeMarkerPause(child)
                store.subscription("a").get.k shouldBe None
              }else {
                child.send("CANCEL a");child.awaitEvent("CANCEL_ACCEPTED");awaitCondition("K before late LOW"){phase(child)=="CANCELED"};save("K-before-LOW",child)
                child.send("RESOLVE_TX");child.awaitEvent("TX_RESOLVE_REQUESTED");child.awaitEvent("PROXY_LATE_COMMIT_OK")
                awaitCondition("late LOW obsolete"){store.fence("a_0_0_L").exists(_.obsolete)}
                mutation("b","other-table-after-late-LOW");awaitCondition("other table continues"){equal("b")}
              }
            case "I22"|"I23"=>
              arm(child,if(variant=="I22-beforeB")"BOOTSTRAP_REGISTERED"else if(variant=="I22-target")"BASELINE_T"else if(variant=="I23-activation")"ACTIVATION_T"else if(scenario=="I22")"READ_OPEN"else "BEFORE_ACTIVATE");boot(child);child.awaitEvent("CUTPOINT");save("before-cancel",child)
              child.send("CANCEL a");child.awaitEvent("CANCEL_ACCEPTED");release(child);awaitCondition("cancel drained"){phase(child)=="CANCELED"}
              if(variant=="I22-beforeB")store.subscription("a") shouldBe None else store.subscription("a").get.k.nonEmpty shouldBe true
              if(scenario=="I23"){sql(state,"CREATE TABLE target_new LIKE source_rows");boot(child,"a","new_a",Some("target_new"));ready(child,"new_a")
                val left=withConnection(source,schema)(query(_,"SELECT * FROM a ORDER BY id"));val right=withConnection(state,schema)(query(_,"SELECT * FROM target_new ORDER BY id"));left shouldBe right;write(dir,"new-generation.json",JsObject("source"->left,"target"->right,"equal"->JsBoolean(left==right)))}
            case "I24"=>
              if(variant=="I24-capacity"){boot(child,"b","b");ready(child,"b")}
              arm(child,"READ_CLOSED");boot(child);child.awaitEvent("CUTPOINT");if(variant=="I24-capacity")sql(state,"UPDATE pekko_bootstrap_storage SET used_bytes=67108864 WHERE bootstrap_id='a'")else sql(source,"ALTER TABLE a ADD COLUMN unexpected_column INT NULL")
              if(variant!="I24-capacity")awaitCondition("DDL capture pause"){status(child).fields("captureFailure")!=JsNull};release(child);awaitCondition("bootstrap pause"){phase(child)=="PAUSED"}
              if(variant=="I24-capacity"){mutation("b","other-table-after-spool-full");awaitCondition("capacity pause isolated"){equal("b")}}
          }
          val active=Option(recovered).getOrElse(child)
          if(!safePauseCase && !Set("I15","I18","I22","I23","I24")(scenario)){ready(active);names.foreach(t=>equal(t) shouldBe true)}
          if(Set("I15-schema","I15-L")(variant)){
            write(dir,"safe-start-refusal.json",JsObject("readerRestarted"->JsBoolean(false),"businessPublished"->JsBoolean(false),"L"->withConnection(state,schema)(query(_,"SELECT * FROM pekko_capture_log ORDER BY seq"))))
          }else save("final",active)
          if(!Set("I15-schema","I15-L")(variant)){active.send("STOP");active.exit() shouldBe 0}
          awaitCondition("source and state transaction release"){
            withConnection(source,schema)(query(_,s"SELECT ID FROM information_schema.processlist WHERE DB='$schema'")).elements.size==1 &&
              withConnection(state,schema)(query(_,s"SELECT ID FROM information_schema.processlist WHERE DB='$schema'")).elements.size==1}
          write(dir,"release.json",JsObject("workerExited"->JsBoolean(true),"sourceAndStateSessionsReleased"->JsBoolean(true)))
        }catch{case e:Throwable=>write(dir,"failure.json",JsObject("error"->JsString(e.toString)));throw e}
        finally {if(transaction!=null){transaction.rollback();transaction.close()};if(blocker!=null){blocker.rollback();blocker.close()};if(recovered!=null)recovered.kill();if(child!=null)child.kill()}
      }
      }
    }
  }
}
