package cn.xuyinyin.magic.workflow.actors

import cn.xuyinyin.magic.common.CborSerializable
import cn.xuyinyin.magic.workflow.engine.{ExecutionResult, WorkflowExecutionEngine, WorkflowValidator}
import cn.xuyinyin.magic.workflow.events.WorkflowEvents._
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.RecoveryCompleted
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import spray.json._

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/** The workflow entity's bounded durable projection; the journal is the full audit trail. */
object EventSourcedWorkflowActor {
  private val MaxRecentExecutions = 50
  private val MaxManualRequests = 100
  private[actors] val MaxScheduleWatermarks = 100
  private val MaxWorkflowNodes = 100
  private val MaxWorkflowJsonBytes = 65536
  private val MaxPersistedNodeResults = 100
  private val MaxPersistedMessageBytes = 4096
  private val DefaultHistoryPageSize = 50

  sealed trait Command extends CborSerializable
  sealed trait Reply extends CborSerializable

  final case class DefineWorkflow(workflowJson: String, expectedRevision: Long, replyTo: ActorRef[Reply]) extends Command
  object DefineWorkflow {
    def apply(workflow: WorkflowDSL.Workflow, expectedRevision: Long, replyTo: ActorRef[Reply]): DefineWorkflow =
      new DefineWorkflow(canonicalWorkflowJson(workflow), expectedRevision, replyTo)
  }
  final case class ExecuteManual(requestId: String, replyTo: ActorRef[Reply]) extends Command
  final case class ExecuteScheduled(scheduleId: String, scheduledAt: Long, triggerId: String, replyTo: ActorRef[Reply]) extends Command
  final case class GetSummary(replyTo: ActorRef[WorkflowSummary]) extends Command
  final case class GetStatus(replyTo: ActorRef[StatusResponse]) extends Command
  final case class GetExecutionHistory(page: Int, pageSize: Int, replyTo: ActorRef[ExecutionHistoryResponse]) extends Command
  object GetExecutionHistory {
    def apply(replyTo: ActorRef[ExecutionHistoryResponse]): GetExecutionHistory =
      new GetExecutionHistory(0, DefaultHistoryPageSize, replyTo)
  }

  /** Kept temporarily so existing supervisor/sharding callers remain source-compatible. */
  final case class Initialize(workflowJson: String, replyTo: ActorRef[InitializeResponse]) extends Command
  object Initialize {
    def apply(workflow: WorkflowDSL.Workflow, replyTo: ActorRef[InitializeResponse]): Initialize =
      new Initialize(canonicalWorkflowJson(workflow), replyTo)
  }
  final case class Execute(replyTo: ActorRef[ExecutionResponse]) extends Command
  case object Stop extends Command
  private final case class EngineFinished(executionId: String, result: ExecutionResult) extends Command
  private final case class EngineCrashed(executionId: String, message: String) extends Command
  private final case class RecoverInterrupted(executionId: String) extends Command

  final case class Defined(workflowId: String, revision: Long) extends Reply
  final case class RevisionConflict(workflowId: String, expectedRevision: Long, actualRevision: Long) extends Reply
  final case class ExecutionAccepted(executionId: String) extends Reply
  final case class DuplicateExecution(requestId: String, executionId: String) extends Reply
  final case class AlreadyRunning(executionId: String) extends Reply
  final case class NotInitialized(workflowId: String) extends Reply
  final case class DefinitionRejected(workflowId: String, errors: Vector[String]) extends Reply
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

  final case class ExecutionState(executionId: String, trigger: ExecutionTrigger, startedAt: Long) extends CborSerializable
  final case class ManualRequestRecord(requestId: String, executionId: String) extends CborSerializable
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
  final case class WorkflowState(
    workflowJson: Option[String] = None,
    revision: Long = 0L,
    status: WorkflowStatus = Uninitialized,
    currentExecution: Option[ExecutionState] = None,
    recentExecutions: Vector[StoredExecutionSummary] = Vector.empty,
    lastAcceptedTriggerBySchedule: Vector[ScheduleWatermark] = Vector.empty,
    manualRequests: Vector[ManualRequestRecord] = Vector.empty
  ) extends CborSerializable

