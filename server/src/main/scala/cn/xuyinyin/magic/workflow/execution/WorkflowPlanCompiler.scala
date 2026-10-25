package cn.xuyinyin.magic.workflow.execution

import cn.xuyinyin.magic.workflow.execution.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.model.WorkflowDSL.{Node, Workflow}
import cn.xuyinyin.magic.workflow.spi.{CheckpointedNodeSink, CheckpointedNodeSource}

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
          if (Set("mysql.cdc", "mysql.cdc.log").contains(pipeline.source.nodeType)) Right(MySqlCdcPlan(task, pipeline, checkpointSource, checkpointSink))
          else Right(CheckpointedLinearPlan(task, pipeline, checkpointSource, checkpointSink))
        case (_: CheckpointedNodeSource, _) => Left(CheckpointSinkRequired(pipeline))
        // Preserve legacy row execution even if only its Sink supports checkpoints.
        case _ => Right(RowStreamPlan(task, pipeline, source, sink))
      }
    }

  private def runtimeNode(node: Node): ExecutionNode =
    ExecutionNode(node.id, node.`type`, node.nodeType, node.label, node.config)
}
