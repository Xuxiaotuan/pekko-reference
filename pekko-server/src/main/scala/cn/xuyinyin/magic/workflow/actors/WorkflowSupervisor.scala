package cn.xuyinyin.magic.workflow.actors

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngine
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import com.typesafe.scalalogging.Logger

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
 * 工作流监督器
 * 
 * 管理所有工作流Actor
 * 
 * 功能：
 * - 创建工作流Actor
 * - 查询工作流状态
 * - 控制工作流生命周期
 * - 容错和监督策略
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-15
 */
object WorkflowSupervisor {
  
  /**
   * 监督器消息
   */
  sealed trait Command
  case class CreateWorkflow(
    workflow: WorkflowDSL.Workflow,
    replyTo: ActorRef[WorkflowCreated]
  ) extends Command
  
  case class ExecuteWorkflow(
    workflowId: String,
    replyTo: ActorRef[WorkflowActor.ExecutionResponse]
  ) extends Command
  
  case class PauseWorkflow(workflowId: String) extends Command
  case class ResumeWorkflow(workflowId: String) extends Command
  case class StopWorkflow(workflowId: String) extends Command
  
  case class GetWorkflowStatus(
    workflowId: String,
    replyTo: ActorRef[WorkflowActor.StatusResponse]
  ) extends Command
  
  case class GetWorkflowLogs(
    workflowId: String,
    replyTo: ActorRef[WorkflowActor.LogsResponse]
  ) extends Command
  
  case class ListWorkflows(
    replyTo: ActorRef[WorkflowList]
  ) extends Command
  
  /**
   * 响应消息
   */
  case class WorkflowCreated(workflowId: String, actorRef: ActorRef[WorkflowActor.Command])
  case class WorkflowList(workflows: Map[String, ActorRef[WorkflowActor.Command]])
  
  /**
   * 创建WorkflowSupervisor
   */
  def apply(executionEngine: WorkflowExecutionEngine)(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.setup { context =>
      context.log.info("WorkflowSupervisor started")
      
      // 监督策略：工作流Actor失败时重启
      val supervisorStrategy = SupervisorStrategy.restart
        .withLimit(maxNrOfRetries = 3, withinTimeRange = 1.minute)
      
      active(executionEngine, Map.empty, supervisorStrategy)
    }
  }
  
  /**
   * 活动状态
   */
  private def active(
    executionEngine: WorkflowExecutionEngine,
    workflows: Map[String, ActorRef[WorkflowActor.Command]],
    supervisorStrategy: SupervisorStrategy
  )(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.receive { (context, message) =>
      message match {
        case CreateWorkflow(workflow, replyTo) =>
          workflows.get(workflow.id) match {
            case Some(existingActor) =>
              context.log.info(s"Workflow already exists: ${workflow.id}")
              replyTo ! WorkflowCreated(workflow.id, existingActor)
              Behaviors.same
            
            case None =>
              context.log.info(s"Creating workflow actor: ${workflow.id}")
              
              // 创建带监督策略的工作流Actor
              val workflowActor = context.spawn(
                Behaviors.supervise(WorkflowActor(workflow, executionEngine))
                  .onFailure(supervisorStrategy),
                s"workflow-${workflow.id}"
              )
              
              replyTo ! WorkflowCreated(workflow.id, workflowActor)
              active(executionEngine, workflows + (workflow.id -> workflowActor), supervisorStrategy)
          }
        
        case ExecuteWorkflow(workflowId, replyTo) =>
          workflows.get(workflowId) match {
            case Some(actor) =>
              context.log.info(s"Executing workflow: $workflowId")
              actor ! WorkflowActor.Execute(replyTo)
            
            case None =>
              context.log.warn(s"Workflow not found: $workflowId")
          }
          Behaviors.same
        
        case PauseWorkflow(workflowId) =>
          workflows.get(workflowId).foreach { actor =>
            context.log.info(s"Pausing workflow: $workflowId")
            actor ! WorkflowActor.Pause
          }
          Behaviors.same
        
        case ResumeWorkflow(workflowId) =>
          workflows.get(workflowId).foreach { actor =>
            context.log.info(s"Resuming workflow: $workflowId")
            actor ! WorkflowActor.Resume
          }
          Behaviors.same
        
        case StopWorkflow(workflowId) =>
          workflows.get(workflowId) match {
            case Some(actor) =>
              context.log.info(s"Stopping workflow: $workflowId")
              actor ! WorkflowActor.Stop
              active(executionEngine, workflows - workflowId, supervisorStrategy)
            
            case None =>
              context.log.warn(s"Workflow not found: $workflowId")
              Behaviors.same
          }
        
        case GetWorkflowStatus(workflowId, replyTo) =>
          workflows.get(workflowId) match {
            case Some(actor) =>
              actor ! WorkflowActor.GetStatus(replyTo)
            
            case None =>
              context.log.warn(s"Workflow not found: $workflowId")
          }
          Behaviors.same
        
        case GetWorkflowLogs(workflowId, replyTo) =>
          workflows.get(workflowId) match {
            case Some(actor) =>
              actor ! WorkflowActor.GetLogs(replyTo)
            
            case None =>
              context.log.warn(s"Workflow not found: $workflowId")
          }
          Behaviors.same
        
        case ListWorkflows(replyTo) =>
          context.log.info(s"Listing workflows: ${workflows.size} active")
          replyTo ! WorkflowList(workflows)
          Behaviors.same
      }
    }
  }
}
