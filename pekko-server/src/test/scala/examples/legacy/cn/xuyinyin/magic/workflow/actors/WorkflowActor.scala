package cn.xuyinyin.magic.workflow.actors

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.engine.{WorkflowExecutionEngine, ExecutionResult}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import com.typesafe.scalalogging.Logger

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/**
 * 工作流Actor
 * 
 * 每个工作流实例对应一个Actor
 * 负责：
 * - 工作流执行
 * - 状态管理
 * - 日志收集
 * - 生命周期管理
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-15
 */
object WorkflowActor {
  
  /**
   * 工作流状态
   */
  sealed trait WorkflowState
  case object Idle extends WorkflowState           // 空闲
  case object Running extends WorkflowState        // 运行中
  case object Paused extends WorkflowState         // 暂停
  case object Completed extends WorkflowState      // 完成
  case object Failed extends WorkflowState         // 失败
  
  /**
   * Actor消息
   */
  sealed trait Command
  case class Execute(replyTo: ActorRef[ExecutionResponse]) extends Command
  case object Pause extends Command
  case object Resume extends Command
  case object Stop extends Command
  case class GetStatus(replyTo: ActorRef[StatusResponse]) extends Command
  case class GetLogs(replyTo: ActorRef[LogsResponse]) extends Command
  private case class ExecutionCompleted(result: ExecutionResult) extends Command
  private case class ExecutionFailed(error: Throwable) extends Command
  
  /**
   * 响应消息
   */
  sealed trait Response
  case class ExecutionResponse(executionId: String, status: String) extends Response
  case class StatusResponse(
    workflowId: String,
    state: WorkflowState,
    currentExecutionId: Option[String],
    logs: List[String]
  ) extends Response
  case class LogsResponse(logs: List[String]) extends Response
  
  /**
   * 创建WorkflowActor
   */
  def apply(
    workflow: WorkflowDSL.Workflow,
    executionEngine: WorkflowExecutionEngine
  )(implicit ec: ExecutionContext): Behavior[Command] = {
    idle(workflow, executionEngine, List.empty)
  }
  
  /**
   * 空闲状态
   */
  private def idle(
    workflow: WorkflowDSL.Workflow,
    executionEngine: WorkflowExecutionEngine,
    logs: List[String]
  )(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.setup { context =>
      context.log.info(s"WorkflowActor created for: ${workflow.id}")
      
      Behaviors.receiveMessage {
        case Execute(replyTo) =>
          context.log.info(s"Starting workflow execution: ${workflow.id}")
          val executionId = s"exec_${System.currentTimeMillis()}"
          
          // 回复已开始
          replyTo ! ExecutionResponse(executionId, "started")
          
          // 异步执行工作流
          val newLogs = scala.collection.mutable.ListBuffer[String]()
          newLogs ++= logs
          
          context.pipeToSelf(
            executionEngine.execute(
              workflow,
              executionId,
              msg => {
                newLogs += s"[${java.time.Instant.now()}] $msg"
                context.log.info(s"[$executionId] $msg")
              }
            )
          ) {
            case Success(result) => ExecutionCompleted(result)
            case Failure(ex) => ExecutionFailed(ex)
          }
          
          running(workflow, executionEngine, executionId, newLogs.toList)
        
        case GetStatus(replyTo) =>
          replyTo ! StatusResponse(workflow.id, Idle, None, logs)
          Behaviors.same
        
        case GetLogs(replyTo) =>
          replyTo ! LogsResponse(logs)
          Behaviors.same
        
        case Stop =>
          context.log.info(s"Stopping workflow actor: ${workflow.id}")
          Behaviors.stopped
        
        case _ =>
          context.log.warn("Unexpected message in idle state")
          Behaviors.same
      }
    }
  }
  
