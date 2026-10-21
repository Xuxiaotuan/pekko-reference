package cn.xuyinyin.magic.workflow

import cn.xuyinyin.magic.workflow.model.WorkflowDSL.{Edge, Node, Position, Workflow, WorkflowMetadata}
import spray.json.{JsNumber, JsObject, JsString}

object WorkflowFixtures {
  private val metadata = WorkflowMetadata("2026-08-29", "2026-08-29")

  private def node(id: String, role: String, nodeType: String, config: JsObject = JsObject.empty): Node =
    Node(id, role, nodeType, id, Position(0, 0), config)

  private def workflow(id: String, nodes: List[Node], edges: List[Edge]): Workflow =
    Workflow(id, id, "test workflow", "1", "test", Nil, nodes, edges, metadata)

  private def edge(source: String, target: String): Edge = Edge(s"$source-$target", source, target)

  private val source = node("source-1", "source", "sequence.numbers", JsObject(
    "start" -> JsNumber(1),
    "end" -> JsNumber(1)
  ))
  private val transform = node("transform-1", "transform", "map", JsObject("expression" -> JsString("trim")))
  private val sink = node("sink-1", "sink", "console.log")

  val linearWorkflow: Workflow = workflow(
    "linear",
    List(source, transform, sink),
    List(edge(source.id, transform.id), edge(transform.id, sink.id))
  )

  val mysqlCdcWorkflow: Workflow = {
    val cdcSource = node("source-1", "source", "mysql.cdc", JsObject(
      "connectorId" -> JsString("orders-cdc-v1"),
      "host" -> JsString("mysql"),
      "port" -> JsNumber(3306),
      "database" -> JsString("pekko_workflow"),
      "table" -> JsString("pekko_cdc_source_acceptance"),
      "username" -> JsString("pekko_cdc"),
      "passwordEnv" -> JsString("MYSQL_CDC_PASSWORD"),
      "serverId" -> JsNumber(54001),
      "maxBatchSize" -> JsNumber(100),
      "pollIntervalMillis" -> JsNumber(500)
    ))
    val cdcSink = node("sink-1", "sink", "mysql.cdc.apply", JsObject(
      "host" -> JsString("mysql"),
      "port" -> JsNumber(3306),
      "database" -> JsString("pekko_workflow"),
      "table" -> JsString("pekko_cdc_target_acceptance"),
      "username" -> JsString("pekko_workflow"),
      "passwordEnv" -> JsString("DB_PASSWORD")
    ))
    workflow("mysql-cdc", List(cdcSource, cdcSink), List(edge(cdcSource.id, cdcSink.id)))
  }

  val branchedWorkflow: Workflow = workflow(
    "branched",
    List(source, transform, sink, node("sink-2", "sink", "console.log")),
    List(edge(source.id, transform.id), edge(transform.id, sink.id), edge(transform.id, "sink-2"))
  )

  val disconnectedWorkflow: Workflow = workflow(
    "disconnected",
    List(source, transform, sink, node("transform-2", "transform", "distinct")),
    List(edge(source.id, transform.id), edge(transform.id, sink.id))
  )

  val cyclicWorkflow: Workflow = workflow(
    "cyclic",
    List(source, transform, sink),
    List(edge(source.id, transform.id), edge(transform.id, sink.id), edge(sink.id, source.id))
  )

  val mergedWorkflow: Workflow = workflow(
    "merged",
    List(source, node("source-2", "source", "sequence.numbers"), transform, sink),
    List(edge(source.id, transform.id), edge("source-2", transform.id), edge(transform.id, sink.id))
  )

  val unknownEndpointWorkflow: Workflow = workflow(
    "unknown-endpoint",
    List(source, sink),
    List(edge(source.id, "missing-sink"))
  )

  val unimplementedTransformWorkflow: Workflow = workflow(
    "unimplemented-transform",
    List(source, node("transform-1", "transform", "data.clean"), sink),
    List(edge(source.id, "transform-1"), edge("transform-1", sink.id))
  )

  val failingSinkWorkflow: Workflow = workflow(
    "failing-sink",
    List(source, node("sink-1", "sink", "file.text", JsObject("path" -> JsString(0.toChar + "invalid")))),
    List(edge(source.id, sink.id))
  )

  val failingSourceWorkflow: Workflow = workflow(
    "failing-source",
    List(node("source-1", "source", "test.failing-source"), transform, sink),
    List(edge("source-1", transform.id), edge(transform.id, sink.id))
  )

  val multiErrorWorkflow: Workflow = workflow(
    "multi-error",
    List(source, transform, sink),
    List(
      edge(source.id, "missing-node"),
      edge(source.id, transform.id),
      edge(source.id, sink.id),
      edge(transform.id, sink.id)
    )
  )
}
