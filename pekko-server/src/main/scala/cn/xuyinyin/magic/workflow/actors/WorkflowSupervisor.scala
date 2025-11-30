package cn.xuyinyin.magic.workflow.actors

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngine
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
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
    replyTo: ActorRef[EventSourcedWorkflowActor.ExecutionResponse]
  ) extends Command
  
  case class ExecuteWorkflowScheduled(
    workflowId: String,
    triggeredBy: String = "scheduler"
  ) extends Command
  
  case class CreateAndExecuteWorkflow(
    workflow: WorkflowDSL.Workflow,
    triggeredBy: String = "scheduler"
  ) extends Command
  
  case class PauseWorkflow(workflowId: String) extends Command
  case class ResumeWorkflow(workflowId: String) extends Command
  case class StopWorkflow(workflowId: String) extends Command
  
  case class GetWorkflowStatus(
    workflowId: String,
    replyTo: ActorRef[EventSourcedWorkflowActor.StatusResponse]
  ) extends Command
  
  case class GetWorkflowLogs(
    workflowId: String,
    replyTo: ActorRef[WorkflowActor.LogsResponse]
  ) extends Command
  
  case class ListWorkflows(
    replyTo: ActorRef[WorkflowList]
  ) extends Command
  
  case class GetExecutionHistory(
    workflowId: String,
    replyTo: ActorRef[EventSourcedWorkflowActor.ExecutionHistoryResponse]
  ) extends Command
  
  /**
   * 响应消息
   */
  case class WorkflowCreated(workflowId: String, actorRef: ActorRef[_])
  case class WorkflowList(workflows: Map[String, ActorRef[_]])
  
  /**
   * 创建WorkflowSupervisor（旧版本 - 本地管理）
   * 
   * @param executionEngine 执行引擎
   * @param useEventSourcing 是否启用Event Sourcing（默认true）
   */
  def apply(
    executionEngine: WorkflowExecutionEngine,
    useEventSourcing: Boolean = true
  )(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.setup { context =>
      context.log.info(s"WorkflowSupervisor started (Event Sourcing: $useEventSourcing)")
      
      // 监督策略：工作流Actor失败时重启
      val supervisorStrategy = SupervisorStrategy.restart
        .withLimit(maxNrOfRetries = 3, withinTimeRange = 1.minute)
      
      active(executionEngine, Map.empty, supervisorStrategy, useEventSourcing)
    }
  }
  
  /**
   * 创建WorkflowSupervisor（新版本 - Sharding代理）
   * 
   * @param shardRegion Cluster Sharding的ShardRegion引用
   */
  def withSharding(
    shardRegion: ActorRef[ShardingEnvelope[EventSourcedWorkflowActor.Command]]
  ): Behavior[Command] = {
    Behaviors.setup { context =>
      context.log.info("WorkflowSupervisor started with Cluster Sharding")
      
      shardingProxy(shardRegion)
    }
  }
  
  /**
   * 活动状态
   */
  private def active(
    executionEngine: WorkflowExecutionEngine,
    workflows: Map[String, ActorRef[_]],
    supervisorStrategy: SupervisorStrategy,
    useEventSourcing: Boolean
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
              context.log.info(s"Creating workflow actor: ${workflow.id} (Event Sourcing: $useEventSourcing)")
              
              // 根据配置创建不同的工作流Actor
              val workflowActor = if (useEventSourcing) {
                // 使用 Event Sourced Actor
                context.spawn(
                  EventSourcedWorkflowActor(workflow.id, workflow, executionEngine),
                  s"workflow-${workflow.id}"
                )
              } else {
                // 使用传统 Actor
                context.spawn(
                  Behaviors.supervise(WorkflowActor(workflow, executionEngine))
                    .onFailure(supervisorStrategy),
                  s"workflow-${workflow.id}"
                )
              }
              
              replyTo ! WorkflowCreated(workflow.id, workflowActor)
              active(executionEngine, workflows + (workflow.id -> workflowActor), supervisorStrategy, useEventSourcing)
          }
        
        case ExecuteWorkflow(workflowId, replyTo) =>
          workflows.get(workflowId) match {
            case Some(actor) =>
              context.log.info(s"Executing workflow: $workflowId")
              if (useEventSourcing) {
                actor.asInstanceOf[ActorRef[EventSourcedWorkflowActor.Command]] ! EventSourcedWorkflowActor.Execute(replyTo)
              } else {
                val adapter = context.messageAdapter[WorkflowActor.ExecutionResponse] { response =>
                  ExecuteWorkflow(workflowId, replyTo)
                }
                actor.asInstanceOf[ActorRef[WorkflowActor.Command]] ! WorkflowActor.Execute(adapter)
              }
            
            case None =>
              context.log.warn(s"Workflow not found: $workflowId")
          }
          Behaviors.same
        
        case ExecuteWorkflowScheduled(workflowId, triggeredBy) =>
          workflows.get(workflowId) match {
            case Some(actor) =>
              context.log.info(s"Executing workflow (triggered by $triggeredBy): $workflowId")
              // 创建临时响应actor来接收执行结果
              val responseAdapter = context.messageAdapter[WorkflowActor.ExecutionResponse] { response =>
                context.log.info(s"Scheduled workflow execution: $workflowId, status: ${response.status}")
                // 可以在这里添加调度执行的额外逻辑
                ExecuteWorkflowScheduled(workflowId, triggeredBy) // 用于占位，实际不会处理
              }
              actor.asInstanceOf[ActorRef[WorkflowActor.Command]] ! WorkflowActor.Execute(responseAdapter)
            
            case None =>
              context.log.warn(s"Workflow not found for scheduled execution: $workflowId")
          }
          Behaviors.same
        
        case CreateAndExecuteWorkflow(workflow, triggeredBy) =>
          val workflowId = workflow.id
          
          // 检查是否已存在，如果不存在则创建
          workflows.get(workflowId) match {
            case Some(actor) =>
              // Actor 已存在，直接执行
              context.log.info(s"Executing existing workflow (triggered by $triggeredBy): $workflowId")
              val responseAdapter = context.messageAdapter[WorkflowActor.ExecutionResponse] { response =>
                context.log.info(s"Scheduled workflow execution completed: $workflowId, status: ${response.status}")
                CreateAndExecuteWorkflow(workflow, triggeredBy)
              }
              actor.asInstanceOf[ActorRef[WorkflowActor.Command]] ! WorkflowActor.Execute(responseAdapter)
              Behaviors.same
            
            case None =>
              // Actor 不存在，创建新的
              context.log.info(s"Creating workflow actor for scheduled execution: $workflowId (Event Sourcing: $useEventSourcing)")
              
              val newActor = if (useEventSourcing) {
                context.spawn(
                  EventSourcedWorkflowActor(workflowId, workflow, executionEngine),
                  s"event-sourced-workflow-${workflow.id}"
                )
              } else {
                context.spawn(
                  WorkflowActor(workflow, executionEngine),
                  s"workflow-${workflow.id}"
                )
              }
              
              // 执行工作流
              context.log.info(s"Executing newly created workflow (triggered by $triggeredBy): $workflowId")
              val responseAdapter = context.messageAdapter[WorkflowActor.ExecutionResponse] { response =>
                context.log.info(s"Scheduled workflow execution completed: $workflowId, status: ${response.status}")
                CreateAndExecuteWorkflow(workflow, triggeredBy)
              }
              newActor.asInstanceOf[ActorRef[WorkflowActor.Command]] ! WorkflowActor.Execute(responseAdapter)
              
              // 返回新的行为，更新 workflows Map
              active(executionEngine, workflows + (workflowId -> newActor), supervisorStrategy, useEventSourcing)
          }
        
        case PauseWorkflow(workflowId) =>
          workflows.get(workflowId).foreach { actor =>
            context.log.info(s"Pausing workflow: $workflowId")
            actor.asInstanceOf[ActorRef[WorkflowActor.Command]] ! WorkflowActor.Pause
          }
          Behaviors.same
        
        case ResumeWorkflow(workflowId) =>
          workflows.get(workflowId).foreach { actor =>
            context.log.info(s"Resuming workflow: $workflowId")
            actor.asInstanceOf[ActorRef[WorkflowActor.Command]] ! WorkflowActor.Resume
          }
          Behaviors.same
        
        case StopWorkflow(workflowId) =>
          workflows.get(workflowId) match {
            case Some(actor) =>
              context.log.info(s"Stopping workflow: $workflowId")
              actor.asInstanceOf[ActorRef[WorkflowActor.Command]] ! WorkflowActor.Stop
              active(executionEngine, workflows - workflowId, supervisorStrategy, useEventSourcing)
            
            case None =>
              context.log.warn(s"Workflow not found: $workflowId")
              Behaviors.same
          }
        
        case GetWorkflowStatus(workflowId, replyTo) =>
          workflows.get(workflowId) match {
            case Some(actor) =>
              if (useEventSourcing) {
                actor.asInstanceOf[ActorRef[EventSourcedWorkflowActor.Command]] ! EventSourcedWorkflowActor.GetStatus(replyTo)
              } else {
                val adapter = context.messageAdapter[WorkflowActor.StatusResponse] { response =>
                  GetWorkflowStatus(workflowId, replyTo)
                }
                actor.asInstanceOf[ActorRef[WorkflowActor.Command]] ! WorkflowActor.GetStatus(adapter)
              }
            
            case None =>
              context.log.warn(s"Workflow not found: $workflowId")
          }
          Behaviors.same
        
        case GetWorkflowLogs(workflowId, replyTo) =>
          workflows.get(workflowId) match {
            case Some(actor) =>
              actor.asInstanceOf[ActorRef[WorkflowActor.Command]] ! WorkflowActor.GetLogs(replyTo)
            
            case None =>
              context.log.warn(s"Workflow not found: $workflowId")
          }
          Behaviors.same
        
        case ListWorkflows(replyTo) =>
          context.log.info(s"Listing workflows: ${workflows.size} active")
          replyTo ! WorkflowList(workflows)
          Behaviors.same
        
        case GetExecutionHistory(workflowId, replyTo) =>
          workflows.get(workflowId) match {
            case Some(actor) =>
              context.log.info(s"Querying execution history for workflow: $workflowId")
              // 只有 EventSourcedWorkflowActor 支持历史查询
              actor.asInstanceOf[ActorRef[EventSourcedWorkflowActor.Command]] ! 
                EventSourcedWorkflowActor.GetExecutionHistory(replyTo)
            
            case None =>
              context.log.warn(s"Workflow not found for history query: $workflowId")
              // 返回空历史
              replyTo ! EventSourcedWorkflowActor.ExecutionHistoryResponse(workflowId, List.empty)
          }
          Behaviors.same
      }
    }
  }
  
  /**
   * Sharding代理模式
   * 
   * 不再本地管理Actor，而是通过ShardRegion路由到正确的节点
   */
  private def shardingProxy(
    shardRegion: ActorRef[ShardingEnvelope[EventSourcedWorkflowActor.Command]]
  ): Behavior[Command] = {
    Behaviors.receive { (context, message) =>
      message match {
        case CreateWorkflow(workflow, replyTo) =>
          context.log.info(s"Creating workflow via Sharding: ${workflow.id}")
          
          // 通过Sharding创建/初始化工作流
          val initReplyAdapter = context.messageAdapter[EventSourcedWorkflowActor.InitializeResponse] { response =>
            // 转换为WorkflowCreated响应
            CreateWorkflow(workflow, replyTo) // 占位，实际不会再处理
          }
          
          shardRegion ! ShardingEnvelope(
            workflow.id,
            EventSourcedWorkflowActor.Initialize(workflow, initReplyAdapter)
          )
          
          // 直接回复创建成功（Entity会自动创建）
          replyTo ! WorkflowCreated(workflow.id, shardRegion)
          Behaviors.same
        
        case ExecuteWorkflow(workflowId, replyTo) =>
          context.log.info(s"Executing workflow via Sharding: $workflowId")
          
          // 通过Sharding路由执行命令
          shardRegion ! ShardingEnvelope(
            workflowId,
            EventSourcedWorkflowActor.Execute(replyTo)
          )
          Behaviors.same
        
        case GetWorkflowStatus(workflowId, replyTo) =>
          context.log.info(s"Getting workflow status via Sharding: $workflowId")
          
          // 通过Sharding路由状态查询
          val statusAdapter = context.messageAdapter[EventSourcedWorkflowActor.StatusResponse] { response =>
            // 转换为WorkflowActor.StatusResponse
            GetWorkflowStatus(workflowId, replyTo) // 占位
          }
          
          shardRegion ! ShardingEnvelope(
            workflowId,
            EventSourcedWorkflowActor.GetStatus(statusAdapter)
          )
          
          // 直接转发到原始replyTo
          shardRegion ! ShardingEnvelope(
            workflowId,
            EventSourcedWorkflowActor.GetStatus(replyTo.asInstanceOf[ActorRef[EventSourcedWorkflowActor.StatusResponse]])
          )
          Behaviors.same
        
        case GetExecutionHistory(workflowId, replyTo) =>
          context.log.info(s"Getting execution history via Sharding: $workflowId")
          
          // 通过Sharding路由历史查询
          shardRegion ! ShardingEnvelope(
            workflowId,
            EventSourcedWorkflowActor.GetExecutionHistory(replyTo)
          )
          Behaviors.same
        
        case StopWorkflow(workflowId) =>
          context.log.info(s"Stopping workflow via Sharding: $workflowId")
          
          // 通过Sharding发送停止命令
          shardRegion ! ShardingEnvelope(
            workflowId,
            EventSourcedWorkflowActor.Stop
          )
          Behaviors.same
        
        case ExecuteWorkflowScheduled(workflowId, triggeredBy) =>
          context.log.info(s"Executing workflow via Sharding (triggered by $triggeredBy): $workflowId")
          
          // 创建响应适配器
          val responseAdapter = context.messageAdapter[EventSourcedWorkflowActor.ExecutionResponse] { response =>
                context.log.info(s"Scheduled workflow execution: $workflowId, status: ${response.status}")
            ExecuteWorkflowScheduled(workflowId, triggeredBy)
          }
          
          shardRegion ! ShardingEnvelope(
            workflowId,
            EventSourcedWorkflowActor.Execute(responseAdapter)
          )
          Behaviors.same
        
        case CreateAndExecuteWorkflow(workflow, triggeredBy) =>
          context.log.info(s"Creating and executing workflow via Sharding (triggered by $triggeredBy): ${workflow.id}")
          
          // 先初始化
          val initAdapter = context.messageAdapter[EventSourcedWorkflowActor.InitializeResponse] { _ =>
            CreateAndExecuteWorkflow(workflow, triggeredBy)
          }
          
          shardRegion ! ShardingEnvelope(
            workflow.id,
            EventSourcedWorkflowActor.Initialize(workflow, initAdapter)
          )
          
          // 然后执行
          val executeAdapter = context.messageAdapter[EventSourcedWorkflowActor.ExecutionResponse] { response =>
            context.log.info(s"Scheduled workflow execution completed: ${workflow.id}, status: ${response.status}")
            CreateAndExecuteWorkflow(workflow, triggeredBy)
          }
          
          shardRegion ! ShardingEnvelope(
            workflow.id,
            EventSourcedWorkflowActor.Execute(executeAdapter)
          )
          Behaviors.same
        
        case ListWorkflows(replyTo) =>
          context.log.warn("ListWorkflows not supported in Sharding mode")
          replyTo ! WorkflowList(Map.empty)
          Behaviors.same
        
        case PauseWorkflow(workflowId) =>
          context.log.warn(s"PauseWorkflow not supported in Sharding mode: $workflowId")
          Behaviors.same
        
        case ResumeWorkflow(workflowId) =>
          context.log.warn(s"ResumeWorkflow not supported in Sharding mode: $workflowId")
          Behaviors.same
        
        case GetWorkflowLogs(workflowId, replyTo) =>
          context.log.warn(s"GetWorkflowLogs not supported in Sharding mode: $workflowId")
          Behaviors.same
      }
    }
  }
}
