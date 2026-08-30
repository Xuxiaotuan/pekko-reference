package cn.xuyinyin.magic.workflow.engine

import cn.xuyinyin.magic.workflow.checkpoint.{BatchCheckpoint, SnapshotBoundary}
import org.apache.pekko.Done

import scala.concurrent.Future

final case class ReliableRunContext(
  executionId: String,
  workflowRevision: Long,
  boundary: Option[SnapshotBoundary],
  checkpoints: Vector[BatchCheckpoint],
  initializeBoundary: SnapshotBoundary => Future[Done],
  checkpointCommitted: BatchCheckpoint => Future[Done]
)