  final case class WorkflowSummary(
    workflowId: String,
    revision: Long,
    status: WorkflowStatus,
    currentExecution: Option[ExecutionState],
    recentExecutions: Vector[ExecutionSummary]
  ) extends CborSerializable
  final case class ExecutionInfo(executionId: String, startTime: Long, endTime: Option[Long], status: String, completedNodes: Int, totalNodes: Int) extends CborSerializable
  final case class StatusResponse(workflowId: String, state: String, currentExecution: Option[ExecutionInfo], allExecutions: List[ExecutionSummary]) extends Response
  final case class NodeExecutionDetail(nodeId: String, nodeType: String, startTime: Option[Long], endTime: Option[Long], duration: Option[Long], status: String, recordsProcessed: Option[Int], error: Option[String]) extends CborSerializable
  final case class ExecutionDetail(executionId: String, workflowName: String, startTime: Long, endTime: Option[Long], status: String, duration: Option[Long], nodes: List[NodeExecutionDetail]) extends CborSerializable
  final case class ExecutionHistoryResponse(workflowId: String, executions: List[ExecutionDetail]) extends Response

  def apply(workflowId: String, executionEngine: WorkflowExecutionEngine)(implicit ec: ExecutionContext): Behavior[Command] = behavior(workflowId, executionEngine)
  def apply(workflowId: String, workflow: WorkflowDSL.Workflow, executionEngine: WorkflowExecutionEngine)(implicit ec: ExecutionContext): Behavior[Command] = behavior(workflowId, executionEngine)

