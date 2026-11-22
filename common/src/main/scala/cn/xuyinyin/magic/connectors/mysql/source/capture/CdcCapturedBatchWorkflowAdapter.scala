package cn.xuyinyin.magic.connectors.mysql.source.capture

import cn.xuyinyin.magic.cdc.checkpoint.{SourceBatch,SourceCursor}

/** Keeps the existing workflow wire types at the compatibility boundary. */
private[magic] object CdcCapturedBatchWorkflowAdapter {
  def toWorkflow(batch:CdcCapturedBatch,acknowledgementId:Option[String]=None):SourceBatch =
    SourceBatch(batch.sourceNodeId,batch.partitionId,batch.batchSequence,batch.batchId,
      SourceCursor(batch.cursor.kind,batch.cursor.value,batch.cursor.upperBound),batch.rows,acknowledgementId)

  def cursorFromWorkflow(cursor:SourceCursor):CdcCaptureCursor =
    CdcCaptureCursor(cursor.kind,cursor.value,cursor.upperBound)
}
