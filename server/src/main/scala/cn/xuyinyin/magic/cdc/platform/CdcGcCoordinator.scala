package cn.xuyinyin.magic.cdc.platform

import cn.xuyinyin.magic.cdc.bootstrap.{BootstrapJdbc,BootstrapProgress,BootstrapRunner}
import cn.xuyinyin.magic.connectors.mysql.source.MySQLDebeziumConnector
import cn.xuyinyin.magic.connectors.mysql.source.capture._
import java.sql.Connection
import java.util.UUID
import org.apache.pekko.actor.typed.ActorSystem
import scala.util.control.NonFatal
import spray.json._

/** One bounded maintenance unit per poll. Table audits use ordinary TAIL grants. */
private[cdc] final class CdcGcCoordinator(cfg:CdcCaptureSettings,store:MySQLCaptureStore,reader:SharedMySQLCapture,
  open:()=>Connection,writer:()=>Connection,system:ActorSystem[_],captures:Set[String],observe:(String,JsObject)=>Unit) {
  private val settings=cfg.gc.get
  private val jdbc=new BootstrapJdbc(open);import jdbc._
  private var permit:Option[CaptureGcNamespace.Permit]=None
  private var retention:CaptureGcRetention=null
  @volatile private var disabled:Option[String]=None
  private val capture=cfg.id
  private var nextWorkNanos=0L
  private val offset=MySQLDebeziumConnector.connectorStateTables(cfg.sdkState,capture)._1
  def initialize(owner:String,stop:()=>Boolean):Unit=try {
    new CaptureGcRetention(open,store,offset).initialize()
    connection {c=>
      Vector(
        "CREATE TABLE IF NOT EXISTS pekko_capture_gc_schedule_v1(capture_id VARCHAR(64) PRIMARY KEY,after_task VARCHAR(128) NOT NULL,next_at BIGINT NOT NULL,operation_id VARCHAR(128) NULL,phase VARCHAR(32) NOT NULL,reason VARCHAR(128) NULL)",
        "CREATE TABLE IF NOT EXISTS pekko_capture_gc_audits_v1(capture_id VARCHAR(64) NOT NULL,subscription_id VARCHAR(128) NOT NULL,required_floor BIGINT NOT NULL,phase VARCHAR(24) NOT NULL,proof_id CHAR(64) NULL,PRIMARY KEY(capture_id,subscription_id),INDEX pending_audits(capture_id,phase,subscription_id))"
      ).foreach(s=>update(c,s+" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin"))
      if(query(c,"SELECT 1 AS n FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='pekko_capture_gc_audits_v1' AND index_name='pending_audits' LIMIT 1").isEmpty)
        update(c,"ALTER TABLE pekko_capture_gc_audits_v1 ADD INDEX pending_audits(capture_id,phase,subscription_id)")
      // Installation is opt-in; existing conflicting triggers are never replaced.
      val ledger=getClass.getClassLoader.getResourceAsStream("db/mysql/pekko-sync-cdc-group-ledger-schema.sql")
      val ddl=try scala.io.Source.fromInputStream(ledger).mkString finally ledger.close()
      ddl.split(";").map(_.trim).filter(_.nonEmpty).foreach(update(c,_))
      Vector("update"->"UPDATE","delete"->"DELETE").foreach {case(name,event)=>
        if(query(c,"SELECT TRIGGER_NAME FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name=?","pekko_ledger_no_"+name).isEmpty)
          update(c,s"CREATE TRIGGER pekko_ledger_no_$name BEFORE $event ON pekko_sync_cdc_group_ledger FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ledger immutable'")
      }
      update(c,"INSERT IGNORE INTO pekko_capture_gc_schedule_v1 VALUES(?,'',0,NULL,'ENABLING',NULL)",capture)
    }
    permit=Some(CdcGcNamespace.enable(open,store,settings,captures,owner,system,stop,observe))
    retention=new CaptureGcRetention(writer,store,offset,permit)
    connection(c=>update(c,"UPDATE pekko_capture_gc_schedule_v1 SET phase='READY',reason=NULL WHERE capture_id=?",capture))
    observe("GC_NAMESPACE_READY",JsObject("captureId"->JsString(capture)))
  }catch {case NonFatal(e)=>
    disabled=Some(Option(e.getMessage).getOrElse(e.getClass.getSimpleName).take(128))
    try connection(c=>update(c,"UPDATE pekko_capture_gc_schedule_v1 SET phase='BLOCKED',reason=? WHERE capture_id=?",disabled.get,capture))catch{case NonFatal(_)=>()}
    observe("GC_NAMESPACE_BLOCKED",JsObject("captureId"->JsString(capture),"reason"->JsString(disabled.get)))
    if(!cn.xuyinyin.magic.cdc.recovery.ExecutionFailure.from(e,"gc-namespace").releaseConfirmed)throw e
  }
  def ready:Boolean=disabled.isEmpty && retention!=null
  def requested(id:String):Option[Long]=if(!ready)None else connection {c=>
    query(c,"SELECT required_floor FROM pekko_capture_gc_audits_v1 WHERE capture_id=? AND subscription_id=? AND phase='REQUESTED'",capture,id).headOption.map(_("required_floor").toLong)
  }
  def covered(p:BootstrapProgress,floor:Long):Boolean=p.gcCheckpoint.exists(x=>scala.util.Try {
    val f=x.parseJson.asJsObject.fields
    f("version")==JsNumber(2) && f("definition")==JsString(p.definition) && f("execution")==JsString(p.executionId) &&
      f("deltaAfter").asInstanceOf[JsNumber].value>=floor
  }.getOrElse(false))
  /** Called inside the same task grant, while its actual Actor can still issue a receipt. */
  def publish(id:String,runner:BootstrapRunner):Unit={
    val proof=runner.publishGcCheckpoint(retention)
    connection(c=>retention.activateCheckpoint(proof,reader,c,settings.account,settings.ledgerDefiner))
    val acceptedFloor=runner.state.gcCheckpoint.get.parseJson.asJsObject.fields("deltaAfter").asInstanceOf[JsNumber].value.toLongExact
    connection(c=>update(c,"UPDATE pekko_capture_gc_audits_v1 SET phase='ACTIVE',proof_id=?,required_floor=? WHERE capture_id=? AND subscription_id=?",proof,acceptedFloor,capture,id))
    observe("GC_PROOF_ACTIVE",JsObject("captureId"->JsString(capture),"taskId"->JsString(id),"proofId"->JsString(proof)))
  }
  private def wake(owner:String,now:Long):Unit=transaction {c=>
    require(query(c,"SELECT status FROM pekko_cdc_owner_v1 WHERE platform_id='default' AND owner_attempt=? FOR UPDATE",owner).head("status")=="ACTIVE","GC maintenance owner changed")
    val s=query(c,"SELECT * FROM pekko_capture_gc_schedule_v1 WHERE capture_id=? FOR UPDATE",capture).head
    if(s("next_at").toLong<=now){
      val page=query(c,"SELECT * FROM pekko_capture_subscriptions WHERE capture_id=? AND request_id>? ORDER BY request_id LIMIT ?",capture,s("after_task"),settings.pageSize)
      page.foreach {sub=>
        query(c,"SELECT phase,desired_state,delta_after FROM pekko_cdc_tasks_v1 WHERE task_id=? FOR UPDATE",sub("request_id")).headOption
          .filter(t=>t("phase")=="STREAMING" && t("desired_state")=="RUNNING").foreach {t=>
            val prior=query(c,"SELECT phase,required_floor FROM pekko_capture_gc_audits_v1 WHERE capture_id=? AND subscription_id=?",capture,sub("request_id")).headOption
            if(!prior.exists(p=>p("phase")=="ACTIVE" && p("required_floor").toLong>=t("delta_after").toLong)){
            update(c,"INSERT INTO pekko_capture_gc_audits_v1 VALUES(?,?,?,'REQUESTED',NULL) ON DUPLICATE KEY UPDATE required_floor=IF(phase='REQUESTED',required_floor,VALUES(required_floor)),phase='REQUESTED'",capture,sub("request_id"),t("delta_after").toLong)
            update(c,"UPDATE pekko_cdc_tasks_v1 SET schedulable=TRUE,due_at=LEAST(due_at,?) WHERE task_id=? AND grant_id IS NULL AND phase='STREAMING' AND desired_state='RUNNING'",now,sub("request_id"))
            }
          }
      }
      update(c,"UPDATE pekko_capture_gc_schedule_v1 SET after_task=?,next_at=? WHERE capture_id=?",
        if(page.nonEmpty)page.last("request_id")else"",if(page.nonEmpty)now else now+settings.intervalMillis,capture)
    }
  }
  /** A no-gain frame is a safe cursor advance, not a durable safety failure.
    * Re-open schedules left BLOCKED by an older runtime so the next tick can
    * skip the retained frame and continue to a later candidate.
    */
  private def reopenNoGainSchedule():Unit=transaction {c=>
    val row=query(c,"SELECT phase,reason FROM pekko_capture_gc_schedule_v1 WHERE capture_id=? FOR UPDATE",capture).headOption
    row.filter(r=>r("phase")=="BLOCKED" && Option(r("reason")).exists(_.startsWith("GC frame has no worthwhile space saving"))).foreach {_=>
      update(c,"UPDATE pekko_capture_gc_schedule_v1 SET phase='REPLANNING',reason='AUTO_REPLAN' WHERE capture_id=?",capture)
      observe("GC_REPLAN",JsObject("captureId"->JsString(capture),"reason"->JsString("AUTO_REPLAN_NO_GAIN")))
    }
  }
  def tick(owner:String,stop:()=>Boolean):Unit=if(ready && !stop() && System.nanoTime()>=nextWorkNanos)try {
    nextWorkNanos=System.nanoTime()+math.min(settings.intervalMillis,1000L)*1000000L
    reopenNoGainSchedule()
    wake(owner,System.currentTimeMillis())
    transaction {c=>
      val pending=query(c,"SELECT a.subscription_id FROM pekko_capture_gc_audits_v1 a JOIN pekko_cdc_tasks_v1 t ON t.task_id=a.subscription_id WHERE a.capture_id=? AND a.phase='REQUESTED' AND t.phase='STREAMING' AND t.desired_state='RUNNING' AND t.grant_id IS NULL ORDER BY a.subscription_id LIMIT ?",capture,settings.pageSize)
      pending.foreach(r=>update(c,"UPDATE pekko_cdc_tasks_v1 SET schedulable=TRUE,due_at=LEAST(due_at,?) WHERE task_id=? AND grant_id IS NULL AND phase='STREAMING' AND desired_state='RUNNING'",System.currentTimeMillis(),r("subscription_id")))
    }
    val operation=connection(c=>query(c,"SELECT operation_id FROM pekko_capture_gc_schedule_v1 WHERE capture_id=?",capture).head.get("operation_id").flatMap(Option(_)))
    val selected=operation.orElse {
      val view=retention.inspect()
      if(!view.eligible)None else {
        val id="runtime_"+UUID.randomUUID().toString
        transaction(c=>update(c,"UPDATE pekko_capture_gc_schedule_v1 SET operation_id=? WHERE capture_id=? AND operation_id IS NULL",id,capture))
        Some(id)
      }
    }
    selected.foreach {op=>
      retention.plan(op)
      val phase=connection(c=>retention.deleteBatch(op,settings.batchFrames,reader,c,settings.account,settings.ledgerDefiner))
      connection(c=>update(c,"UPDATE pekko_capture_gc_schedule_v1 SET phase=?,reason=NULL,operation_id=? WHERE capture_id=?",phase,if(phase=="DONE")null else op,capture))
      observe("GC_BATCH",JsObject("captureId"->JsString(capture),"operation"->JsString(op),"phase"->JsString(phase)))
    }
  }catch {case NonFatal(e)=>
    val message=Option(e.getMessage).getOrElse(e.getClass.getSimpleName).take(128)
    connection(c=>update(c,"UPDATE pekko_capture_gc_schedule_v1 SET phase='BLOCKED',reason=? WHERE capture_id=?",message,capture))
    observe("GC_BLOCKED",JsObject("captureId"->JsString(capture),"reason"->JsString(message)))
    if(!cn.xuyinyin.magic.cdc.recovery.ExecutionFailure.from(e,"gc-maintenance").releaseConfirmed)throw e
  }
  def status:JsObject=try connection {c=>
    val row=query(c,"SELECT * FROM pekko_capture_gc_schedule_v1 WHERE capture_id=?",capture).headOption
    JsObject("captureId"->JsString(capture),"phase"->JsString(row.map(_("phase")).getOrElse("ENABLING")),
      "reason"->disabled.orElse(row.flatMap(x=>Option(x("reason")))).map(JsString(_)).getOrElse(JsNull),
      "operation"->row.flatMap(x=>Option(x("operation_id"))).map(JsString(_)).getOrElse(JsNull))
  }catch {case NonFatal(_)=>JsObject("captureId"->JsString(capture),"phase"->JsString("UNAVAILABLE"))}
}
