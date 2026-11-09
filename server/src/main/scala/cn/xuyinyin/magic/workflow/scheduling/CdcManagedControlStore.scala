package cn.xuyinyin.magic.workflow.scheduling

import javax.sql.DataSource
import spray.json._

object CdcManagedControlStore {
  /** Stable across supervisor restarts; credentials and mutable epochs are excluded. */
  final case class CutoverIdentity(deploymentId:String,hostId:String,operationId:String) {
    require(deploymentId.nonEmpty && deploymentId.length<=255 && hostId.nonEmpty && hostId.length<=128)
    require(operationId.matches("[A-Za-z0-9-]{1,128}"))
    def policyHash(limits:SchedulingLimits):String =
      cn.xuyinyin.magic.workflow.snapshot.SnapshotSplitState.sha256(
        JsArray(JsString("cdc-control-cutover-v1"),JsString(limits.toString),JsString(deploymentId),JsString(hostId),JsString(operationId)).compactPrint)
  }
}

/** CDC control storage; the legacy admission row remains the inter-version lock. */
final class CdcManagedControlStore(dataSource:DataSource,val limits:SchedulingLimits,cutover:Option[CdcManagedControlStore.CutoverIdentity]=None)
  extends ManagedControlTransactions(dataSource,cutover.fold(cn.xuyinyin.magic.workflow.snapshot.SnapshotSplitState.sha256(limits.toString))(_.policyHash(limits)),
    allowPolicyInitialization=cutover.isEmpty) {
  val ownership:ManagedCdcOwnerStore=new ManagedCdcOwnerStore(this,limits.candidatePage)
}

/** Release legacy grants only after the same durable isolation and drain checks as the old entry. */
final class ManagedCdcOwnerStore private[scheduling](override protected val store:ManagedControlTransactions,pageLimit:Int)
  extends ManagedOwnerStore(store) {
  import store.{query,update,transaction}
  def releaseDrained(id:String,limit:Int):Int=transaction { c=>
    if(!owner(c,id).exists(_.phase=="db_drained"))0 else {
      val tasks=query(c,"SELECT workflow_id,grant_id FROM pekko_table_tasks WHERE owner_id=? AND phase IN ('held','recovering','running','drained','unknown') ORDER BY workflow_id LIMIT ?",Seq(id,math.min(math.max(1,limit),pageLimit)))(r=>(r.getString(1),r.getString(2)))
      tasks.foreach {case(workflow,grant)=>
        update(c,"UPDATE pekko_table_tasks SET phase='ready',grant_id=NULL,owner_id=NULL,queue_order=? WHERE workflow_id=? AND owner_id=? AND grant_id=?",store.next(c),workflow,id,grant)
        audit(c,id,Some(workflow),"old_quota_released",JsObject("grant"->JsString(grant)).compactPrint)
      };tasks.size
    }
  }
  def hasOutstandingQuota(id:String):Boolean=store.read(c=>query(c,"SELECT workflow_id FROM pekko_table_tasks WHERE owner_id=? AND phase IN ('held','recovering','running','drained','unknown') LIMIT 1",Seq(id))(_.getString(1)).nonEmpty)
}
