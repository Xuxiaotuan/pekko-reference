package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.workflow.bootstrap.{BootstrapJdbc,BootstrapProgress}
import cn.xuyinyin.magic.workflow.engine.{CdcExecutionException,ExecutionFailure}
import java.sql.{Connection,SQLException}
import java.util.UUID
import spray.json._

/** A new control index. It never edits the kernel Journal, original L, SDK D or target receipts. */
final class CdcTaskStore(open:()=>Connection,val limits:CdcPlatformLimits) {
  private val jdbc=new BootstrapJdbc(open,2)
  import jdbc._
  def initialize():Unit=connection {c=>
    val mysql=c.getMetaData.getDatabaseProductName.toLowerCase(java.util.Locale.ROOT).contains("mysql")
    val suffix=if(mysql)" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin"else""
    Vector(
      "CREATE TABLE IF NOT EXISTS pekko_cdc_owner_v1(platform_id VARCHAR(32) PRIMARY KEY,owner_attempt VARCHAR(128),status VARCHAR(32) NOT NULL,updated_at BIGINT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_tasks_v1(task_id CHAR(64) PRIMARY KEY,capture_id VARCHAR(64) NOT NULL,request_id VARCHAR(128) NOT NULL,table_name VARCHAR(128) NOT NULL,generation VARCHAR(128) NOT NULL,target_table VARCHAR(128) NOT NULL,definition_hash CHAR(64) NOT NULL,desired_state VARCHAR(32) NOT NULL,control_revision BIGINT NOT NULL,phase VARCHAR(32) NOT NULL,journal_revision BIGINT NOT NULL,execution_id VARCHAR(128),capture_readiness VARCHAR(32) NOT NULL,initialization_evidence LONGTEXT,runtime_health VARCHAR(32) NOT NULL,release_status VARCHAR(32) NOT NULL,failure_code VARCHAR(64),observed_at BIGINT NOT NULL,last_verified_at BIGINT,due_at BIGINT NOT NULL,grant_id VARCHAR(64),grant_owner VARCHAR(128),continuation_json LONGTEXT,continuation_owner VARCHAR(128),cancel_evidence VARCHAR(32),work_pool VARCHAR(16) NOT NULL,schedulable BOOLEAN NOT NULL,delta_after BIGINT NOT NULL DEFAULT 0,wake_seq BIGINT NOT NULL DEFAULT 0,UNIQUE(capture_id,request_id),UNIQUE(capture_id,table_name,generation))",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_dispatch_v1(capture_id VARCHAR(64) PRIMARY KEY,after_seq BIGINT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_grants_v1(grant_id VARCHAR(64) PRIMARY KEY,task_id CHAR(64) NOT NULL,owner_attempt VARCHAR(128) NOT NULL,pool VARCHAR(16) NOT NULL,status VARCHAR(32) NOT NULL,created_at BIGINT NOT NULL,finished_at BIGINT)"
    ).foreach(sql=>update(c,sql+suffix))
    def index(table:String,name:String,columns:String):Unit={
      val rs=c.getMetaData.getIndexInfo(c.getCatalog,null,table,false,false);var found=false
      try while(rs.next())if(Option(rs.getString("INDEX_NAME")).exists(_.equalsIgnoreCase(name)))found=true finally rs.close()
      if(!found)try update(c,s"CREATE INDEX $name ON $table($columns)")catch {case e:SQLException=>
        val check=c.getMetaData.getIndexInfo(c.getCatalog,null,table,false,false);var exists=false
        try while(check.next())if(Option(check.getString("INDEX_NAME")).exists(_.equalsIgnoreCase(name)))exists=true finally check.close()
        if(!exists)throw e
      }
    }
    index("pekko_cdc_tasks_v1","cdc_task_ready_v1","capture_id,work_pool,schedulable,due_at,task_id")
    index("pekko_cdc_grants_v1","cdc_grant_budget_v1","pool,status")
    index("pekko_cdc_grants_v1","cdc_grant_state_v1","status,grant_id")
    if(query(c,"SELECT platform_id FROM pekko_cdc_owner_v1 WHERE platform_id='default'").isEmpty){
      val retained=Vector("pekko_cdc_tasks_v1","pekko_cdc_grants_v1","pekko_cdc_dispatch_v1").exists(t=>query(c,s"SELECT 1 AS n FROM $t LIMIT 1").nonEmpty)
      if(retained)throw new CdcPlatformException("CONTROL_OWNER_MISSING")
      try update(c,"INSERT INTO pekko_cdc_owner_v1 VALUES('default',NULL,'STOPPED',0)")catch {case _:SQLException=>
        require(query(c,"SELECT platform_id FROM pekko_cdc_owner_v1 WHERE platform_id='default'").nonEmpty,"CDC owner initialization failed")}
    }
  }
  private def validateId(id:String):Unit=if(id==null || !id.matches("[a-f0-9]{64}"))throw new CdcPlatformException("INVALID_TASK_ID")
  private def record(r:Map[String,String]):CdcTaskRecord={
    val request=CdcTaskRequest(r("request_id"),r("capture_id"),r("table_name"),r("generation"),r("target_table"))
    val phase=r("phase");val grant=Option(r("grant_id"))
    val view=CdcTaskView(r("task_id"),request.requestId,request.captureId,request.table,request.generation,request.targetTable,
      r("definition_hash"),r("desired_state"),r("control_revision").toLong,phase,r("journal_revision").toLong,
      Option(r("execution_id")),r("capture_readiness"),Option(r("initialization_evidence")),r("runtime_health"),r("release_status"),
      Option(r("failure_code")),r("observed_at").toLong,Option(r("last_verified_at")).map(_.toLong),
      !Set("PAUSED","CANCELED").contains(phase) && r("release_status")!="UNKNOWN",Option(r.getOrElse("cancel_evidence",null)))
    CdcTaskRecord(view,request,grant,Option(r("grant_owner")),r("due_at").toLong,
      Option(r("continuation_json")).map(CdcProgressJson.decode),r("work_pool"),Set("1","TRUE","true")(r("schedulable")),r("wake_seq").toLong)
  }
  private def row(c:Connection,id:String,lock:Boolean=false):Option[CdcTaskRecord]=
    query(c,"SELECT * FROM pekko_cdc_tasks_v1 WHERE task_id=?"+(if(lock)" FOR UPDATE"else""),id).headOption.map(record)
  private def owner(c:Connection):Map[String,String]=query(c,"SELECT * FROM pekko_cdc_owner_v1 WHERE platform_id='default' FOR UPDATE").head
  def beginOwner(attempt:String,now:Long):Boolean=transaction {c=>
    require(attempt.matches("[A-Za-z0-9_-]{1,128}"))
    val old=owner(c)
    val active=query(c,"SELECT COUNT(*) AS n FROM pekko_cdc_grants_v1 WHERE status IN ('ACTIVE','UNKNOWN')").head("n").toLong
    if(old("status")=="STOPPED" && active==0){update(c,"UPDATE pekko_cdc_owner_v1 SET owner_attempt=?,status='ACTIVE',updated_at=? WHERE platform_id='default'",attempt,now);true}
    else old("owner_attempt")==attempt && old("status")=="ACTIVE"
  }
  def ownerStatus:String=connection(c=>query(c,"SELECT status FROM pekko_cdc_owner_v1 WHERE platform_id='default'").head("status"))
  def endOwner(attempt:String,confirmed:Boolean,now:Long):Boolean=transaction {c=>
    val old=owner(c)
    if(old("owner_attempt")!=attempt)false
    else {
      val active=query(c,"SELECT COUNT(*) AS n FROM pekko_cdc_grants_v1 WHERE status IN ('ACTIVE','UNKNOWN')").head("n").toLong
      val clean=confirmed && active==0
      update(c,"UPDATE pekko_cdc_owner_v1 SET status=?,updated_at=? WHERE platform_id='default'",if(clean)"STOPPED"else"UNKNOWN",now)
      clean
    }
  }
  def submit(request:CdcTaskRequest,now:Long):CdcTaskAcceptance={
    request.validate()
    try transaction {c=>
      // Serialize request identity against admission/cancel, including previously unexecuted rows.
      owner(c)
      row(c,request.taskId,true) match {
        case Some(existing)=>
          if(existing.view.definitionFingerprint!=request.fingerprint)throw new CdcPlatformException("CONFLICT")
          CdcTaskAcceptance(existing.view,false)
        case None=>
          if(query(c,"SELECT task_id FROM pekko_cdc_tasks_v1 WHERE capture_id=? AND table_name=? AND generation=?",request.captureId,request.table,request.generation).nonEmpty)
            throw new CdcPlatformException("CONFLICT")
          update(c,"INSERT INTO pekko_cdc_tasks_v1(task_id,capture_id,request_id,table_name,generation,target_table,definition_hash,desired_state,control_revision,phase,journal_revision,capture_readiness,runtime_health,release_status,observed_at,due_at,work_pool,schedulable) VALUES(?,?,?,?,?,?,?,'RUNNING',1,'QUEUED',0,'UNKNOWN','WAITING','RELEASED',?,?,'SNAPSHOT',TRUE)",
            request.taskId,request.captureId,request.requestId,request.table,request.generation,request.targetTable,request.fingerprint,now,now)
          CdcTaskAcceptance(row(c,request.taskId).get.view,true)
      }
    }catch {case e:SQLException if Option(e.getSQLState).exists(_.startsWith("23"))=>throw new CdcPlatformException("CONFLICT")}
  }
  def get(id:String):Option[CdcTaskView]={validateId(id);connection(c=>row(c,id).map(_.view))}
  private[cdc] def getRecord(id:String):Option[CdcTaskRecord]={validateId(id);connection(c=>row(c,id))}
  def listAfter(after:Option[String],limit:Int):CdcTaskPage={
    if(limit<1 || limit>100 || after.exists(x=> !x.matches("[a-f0-9]{64}")))throw new CdcPlatformException("INVALID_PAGE")
    connection {c=>val rows=query(c,"SELECT * FROM pekko_cdc_tasks_v1 WHERE task_id>? ORDER BY task_id LIMIT ?",after.getOrElse(""),limit+1).map(record).map(_.view)
      CdcTaskPage(rows.take(limit),if(rows.size>limit)rows.take(limit).lastOption.map(_.taskId)else None)}
  }
  def cancel(id:String,now:Long):CdcTaskView={validateId(id);transaction {c=>
    owner(c)
    val task=row(c,id,true).getOrElse(throw new CdcPlatformException("NOT_FOUND"));val v=task.view
    if(v.phase=="PAUSED" || v.releaseStatus=="UNKNOWN")throw new CdcPlatformException("CANCEL_NOT_PERMITTED")
    if(v.desiredState!="CANCELED" && v.phase!="CANCELED"){
      val preexec=v.executionId.isEmpty && task.grantId.isEmpty && v.phase=="QUEUED"
      update(c,"UPDATE pekko_cdc_tasks_v1 SET desired_state='CANCELED',control_revision=control_revision+1,phase=?,cancel_evidence=?,observed_at=?,due_at=?,schedulable=? WHERE task_id=?",
        if(preexec)"CANCELED"else"CANCELING",if(preexec)"PREEXEC_CANCEL"else v.cancellationEvidence.orNull,now,now,!preexec && task.grantId.isEmpty,id)
    }
    row(c,id).get.view
  }}
  private[cdc] def claim(attempt:String,pool:String,ready:Set[String],now:Long,onlyTasks:Option[Set[String]]=None):Option[CdcTaskGrant]=transaction {c=>
    require(pool=="SNAPSHOT" || pool=="TAIL")
    val o=owner(c)
    if(o("status")!="ACTIVE" || o("owner_attempt")!=attempt || ready.isEmpty || onlyTasks.exists(_.isEmpty))None
    else {
      val count=query(c,"SELECT COUNT(*) AS n FROM pekko_cdc_grants_v1 WHERE pool=? AND status IN ('ACTIVE','UNKNOWN')",pool).head("n").toInt
      val maximum=if(pool=="TAIL")limits.tailSteps else limits.snapshotSteps
      if(count>=maximum)None else {
        val captureIds=ready.toVector.sorted
        require(captureIds.size<=32,"too many configured capture domains")
        val candidates=onlyTasks match {
          case Some(ids)=>
            require(ids.size<=32,"too many pinned validation tasks")
            ids.toVector.sorted.flatMap {id=>
              query(c,"SELECT * FROM pekko_cdc_tasks_v1 WHERE task_id=? AND work_pool=? AND schedulable=TRUE AND due_at<=? FOR UPDATE",id,pool,now).map(record)
            }.filter(t=>ready(t.view.captureId))
          case None=>
            // Equality predicates match the scheduling index prefix. At most one head row
            // per configured capture is locked; terminal and unready domains are never scanned.
            captureIds.flatMap {capture=>
              query(c,"SELECT * FROM pekko_cdc_tasks_v1 WHERE capture_id=? AND work_pool=? AND schedulable=TRUE AND due_at<=? ORDER BY due_at,task_id LIMIT 1 FOR UPDATE",capture,pool,now).map(record)
            }
        }
        candidates.sortBy(t=>(t.dueAt,t.view.taskId)).headOption.map {task=>
          val id=UUID.randomUUID().toString
          update(c,"INSERT INTO pekko_cdc_grants_v1 VALUES(?,?,?,?,'ACTIVE',?,NULL)",id,task.view.taskId,attempt,pool,now)
          update(c,"UPDATE pekko_cdc_tasks_v1 SET grant_id=?,grant_owner=?,release_status='IN_USE',runtime_health='WORKING',observed_at=?,schedulable=FALSE WHERE task_id=?",id,attempt,now,task.view.taskId)
          CdcTaskGrant(id,attempt,pool,row(c,task.view.taskId).get)
        }
      }
    }
  }
  private def requireGrant(c:Connection,grant:CdcTaskGrant):CdcTaskRecord={
    val r=row(c,grant.task.view.taskId,true).getOrElse(throw new CdcPlatformException("NOT_FOUND"))
    if(!r.grantId.contains(grant.id) || !r.grantOwner.contains(grant.owner))throw new CdcPlatformException("STALE_GRANT")
    r
  }
  private[cdc] def continuation(grant:CdcTaskGrant):Option[BootstrapProgress]=connection {c=>
    val r=query(c,"SELECT continuation_json,continuation_owner FROM pekko_cdc_tasks_v1 WHERE task_id=? AND grant_id=? AND grant_owner=?",grant.task.view.taskId,grant.id,grant.owner).head
    if(Option(r("continuation_owner")).contains(grant.owner))Option(r("continuation_json")).map(CdcProgressJson.decode)else None
  }
  private[cdc] def finish(grant:CdcTaskGrant,progress:BootstrapProgress,initialization:Option[String],readiness:String,
    didWork:Boolean,validationComplete:Boolean,delay:Long,now:Long):Unit=transaction {c=>
    val old=requireGrant(c,grant)
    val canceled=old.view.desiredState=="CANCELED"
    val phase=if(canceled && !Set("PAUSED","CANCELED").contains(progress.phase))"CANCELING"else progress.phase
    update(c,"UPDATE pekko_cdc_grants_v1 SET status='RELEASED',finished_at=? WHERE grant_id=? AND status='ACTIVE'",now,grant.id)
    update(c,"UPDATE pekko_cdc_tasks_v1 SET phase=?,journal_revision=?,execution_id=?,capture_readiness=?,initialization_evidence=?,runtime_health=?,release_status='RELEASED',failure_code=?,observed_at=?,last_verified_at=?,due_at=?,grant_id=NULL,grant_owner=NULL,continuation_json=?,continuation_owner=?,work_pool=?,schedulable=?,delta_after=? WHERE task_id=?",
      phase,progress.revision,Option(progress.executionId).filter(_.nonEmpty).orNull,readiness,initialization.orElse(old.view.initializationEvidence).orNull,
      if(phase=="PAUSED")"PAUSED"else if(phase=="CANCELED")"STOPPED"else if(validationComplete && progress.phase=="STREAMING")"HEALTHY"else"VERIFYING",
      progress.failure.orNull,now,if(validationComplete)Long.box(now)else old.view.lastVerifiedAt.map(Long.box).orNull,now+math.max(0L,delay),
      CdcProgressJson.encode(progress),grant.owner,if(progress.phase=="STREAMING" || (phase=="CANCELING" && old.workPool=="TAIL"))"TAIL"else"SNAPSHOT",!Set("PAUSED","CANCELED").contains(phase) && (phase!="STREAMING" || !validationComplete || canceled || old.wakeSequence>progress.deltaAfter),progress.deltaAfter,old.view.taskId)
  }
  private[cdc] def defer(grant:CdcTaskGrant,delay:Long,now:Long):Unit=transaction {c=>
    val old=requireGrant(c,grant)
    update(c,"UPDATE pekko_cdc_grants_v1 SET status='RELEASED',finished_at=? WHERE grant_id=?",now,grant.id)
    update(c,"UPDATE pekko_cdc_tasks_v1 SET grant_id=NULL,grant_owner=NULL,release_status='RELEASED',runtime_health='WAITING',due_at=?,schedulable=TRUE WHERE task_id=?",now+delay,old.view.taskId)
  }
  private[cdc] def finishFailureReleased(grant:CdcTaskGrant,code:String,now:Long):Unit=transaction {c=>
    requireGrant(c,grant)
    update(c,"UPDATE pekko_cdc_grants_v1 SET status='RELEASED',finished_at=? WHERE grant_id=?",now,grant.id)
    update(c,"UPDATE pekko_cdc_tasks_v1 SET phase='PAUSED',runtime_health='PAUSED',release_status='RELEASED',failure_code=?,observed_at=?,schedulable=FALSE,grant_id=NULL,grant_owner=NULL WHERE task_id=?",code,now,grant.task.view.taskId)
  }
  private[cdc] def finishUnknown(grant:CdcTaskGrant,code:String,now:Long):Unit=transaction {c=>
    requireGrant(c,grant)
    update(c,"UPDATE pekko_cdc_grants_v1 SET status='UNKNOWN' WHERE grant_id=?",grant.id)
    update(c,"UPDATE pekko_cdc_tasks_v1 SET phase='PAUSED',runtime_health='UNKNOWN',release_status='UNKNOWN',failure_code=?,observed_at=?,schedulable=FALSE WHERE task_id=?",code,now,grant.task.view.taskId)
  }
  /** Bounded scan of new immutable L frames, not a poll of every subscribed table. */
  def dispatch(attempt:String,captures:Set[String],maxFrames:Int,now:Long):CdcDispatchResult=transaction {c=>
    require(captures.size<=32 && maxFrames>=math.max(1,captures.size) && maxFrames<=128)
    val own=owner(c)
    if(own("owner_attempt")!=attempt || own("status")!="ACTIVE")throw new CdcPlatformException("OWNER_RELEASE_UNPROVEN")
    var frames=0;var caughtUp=true
    val budget=math.max(1,maxFrames/math.max(1,captures.size))
    captures.toVector.sorted.foreach {capture=>
      val previous=query(c,"SELECT after_seq FROM pekko_cdc_dispatch_v1 WHERE capture_id=? FOR UPDATE",capture).headOption
      val after=previous.map(_("after_seq").toLong).getOrElse(0L)
      if(previous.isEmpty)update(c,"INSERT INTO pekko_cdc_dispatch_v1 VALUES(?,0)",capture)
      val entries=query(c,"SELECT seq,kind,subscription_id FROM pekko_capture_log WHERE capture_id=? AND seq>? ORDER BY seq LIMIT ?",capture,after,budget)
      frames+=entries.size
      if(entries.size==budget)caughtUp=false
      entries.filter(_("kind")=="GROUP").foreach {entry=>
        val sequence=entry("seq").toLong
        update(c,"UPDATE pekko_cdc_tasks_v1 SET wake_seq=CASE WHEN wake_seq<? THEN ? ELSE wake_seq END,schedulable=CASE WHEN phase='STREAMING' AND grant_id IS NULL AND delta_after<? THEN TRUE ELSE schedulable END,due_at=CASE WHEN phase='STREAMING' AND delta_after<? THEN ? ELSE due_at END WHERE task_id=? AND capture_id=? AND phase NOT IN ('PAUSED','CANCELED')",
          sequence,sequence,sequence,sequence,now,entry("subscription_id"),capture)
      }
      entries.lastOption.foreach(e=>update(c,"UPDATE pekko_cdc_dispatch_v1 SET after_seq=? WHERE capture_id=?",e("seq").toLong,capture))
    }
    CdcDispatchResult(frames,caughtUp)
  }
  /** Controlled owner restart: activate only a bounded metadata page, never all Actors. */
  def recoverColdPage(attempt:String,after:Option[String],limit:Int,now:Long):Option[String]=transaction {c=>
    require(limit>0 && limit<=128 && after.forall(_.matches("[a-f0-9]{64}")))
    val own=owner(c)
    if(own("owner_attempt")!=attempt || own("status")!="ACTIVE")throw new CdcPlatformException("OWNER_RELEASE_UNPROVEN")
    val page=query(c,"SELECT task_id FROM pekko_cdc_tasks_v1 WHERE task_id>? ORDER BY task_id LIMIT ?",after.getOrElse(""),limit+1)
    val ids=page.take(limit).map(_("task_id"))
    if(ids.nonEmpty)update(c,"UPDATE pekko_cdc_tasks_v1 SET schedulable=TRUE,due_at=?,runtime_health='VERIFYING' WHERE task_id IN ("+ids.map(_=>"?").mkString(",")+") AND grant_id IS NULL AND phase NOT IN ('PAUSED','CANCELED')",(Vector[Any](now)++ids):_*)
    if(page.size>limit)ids.lastOption else None
  }
  def grants:Vector[(String,String,String)]=connection(c=>query(c,"SELECT grant_id,owner_attempt,status FROM pekko_cdc_grants_v1 WHERE status IN ('ACTIVE','UNKNOWN') ORDER BY grant_id LIMIT 128").map(r=>(r("grant_id"),r("owner_attempt"),r("status"))))
  def counts:Map[String,Long]=connection(c=>query(c,"SELECT status,COUNT(*) AS n FROM pekko_cdc_grants_v1 WHERE status IN ('ACTIVE','UNKNOWN') GROUP BY status").map(r=>r("status")->r("n").toLong).toMap)
}
