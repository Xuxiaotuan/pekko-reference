package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.workflow.bootstrap.{BootstrapJdbc,BootstrapProgress}
import cn.xuyinyin.magic.workflow.engine.{CdcExecutionException,ExecutionFailure}
import java.sql.{Connection,SQLException}
import java.util.UUID
import spray.json._

/** Immutable association recorded only when an owner acquires the platform. */
final case class CdcManagedOwner(bootId:String,deploymentId:String,hostId:String,guardianBoot:String,
  guardianPublicKey:String,pid:Long,startIdentity:String,profileHash:String)
private[cdc] final case class CdcBRecoveryPermit(id:String,oldAttempt:String,taskId:String,captureId:String,offset:String,
  markerPresent:Boolean,table:String,generation:String,operation:String,proof:String,claimedBy:Option[String],consumed:Boolean)

/** A new control index. It never edits the kernel Journal, original L, SDK D or target receipts. */
final class CdcTaskStore(open:()=>Connection,val limits:CdcPlatformLimits) {
  private val jdbc=new BootstrapJdbc(open,2)
  import jdbc._
  def initialize():Unit=connection {c=>
    val mysql=c.getMetaData.getDatabaseProductName.toLowerCase(java.util.Locale.ROOT).contains("mysql")
    val suffix=if(mysql)" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin"else""
    Vector(
      "CREATE TABLE IF NOT EXISTS pekko_cdc_owner_v1(platform_id VARCHAR(32) PRIMARY KEY,owner_attempt VARCHAR(128),status VARCHAR(32) NOT NULL,updated_at BIGINT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_managed_owner_v1(owner_attempt VARCHAR(128) PRIMARY KEY,boot_id VARCHAR(128) NOT NULL,deployment_id VARCHAR(255) NOT NULL,host_id VARCHAR(128) NOT NULL,guardian_boot VARCHAR(128) NOT NULL,guardian_key TEXT NOT NULL,process_id BIGINT NOT NULL,start_identity VARCHAR(128) NOT NULL,profile_hash VARCHAR(128) NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_managed_manifest_v1(owner_attempt VARCHAR(128) PRIMARY KEY,manifest_json LONGTEXT NOT NULL,created_at BIGINT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_b_recovery_v1(owner_attempt VARCHAR(128) PRIMARY KEY,permit_id VARCHAR(128) NOT NULL,task_id CHAR(64) NOT NULL,capture_id VARCHAR(64) NOT NULL,offset_json LONGTEXT NOT NULL,marker_present BOOLEAN NOT NULL,table_name VARCHAR(128) NOT NULL,generation VARCHAR(128) NOT NULL,operation_id VARCHAR(128) NOT NULL,signed_proof LONGTEXT NOT NULL,created_at BIGINT NOT NULL,claimed_by VARCHAR(128),consumed BOOLEAN NOT NULL DEFAULT FALSE)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_b_budget_v1(task_id CHAR(64) PRIMARY KEY,attempts INT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_b_rejection_v1(permit_id VARCHAR(128) PRIMARY KEY,task_id CHAR(64) NOT NULL)",
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
    index("pekko_cdc_b_recovery_v1","cdc_b_recovery_active_v2","capture_id,task_id,claimed_by,consumed")
    index("pekko_cdc_b_recovery_v1","cdc_b_recovery_cancel_v1","task_id,consumed")
    index("pekko_cdc_b_recovery_v1","cdc_b_recovery_claim_v1","claimed_by,consumed")
    index("pekko_cdc_managed_owner_v1","cdc_managed_owner_boot_v1","boot_id")
    if(query(c,"SELECT platform_id FROM pekko_cdc_owner_v1 WHERE platform_id='default'").isEmpty){
      val retained=Vector("pekko_cdc_tasks_v1","pekko_cdc_grants_v1","pekko_cdc_dispatch_v1","pekko_cdc_managed_owner_v1","pekko_cdc_managed_manifest_v1","pekko_cdc_b_recovery_v1","pekko_cdc_b_budget_v1","pekko_cdc_b_rejection_v1").exists(t=>query(c,s"SELECT 1 AS n FROM $t LIMIT 1").nonEmpty)
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
      !Set("PAUSED","CANCELED").contains(phase) && r("release_status")!="UNKNOWN" && !r.get("recovery_pending").exists(Set("1","TRUE","true")),Option(r.getOrElse("cancel_evidence",null)))
    CdcTaskRecord(view,request,grant,Option(r("grant_owner")),r("due_at").toLong,
      Option(r("continuation_json")).map(CdcProgressJson.decode),r("work_pool"),Set("1","TRUE","true")(r("schedulable")),r("wake_seq").toLong)
  }
  private def row(c:Connection,id:String,lock:Boolean=false):Option[CdcTaskRecord]=
    query(c,"SELECT t.*,EXISTS(SELECT 1 FROM pekko_cdc_b_recovery_v1 p WHERE p.task_id=t.task_id AND p.consumed=FALSE) AS recovery_pending FROM pekko_cdc_tasks_v1 t WHERE t.task_id=?"+(if(lock)" FOR UPDATE"else""),id).headOption.map(record)
  private def owner(c:Connection):Map[String,String]=query(c,"SELECT * FROM pekko_cdc_owner_v1 WHERE platform_id='default' FOR UPDATE").head
  private def managedOn(c:Connection,attempt:String):Option[CdcManagedOwner]=
    query(c,"SELECT * FROM pekko_cdc_managed_owner_v1 WHERE owner_attempt=?",attempt).headOption.map {r=>
      CdcManagedOwner(r("boot_id"),r("deployment_id"),r("host_id"),r("guardian_boot"),r("guardian_key"),r("process_id").toLong,r("start_identity"),r("profile_hash"))
    }
  def managedOwner(attempt:String):Option[CdcManagedOwner]=connection(c=>managedOn(c,attempt))
  def managedManifest(attempt:String):Option[String]=connection(c=>query(c,"SELECT manifest_json FROM pekko_cdc_managed_manifest_v1 WHERE owner_attempt=?",attempt).headOption.map(_("manifest_json")))
  private def permit(r:Map[String,String]):CdcBRecoveryPermit=CdcBRecoveryPermit(r("permit_id"),r("owner_attempt"),r("task_id"),r("capture_id"),r("offset_json"),
    Set("1","TRUE","true")(r("marker_present")),r("table_name"),r("generation"),r("operation_id"),r("signed_proof"),Option(r("claimed_by")),Set("1","TRUE","true")(r("consumed")))
  private[cdc] def pendingBPermit(capture:String,task:String):Option[CdcBRecoveryPermit]=connection {c=>
    val current=owner(c)
    if(current("status")!="ACTIVE")None else {
      val entries=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE capture_id=? AND task_id=? AND claimed_by=? AND consumed=FALSE LIMIT 2",capture,task,current("owner_attempt")).map(permit)
      require(entries.size<=1,"duplicate pending B permits");entries.headOption
    }
  }
  private[cdc] def permitForOldOwner(attempt:String):Option[CdcBRecoveryPermit]=connection(c=>
    query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE owner_attempt=?",attempt).headOption.map(permit))
  private[cdc] def authorizedBPermits(capture:String,offset:String,ids:Vector[String]):Boolean=connection {c=>
    val current=owner(c)
    if(current("status")!="ACTIVE" || managedOn(c,current("owner_attempt")).isEmpty || ids.size!=1)false
    else {
      val entries=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE capture_id=? AND task_id=? AND claimed_by=? AND consumed=FALSE LIMIT 2",capture,ids.head,current("owner_attempt")).map(permit)
      require(entries.size<=1,"duplicate pending B permits")
      ids.forall(id=>entries.exists(p=>p.taskId==id && p.offset==offset && p.claimedBy.contains(current("owner_attempt")) && !p.consumed &&
        row(c,id).exists(t=>t.view.captureId==capture && t.view.table==p.table && t.view.generation==p.generation && t.view.desiredState=="RUNNING")))
    }
  }
  private[cdc] def authorizedBPermit(id:String,task:String):Boolean=connection {c=>
    val current=owner(c)
    current("status")=="ACTIVE" && managedOn(c,current("owner_attempt")).nonEmpty &&
      row(c,task).exists(t=>t.view.desiredState=="RUNNING" && t.grantOwner.contains(current("owner_attempt")) && t.grantId.nonEmpty) &&
      query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE permit_id=? AND task_id=?",id,task).map(permit).exists(p=>p.claimedBy.contains(current("owner_attempt")) && !p.consumed)
  }
  private[cdc] def consumeBPermit(id:String,task:String):Boolean=transaction {c=>
    val current=owner(c)
    val attempt=current("owner_attempt")
    val records=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE permit_id=? AND task_id=? FOR UPDATE",id,task).map(permit)
    if(current("status")!="ACTIVE" || managedOn(c,attempt).isEmpty || records.size!=1 || !records.head.claimedBy.contains(attempt) ||
      !row(c,task,true).exists(t=>t.grantOwner.contains(attempt) && t.grantId.nonEmpty && t.view.desiredState=="RUNNING"))false
    else {if(!records.head.consumed)update(c,"UPDATE pekko_cdc_b_recovery_v1 SET consumed=TRUE WHERE permit_id=? AND task_id=?",id,task);true}
  }
  private[cdc] def consumedBPermit(id:String,task:String):Boolean=connection {c=>
    val current=owner(c);val attempt=current("owner_attempt")
    current("status")=="ACTIVE" && managedOn(c,attempt).nonEmpty &&
      row(c,task).exists(t=>t.grantOwner.contains(attempt) && t.grantId.nonEmpty && t.view.desiredState=="RUNNING") &&
      query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE permit_id=? AND task_id=?",id,task).map(permit).exists(p=>p.claimedBy.contains(attempt) && p.consumed)
  }
  /** Explicit Journal rejection is terminal and does not spend an automatic-recovery attempt. */
  private[cdc] def rejectBPermit(id:String,task:String):Boolean=transaction {c=>
    val current=owner(c);val attempt=current("owner_attempt")
    val previous=query(c,"SELECT task_id FROM pekko_cdc_b_rejection_v1 WHERE permit_id=?",id).headOption
    if(previous.nonEmpty)previous.exists(_("task_id")==task)
    else {
      val p=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE permit_id=? AND task_id=? FOR UPDATE",id,task).map(permit)
      if(current("status")!="ACTIVE" || managedOn(c,attempt).isEmpty || p.size!=1 || p.head.consumed || !p.head.claimedBy.contains(attempt) ||
        !row(c,task,true).exists(t=>t.grantOwner.contains(attempt) && t.grantId.nonEmpty && t.view.desiredState=="RUNNING"))false
      else {
        val budget=query(c,"SELECT attempts FROM pekko_cdc_b_budget_v1 WHERE task_id=? FOR UPDATE",task).headOption.map(_("attempts").toInt).getOrElse(0)
        require(budget>0,"rejected permit budget missing")
        update(c,"INSERT INTO pekko_cdc_b_rejection_v1 VALUES(?,?)",id,task)
        update(c,"UPDATE pekko_cdc_b_recovery_v1 SET consumed=TRUE WHERE permit_id=? AND task_id=?",id,task)
        update(c,"UPDATE pekko_cdc_b_budget_v1 SET attempts=? WHERE task_id=?",budget-1,task);true
      }
    }
  }
  private[cdc] def rejectedBPermit(id:String,task:String):Boolean=connection(c=>
    query(c,"SELECT task_id FROM pekko_cdc_b_rejection_v1 WHERE permit_id=?",id).exists(_("task_id")==task))
  private[cdc] def ownerAttempt:Option[(String,String)]=connection(c=>query(c,"SELECT owner_attempt,status FROM pekko_cdc_owner_v1 WHERE platform_id='default'").headOption.flatMap(r=>Option(r("owner_attempt")).map(_->r("status"))))
  def managedOwnerNeedsIsolation(boot:String):Boolean=connection {c=>
    val current=query(c,"SELECT owner_attempt,status FROM pekko_cdc_owner_v1 WHERE platform_id='default'").headOption
    current.exists(r=>r("status")=="UNKNOWN" && Option(r("owner_attempt")).flatMap(a=>managedOn(c,a)).exists(_.bootId==boot))
  }
  def managedOwnerNeedsIsolation(expected:CdcManagedOwner):Boolean=connection {c=>
    val current=query(c,"SELECT owner_attempt,status FROM pekko_cdc_owner_v1 WHERE platform_id='default'").headOption
    current.exists {r=>
      val actual=Option(r("owner_attempt")).flatMap(a=>managedOn(c,a))
      if(r("status")=="UNKNOWN" && actual.exists(_.bootId==expected.bootId)){
        require(actual.contains(expected),"CDC owner identity differs from guardian launch")
        true
      }else false
    }
  }
  def managedOwnerIs(boot:String):Boolean=connection {c=>
    val current=query(c,"SELECT owner_attempt,status FROM pekko_cdc_owner_v1 WHERE platform_id='default'").headOption
    current.exists(r=>Set("ACTIVE","UNKNOWN")(r("status")) && Option(r("owner_attempt")).flatMap(a=>managedOn(c,a)).exists(_.bootId==boot))
  }
  private[cdc] def soleOutstandingTask(attempt:String):Option[CdcTaskView]=connection {c=>
    val grants=query(c,"SELECT task_id,owner_attempt,pool,status FROM pekko_cdc_grants_v1 WHERE status IN ('ACTIVE','UNKNOWN') ORDER BY grant_id LIMIT 129")
    val unknown=grants.filter(_("status")=="UNKNOWN")
    val snapshots=grants.filter(_("pool")=="SNAPSHOT")
    val candidate=if(unknown.size==1 && unknown.head("pool")=="SNAPSHOT")unknown.headOption
      else if(unknown.isEmpty && snapshots.size==1)snapshots.headOption else None
    if(grants.nonEmpty && grants.size<=128 && grants.forall(_("owner_attempt")==attempt))candidate.flatMap(g=>row(c,g("task_id")).map(_.view))
    else if(grants.isEmpty){
      val waiting=query(c,"SELECT p.task_id FROM pekko_cdc_b_recovery_v1 p JOIN pekko_cdc_tasks_v1 t ON p.task_id=t.task_id WHERE p.claimed_by=? AND t.phase='QUEUED' AND t.grant_id IS NULL LIMIT 2",attempt)
      if(waiting.size==1)row(c,waiting.head("task_id")).filter(_.grantId.isEmpty).map(_.view)else None
    } else None
  }
  /** Caller must supply independently verified guardian/drain and actual source/D evidence. */
  private[cdc] def releaseForPendingB(attempt:String,proof:CdcManagedIsolation.Verified,b:CdcManagedPendingB.Verified,now:Long):CdcBRecoveryPermit=transaction {c=>
    val old=owner(c)
    val previous=query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE owner_attempt=?",attempt).headOption.map(permit)
    def matches(p:CdcBRecoveryPermit)=p.taskId==b.taskId && p.captureId==b.captureId && p.offset==b.offset && p.markerPresent==b.markerPresent &&
      p.table==b.table && p.generation==b.generation && p.operation==proof.operationId && p.proof==proof.signedProof
    previous match {
      case Some(p)=>require(matches(p),"recovery permit evidence changed");p
      case None=>
        require(old("owner_attempt")==attempt && Set("ACTIVE","UNKNOWN")(old("status")),"old CDC owner changed")
        require(managedOn(c,attempt).contains(proof.managed),"guardian proof is not bound to CDC owner")
        require(query(c,"SELECT manifest_json FROM pekko_cdc_managed_manifest_v1 WHERE owner_attempt=?",attempt).headOption.exists(_("manifest_json")==proof.manifest),"resource manifest changed")
        val grants=query(c,"SELECT * FROM pekko_cdc_grants_v1 WHERE status IN ('ACTIVE','UNKNOWN') ORDER BY grant_id LIMIT 129 FOR UPDATE")
        require(grants.size<=128 && grants.forall(_("owner_attempt")==attempt),"other outstanding work prevents recovery")
        val candidateGrants=grants.filter(_("task_id")==b.taskId)
        val otherGrants=grants.filterNot(_("task_id")==b.taskId)
        require(candidateGrants.size<=1 && candidateGrants.forall(_("pool")=="SNAPSHOT") && otherGrants.forall(_("status")=="ACTIVE"),"other uncertain work prevents recovery")
        val otherTasks=otherGrants.map {g=>
          val t=row(c,g("task_id"),true).getOrElse(throw new IllegalStateException("outstanding task missing"))
          require(t.grantId.contains(g("grant_id")) && t.grantOwner.contains(attempt) && t.view.captureId==b.captureId,"other task or grant changed")
          require(g("pool")=="TAIL" && t.view.phase=="STREAMING" && t.view.desiredState=="RUNNING" &&
            t.view.executionId.exists(_.nonEmpty) && t.view.initializationEvidence.exists(_.nonEmpty) &&
            query(c,"SELECT 1 FROM pekko_cdc_b_recovery_v1 WHERE task_id=? AND consumed=FALSE LIMIT 1",t.view.taskId).isEmpty,
            "other task is not an independently recoverable streaming tail")
          (g,t)
        }
        val claimed=query(c,"SELECT task_id FROM pekko_cdc_b_recovery_v1 WHERE claimed_by=? AND consumed=FALSE LIMIT 2 FOR UPDATE",attempt)
        require(claimed.size<=1 && claimed.forall(_("task_id")==b.taskId),"other claimed recovery permits prevent recovery")
        val task=row(c,b.taskId,true).getOrElse(throw new IllegalStateException("pending B task missing"))
        val grantMatches=if(candidateGrants.nonEmpty)task.grantId.contains(candidateGrants.head("grant_id")) && task.grantOwner.contains(attempt)
          else task.grantId.isEmpty && task.view.phase=="QUEUED" && query(c,"SELECT permit_id FROM pekko_cdc_b_recovery_v1 WHERE claimed_by=? AND task_id=? LIMIT 2",attempt,b.taskId).size==1
        require(task.view.captureId==b.captureId && task.view.table==b.table && task.view.generation==b.generation && task.view.desiredState=="RUNNING" && grantMatches,"pending B task or grant changed")
        val budgetRow=query(c,"SELECT attempts FROM pekko_cdc_b_budget_v1 WHERE task_id=? FOR UPDATE",b.taskId).headOption
        val budget=budgetRow.map(_("attempts").toInt).getOrElse(0)
        if(budget>=3)throw new CdcPlatformException("AUTOMATIC_RECOVERY_EXHAUSTED")
        if(budgetRow.isEmpty)update(c,"INSERT INTO pekko_cdc_b_budget_v1 VALUES(?,1)",b.taskId)
        else update(c,"UPDATE pekko_cdc_b_budget_v1 SET attempts=? WHERE task_id=?",budget+1,b.taskId)
        val id=UUID.randomUUID().toString
        update(c,"INSERT INTO pekko_cdc_b_recovery_v1(owner_attempt,permit_id,task_id,capture_id,offset_json,marker_present,table_name,generation,operation_id,signed_proof,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",attempt,id,b.taskId,b.captureId,b.offset,b.markerPresent,b.table,b.generation,proof.operationId,proof.signedProof,now)
        grants.foreach(g=>update(c,"UPDATE pekko_cdc_grants_v1 SET status='RELEASED',finished_at=? WHERE grant_id=?",now,g("grant_id")))
        otherTasks.foreach {case(_,t)=>
          update(c,"UPDATE pekko_cdc_tasks_v1 SET runtime_health='WAITING',release_status='RELEASED',grant_id=NULL,grant_owner=NULL,continuation_json=NULL,continuation_owner=NULL,due_at=?,schedulable=TRUE,observed_at=? WHERE task_id=?",now,now,t.view.taskId)
        }
        update(c,"UPDATE pekko_cdc_b_recovery_v1 SET consumed=TRUE WHERE claimed_by=? AND task_id=? AND consumed=FALSE",attempt,b.taskId)
        update(c,"UPDATE pekko_cdc_tasks_v1 SET phase='QUEUED',runtime_health='WAITING',release_status='RELEASED',grant_id=NULL,grant_owner=NULL,continuation_json=NULL,continuation_owner=NULL,due_at=?,schedulable=TRUE,observed_at=? WHERE task_id=?",now,now,b.taskId)
        update(c,"UPDATE pekko_cdc_owner_v1 SET status='STOPPED',updated_at=? WHERE platform_id='default'",now)
        permit(query(c,"SELECT * FROM pekko_cdc_b_recovery_v1 WHERE owner_attempt=?",attempt).head)
    }
  }
  private[cdc] def releaseEmptyDrainedOwner(attempt:String,proof:CdcManagedIsolation.Verified,now:Long):Boolean=transaction {c=>
    val old=owner(c)
    val noGrants=query(c,"SELECT 1 FROM pekko_cdc_grants_v1 WHERE status IN ('ACTIVE','UNKNOWN') LIMIT 1").isEmpty
    val noPending=query(c,"SELECT 1 FROM pekko_cdc_b_recovery_v1 WHERE claimed_by=? AND consumed=FALSE LIMIT 1",attempt).isEmpty
    if(old("owner_attempt")!=attempt || !Set("ACTIVE","UNKNOWN")(old("status")) || !noGrants || !noPending ||
      !managedOn(c,attempt).contains(proof.managed) || !query(c,"SELECT manifest_json FROM pekko_cdc_managed_manifest_v1 WHERE owner_attempt=?",attempt).headOption.exists(_("manifest_json")==proof.manifest))false
    else {update(c,"UPDATE pekko_cdc_owner_v1 SET status='STOPPED',updated_at=? WHERE platform_id='default'",now);true}
  }
  private[cdc] def recordManagedManifest(attempt:String,manifest:String,now:Long):Unit=transaction {c=>
    val current=owner(c)
    require(current("status")=="ACTIVE" && current("owner_attempt")==attempt && managedOn(c,attempt).nonEmpty,"managed owner is not active")
    require(manifest.nonEmpty && manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=65536,"invalid managed manifest")
    val old=query(c,"SELECT manifest_json FROM pekko_cdc_managed_manifest_v1 WHERE owner_attempt=?",attempt).headOption
    old match {
      case Some(r)=>require(r("manifest_json")==manifest,"managed manifest changed")
      case None=>update(c,"INSERT INTO pekko_cdc_managed_manifest_v1 VALUES(?,?,?)",attempt,manifest,now)
    }
  }
  def beginOwner(attempt:String,now:Long,managed:Option[CdcManagedOwner]=None):Boolean=try transaction {c=>
    require(attempt.matches("[A-Za-z0-9_-]{1,128}"))
    val old=owner(c)
    val active=query(c,"SELECT COUNT(*) AS n FROM pekko_cdc_grants_v1 WHERE status IN ('ACTIVE','UNKNOWN')").head("n").toLong
    val pending=query(c,"SELECT permit_id FROM pekko_cdc_b_recovery_v1 WHERE consumed=FALSE LIMIT 1").nonEmpty
    val claimed=query(c,"SELECT permit_id FROM pekko_cdc_b_recovery_v1 WHERE consumed=FALSE AND claimed_by IS NOT NULL LIMIT 1").nonEmpty
    if(old("status")=="STOPPED" && active==0 && !claimed && (managed.nonEmpty || !pending)){
      require(managedOn(c,attempt).isEmpty,"owner attempt cannot be reused")
      managed.foreach {m=>
        require(Vector(m.bootId,m.deploymentId,m.hostId,m.guardianBoot,m.guardianPublicKey,m.startIdentity,m.profileHash).forall(_.nonEmpty) && m.pid>0,"invalid managed owner identity")
        update(c,"INSERT INTO pekko_cdc_managed_owner_v1 VALUES(?,?,?,?,?,?,?,?,?)",attempt,m.bootId,m.deploymentId,m.hostId,m.guardianBoot,m.guardianPublicKey,m.pid,m.startIdentity,m.profileHash)
      }
      if(managed.nonEmpty)update(c,"UPDATE pekko_cdc_b_recovery_v1 SET claimed_by=? WHERE claimed_by IS NULL AND consumed=FALSE",attempt)
      update(c,"UPDATE pekko_cdc_owner_v1 SET owner_attempt=?,status='ACTIVE',updated_at=? WHERE platform_id='default'",attempt,now);true
    } else if(old("owner_attempt")==attempt && old("status")=="ACTIVE") {
      require(managedOn(c,attempt)==managed,"managed owner binding changed");true
    } else false
  } catch {
    case e:CdcExecutionException if e.failure.code==ExecutionFailure.CommitUnknown && e.failure.releaseConfirmed && managed.nonEmpty=>
      val exact=try transaction {c=>
        val current=owner(c)
        current("status")=="ACTIVE" && current("owner_attempt")==attempt && managedOn(c,attempt)==managed
      } catch {case scala.util.control.NonFatal(_)=>false}
      if(exact)true else throw e
  }
  def ownerStatus:String=connection(c=>query(c,"SELECT status FROM pekko_cdc_owner_v1 WHERE platform_id='default'").head("status"))
  def endOwner(attempt:String,confirmed:Boolean,now:Long):Boolean=transaction {c=>
    val old=owner(c)
    if(old("owner_attempt")!=attempt)false
    else {
      val active=query(c,"SELECT COUNT(*) AS n FROM pekko_cdc_grants_v1 WHERE status IN ('ACTIVE','UNKNOWN')").head("n").toLong
      val pending=query(c,"SELECT COUNT(*) AS n FROM pekko_cdc_b_recovery_v1 WHERE claimed_by=? AND consumed=FALSE",attempt).head("n").toLong
      val clean=confirmed && active==0 && pending==0
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
    connection {c=>val rows=query(c,"SELECT t.*,EXISTS(SELECT 1 FROM pekko_cdc_b_recovery_v1 p WHERE p.task_id=t.task_id AND p.consumed=FALSE) AS recovery_pending FROM pekko_cdc_tasks_v1 t WHERE t.task_id>? ORDER BY t.task_id LIMIT ?",after.getOrElse(""),limit+1).map(record).map(_.view)
      CdcTaskPage(rows.take(limit),if(rows.size>limit)rows.take(limit).lastOption.map(_.taskId)else None)}
  }
  def cancel(id:String,now:Long):CdcTaskView={validateId(id);transaction {c=>
    owner(c)
    val task=row(c,id,true).getOrElse(throw new CdcPlatformException("NOT_FOUND"));val v=task.view
    if(v.phase=="PAUSED" || v.releaseStatus=="UNKNOWN")throw new CdcPlatformException("CANCEL_NOT_PERMITTED")
    if(v.desiredState!="CANCELED" && v.phase!="CANCELED"){
      if(query(c,"SELECT permit_id FROM pekko_cdc_b_recovery_v1 WHERE task_id=? AND consumed=FALSE LIMIT 1",id).nonEmpty)
        throw new CdcPlatformException("CANCEL_NOT_PERMITTED")
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
    val current=owner(c)
    requireGrant(c,grant)
    update(c,"UPDATE pekko_cdc_grants_v1 SET status='UNKNOWN' WHERE grant_id=?",grant.id)
    update(c,"UPDATE pekko_cdc_tasks_v1 SET phase='PAUSED',runtime_health='UNKNOWN',release_status='UNKNOWN',failure_code=?,observed_at=?,schedulable=FALSE WHERE task_id=?",code,now,grant.task.view.taskId)
    if(current("owner_attempt")==grant.owner && managedOn(c,grant.owner).nonEmpty)
      update(c,"UPDATE pekko_cdc_owner_v1 SET status='UNKNOWN',updated_at=? WHERE platform_id='default'",now)
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
