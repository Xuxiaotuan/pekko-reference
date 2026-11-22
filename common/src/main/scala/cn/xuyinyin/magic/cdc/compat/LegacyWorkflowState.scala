package cn.xuyinyin.magic.cdc.compat

import cn.xuyinyin.magic.common.CborSerializable
import cn.xuyinyin.magic.cdc.checkpoint.{BatchCheckpoint,SnapshotBoundary}
import cn.xuyinyin.magic.cdc.snapshot.SnapshotSplitState
import cn.xuyinyin.magic.cdc.compat.WorkflowEvents._

/** Historical snapshot payloads, without a Workflow executor or Actor startup. */
object LegacyWorkflowState {
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

}
