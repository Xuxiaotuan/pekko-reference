package cn.xuyinyin.magic.workflow.scheduling

import java.sql.{Connection,ResultSet}
import spray.json._
import spray.json.DefaultJsonProtocol._

// These control-plane records are deliberately NOT Journal/Snapshot payloads.
final case class SnapshotOwnerIdentity(bootId:String,deploymentId:String,pid:Long,startIdentity:String) {
  require(bootId.matches("[A-Za-z0-9-]{1,128}") && deploymentId.nonEmpty && deploymentId.length<=255 && pid>0 && startIdentity.nonEmpty)
}
final case class SnapshotOwner(identity:SnapshotOwnerIdentity,phase:String,operation:Option[String],manifest:Long,sealedManifest:Option[Long],address:Option[String])
final case class SnapshotIsolationReceipt(operationId:String,identity:SnapshotOwnerIdentity,provider:String,confirmedAt:Long,details:String)
final case class SnapshotDatabaseObservation(instanceId:String,complete:Boolean,sessions:Int,transactions:Int,unattributed:Int,evidence:String,observedAt:Long,endedSessions:Vector[Long]=Vector.empty) {
  def empty:Boolean=complete && sessions==0 && transactions==0 && unattributed==0
}
final case class SnapshotRecoveryPermit(workflowId:String,owner:String,generation:Long,id:String,grantId:String,executionId:String,revision:Long,definitionHash:String,phase:String)

final case class SnapshotScheduledTrigger(scheduleId:String,scheduledAt:Long,triggerId:String,executionId:String,disposition:String,recordedAt:Long)

