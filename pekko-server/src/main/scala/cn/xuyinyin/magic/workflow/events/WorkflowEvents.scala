package cn.xuyinyin.magic.workflow.events

import spray.json.{DefaultJsonProtocol, RootJsonFormat}

/**
 * 工作流事件定义
 * 
 * Event Sourcing 的核心：所有状态变更都通过事件表示
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-16
 */
object WorkflowEvents {
  
  /**
   * 工作流事件基类
   */
  sealed trait WorkflowEvent {
    def workflowId: String
    def timestamp: Long
  }
  
  // ========== 工作流级别事件 ==========
  
  /**
   * 工作流开始事件
   */
  case class WorkflowStarted(
    workflowId: String,
    workflowName: String,
    executionId: String,
    totalNodes: Int,
    timestamp: Long = System.currentTimeMillis()
  ) extends WorkflowEvent
  
  /**
   * 工作流完成事件
   */
  case class WorkflowCompleted(
    workflowId: String,
    executionId: String,
    duration: Long,
    completedNodes: Int,
    timestamp: Long = System.currentTimeMillis()
  ) extends WorkflowEvent
  
  /**
   * 工作流失败事件
   */
  case class WorkflowFailed(
    workflowId: String,
    executionId: String,
    reason: String,
    failedNodeId: Option[String],
    timestamp: Long = System.currentTimeMillis()
  ) extends WorkflowEvent
  
  // ========== 节点级别事件 ==========
  
  /**
   * 节点执行开始事件
   */
  case class NodeExecutionStarted(
    workflowId: String,
    executionId: String,
    nodeId: String,
    nodeType: String,
    timestamp: Long = System.currentTimeMillis()
  ) extends WorkflowEvent
  
  /**
   * 节点执行完成事件
   */
  case class NodeExecutionCompleted(
    workflowId: String,
    executionId: String,
    nodeId: String,
    nodeType: String,
    duration: Long,
    recordsProcessed: Int = 0,
    timestamp: Long = System.currentTimeMillis()
  ) extends WorkflowEvent
  
  /**
   * 节点执行失败事件
   */
  case class NodeExecutionFailed(
    workflowId: String,
    executionId: String,
    nodeId: String,
    nodeType: String,
    error: String,
    timestamp: Long = System.currentTimeMillis()
  ) extends WorkflowEvent
  
  // ========== JSON 序列化支持 ==========
  
  object WorkflowEventJsonProtocol extends DefaultJsonProtocol {
    implicit val workflowStartedFormat: RootJsonFormat[WorkflowStarted] = jsonFormat5(WorkflowStarted.apply)
    implicit val workflowCompletedFormat: RootJsonFormat[WorkflowCompleted] = jsonFormat5(WorkflowCompleted.apply)
    implicit val workflowFailedFormat: RootJsonFormat[WorkflowFailed] = jsonFormat5(WorkflowFailed.apply)
    implicit val nodeExecutionStartedFormat: RootJsonFormat[NodeExecutionStarted] = jsonFormat5(NodeExecutionStarted.apply)
    implicit val nodeExecutionCompletedFormat: RootJsonFormat[NodeExecutionCompleted] = jsonFormat7(NodeExecutionCompleted.apply)
    implicit val nodeExecutionFailedFormat: RootJsonFormat[NodeExecutionFailed] = jsonFormat6(NodeExecutionFailed.apply)
  }
}
