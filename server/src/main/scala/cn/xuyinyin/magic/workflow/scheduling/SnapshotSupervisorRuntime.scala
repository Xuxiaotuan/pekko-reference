package cn.xuyinyin.magic.workflow.scheduling

import java.util.UUID
import spray.json._
import scala.util.control.NonFatal

/** Bounded synchronous control tick. External RPC/observations never execute inside SQL transactions. */
final class SnapshotSupervisorRuntime(val metadata:SnapshotSupervisorStore,val control:SupervisorControl,
  client:SnapshotGuardianWire.Client,observe:(String,(String,String,Long))=>SnapshotDatabaseObservation,
  profileHash:String,minObservationIntervalMillis:Long=250,event:(String,String)=>Unit=(_,_)=>()) {
  import SnapshotGuardianWire._
  require(minObservationIntervalMillis>0)
  private val ha=metadata.store.ownership.get
  private var own=Option.empty[String]
  private var nextBoot=UUID.randomUUID().toString
  def workerBoot:Option[String]=own
  private def confirmControl():Unit={
    val inventory=client.request("INVENTORY")
    require(!inventory.fields.values.exists(_.asJsObject.fields("status")==JsString("UNKNOWN")),"guardian original process capability lost")
  }
  private def readback[A](attempt: => A)(recorded: => Option[A]):A=try attempt catch {case NonFatal(e)=>recorded.getOrElse(throw e)}
  def tick():Unit=try {
    client.request("BIND")
    val inventory=client.request("INVENTORY")
    require(!inventory.fields.values.exists(_.asJsObject.fields("status")==JsString("UNKNOWN")),"guardian original process capability lost")
    own.foreach { boot=>
      val status=inventory.fields.get(boot).map(_.asJsObject.fields("status"))
      require(status.nonEmpty,"current worker inventory missing")
      if(status.contains(JsString("EXITED")))own=None
      else ha.owner(boot).filter(_.phase=="active").foreach { _=>
        ha.instances(boot).foreach { instance=>
          val observation=observe(boot,instance)
          if(observation.complete)metadata.fenced(control){ha.forgetEndedSessions(boot,instance._1,observation.endedSessions)}
        }
      }
    }
    val old=metadata.pending.filterNot(t=>own.contains(t.bootId))
    old.foreach { t=>
      val record=inventory.fields.getOrElse(t.bootId,throw new IllegalStateException("incomplete guardian inventory")).asJsObject
      if(record.fields("status")==JsString("NOT_STARTED")) {
        require(t.identity.isEmpty)
        client.request("CANCEL",JsObject("boot"->JsString(t.bootId)))
      }else {
        val id=record.fields("identity").convertTo[SnapshotOwnerIdentity];require(t.identity.contains(id))
        val operation=readback(metadata.operation(control,t.bootId))(metadata.launch(t.bootId).flatMap(_.operation))
        ha.owner(t.bootId).foreach { _=>
          readback(metadata.fenced(control){ha.beginRevocation(t.bootId,operation)})(ha.owner(t.bootId).flatMap(_.operation).filter(_==operation))
        }
        val proof=client.request("STOP",JsObject("boot"->JsString(t.bootId),"identity"->id.toJson,"operation"->JsString(operation)))
        val raw=text(proof,"proof");val receipt=verifyReceipt(raw,control.guardian,id,operation)
        event("PROOF_RECEIVED",t.bootId)
        readback(metadata.recordProof(control,t.bootId,raw){
          if(ha.owner(t.bootId).isEmpty) {
            if(!ha.recordFailedStart(receipt))throw new IllegalStateException("startup registered concurrently; retry normal drain")
          } else require(ha.confirmIsolation(receipt),"isolation proof rejected")
        })(metadata.launch(t.bootId).filter(_.proof.contains(raw)).flatMap(_=>ha.owner(t.bootId)).filter(o=>Set("startup_failed","fenced","drain_wait","db_drained").contains(o.phase)).map(_=>()))
        event("PROOF_RECORDED",t.bootId)
        confirmControl()
        ha.owner(t.bootId).foreach { owner=>
          if(Set("fenced","drain_wait").contains(owner.phase))ha.instances(t.bootId).foreach { instance=>
            val observation=observe(t.bootId,instance)
            metadata.fenced(control){ha.recordObservation(t.bootId,observation,minObservationIntervalMillis)}
            event("DATABASE_OBSERVED",t.bootId)
          }
        }
        if(ha.owner(t.bootId).exists(_.phase=="db_drained")) {
          confirmControl()
          readback(metadata.fenced(control){ha.releaseDrained(t.bootId,100)})(if(!ha.hasOutstandingQuota(t.bootId))Some(0)else None)
          event("QUOTA_RETURNED",t.bootId)
        }
        if(ha.owner(t.bootId).exists(o=>Set("db_drained","startup_failed").contains(o.phase)) && !ha.hasOutstandingQuota(t.bootId)) {
          readback(metadata.retire(control,t.bootId))(metadata.launch(t.bootId).filter(_.phase=="retired").map(_=>()))
          client.request("RETIRE",JsObject("boot"->JsString(t.bootId)));event("WORKER_RETIRED",t.bootId)
        }
      }
    }
    if(own.isEmpty && metadata.pending.isEmpty) {
      confirmControl()
      if(metadata.launch(nextBoot).nonEmpty)nextBoot=UUID.randomUUID().toString
      val ticket=readback(metadata.prepare(control,nextBoot,profileHash))(metadata.launch(nextBoot).filter(t=>t.profileHash==profileHash && t.phase=="prepared"))
      event("LAUNCH_PREPARED",ticket.bootId)
      readback(metadata.dispatch(control,ticket.bootId))(metadata.launch(ticket.bootId).filter(t=>Set("dispatching","launched").contains(t.phase)))
      val result=client.request("LAUNCH",JsObject("boot"->JsString(ticket.bootId)))
      require(metadata.launch(ticket.bootId).flatMap(_.identity).contains(result.fields("identity").convertTo[SnapshotOwnerIdentity]))
      own=Some(ticket.bootId);event("WORKER_STARTED",ticket.bootId)
    }
    metadata.clearPause(control)
  }catch{case NonFatal(error)=>
    try metadata.pause(control,error.getClass.getName)catch{case NonFatal(_)=>()}
    event("SAFE_PAUSE",error.getClass.getName)
  }
}