/** All authorization changes use the existing short admission transaction. No external I/O runs under that lock. */
final class SnapshotOwnershipStore private[scheduling](store:TableSchedulingStore) {
  import store.{query,update,transaction}
  private def now=System.currentTimeMillis()
  private def hash(g:TableGrant)=cn.xuyinyin.magic.workflow.snapshot.SnapshotSplitState.sha256(g.task.definition.workflowJson)
  private def decodeOwner(r:ResultSet)=SnapshotOwner(SnapshotOwnerIdentity(r.getString("owner_id"),r.getString("deployment_id"),r.getLong("process_id"),r.getString("start_identity")),
    r.getString("phase"),Option(r.getString("operation_id")),r.getLong("manifest_version"),Option(r.getObject("sealed_version")).map(_=>r.getLong("sealed_version")),Option(r.getString("cluster_address")))
  private def owner(c:Connection,id:String)=query(c,"SELECT * FROM pekko_snapshot_owners WHERE owner_id=?",Seq(id))(decodeOwner).headOption
  def owner(id:String):Option[SnapshotOwner]=store.read(owner(_,id))
  def owners(after:String,limit:Int):Vector[SnapshotOwner]=store.read(c=>query(c,"SELECT * FROM pekko_snapshot_owners WHERE owner_id>? ORDER BY owner_id LIMIT ?",Seq(after,math.min(math.max(limit,1),100)))(decodeOwner))
  private def active(c:Connection,id:String)=owner(c,id).exists(_.phase=="active")
  private[scheduling] def audit(c:Connection,id:String,workflow:Option[String],kind:String,details:String):Unit={
    update(c,"INSERT INTO pekko_snapshot_ownership_audit VALUES (?,?,?,?,?,?,?)",java.util.UUID.randomUUID().toString,id,workflow.orNull,kind,details,now,store.next(c));()
  }
  def registerOwner(identity:SnapshotOwnerIdentity):Unit=transaction(registerOwner(_,identity))
  private def registerOwner(c:Connection,identity:SnapshotOwnerIdentity):Unit={
    owner(c,identity.bootId) match {
      case Some(existing) => require(existing.identity==identity && existing.phase=="active","owner boot identity cannot be reused or revived")
      case None =>
        update(c,"INSERT INTO pekko_snapshot_owners (owner_id,deployment_id,process_id,start_identity,phase,manifest_version,updated_at) VALUES (?,?,?,?,'active',0,?)",identity.bootId,identity.deploymentId,identity.pid,identity.startIdentity,now)
        audit(c,identity.bootId,None,"owner_registered","{}")
    }
  }
  /** Owner visibility and its first control connection commit together: no recoverable empty manifest. */
  def registerBootstrapOwner(identity:SnapshotOwnerIdentity):Unit=transaction { c=>
    registerOwner(c,identity)
    val (uuid,started,id)=SnapshotOwnerJdbc.databaseIdentity(c)
    registerSession(c,identity.bootId,SnapshotOwnerJdbc.endpoint(c.getMetaData.getURL),uuid,started,id,"control",SnapshotOwnerJdbc.databaseUser(c))
  }
  /** Serializes against a delayed worker registration, retaining an immutable boot tombstone. */
  def recordFailedStart(receipt:SnapshotIsolationReceipt):Boolean=transaction { c=>
    require(receipt.provider=="managed-child-jvm")
    owner(c,receipt.identity.bootId) match {
      case Some(_)=>false // A worker registered first: its database manifest must follow the normal drain path.
      case None=>
        val id=receipt.identity
        update(c,"INSERT INTO pekko_snapshot_owners (owner_id,deployment_id,process_id,start_identity,phase,manifest_version,sealed_version,operation_id,isolation_proof,updated_at) VALUES (?,?,?,?,'startup_failed',0,0,?,?,?)",
          id.bootId,id.deploymentId,id.pid,id.startIdentity,receipt.operationId,receipt.details,now)
        audit(c,id.bootId,None,"startup_failed",JsObject("pid"->JsNumber(id.pid),"startIdentity"->JsString(id.startIdentity),"proof"->receipt.details.parseJson).compactPrint);true
    }
  }
  def setAddress(id:String,address:String):Unit=transaction { c=>
    require(active(c,id),"owner is not active");require(address.length<=255)
    val old=owner(c,id).get.address;require(old.forall(_==address),"owner address changed")
    update(c,"UPDATE pekko_snapshot_owners SET cluster_address=? WHERE owner_id=?",address,id);()
  }
  private def instance(c:Connection,id:String,key:String)=query(c,"SELECT server_uuid,server_started FROM pekko_snapshot_owner_instances WHERE owner_id=? AND instance_id=?",Seq(id,key))(r=>(r.getString(1),r.getLong(2))).headOption
  private def registerInstance(c:Connection,id:String,key:String,uuid:String,started:Long):Unit={
    if(!active(c,id))throw new IllegalStateException("owner is revoked; no new database resources may be opened")
    require(key.nonEmpty && key.length<=128 && uuid.nonEmpty)
    val incompatible=query(c,"SELECT owner_id FROM pekko_snapshot_owner_instances WHERE instance_id=? AND (server_uuid<>? OR ABS(server_started-?)>3000) LIMIT 1",Seq(key,uuid,started))(_.getString(1))
    require(incompatible.isEmpty,"fixed database deployment changed; automatic takeover is paused")
    instance(c,id,key) match {
      case Some((u,t))=>require(u==uuid && math.abs(t-started)<=3000,"database instance restarted or changed")
      case None=>
        update(c,"INSERT INTO pekko_snapshot_owner_instances (owner_id,instance_id,server_uuid,server_started,database_users,empty_rounds,complete) VALUES (?,?,?,?,'[]',0,false)",id,key,uuid,started)
        update(c,"UPDATE pekko_snapshot_owners SET manifest_version=manifest_version+1 WHERE owner_id=?",id)
        audit(c,id,None,"instance_registered",JsObject("instance"->JsString(key)).compactPrint)
    }
  }
  def registerInstance(id:String,key:String,uuid:String,started:Long):Unit=transaction(registerInstance(_,id,key,uuid,started))
  def registerSession(id:String,key:String,uuid:String,started:Long,connectionId:Long,role:String,databaseUser:String=""):Unit=transaction(registerSession(_,id,key,uuid,started,connectionId,role,databaseUser))
  private def registerSession(c:Connection,id:String,key:String,uuid:String,started:Long,connectionId:Long,role:String,databaseUser:String):Unit={
    registerInstance(c,id,key,uuid,started)
    if(databaseUser.nonEmpty) {
      require(databaseUser.length<=128)
      val users=query(c,"SELECT database_users FROM pekko_snapshot_owner_instances WHERE owner_id=? AND instance_id=?",Seq(id,key))(_.getString(1)).head.parseJson.asInstanceOf[JsArray].elements.map(_.convertTo[String])
      val updated=(users:+databaseUser).distinct.sorted
      require(updated.size<=64,"fixed managed database account inventory exceeded")
      if(updated!=users)update(c,"UPDATE pekko_snapshot_owner_instances SET database_users=? WHERE owner_id=? AND instance_id=?",JsArray(updated.map(JsString(_))).compactPrint,id,key)
    }
    val existing=query(c,"SELECT role_name FROM pekko_snapshot_owner_sessions WHERE owner_id=? AND instance_id=? AND connection_id=?",Seq(id,key,connectionId))(_.getString(1))
    if(existing.isEmpty) {
      require(query(c,"SELECT COUNT(*) FROM pekko_snapshot_owner_sessions WHERE owner_id=?",Seq(id))(_.getLong(1)).head<4096,"unconfirmed connection registry capacity exhausted")
      update(c,"INSERT INTO pekko_snapshot_owner_sessions VALUES (?,?,?,?,?)",id,key,connectionId,role,now)
      audit(c,id,None,"session_registered",JsObject("instance"->JsString(key),"connection"->JsNumber(connectionId),"role"->JsString(role)).compactPrint)
    }
    else require(existing.head==role,"connection role changed")
  }
  private[scheduling] def registerControlSession(c:Connection,id:String):Unit={
    val (uuid,started,connectionId)=SnapshotOwnerJdbc.databaseIdentity(c)
    store.transactionOn(c)(x=>registerSession(x,id,SnapshotOwnerJdbc.endpoint(c.getMetaData.getURL),uuid,started,connectionId,"control",SnapshotOwnerJdbc.databaseUser(c)))
  }
  def instances(id:String):Vector[(String,String,Long)]=store.read(c=>query(c,"SELECT instance_id,server_uuid,server_started FROM pekko_snapshot_owner_instances WHERE owner_id=? ORDER BY instance_id",Seq(id))(r=>(r.getString(1),r.getString(2),r.getLong(3))))
  def databaseUsers(id:String,key:String):Set[String]=store.read(c=>query(c,"SELECT database_users FROM pekko_snapshot_owner_instances WHERE owner_id=? AND instance_id=?",Seq(id,key))(_.getString(1)).head.parseJson.asInstanceOf[JsArray].elements.map(_.convertTo[String]).toSet)
  def knownSessions(id:String,key:String):Vector[Long]=store.read(c=>query(c,"SELECT connection_id FROM pekko_snapshot_owner_sessions WHERE owner_id=? AND instance_id=? ORDER BY connection_id",Seq(id,key))(_.getLong(1)))
  def forgetEndedSessions(id:String,key:String,ended:Vector[Long]):Unit=transaction { c=>
    ended.take(1024).foreach(n=>update(c,"DELETE FROM pekko_snapshot_owner_sessions WHERE owner_id=? AND instance_id=? AND connection_id=?",id,key,n));()
  }
  def beginRevocation(id:String,operation:String):String=transaction { c=>
    val current=owner(c,id).getOrElse(throw new IllegalArgumentException("unknown owner"))
    current.operation match {
      case Some(existing)=>existing
      case None=>
        require(current.phase=="active" && operation.nonEmpty && operation.length<=64)
        update(c,"UPDATE pekko_snapshot_owners SET phase='revoking',operation_id=?,updated_at=? WHERE owner_id=?",operation,now,id)
        audit(c,id,None,"revoking",JsObject("operation"->JsString(operation)).compactPrint);operation
    }
  }
  def confirmIsolation(receipt:SnapshotIsolationReceipt):Boolean=transaction { c=>
    owner(c,receipt.identity.bootId) match {
      case Some(o) if o.identity==receipt.identity && o.operation.contains(receipt.operationId) && Set("revoking","fenced","drain_wait","db_drained").contains(o.phase) && receipt.provider=="managed-child-jvm" =>
        if(o.phase=="revoking") {
          val proof=JsObject("operation"->JsString(receipt.operationId),"owner"->JsString(o.identity.bootId),"pid"->JsNumber(o.identity.pid),
            "startIdentity"->JsString(o.identity.startIdentity),"deployment"->JsString(o.identity.deploymentId),"provider"->JsString(receipt.provider),"confirmedAt"->JsNumber(receipt.confirmedAt),"details"->receipt.details.parseJson)
          update(c,"UPDATE pekko_snapshot_owners SET phase='fenced',sealed_version=manifest_version,isolation_proof=?,updated_at=? WHERE owner_id=?",proof.compactPrint,now,o.identity.bootId)
          audit(c,o.identity.bootId,None,"fenced",proof.compactPrint)
        };true
      case _=>false
    }
  }
  def recordObservation(id:String,o:SnapshotDatabaseObservation,minIntervalMillis:Long):Boolean=transaction { c=>
    require(minIntervalMillis>0)
    owner(c,id) match {
      case Some(current) if Set("fenced","drain_wait").contains(current.phase) && current.sealedManifest.contains(current.manifest) =>
        val previous=query(c,"SELECT empty_since,empty_rounds FROM pekko_snapshot_owner_instances WHERE owner_id=? AND instance_id=?",Seq(id,o.instanceId))(r=>(Option(r.getObject(1)).map(_=>r.getLong(1)),r.getInt(2))).headOption
        previous match {
          case None=>false
          case Some((since,rounds))=>
            val nextRounds=if(!o.empty)0 else if(since.isEmpty)1 else if(o.observedAt-since.get>=minIntervalMillis)math.min(2,rounds+1) else rounds
            update(c,"UPDATE pekko_snapshot_owner_instances SET complete=?,empty_since=?,empty_rounds=?,observation=? WHERE owner_id=? AND instance_id=?",
              o.complete,if(!o.empty)null else since.getOrElse(o.observedAt),nextRounds,o.evidence,id,o.instanceId)
            val total=query(c,"SELECT COUNT(*) FROM pekko_snapshot_owner_instances WHERE owner_id=?",Seq(id))(_.getLong(1)).head
            val pending=query(c,"SELECT COUNT(*) FROM pekko_snapshot_owner_instances WHERE owner_id=? AND (complete=false OR empty_rounds<2)",Seq(id))(_.getLong(1)).head
            val drained=total>0 && pending==0
            update(c,"UPDATE pekko_snapshot_owners SET phase=?,updated_at=? WHERE owner_id=?",if(drained)"db_drained" else "drain_wait",now,id)
            audit(c,id,None,if(drained)"db_drained" else "database_observed",JsObject("instance"->JsString(o.instanceId),"complete"->JsBoolean(o.complete),"sessions"->JsNumber(o.sessions),"transactions"->JsNumber(o.transactions),"unattributed"->JsNumber(o.unattributed),"emptyRounds"->JsNumber(nextRounds),"evidence"->o.evidence.parseJson).compactPrint)
            drained
        }
      case Some(o) if o.phase=="db_drained"=>true
      case _=>false
    }
  }
  def releaseDrained(id:String,limit:Int):Int=transaction { c=>
    if(!owner(c,id).exists(_.phase=="db_drained"))0 else {
      val tasks=query(c,"SELECT workflow_id,grant_id FROM pekko_table_tasks WHERE owner_id=? AND phase IN ('held','recovering','running','drained','unknown') ORDER BY workflow_id LIMIT ?",Seq(id,math.min(math.max(1,limit),store.limits.candidatePage)))(r=>(r.getString(1),r.getString(2)))
      tasks.foreach { case (workflow,grant)=>
        update(c,"UPDATE pekko_table_tasks SET phase='ready',grant_id=NULL,owner_id=NULL,queue_order=? WHERE workflow_id=? AND owner_id=? AND grant_id=?",store.next(c),workflow,id,grant)
        audit(c,id,Some(workflow),"old_quota_released",JsObject("grant"->JsString(grant)).compactPrint)
      };tasks.size
    }
  }
  def hasOutstandingQuota(id:String):Boolean=store.read(c=>query(c,"SELECT workflow_id FROM pekko_table_tasks WHERE owner_id=? AND phase IN ('held','recovering','running','drained','unknown') LIMIT 1",Seq(id))(_.getString(1)).nonEmpty)
  private def decodeTrigger(r:ResultSet)=SnapshotScheduledTrigger(r.getString("schedule_id"),r.getLong("scheduled_at"),r.getString("trigger_id"),r.getString("execution_id"),r.getString("disposition"),r.getLong("recorded_at"))
  def scheduledTrigger(execution:String):Option[SnapshotScheduledTrigger]=store.read(c=>query(c,"SELECT * FROM pekko_snapshot_schedule_triggers WHERE execution_id=? AND disposition='accepted' LIMIT 1",Seq(execution))(decodeTrigger).headOption)
  def skippedTriggers(workflow:String):Vector[SnapshotScheduledTrigger]=store.read(c=>query(c,"SELECT * FROM pekko_snapshot_schedule_triggers WHERE workflow_id=? AND disposition='skipped' ORDER BY recorded_at DESC LIMIT 50",Seq(workflow))(decodeTrigger))
  def submitScheduled(workflow:String,schedule:String,at:Long,trigger:String,seedWatermark:Option[Long],execution:String):(String,String)=transaction { c=>
    require(schedule.nonEmpty && schedule.length<=255 && trigger.nonEmpty && trigger.length<=255 && at>0)
    val task=store.get(c,workflow).getOrElse(throw new IllegalArgumentException("managed workflow is not registered"))
    val existing=query(c,"SELECT * FROM pekko_snapshot_schedule_triggers WHERE workflow_id=? AND trigger_id=?",Seq(workflow,trigger))(decodeTrigger).headOption
    val recorded=query(c,"SELECT scheduled_at FROM pekko_snapshot_schedule_triggers WHERE workflow_id=? AND schedule_id=? ORDER BY scheduled_at DESC LIMIT 1",Seq(workflow,schedule))(_.getLong(1)).headOption
    val watermark=(recorded.toVector++seedWatermark.toVector).reduceOption((left:Long,right:Long)=>math.max(left,right))
    if(existing.nonEmpty || watermark.exists(_>=at))("duplicate",s"$schedule-$at")
    else {
      val available=Set("registered","completed").contains(task.state)
      val id=if(available)execution else task.executionId.getOrElse(throw new IllegalStateException("unfinished task has no execution"))
      val disposition=if(available)"accepted" else "skipped"
      update(c,"INSERT INTO pekko_snapshot_schedule_triggers VALUES (?,?,?,?,?,?,?)",workflow,schedule,at,trigger,id,disposition,now)
      if(available)store.enqueueOn(c,task.definition,id,trigger,0L)
      audit(c,"scheduler",Some(workflow),"schedule_"+disposition,JsObject("schedule"->JsString(schedule),"scheduledAt"->JsNumber(at),"trigger"->JsString(trigger),"execution"->JsString(id)).compactPrint)
      (disposition,id)
    }
  }
  private def permit(c:Connection,id:String)=query(c,"SELECT * FROM pekko_snapshot_writer_permissions WHERE workflow_id=?",Seq(id))(r=>SnapshotRecoveryPermit(r.getString("workflow_id"),r.getString("owner_id"),r.getLong("generation"),r.getString("permit_id"),r.getString("grant_id"),r.getString("execution_id"),r.getLong("revision"),r.getString("definition_hash"),r.getString("phase"))).headOption
  def permit(id:String):Option[SnapshotRecoveryPermit]=store.read(permit(_,id))
  def acquire(g:TableGrant,id:String):Option[SnapshotRecoveryPermit]=transaction { c=>
    val task=store.get(c,g.task.workflowId)
    val previous=permit(c,g.task.workflowId)
    if(!active(c,id) || !task.exists(t=>t.grantId.contains(g.id) && t.definition==g.task.definition && t.executionId==g.task.executionId && t.owner.forall(_==id) && Set("held","recovering","running").contains(t.state)) ||
      previous.exists(p=>p.owner!=id && !owner(c,p.owner).exists(_.phase=="db_drained")))None
    else if(previous.exists(p=>p.owner==id && p.grantId==g.id && Set("recovering","active").contains(p.phase)))previous
    else if(task.get.state!="held" || store.active(c).count(_.owner.contains(id))>=store.limits.maxTablesPerNode)None
    else {
      val generation=previous.fold(1L)(p=>if(p.owner==id)p.generation else Math.addExact(p.generation,1L))
      val p=SnapshotRecoveryPermit(g.task.workflowId,id,generation,java.util.UUID.randomUUID().toString,g.id,g.task.executionId.get,g.task.definition.revision,hash(g),"recovering")
      update(c,"DELETE FROM pekko_snapshot_writer_permissions WHERE workflow_id=?",p.workflowId)
      update(c,"INSERT INTO pekko_snapshot_writer_permissions VALUES (?,?,?,?,?,?,?,?,?,?)",p.workflowId,p.owner,p.generation,p.id,p.grantId,p.executionId,p.revision,p.definitionHash,p.phase,previous.map(_.owner).filter(_!=id).orNull)
      update(c,"UPDATE pekko_table_tasks SET phase='recovering',owner_id=? WHERE workflow_id=? AND grant_id=?",id,p.workflowId,g.id)
      audit(c,id,Some(p.workflowId),"recovery_permitted",JsObject("permit"->JsString(p.id),"grant"->JsString(p.grantId),"generation"->JsNumber(p.generation),"execution"->JsString(p.executionId)).compactPrint)
      Some(p)
    }
  }
  def activate(g:TableGrant,id:String):Boolean=transaction { c=>
    val p=permit(c,g.task.workflowId)
    if(!active(c,id) || !p.exists(v=>v.owner==id && v.grantId==g.id && v.executionId==g.task.executionId.get && v.revision==g.task.definition.revision && v.definitionHash==hash(g) && Set("recovering","active").contains(v.phase)))false
    else store.get(c,g.task.workflowId) match {
      case Some(t) if t.grantId.contains(g.id) && t.owner.contains(id) && Set("recovering","running").contains(t.state)=>
        if(t.state!="running") {
          update(c,"UPDATE pekko_table_tasks SET phase='running' WHERE workflow_id=? AND grant_id=?",t.workflowId,g.id)
          update(c,"UPDATE pekko_snapshot_writer_permissions SET phase='active' WHERE workflow_id=? AND permit_id=?",t.workflowId,p.get.id)
          audit(c,id,Some(t.workflowId),"writer_active",JsObject("grant"->JsString(g.id),"permit"->JsString(p.get.id)).compactPrint)
        };true
      case _=>false
    }
  }
  private[scheduling] def canRelease(c:Connection,t:ScheduledTable,caller:Option[String]):Boolean=caller.exists(id=>active(c,id) &&
    (if(t.owner.isEmpty)t.state=="held" else t.owner.contains(id) && permit(c,t.workflowId).exists(p=>p.owner==id && t.grantId.contains(p.grantId))))
  private[scheduling] def released(c:Connection,t:ScheduledTable,disposition:String,confirmed:Boolean):Unit={
    if(confirmed)update(c,"UPDATE pekko_snapshot_writer_permissions SET phase='parked' WHERE workflow_id=? AND grant_id=?",t.workflowId,t.grantId.orNull)
    audit(c,t.owner.getOrElse("unbound"),Some(t.workflowId),if(confirmed)"quota_released" else "quota_unknown",JsObject("grant"->JsString(t.grantId.get),"disposition"->JsString(disposition)).compactPrint)
  }
}
