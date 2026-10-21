package cn.xuyinyin.magic.workflow.spi

import cn.xuyinyin.magic.workflow.model.WorkflowDSL.Node
import org.apache.pekko.Done
import scala.concurrent.{ExecutionContext, Future}

trait SnapshotSplitSink extends CheckpointedNodeSink { self: NodeSink =>
  def validateSnapshotReady(node: Node)(implicit ec: ExecutionContext): Future[Done]
}
