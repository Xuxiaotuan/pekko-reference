package cn.xuyinyin.magic.workflow.actors

import cn.xuyinyin.magic.common.CborSerializable
import cn.xuyinyin.magic.workflow.checkpoint.{BatchCheckpoint, BatchId, SnapshotBoundary}
import cn.xuyinyin.magic.workflow.engine.{CdcExecutionException, ExecutionFailure, ExecutionResult}
import cn.xuyinyin.magic.workflow.execution.{ReliableRunContext, WorkflowExecutionEngine}
import cn.xuyinyin.magic.workflow.execution.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.events.WorkflowEvents._
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.{ActorRef, Behavior, PostStop}
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.RecoveryCompleted
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EffectBuilder, EventSourcedBehavior, RetentionCriteria}
import org.apache.pekko.util.Timeout

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

/** The workflow entity's bounded durable projection; the journal is the full audit trail. */
object EventSourcedWorkflowActor {
  import cn.xuyinyin.magic.workflow.snapshot._
  import cn.xuyinyin.magic.workflow.execution.SnapshotSplitRunContext
  import cn.xuyinyin.magic.workflow.scheduling.TableGrant
  import WorkflowDefinitionCodec.{canonicalAndValidateJson, decodeWorkflow, workflowSourceNode}
  import WorkflowHistory._
  private[actors] val MaxRecentExecutions = 50
  private[actors] val MaxManualRequests = 100
  private[actors] val MaxScheduleWatermarks = 100
  private[actors] val MaxWorkflowNodes = 100
  private[actors] val MaxWorkflowJsonBytes = 65536
  private[actors] val MaxPersistedNodeResults = 100
  private[actors] val MaxPersistedMessageBytes = 4096
  private[actors] val DefaultHistoryPageSize = 50
  private[actors] val KafkaSourceNodeType = "kafka.consumer"

  sealed trait Command extends CborSerializable
  sealed trait Reply extends CborSerializable

  final case class DefineWorkflow(workflowJson: String, expectedRevision: Long, replyTo: ActorRef[Reply]) extends Command
  object DefineWorkflow {
    def apply(workflow: WorkflowDSL.Workflow, expectedRevision: Long, replyTo: ActorRef[Reply]): DefineWorkflow =
      new DefineWorkflow(canonicalWorkflowJson(workflow), expectedRevision, replyTo)
  }
  final case class ExecuteManual(requestId: String, replyTo: ActorRef[Reply]) extends Command
  final case class ExecuteScheduled(scheduleId: String, scheduledAt: Long, triggerId: String, replyTo: ActorRef[Reply]) extends Command
  final case class SchedulingUnavailable(reason: String) extends Reply
  final case class ActivateSnapshot(grant: TableGrant) extends Command
  private final case class SnapshotQueued(id: String, token: String) extends Command
  private final case class SnapshotQueueFailed(id: String, token: String) extends Command
  private final case class SnapshotSchedulingReleased(id: String, grantId: Option[String], stopAfter: Boolean) extends Command
  private final case class SnapshotDispositionFailed(id: String, grantId: Option[String], disposition: String, confirmed: Boolean, stopAfter: Boolean) extends Command
  private final case class RetrySnapshotDisposition(id: String, grantId: Option[String], disposition: String, confirmed: Boolean, stopAfter: Boolean) extends Command
  final case class InitializeSnapshot(executionId: String, boundary: SnapshotBoundary, replyTo: ActorRef[Reply]) extends Command
  final case class AdvanceCheckpoint(executionId: String, checkpoint: BatchCheckpoint, replyTo: ActorRef[Reply]) extends Command
  final case class ReadSnapshotSplits(executionId: String, attemptId: String, replyTo: ActorRef[Reply]) extends Command
  final case class UpdateSnapshotSplits(executionId: String, attemptId: String, update: SnapshotSplitUpdate, replyTo: ActorRef[Reply]) extends Command
  final case class SnapshotSplitsRead(value: Option[SnapshotSplitState]) extends Reply
  final case class SnapshotSplitsStored(value: SnapshotSplitState) extends Reply
  final case class GetSummary(replyTo: ActorRef[WorkflowSummary]) extends Command
  final case class GetStatus(replyTo: ActorRef[StatusResponse]) extends Command
  final case class GetExecutionHistory(page: Int, pageSize: Int, replyTo: ActorRef[ExecutionHistoryResponse]) extends Command
  object GetExecutionHistory {
    def apply(replyTo: ActorRef[ExecutionHistoryResponse]): GetExecutionHistory =
      new GetExecutionHistory(0, DefaultHistoryPageSize, replyTo)
  }
  private[actors] final case class GetReliableRunState(replyTo: ActorRef[ReliableRunState]) extends Command

  /** Kept temporarily so existing supervisor/sharding callers remain source-compatible. */
  final case class Initialize(workflowJson: String, replyTo: ActorRef[InitializeResponse]) extends Command
  object Initialize {
    def apply(workflow: WorkflowDSL.Workflow, replyTo: ActorRef[InitializeResponse]): Initialize =
      new Initialize(canonicalWorkflowJson(workflow), replyTo)
  }
  final case class Execute(replyTo: ActorRef[ExecutionResponse]) extends Command
  case object Stop extends Command
  private final case class EngineFinished(executionId: String, attemptId: String, result: ExecutionResult) extends Command
  private final case class EngineCrashed(executionId: String, attemptId: String, message: String, failure: ExecutionFailure) extends Command
  private final case class SnapshotPermitChecked(grant: Option[TableGrant]) extends Command
  private case object SnapshotIngressCompleted extends Command
  private case object SnapshotPermitUnavailable extends Command
  private final case class RecoverInterrupted(executionId: String) extends Command
  private final case class RecoveryEngineLaunched(executionId: String, attemptId: String) extends Command

  private final case class StopReleased(token: String) extends Command
  private final case class StopReleaseFailed(token: String) extends Command
  private final case class StopResourcesReleased(token: String, id: String, grantId: Option[String]) extends Command
  private final case class RetryStopDisposition(token: String, id: String, grantId: Option[String]) extends Command
  private final case class StopDispositionFailed(token: String, id: String, grantId: Option[String]) extends Command
  private final case class BeginRecovery(executionId: String, attempt: Int) extends Command
  private final case class RecoveryReleaseChecked(executionId: String, attempt: Int, status: WorkflowExecutionEngine.ReleaseStatus) extends Command
  private final case class InitializeFromAttempt(executionId: String, attemptId: String, boundary: SnapshotBoundary, replyTo: ActorRef[Reply]) extends Command
  private final case class CheckpointFromAttempt(executionId: String, attemptId: String, checkpoint: BatchCheckpoint, replyTo: ActorRef[Reply]) extends Command

  final case class Defined(workflowId: String, revision: Long) extends Reply
  final case class RevisionConflict(workflowId: String, expectedRevision: Long, actualRevision: Long) extends Reply
  final case class ExecutionAccepted(executionId: String) extends Reply
  final case class DuplicateExecution(requestId: String, executionId: String) extends Reply
  final case class AlreadyRunning(executionId: String) extends Reply
  final case class NotInitialized(workflowId: String) extends Reply
  final case class DefinitionRejected(workflowId: String, errors: Vector[String]) extends Reply
  final case class SnapshotInitialized(boundary: SnapshotBoundary) extends Reply
  final case class SnapshotAlreadyInitialized(boundary: SnapshotBoundary) extends Reply
  final case class CheckpointAccepted(checkpoint: BatchCheckpoint) extends Reply
  final case class CheckpointAlreadyStored(checkpoint: BatchCheckpoint) extends Reply
  final case class CheckpointRejected(reason: String) extends Reply
  sealed trait Response extends Reply
  final case class InitializeResponse(workflowId: String, status: String) extends Response
  final case class ExecutionResponse(executionId: String, status: String) extends Response

