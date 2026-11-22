package cn.xuyinyin.magic.cdc.compat

import cn.xuyinyin.magic.cdc.persistence.JournalCheckpointReceipt
import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor

/** The legacy issuer is confined here; CDC projection consumes an independent proof handle. */
private[magic] object LegacyJournalReceiptAdapter {
  private val issued=new java.util.WeakHashMap[JournalCheckpointReceipt,java.lang.Boolean]()
  def fromActor(original:EventSourcedWorkflowActor.PersistedCheckpoint):JournalCheckpointReceipt={
    require(EventSourcedWorkflowActor.isActorIssuedCheckpoint(original),"legacy checkpoint was not actor-issued")
    val receipt=new JournalCheckpointReceipt(original.persistenceId,original.sequenceNr,original.executionId,original.checkpoint)
    issued.synchronized {issued.put(receipt,java.lang.Boolean.TRUE)}
    receipt
  }
  def isIssued(receipt:JournalCheckpointReceipt):Boolean=
    receipt!=null && issued.synchronized {issued.containsKey(receipt)}
}
