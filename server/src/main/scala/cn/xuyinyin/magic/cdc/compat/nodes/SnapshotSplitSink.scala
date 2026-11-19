package cn.xuyinyin.magic.cdc.compat.nodes

import cn.xuyinyin.magic.cdc.compat.nodes.WorkflowDSL.Node
import org.apache.pekko.Done
import scala.concurrent.{ExecutionContext, Future}

trait SnapshotSplitSink extends CheckpointedNodeSink { self: NodeSink =>
  def validateSnapshotReady(node: Node)(implicit ec: ExecutionContext): Future[Done]
}
