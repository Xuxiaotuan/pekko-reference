package cn.xuyinyin.magic.workflow.engine

import cn.xuyinyin.magic.workflow.model.WorkflowDSL.{Node, Position}
import cn.xuyinyin.magic.workflow.nodes.base.{CheckpointedNodeSink, CheckpointedNodeSource, NodeSink, NodeSource}
import spray.json.JsObject

/** Runtime descriptions are not journal payloads and contain no editor layout or history. */
final case class ExecutionTask(id: String, name: String)

final case class ExecutionNode(id: String, role: String, nodeType: String, label: String, config: JsObject) {
  /** Compatibility boundary for existing connectors; never reconstruct the editor definition. */
  private[workflow] def connectorNode: Node = Node(id, role, nodeType, label, Position(0, 0), config)
}

final case class ExecutionPipeline(source: ExecutionNode, transforms: Vector[ExecutionNode], sink: ExecutionNode) {
  def nodes: Vector[ExecutionNode] = source +: transforms :+ sink
}

sealed trait ExecutionPlan {
  def task: ExecutionTask
  def pipeline: ExecutionPipeline
  def resumable: Boolean
}

final case class RowStreamPlan(task: ExecutionTask, pipeline: ExecutionPipeline, source: NodeSource, sink: NodeSink)
  extends ExecutionPlan {
  override val resumable: Boolean = false
}

sealed trait CheckpointedExecutionPlan extends ExecutionPlan {
  def source: CheckpointedNodeSource
  def sink: CheckpointedNodeSink
  override final val resumable: Boolean = true
}

final case class MySqlCdcPlan(task: ExecutionTask, pipeline: ExecutionPipeline,
  source: CheckpointedNodeSource, sink: CheckpointedNodeSink) extends CheckpointedExecutionPlan

final case class CheckpointedLinearPlan(task: ExecutionTask, pipeline: ExecutionPipeline,
  source: CheckpointedNodeSource, sink: CheckpointedNodeSink) extends CheckpointedExecutionPlan

sealed trait PlanCompilationFailure
final case class InvalidWorkflowPlan(errors: Vector[WorkflowValidationError]) extends PlanCompilationFailure
final case class CheckpointSinkRequired(pipeline: ExecutionPipeline) extends PlanCompilationFailure