  private def behavior(workflowId: String, executionEngine: WorkflowExecutionEngine)(implicit ec: ExecutionContext): Behavior[Command] =
    Behaviors.setup { context =>
      val recoveryGate = new AtomicBoolean(false)
      EventSourcedBehavior[Command, WorkflowEvent, WorkflowState](
        PersistenceId.ofUniqueId(s"workflow-$workflowId"),
        WorkflowState(),
        commandHandler(workflowId, executionEngine, context, recoveryGate),
        eventHandler
      ).withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))
        .receiveSignal {
          case (state, RecoveryCompleted) =>
            state.currentExecution.foreach { execution =>
              recoveryGate.set(true)
              context.self ! RecoverInterrupted(execution.executionId)
            }
        }
    }

  private def commandHandler(workflowId: String, executionEngine: WorkflowExecutionEngine, context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command], recoveryGate: AtomicBoolean)
    (state: WorkflowState, command: Command)(implicit ec: ExecutionContext): Effect[WorkflowEvent, WorkflowState] =
    if (recoveryGate.get() && !command.isInstanceOf[RecoverInterrupted]) Effect.stash()
    else command match {
    case DefineWorkflow(workflowJson, expectedRevision, replyTo) => validateDefinition(workflowId, state, workflowJson, expectedRevision, replyTo)
    case Initialize(workflowJson, replyTo) =>
      if (state.revision != 0L) { replyTo ! InitializeResponse(workflowId, "initialized"); Effect.none }
      else canonicalAndValidateJson(workflowJson) match {
        case Left(_) => replyTo ! InitializeResponse(workflowId, "rejected"); Effect.none
        case Right(json) => Effect.persist(WorkflowDefined(json, 1L, now())).thenReply(replyTo)(_ => InitializeResponse(workflowId, "initialized"))
      }
    case ExecuteManual(requestId, replyTo) => startManual(workflowId, state, requestId, replyTo, executionEngine, context)
    case ExecuteScheduled(scheduleId, scheduledAt, triggerId, replyTo) => startScheduled(workflowId, state, scheduleId, scheduledAt, triggerId, replyTo, executionEngine, context)
    case Execute(replyTo) => startLegacyManual(workflowId, state, replyTo, executionEngine, context)
    case GetSummary(replyTo) => replyTo ! WorkflowSummary(workflowId, state.revision, state.status, state.currentExecution, state.recentExecutions.map(publicSummary)); Effect.none
    case GetStatus(replyTo) => replyTo ! statusResponse(workflowId, state); Effect.none
    case GetExecutionHistory(page, pageSize, replyTo) => replyTo ! historyResponse(workflowId, state, page, pageSize); Effect.none
    case EngineFinished(executionId, result) if state.currentExecution.exists(_.executionId == executionId) =>
      boundedPersistedResult(result) match {
        case Right(persisted) => Effect.persist(if (result.success) ExecutionCompleted(executionId, persisted, now()) else ExecutionFailed(executionId, persisted, now()))
        case Left(reason) => Effect.persist(ExecutionFailed(executionId, failedPersistedResult(reason), now()))
      }
    case EngineCrashed(executionId, message) if state.currentExecution.exists(_.executionId == executionId) =>
      Effect.persist(ExecutionFailed(executionId, failedPersistedResult(message), now()))
    case RecoverInterrupted(executionId) if state.currentExecution.exists(_.executionId == executionId) =>
      Effect.persist(ExecutionFailed(executionId, failedPersistedResult("interrupted/recovered"), now()))
        .thenRun((_: WorkflowState) => recoveryGate.set(false))
        .thenUnstashAll()
    case RecoverInterrupted(_) =>
      recoveryGate.set(false)
      Effect.unstashAll()
    case Stop => Effect.stop()
    case _ => Effect.none
    }

  private def validateDefinition(workflowId: String, state: WorkflowState, workflowJson: String, expectedRevision: Long, replyTo: ActorRef[Reply]): Effect[WorkflowEvent, WorkflowState] =
    if (expectedRevision != state.revision) { replyTo ! RevisionConflict(workflowId, expectedRevision, state.revision); Effect.none }
    else canonicalAndValidateJson(workflowJson) match {
      case Left(errors) => replyTo ! DefinitionRejected(workflowId, errors); Effect.none
      case Right(json) =>
        val revision = state.revision + 1L
        Effect.persist(WorkflowDefined(json, revision, now())).thenReply(replyTo)(_ => Defined(workflowId, revision))
    }

  private def startManual(workflowId: String, state: WorkflowState, requestId: String, replyTo: ActorRef[Reply], executionEngine: WorkflowExecutionEngine, context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command])
    (implicit ec: ExecutionContext): Effect[WorkflowEvent, WorkflowState] =
    if (state.workflowJson.isEmpty) immediate(replyTo, NotInitialized(workflowId))
    else state.manualRequests.find(_.requestId == requestId) match {
      case Some(record) => immediate(replyTo, DuplicateExecution(requestId, record.executionId))
      case None if state.currentExecution.nonEmpty => immediate(replyTo, AlreadyRunning(state.currentExecution.get.executionId))
      case None => startExecution(workflowId, state, manualTrigger(requestId), replyTo, executionEngine, context)
    }

  private def startScheduled(workflowId: String, state: WorkflowState, scheduleId: String, scheduledAt: Long, triggerId: String, replyTo: ActorRef[Reply], executionEngine: WorkflowExecutionEngine, context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command])
    (implicit ec: ExecutionContext): Effect[WorkflowEvent, WorkflowState] =
    if (state.workflowJson.isEmpty) immediate(replyTo, NotInitialized(workflowId))
    else if (scheduleWatermark(state.lastAcceptedTriggerBySchedule, scheduleId).exists(_ >= scheduledAt)) immediate(replyTo, DuplicateExecution(triggerId, s"$scheduleId-$scheduledAt"))
    else if (state.currentExecution.nonEmpty) {
      val running = state.currentExecution.get
      Effect.persist(ExecutionSkipped(s"skipped-$scheduleId-$scheduledAt", scheduledTrigger(scheduleId, scheduledAt, triggerId), "already_running", now()))
        .thenReply(replyTo)(_ => AlreadyRunning(running.executionId))
    } else startExecution(workflowId, state, scheduledTrigger(scheduleId, scheduledAt, triggerId), replyTo, executionEngine, context)

  private def startLegacyManual(workflowId: String, state: WorkflowState, replyTo: ActorRef[ExecutionResponse], executionEngine: WorkflowExecutionEngine, context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command])
    (implicit ec: ExecutionContext): Effect[WorkflowEvent, WorkflowState] =
    if (state.workflowJson.isEmpty) { replyTo ! ExecutionResponse("", "not_initialized"); Effect.none }
    else if (state.currentExecution.nonEmpty) { replyTo ! ExecutionResponse(state.currentExecution.get.executionId, "already_running"); Effect.none }
    else decodeWorkflow(state) match {
      case Left(_) => replyTo ! ExecutionResponse("", "definition_invalid"); Effect.none
      case Right(workflow) =>
        val executionId = newExecutionId()
        Effect.persist(ExecutionStarted(executionId, manualTrigger(s"legacy-$executionId"), now()))
          .thenRun((_: WorkflowState) => runEngine(workflow, executionId, executionEngine, context))
          .thenReply(replyTo)((_: WorkflowState) => ExecutionResponse(executionId, "started"))
    }

  private def startExecution(workflowId: String, state: WorkflowState, trigger: ExecutionTrigger, replyTo: ActorRef[Reply], executionEngine: WorkflowExecutionEngine, context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command])
    (implicit ec: ExecutionContext): Effect[WorkflowEvent, WorkflowState] = decodeWorkflow(state) match {
    case Left(errors) => immediate(replyTo, DefinitionRejected(workflowId, errors))
    case Right(workflow) =>
      val executionId = newExecutionId()
      Effect.persist(ExecutionStarted(executionId, trigger, now()))
        .thenRun((_: WorkflowState) => runEngine(workflow, executionId, executionEngine, context))
        .thenReply(replyTo)((_: WorkflowState) => ExecutionAccepted(executionId))
  }

  private def runEngine(workflow: WorkflowDSL.Workflow, executionId: String, executionEngine: WorkflowExecutionEngine, context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command]): Unit =
    context.pipeToSelf(executionEngine.execute(workflow, executionId, _ => ())) {
      case Success(result) => EngineFinished(executionId, result)
      case Failure(error) => EngineCrashed(executionId, Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
    }

  private def eventHandler(state: WorkflowState, event: WorkflowEvent): WorkflowState = event match {
    case WorkflowDefined(json, revision, _) => state.copy(workflowJson = Some(json), revision = revision)
    case ExecutionStarted(executionId, trigger, timestamp) =>
      val manual = trigger.requestId.fold(state.manualRequests)(id => (state.manualRequests :+ ManualRequestRecord(id, executionId)).takeRight(MaxManualRequests))
      state.copy(status = Running, currentExecution = Some(ExecutionState(executionId, trigger, timestamp)), manualRequests = manual, lastAcceptedTriggerBySchedule = updateWatermark(state.lastAcceptedTriggerBySchedule, trigger))
    case ExecutionCompleted(executionId, result, timestamp) => terminal(state, executionId, result, Completed, timestamp)
    case ExecutionFailed(executionId, result, timestamp) => terminal(state, executionId, result, Failed, timestamp)
    case ExecutionSkipped(executionId, trigger, reason, timestamp) =>
      state.copy(recentExecutions = (state.recentExecutions :+ StoredExecutionSummary(executionId, timestamp, timestamp, hasEndTime = true, "skipped", 0L, hasDuration = true)).takeRight(MaxRecentExecutions), lastAcceptedTriggerBySchedule = updateWatermark(state.lastAcceptedTriggerBySchedule, trigger))
  }

  private def terminal(state: WorkflowState, executionId: String, result: PersistedExecutionResult, status: WorkflowStatus, timestamp: Long): WorkflowState = state.currentExecution match {
    case Some(current) if current.executionId == executionId =>
      val summary = StoredExecutionSummary(executionId, current.startedAt, timestamp, hasEndTime = true, status.value, timestamp - current.startedAt, hasDuration = true, Some(result))
      state.copy(status = status, currentExecution = None, recentExecutions = (state.recentExecutions :+ summary).takeRight(MaxRecentExecutions))
    case _ => state
  }

  private def statusResponse(workflowId: String, state: WorkflowState): StatusResponse =
    StatusResponse(workflowId, state.status.value, state.currentExecution.map(e => ExecutionInfo(e.executionId, e.startedAt, None, "running", 0, 0)), state.recentExecutions.map(publicSummary).toList)

  private def historyResponse(workflowId: String, state: WorkflowState, page: Int, pageSize: Int): ExecutionHistoryResponse = {
    val size = math.max(1, math.min(DefaultHistoryPageSize, pageSize))
    val selected = state.recentExecutions.reverse.slice(math.max(0, page) * size, (math.max(0, page) + 1) * size)
    val name = decodeWorkflow(state).toOption.map(_.name).getOrElse(workflowId)
    ExecutionHistoryResponse(workflowId, selected.map { summary =>
      val public = publicSummary(summary)
      ExecutionDetail(public.executionId, name, public.startTime, public.endTime, public.status, public.duration, historyNodes(summary))
    }.toList)
  }

  private def historyNodes(summary: StoredExecutionSummary): List[NodeExecutionDetail] = summary.result.toList.flatMap { result =>
    if (result.nodeResults.nonEmpty) {
      result.nodeResults.toList.map { node =>
        NodeExecutionDetail(
          node.nodeId,
          node.nodeType,
          None,
          None,
          Option.when(node.hasDuration)(node.duration),
          node.status,
          None,
          Option.when(node.hasMessage)(node.message)
        )
      }
    } else if (!result.success && result.message.nonEmpty) {
      List(NodeExecutionDetail("__workflow__", "workflow", Some(summary.startTime), summary.endTimeOption, summary.durationOption, result.status, Option.when(result.hasRowsProcessed)(result.rowsProcessed), Some(result.message)))
    } else Nil
  }

  private def decodeWorkflow(state: WorkflowState): Either[Vector[String], WorkflowDSL.Workflow] = state.workflowJson match {
    case None => Left(Vector("Workflow is not initialized"))
    case Some(json) =>
      try { import WorkflowDSL.workflowFormat; Right(json.parseJson.convertTo[WorkflowDSL.Workflow]) }
      catch { case error: Exception => Left(Vector(Option(error.getMessage).getOrElse(error.getClass.getSimpleName))) }
  }
  private[actors] def canonicalWorkflowJson(workflow: WorkflowDSL.Workflow): String = {
    import WorkflowDSL.workflowFormat
    canonicalJson(workflow.toJson)
  }
  private def canonicalJson(value: JsValue): String = value match {
    case JsObject(fields) => fields.toVector.sortBy(_._1).map { case (key, child) => s"${JsString(key).compactPrint}:${canonicalJson(child)}" }.mkString("{", ",", "}")
    case JsArray(values) => values.map(canonicalJson).mkString("[", ",", "]")
    case other => other.compactPrint
  }
  private def canonicalAndValidate(workflow: WorkflowDSL.Workflow): Either[Vector[String], String] = {
    val json = canonicalWorkflowJson(workflow)
    try {
      import WorkflowDSL.workflowFormat
      val decoded = json.parseJson.convertTo[WorkflowDSL.Workflow]
      val bounds = Vector(
        Option.when(decoded.nodes.size > MaxWorkflowNodes)(s"workflow node count exceeds $MaxWorkflowNodes"),
        Option.when(utf8Bytes(json) > MaxWorkflowJsonBytes)(s"workflow canonical JSON exceeds $MaxWorkflowJsonBytes bytes")
      ).flatten
      val validation = WorkflowValidator.validate(decoded).left.map(_.map(_.message))
      if (bounds.nonEmpty) Left(bounds) else validation.map(_ => json)
    } catch {
      case error: Exception => Left(Vector(Option(error.getMessage).getOrElse(error.getClass.getSimpleName)))
    }
  }
  private def canonicalAndValidateJson(workflowJson: String): Either[Vector[String], String] =
    if (utf8Bytes(workflowJson) > MaxWorkflowJsonBytes) Left(Vector(s"workflow canonical JSON exceeds $MaxWorkflowJsonBytes bytes"))
    else {
      try {
        import WorkflowDSL.workflowFormat
        canonicalAndValidate(workflowJson.parseJson.convertTo[WorkflowDSL.Workflow])
      } catch {
        case error: Exception => Left(Vector(Option(error.getMessage).getOrElse(error.getClass.getSimpleName)))
      }
    }
  private def boundedPersistedResult(result: ExecutionResult): Either[String, PersistedExecutionResult] = {
    val oversized =
      result.nodeResults.size > MaxPersistedNodeResults ||
        utf8Bytes(result.message) > MaxPersistedMessageBytes ||
        result.nodeResults.exists(node => node.message.exists(message => utf8Bytes(message) > MaxPersistedMessageBytes))
    if (oversized) Left("engine result exceeded persistence bounds")
    else Right(PersistedExecutionResult(
      result.status,
      result.success,
      result.message,
      result.rowsProcessed.getOrElse(0),
      result.rowsProcessed.nonEmpty,
      result.duration.getOrElse(0L),
      result.duration.nonEmpty,
      result.nodeResults.map(n => PersistedNodeExecutionResult(n.nodeId, n.nodeType, n.status, n.message.getOrElse(""), n.message.nonEmpty, n.duration.getOrElse(0L), n.duration.nonEmpty))
    ))
  }
  private def failedPersistedResult(message: String): PersistedExecutionResult =
    PersistedExecutionResult("failed", success = false, message, 0, hasRowsProcessed = false, 0L, hasDuration = false, Vector.empty)
  private def publicSummary(summary: StoredExecutionSummary): ExecutionSummary =
    ExecutionSummary(summary.executionId, summary.startTime, if (summary.hasEndTime) Some(summary.endTime) else None, summary.status, if (summary.hasDuration) Some(summary.duration) else None)
  private implicit class StoredExecutionSummaryOps(private val summary: StoredExecutionSummary) extends AnyVal {
    def endTimeOption: Option[Long] = Option.when(summary.hasEndTime)(summary.endTime)
    def durationOption: Option[Long] = Option.when(summary.hasDuration)(summary.duration)
  }
  private def manualTrigger(requestId: String): ExecutionTrigger = ExecutionTrigger("manual", requestId = Some(requestId))
  private def scheduledTrigger(scheduleId: String, scheduledAt: Long, triggerId: String): ExecutionTrigger = ExecutionTrigger("scheduled", scheduleId = Some(scheduleId), scheduledAt = scheduledAt, triggerId = Some(triggerId))
  private def updateWatermark(current: Vector[ScheduleWatermark], trigger: ExecutionTrigger): Vector[ScheduleWatermark] = trigger.scheduleId match {
    case Some(id) if trigger.scheduledAt >= 0L => updateScheduleWatermarks(current, id, trigger.scheduledAt)
    case _ => current
  }
  private[actors] def updateScheduleWatermarks(current: Vector[ScheduleWatermark], scheduleId: String, scheduledAt: Long): Vector[ScheduleWatermark] =
    (current.filterNot(_.scheduleId == scheduleId) :+ ScheduleWatermark(scheduleId, scheduledAt)).takeRight(MaxScheduleWatermarks)
  private def scheduleWatermark(current: Vector[ScheduleWatermark], scheduleId: String): Option[Long] = current.reverseIterator.find(_.scheduleId == scheduleId).map(_.scheduledAt)
  private def utf8Bytes(value: String): Int = value.getBytes(StandardCharsets.UTF_8).length
  private def immediate(replyTo: ActorRef[Reply], response: Reply): Effect[WorkflowEvent, WorkflowState] = { replyTo ! response; Effect.none }
  private def now(): Long = System.currentTimeMillis()
  private def newExecutionId(): String = java.util.UUID.randomUUID().toString
}
