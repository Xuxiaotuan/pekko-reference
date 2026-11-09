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
  isolation.bindRegistry(controlStore.ownerRegistry)
  private def find(t:SnapshotWorkerLaunch)=isolation.managed.find(c=>t.identity.contains(c.identity))
  /** Read-only worker challenge: independent of supervisor epoch and sequence. */
  def attest(args:JsObject):JsObject=synchronized {
    require(args.fields.keySet==Set("nonce","identity","profile") || args.fields.keySet==Set("nonce","identity","profile","context"),"invalid worker attestation request")
    val nonce=text(args,"nonce");require(nonce.matches("[A-Za-z0-9_-]{43}"),"invalid worker nonce")
    val identity=args.fields("identity").convertTo[SnapshotOwnerIdentity]
    val profile=text(args,"profile");require(profile.nonEmpty && profile.length<=128,"invalid worker profile")
    val ticket=controlStore.launch(identity.bootId).getOrElse(throw new IllegalStateException("worker ticket missing"))
    require(ticket.phase=="launched" && ticket.operation.isEmpty && ticket.identity.contains(identity) && ticket.profileHash==profile,
      "worker launch ticket is not active")
    require(controlStore.ownerRegistry.owner(identity.bootId).exists(o=>o.identity==identity && o.phase=="active"),"worker owner is not active")
    val child=find(ticket).getOrElse(throw new IllegalStateException("original worker process capability unavailable"))
    require(child.identity==identity && child.process.pid()==identity.pid && child.process.isAlive &&
      child.process.info().startInstant().isPresent && child.process.info().startInstant().get().toString==identity.startIdentity,
      "original worker process is not alive")
    args.fields.get("context").foreach {v=>
      val ctx=v.convertTo[WorkerAttestationContext]
      require(ctx.attempt.nonEmpty && ctx.attempt.length<=128 && ctx.permitId.nonEmpty && ctx.permitId.length<=128 &&
        ctx.manifestDigest.matches("[a-f0-9]{64}") && ctx.bindingDigest.matches("[a-f0-9]{64}") &&
        ctx.sourceUuid.matches("[a-fA-F0-9-]{36}") && ctx.database.matches("[A-Za-z_][A-Za-z0-9_]*"),"invalid attestation context")
      val at=ctx.controllerAccount.lastIndexOf('@')
      require(at>0 && at<ctx.controllerAccount.length-1,"invalid controller account")
      val ownership=controlStore.ownerRegistry
      require(ownership.instances(identity.bootId).exists {case(endpoint,uuid,_)=>endpoint==ctx.endpoint && uuid==ctx.sourceUuid} &&
        ownership.databaseUsers(identity.bootId,ctx.endpoint)(ctx.controllerAccount.substring(0,at)),"controller account not registered to worker")
    }
    JsObject(Map[String,JsValue]("nonce"->JsString(nonce),"identity"->identity.toJson,"profile"->JsString(profile),
      "provider"->JsString("managed-child-jvm"),"alive"->JsBoolean(true))++args.fields.get("context").map(v=>"context"->v))
  }
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
