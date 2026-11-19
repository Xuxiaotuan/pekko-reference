package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.connectors.mysql.source.capture.MySQLCaptureStore
import cn.xuyinyin.magic.cdc.MySQLCdcGroup
import cn.xuyinyin.magic.tags.ExternalIntegration
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import spray.json._
import java.nio.file.{Files,Path}
import java.sql.{Connection,DriverManager}
import java.net.URLClassLoader
import java.io.File
import java.util.UUID
import scala.concurrent.duration._

/** Source and infrastructure use distinct disposable loopback servers. No online snapshot fixture. */
class SharedCaptureMatrixSpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._
  "shared capture with real MySQL, Journal and Sink" should {
    for(variant<-Vector("R01","R01-concurrent","R02","R03","R03-late-commit","R03-late-rollback","R03-close-unknown","R04","R05","R06","R07","R07-d-before","R07-d-after","R08","R09","R10","R10-mid","R10-ack-d","R11","R11-rollback","R12","R12-ddl","R12-drop","R12-truncate","R13","R13-reject","R14","R14-capacity","R15","R15-late-bind","R15-bound-cancel","R15-unknown-cancel","R15-unknown-cancel-rollback","R16","R16-history","R16-log","R16-lineage","R16-source","R16-registry","R16-control-row","R16-ack-chain","R16-ack-ref","R16-namespace","R16-binlog")) {
      val scenario=variant.take(3)
      s"retain independent capture/application evidence for $variant" taggedAs ExternalIntegration in {
        if(!sys.env.get("RUN_SHARED_CAPTURE_MATRIX").contains("1"))cancel("disposable dual MySQL required")
        val source=Settings.environment();val state=source.copy(port=sys.env("CAPTURE_STATE_PORT").toInt)
        require(source.port!=state.port)
        val suffix=UUID.randomUUID().toString.replace("-","").take(12)
        val schema="pekko_test_cdc_window_"+suffix;val captureId="capture_"+suffix
        val dir=Path.of("target/shared-capture-matrix",variant+"-"+suffix).toAbsolutePath;Files.createDirectories(dir)
        initialize(source,schema);initialize(state,schema)
        def sql(s:Settings,q:String):Unit=withConnection(s,schema)(executeSql(_,q))
        val names=Vector("a","b","c","d","e","f","g")
        names.foreach {t=>
          sql(source,s"CREATE TABLE $t LIKE source_rows");sql(source,s"INSERT INTO $t SELECT * FROM source_rows WHERE id<=4")
          sql(state,s"CREATE TABLE target_$t LIKE source_rows");sql(state,s"INSERT INTO target_$t SELECT * FROM source_rows WHERE id<=4")
        }
        withConnection(state,schema){c=>val input=getClass.getClassLoader.getResourceAsStream("db/mysql/pekko-sync-cdc-group-ledger-schema.sql")
          val ddl=try scala.io.Source.fromInputStream(input).mkString finally input.close()
          ddl.split(";").map(_.trim).filter(_.nonEmpty).foreach(executeSql(c,_))}
        val lineage=withConnection(source,schema)(query(_,"SELECT @@server_uuid AS uuid")).elements.head.asJsObject.fields("uuid").convertTo[String]
        val store=new MySQLCaptureStore(()=>DriverManager.getConnection(state.url(schema),state.user,state.password),captureId,lineage,64L*1024*1024)
        def start(size:Int,label:String):Child={
          val loaders=Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(x=>Option(x.getParent))).takeWhile(_.nonEmpty).flatten.collect {case l:URLClassLoader=>l.getURLs.iterator}.flatten.filter(_.getProtocol=="file").map(x=>Path.of(x.toURI).toString)
          val cp=(loaders++System.getProperty("java.class.path","").split(File.pathSeparator).iterator).filter(_.nonEmpty).toVector.distinct.mkString(File.pathSeparator)
          val p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx512m","-Duser.timezone=UTC","-cp",cp,SharedCaptureMatrixWorker.getClass.getName.stripSuffix("$"),schema,captureId,size.toString)
          p.redirectErrorStream(true);p.environment().put("CAPTURE_STATE_PORT",state.port.toString)
          if(variant=="R16-source" && label=="missing-D")p.environment().put("CDC_WINDOW_MYSQL_PORT",state.port.toString)
          if(variant=="R14-capacity")p.environment().put("CAPTURE_LOG_CAPACITY","131072")
          new Child(p.start(),dir.resolve(label+".log"))
        }
        def comparison(t:String):JsObject={
          val left=withConnection(source,schema)(query(_,s"SELECT * FROM $t ORDER BY id"))
          val right=withConnection(state,schema)(query(_,s"SELECT * FROM target_$t ORDER BY id"))
          val l=left.elements.map(_.asJsObject).map(r=>r.fields("id")->r).toMap
          val r=right.elements.map(_.asJsObject).map(r=>r.fields("id")->r).toMap
          val differences=(l.keySet intersect r.keySet).toVector.flatMap(k=>l(k).fields.toVector.collect {case(f,v) if r(k).fields.get(f)!=Some(v)=>JsObject("id"->k,"field"->JsString(f),"source"->v,"target"->r(k).fields.getOrElse(f,JsNull))})
          JsObject("source"->left,"target"->right,"equal"->JsBoolean(left==right),"fieldsCompared"->JsNumber(l.values.map(_.fields.size).sum),"differences"->JsArray(differences),
            "missing"->JsArray((l.keySet--r.keySet).toVector),"extra"->JsArray((r.keySet--l.keySet).toVector))
        }
        def equal(t:String):Boolean=comparison(t).fields("equal")==JsBoolean(true)
        def mutation(t:String,label:String):Unit=sql(source,s"UPDATE $t SET label='$label',revision=revision+1,amount=amount+0.1250,note='',updated_at='2026-10-25 01:02:03.123456' WHERE id=1")
        val legacyStartupExpectations=sys.env.get("SHARED_LEGACY_STARTUP_EXPECTATIONS").contains("1")
        var startupOutcome:Option[String]=None
        var child:Child=null;var recovered:Child=null;var blocker:Connection=null
        def subscribe(c:Child,id:String,table:String):Unit={
          // Compatibility of the normal caller, not a change to the raw PREPARE/R02 trigger.
          // PROCESS_READY and Schema persistence are deliberately not admission signals.
          awaitCondition("current capture admission before new subscription",scala.concurrent.duration.Duration(90,"seconds")){
            c.send("CAPTURE_ADMISSION")
            val observed=c.awaitEvent("CAPTURE_ADMISSION")
            val phase=observed.fields("phase").convertTo[String]
            require(!Set("FAILED","PAUSED_UNPROVEN","STOPPING").contains(phase),"capture cannot admit: "+observed.compactPrint)
            phase=="ADMISSION_READY"
          }
          c.awaitEvent("CAPTURE_ADMISSION_READY")
          c.send(s"SUBSCRIBE $id $table g1");c.awaitEvent("SUBSCRIBE_ACCEPTED")
          awaitCondition("B "+id){store.subscription(id).exists(_.status=="CAPTURE_READY")}
        }
        def apply(c:Child,id:String,table:String,recover:Boolean=false):Unit={c.send(s"${if(recover)"RECOVER_APPLY" else "APPLY"} $id $table");c.awaitEvent("APPLY_READY")}
        def save(label:String,c:Child):Unit={
          if(label=="final" && variant!="R13-reject" && variant!="R14-capacity")awaitCondition("J caught up to captured groups"){
            val actors=c.state().fields("actors").asJsObject.fields
            actors.forall {case(id,value)=>
              val last=store.read(id,0,1000).lastOption.map(_.sequence).getOrElse(0L)
              val checkpoints=value.asJsObject.fields("checkpoints").asInstanceOf[JsArray].elements
              last==0L || checkpoints.exists(_.asJsObject.fields("cursor").asJsObject.fields("sequence")==JsNumber(last))
            }
          }
          write(dir,label+".json",JsObject("case"->JsString(scenario),"process"->JsNumber(c.pid),"worker"->c.state(),"comparisons"->JsObject(names.map(t=>t->comparison(t)).toMap),
            "T"->withConnection(state,schema)(query(_,"SELECT * FROM pekko_sync_cdc_group_ledger ORDER BY group_id")),
            "stateTransactions"->withConnection(state,schema)(query(_,"SELECT trx_mysql_thread_id,trx_state,trx_started,trx_query FROM information_schema.innodb_trx")),
            "sourceTransactions"->withConnection(source,schema)(query(_,"SELECT trx_mysql_thread_id,trx_state,trx_started,trx_query FROM information_schema.innodb_trx")),
            "stateConnections"->withConnection(state,schema)(query(_,s"SELECT ID,DB,COMMAND,STATE FROM information_schema.processlist WHERE DB='$schema'"))))
        }
        def crash(c:Child):Unit={c.send("CRASH");c.exit() shouldBe 86}
        def restart():Child={val r=start(128,"recovered");r.awaitEvent("PROCESS_READY");apply(r,"a","a",recover=true);r}
        // Only the fixture's real SQL facts classify the restart; a case name is not proof of COMMIT.
        def oldTransactions(s:Settings,stage:String):JsArray={
          val ids=child.events.filter(_.fields.get("batch").exists {
            case x:JsObject=>x.fields.get("stage").contains(JsString(stage));case _=>false
          }).flatMap(_.fields.get("connectionId").collect {case JsNumber(n)=>n.toLongExact}).distinct
          val known=if(ids.isEmpty)"" else " OR t.trx_mysql_thread_id IN ("+ids.mkString(",")+")"
          withConnection(s,schema)(query(_,s"SELECT t.trx_mysql_thread_id,t.trx_state,t.trx_started,t.trx_query FROM information_schema.innodb_trx t LEFT JOIN information_schema.processlist p ON p.ID=t.trx_mysql_thread_id WHERE (p.DB='$schema'$known) ORDER BY t.trx_mysql_thread_id"))
        }
        def durableSnapshot():JsObject={
          val hash=java.security.MessageDigest.getInstance("SHA-256").digest(captureId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).map(b=>f"${b&255}%02x").mkString.take(32)
          def stateRows(sql:String)=withConnection(state,schema)(query(_,sql))
          JsObject("L"->JsObject(
            "reader"->stateRows("SELECT * FROM pekko_capture_readers ORDER BY capture_id"),
            "subscriptions"->stateRows("SELECT * FROM pekko_capture_subscriptions ORDER BY capture_id,request_id"),
            "schemas"->stateRows("SELECT * FROM pekko_capture_schemas ORDER BY capture_id,table_name"),
            "fences"->stateRows("SELECT * FROM pekko_capture_fences ORDER BY capture_id,fence_id"),
            "log"->stateRows("SELECT * FROM pekko_capture_log ORDER BY capture_id,seq")),
            "D"->stateRows(s"SELECT offset_key,offset_val FROM `capture_offsets_$hash` ORDER BY offset_key"),
            "T"->stateRows("SELECT * FROM pekko_sync_cdc_group_ledger ORDER BY group_id"),
            "J"->stateRows("SELECT persistence_id,sequence_number,event_ser_id,event_ser_manifest,HEX(event_payload) AS payload_hex FROM event_journal ORDER BY ordering"),
            "sourceControls"->withConnection(source,schema)(query(_,"SELECT * FROM pekko_capture_control ORDER BY operation_id")),
            "comparisons"->JsObject(names.map(t=>t->comparison(t)).toMap))
        }
        def safePendingStartup(id:String):Boolean={
          awaitCondition("old fixture transactions ended before durable startup classification",scala.concurrent.duration.Duration(120,"seconds")){
            oldTransactions(source,"SOURCE_MARKER_COMMIT").elements.isEmpty && oldTransactions(state,"B_L_COMMIT").elements.isEmpty
          }
          write(dir,"before-startup-transactions.json",JsObject("source"->oldTransactions(source,"SOURCE_MARKER_COMMIT"),"state"->oldTransactions(state,"B_L_COMMIT")))
          val before=durableSnapshot();write(dir,"before-startup-independent-sql.json",before)
          val subscription=store.subscription(id).getOrElse(fail("expected persisted subscription intent"))
          if(subscription.status=="CAPTURE_READY" && subscription.b.nonEmpty){
            write(dir,"startup-classification.json",JsObject("classification"->JsString("BOUND_RETAIN_ORIGINAL_RECOVERY_ASSERTIONS"),"subscriptionId"->JsString(id),"B"->subscription.b.get.json.parseJson))
            false
          }else {
            subscription.status shouldBe "B_PENDING";subscription.b shouldBe empty
            val operation=store.markerId(id,stop=false)
            recovered=start(128,"unbound-marker-rejected")
            val failed=recovered.awaitEvent("START_FAILED")
            failed.fields("code") shouldBe JsString("UNBOUND_MARKER_UNPROVEN")
            failed.fields("engineCreations") shouldBe JsNumber(0)
            recovered.exit() should not be 0
            recovered.events.exists(_.fields("kind")==JsString("PROCESS_READY")) shouldBe false
            val writes=recovered.events.filter(_.fields("kind")==JsString("SOURCE_CONTROL_ATTEMPT"))
            writes shouldBe empty
            val after=durableSnapshot();write(dir,"after-rejection-independent-sql.json",after)
            after shouldBe before
            startupOutcome=Some("SAFE_PAUSED")
            write(dir,"startup-acceptance.json",JsObject("status"->JsString("SAFE_PAUSED"),"converged"->JsBoolean(false),"initializationCompleted"->JsBoolean(false),
              "reason"->JsString("UNBOUND_MARKER_UNPROVEN"),"subscriptionId"->JsString(id),"originalOperationId"->JsString(operation),
              "engineCreations"->failed.fields("engineCreations"),"sourceControlAttempts"->JsNumber(writes.size),"independentSqlUnchanged"->JsBoolean(true),"failedStartup"->failed))
            true
          }
        }
        write(dir,"environment.json",JsObject("case"->JsString(scenario),"schema"->JsString(schema),"captureId"->JsString(captureId),"sourceLineage"->JsString(lineage),
          "fixtureBaseline"->JsString("known copied baseline before B; not LOW/HIGH online initialization"),"sourceVersion"->withConnection(source,schema)(query(_,"SELECT VERSION() AS version,@@binlog_format AS format,@@binlog_row_image AS row_image"))))
        try {
          child=start(1,"original");child.awaitEvent("PROCESS_READY")
          awaitCondition("Reader Schema"){names.forall(t=>store.schema(t).nonEmpty)}
          if(scenario=="R02") {
            child.send("PREPARE a a g1");child.awaitEvent("PREPARED");save("before-marker",child);crash(child)
            if(legacyStartupExpectations){
              recovered=start(128,"recovered");recovered.awaitEvent("PROCESS_READY");subscribe(recovered,"a","a");apply(recovered,"a","a");mutation("a","after-R02");awaitCondition("R02 equal"){equal("a")};save("final",recovered)
            }else safePendingStartup("a") shouldBe true
          } else {
            subscribe(child,"a","a");apply(child,"a","a");mutation("a","baseline");awaitCondition("baseline T/J"){equal("a")};save("baseline",child)
            scenario match {
              case "R01"=>
                if(variant=="R01-concurrent"){
                  child.send("ARM_SUBSCRIBE");child.awaitEvent("ARMED")
                  child.send("SUBSCRIBE b b g1");child.awaitEvent("CUTPOINT").fields("window") shouldBe JsString("SUBSCRIBE_ADMITTED_HELD")
                  child.send("SUBSCRIBE b b g1");child.send("SUBSCRIBE c c g1")
                  awaitCondition("overlapping subscribe commands dispatched",30.seconds){
                    val events=child.events.filter(_.fields.get("kind").contains(JsString("SUBSCRIBE_DISPATCHED")))
                    events.count(_.fields.get("id").contains(JsString("b")))==2 && events.exists(_.fields.get("id").contains(JsString("c")))
                  }
                  write(dir,"concurrent-admission-held.json",JsObject("events"->JsArray(child.events),"L"->store.evidence))
                  child.send("RELEASE");child.awaitEvent("GATE_RELEASED")
                  awaitCondition("concurrent original command replies",45.seconds){
                    val events=child.events.filter(_.fields.get("kind").contains(JsString("SUBSCRIBE_ACCEPTED")))
                    events.count(_.fields.get("id").contains(JsString("b")))==2 && events.exists(_.fields.get("id").contains(JsString("c")))
                  }
                  awaitCondition("both independent B boundaries",30.seconds){Vector("b","c").forall(t=>store.subscription(t).exists(_.b.nonEmpty))}
                  val original=store.subscription("b").get.b
                  child.send("SUBSCRIBE b d g1")
                  awaitCondition("conflicting identity rejected",30.seconds){child.events.exists(e=>e.fields.get("kind").contains(JsString("COMMAND_FAILED")) && e.fields.get("command").contains(JsString("SUBSCRIBE")))}
                  store.subscription("b").get.b shouldBe original
                  val controls=withConnection(source,schema)(query(_,"SELECT * FROM pekko_capture_control ORDER BY operation_id"))
                  Vector("b","c").foreach {id=>controls.elements.count(_.asJsObject.fields("operation_id")==JsString(store.markerId(id,false))) shouldBe 1}
                  Vector("b","c").foreach {t=>apply(child,t,t);mutation(t,"concurrent-subscribe")}
                  mutation("a","concurrent-other-table")
                  awaitCondition("three subscribed tables equal",30.seconds){Vector("a","b","c").forall(equal)}
                  child.state().fields("engineCreations") shouldBe JsNumber(1)
                  write(dir,"concurrent-controls.json",JsObject("controls"->controls,"events"->JsArray(child.events),"L"->store.evidence))
                  save("final",child)
                }else {
                  Vector("b","c","d","e").foreach {t=>subscribe(child,t,t);apply(child,t,t);mutation(t,"before-add")}
                  Vector("f","g").foreach {t=>subscribe(child,t,t);store.subscription(t).get.initialized shouldBe false;apply(child,t,t);mutation(t,"new-table")}
                  mutation("a","after-add");awaitCondition("seven tables equal"){names.forall(equal)};save("final",child)
                }
              case "R08"=>
                withConnection(source,schema){c=>c.setAutoCommit(false);executeSql(c,"UPDATE a SET revision=revision+1,amount=amount+1 WHERE id>=2");executeSql(c,"UPDATE a SET note=NULL WHERE id=2");executeSql(c,"UPDATE a SET label='same-transaction-again' WHERE id=2");c.commit()}
                sql(source,"UPDATE a SET id=101 WHERE id=1");child.awaitEvent("DELETE_CALLBACK_RETURNED")
                awaitCondition("PK pair applied"){equal("a")};save("final",child)
              case "R03"|"R04"|"R05"|"R11"=>
                if(variant=="R03-close-unknown"){
                  child.send("ARM_CONTROL LATE_COMMIT");child.awaitEvent("ARMED")
                  child.send("SUBSCRIBE f f g1");child.awaitEvent("CUTPOINT")
                  awaitCondition("source transaction in flight",30.seconds)(oldTransactions(source,"SOURCE_MARKER_COMMIT").elements.nonEmpty)
                  awaitCondition("source COMMIT unknown",30.seconds)(child.events.exists(e=>e.fields.get("kind").contains(JsString("COMMAND_FAILED")) &&
                    e.fields.get("failureStage").contains(JsString("source-marker:MARKER_COMMIT_UNKNOWN"))))
                  val before=durableSnapshot();child.send("REQUEST_CLOSE");val close=child.awaitEvent("CLOSE_EVIDENCE")
                  val held=oldTransactions(source,"SOURCE_MARKER_COMMIT");val after=durableSnapshot()
                  val owner=withConnection(state,schema)(query(_,s"SELECT IS_USED_LOCK('pekko_capture_$captureId') AS connection_id"))
                  write(dir,"close-source-commit-unknown.json",JsObject("before"->before,"after"->after,"close"->close,
                    "heldTransactions"->held,"ownerLock"->owner,"events"->JsArray(child.events)))
                  held.elements should not be empty;close.fields("reusable") shouldBe JsBoolean(false);close.fields("ownerReleased") shouldBe JsBoolean(false)
                  after.fields("T") shouldBe before.fields("T");after.fields("J") shouldBe before.fields("J")
                  child.send("RESOLVE_TX");child.awaitEvent("TX_RESOLVE_REQUESTED")
                  awaitCondition("source transaction resolves",30.seconds)(oldTransactions(source,"SOURCE_MARKER_COMMIT").elements.isEmpty)
                  write(dir,"close-source-commit-resolved.json",JsObject("state"->durableSnapshot(),"events"->JsArray(child.events)))
                  crash(child);safePendingStartup("f") shouldBe true
                }else {
                val mode=if(scenario=="R04")"C0"else if(variant.endsWith("rollback"))"LATE_ROLLBACK"else if(scenario=="R11" || variant=="R03-late-commit")"LATE_COMMIT"else "C1"
                child.send(s"${if(scenario=="R03")"ARM_CONTROL"else "ARM_L"} $mode");child.awaitEvent("ARMED")
                child.send("SUBSCRIBE f f g1");child.awaitEvent("CUTPOINT")
                save("before-resolution",child)
                if(mode.startsWith("LATE")){child.send("RESOLVE_TX");child.awaitEvent("TX_RESOLVE_REQUESTED")}else {child.send("DROP_RESPONSE");child.awaitEvent("RESPONSE_RELEASED")}
                if(scenario=="R03")awaitCondition("lost source COMMIT reply"){child.events.exists(e=>e.fields("kind")==JsString("COMMAND_FAILED") && e.fields.get("command").contains(JsString("SUBSCRIBE")))}
                else awaitCondition("capture stopped on uncertain L"){child.state().fields("captureFailure")!=JsNull}
                save("cut",child);crash(child)
                if(legacyStartupExpectations || !safePendingStartup("f")){
                  recovered=restart();subscribe(recovered,"f","f");apply(recovered,"f","f");mutation("a","recovered");mutation("f","recovered-F")
                  awaitCondition("recovered A/F"){equal("a")&&equal("f")};save("final",recovered)
                }
                }
              case "R06"|"R07"=>
                if(variant=="R07-d-after"){
                  // Construct an imported historical inconsistency deliberately: the source control row
                  // arrives before its registry intent. The current subscribe path never orders it this way.
                  val operation=store.markerId("f",false)
                  sql(source,s"INSERT INTO pekko_capture_control VALUES('$operation','SUBSCRIBE','f','g1')")
                  awaitCondition("historical B record delivered",30.seconds){child.events.exists(e=>
                    e.fields.get("kind").contains(JsString("ORIGINAL_RECORD")) && e.fields.get("key").exists {
                      case k:JsObject=>k.fields.get("operation_id").contains(JsString(operation));case _=>false})}
                  val marker=child.events.find(e=>e.fields.get("kind").contains(JsString("ORIGINAL_RECORD")) && e.fields.get("key").exists {
                    case k:JsObject=>k.fields.get("operation_id").contains(JsString(operation));case _=>false}).get
                  val markerOffset=marker.fields("offset").asJsObject.fields("offset").asJsObject.fields
                  def number(f:Map[String,JsValue],key:String):BigDecimal=f.get(key).collect {case JsNumber(n)=>n}.getOrElse(BigDecimal(0))
                  val hash=java.security.MessageDigest.getInstance("SHA-256").digest(captureId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).map(b=>f"${b&255}%02x").mkString.take(32)
                  def actualD=withConnection(state,schema)(query(_,s"SELECT offset_key,offset_val FROM capture_offsets_$hash"))
                  awaitCondition("actual covered D beyond historical B",60.seconds){
                    val rows=actualD.elements
                    rows.size==1 && {val value=rows.head.asJsObject.fields("offset_val").convertTo[String]
                      scala.util.Try {val d=value.parseJson.asJsObject.fields
                        d("file")==markerOffset("file") &&
                          ((number(d,"pos")>number(markerOffset,"pos")) ||
                            (number(d,"pos")==number(markerOffset,"pos") && number(d,"event")>number(markerOffset,"event")) ||
                            (number(d,"pos")==number(markerOffset,"pos") && number(d,"event")==number(markerOffset,"event") && number(d,"row")>number(markerOffset,"row"))) &&
                          {store.verifyOffset(value);true}}.getOrElse(false)}
                  }
                  store.register("f","f","g1",store.schema("f").get.id)
                  store.subscription("f").get.status shouldBe "B_PENDING"
                  store.subscription("f").get.b shouldBe None
                  store.barrierSequence("f",false) shouldBe None
                  write(dir,"d-after-unbound-B.json",JsObject("actualD"->actualD,"markerEvent"->marker,
                    "construction"->JsString("test inserts historical source control before registering intent"),"state"->durableSnapshot()))
                  save("cut",child);crash(child)
                  safePendingStartup("f") shouldBe true
                }else {
                var heldD:Option[JsArray]=None
                if(variant=="R07-d-before"){
                  val hash=java.security.MessageDigest.getInstance("SHA-256").digest(captureId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).map(b=>f"${b&255}%02x").mkString.take(32)
                  blocker=DriverManager.getConnection(state.url(schema),state.user,state.password);blocker.setAutoCommit(false)
                  val locked=query(blocker,s"SELECT offset_key,offset_val FROM capture_offsets_$hash FOR UPDATE")
                  locked.elements.size shouldBe 1
                  locked.elements.head.asJsObject.fields("offset_val").convertTo[String] should not include "INITIAL"
                  heldD=Some(locked)
                }
                child.send("ARM_ACK "+(if(scenario=="R06")"B_BOUND"else "MARKER_RETURNED"));child.awaitEvent("ARMED");child.send("SUBSCRIBE f f g1");child.awaitEvent("CUTPOINT")
                if(variant=="R07-d-before"){
                  val operation=store.markerId("f",false)
                  val marker=child.events.find(e=>e.fields.get("kind").contains(JsString("ORIGINAL_RECORD")) &&
                    e.fields.get("key").exists {case k:JsObject=>k.fields.get("operation_id").contains(JsString(operation));case _=>false}).getOrElse(fail("actual B row event missing"))
                  val markerSource=marker.fields("data").asJsObject.fields("source").asJsObject.fields
                  val actualD=heldD.get.elements.head.asJsObject.fields("offset_val").convertTo[String].parseJson.asJsObject.fields
                  val dFile=actualD("file").convertTo[String];val bFile=markerSource("file").convertTo[String]
                  dFile shouldBe bFile
                  actualD("pos").asInstanceOf[JsNumber].value should be < markerSource("pos").asInstanceOf[JsNumber].value
                  store.subscription("f").get.status shouldBe "B_PENDING"
                  val controls=withConnection(source,schema)(query(_,s"SELECT * FROM pekko_capture_control WHERE operation_id='$operation'"))
                  controls.elements.size shouldBe 1
                  write(dir,"d-before-unbound-B.json",JsObject("actualD"->heldD.get,"markerEvent"->marker,"control"->controls))
                }
                save("cut",child);heldD.foreach(d=>child.state().fields("D") shouldBe d);crash(child)
                if(blocker!=null){blocker.rollback();blocker.close();blocker=null}
                // MARKER_RETURNED covers the row callback, not necessarily its later transaction END/B binding.
                // R06 remains a fixed B_BOUND recovery assertion; only R07 branches on the durable cut.
                if(scenario=="R06" || legacyStartupExpectations || !safePendingStartup("f")){
                  recovered=restart();subscribe(recovered,"f","f");apply(recovered,"f","f");mutation("f","after-marker-crash");awaitCondition("F recovered"){equal("f")};save("final",recovered)
                }
                }
              case "R09"=>
                val suffixHash=java.security.MessageDigest.getInstance("SHA-256").digest(captureId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).map(b=>f"${b&255}%02x").mkString.take(32)
                if(Set("R16-namespace","R16-ack-ref")(variant)) awaitCondition("completed durable D before injection",scala.concurrent.duration.Duration(60,"seconds")) {
                  val rows=withConnection(state,schema)(query(_,s"SELECT offset_key,offset_val FROM capture_offsets_$suffixHash"))
                  rows.elements.nonEmpty && rows.elements.head.asJsObject.fields("offset_val").convertTo[String].parseJson.asJsObject.fields.get("snapshot").forall(_==JsBoolean(false))
                }
                blocker=DriverManager.getConnection(state.url(schema),state.user,state.password);blocker.setAutoCommit(false)
                query(blocker,s"SELECT * FROM capture_offsets_$suffixHash FOR UPDATE")
                mutation("a","D-held");awaitCondition("L and T progress"){equal("a")};save("cut",child);crash(child)
                blocker.rollback();blocker.close();blocker=null;recovered=restart();mutation("a","after-rebatch");awaitCondition("rebatched equal"){equal("a")};save("final",recovered)
              case "R10"=>
                val originalB=store.subscription("a").get.b
                var heldD:Option[JsArray]=None
                if(variant=="R10-ack-d"){
                  val suffixHash=java.security.MessageDigest.getInstance("SHA-256").digest(captureId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).map(b=>f"${b&255}%02x").mkString.take(32)
                  blocker=DriverManager.getConnection(state.url(schema),state.user,state.password);blocker.setAutoCommit(false)
                  val locked=query(blocker,s"SELECT offset_key,offset_val FROM capture_offsets_$suffixHash FOR UPDATE")
                  locked.elements.size shouldBe 1
                  locked.elements.head.asJsObject.fields("offset_val").convertTo[String] should not include "INITIAL"
                  heldD=Some(locked)
                  write(dir,"D-lock-before-pk-update.json",JsObject("lockedActualD"->locked,"B"->store.subscription("a").get.b.get.json.parseJson))
                }
                child.send("ARM_ACK "+(if(variant=="R10-mid" || variant=="R10-ack-d")"MID_ACK"else "BETWEEN_ACK"));child.awaitEvent("ARMED");sql(source,"UPDATE a SET id=101 WHERE id=1");child.awaitEvent("CUTPOINT")
                save("cut",child)
                heldD.foreach(d=>child.state().fields("D") shouldBe d)
                crash(child)
                if(blocker!=null){blocker.rollback();blocker.close();blocker=null}
                recovered=restart();awaitCondition("ACK recovery"){equal("a")}
                store.subscription("a").get.b shouldBe originalB
                save("final",recovered)
              case "R12"=>
                child.send("SUBSCRIBE missing absent_table g1");child.awaitEvent("COMMAND_FAILED");mutation("a","schema-refusal-isolated");awaitCondition("A continues"){equal("a")};save("final",child)
                if(variant!="R12"){
                  sql(source,if(variant=="R12-ddl")"ALTER TABLE a MODIFY label VARCHAR(256) NOT NULL" else if(variant=="R12-drop")"DROP TABLE a"else "TRUNCATE TABLE a")
                  awaitCondition("DDL pauses capture"){child.state().fields("captureFailure")!=JsNull}
                  write(dir,"ddl-pause.json",JsObject("worker"->child.state(),"comparison"->(if(variant=="R12-drop")JsObject("sourceTableMissing"->JsBoolean(true),"target"->withConnection(state,schema)(query(_,"SELECT * FROM target_a ORDER BY id")))else comparison("a"))))
                }
              case "R13"=>
                child.send("ARM_APP "+(if(variant=="R13-reject")"REJECT_J"else "T_J"));child.awaitEvent("ARMED");mutation("a","T-before-J")
                if(variant=="R13-reject"){
                  awaitCondition("explicit rejection pauses"){child.state().fields("actors").asJsObject.fields("a").asJsObject.fields("recovery") match {case x:JsObject=>x.fields("phase")==JsString("blocked");case _=>false}}
                  save("final",child)
                }else {child.awaitEvent("CUTPOINT");save("cut",child);crash(child)
                  recovered=restart();awaitCondition("T/J replay equal"){equal("a")};mutation("a","after-TJ-recovery");awaitCondition("T/J resumed"){equal("a")};save("final",recovered)}
              case "R14"=>
                if(variant=="R14-capacity"){
                  (1 to 200).foreach(i=>mutation("a","capacity-"+i))
                  awaitCondition("capacity safely pauses"){child.state().fields("captureFailure")!=JsNull};save("final",child)
                }else {
                subscribe(child,"b","b");apply(child,"b","b");child.send("ARM_APP T_J");child.awaitEvent("ARMED");mutation("a","slow-A");child.awaitEvent("CUTPOINT")
                mutation("b","independent-B");awaitCondition("B not blocked by A"){equal("b")};save("cut",child);child.send("RELEASE");child.awaitEvent("GATE_RELEASED");awaitCondition("both equal"){equal("a")&&equal("b")};save("final",child)
                }
              case "R15"=>
                if(variant.startsWith("R15-unknown-cancel")){
                  val rollback=variant.endsWith("rollback")
                  child.send("ARM_CONTROL "+(if(rollback)"LATE_ROLLBACK"else"LATE_COMMIT"));child.awaitEvent("ARMED")
                  child.send("SUBSCRIBE f f g1");child.awaitEvent("CUTPOINT")
                  awaitCondition("real source transaction still open",30.seconds){
                    oldTransactions(source,"SOURCE_MARKER_COMMIT").elements.nonEmpty
                  }
                  awaitCondition("source commit classified unknown",30.seconds){child.events.exists(e=>
                    e.fields.get("kind").contains(JsString("COMMAND_FAILED")) &&
                      e.fields.get("failureCode").contains(JsString("local_release_unconfirmed")) &&
                      e.fields.get("failureStage").contains(JsString("source-marker:MARKER_COMMIT_UNKNOWN")) &&
                      e.fields.get("releaseConfirmed").contains(JsBoolean(false)))}
                  store.subscription("f").get.status shouldBe "B_PENDING"
                  val beforeCancel=durableSnapshot()
                  write(dir,"unknown-before-cancel.json",JsObject("state"->beforeCancel,
                    "sourceTransactions"->oldTransactions(source,"SOURCE_MARKER_COMMIT"),"events"->JsArray(child.events)))
                  child.send("CANCEL_PENDING f")
                  awaitCondition("cancel response",30.seconds){child.events.exists(e=>
                    e.fields.get("kind").contains(JsString("PENDING_CANCELED")) ||
                      (e.fields.get("kind").contains(JsString("COMMAND_FAILED")) && e.fields.get("command").contains(JsString("CANCEL_PENDING"))))}
                  val canceledWhileUnknown=store.subscription("f").exists(_.status=="CANCELED")
                  val stillOpen=oldTransactions(source,"SOURCE_MARKER_COMMIT")
                  val afterCancel=durableSnapshot()
                  write(dir,"unknown-after-cancel.json",JsObject("state"->afterCancel,"sourceTransactions"->stillOpen,
                    "canceledWhileUnknown"->JsBoolean(canceledWhileUnknown),"events"->JsArray(child.events)))
                  stillOpen.elements should not be empty
                  afterCancel.fields("T") shouldBe beforeCancel.fields("T")
                  afterCancel.fields("J") shouldBe beforeCancel.fields("J")
                  child.send("RESOLVE_TX");child.awaitEvent("TX_RESOLVE_REQUESTED")
                  awaitCondition("late source transaction outcome",30.seconds){child.events.exists(_.fields.get("kind").contains(JsString(if(rollback)"PROXY_LATE_ROLLBACK_DISCONNECTED"else"PROXY_LATE_COMMIT_OK")))}
                  awaitCondition("source transaction ended",30.seconds){oldTransactions(source,"SOURCE_MARKER_COMMIT").elements.isEmpty}
                  val afterResolution=durableSnapshot()
                  write(dir,"unknown-after-resolution.json",JsObject("state"->afterResolution,
                    "sourceTransactions"->oldTransactions(source,"SOURCE_MARKER_COMMIT"),"events"->JsArray(child.events),
                    "matrixExpected"->JsString("cancel must not complete while source commit is unknown"),
                    "matrixStatus"->JsString(if(canceledWhileUnknown)"FAILED" else "EXPECTED_BOUNDARY")))
                  afterResolution.fields("T") shouldBe beforeCancel.fields("T")
                  afterResolution.fields("J") shouldBe beforeCancel.fields("J")
                  withClue("A14: a pending cancellation must not complete while the source COMMIT remains unresolved") {
                    canceledWhileUnknown shouldBe false
                  }
                }else if(variant=="R15-late-bind"){
                  child.send("ARM_ACK MARKER_RETURNED");child.awaitEvent("ARMED")
                  child.send("SUBSCRIBE f f g1");child.awaitEvent("CUTPOINT")
                  store.subscription("f").get.status shouldBe "B_PENDING"
                  save("marker-returned-before-cancel",child)
                  child.send("CANCEL_PENDING f");child.awaitEvent("PENDING_CANCELED")
                  store.subscription("f").get.status shouldBe "CANCELED"
                  child.send("RELEASE");child.awaitEvent("GATE_RELEASED")
                  awaitCondition("late marker transaction end observed",30.seconds){child.events.exists(e=>
                    e.fields.get("kind").contains(JsString("MARKER_IGNORED_CANCELED")) && e.fields.get("requestId").contains(JsString("f")))}
                  store.subscription("f").get.status shouldBe "CANCELED"
                  child.events.exists(e=>e.fields.get("kind").contains(JsString("B_BOUND")) && e.fields.get("requestId").contains(JsString("f"))) shouldBe false
                  store.subscription("f").get.b shouldBe None
                  store.barrierSequence("f",false) shouldBe None
                  mutation("a","other-table-continues-after-cancel")
                  awaitCondition("other table remains live",30.seconds){equal("a")}
                  save("final",child)
                }else if(variant=="R15-bound-cancel"){
                  subscribe(child,"f","f")
                  val before=store.subscription("f").get.b
                  before should not be empty
                  child.send("CANCEL_PENDING f")
                  child.awaitEvent("COMMAND_FAILED").fields("command") shouldBe JsString("CANCEL_PENDING")
                  store.subscription("f").get.b shouldBe before
                  store.subscription("f").get.status shouldBe "CAPTURE_READY"
                  child.send("UNSUBSCRIBE f");child.awaitEvent("UNSUBSCRIBE_ACCEPTED")
                  awaitCondition("original generation K",30.seconds){store.subscription("f").exists(_.k.nonEmpty)}
                  store.subscription("f").get.b shouldBe before
                  mutation("a","other-table-continues-after-K")
                  awaitCondition("other table after K",30.seconds){equal("a")}
                  save("final",child)
                }else {
                  child.send("PREPARE f f g1");child.awaitEvent("PREPARED");child.send("CANCEL_PENDING f");child.awaitEvent("PENDING_CANCELED")
                  child.send("UNSUBSCRIBE a");child.awaitEvent("UNSUBSCRIBE_ACCEPTED");awaitCondition("capture K"){store.subscription("a").exists(_.status=="CAPTURE_STOPPED")}
                  val n=store.read("a",0,1000).size;mutation("a","unsubscribed");Thread.sleep(300);store.read("a",0,1000).size shouldBe n
                  child.send("SUBSCRIBE a2 a g2");child.awaitEvent("SUBSCRIBE_ACCEPTED");awaitCondition("new generation B"){store.subscription("a2").exists(_.b.nonEmpty)}
                  store.read("a2",0,1000) shouldBe empty;save("final",child)
                }
              case "R16"=>
                val second=start(1,"duplicate-owner");try {second.awaitEvent("START_FAILED");second.exit() should not be 0}finally second.kill()
                mutation("a","original-owner-continues");awaitCondition("A after duplicate"){equal("a")}
                val suffixHash=java.security.MessageDigest.getInstance("SHA-256").digest(captureId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).map(b=>f"${b&255}%02x").mkString.take(32)
                if(variant=="R16-binlog"){
                  blocker=DriverManager.getConnection(state.url(schema),state.user,state.password);blocker.setAutoCommit(false)
                  query(blocker,s"SELECT * FROM capture_offsets_$suffixHash FOR UPDATE")
                  mutation("a","needed-after-durable-D");awaitCondition("captured past held D"){equal("a")}
                }
                save("before-missing-D",child);crash(child)
                if(blocker!=null){blocker.rollback();blocker.close();blocker=null}
                if(variant=="R16-ack-ref"){
                  val all=withConnection(state,schema)(query(_,"SELECT * FROM pekko_capture_log WHERE kind='ACK' ORDER BY seq"))
                  val old=all.elements.map(_.asJsObject).find(_.fields("payload").convertTo[String].parseJson.asJsObject.fields("parent")!=JsNull).get
                  val fields=old.fields;val payload=fields("payload").convertTo[String].parseJson.asJsObject
                  val parent=payload.fields("parent").asJsObject
                  val changed=MySQLCdcGroup.canonical(JsObject(payload.fields.updated("parent",JsObject(parent.fields.updated("digest",JsString("0"*64))))) )
                  val hash=MySQLCdcGroup.hash(MySQLCdcGroup.canonical(JsArray(Vector("kind","table_name","subscription_id","schema_id").map(k=>fields(k)) ++ Vector(JsString(changed),JsString("")))))
                  withConnection(state,schema){c=>val q=c.prepareStatement("UPDATE pekko_capture_log SET payload=?,digest=? WHERE capture_id=? AND seq=?")
                    try {q.setString(1,changed);q.setString(2,hash);q.setString(3,captureId);q.setLong(4,fields("seq").convertTo[String].toLong);q.executeUpdate() shouldBe 1}finally q.close()}
                  write(dir,"ack-reference-injection.json",JsObject("before"->old,"after"->withConnection(state,schema)(query(_,s"SELECT * FROM pekko_capture_log WHERE capture_id='$captureId' AND seq=${fields("seq").convertTo[String]}"))))
                }else if(variant=="R16-namespace"){
                  val before=withConnection(state,schema)(query(_,s"SELECT offset_key,offset_val FROM capture_offsets_$suffixHash"))
                  val original=before.elements.head.asJsObject.fields("offset_val").convertTo[String].parseJson.asJsObject
                  val injected=JsObject(original.fields.updated("file",JsString("other-bin.000001"))).compactPrint
                  withConnection(state,schema){c=>val q=c.prepareStatement(s"UPDATE capture_offsets_$suffixHash SET offset_val=?")
                    try {q.setString(1,injected);q.executeUpdate() shouldBe 1}finally q.close()}
                  write(dir,"namespace-injection.json",JsObject("before"->before,"after"->withConnection(state,schema)(query(_,s"SELECT offset_key,offset_val FROM capture_offsets_$suffixHash"))))
                }else if(variant=="R16-control-row"){
                  val marker=store.markerId("a",false)
                  val before=withConnection(source,schema)(query(_,s"SELECT * FROM pekko_capture_control WHERE operation_id='$marker'"))
                  before.elements.size shouldBe 1
                  sql(source,s"DELETE FROM pekko_capture_control WHERE operation_id='$marker'")
                  val after=withConnection(source,schema)(query(_,s"SELECT * FROM pekko_capture_control WHERE operation_id='$marker'"))
                  after.elements shouldBe empty
                  write(dir,"control-row-injection.json",JsObject("before"->before,"after"->after))
                }else if(variant=="R16-ack-chain"){
                  val before=withConnection(state,schema)(query(_,s"SELECT * FROM pekko_capture_log WHERE capture_id='$captureId' AND kind='ACK' ORDER BY seq LIMIT 1"))
                  before.elements.size shouldBe 1
                  val seq=before.elements.head.asJsObject.fields("seq").convertTo[String]
                  require(seq.forall(_.isDigit))
                  sql(state,s"DELETE FROM pekko_capture_log WHERE capture_id='$captureId' AND seq=$seq")
                  val after=withConnection(state,schema)(query(_,s"SELECT * FROM pekko_capture_log WHERE capture_id='$captureId' AND seq=$seq"))
                  after.elements shouldBe empty
                  write(dir,"ack-chain-injection.json",JsObject("before"->before,"after"->after))
                }else if(variant=="R16-registry")sql(state,"DELETE FROM pekko_capture_subscriptions WHERE request_id='a'")
                else if(variant=="R16-history")sql(state,s"DELETE FROM capture_history_$suffixHash")
                else if(variant=="R16-log")sql(state,"DELETE FROM pekko_capture_log WHERE kind='GROUP'")
                else if(variant=="R16-lineage")sql(state,"UPDATE pekko_capture_readers SET lineage='wrong-lineage'")
                else if(variant!="R16-source" && variant!="R16-binlog")sql(state,s"DELETE FROM capture_offsets_$suffixHash")
                if(variant=="R16-binlog"){
                  sys.env.get("CAPTURE_SOURCE_PURGE_GATE") match {
                    case Some(gate) =>
                      val path=Path.of(gate)
                      write(path,"source-purge-request.json",JsObject("uuid"->JsString(lineage),"port"->JsNumber(source.port),"captureId"->JsString(captureId)))
                      awaitCondition("leased external archive and purge",scala.concurrent.duration.Duration(200,"seconds")){
                        Files.exists(path.resolve("source-purge-response.json"))
                      }
                      val proof=Files.readString(path.resolve("source-purge-response.json")).parseJson.asJsObject
                      proof.fields("purged") shouldBe JsBoolean(true)
                      write(dir,"binlog-archive.json",proof)
                    case None =>
                  require(source.port==13365,"binlog fault is restricted to the dedicated lab instance")
                  val base=Path.of("/tmp/pekko-shared-capture-20261025")
                  val archive=base.resolve("preserved-binlogs-"+suffix);Files.createDirectories(archive)
                  sql(source,"FLUSH BINARY LOGS")
                  val logs=withConnection(source,schema)(query(_,"SHOW BINARY LOGS")).elements.map(_.asJsObject.fields).map(f=>f.find(_._1.equalsIgnoreCase("Log_name")).get._2.convertTo[String]).toVector
                  logs.dropRight(1).foreach {name=>require(name.matches("mysql-bin\\.[0-9]+"));Files.copy(base.resolve(name),archive.resolve(name))}
                  Files.copy(base.resolve("mysql-bin.index"),archive.resolve("mysql-bin.index"))
                  write(dir,"binlog-archive.json",JsObject("privateArchive"->JsString(archive.toString),"files"->JsArray(logs.map(JsString(_)))))
                  sql(source,s"PURGE BINARY LOGS TO '${logs.last}'")
                  }
                }
                recovered=start(128,"missing-D")
                if(variant=="R16-binlog") {recovered.awaitEvent("PROCESS_READY");awaitCondition("missing required binlog pauses"){recovered.state().fields("captureFailure")!=JsNull};write(dir,"rejected-reader.json",recovered.state())}
                else {
                  val failure=recovered.awaitEvent("START_FAILED")
                  if(variant=="R16-control-row")failure.fields("error").convertTo[String] should include ("CONTROL_ROW_MISSING_OR_CHANGED")
                  if(Set("R16-namespace","R16-ack-ref")(variant))failure.fields("error").convertTo[String] should include ("CHECKPOINT_UNCOVERED")
                  write(dir,"startup-failure.json",failure)
                  recovered.exit() should not be 0
                }
                write(dir,"final.json",JsObject("safePause"->JsBoolean(true),"L"->store.evidence,"comparison"->comparison("a")))
            }
          }
          write(dir,"result.json",JsObject("scenario"->JsString(scenario),"variant"->JsString(variant),"status"->JsString("passed"),"startupOutcome"->JsString(startupOutcome.getOrElse("ORIGINAL_ASSERTIONS")),"legacyStartupExpectations"->JsBoolean(legacyStartupExpectations),"initializationImplemented"->JsBoolean(false)))
        }finally {
          if(blocker!=null){blocker.rollback();blocker.close()}
          Vector(recovered,child).filter(_!=null).foreach {c=>c.kill();require(!c.alive,"owned worker did not exit")}
          def connections(s:Settings):JsArray=withConnection(s,schema)(query(_,s"SELECT ID,DB,COMMAND,STATE FROM information_schema.processlist WHERE DB='$schema' AND ID<>CONNECTION_ID()"))
          awaitCondition("owned database sessions released"){connections(source).elements.isEmpty && connections(state).elements.isEmpty}
          write(dir,"cleanup.json",JsObject("workersExited"->JsBoolean(true),"sourceConnections"->connections(source),"stateConnections"->connections(state)))
        }
      }
    }
  }
}
