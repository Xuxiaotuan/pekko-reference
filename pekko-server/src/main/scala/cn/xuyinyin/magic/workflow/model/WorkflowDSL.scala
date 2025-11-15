package cn.xuyinyin.magic.workflow.model

import spray.json._

/**
 * 工作流DSL模型定义
 * 
 * 用于可视化DAG编辑器的数据模型
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-15
 */
object WorkflowDSL {
  
  /**
   * 工作流定义
   */
  case class Workflow(
    id: String,
    name: String,
    description: String,
    version: String,
    author: String,
    tags: List[String],
    nodes: List[Node],
    edges: List[Edge],
    metadata: WorkflowMetadata
  )
  
  /**
   * 节点定义
   */
  case class Node(
    id: String,
    `type`: String,           // source, transform, sink
    nodeType: String,         // file.csv, filter, console.log等
    label: String,
    position: Position,
    config: JsObject,         // 节点特定配置
    style: Option[JsObject] = None
  )
  
  /**
   * 边定义
   */
  case class Edge(
    id: String,
    source: String,
    target: String,
    sourceHandle: Option[String] = Some("out"),
    targetHandle: Option[String] = Some("in"),
    animated: Option[Boolean] = Some(false),
    label: Option[String] = None,
    style: Option[JsObject] = None
  )
  
  /**
   * 节点位置
   */
  case class Position(x: Double, y: Double)
  
  /**
   * 工作流元数据
   */
  case class WorkflowMetadata(
    createdAt: String,
    updatedAt: String,
    executionHistory: Option[List[ExecutionRecord]] = None
  )
  
  /**
   * 执行记录
   */
  case class ExecutionRecord(
    executionId: String,
    startTime: String,
    endTime: Option[String],
    status: String,
    result: Option[JsValue]
  )
  
  // JSON序列化支持
  import DefaultJsonProtocol._
  
  implicit val positionFormat: RootJsonFormat[Position] = jsonFormat2(Position)
  
  implicit val executionRecordFormat: RootJsonFormat[ExecutionRecord] = jsonFormat5(ExecutionRecord)
  
  implicit val workflowMetadataFormat: RootJsonFormat[WorkflowMetadata] = jsonFormat3(WorkflowMetadata)
  
  implicit val nodeFormat: RootJsonFormat[Node] = jsonFormat7(Node)
  
  implicit val edgeFormat: RootJsonFormat[Edge] = jsonFormat8(Edge)
  
  implicit val workflowFormat: RootJsonFormat[Workflow] = jsonFormat9(Workflow)
}
