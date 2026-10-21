package cn.xuyinyin.magic.workflow.events

import cn.xuyinyin.magic.common.CborSerializable
import cn.xuyinyin.magic.workflow.engine.ExecutionFailure
import cn.xuyinyin.magic.workflow.checkpoint.{BatchCheckpoint, SnapshotBoundary}

/** Events and persisted value objects for a workflow entity. */
object WorkflowEvents {
  sealed trait WorkflowEvent extends CborSerializable { def timestamp: Long }

  /** A concrete value object avoids persisting Spray JSON implementation types. */
  final case class ExecutionTrigger(
    kind: String,
    requestId: Option[String] = None,
    scheduleId: Option[String] = None,
    scheduledAt: Long = -1L,
    triggerId: Option[String] = None
  ) extends CborSerializable

  final case class PersistedNodeExecutionResult(
    nodeId: String,
    nodeType: String,
    status: String,
    message: String,
    hasMessage: Boolean,
    duration: Long,
    hasDuration: Boolean
  ) extends CborSerializable

  final case class PersistedExecutionResult(
    status: String,
    success: Boolean,
    message: String,
    rowsProcessed: Int,
    hasRowsProcessed: Boolean,
    duration: Long,
    hasDuration: Boolean,
    nodeResults: Vector[PersistedNodeExecutionResult]
  ) extends CborSerializable

  final case class WorkflowDefined(workflowJson: String, revision: Long, timestamp: Long) extends WorkflowEvent
  final case class ExecutionStarted(executionId: String, trigger: ExecutionTrigger, timestamp: Long) extends WorkflowEvent
  final case class ResumableExecutionStarted(executionId: String, trigger: ExecutionTrigger, workflowRevision: Long, timestamp: Long) extends WorkflowEvent
  final case class ExecutionSnapshotInitialized(executionId: String, boundary: SnapshotBoundary, timestamp: Long) extends WorkflowEvent
  final case class ExecutionCheckpointAdvanced(executionId: String, checkpoint: BatchCheckpoint, timestamp: Long) extends WorkflowEvent
  final case class ExecutionRecoveryState(attemptsStarted: Int = 0, nextAttempt: Int = 0,
    phase: String = "waiting", notBefore: Long = 0L, reasonCode: String = "", stage: String = "", failure: Option[ExecutionFailure] = None) extends CborSerializable
  final case class ExecutionRecoveryScheduled(executionId: String, nextAttempt: Int, notBefore: Long,
    reasonCode: String, stage: String, timestamp: Long, failure: Option[ExecutionFailure] = None) extends WorkflowEvent
  final case class ExecutionRecoveryAttemptStarted(executionId: String, attempt: Int, timestamp: Long) extends WorkflowEvent
  final case class ExecutionRecoveryBlocked(executionId: String, reasonCode: String, stage: String, timestamp: Long, failure: Option[ExecutionFailure] = None) extends WorkflowEvent
  final case class ExecutionCompleted(executionId: String, result: PersistedExecutionResult, timestamp: Long) extends WorkflowEvent
  final case class ExecutionFailed(executionId: String, result: PersistedExecutionResult, timestamp: Long) extends WorkflowEvent
  final case class SnapshotSplitsUpdated(executionId: String, update: cn.xuyinyin.magic.workflow.snapshot.SnapshotSplitUpdate, timestamp: Long) extends WorkflowEvent
  final case class SnapshotExecutionPaused(executionId: String, reason: String, timestamp: Long) extends WorkflowEvent
  final case class ExecutionSkipped(executionId: String, trigger: ExecutionTrigger, reason: String, timestamp: Long) extends WorkflowEvent
}
