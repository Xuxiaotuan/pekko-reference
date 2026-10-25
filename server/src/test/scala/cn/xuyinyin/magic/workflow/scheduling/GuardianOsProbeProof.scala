package cn.xuyinyin.magic.workflow.scheduling

import spray.json._

/** Laboratory acceptance only. Production provider validation is deliberately unchanged. */
object GuardianOsProbeProof {
  def verify(raw:String,pin:String,binding:JsObject,operation:String):JsObject={
    val value=SnapshotGuardianWire.verified(raw.parseJson.asJsObject,pin)
    require(value.fields.get("provider").contains(JsString("test-systemd-cgroup-v2")),"not a laboratory OS proof")
    require(value.fields.get("binding").contains(binding) && value.fields.get("operation").contains(JsString(operation)),"OS boundary or operation mismatch")
    Vector("startSealed","stopCompleted","subtreeEmpty","beforeMatched").foreach(k=>
      require(value.fields.get(k).contains(JsBoolean(true)),"unconfirmed OS boundary: "+k))
    value
  }

  /** Explicit test authority bridging a verified external proof to the unchanged production drain API.
    * No legacy Process receipt is created. Closing a ticket denies new admission; the OS stop,
    * not that metadata check, fences a launcher which already passed its pre-fork check.
    */
  def record(store:TableSchedulingStore,boot:String,raw:String,pin:String,binding:JsObject,operation:String):Unit={
    val proof=verify(raw,pin,binding,operation)
    val ha=store.ownership.get
    store.transaction { c=>
      val ticket=store.query(c,"SELECT guardian,phase FROM guardian_os_tickets WHERE boot=?",Seq(boot))(r=>(r.getString(1),r.getString(2))).head
      require(ticket._1==SnapshotGuardianWire.text(binding,"guardian") && ticket._2=="sealed","laboratory launch path remains open")
      ha.owner(boot).foreach { owner=>
        require(ha.beginRevocation(boot,operation)==operation)
        val current=ha.owner(boot).get
        if(current.phase=="revoking") {
          store.update(c,"UPDATE pekko_snapshot_owners SET phase='fenced',sealed_version=manifest_version,isolation_proof=?,updated_at=? WHERE owner_id=?",
            JsObject("provider"->JsString("test-systemd-cgroup-v2"),"proof"->proof).compactPrint,System.currentTimeMillis(),boot)
          ha.audit(c,boot,None,"fenced",JsObject("provider"->JsString("test-systemd-cgroup-v2"),"proof"->proof).compactPrint)
        }else require(Set("fenced","drain_wait","db_drained").contains(current.phase))
      }
      store.update(c,"UPDATE guardian_os_tickets SET proof=? WHERE boot=?",raw,boot)
    }
  }
}
