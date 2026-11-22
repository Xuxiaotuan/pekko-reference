package cn.xuyinyin.magic.workflow.execution

import cn.xuyinyin.magic.cdc.snapshot.SnapshotSplitRunContext

import cn.xuyinyin.magic.cdc.checkpoint.{BatchCheckpoint, SnapshotBoundary}
import org.apache.pekko.Done

import scala.concurrent.Future

final case class ReliableRunContext(
  executionId: String,
  workflowRevision: Long,
  boundary: Option[SnapshotBoundary],
  checkpoints: Vector[BatchCheckpoint],
  initializeBoundary: SnapshotBoundary => Future[Done],
  checkpointCommitted: BatchCheckpoint => Future[Done],
  splitContext: Option[SnapshotSplitRunContext] = None,
  schedulingGrant: Option[cn.xuyinyin.magic.workflow.scheduling.TableGrant] = None,
  checkpointWithReceipt: Option[BatchCheckpoint => Future[Option[cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor.PersistedCheckpoint]]] = None
)
