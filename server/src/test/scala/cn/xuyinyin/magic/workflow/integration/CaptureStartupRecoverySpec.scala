package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.connectors.mysql.source.{MySQLCdcSourceNode,MySQLCdcStateConfig}
import cn.xuyinyin.magic.connectors.mysql.source.capture.MySQLCaptureStore
import cn.xuyinyin.magic.tags.ExternalIntegration
import java.io.File
import java.net.URLClassLoader
import java.nio.file.{Files,Path}
import java.sql.DriverManager
import java.util.UUID
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.duration._
import spray.json._

/** Deliberately red against the unmodified startup protocol. No synthesized records/offsets.
  * Each attempt retains its failure and complete source/target fields; reruns use new paths.
  */
class CaptureStartupRecoverySpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._
  "capture startup recovery on an exclusive disposable slot" should {
    for(variant<-Vector("R02-original-trigger","R02-before-point","B-bound-D-initial")) {
      s"preserve startup continuity for $variant" taggedAs ExternalIntegration in {
        if(!sys.env.get("RUN_CAPTURE_STARTUP_RECOVERY").contains("1"))cancel("exclusive dual MySQL lease required")
        val source=Settings.environment();val state=source.copy(port=sys.env("CAPTURE_STATE_PORT").toInt)
        require(source.port!=state.port && !Set(3306,13365,13366).contains(source.port) && !Set(3306,13365,13366).contains(state.port),"old/shared database ports forbidden")
        require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").exists(_.nonEmpty),"explicit exclusive lease required")
        val suffix=UUID.randomUUID().toString.replace("-","").take(12)
        val schema="pekko_test_cdc_window_"+suffix;val captureId="startup_"+suffix
        val root=Path.of(sys.env.getOrElse("CAPTURE_STARTUP_EVIDENCE","docs/experiments/2026-10-25-capture-startup"))
        val dir=root.resolve(variant+"-"+suffix).toAbsolutePath;Files.createDirectories(dir)
        initialize(source,schema);initialize(state,schema)
        def sql(s:Settings,q:String):Unit=withConnection(s,schema)(executeSql(_,q))
        val names=Vector("a","b","c","d","e","f","g")
        names.foreach {t=>
          sql(source,s"CREATE TABLE $t LIKE source_rows");sql(source,s"INSERT INTO $t SELECT * FROM source_rows WHERE id<=4")
          sql(state,s"CREATE TABLE target_$t LIKE source_rows");sql(state,s"INSERT INTO target_$t SELECT * FROM source_rows WHERE id<=4")
        }
        withConnection(state,schema){c=>
          val input=getClass.getClassLoader.getResourceAsStream("db/mysql/pekko-sync-cdc-group-ledger-schema.sql")
          val ddl=try scala.io.Source.fromInputStream(input).mkString finally input.close()
          ddl.split(";").map(_.trim).filter(_.nonEmpty).foreach(executeSql(c,_))
        }
        val lineage=withConnection(source,schema)(query(_,"SELECT @@server_uuid AS uuid"))
        val stateLineage=withConnection(state,schema)(query(_,"SELECT @@server_uuid AS uuid"))
        lineage should not be stateLineage
        val store=new MySQLCaptureStore(()=>DriverManager.getConnection(state.url(schema),state.user,state.password),captureId,lineage.elements.head.asJsObject.fields("uuid").convertTo[String],64L*1024*1024)
        val offsetTable=MySQLCdcSourceNode.connectorStateTables(MySQLCdcStateConfig(state.url(schema),state.user,state.password,"capture_offsets","capture_history",0),captureId)._1
        def offsets:JsArray=withConnection(state,schema)(query(_,s"SELECT offset_key,offset_val FROM `$offsetTable`"))
        def initial: Boolean=offsets.elements.exists(_.asJsObject.fields("offset_val").convertTo[String].parseJson.asJsObject.fields.get("snapshot").contains(JsString("INITIAL")))
        def start(label:String,gate:String,size:Int=128):Child={
          val loaders=Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(x=>Option(x.getParent))).takeWhile(_.nonEmpty).flatten.collect {case l:URLClassLoader=>l.getURLs.iterator}.flatten.filter(_.getProtocol=="file").map(x=>Path.of(x.toURI).toString)
          val cp=(loaders++System.getProperty("java.class.path","").split(File.pathSeparator).iterator).filter(_.nonEmpty).toVector.distinct.mkString(File.pathSeparator)
          val p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx512m","-Duser.timezone=UTC","-cp",cp,CaptureStartupRecoveryWorker.getClass.getName.stripSuffix("$"),schema,captureId,size.toString,gate)
          p.redirectErrorStream(true)
          new Child(p.start(),dir.resolve(label+".log"))
        }
        def comparison(t:String):JsObject={
          val left=withConnection(source,schema)(query(_,s"SELECT * FROM $t ORDER BY id"))
          val right=withConnection(state,schema)(query(_,s"SELECT * FROM target_$t ORDER BY id"))
          val l=left.elements.map(_.asJsObject).map(r=>r.fields("id")->r).toMap
          val r=right.elements.map(_.asJsObject).map(r=>r.fields("id")->r).toMap
          val differences=(l.keySet intersect r.keySet).toVector.flatMap(k=>l(k).fields.toVector.collect {case(f,v) if r(k).fields.get(f)!=Some(v)=>JsObject("id"->k,"field"->JsString(f),"source"->v,"target"->r(k).fields.getOrElse(f,JsNull))})
          JsObject("source"->left,"target"->right,"equal"->JsBoolean(left==right),"comparedFields"->JsArray(Vector("id","label","amount","note","updated_at","revision").map(JsString(_))),"differences"->JsArray(differences),"missing"->JsArray((l.keySet--r.keySet).toVector),"extra"->JsArray((r.keySet--l.keySet).toVector))
        }
        def mutation(id:Int,label:String):Unit=sql(source,s"UPDATE a SET label='$label',revision=revision+1,amount=amount+0.1250,note='',updated_at='2026-10-25 01:02:03.123456' WHERE id=$id")
        def applyActor(c:Child,recover:Boolean):Unit={c.send(s"${if(recover)"RECOVER_APPLY" else "APPLY"} a a");c.awaitEvent("APPLY_READY")}
        var child:Child=null;var recovered:Child=null
        def save(label:String,c:Child):Unit=write(dir,label+".json",JsObject("case"->JsString(variant),"worker"->c.state(),"sourceStatus"->withConnection(source,schema)(query(_,"SHOW BINARY LOG STATUS")),"controls"->withConnection(source,schema)(query(_,"SELECT * FROM pekko_capture_control ORDER BY operation_id")),"comparisons"->JsObject(names.map(t=>t->comparison(t)).toMap),"T"->withConnection(state,schema)(query(_,"SELECT * FROM pekko_sync_cdc_group_ledger ORDER BY group_id"))))
        def crash(c:Child):Unit={c.send("CRASH");c.exit() shouldBe 86}
        def seen(c:Child,kind:String):JsObject={awaitCondition(kind,20.seconds){c.events.exists(_.fields("kind")==JsString(kind))};c.events.find(_.fields("kind")==JsString(kind)).get}
        write(dir,"environment.json",JsObject("case"->JsString(variant),"schema"->JsString(schema),"captureId"->JsString(captureId),"sourcePort"->JsNumber(source.port),"statePort"->JsNumber(state.port),"sourceUuid"->lineage,"stateUuid"->stateLineage,"lease"->JsString(sys.env("CDC_LAB_EXCLUSIVE_LEASE")),"snapshotMode"->JsString("no_data"),"snapshotLockingMode"->JsString("none"),"sdkTransport"->JsString(if(variant=="R02-original-trigger")"original direct SDK TLS configuration" else "test loopback SQL proxy with database.ssl.mode=disabled"),"fixture"->JsString("known copied baseline before B; no online initialization claim")))
        try {
          child=start("original",if(variant=="R02-original-trigger")"ORIGINAL" else if(variant=="B-bound-D-initial")"SCHEMA_AND_CALLBACK" else "SCHEMA_EMITTED",if(variant=="B-bound-D-initial")128 else 1);child.awaitEvent("PROCESS_READY")
          if(variant!="R02-original-trigger")seen(child,"SDK_CUTPOINT").fields("window") shouldBe JsString("SCHEMA_EMITTED")
          awaitCondition("Reader Schema"){names.forall(t=>store.schema(t).nonEmpty)}
          if(variant.startsWith("R02")) {
            child.send("PREPARE a a g1");child.awaitEvent("PREPARED")
            if(variant=="R02-before-point")awaitCondition("actual INITIAL D",20.seconds){initial}
            save("before-marker",child);crash(child)
            recovered=start("recovered",if(variant=="R02-before-point")"BEFORE_POINT" else "ORIGINAL");recovered.awaitEvent("PROCESS_READY")
            if(variant=="R02-before-point")seen(recovered,"SDK_CUTPOINT").fields("window") shouldBe JsString("BEFORE_POINT")
            recovered.send("SUBSCRIBE a a g1");recovered.awaitEvent("SUBSCRIBE_ACCEPTED");seen(recovered,"SOURCE_CONTROL_COMMIT_RETURNED")
            save("B-committed-before-SDK-point",recovered)
            if(variant=="R02-before-point") {recovered.send("RELEASE");recovered.awaitEvent("GATE_RELEASED");seen(recovered,"SDK_POINT_REPLY_FRAME")}
            try awaitCondition("B a",20.seconds){store.subscription("a").exists(_.b.nonEmpty)}
            finally save("after-startup",recovered)
            applyActor(recovered,false);mutation(1,"after-R02");awaitCondition("R02 full fields equal"){comparison("a").fields("equal")==JsBoolean(true)}
          } else {
            awaitCondition("actual INITIAL D",20.seconds){initial}
            seen(child,"SDK_CALLBACK_HELD")
            child.send("ARM_ACK B_BOUND");child.awaitEvent("ARMED")
            child.send("RELEASE_SDK");child.awaitEvent("SDK_GATE_RELEASED")
            child.send("SUBSCRIBE a a g1");child.awaitEvent("SUBSCRIBE_ACCEPTED")
            seen(child,"SDK_B_ROW_ENQUEUED")
            child.send("RELEASE_CALLBACK");child.awaitEvent("CALLBACK_GATE_RELEASED")
            seen(child,"CUTPOINT").fields("window") shouldBe JsString("B_COMMITTED_ACK_PENDING")
            store.subscription("a").get.b should not be empty
            initial shouldBe true
            applyActor(child,false);mutation(1,"committed-between-B-and-restart")
            save("bound-B-with-INITIAL-D",child);crash(child)
            recovered=start("recovered","NONE");recovered.awaitEvent("PROCESS_READY");applyActor(recovered,true)
            // A real SDK schema callback proves the restart chose its snapshot point before this witness write.
            seen(recovered,"SDK_POINT_REPLY_FRAME")
            awaitCondition("restart schema raw acknowledged",20.seconds){recovered.events.exists(_.fields("kind")==JsString("RAW_ACK_RETURNED"))}
            mutation(2,"post-restart-witness")
            awaitCondition("post-restart witness reaches Sink",30.seconds){
              val c=comparison("a");val rows=c.fields("target").asInstanceOf[JsArray].elements.map(_.asJsObject)
              rows.exists(r=>r.fields("id")==JsString("2") && r.fields("label")==JsString("post-restart-witness"))
            }
            awaitCondition("witness J durably covers captured group",20.seconds){
              val last=store.read("a",0,1000).lastOption.map(_.sequence)
              val stateValue=recovered.state().fields("actors").asJsObject.fields("a").asJsObject
              stateValue.fields("checkpoints").asInstanceOf[JsArray].elements.exists(cp=>last.contains(cp.asJsObject.fields("cursor").asJsObject.fields("sequence").convertTo[Long]))
            }
            save("after-witness-T-J",recovered)
            comparison("a").fields("equal") shouldBe JsBoolean(true)
          }
          save("final",recovered)
          write(dir,"outcome.json",JsObject("status"->JsString("passed")))
        }catch {case e:Throwable=>
          write(dir,"outcome.json",JsObject("status"->JsString("failed_and_preserved"),"error"->JsString(e.toString),"L"->store.evidence,"D"->(try offsets catch{case failure:Throwable=>JsObject("evidenceError"->JsString(failure.toString))}),"comparisons"->JsObject(names.map(t=>t->comparison(t)).toMap)))
          throw e
        }finally {
          if(child!=null)child.kill();if(recovered!=null)recovered.kill()
          // The unique schema and raw evidence remain for audit. No database or binlog cleanup here.
        }
      }
    }
  }
}
