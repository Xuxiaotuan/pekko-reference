package cn.xuyinyin.magic.cdc.control

import cn.xuyinyin.magic.workflow.scheduling.{ScheduledTable, TableGrant}


import cn.xuyinyin.magic.cdc.control.SnapshotRecoveryPermit


import cn.xuyinyin.magic.cdc.guardian.{SnapshotOwnerIdentity}
import java.sql.{Connection,ResultSet}
import spray.json._
import spray.json.DefaultJsonProtocol._

// These control-plane records are deliberately NOT Journal/Snapshot payloads.

final case class SnapshotScheduledTrigger(scheduleId:String,scheduledAt:Long,triggerId:String,executionId:String,disposition:String,recordedAt:Long)

/** All authorization changes use the existing short admission transaction. No external I/O runs under that lock. */
final class SnapshotOwnershipStore private[control](override protected val store:TableSchedulingStore) extends ManagedOwnerStore(store) {
  import store.{query,update,transaction}
  private def hash(g:TableGrant)=cn.xuyinyin.magic.cdc.snapshot.SnapshotSplitState.sha256(g.task.definition.workflowJson)
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
  private[control] def canRelease(c:Connection,t:ScheduledTable,caller:Option[String]):Boolean=caller.exists(id=>active(c,id) &&
    (if(t.owner.isEmpty)t.state=="held" else t.owner.contains(id) && permit(c,t.workflowId).exists(p=>p.owner==id && t.grantId.contains(p.grantId))))
  private[control] def released(c:Connection,t:ScheduledTable,disposition:String,confirmed:Boolean):Unit={
    if(confirmed)update(c,"UPDATE pekko_snapshot_writer_permissions SET phase='parked' WHERE workflow_id=? AND grant_id=?",t.workflowId,t.grantId.orNull)
    audit(c,t.owner.getOrElse("unbound"),Some(t.workflowId),if(confirmed)"quota_released" else "quota_unknown",JsObject("grant"->JsString(t.grantId.get),"disposition"->JsString(disposition)).compactPrint)
  }
}
