package cn.xuyinyin.magic.workflow.actors

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.engine.{WorkflowExecutionEngine, ExecutionResult}
import cn.xuyinyin.magic.workflow.events.WorkflowEvents._
import cn.xuyinyin.magic.monitoring.ClusterEventLogger
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import org.apache.pekko.cluster.typed.Cluster
import com.typesafe.scalalogging.Logger

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/**
 * Event Sourced 工作流Actor
 * 
 * 使用 Event Sourcing 模式：
 * - 所有状态变更通过事件记录
 * - 可以重放历史事件
 * - 完整的执行审计轨迹
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-16
 */
object EventSourcedWorkflowActor {
  
  // ========== 命令 ==========
  
  sealed trait Command
  case class Initialize(workflow: WorkflowDSL.Workflow, replyTo: ActorRef[InitializeResponse]) extends Command
  case class Execute(replyTo: ActorRef[ExecutionResponse]) extends Command
  case class GetStatus(replyTo: ActorRef[StatusResponse]) extends Command
  case class GetExecutionHistory(replyTo: ActorRef[ExecutionHistoryResponse]) extends Command
  case object Stop extends Command
  
  // 内部命令
  private case class NodeStarted(nodeId: String, nodeType: String) extends Command
  private case class NodeCompleted(nodeId: String, nodeType: String, duration: Long, records: Int) extends Command
  private case class NodeFailed(nodeId: String, nodeType: String, error: String) extends Command
  private case class ExecutionCompleted(result: ExecutionResult) extends Command
  private case class ExecutionFailed(error: Throwable) extends Command
  
  // ========== 响应 ==========
  
  sealed trait Response
  case class InitializeResponse(workflowId: String, status: String) extends Response
  case class ExecutionResponse(executionId: String, status: String) extends Response
  case class StatusResponse(
    workflowId: String,
    state: String,
    currentExecution: Option[ExecutionInfo],
    allExecutions: List[ExecutionSummary]
  ) extends Response
  
  case class ExecutionInfo(
    executionId: String,
    startTime: Long,
    endTime: Option[Long],
    status: String,
    completedNodes: Int,
    totalNodes: Int
  )
  
  case class ExecutionSummary(
    executionId: String,
    startTime: Long,
    endTime: Option[Long],
    status: String,
    duration: Option[Long]
  )
  
  case class ExecutionHistoryResponse(
    workflowId: String,
    executions: List[ExecutionDetail]
  ) extends Response
  
  case class ExecutionDetail(
    executionId: String,
    workflowName: String,
    startTime: Long,
    endTime: Option[Long],
    status: String,
    duration: Option[Long],
    nodes: List[NodeExecutionDetail]
  )
  
  case class NodeExecutionDetail(
    nodeId: String,
    nodeType: String,
    startTime: Long,
    endTime: Option[Long],
    duration: Option[Long],
    status: String,
    recordsProcessed: Int,
    error: Option[String]
  )
  
  // ========== 状态 ==========
  
  sealed trait State {
    def workflowId: String
    def allEvents: List[WorkflowEvent]
  }
  
  case class IdleState(
    workflowId: String,
    allEvents: List[WorkflowEvent] = List.empty
  ) extends State
  
  case class RunningState(
    workflowId: String,
    executionId: String,
    workflowName: String,
    startTime: Long,
    totalNodes: Int,
    completedNodes: Set[String],
    allEvents: List[WorkflowEvent]
  ) extends State
  
  case class CompletedState(
    workflowId: String,
    lastExecutionId: String,
    allEvents: List[WorkflowEvent]
  ) extends State
  
  case class FailedState(
    workflowId: String,
    lastExecutionId: String,
    reason: String,
    allEvents: List[WorkflowEvent]
  ) extends State
  
  // ========== 创建 Actor ==========
  
