package cn.xuyinyin.magic.workflow.nodes.base

import cn.xuyinyin.magic.workflow.checkpoint.{BatchCheckpoint, BatchCommitResult, SnapshotBoundary, SourceBatch}
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.{ExecutionContext, Future}

trait CheckpointedNodeSource { self: NodeSource =>
  def discoverBoundary(
    node: WorkflowDSL.Node,
    resumeFrom: Option[BatchCheckpoint],
    onLog: String => Unit
  )(implicit blockingEc: ExecutionContext): Future[SnapshotBoundary] =
    discoverBoundary(node, onLog)

  def discoverBoundary(node: WorkflowDSL.Node, onLog: String => Unit)
    (implicit blockingEc: ExecutionContext): Future[SnapshotBoundary] =
    Future.failed(new UnsupportedOperationException(
      "CheckpointedNodeSource must implement boundary discovery"
    ))

  def createBatches(
    node: WorkflowDSL.Node,
    executionId: String,
    boundary: SnapshotBoundary,
    resumeFrom: Option[BatchCheckpoint],
    onLog: String => Unit
  )(implicit blockingEc: ExecutionContext): Source[SourceBatch, NotUsed]

  def acknowledgeCommittedBatch(
    node: WorkflowDSL.Node,
    batch: SourceBatch,
    onLog: String => Unit
  )(implicit blockingEc: ExecutionContext): Future[Done] = Future.successful(Done)
}

trait CheckpointedNodeSink { self: NodeSink =>
  def validateReady(
    node: WorkflowDSL.Node,
    onLog: String => Unit
  )(implicit blockingEc: ExecutionContext): Future[Done]

  def validateSourceBoundary(
    node: WorkflowDSL.Node,
    boundary: SnapshotBoundary,
    onLog: String => Unit
  )(implicit blockingEc: ExecutionContext): Future[Done] = Future.successful(Done)

  def commitBatch(
    node: WorkflowDSL.Node,
    workflowId: String,
    executionId: String,
    batch: SourceBatch,
    transformedRows: Vector[String],
    onLog: String => Unit
  )(implicit blockingEc: ExecutionContext): Future[BatchCommitResult]
}
