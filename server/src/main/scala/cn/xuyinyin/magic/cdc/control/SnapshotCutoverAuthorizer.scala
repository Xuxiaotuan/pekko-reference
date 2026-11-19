package cn.xuyinyin.magic.cdc.control


import cn.xuyinyin.magic.cdc.control.CdcManagedControlStore





import cn.xuyinyin.magic.cdc.control.{CutoverAuthorizationKey, CredentialBinding}
import cn.xuyinyin.magic.cdc.guardian.{SnapshotWorkerLaunch}
import cn.xuyinyin.magic.cdc.guardian.{SupervisorControl}
import cn.xuyinyin.magic.cdc.guardian.SnapshotGuardianWire
/** Atomic bridge from a proven old owner to a migrated runtime identity. */
private[control] final class SnapshotCutoverAuthorizer(metadata:SnapshotSupervisorStore) {
  import SnapshotGuardianWire._

  final case class BindingRequest(workflowId:String,executionId:String,nodeId:String,nodeRole:String,permitId:String,
    ownerId:String,operationId:String,endpoint:String,databaseName:String,legacyUser:String,runtimeUser:String,passwordEnv:String,definitionHash:String)

  def authorize(control:SupervisorControl,old:SnapshotWorkerLaunch,next:SnapshotWorkerLaunch,receiptRaw:String,lockPlan:LegacyAccountLockIntent.Plan,
    accountUrl:String,adminUser:String,adminPassword:String,bindings:Vector[BindingRequest]):CutoverAuthorizationKey={
    metadata.fenced(control) {
      val identity=old.identity.getOrElse(throw new IllegalStateException("old worker identity is missing"))
      require(old.phase=="launched" && old.operation.nonEmpty && old.proof.contains(receiptRaw),"old worker proof is not durable")
      require(lockPlan.operationId==old.operation.get,"account lock operation differs from worker isolation operation")
      require(next.phase=="prepared" && next.identity.isEmpty && next.bootId!=old.bootId,"next worker is not a fresh prepared ticket")
      verifyReceipt(receiptRaw,control.guardian,identity,old.operation.get)
      val owner=metadata.ownerRegistry.owner(identity.bootId).getOrElse(throw new IllegalStateException("old owner is missing"))
      require(owner.identity==identity && owner.phase=="db_drained" && owner.sealedManifest.contains(owner.manifest),"old owner is not drained")
      val intent=new LegacyAccountLockIntent(metadata)
      intent.verifyDrain(control,lockPlan,accountUrl,adminUser,adminPassword)
      val cutover=CdcManagedControlStore.CutoverIdentity(control.guardian.deploymentId,control.guardian.hostId,old.operation.get)
      var rounds=0
      while(metadata.ownerRegistry.hasOutstandingQuota(identity.bootId)) {
        rounds+=1;require(rounds<=1024,"old owner quota migration exceeded bounded rounds")
        require(metadata.ownerRegistry.releaseDrained(identity.bootId,1000)>0,"old owner quota migration made no progress")
      }
      val limits=metadata.store match {
        case s:TableSchedulingStore=>s.limits
        case s:CdcManagedControlStore=>s.limits
        case _=>throw new IllegalStateException("cutover requires a known scheduling policy")
      }
      val key=CutoverAuthorizationKey(cutover.deploymentId,cutover.hostId,cutover.operationId,identity.bootId,owner.manifest,
        cutover.policyHash(limits),
        cn.xuyinyin.magic.cdc.snapshot.SnapshotSplitState.sha256(receiptRaw),
        cn.xuyinyin.magic.cdc.snapshot.SnapshotSplitState.sha256(lockPlan.digest))
      metadata.store.cutoverStore.recordAuthorization(key)
      require(bindings.isEmpty,"credential bindings require the next owner permit and must be activated after worker admission")
      metadata.store.admission { c=>metadata.store.update(c,"UPDATE pekko_table_admission_lock SET policy_hash=? WHERE id=1",key.policyHash);() }
      key
    }
  }

  def activateBinding(authorization:CutoverAuthorizationKey,binding:BindingRequest):Unit={
    val value=CredentialBinding(binding.workflowId,binding.executionId,binding.nodeId,binding.nodeRole,binding.permitId,binding.ownerId,
      binding.operationId,binding.endpoint,binding.databaseName,binding.legacyUser,binding.runtimeUser,binding.passwordEnv,binding.definitionHash,"ACTIVE")
    metadata.store.cutoverStore.recordBinding(value,authorization)
  }
}
