package cn.xuyinyin.magic.cdc.compat.nodes

import cn.xuyinyin.magic.cdc.checkpoint.BatchCheckpoint
import cn.xuyinyin.magic.cdc.compat.nodes.WorkflowDSL.Node
import cn.xuyinyin.magic.cdc.snapshot.{SnapshotSplit, SnapshotSplitPlanState}
import scala.concurrent.{ExecutionContext, Future}

/** Optional snapshot-only capability; CDC callback ordering is deliberately outside this API. */
trait SnapshotSplitSource { self: NodeSource =>
  def discoverSplit(node: Node, plan: SnapshotSplitPlanState, lower: Option[String], index: Long)
    (implicit ec: ExecutionContext): Future[Option[SnapshotSplit]]
  def openSplit(node: Node, executionId: String, plan: SnapshotSplitPlanState, split: SnapshotSplit,
    checkpoint: Option[BatchCheckpoint], onLog: String => Unit, closeEc: ExecutionContext)
    (implicit ec: ExecutionContext): BatchSourceSession
}