  /**
   * 运行状态
   */
  private def running(
    workflow: WorkflowDSL.Workflow,
    executionEngine: WorkflowExecutionEngine,
    executionId: String,
    logs: List[String]
  )(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.receive { (context, message) =>
      message match {
        case ExecutionCompleted(result) =>
          context.log.info(s"Workflow execution completed: ${workflow.id}, status: ${result.status}")
          val finalLogs = logs :+ s"[${java.time.Instant.now()}] Execution completed: ${result.status}"
          
          if (result.success) {
            completed(workflow, executionEngine, executionId, finalLogs)
          } else {
            failed(workflow, executionEngine, executionId, finalLogs, result.message)
          }
        
        case ExecutionFailed(error) =>
          context.log.error(s"Workflow execution failed: ${workflow.id}", error)
          val finalLogs = logs :+ s"[${java.time.Instant.now()}] Execution failed: ${error.getMessage}"
          failed(workflow, executionEngine, executionId, finalLogs, error.getMessage)
        
        case GetStatus(replyTo) =>
          replyTo ! StatusResponse(workflow.id, Running, Some(executionId), logs)
          Behaviors.same
        
        case GetLogs(replyTo) =>
          replyTo ! LogsResponse(logs)
          Behaviors.same
        
        case Pause =>
          context.log.info(s"Pausing workflow: ${workflow.id}")
          paused(workflow, executionEngine, executionId, logs)
        
        case Stop =>
          context.log.info(s"Stopping running workflow: ${workflow.id}")
          Behaviors.stopped
        
        case _ =>
          context.log.warn("Execute command ignored while running")
          Behaviors.same
      }
    }
  }
  
  /**
   * 暂停状态
   */
  private def paused(
    workflow: WorkflowDSL.Workflow,
    executionEngine: WorkflowExecutionEngine,
    executionId: String,
    logs: List[String]
  )(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.receive { (context, message) =>
      message match {
        case Resume =>
          context.log.info(s"Resuming workflow: ${workflow.id}")
          running(workflow, executionEngine, executionId, logs)
        
        case GetStatus(replyTo) =>
          replyTo ! StatusResponse(workflow.id, Paused, Some(executionId), logs)
          Behaviors.same
        
        case Stop =>
          Behaviors.stopped
        
        case _ =>
          Behaviors.same
      }
    }
  }
  
  /**
   * 完成状态
   */
  private def completed(
    workflow: WorkflowDSL.Workflow,
    executionEngine: WorkflowExecutionEngine,
    executionId: String,
    logs: List[String]
  )(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.receive { (context, message) =>
      message match {
        case Execute(replyTo) =>
          // 可以重新执行
          context.log.info(s"Re-executing completed workflow: ${workflow.id}")
          replyTo ! ExecutionResponse(executionId, "restarted")
          idle(workflow, executionEngine, logs)
        
        case GetStatus(replyTo) =>
          replyTo ! StatusResponse(workflow.id, Completed, Some(executionId), logs)
          Behaviors.same
        
        case GetLogs(replyTo) =>
          replyTo ! LogsResponse(logs)
          Behaviors.same
        
        case Stop =>
          Behaviors.stopped
        
        case _ =>
          Behaviors.same
      }
    }
  }
  
  /**
   * 失败状态
   */
  private def failed(
    workflow: WorkflowDSL.Workflow,
    executionEngine: WorkflowExecutionEngine,
    executionId: String,
    logs: List[String],
    error: String
  )(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.receive { (context, message) =>
      message match {
        case Execute(replyTo) =>
          // 可以重试
          context.log.info(s"Retrying failed workflow: ${workflow.id}")
          replyTo ! ExecutionResponse(executionId, "retrying")
          idle(workflow, executionEngine, logs)
        
        case GetStatus(replyTo) =>
          replyTo ! StatusResponse(workflow.id, Failed, Some(executionId), logs)
          Behaviors.same
        
        case GetLogs(replyTo) =>
          replyTo ! LogsResponse(logs)
          Behaviors.same
        
        case Stop =>
          Behaviors.stopped
        
        case _ =>
          Behaviors.same
      }
    }
  }
}
