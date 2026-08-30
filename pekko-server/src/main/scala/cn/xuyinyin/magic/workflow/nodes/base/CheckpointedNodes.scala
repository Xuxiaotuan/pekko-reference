package cn.xuyinyin.magic.workflow.nodes.base

import cn.xuyinyin.magic.workflow.checkpoint.{BatchCheckpoint, BatchCommitResult, SnapshotBoundary, SourceBatch}
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.{ExecutionContext, Future}

trait CheckpointedNodeSource { self: NodeSource =>
  def discoverBoundary(node: WorkflowDSL.Node, onLog: String => Unit)
    (implicit blockingEc: ExecutionContext): Future[SnapshotBoundary]

  def createBatches(
    node: WorkflowDSL.Node,
    executionId: String,
    boundary: SnapshotBoundary,
    resumeFrom: Option[BatchCheckpoint],
    onLog: String => Unit
  )(implicit blockingEc: ExecutionContext): Source[SourceBatch, NotUsed]
}

trait CheckpointedNodeSink { self: NodeSink =>
  def validateReady(
    node: WorkflowDSL.Node,
    onLog: String => Unit
  )(implicit blockingEc: ExecutionContext): Future[Done]

  def commitBatch(
    node: WorkflowDSL.Node,
    workflowId: String,
    executionId: String,
    batch: SourceBatch,
    transformedRows: Vector[String],
    onLog: String => Unit
  )(implicit blockingEc: ExecutionContext): Future[BatchCommitResult]
}
