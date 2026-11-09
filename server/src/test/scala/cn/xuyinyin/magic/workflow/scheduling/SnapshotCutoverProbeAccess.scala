package cn.xuyinyin.magic.workflow.scheduling

/** Test-only operator action for the real MySQL cutover fixture; production uses SnapshotCutoverAuthorizer. */
object SnapshotCutoverProbeAccess {
  def commitOwner(ownership:ManagedOwnerStore,key:CutoverAuthorizationKey):Unit=commit(ownership.transactionOwner,ownership,key)
  def commit(control:ManagedControlTransactions,ownership:ManagedOwnerStore,key:CutoverAuthorizationKey):Unit={
    var rounds=0
    while(ownership.hasOutstandingQuota(key.oldOwnerId)) {
      rounds+=1;require(rounds<=1024,"fixture quota migration exceeded bound")
      require(ownership.releaseDrained(key.oldOwnerId,1000)>0,"fixture quota migration made no progress")
    }
    control.cutoverStore.recordAuthorization(key)
    control.admission { c=>control.update(c,"UPDATE pekko_table_admission_lock SET policy_hash=? WHERE id=1",key.policyHash);() }
  }
}