  def apply(
    workflowId: String,
    workflow: WorkflowDSL.Workflow,
    executionEngine: WorkflowExecutionEngine
  )(implicit ec: ExecutionContext): Behavior[Command] = {
    
    Behaviors.setup { context =>
      val cluster = Cluster(context.system)
      val nodeAddress = cluster.selfMember.address.toString
      val shardId = cn.xuyinyin.magic.workflow.sharding.WorkflowSharding.extractShardId(workflowId)
      
      // 记录Entity启动事件
      ClusterEventLogger.logEntityStarted(workflowId, shardId, nodeAddress)
      
      EventSourcedBehavior[Command, WorkflowEvent, State](
        persistenceId = PersistenceId.ofUniqueId(s"workflow-$workflowId"),
        emptyState = IdleState(workflowId),
        commandHandler = (state, command) => commandHandler(state, command, workflow, executionEngine, context),
        eventHandler = eventHandler
      )
      .withRetention(
        RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2)
      )
      .receiveSignal {
        case (state, org.apache.pekko.persistence.typed.RecoveryCompleted) =>
          context.log.info(s"Workflow $workflowId recovered: state=${getStateString(state)}, events=${state.allEvents.size}")
          
        case (state, org.apache.pekko.persistence.typed.SnapshotCompleted(metadata)) =>
          context.log.info(s"Snapshot completed for workflow $workflowId at seqNr ${metadata.sequenceNr}")
          
        case (state, org.apache.pekko.actor.typed.PostStop) =>
          context.log.info(s"Workflow $workflowId stopped")
          // 记录Entity停止事件
          ClusterEventLogger.logEntityStopped(workflowId, shardId, nodeAddress, "passivation")
      }
    }
  }
  
  private def getStateString(state: State): String = state match {
    case _: IdleState => "idle"
    case _: RunningState => "running"
    case _: CompletedState => "completed"
    case _: FailedState => "failed"
  }
  
  // ========== 命令处理 ==========
  
  private def commandHandler(
    state: State,
    command: Command,
    workflow: WorkflowDSL.Workflow,
    executionEngine: WorkflowExecutionEngine,
    context: ActorContext[Command]
  )(implicit ec: ExecutionContext): Effect[WorkflowEvent, State] = {
    
    (state, command) match {
      // Initialize命令：初始化工作流
      case (_, Initialize(wf, replyTo)) =>
        context.log.info(s"Initializing workflow: ${wf.id}")
        replyTo ! InitializeResponse(wf.id, "initialized")
        Effect.none
      
      // 空闲状态：开始执行
      case (idle: IdleState, Execute(replyTo)) =>
        val executionId = s"exec_${System.currentTimeMillis()}"
        context.log.info(s"Starting workflow execution: ${workflow.id} -> $executionId")
        
        replyTo ! ExecutionResponse(executionId, "started")
        
        // 发布 WorkflowStarted 事件
        Effect
          .persist(WorkflowStarted(
            workflowId = workflow.id,
            workflowName = workflow.name,
            executionId = executionId,
            totalNodes = workflow.nodes.size
          ))
          .thenRun { newState =>
            // 异步执行工作流
            startExecution(workflow, executionId, executionEngine, context)
          }
      
      // 运行状态：节点完成
      case (running: RunningState, NodeCompleted(nodeId, nodeType, duration, records)) =>
        Effect.persist(NodeExecutionCompleted(
          workflowId = running.workflowId,
          executionId = running.executionId,
          nodeId = nodeId,
          nodeType = nodeType,
          duration = duration,
          recordsProcessed = records
        ))
      
      // 运行状态：节点失败
      case (running: RunningState, NodeFailed(nodeId, nodeType, error)) =>
        Effect.persist(Seq(
          NodeExecutionFailed(
            workflowId = running.workflowId,
            executionId = running.executionId,
            nodeId = nodeId,
            nodeType = nodeType,
            error = error
          ),
          WorkflowFailed(
            workflowId = running.workflowId,
            executionId = running.executionId,
            reason = s"Node $nodeId failed: $error",
            failedNodeId = Some(nodeId)
          )
        ))
      
      // 运行状态：执行完成
      case (running: RunningState, ExecutionCompleted(result)) =>
        val duration = System.currentTimeMillis() - running.startTime
        Effect.persist(WorkflowCompleted(
          workflowId = running.workflowId,
          executionId = running.executionId,
          duration = duration,
          completedNodes = running.completedNodes.size
        ))
      
      // 运行状态：执行失败
      case (running: RunningState, ExecutionFailed(error)) =>
        Effect.persist(WorkflowFailed(
          workflowId = running.workflowId,
          executionId = running.executionId,
          reason = error.getMessage,
          failedNodeId = None
        ))
      
      // 查询状态
      case (_, GetStatus(replyTo)) =>
        replyTo ! buildStatusResponse(state)
        Effect.none
      
      // 查询执行历史
      case (_, GetExecutionHistory(replyTo)) =>
        replyTo ! buildExecutionHistory(state)
        Effect.none
      
      // 完成状态：可以重新执行
      case (_: CompletedState, Execute(replyTo)) =>
        commandHandler(IdleState(state.workflowId, state.allEvents), Execute(replyTo), workflow, executionEngine, context)
      
      // 失败状态：可以重试
      case (_: FailedState, Execute(replyTo)) =>
        commandHandler(IdleState(state.workflowId, state.allEvents), Execute(replyTo), workflow, executionEngine, context)
      
      // 停止
      case (_, Stop) =>
        Effect.stop()
      
      case _ =>
        Effect.none
    }
  }
  
  // ========== 事件处理 ==========
  
  private def eventHandler(state: State, event: WorkflowEvent): State = {
    val updatedEvents = state.allEvents :+ event
    
    event match {
      case WorkflowStarted(wfId, name, execId, total, _) =>
        RunningState(
          workflowId = wfId,
          executionId = execId,
          workflowName = name,
          startTime = event.timestamp,
          totalNodes = total,
          completedNodes = Set.empty,
          allEvents = updatedEvents
        )
      
      case NodeExecutionCompleted(wfId, execId, nodeId, _, _, _, _) =>
        state match {
          case running: RunningState =>
            running.copy(
              completedNodes = running.completedNodes + nodeId,
              allEvents = updatedEvents
            )
          case _ => state
        }
      
      case WorkflowCompleted(wfId, execId, _, _, _) =>
        CompletedState(
          workflowId = wfId,
          lastExecutionId = execId,
          allEvents = updatedEvents
        )
      
      case WorkflowFailed(wfId, execId, reason, _, _) =>
        FailedState(
          workflowId = wfId,
          lastExecutionId = execId,
          reason = reason,
          allEvents = updatedEvents
        )
      
      case _ =>
        state match {
          case idle: IdleState => idle.copy(allEvents = updatedEvents)
          case running: RunningState => running.copy(allEvents = updatedEvents)
          case completed: CompletedState => completed.copy(allEvents = updatedEvents)
          case failed: FailedState => failed.copy(allEvents = updatedEvents)
        }
    }
  }
  
  // ========== 辅助方法 ==========
  
  private def startExecution(
    workflow: WorkflowDSL.Workflow,
    executionId: String,
    executionEngine: WorkflowExecutionEngine,
    context: ActorContext[Command]
  )(implicit ec: ExecutionContext): Unit = {
    
    context.pipeToSelf(
      executionEngine.execute(
        workflow,
        executionId,
        msg => context.log.info(s"[$executionId] $msg")
      )
    ) {
      case Success(result) => ExecutionCompleted(result)
      case Failure(ex) => ExecutionFailed(ex)
    }
  }
  
  private def buildStatusResponse(state: State): StatusResponse = {
    val executions = extractExecutionSummaries(state.allEvents)
    val currentExecution = state match {
      case running: RunningState =>
        Some(ExecutionInfo(
          executionId = running.executionId,
          startTime = running.startTime,
          endTime = None,
          status = "running",
          completedNodes = running.completedNodes.size,
          totalNodes = running.totalNodes
        ))
      case _ => None
    }
    
    val stateStr = state match {
      case _: IdleState => "idle"
      case _: RunningState => "running"
      case _: CompletedState => "completed"
      case _: FailedState => "failed"
    }
    
    StatusResponse(
      workflowId = state.workflowId,
      state = stateStr,
      currentExecution = currentExecution,
      allExecutions = executions
    )
  }
  
  private def buildExecutionHistory(state: State): ExecutionHistoryResponse = {
    val details = extractExecutionDetails(state.allEvents)
    ExecutionHistoryResponse(
      workflowId = state.workflowId,
      executions = details
    )
  }
  
  private def extractExecutionSummaries(events: List[WorkflowEvent]): List[ExecutionSummary] = {
    events.collect { case e: WorkflowStarted => e }.map { started =>
      val endEvent = events.collectFirst {
        case e: WorkflowCompleted if e.executionId == started.executionId => e
        case e: WorkflowFailed if e.executionId == started.executionId => e
      }
      
      val (status, endTime, duration) = endEvent match {
        case Some(completed: WorkflowCompleted) =>
          ("completed", Some(completed.timestamp), Some(completed.duration))
        case Some(failed: WorkflowFailed) =>
          ("failed", Some(failed.timestamp), Some(failed.timestamp - started.timestamp))
        case None =>
          ("running", None, None)
      }
      
      ExecutionSummary(
        executionId = started.executionId,
        startTime = started.timestamp,
        endTime = endTime,
        status = status,
        duration = duration
      )
    }
  }
  
  private def extractExecutionDetails(events: List[WorkflowEvent]): List[ExecutionDetail] = {
    val executionIds = events.collect { case e: WorkflowStarted => e.executionId }.distinct
    
    executionIds.map { execId =>
      val execEvents = events.filter(_.asInstanceOf[{ def executionId: String }].executionId == execId)
      
      val started = execEvents.collectFirst { case e: WorkflowStarted => e }.get
      val endEvent = execEvents.collectFirst {
        case e: WorkflowCompleted => e
        case e: WorkflowFailed => e
      }
      
      val (status, endTime, duration) = endEvent match {
        case Some(completed: WorkflowCompleted) =>
          ("completed", Some(completed.timestamp), Some(completed.duration))
        case Some(failed: WorkflowFailed) =>
          ("failed", Some(failed.timestamp), Some(failed.timestamp - started.timestamp))
        case None =>
          ("running", None, None)
      }
      
      val nodeDetails = extractNodeDetails(execEvents, execId)
      
      ExecutionDetail(
        executionId = execId,
        workflowName = started.workflowName,
        startTime = started.timestamp,
        endTime = endTime,
        status = status,
        duration = duration,
        nodes = nodeDetails
      )
    }
  }
  
  private def extractNodeDetails(events: List[WorkflowEvent], execId: String): List[NodeExecutionDetail] = {
    val nodeStartEvents = events.collect { case e: NodeExecutionStarted if e.executionId == execId => e }
    
    nodeStartEvents.map { started =>
      val completed = events.collectFirst {
        case e: NodeExecutionCompleted if e.executionId == execId && e.nodeId == started.nodeId => e
      }
      val failed = events.collectFirst {
        case e: NodeExecutionFailed if e.executionId == execId && e.nodeId == started.nodeId => e
      }
      
      val (status, endTime, duration, records, error) = (completed, failed) match {
        case (Some(c), _) =>
          ("completed", Some(c.timestamp), Some(c.duration), c.recordsProcessed, None)
        case (_, Some(f)) =>
          ("failed", Some(f.timestamp), Some(f.timestamp - started.timestamp), 0, Some(f.error))
        case _ =>
          ("running", None, None, 0, None)
      }
      
      NodeExecutionDetail(
        nodeId = started.nodeId,
        nodeType = started.nodeType,
        startTime = started.timestamp,
        endTime = endTime,
        duration = duration,
        status = status,
        recordsProcessed = records,
        error = error
      )
    }
  }
}
