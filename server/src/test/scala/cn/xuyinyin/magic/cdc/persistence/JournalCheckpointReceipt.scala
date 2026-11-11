package cn.xuyinyin.magic.cdc.persistence

import cn.xuyinyin.magic.cdc.checkpoint.BatchCheckpoint

/** Process-local proof handle. Fields and constructors alone do not establish authenticity. */
final class JournalCheckpointReceipt private[cdc] (
  val persistenceId:String,val sequenceNr:Long,val executionId:String,val checkpoint:BatchCheckpoint)
