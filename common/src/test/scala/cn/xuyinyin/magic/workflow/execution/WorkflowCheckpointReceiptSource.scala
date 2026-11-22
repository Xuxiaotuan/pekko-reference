package cn.xuyinyin.magic.workflow.execution

import cn.xuyinyin.magic.cdc.checkpoint.SourceBatch
import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor.PersistedCheckpoint
import cn.xuyinyin.magic.cdc.compat.nodes.WorkflowDSL
import org.apache.pekko.Done
import scala.concurrent.{ExecutionContext, Future}

/** Legacy source adapter; actor-issued receipts never enter the CDC Source SPI. */
trait WorkflowCheckpointReceiptSource {
  def acknowledgeCommittedBatchWithReceipt(node: WorkflowDSL.Node, batch: SourceBatch,
    receipt: Option[PersistedCheckpoint], onLog: String => Unit)(implicit ec: ExecutionContext): Future[Done]
}
