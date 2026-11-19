package cn.xuyinyin.magic.cdc.control


import cn.xuyinyin.magic.cdc.control.{ManagedCdcOwnerStore, ManagedControlTransactions}

import javax.sql.DataSource
import spray.json._

object CdcManagedControlStore {
  /** Stable across supervisor restarts; credentials and mutable epochs are excluded. */
  final case class CutoverIdentity(deploymentId:String,hostId:String,operationId:String) {
    require(deploymentId.nonEmpty && deploymentId.length<=255 && hostId.nonEmpty && hostId.length<=128)
    require(operationId.matches("[A-Za-z0-9-]{1,128}"))
    def policyHash(limits:SchedulingLimits):String =
      cn.xuyinyin.magic.cdc.snapshot.SnapshotSplitState.sha256(
        JsArray(JsString("cdc-control-cutover-v1"),JsString(limits.toString),JsString(deploymentId),JsString(hostId),JsString(operationId)).compactPrint)
  }
}

/** CDC control storage; the legacy admission row remains the inter-version lock. */
final class CdcManagedControlStore(dataSource:DataSource,val limits:SchedulingLimits,cutover:Option[CdcManagedControlStore.CutoverIdentity]=None)
  extends ManagedControlTransactions(dataSource,cutover.fold(cn.xuyinyin.magic.cdc.snapshot.SnapshotSplitState.sha256(limits.toString))(_.policyHash(limits)),
    allowPolicyInitialization=cutover.isEmpty) {
  val ownership:ManagedCdcOwnerStore=ManagedCdcOwnerStore.forCdc(this)
}
