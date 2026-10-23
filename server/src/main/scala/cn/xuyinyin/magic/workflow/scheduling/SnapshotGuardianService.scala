package cn.xuyinyin.magic.workflow.scheduling

import java.security.PrivateKey
import spray.json._

/** Lives in an independent JVM. Only this object owns the original ManagedJvmIsolation capabilities. */
final class SnapshotGuardianService(controlStore:SnapshotSupervisorStore,isolation:ManagedJvmIsolation,
  builder:SnapshotWorkerLaunch=>ProcessBuilder,signingKey:PrivateKey) {
  import SnapshotGuardianWire._
  private val binding=controlStore.binding
  controlStore.bindGuardianCapability(isolation.capabilityId)
  private val receipts=scala.collection.mutable.Map.empty[(String,String),String]
  isolation.bindRegistry(controlStore.store.ownership.get)
  private def find(t:SnapshotWorkerLaunch)=isolation.managed.find(c=>t.identity.contains(c.identity))
  def handle(control:SupervisorControl,method:String,args:JsObject):JsObject=synchronized {
    controlStore.fenced(control){()}
    method match {
      case "BIND"=>JsObject("bound"->JsBoolean(true))
      case "INVENTORY"=>
        isolation.managed.foreach { child=>
          controlStore.launch(child.identity.bootId).filter(_.phase=="retired").foreach { t=>
            require(!child.process.isAlive && t.identity.contains(child.identity) && t.proof.nonEmpty && t.operation.nonEmpty,"retired child proof missing")
            verifyReceipt(t.proof.get,binding,child.identity,t.operation.get)
            isolation.retire(t.bootId);receipts.keys.filter(_._1==t.bootId).toVector.foreach(receipts.remove)
          }
        }
        controlStore.pending.filter(_.identity.isEmpty).foreach { t=>
          isolation.managed.find(_.identity.bootId==t.bootId).foreach(c=>controlStore.attach(t.bootId,c.identity))
        }
        val pending=controlStore.pending;require(pending.size<=controlStore.maxWorkers,"launch inventory exceeds capacity")
        JsObject(pending.map(t=>t.bootId->JsObject("identity"->t.identity.map(_.toJson).getOrElse(JsNull),
          "status"->JsString(if(isolation.hasUnidentified(t.bootId))"UNKNOWN" else if(t.identity.isEmpty)"NOT_STARTED" else find(t).fold("UNKNOWN")(c=>if(c.process.isAlive)"ALIVE" else "EXITED")))).toMap)
      case "LAUNCH"=>
        val boot=text(args,"boot");val t=controlStore.dispatch(control,boot)
        val identity=t.identity match {
          case Some(id)=>require(find(t).nonEmpty,"original child capability unavailable");id
          case None=>
            val known=isolation.managed.find(_.identity.bootId==boot)
            val child=known.getOrElse(isolation.launch(boot,builder(t)))
            controlStore.attach(boot,child.identity);child.identity
        }
        JsObject("identity"->identity.toJson)
      case "CANCEL"=>
        val boot=text(args,"boot");require(!isolation.hasUnidentified(boot) && !isolation.managed.exists(_.identity.bootId==boot),"worker created or launch identity unknown")
        controlStore.cancel(control,boot);JsObject("cancelled"->JsBoolean(true))
      case "STOP"=>
        val boot=text(args,"boot");val op=text(args,"operation");val t=controlStore.launch(boot).get
        require(t.operation.contains(op) && t.identity.contains(args.fields("identity").convertTo[SnapshotOwnerIdentity]),"isolation operation mismatch")
        val raw=t.proof.orElse(receipts.get((boot,op))).getOrElse {
          val receipt=isolation.confirm(t.identity.get,op,terminate=true).getOrElse(throw new IllegalStateException("original child exit unconfirmed"))
          val value=sign(JsObject("guardian"->binding.toJson,"receipt"->receipt.toJson),signingKey).compactPrint
          receipts.update((boot,op),value);value
        }
        JsObject("proof"->JsString(raw))
      case "RETIRE"=>
        val boot=text(args,"boot");val t=controlStore.launch(boot).get
        require(Set("retired","cancelled").contains(t.phase),"durable retirement required")
        isolation.retire(boot);receipts.keys.filter(_._1==boot).toVector.foreach(receipts.remove)
        JsObject("retired"->JsBoolean(true))
      case _=>throw new IllegalArgumentException("unsupported guardian method")
    }
  }
}
