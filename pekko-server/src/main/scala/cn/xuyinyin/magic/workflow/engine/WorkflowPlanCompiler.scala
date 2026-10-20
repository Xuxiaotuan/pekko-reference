package cn.xuyinyin.magic.workflow.engine

import cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.model.WorkflowDSL.{Node, Workflow}
import cn.xuyinyin.magic.workflow.nodes.base.{CheckpointedNodeSink, CheckpointedNodeSource}

/** Definition-only compilation: no credentials, connections, streams or ACK handles are acquired. */
final class WorkflowPlanCompiler(registry: NodeRegistry) {
  def compile(workflow: Workflow): Either[PlanCompilationFailure, ExecutionPlan] =
    WorkflowValidator.validate(workflow, registry).left.map(InvalidWorkflowPlan).flatMap { validated =>
      val task = ExecutionTask(workflow.id, workflow.name)
      val pipeline = ExecutionPipeline(runtimeNode(validated.source), validated.transforms.map(runtimeNode), runtimeNode(validated.sink))
      // The immutable registry used for validation also owns these exact adapter instances.
      val source = registry.findSource(pipeline.source.nodeType).get
      val sink = registry.findSink(pipeline.sink.nodeType).get
      (source, sink) match {
        case (checkpointSource: CheckpointedNodeSource, checkpointSink: CheckpointedNodeSink) =>
          if (pipeline.source.nodeType == "mysql.cdc") Right(MySqlCdcPlan(task, pipeline, checkpointSource, checkpointSink))
          else Right(CheckpointedLinearPlan(task, pipeline, checkpointSource, checkpointSink))
        case (_: CheckpointedNodeSource, _) => Left(CheckpointSinkRequired(pipeline))
        // Preserve legacy row execution even if only its Sink supports checkpoints.
        case _ => Right(RowStreamPlan(task, pipeline, source, sink))
      }
    }

  private def runtimeNode(node: Node): ExecutionNode =
    ExecutionNode(node.id, node.`type`, node.nodeType, node.label, node.config)
}
