package cn.xuyinyin.magic.workflow.execution

import cn.xuyinyin.magic.workflow.checkpoint.{BatchCheckpoint, SnapshotBoundary}
import org.apache.pekko.Done

import scala.concurrent.Future

final case class ReliableRunContext(
  executionId: String,
  workflowRevision: Long,
  boundary: Option[SnapshotBoundary],
  checkpoints: Vector[BatchCheckpoint],
  initializeBoundary: SnapshotBoundary => Future[Done],
  checkpointCommitted: BatchCheckpoint => Future[Done],
  splitContext: Option[SnapshotSplitRunContext] = None
)

final case class SnapshotSplitRunContext(
  read: () => Future[Option[cn.xuyinyin.magic.workflow.snapshot.SnapshotSplitState]],
  update: cn.xuyinyin.magic.workflow.snapshot.SnapshotSplitUpdate => Future[cn.xuyinyin.magic.workflow.snapshot.SnapshotSplitState]
)
