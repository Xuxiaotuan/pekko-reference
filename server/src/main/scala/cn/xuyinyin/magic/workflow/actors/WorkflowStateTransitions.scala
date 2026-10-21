package cn.xuyinyin.magic.workflow.actors

import EventSourcedWorkflowActor._
import cn.xuyinyin.magic.workflow.events.WorkflowEvents._
import cn.xuyinyin.magic.workflow.checkpoint._
import cn.xuyinyin.magic.connectors.kafka.KafkaCheckpointCodec
import WorkflowDefinitionCodec.workflowSourceNode

/** Pure replay projection. Public persisted model declarations remain on the entity object. */
private[actors] object WorkflowStateTransitions {
  def apply(state: WorkflowState, event: WorkflowEvent): WorkflowState = event match {
    case WorkflowDefined(json, revision, _) =>
      state.copy(workflowJson = Some(json), revision = revision, workflowSourceProgress = None)
    case ExecutionStarted(executionId, trigger, timestamp) =>
      running(state, ExecutionState(executionId, trigger, timestamp))
    case ResumableExecutionStarted(executionId, trigger, workflowRevision, timestamp) =>
      running(state, ExecutionState(
        executionId,
        trigger,
        timestamp,
        workflowRevision,
        resumable = true,
        checkpoints = seededWorkflowCheckpoints(state, workflowRevision)
      ))
    case ExecutionSnapshotInitialized(executionId, boundary, _) => state.currentExecution match {
      case Some(current) if current.executionId == executionId => state.copy(currentExecution = Some(current.copy(boundary = Some(boundary))))
      case _ => state
    }
    case ExecutionCheckpointAdvanced(executionId, checkpoint, _) => state.currentExecution match {
      case Some(current) if current.executionId == executionId =>
        val checkpoints = current.checkpoints.filterNot(existing => samePartition(existing, checkpoint)) :+ checkpoint
        state.copy(
          status = if (current.recovery.exists(_.phase == "active")) Running else state.status,
          currentExecution = Some(current.copy(checkpoints = checkpoints)),
          workflowSourceProgress = projectedWorkflowSourceProgress(state, current, checkpoint)
        )
      case _ => state
    }
    case ExecutionRecoveryScheduled(id, next, at, code, stage, _, fault) => state.currentExecution match {
      case Some(c) if c.executionId == id => state.copy(status = WorkflowStatus("recovering"), currentExecution = Some(c.copy(
        recovery = Some(ExecutionRecoveryState(c.recovery.fold(0)(_.attemptsStarted), next, "waiting", at, code, stage, fault)))))
      case _ => state
    }
    case ExecutionRecoveryAttemptStarted(id, attempt, _) => state.currentExecution match {
      case Some(c) if c.executionId == id => state.copy(status = WorkflowStatus("recovering"), currentExecution = Some(c.copy(
        recovery = c.recovery.map(_.copy(attemptsStarted=attempt, phase="active")))))
      case _ => state
    }
    case ExecutionRecoveryBlocked(id, code, stage, _, fault) => state.currentExecution match {
      case Some(c) if c.executionId == id => state.copy(status = WorkflowStatus("recovery_blocked"), currentExecution = Some(c.copy(
        recovery = Some(c.recovery.getOrElse(ExecutionRecoveryState()).copy(phase="blocked", reasonCode=code, stage=stage, failure=fault.orElse(c.recovery.flatMap(_.failure)))))))
      case _ => state
    }
    case ExecutionCompleted(executionId, result, timestamp) => terminal(state, executionId, result, Completed, timestamp)
    case ExecutionFailed(executionId, result, timestamp) => terminal(state, executionId, result, Failed, timestamp)
    case ExecutionSkipped(executionId, trigger, reason, timestamp) =>
      state.copy(recentExecutions = (state.recentExecutions :+ StoredExecutionSummary(executionId, timestamp, timestamp, hasEndTime = true, "skipped", 0L, hasDuration = true)).takeRight(MaxRecentExecutions), lastAcceptedTriggerBySchedule = updateWatermark(state.lastAcceptedTriggerBySchedule, trigger))
  }

  private def running(state: WorkflowState, execution: ExecutionState): WorkflowState = {
    val manual = execution.trigger.requestId.fold(state.manualRequests)(id => (state.manualRequests :+ ManualRequestRecord(id, execution.executionId)).takeRight(MaxManualRequests))
    state.copy(
      status = Running,
      currentExecution = Some(execution),
      manualRequests = manual,
      lastAcceptedTriggerBySchedule = updateWatermark(state.lastAcceptedTriggerBySchedule, execution.trigger)
    )
  }

  private def terminal(state: WorkflowState, executionId: String, result: PersistedExecutionResult, status: WorkflowStatus, timestamp: Long): WorkflowState = state.currentExecution match {
    case Some(current) if current.executionId == executionId =>
      val summary = StoredExecutionSummary(executionId, current.startedAt, timestamp, hasEndTime = true, status.value, timestamp - current.startedAt, hasDuration = true, Some(result))
      state.copy(status = status, currentExecution = None, recentExecutions = (state.recentExecutions :+ summary).takeRight(MaxRecentExecutions))
    case _ => state
  }

  private def projectedWorkflowSourceProgress(
    state: WorkflowState,
    execution: ExecutionState,
    checkpoint: BatchCheckpoint
  ): Option[WorkflowSourceProgress] = workflowSourceNode(state) match {
    case Some(source)
        if execution.workflowRevision == state.revision &&
          source.id == checkpoint.sourceNodeId &&
          source.nodeType == KafkaSourceNodeType &&
          checkpoint.cursor.kind == KafkaCheckpointCodec.CursorKind =>
      Some(WorkflowSourceProgress(state.revision, source.id, source.nodeType, checkpoint))
    case _ => state.workflowSourceProgress
  }
  private def seededWorkflowCheckpoints(state: WorkflowState, workflowRevision: Long): Vector[BatchCheckpoint] =
    (state.workflowSourceProgress, workflowSourceNode(state)) match {
      case (Some(progress), Some(source))
          if progress.workflowRevision == workflowRevision &&
            progress.sourceNodeId == source.id &&
            progress.sourceNodeType == source.nodeType &&
            source.nodeType == KafkaSourceNodeType =>
        Vector(progress.checkpoint)
      case _ => Vector.empty
    }
  private def updateWatermark(current: Vector[ScheduleWatermark], trigger: ExecutionTrigger): Vector[ScheduleWatermark] = trigger.scheduleId match {
    case Some(id) if trigger.scheduledAt >= 0L => updateScheduleWatermarks(current, id, trigger.scheduledAt)
    case _ => current
  }
  def updateScheduleWatermarks(current: Vector[ScheduleWatermark], scheduleId: String, scheduledAt: Long): Vector[ScheduleWatermark] =
    (current.filterNot(_.scheduleId == scheduleId) :+ ScheduleWatermark(scheduleId, scheduledAt)).takeRight(MaxScheduleWatermarks)
}