  final case class WorkflowStatus(value: String) extends CborSerializable
  object WorkflowStatus {
    val Uninitialized: WorkflowStatus = WorkflowStatus("uninitialized")
    val Running: WorkflowStatus = WorkflowStatus("running")
    val Completed: WorkflowStatus = WorkflowStatus("completed")
    val Failed: WorkflowStatus = WorkflowStatus("failed")
  }
  val Uninitialized: WorkflowStatus = WorkflowStatus.Uninitialized
  val Running: WorkflowStatus = WorkflowStatus.Running
  val Completed: WorkflowStatus = WorkflowStatus.Completed
  val Failed: WorkflowStatus = WorkflowStatus.Failed

  final case class ExecutionState(
    executionId: String,
    trigger: ExecutionTrigger,
    startedAt: Long,
    workflowRevision: Long = 0L,
    resumable: Boolean = false,
    boundary: Option[SnapshotBoundary] = None,
    checkpoints: Vector[BatchCheckpoint] = Vector.empty,
    recovery: Option[ExecutionRecoveryState] = None
  ) extends CborSerializable
  final case class ManualRequestRecord(requestId: String, executionId: String) extends CborSerializable
  final case class SnapshotAdmissionState(phase: String, grantId: String) extends CborSerializable
  final case class ExecutionSummary(
    executionId: String,
    startTime: Long,
    endTime: Option[Long],
    status: String,
    duration: Option[Long]
  ) extends CborSerializable
  final case class StoredExecutionSummary(
    executionId: String,
    startTime: Long,
    endTime: Long,
    hasEndTime: Boolean,
    status: String,
    duration: Long,
    hasDuration: Boolean,
    result: Option[PersistedExecutionResult] = None
  ) extends CborSerializable
  final case class ScheduleWatermark(scheduleId: String, scheduledAt: Long) extends CborSerializable
  final case class WorkflowSourceProgress(
    workflowRevision: Long,
    sourceNodeId: String,
    sourceNodeType: String,
    checkpoint: BatchCheckpoint
  ) extends CborSerializable
  final case class WorkflowState(
    workflowJson: Option[String] = None,
    revision: Long = 0L,
    status: WorkflowStatus = Uninitialized,
    currentExecution: Option[ExecutionState] = None,
    recentExecutions: Vector[StoredExecutionSummary] = Vector.empty,
    lastAcceptedTriggerBySchedule: Vector[ScheduleWatermark] = Vector.empty,
    manualRequests: Vector[ManualRequestRecord] = Vector.empty,
    workflowSourceProgress: Option[WorkflowSourceProgress] = None,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_ABSENT)
    splitSnapshot: Option[SnapshotSplitState] = None,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_ABSENT)
    snapshotAdmission: Option[SnapshotAdmissionState] = None
  ) extends CborSerializable

  final case class WorkflowSummary(
    workflowId: String,
    revision: Long,
    status: WorkflowStatus,
    currentExecution: Option[ExecutionState],
    recentExecutions: Vector[ExecutionSummary]
  ) extends CborSerializable
  private[actors] final case class ReliableRunState(currentExecution: Option[ExecutionState]) extends CborSerializable
  final case class ExecutionInfo(executionId: String, startTime: Long, endTime: Option[Long], status: String, completedNodes: Int, totalNodes: Int) extends CborSerializable
  final case class StatusResponse(workflowId: String, state: String, currentExecution: Option[ExecutionInfo], allExecutions: List[ExecutionSummary]) extends Response
  final case class NodeExecutionDetail(nodeId: String, nodeType: String, startTime: Option[Long], endTime: Option[Long], duration: Option[Long], status: String, recordsProcessed: Option[Int], error: Option[String]) extends CborSerializable
  final case class ExecutionDetail(executionId: String, workflowName: String, startTime: Long, endTime: Option[Long], status: String, duration: Option[Long], nodes: List[NodeExecutionDetail]) extends CborSerializable
  final case class ExecutionHistoryResponse(workflowId: String, executions: List[ExecutionDetail]) extends Response

  def apply(workflowId: String, executionEngine: WorkflowExecutionEngine)(implicit ec: ExecutionContext): Behavior[Command] = behavior(workflowId, executionEngine)
  def apply(workflowId: String, workflow: WorkflowDSL.Workflow, executionEngine: WorkflowExecutionEngine)(implicit ec: ExecutionContext): Behavior[Command] = behavior(workflowId, executionEngine)
  def apply(workflowId: String, executionEngine: WorkflowExecutionEngine, passivate: ActorRef[Command] => Unit)
    (implicit ec: ExecutionContext): Behavior[Command] = behavior(workflowId,executionEngine,Some(passivate))

  private def behavior(workflowId: String, executionEngine: WorkflowExecutionEngine, shardPassivate: Option[ActorRef[Command] => Unit] = None)(implicit ec: ExecutionContext): Behavior[Command] =
    executionEngine.tableScheduling.filter(_.store.ownership.nonEmpty) match {
      case None => persistentBehavior(workflowId,executionEngine,shardPassivate)
      case Some(runtime) => Behaviors.setup { context => Behaviors.withStash[Command](32) { stash =>
        def stopGate():Behavior[Command]={shardPassivate.foreach(_(context.self));Behaviors.stopped}
        def pendingIngress:Behavior[Command]=Behaviors.receiveMessage {
          case SnapshotIngressCompleted | Stop=>stopGate()
          case command=>
            managedIngressRequest(workflowId,executionEngine,command).foreach(result=>context.pipeToSelf(result)(_=>SnapshotIngressCompleted))
            Behaviors.same
        }
        def pendingPermit:Behavior[Command]=Behaviors.receiveMessage {
          case SnapshotPermitChecked(Some(_))=>stash.unstashAll(persistentBehavior(workflowId,executionEngine,shardPassivate))
          case SnapshotPermitChecked(None) | SnapshotPermitUnavailable | Stop=>stopGate()
          case _ if stash.isFull=>stopGate()
          case command=>stash.stash(command);Behaviors.same
        }
        Behaviors.receiveMessage { command=>
          managedIngressRequest(workflowId,executionEngine,command) match {
            case Some(result)=>context.pipeToSelf(result)(_=>SnapshotIngressCompleted);pendingIngress
            case None=>command match {
              case Stop=>stopGate()
              case ActivateSnapshot(grant) if runtime.attach(workflowId,context.self)(context.system)=>
                stash.stash(command)
                context.pipeToSelf(runtime.acquireRecovery(workflowId,grant.id)) {
                  case Success(grant)=>SnapshotPermitChecked(grant)
                  case Failure(_)=>SnapshotPermitUnavailable
                };pendingPermit
              case _=>stopGate()
            }
          }
        }
      }}
    }

  private def managedIngressRequest(workflowId:String,engine:WorkflowExecutionEngine,command:Command)
    (implicit ec:ExecutionContext):Option[scala.concurrent.Future[Unit]]=engine.snapshotIngress.flatMap { ingress=>
    val result:Option[scala.concurrent.Future[Unit]]=command match {
      case GetSummary(reply)=>Some(ingress.read(workflowId).map(s=>reply ! WorkflowSummary(workflowId,s.revision,s.status,s.currentExecution,s.recentExecutions.map(WorkflowHistory.publicSummary))))
      case GetStatus(reply)=>Some(ingress.read(workflowId).map(s=>reply ! WorkflowHistory.statusResponse(workflowId,s)))
      case GetExecutionHistory(page,size,reply)=>Some(ingress.read(workflowId).map(s=>reply ! WorkflowHistory.historyResponse(workflowId,s,page,size)))
      case ExecuteManual(request,reply)=>Some(ingress.manual(workflowId,request).map(reply ! _))
      case ExecuteScheduled(schedule,at,trigger,reply)=>Some(ingress.scheduled(workflowId,schedule,at,trigger).map(reply ! _))
      case Execute(reply)=>Some(ingress.manual(workflowId,java.util.UUID.randomUUID().toString).map {
        case ExecutionAccepted(id)=>reply ! ExecutionResponse(id,"started")
        case AlreadyRunning(id)=>reply ! ExecutionResponse(id,"already_running")
        case DuplicateExecution(_,id)=>reply ! ExecutionResponse(id,"already_running")
        case _=>reply ! ExecutionResponse("","not_initialized")
      })
      case DefineWorkflow(json,expected,reply)=>
        import spray.json._
        import WorkflowDSL.workflowFormat
        if(expected!=0L)Some(scala.concurrent.Future.successful(reply ! DefinitionRejected(workflowId,Vector("managed revisions must use snapshot-tasks ingress"))))
        else Some(scala.concurrent.Future(json.parseJson.convertTo[WorkflowDSL.Workflow]).flatMap(w=>engine.tableScheduling.get.register(w,1L)).map(t=>reply ! Defined(workflowId,t.definition.revision)))
      case _=>None
    }
    result.map(_.recover {case NonFatal(_)=>
      val target:Option[ActorRef[_]]=command match {
        case GetSummary(r)=>Some(r);case GetStatus(r)=>Some(r);case GetExecutionHistory(_,_,r)=>Some(r)
        case ExecuteManual(_,r)=>Some(r);case ExecuteScheduled(_,_,_,r)=>Some(r);case Execute(r)=>Some(r);case DefineWorkflow(_,_,r)=>Some(r)
        case _=>None
      }
      target.foreach(_.asInstanceOf[ActorRef[Any]] ! org.apache.pekko.actor.Status.Failure(new java.util.concurrent.RejectedExecutionException("managed query or scheduling result unavailable; retry the same request")))
    })
  }

  private def persistentBehavior(workflowId: String, executionEngine: WorkflowExecutionEngine, shardPassivate: Option[ActorRef[Command] => Unit])(implicit ec: ExecutionContext): Behavior[Command] =
    Behaviors.setup { context => Behaviors.withTimers[Command] { timers =>
      val recoveryGate = new AtomicBoolean(false)
      val activeEngineExecutionId = new AtomicReference[String]()
      val activeAttemptId = new AtomicReference[String]()
      val stopping = new AtomicBoolean(false)
      val passivating = new AtomicBoolean(false)
      val passivate: () => Unit = () => if(passivating.compareAndSet(false,true)) {
        shardPassivate match { case Some(request) => request(context.self); case None => context.self ! Stop }
      }
      val stop = new StopController(workflowId, executionEngine, context, timers, stopping, activeEngineExecutionId, activeAttemptId)
      val recovery = new RecoveryController(executionEngine, context, timers, recoveryGate, activeEngineExecutionId, activeAttemptId, stopping)
      val snapshotEvery = context.system.settings.config.getInt("pekko.workflow.event-sourcing.snapshot-every")
      val keepSnapshots = context.system.settings.config.getInt("pekko.workflow.event-sourcing.keep-n-snapshots")
      EventSourcedBehavior[Command, WorkflowEvent, WorkflowState](
        PersistenceId.ofUniqueId(s"workflow-$workflowId"),
        WorkflowState(),
        commandHandler(workflowId, executionEngine, context, recoveryGate, activeEngineExecutionId, activeAttemptId, recovery, stop, timers,passivate,passivating),
        WorkflowStateTransitions.apply
      ).withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = snapshotEvery, keepNSnapshots = keepSnapshots))
        .receiveSignal {
          case (state, RecoveryCompleted) =>
            state.currentExecution.foreach { execution =>
              recoveryGate.set(true)
              context.self ! RecoverInterrupted(execution.executionId)
            }
          case (state, PostStop) =>
            cancelActiveExecution(state, executionEngine, activeEngineExecutionId)
        }
    }}

  private def commandHandler(
    workflowId: String,
    executionEngine: WorkflowExecutionEngine,
    context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
    recoveryGate: AtomicBoolean,
    activeEngineExecutionId: AtomicReference[String],
    activeAttemptId: AtomicReference[String],
    recovery: RecoveryController,
    stop: StopController,
    timers: TimerScheduler[Command],
    passivate: () => Unit,
    passivating: AtomicBoolean
  )
    (state: WorkflowState, command: Command)(implicit ec: ExecutionContext): Effect[WorkflowEvent, WorkflowState] =
    stop.intercept(state, command).getOrElse {
    val ingress=managedIngressRequest(workflowId,executionEngine,command)
    if(ingress.nonEmpty) Effect.none
    else if (recoveryGate.get() && !allowedDuringRecovery(command)) Effect.stash()
    else command match {
    case _: ActivateSnapshot if passivating.get() => Effect.none
    case ActivateSnapshot(grant) =>
      activateSnapshot(workflowId, state, grant, executionEngine, context, recoveryGate, activeEngineExecutionId, activeAttemptId,passivate)
    case SnapshotQueued(id, token) if activeAttemptId.get() == token && state.currentExecution.exists(_.executionId == id) && activeEngineExecutionId.get() == null =>
      Effect.persist(SnapshotSchedulingChanged(id,"queued","",now())).thenRun((_: WorkflowState) => {recoveryGate.set(false);passivate()})
    case SnapshotQueueFailed(id, token) if activeAttemptId.get() == token && state.currentExecution.exists(_.executionId == id) =>
      context.scheduleOnce(1.second,context.self,RecoverInterrupted(id))
      releaseRecoveryGateAfter(Effect.persist(SnapshotSchedulingChanged(id,"queued","",now())), recoveryGate)
    case SnapshotSchedulingReleased(id, grantId, stopAfter) =>
      timers.cancel(("snapshot-disposition",id,grantId))
      val sameRun = state.currentExecution.forall(_.executionId == id)
      val sameGrant = state.snapshotAdmission.forall(a => grantId.contains(a.grantId))
      if (stopAfter && sameRun && sameGrant && activeEngineExecutionId.get() == null) passivate()
      Effect.none
    case SnapshotDispositionFailed(id, grantId, disposition, confirmed, stopAfter) =>
      timers.startSingleTimer(("snapshot-disposition",id,grantId),RetrySnapshotDisposition(id,grantId,disposition,confirmed,stopAfter),1.second)
      Effect.none
    case RetrySnapshotDisposition(id, grantId, disposition, confirmed, stopAfter) =>
      releaseScheduled(executionEngine,context,id,disposition,confirmed,stopAfter,grantId)
      Effect.none
    case ReadSnapshotSplits(id, token, replyTo) =>
      if (token != activeAttemptId.get() || !state.currentExecution.exists(_.executionId == id)) rejectCheckpoint(replyTo, "stale snapshot execution")
      else immediate(replyTo, SnapshotSplitsRead(state.splitSnapshot))
    case UpdateSnapshotSplits(id, token, update, replyTo) =>
      if (token != activeAttemptId.get() || !state.currentExecution.exists(_.executionId == id)) rejectCheckpoint(replyTo, "stale snapshot execution")
      else {
        val validIdentity = update match {
          case CommitSnapshotPage(c, _) => c.batchId == BatchId.sha256(id, c.sourceNodeId, c.partitionId, c.batchSequence)
          case InitializeSnapshotSplits(p) => reliableSourceNodeId(state).contains(p.sourceNodeId)
          case _ => true
        }
        val next = if (validIdentity) scala.util.Try(SnapshotSplitState.advance(state.splitSnapshot, update)).toOption.flatMap(_.toOption) else None
        next match {
          case Some(value) => Effect.persist(SnapshotSplitsUpdated(id, update, now())).thenReply(replyTo)(_ => SnapshotSplitsStored(value))
          case None => rejectCheckpoint(replyTo, "snapshot split update rejected")
        }
      }
    case DefineWorkflow(_, _, replyTo) if state.currentExecution.nonEmpty => immediate(replyTo, AlreadyRunning(state.currentExecution.get.executionId))
    case DefineWorkflow(workflowJson, _, replyTo) if state.revision>0L && executionEngine.tableScheduling.nonEmpty &&
      (decodeWorkflow(state).exists(executionEngine.managedSnapshot) ||
        decodeWorkflow(state.copy(workflowJson=Some(workflowJson))).exists(executionEngine.managedSnapshot)) =>
      immediate(replyTo,DefinitionRejected(workflowId,Vector("managed snapshot revisions must use the durable snapshot-tasks ingress")))
    case DefineWorkflow(workflowJson, expectedRevision, replyTo) => validateDefinition(workflowId, state, workflowJson, expectedRevision, replyTo, executionEngine.nodeRegistry,
      json => if(decodeWorkflow(state.copy(workflowJson=Some(json))).exists(executionEngine.managedSnapshot))passivate())
    case Initialize(workflowJson, replyTo) =>
      if (state.revision != 0L) { replyTo ! InitializeResponse(workflowId, "initialized"); Effect.none }
      else canonicalAndValidateJson(workflowJson, executionEngine.nodeRegistry) match {
        case Left(_) => replyTo ! InitializeResponse(workflowId, "rejected"); Effect.none
        case Right(json) => Effect.persist(WorkflowDefined(json, 1L, now())).thenReply(replyTo)(_ => InitializeResponse(workflowId, "initialized"))
      }
    case ExecuteManual(requestId, replyTo) => startManual(workflowId, state, requestId, replyTo, executionEngine, context, activeEngineExecutionId, activeAttemptId)
    case ExecuteScheduled(scheduleId, scheduledAt, triggerId, replyTo) => startScheduled(workflowId, state, scheduleId, scheduledAt, triggerId, replyTo, executionEngine, context, activeEngineExecutionId, activeAttemptId)
    case InitializeFromAttempt(id, token, boundary, replyTo) =>
      if (token != activeAttemptId.get()) rejectCheckpoint(replyTo, "stale execution attempt")
      else initializeSnapshot(state, id, boundary, replyTo)
    case CheckpointFromAttempt(id, token, checkpoint, replyTo) =>
      if (token != activeAttemptId.get()) rejectCheckpoint(replyTo, "stale execution attempt")
      else advanceCheckpoint(state, id, checkpoint, replyTo)
    case InitializeSnapshot(_, _, replyTo) if state.currentExecution.exists(_.recovery.nonEmpty) => rejectCheckpoint(replyTo, "execution attempt identity required")
    case AdvanceCheckpoint(_, _, replyTo) if state.currentExecution.exists(_.recovery.nonEmpty) => rejectCheckpoint(replyTo, "execution attempt identity required")
    case InitializeSnapshot(executionId, boundary, replyTo) => initializeSnapshot(state, executionId, boundary, replyTo)
    case AdvanceCheckpoint(executionId, checkpoint, replyTo) => advanceCheckpoint(state, executionId, checkpoint, replyTo)
    case Execute(replyTo) => startLegacyManual(workflowId, state, replyTo, executionEngine, context, activeEngineExecutionId, activeAttemptId)
    case GetSummary(replyTo) => replyTo ! WorkflowSummary(workflowId, state.revision, state.status, state.currentExecution, state.recentExecutions.map(publicSummary)); snapshotReadEffect(state,executionEngine,passivate)
    case GetStatus(replyTo) => replyTo ! statusResponse(workflowId, state); snapshotReadEffect(state,executionEngine,passivate)
    case GetExecutionHistory(page, pageSize, replyTo) => replyTo ! historyResponse(workflowId, state, page, pageSize); snapshotReadEffect(state,executionEngine,passivate)
    case GetReliableRunState(replyTo) => replyTo ! ReliableRunState(state.currentExecution); Effect.none
    case EngineFinished(executionId, token, result) if token == activeAttemptId.get() && state.currentExecution.exists(_.executionId == executionId) =>
      activeEngineExecutionId.compareAndSet(executionId, null)
      activeAttemptId.compareAndSet(token, null)
      if (Set("snapshot_yielded","snapshot_deferred").contains(result.status)) {
        val grantId = state.snapshotAdmission.fold("")(_.grantId)
        releaseRecoveryGateAfter(Effect.persist(SnapshotSchedulingChanged(executionId,
          if (result.status == "snapshot_yielded") "yielded" else "queued",grantId,now()))
          .thenRun((_: WorkflowState) => releaseScheduled(executionEngine, context, executionId,"ready",true,stopAfter=true,grantId=state.snapshotAdmission.map(_.grantId))), recoveryGate)
      } else if (!result.success && decodeWorkflow(state).exists(executionEngine.supportsSplitSnapshot)) {
        releaseRecoveryGateAfter(Effect.persist(SnapshotExecutionPaused(executionId, result.failure.fold("snapshot_execution_failed")(_.code), now()))
          .thenRun((_: WorkflowState) => releaseScheduled(executionEngine,context,executionId,"blocked",result.failure.forall(_.releaseConfirmed),stopAfter=decodeWorkflow(state).exists(executionEngine.managedSnapshot) && result.failure.forall(_.releaseConfirmed),grantId=state.snapshotAdmission.map(_.grantId))), recoveryGate)
      } else if (!result.success && recovery.eligible(state)) {
        recovery.decide(state, result.failure.getOrElse(ExecutionFailure(ExecutionFailure.Unclassified,"execution")))
      } else {
      val terminal: EffectBuilder[WorkflowEvent, WorkflowState] = boundedPersistedResult(result) match {
        case Right(persisted) => Effect.persist(if (result.success) ExecutionCompleted(executionId, persisted, now()) else ExecutionFailed(executionId, persisted, now()))
        case Left(reason) => Effect.persist(ExecutionFailed(executionId, failedPersistedResult(reason), now()))
      }
      releaseRecoveryGateAfter(terminal.thenRun((_: WorkflowState) =>
        releaseScheduled(executionEngine,context,executionId,if(result.success) "completed" else "blocked",result.failure.forall(_.releaseConfirmed),
          stopAfter=decodeWorkflow(state).exists(executionEngine.managedSnapshot),grantId=state.snapshotAdmission.map(_.grantId))), recoveryGate)
      }
    case EngineCrashed(executionId, token, message, failure) if token == activeAttemptId.get() && state.currentExecution.exists(_.executionId == executionId) =>
      activeEngineExecutionId.compareAndSet(executionId, null)
      activeAttemptId.compareAndSet(token, null)
      if (decodeWorkflow(state).exists(executionEngine.supportsSplitSnapshot))
        releaseRecoveryGateAfter(Effect.persist(SnapshotExecutionPaused(executionId, failure.code, now()))
          .thenRun((_: WorkflowState) => releaseScheduled(executionEngine,context,executionId,"blocked",failure.releaseConfirmed,stopAfter=decodeWorkflow(state).exists(executionEngine.managedSnapshot) && failure.releaseConfirmed,grantId=state.snapshotAdmission.map(_.grantId))), recoveryGate)
      else if (recovery.eligible(state)) recovery.decide(state, failure)
      else releaseRecoveryGateAfter(
        Effect.persist(ExecutionFailed(executionId, failedPersistedResult(message), now())),
        recoveryGate
      )
    case BeginRecovery(id, attempt) => recovery.begin(state, id, attempt)
    case RecoveryReleaseChecked(id, attempt, status) => recovery.released(state, id, attempt, status)
    case RecoverInterrupted(executionId) if activeEngineExecutionId.get() == executionId =>
      releaseRecoveryGateAfter(Effect.none,recoveryGate)
    case RecoverInterrupted(executionId) if state.currentExecution.exists(_.executionId == executionId) && state.status.value == "snapshot_blocked" =>
      executionEngine.tableScheduling.filter(_.store.ownership.nonEmpty).foreach { runtime=>
        runtime.find(workflowId).foreach(_.foreach(t=>t.grantId.foreach(id=>runtime.reject(TableGrant(t,id)))))
        passivate()
      }
      releaseRecoveryGateAfter(Effect.none, recoveryGate)
    case RecoverInterrupted(executionId) if state.currentExecution.exists(_.executionId == executionId) && recovery.eligible(state) =>
      val current = state.currentExecution.get
      current.recovery match {
        case Some(r) if r.phase == "blocked" => releaseRecoveryGateAfter(Effect.none, recoveryGate)
        case Some(r) if r.phase == "waiting" =>
          recovery.arm(current)
          releaseRecoveryGateAfter(Effect.none, recoveryGate)
        case _ => recovery.decide(state, ExecutionFailure(ExecutionFailure.Interrupted,"recovery"))
      }
    case RecoverInterrupted(executionId) if state.currentExecution.exists(current => current.executionId == executionId && current.resumable) =>
      decodeWorkflow(state) match {
        case Left(errors) => releaseRecoveryGateAfter(
          Effect.persist(ExecutionFailed(executionId, failedPersistedResult(s"recovered definition is invalid: ${errors.mkString("; ")}"), now())),
          recoveryGate
        )
        case Right(workflow) =>
          val current = state.currentExecution.get
          Effect.none.thenRun { (_: WorkflowState) =>
            if (runEngine(workflow, current, executionEngine, context, activeEngineExecutionId, activeAttemptId)) {
              context.self ! RecoveryEngineLaunched(executionId, activeAttemptId.get())
            }
          }
      }
    case RecoverInterrupted(executionId) if state.currentExecution.exists(_.executionId == executionId) =>
      releaseRecoveryGateAfter(
        Effect.persist(ExecutionFailed(executionId, failedPersistedResult("interrupted/recovered"), now())),
        recoveryGate
      )
    case RecoveryEngineLaunched(_, token) if token == activeAttemptId.get() =>
      recoveryGate.set(false)
      Effect.unstashAll()
    case RecoverInterrupted(_) =>
      recoveryGate.set(false)
      Effect.unstashAll()
    case _ => Effect.none
    }}

  /** Local lifecycle only. Durable execution/progress remains available to the next owner. */
  private final class StopController(workflowId: String, engine: WorkflowExecutionEngine,
    context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command], timers: TimerScheduler[Command],
    stopping: AtomicBoolean, activeExecution: AtomicReference[String], activeAttempt: AtomicReference[String]
  ) {
    private var stopToken = ""
    private var waitingForRow = false
    private def finishRow(id: String, result: ExecutionResult): Effect[WorkflowEvent, WorkflowState] = {
      activeExecution.set(null); activeAttempt.set(null)
      val event = boundedPersistedResult(result) match {
        case Right(persisted) if result.success => ExecutionCompleted(id, persisted, now())
        case Right(persisted) => ExecutionFailed(id, persisted, now())
        case Left(reason) => ExecutionFailed(id, failedPersistedResult(reason), now())
      }
      Effect.persist(event).thenStop()
    }
    private def releaseDisposition(token: String, id: String, grantId: Option[String]): Unit = {
      context.pipeToSelf(engine.finishScheduledSnapshot(id,"ready",true,grantId)) {
        case Success(_) => StopReleased(token)
        case Failure(_) => StopDispositionFailed(token,id,grantId)
      }
    }
    def intercept(state: WorkflowState, command: Command): Option[Effect[WorkflowEvent, WorkflowState]] = command match {
      case Stop =>
        if (stopping.getAndSet(true)) Some(Effect.none)
        else {
          timers.cancel("cdc-recovery")
          state.currentExecution match {
            case Some(current) if current.resumable =>
              stopToken = java.util.UUID.randomUUID().toString
              val token = stopToken
              val released = try engine.cancel(current.executionId)
                catch { case NonFatal(e) => scala.concurrent.Future.failed(e) }
              context.pipeToSelf(released) {
                case Success(_) => StopResourcesReleased(token,current.executionId,grantId=state.snapshotAdmission.map(_.grantId))
                case Failure(_) => StopReleaseFailed(token)
              }
              Some(Effect.none)
            case Some(current) if activeExecution.get() == current.executionId =>
              // Row streams are not registered as reliable sessions. Their actual result is the fence.
              waitingForRow = true
              Some(Effect.none)
            case _ => Some(Effect.stop())
          }
        }
      case StopResourcesReleased(token,id,grantId) if stopping.get() && token == stopToken =>
        releaseDisposition(token,id,grantId)
        Some(Effect.none)
      case StopDispositionFailed(token,id,grantId) if stopping.get() && token == stopToken =>
        timers.startSingleTimer("snapshot-stop-disposition",RetryStopDisposition(token,id,grantId),1.second)
        Some(Effect.none)
      case RetryStopDisposition(token,id,grantId) if stopping.get() && token == stopToken =>
        releaseDisposition(token,id,grantId)
        Some(Effect.none)
      case StopReleased(token) if stopping.get() && token == stopToken =>
        activeExecution.set(null); activeAttempt.set(null); Some(Effect.stop())
      case StopReleaseFailed(token) if stopping.get() && token == stopToken =>
        context.log.error("Workflow {} remains stopping because actual release failed", workflowId)
        Some(Effect.none)
      case _ if stopping.get() => command match {
        case _: ActivateSnapshot => Some(Effect.none)
        case EngineFinished(id, token, result) if waitingForRow && activeExecution.get() == id && activeAttempt.get() == token => Some(finishRow(id, result))
        case EngineCrashed(id, token, message, _) if waitingForRow && activeExecution.get() == id && activeAttempt.get() == token =>
          Some(finishRow(id, ExecutionResult("failed", false, message, None)))
        case _: EngineFinished | _: EngineCrashed | _: BeginRecovery | _: RecoveryReleaseChecked |
             _: RecoverInterrupted | _: RecoveryEngineLaunched | _: StopReleased | _: StopReleaseFailed => Some(Effect.none)
        case ExecuteManual(_, replyTo) => Some(immediate(replyTo, DefinitionRejected(workflowId, Vector("workflow is stopping"))))
        case ExecuteScheduled(_, _, _, replyTo) => Some(immediate(replyTo, DefinitionRejected(workflowId, Vector("workflow is stopping"))))
        case DefineWorkflow(_, _, replyTo) => Some(immediate(replyTo, DefinitionRejected(workflowId, Vector("workflow is stopping"))))
        case Execute(replyTo) => replyTo ! ExecutionResponse(state.currentExecution.fold("")(_.executionId), "stopping"); Some(Effect.none)
        case Initialize(_, replyTo) => replyTo ! InitializeResponse(workflowId, "stopping"); Some(Effect.none)
        case GetSummary(replyTo) =>
          replyTo ! WorkflowSummary(workflowId, state.revision, WorkflowStatus("stopping"), state.currentExecution, state.recentExecutions.map(publicSummary))
          Some(Effect.none)
        case GetStatus(replyTo) => replyTo ! statusResponse(workflowId, state.copy(status=WorkflowStatus("stopping"))); Some(Effect.none)
        // Admitted initialization/J callbacks and read queries keep the normal handling path.
        case _ => None
      }
      case _ => None
    }
  }

  private final class RecoveryController(engine: WorkflowExecutionEngine,
    context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command], timers: TimerScheduler[Command],
    gate: AtomicBoolean, activeExecution: AtomicReference[String], activeAttempt: AtomicReference[String], stopping: AtomicBoolean
  )(implicit ec: ExecutionContext) {
    private val config = context.system.settings.config.getConfig("pekko.workflow.cdc-recovery")
    private val maxAttempts = config.getInt("max-auto-recoveries")
    private val initialBackoff = config.getDuration("initial-backoff").toMillis
    require(maxAttempts >= 0 && maxAttempts <= 3 && initialBackoff > 0, "invalid CDC recovery budget or backoff")
    private var checking = Option.empty[(String, Int)]
    def eligible(state: WorkflowState): Boolean = state.currentExecution.exists(_.resumable) &&
      decodeWorkflow(state).exists(engine.supportsCompleteGroupRecovery)
    private def blocked(state: WorkflowState, code: String, stage: String, fault: Option[ExecutionFailure] = None): Effect[WorkflowEvent, WorkflowState] =
      releaseRecoveryGateAfter(Effect.persist(ExecutionRecoveryBlocked(state.currentExecution.get.executionId,code,stage,now(),fault)),gate)
    def decide(state: WorkflowState, fault: ExecutionFailure): Effect[WorkflowEvent, WorkflowState] = {
      val current = state.currentExecution.get
      val spent = current.recovery.fold(0)(_.attemptsStarted)
      if (!fault.releaseConfirmed) blocked(state,ExecutionFailure.ReleaseUnknown,fault.stage,Some(fault))
      else if (!ExecutionFailure.Retryable(fault.code)) blocked(state,fault.code,fault.stage,Some(fault))
      else if (spent >= maxAttempts) blocked(state,ExecutionFailure.BudgetExhausted,fault.stage,Some(fault))
      else {
        val next = spent + 1
        val at = now() + initialBackoff * (1L << (next - 1))
        releaseRecoveryGateAfter(Effect.persist(ExecutionRecoveryScheduled(current.executionId,next,at,fault.code,fault.stage,now(),Some(fault)))
          .thenRun((updated: WorkflowState) => arm(updated.currentExecution.get)),gate)
      }
    }
    def arm(current: ExecutionState): Unit = current.recovery.filter(_.phase == "waiting").foreach { r =>
      timers.startSingleTimer("cdc-recovery",BeginRecovery(current.executionId,r.nextAttempt),math.max(0L,r.notBefore-now()).millis)
    }
    private def waiting(state: WorkflowState, id: String, attempt: Int): Boolean =
      state.currentExecution.exists(c => c.executionId==id && c.recovery.exists(r => r.phase=="waiting" && r.nextAttempt==attempt))
    def begin(state: WorkflowState, id: String, attempt: Int): Effect[WorkflowEvent, WorkflowState] = {
      if (waiting(state,id,attempt) && checking.isEmpty) {
        checking=Some(id -> attempt)
        context.pipeToSelf(engine.cancelWithin(id)) {
          case Success(status) => RecoveryReleaseChecked(id,attempt,status)
          case Failure(_) => RecoveryReleaseChecked(id,attempt,WorkflowExecutionEngine.CloseFailed)
        }
      }
      Effect.none
    }
    def released(state: WorkflowState, id: String, attempt: Int, status: WorkflowExecutionEngine.ReleaseStatus): Effect[WorkflowEvent, WorkflowState] = {
      if (!checking.contains(id -> attempt) || !waiting(state,id,attempt)) Effect.none
      else {
        checking=None
        if (status != WorkflowExecutionEngine.Released) blocked(state,ExecutionFailure.ReleaseUnknown,"release")
        else decodeWorkflow(state) match {
          case Left(_) => blocked(state,ExecutionFailure.ProtocolConflict,"definition")
          case Right(workflow) => Effect.persist(ExecutionRecoveryAttemptStarted(id,attempt,now())).thenRun { updated: WorkflowState =>
            if (!stopping.get()) runEngine(workflow,updated.currentExecution.get,engine,context,activeExecution,activeAttempt)
            ()
          }
        }
      }
    }
  }

  private def validateDefinition(workflowId: String, state: WorkflowState, workflowJson: String, expectedRevision: Long, replyTo: ActorRef[Reply], registry: NodeRegistry,
    onDefined: String => Unit = _ => ()): Effect[WorkflowEvent, WorkflowState] =
    if (expectedRevision != state.revision) { replyTo ! RevisionConflict(workflowId, expectedRevision, state.revision); Effect.none }
    else canonicalAndValidateJson(workflowJson, registry) match {
      case Left(errors) => replyTo ! DefinitionRejected(workflowId, errors); Effect.none
      case Right(json) =>
        val revision = state.revision + 1L
        Effect.persist(WorkflowDefined(json, revision, now())).thenRun((_: WorkflowState) => onDefined(json)).thenReply(replyTo)(_ => Defined(workflowId, revision))
    }

  private def startManual(workflowId: String, state: WorkflowState, requestId: String, replyTo: ActorRef[Reply], executionEngine: WorkflowExecutionEngine, context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command], activeEngineExecutionId: AtomicReference[String], activeAttemptId: AtomicReference[String])
    (implicit ec: ExecutionContext): Effect[WorkflowEvent, WorkflowState] =
    if (state.workflowJson.isEmpty) immediate(replyTo, NotInitialized(workflowId))
    else state.manualRequests.find(_.requestId == requestId) match {
      case Some(record) => immediate(replyTo, DuplicateExecution(requestId, record.executionId))
      case None if state.currentExecution.nonEmpty => immediate(replyTo, AlreadyRunning(state.currentExecution.get.executionId))
      case None => startExecution(workflowId, state, manualTrigger(requestId), replyTo, executionEngine, context, activeEngineExecutionId, activeAttemptId)
    }

  private def startScheduled(workflowId: String, state: WorkflowState, scheduleId: String, scheduledAt: Long, triggerId: String, replyTo: ActorRef[Reply], executionEngine: WorkflowExecutionEngine, context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command], activeEngineExecutionId: AtomicReference[String], activeAttemptId: AtomicReference[String])
    (implicit ec: ExecutionContext): Effect[WorkflowEvent, WorkflowState] =
    if (state.workflowJson.isEmpty) immediate(replyTo, NotInitialized(workflowId))
    else if (scheduleWatermark(state.lastAcceptedTriggerBySchedule, scheduleId).exists(_ >= scheduledAt)) immediate(replyTo, DuplicateExecution(triggerId, s"$scheduleId-$scheduledAt"))
    else if (state.currentExecution.nonEmpty) {
      val running = state.currentExecution.get
      Effect.persist(ExecutionSkipped(s"skipped-$scheduleId-$scheduledAt", scheduledTrigger(scheduleId, scheduledAt, triggerId), "already_running", now()))
        .thenReply(replyTo)(_ => AlreadyRunning(running.executionId))
    } else startExecution(workflowId, state, scheduledTrigger(scheduleId, scheduledAt, triggerId), replyTo, executionEngine, context, activeEngineExecutionId, activeAttemptId)

  private def startLegacyManual(workflowId: String, state: WorkflowState, replyTo: ActorRef[ExecutionResponse], executionEngine: WorkflowExecutionEngine, context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command], activeEngineExecutionId: AtomicReference[String], activeAttemptId: AtomicReference[String])
    (implicit ec: ExecutionContext): Effect[WorkflowEvent, WorkflowState] =
    if (state.workflowJson.isEmpty) { replyTo ! ExecutionResponse("", "not_initialized"); Effect.none }
    else if (state.currentExecution.nonEmpty) { replyTo ! ExecutionResponse(state.currentExecution.get.executionId, "already_running"); Effect.none }
    else decodeWorkflow(state) match {
      case Left(_) => replyTo ! ExecutionResponse("", "definition_invalid"); Effect.none
      case Right(workflow) =>
        val executionId = newExecutionId()
        Effect.persist(executionStarted(state, workflow, executionId, manualTrigger(s"legacy-$executionId"), executionEngine))
          .thenRun((newState: WorkflowState) => newState.currentExecution
            .filter(_.executionId == executionId)
            .foreach(execution => runEngine(workflow, execution, executionEngine, context, activeEngineExecutionId, activeAttemptId)))
          .thenReply(replyTo)((_: WorkflowState) => ExecutionResponse(executionId, "started"))
    }

  private def startExecution(workflowId: String, state: WorkflowState, trigger: ExecutionTrigger, replyTo: ActorRef[Reply], executionEngine: WorkflowExecutionEngine, context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command], activeEngineExecutionId: AtomicReference[String], activeAttemptId: AtomicReference[String])
    (implicit ec: ExecutionContext): Effect[WorkflowEvent, WorkflowState] = decodeWorkflow(state) match {
    case Left(errors) => immediate(replyTo, DefinitionRejected(workflowId, errors))
    case Right(workflow) =>
      val executionId = newExecutionId()
      Effect.persist(executionStarted(state, workflow, executionId, trigger, executionEngine))
        .thenRun((newState: WorkflowState) => newState.currentExecution
          .filter(_.executionId == executionId)
          .foreach(execution => runEngine(workflow, execution, executionEngine, context, activeEngineExecutionId, activeAttemptId)))
        .thenReply(replyTo)((_: WorkflowState) => ExecutionAccepted(executionId))
  }

  private def executionStarted(state: WorkflowState, workflow: WorkflowDSL.Workflow, executionId: String, trigger: ExecutionTrigger, engine: WorkflowExecutionEngine): WorkflowEvent =
    if (engine.isResumable(workflow)) ResumableExecutionStarted(executionId, trigger, state.revision, now())
    else ExecutionStarted(executionId, trigger, now())

  private def initializeSnapshot(state: WorkflowState, executionId: String, boundary: SnapshotBoundary, replyTo: ActorRef[Reply]): Effect[WorkflowEvent, WorkflowState] =
    state.currentExecution match {
      case None => rejectCheckpoint(replyTo, "no active execution")
      case Some(current) if current.executionId != executionId => rejectCheckpoint(replyTo, "execution does not match the active execution")
      case Some(current) if !current.resumable => rejectCheckpoint(replyTo, "active execution is not resumable")
      case Some(_) if reliableSourceNodeId(state).forall(_ != boundary.sourceNodeId) => rejectCheckpoint(replyTo, "snapshot source does not match the workflow source")
      case Some(current) => current.boundary match {
        case None => Effect.persist(ExecutionSnapshotInitialized(executionId, boundary, now())).thenReply(replyTo)(_ => SnapshotInitialized(boundary))
        case Some(existing) if existing == boundary => immediate(replyTo, SnapshotAlreadyInitialized(existing))
        case Some(_) => rejectCheckpoint(replyTo, "snapshot boundary is already initialized")
      }
    }

  private def advanceCheckpoint(state: WorkflowState, executionId: String, checkpoint: BatchCheckpoint, replyTo: ActorRef[Reply]): Effect[WorkflowEvent, WorkflowState] =
    state.currentExecution match {
      case None => rejectCheckpoint(replyTo, "no active execution")
      case Some(current) if current.executionId != executionId => rejectCheckpoint(replyTo, "execution does not match the active execution")
      case Some(current) if !current.resumable => rejectCheckpoint(replyTo, "active execution is not resumable")
      case Some(current) => current.boundary match {
        case None => rejectCheckpoint(replyTo, "snapshot boundary is not initialized")
        case Some(boundary) if !checkpointMatchesBoundary(checkpoint, boundary) => rejectCheckpoint(replyTo, "checkpoint does not match the snapshot boundary")
        case Some(_) if checkpoint.batchId != BatchId.sha256(executionId, checkpoint.sourceNodeId, checkpoint.partitionId, checkpoint.batchSequence) =>
          rejectCheckpoint(replyTo, "checkpoint batch identity is invalid")
        case Some(_) =>
          current.checkpoints.find(existing => samePartition(existing, checkpoint)) match {
            case Some(existing) if existing == checkpoint => immediate(replyTo, CheckpointAlreadyStored(existing))
            case latest if isExpectedNextCheckpointSequence(latest.map(_.batchSequence), checkpoint.batchSequence) =>
              Effect.persist(ExecutionCheckpointAdvanced(executionId, checkpoint, now())).thenReply(replyTo)(_ => CheckpointAccepted(checkpoint))
            case Some(existing) if checkpoint.batchSequence <= existing.batchSequence => rejectCheckpoint(replyTo, "checkpoint conflicts with a stored sequence")
            case _ => rejectCheckpoint(replyTo, "checkpoint sequence has a gap")
          }
      }
    }

  private def reliableSourceNodeId(state: WorkflowState): Option[String] =
    workflowSourceNode(state).map(_.id)

  private def checkpointMatchesBoundary(checkpoint: BatchCheckpoint, boundary: SnapshotBoundary): Boolean =
    checkpoint.sourceNodeId == boundary.sourceNodeId &&
      checkpoint.partitionId == boundary.partitionId &&
      boundary.upperBound.contains(checkpoint.cursor.upperBound)

  private[actors] def samePartition(left: BatchCheckpoint, right: BatchCheckpoint): Boolean =
    left.sourceNodeId == right.sourceNodeId && left.partitionId == right.partitionId

  private[actors] def isExpectedNextCheckpointSequence(latest: Option[Long], candidate: Long): Boolean = latest match {
    case None => candidate == 0L
    case Some(Long.MaxValue) => false
    case Some(sequence) => candidate == sequence + 1L
  }

  private def rejectCheckpoint(replyTo: ActorRef[Reply], reason: String): Effect[WorkflowEvent, WorkflowState] =
    immediate(replyTo, CheckpointRejected(reason))

  private def runEngine(
    workflow: WorkflowDSL.Workflow,
    execution: ExecutionState,
    executionEngine: WorkflowExecutionEngine,
    context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
    activeEngineExecutionId: AtomicReference[String],
    activeAttemptId: AtomicReference[String],
    grant: Option[TableGrant] = None
  )(implicit ec: ExecutionContext): Boolean = {
    val token = java.util.UUID.randomUUID().toString
    activeAttemptId.set(token)
    if (executionEngine.managedSnapshot(workflow) && grant.isEmpty) {
      val runtime = executionEngine.tableScheduling.get
      val request = execution.trigger.requestId.orElse(execution.trigger.triggerId).getOrElse(execution.executionId)
      val queued = try runtime.enqueue(workflow,execution.workflowRevision,execution.executionId,request)
        catch { case NonFatal(e) => scala.concurrent.Future.failed(e) }
      context.pipeToSelf(queued) {
        case Success(t) if t.grantId.nonEmpty && t.owner.contains(runtime.nodeId) && Set("running","recovering").contains(t.state) => ActivateSnapshot(TableGrant(t,t.grantId.get))
        case Success(_) => SnapshotQueued(execution.executionId,token)
        case Failure(_) => SnapshotQueueFailed(execution.executionId,token)
      }
      return true
    }
    try {
      activeEngineExecutionId.set(execution.executionId)
      val result =
        if (execution.resumable) executionEngine.execute(workflow, reliableRunContext(execution, token, context).copy(schedulingGrant=grant), _ => ())
        else executionEngine.execute(workflow, execution.executionId, _ => ())
      context.pipeToSelf(result) {
        case Success(value) => EngineFinished(execution.executionId, token, value)
        case Failure(error) => EngineCrashed(execution.executionId, token, Option(error.getMessage).getOrElse(error.getClass.getSimpleName), ExecutionFailure.from(error,"engine"))
      }
      true
    } catch {
      case NonFatal(error) =>
        context.self ! EngineCrashed(execution.executionId, token, Option(error.getMessage).getOrElse(error.getClass.getSimpleName), ExecutionFailure.from(error,"engine"))
      false
    }
  }

  private def releaseScheduled(engine: WorkflowExecutionEngine,
    context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command], id: String, disposition: String,
    confirmed: Boolean, stopAfter: Boolean, grantId: Option[String]): Unit = {
    context.pipeToSelf(engine.finishScheduledSnapshot(id,disposition,confirmed,grantId)) {
      case Success(_) => SnapshotSchedulingReleased(id,grantId,stopAfter)
      case Failure(_) => SnapshotDispositionFailed(id,grantId,disposition,confirmed,stopAfter)
    }
  }

  private def snapshotReadEffect(state: WorkflowState, engine: WorkflowExecutionEngine, passivate: () => Unit): Effect[WorkflowEvent,WorkflowState] = {
    if (state.currentExecution.isEmpty && decodeWorkflow(state).exists(engine.managedSnapshot)) passivate()
    Effect.none
  }

  private def activateSnapshot(workflowId: String, state: WorkflowState, grant: TableGrant, engine: WorkflowExecutionEngine,
    context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command], gate: AtomicBoolean,
    activeExecution: AtomicReference[String], activeAttempt: AtomicReference[String], passivate: () => Unit)
    (implicit ec: ExecutionContext): Effect[WorkflowEvent,WorkflowState] = {
    import spray.json._
    import WorkflowDSL.workflowFormat
    val runtime = engine.tableScheduling.getOrElse(return Effect.none)
    if (grant.task.workflowId != workflowId || grant.task.executionId.isEmpty) return Effect.none
    val id = grant.task.executionId.get
    if (grant.task.owner.exists(_ != runtime.nodeId)) {
      if(runtime.store.ownership.isEmpty) runtime.quarantine(grant)
      return releaseRecoveryGateAfter(Effect.none,gate)
    }
    if (state.status.value == "snapshot_blocked" || state.currentExecution.flatMap(_.recovery).exists(_.phase=="blocked")) { runtime.reject(grant); if(runtime.store.ownership.nonEmpty)passivate(); return releaseRecoveryGateAfter(Effect.none,gate) }
    if (state.currentExecution.isEmpty && state.recentExecutions.exists(_.executionId == id)) {
      if (engine.ownsSnapshotGrant(grant)) releaseScheduled(engine,context,id,"completed",true,stopAfter=true,grantId=Some(grant.id))
      else runtime.reject(grant)
      return releaseRecoveryGateAfter(Effect.none,gate)
    }
    if (state.currentExecution.exists(_.executionId != id) || activeExecution.get() != null) return Effect.none
    if (state.snapshotAdmission.exists(a => a.grantId == grant.id && Set("yielded","queued").contains(a.phase)) && engine.ownsSnapshotGrant(grant)) {
      releaseScheduled(engine,context,id,"ready",true,stopAfter=true,grantId=Some(grant.id))
      return releaseRecoveryGateAfter(Effect.none,gate)
    }
    val json = canonicalAndValidateJson(grant.task.definition.workflowJson,engine.nodeRegistry).toOption
    val advancesDefinition = state.currentExecution.isEmpty && grant.task.definition.revision > state.revision
    if (json.isEmpty || (!advancesDefinition && (state.workflowJson.exists(_.parseJson != json.get.parseJson) ||
      state.revision != grant.task.definition.revision))) {
      runtime.reject(grant); return releaseRecoveryGateAfter(Effect.none,gate)
    }
    val workflow = json.get.parseJson.convertTo[WorkflowDSL.Workflow]
    if (!engine.managedSnapshot(workflow) || runtime.definition(workflow,grant.task.definition.revision) != grant.task.definition) {
      runtime.reject(grant); return releaseRecoveryGateAfter(Effect.none,gate)
    }
    if(!runtime.attach(workflowId,context.self)(context.system)) {
      runtime.defer(grant)
      passivate()
      return Effect.none
    }
    val define = Option.when(advancesDefinition)(WorkflowDefined(json.get,grant.task.definition.revision,now())).toVector
    val start = Option.when(state.currentExecution.isEmpty)(ResumableExecutionStarted(id,
      runtime.scheduledTrigger(id).map(t=>scheduledTrigger(t.scheduleId,t.scheduledAt,t.triggerId))
        .getOrElse(manualTrigger(grant.task.requestKey.getOrElse(id))),grant.task.definition.revision,now())).toVector
    releaseRecoveryGateAfter(Effect.persist(define ++ start :+ SnapshotSchedulingChanged(id,"running",grant.id,now()))
      .thenRun((updated: WorkflowState) => runEngine(workflow,updated.currentExecution.get,engine,context,activeExecution,activeAttempt,Some(grant))),gate)
  }

  private def cancelActiveExecution(
    state: WorkflowState,
    executionEngine: WorkflowExecutionEngine,
    activeEngineExecutionId: AtomicReference[String]
  ): Unit = state.currentExecution.foreach { execution =>
    if (activeEngineExecutionId.compareAndSet(execution.executionId, null)) {
      try executionEngine.cancelWithin(execution.executionId)
      catch { case NonFatal(_) => () }
    }
  }

  private def reliableRunContext(
    execution: ExecutionState,
    token: String,
    context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command]
  )(implicit ec: ExecutionContext): ReliableRunContext = {
    implicit val timeout: Timeout = Timeout(5.seconds)
    implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = context.system.scheduler
    ReliableRunContext(
      execution.executionId,
      execution.workflowRevision,
      execution.boundary,
      execution.checkpoints,
      boundary => context.self.ask[Reply](replyTo => InitializeFromAttempt(execution.executionId, token, boundary, replyTo)).flatMap {
        case SnapshotInitialized(_) | SnapshotAlreadyInitialized(_) => scala.concurrent.Future.successful(Done)
        case CheckpointRejected(reason) => scala.concurrent.Future.failed(new CdcExecutionException(
          ExecutionFailure(ExecutionFailure.ProtocolConflict,"initialization"),new IllegalStateException(reason)))
        case other => scala.concurrent.Future.failed(new IllegalStateException(s"unexpected snapshot reply: ${other.getClass.getSimpleName}"))
      },
      checkpoint => context.self.ask[Reply](replyTo => CheckpointFromAttempt(execution.executionId, token, checkpoint, replyTo)).flatMap {
        case CheckpointAccepted(_) | CheckpointAlreadyStored(_) => scala.concurrent.Future.successful(Done)
        case CheckpointRejected(reason) => scala.concurrent.Future.failed(new CdcExecutionException(
          ExecutionFailure(ExecutionFailure.ProtocolConflict,"journal",Some(checkpoint.batchId)),new IllegalStateException(reason)))
        case other => scala.concurrent.Future.failed(new IllegalStateException(s"unexpected checkpoint reply: ${other.getClass.getSimpleName}"))
      },
      Some(SnapshotSplitRunContext(
        () => context.self.ask[Reply](r => ReadSnapshotSplits(execution.executionId, token, r)).flatMap {
          case SnapshotSplitsRead(value) => scala.concurrent.Future.successful(value)
          case _ => scala.concurrent.Future.failed(new CdcExecutionException(ExecutionFailure(ExecutionFailure.ProtocolConflict, "snapshot-read"), new IllegalStateException("snapshot read rejected")))
        },
        update => context.self.ask[Reply](r => UpdateSnapshotSplits(execution.executionId, token, update, r)).flatMap {
          case SnapshotSplitsStored(value) => scala.concurrent.Future.successful(value)
          case CheckpointRejected(_) => scala.concurrent.Future.failed(new CdcExecutionException(ExecutionFailure(ExecutionFailure.ProtocolConflict, "snapshot-journal"), new IllegalStateException("snapshot update explicitly rejected")))
          case _ => scala.concurrent.Future.failed(new IllegalStateException("unexpected snapshot update reply"))
        }
      ))
    )
  }

  private def allowedDuringRecovery(command: Command): Boolean = command match {
    case _: ActivateSnapshot | _: SnapshotQueued | _: SnapshotQueueFailed | _: SnapshotSchedulingReleased |
         _: SnapshotDispositionFailed | _: RetrySnapshotDisposition => true
    case _: ReadSnapshotSplits | _: UpdateSnapshotSplits | _: InitializeSnapshot | _: AdvanceCheckpoint | _: InitializeFromAttempt | _: CheckpointFromAttempt | _: EngineFinished | _: EngineCrashed => true
    case _: GetSummary | _: GetStatus | _: GetExecutionHistory | _: GetReliableRunState => true
    case _: BeginRecovery | _: RecoveryReleaseChecked => true
    case _: RecoverInterrupted | _: RecoveryEngineLaunched => true
    case _ => false
  }

  private def releaseRecoveryGateAfter(
    effect: EffectBuilder[WorkflowEvent, WorkflowState],
    recoveryGate: AtomicBoolean
  ): Effect[WorkflowEvent, WorkflowState] =
    if (recoveryGate.get()) {
      effect.thenRun((_: WorkflowState) => recoveryGate.set(false)).thenUnstashAll()
    } else effect

  private[actors] def canonicalWorkflowJson(workflow: WorkflowDSL.Workflow): String = WorkflowDefinitionCodec.canonicalWorkflowJson(workflow)
  private[actors] def updateScheduleWatermarks(current: Vector[ScheduleWatermark], scheduleId: String, scheduledAt: Long): Vector[ScheduleWatermark] =
    WorkflowStateTransitions.updateScheduleWatermarks(current, scheduleId, scheduledAt)
  private def manualTrigger(requestId: String): ExecutionTrigger = ExecutionTrigger("manual", requestId = Some(requestId))
  private def scheduledTrigger(scheduleId: String, scheduledAt: Long, triggerId: String): ExecutionTrigger = ExecutionTrigger("scheduled", scheduleId = Some(scheduleId), scheduledAt = scheduledAt, triggerId = Some(triggerId))
  private def scheduleWatermark(current: Vector[ScheduleWatermark], scheduleId: String): Option[Long] = current.reverseIterator.find(_.scheduleId == scheduleId).map(_.scheduledAt)
  private def immediate(replyTo: ActorRef[Reply], response: Reply): Effect[WorkflowEvent, WorkflowState] = { replyTo ! response; Effect.none }
  private def now(): Long = System.currentTimeMillis()
  private def newExecutionId(): String = java.util.UUID.randomUUID().toString
}
