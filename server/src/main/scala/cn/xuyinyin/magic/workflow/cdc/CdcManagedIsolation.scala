package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.workflow.scheduling._
import spray.json._
import scala.util.control.NonFatal

/** Validation only. The caller must load every argument from its pinned supervisor store. */
private[cdc] object CdcManagedIsolation {
  final class Verified private[CdcManagedIsolation] (val managed:CdcManagedOwner,val manifest:String,
    val receipt:SnapshotIsolationReceipt,val signedProof:String) {
    def operationId:String=receipt.operationId
    def identity:SnapshotOwnerIdentity=receipt.identity
  }
  def verify(managed:CdcManagedOwner,manifest:String,binding:GuardianBinding,ticket:SnapshotWorkerLaunch,
    owner:SnapshotOwner,inventory:Map[String,(String,Set[String])]):Verified={
    require(binding==GuardianBinding(managed.deploymentId,managed.hostId,managed.guardianBoot,managed.guardianPublicKey),"guardian binding changed")
    val identity=SnapshotOwnerIdentity(managed.bootId,managed.deploymentId,managed.pid,managed.startIdentity)
    require(ticket.bootId==identity.bootId && ticket.profileHash==managed.profileHash && ticket.phase=="retired" &&
      ticket.identity.contains(identity) && ticket.operation.nonEmpty && ticket.proof.nonEmpty,"managed worker retirement unproven")
    require(owner.identity==identity && owner.phase=="db_drained" && owner.operation==ticket.operation &&
      owner.sealedManifest.contains(owner.manifest),"managed worker drain unproven")
    val receipt=try SnapshotGuardianWire.verifyReceipt(ticket.proof.get,binding,identity,ticket.operation.get)
      catch {case NonFatal(e)=>throw new IllegalArgumentException("invalid guardian isolation proof",e)}
    val details=receipt.details.parseJson.asJsObject.fields
    require(details.get("automaticRestart").contains(JsBoolean(false)),"worker restart isolation unproven")
    val fields=manifest.parseJson.asJsObject.fields
    require(fields.keySet==Set("version","accounts") && fields("version")==JsNumber(1),"invalid managed resource manifest")
    val accounts=fields("accounts").asInstanceOf[JsArray].elements
    require(accounts.nonEmpty && accounts.size<=256,"invalid managed account inventory")
    val entries=accounts.map { value=>
      val f=value.asJsObject.fields
      require(f.keySet==Set("endpoint","serverUuid","user"),"invalid managed account entry")
      val endpoint=f("endpoint").asInstanceOf[JsString].value
      val uuid=f("serverUuid").asInstanceOf[JsString].value
      val user=f("user").asInstanceOf[JsString].value
      require(endpoint.nonEmpty && uuid.nonEmpty && user.nonEmpty,"empty managed account entry")
      require(inventory.get(endpoint).exists(i=>i._1==uuid && i._2(user)),"managed account drain inventory incomplete")
      (endpoint,uuid,user)
    }
    require(entries.distinct.size==entries.size,"duplicate managed account entry")
    new Verified(managed,manifest,receipt,ticket.proof.get)
  }
}
