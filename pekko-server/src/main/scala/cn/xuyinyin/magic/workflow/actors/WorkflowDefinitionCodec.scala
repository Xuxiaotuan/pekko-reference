package cn.xuyinyin.magic.workflow.actors

import EventSourcedWorkflowActor._
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.engine.WorkflowValidator
import cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry
import java.nio.charset.StandardCharsets
import spray.json._

/** Definition parsing and bounds; no actor effects or resource acquisition. */
private[actors] object WorkflowDefinitionCodec {
  def decodeWorkflow(state: WorkflowState): Either[Vector[String], WorkflowDSL.Workflow] = state.workflowJson match {
    case None => Left(Vector("Workflow is not initialized"))
    case Some(json) =>
      try { import WorkflowDSL.workflowFormat; Right(json.parseJson.convertTo[WorkflowDSL.Workflow]) }
      catch { case error: Exception => Left(Vector(Option(error.getMessage).getOrElse(error.getClass.getSimpleName))) }
  }
  def workflowSourceNode(state: WorkflowState): Option[WorkflowDSL.Node] =
    decodeWorkflow(state).toOption.flatMap(_.nodes.find(_.`type` == "source"))
  def canonicalWorkflowJson(workflow: WorkflowDSL.Workflow): String = {
    import WorkflowDSL.workflowFormat
    canonicalJson(workflow.toJson)
  }
  private def canonicalJson(value: JsValue): String = value match {
    case JsObject(fields) => fields.toVector.sortBy(_._1).map { case (key, child) => s"${JsString(key).compactPrint}:${canonicalJson(child)}" }.mkString("{", ",", "}")
    case JsArray(values) => values.map(canonicalJson).mkString("[", ",", "]")
    case other => other.compactPrint
  }
  private def canonicalAndValidate(workflow: WorkflowDSL.Workflow, registry: NodeRegistry): Either[Vector[String], String] = {
    val json = canonicalWorkflowJson(workflow)
    try {
      import WorkflowDSL.workflowFormat
      val decoded = json.parseJson.convertTo[WorkflowDSL.Workflow]
      val bounds = Vector(
        Option.when(decoded.nodes.size > MaxWorkflowNodes)(s"workflow node count exceeds $MaxWorkflowNodes"),
        Option.when(utf8Bytes(json) > MaxWorkflowJsonBytes)(s"workflow canonical JSON exceeds $MaxWorkflowJsonBytes bytes")
      ).flatten
      val validation = WorkflowValidator.validate(decoded, registry).left.map(_.map(_.message))
      if (bounds.nonEmpty) Left(bounds) else validation.map(_ => json)
    } catch {
      case error: Exception => Left(Vector(Option(error.getMessage).getOrElse(error.getClass.getSimpleName)))
    }
  }
  def canonicalAndValidateJson(workflowJson: String, registry: NodeRegistry): Either[Vector[String], String] =
    if (utf8Bytes(workflowJson) > MaxWorkflowJsonBytes) Left(Vector(s"workflow canonical JSON exceeds $MaxWorkflowJsonBytes bytes"))
    else {
      try {
        import WorkflowDSL.workflowFormat
        canonicalAndValidate(workflowJson.parseJson.convertTo[WorkflowDSL.Workflow], registry)
      } catch {
        case error: Exception => Left(Vector(Option(error.getMessage).getOrElse(error.getClass.getSimpleName)))
      }
    }
  private def utf8Bytes(value: String): Int = value.getBytes(StandardCharsets.UTF_8).length
}
