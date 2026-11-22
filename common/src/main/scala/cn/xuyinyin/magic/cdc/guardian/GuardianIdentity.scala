package cn.xuyinyin.magic.cdc.guardian

/** Control-plane identities use explicit JSON and SQL fields, not Journal manifests. */
final case class SnapshotOwnerIdentity(bootId:String,deploymentId:String,pid:Long,startIdentity:String) {
  require(bootId.matches("[A-Za-z0-9-]{1,128}") && deploymentId.nonEmpty && deploymentId.length<=255 && pid>0 && startIdentity.nonEmpty)
}
final case class SnapshotIsolationReceipt(operationId:String,identity:SnapshotOwnerIdentity,provider:String,confirmedAt:Long,details:String)
final case class GuardianBinding(deploymentId:String,hostId:String,bootId:String,publicKey:String) {
  require(Vector(deploymentId,hostId,bootId,publicKey).forall(_.nonEmpty))
  require(deploymentId.length<=255 && hostId.length<=128 && bootId.length<=128)
}
final case class SupervisorControl(guardian:GuardianBinding,bootId:String,epoch:Long)
