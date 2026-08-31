package cn.xuyinyin.magic.workflow.engine

import cn.xuyinyin.magic.workflow.engine.executors.TransformExecutor
import cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.model.WorkflowDSL.{Node, Workflow}

import scala.collection.mutable

final case class WorkflowValidationError(code: String, message: String)
final case class ValidatedPipeline(source: Node, transforms: Vector[Node], sink: Node) {
  def nodes: Vector[Node] = source +: transforms :+ sink
}

object WorkflowValidator {
  private val supportedTransformTypes = TransformExecutor.supportedTypes

  def validate(workflow: Workflow): Either[Vector[WorkflowValidationError], ValidatedPipeline] = {
    val errors = mutable.ArrayBuffer.empty[WorkflowValidationError]
    val nodesById = workflow.nodes.groupBy(_.id)

    nodesById.iterator.collect { case (id, nodes) if nodes.size > 1 => id }.toVector.sorted.foreach { id =>
      errors += WorkflowValidationError("duplicate_node_id", s"Duplicate node id: $id")
    }

    val nodeMap = nodesById.collect { case (id, List(node)) => id -> node }
    workflow.edges.foreach { edge =>
      if (!nodeMap.contains(edge.source) || !nodeMap.contains(edge.target)) {
        errors += WorkflowValidationError("unknown_endpoint", s"Edge ${edge.id} references an unknown node")
      }
    }

    val sources = workflow.nodes.filter(_.`type` == "source")
    val transforms = workflow.nodes.filter(_.`type` == "transform")
    val sinks = workflow.nodes.filter(_.`type` == "sink")
    val knownRoles = Set("source", "transform", "sink")

    workflow.nodes.filterNot(node => knownRoles.contains(node.`type`)).foreach { node =>
      errors += WorkflowValidationError("unsupported_node_role", s"Unsupported node role: ${node.`type`}")
    }
    sources.filterNot(node => NodeRegistry.supportedSourceTypes.contains(node.nodeType)).foreach { node =>
      errors += WorkflowValidationError("unsupported_node_type", s"Unsupported source type: ${node.nodeType}")
    }
    transforms.filterNot(node => supportedTransformTypes.contains(node.nodeType)).foreach { node =>
      errors += WorkflowValidationError("unsupported_node_type", s"Unsupported transform type: ${node.nodeType}")
    }
    sinks.filterNot(node => NodeRegistry.supportedSinkTypes.contains(node.nodeType)).foreach { node =>
      errors += WorkflowValidationError("unsupported_node_type", s"Unsupported sink type: ${node.nodeType}")
    }

    if (sources.size != 1) errors += WorkflowValidationError("source_count", "Workflow must contain exactly one source")
    if (sinks.size != 1) errors += WorkflowValidationError("sink_count", "Workflow must contain exactly one sink")

    val validEdges = workflow.edges.filter(edge => nodeMap.contains(edge.source) && nodeMap.contains(edge.target))
    val outgoing = validEdges.groupBy(_.source)
    validEdges.groupBy(_.source).iterator.collect { case (id, edges) if edges.size > 1 => id }.toVector.sorted.foreach { id =>
      errors += WorkflowValidationError("branch_not_supported", s"Node $id has more than one outgoing edge")
    }
    validEdges.groupBy(_.target).iterator.collect { case (id, edges) if edges.size > 1 => id }.toVector.sorted.foreach { id =>
      errors += WorkflowValidationError("merge_not_supported", s"Node $id has more than one incoming edge")
    }

    if (hasCycle(nodeMap.keySet, outgoing)) {
      errors += WorkflowValidationError("cycle_not_supported", "Workflow contains a cycle")
    }

    if (errors.nonEmpty) Left(errors.toVector)
    else {
      val source = sources.head
      val sink = sinks.head
      val path = walkPath(source, nodeMap, outgoing)
      val pathIds = path.map(_.id).toSet

      if (path.lastOption.forall(_.id != sink.id) || pathIds.size != workflow.nodes.size) {
        Left(Vector(WorkflowValidationError("disconnected_node", "Every node must belong to the Source to Sink path")))
      } else {
        val pipeline = ValidatedPipeline(source, path.drop(1).dropRight(1), sink)
        val cdcErrors = Vector.newBuilder[WorkflowValidationError]
        if (source.nodeType == "mysql.cdc") {
          if (source.config.fields.contains("password")) {
            cdcErrors += WorkflowValidationError(
              "mysql_cdc_inline_password_not_supported",
              "MySQL CDC source definitions require passwordEnv and must not contain password"
            )
          }
          if (pipeline.transforms.nonEmpty) {
            cdcErrors += WorkflowValidationError(
              "mysql_cdc_transform_not_supported",
              "MySQL CDC workflows must connect the source directly to the sink"
            )
          }
          if (sink.nodeType != "mysql.cdc.apply") {
            cdcErrors += WorkflowValidationError(
              "mysql_cdc_sink_required",
              "MySQL CDC sources require a mysql.cdc.apply sink"
            )
          }
          if (workflow.metadata.schedule.exists(_.enabled)) {
            cdcErrors += WorkflowValidationError(
              "mysql_cdc_schedule_not_supported",
              "MySQL CDC workflows do not support scheduled execution"
            )
          }
        }
        if (sink.nodeType == "mysql.cdc.apply" && sink.config.fields.contains("password")) {
          cdcErrors += WorkflowValidationError(
            "mysql_cdc_inline_password_not_supported",
            "MySQL CDC apply sink definitions require passwordEnv and must not contain password"
          )
        }
        if (sink.nodeType == "mysql.cdc.apply" && source.nodeType != "mysql.cdc") {
          cdcErrors += WorkflowValidationError(
            "mysql_cdc_source_required",
            "MySQL CDC apply sinks require a mysql.cdc source"
          )
        }
        val validationErrors = cdcErrors.result()
        if (validationErrors.nonEmpty) Left(validationErrors) else Right(pipeline)
      }
    }
  }

  private def walkPath(
    source: Node,
    nodesById: Map[String, Node],
    outgoing: Map[String, List[cn.xuyinyin.magic.workflow.model.WorkflowDSL.Edge]]
  ): Vector[Node] = {
    val path = mutable.ArrayBuffer(source)
    var current = source

    while (outgoing.contains(current.id)) {
      current = nodesById(outgoing(current.id).head.target)
      path += current
    }

    path.toVector
  }

  private def hasCycle(nodeIds: Iterable[String], outgoing: Map[String, List[cn.xuyinyin.magic.workflow.model.WorkflowDSL.Edge]]): Boolean = {
    val visiting = mutable.Set.empty[String]
    val visited = mutable.Set.empty[String]

    def visit(nodeId: String): Boolean = {
      if (visiting.contains(nodeId)) true
      else if (visited.contains(nodeId)) false
      else {
        visiting += nodeId
        val cyclic = outgoing.getOrElse(nodeId, Nil).exists(edge => visit(edge.target))
        visiting -= nodeId
        visited += nodeId
        cyclic
      }
    }

    nodeIds.exists(visit)
  }
}
