package cn.xuyinyin.magic.cdc.control


import spray.json._

/** Only a CDC control store can construct this owner adapter. */
private[magic] object ManagedCdcOwnerStore {
  def forCdc(store:cn.xuyinyin.magic.cdc.control.CdcManagedControlStore):ManagedCdcOwnerStore=
    new ManagedCdcOwnerStore(store,store.limits.candidatePage)
}

/** Release legacy grants only after the same durable isolation and drain checks as the old entry. */
final class ManagedCdcOwnerStore private[control](override protected val store:ManagedControlTransactions,pageLimit:Int)
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
