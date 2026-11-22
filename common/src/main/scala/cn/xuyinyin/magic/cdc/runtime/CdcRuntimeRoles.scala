package cn.xuyinyin.magic.cdc.runtime

import scala.concurrent.Future

/** The two deployable roles of the CDC runtime. */
sealed trait CdcRuntimeRole
object CdcRuntimeRole {
  case object Capture extends CdcRuntimeRole
  case object Worker  extends CdcRuntimeRole
}

final case class CaptureLease(
    captureId: String,
    sourceLineage: String,
    ownerAttempt: String,
    ownerEpoch: Long,
    incarnation: String,
    leaseId: Option[String] = None)

final case class CaptureOwnerSnapshot(
    lease: CaptureLease,
    state: String,
    updatedAt: Long,
    leaseExpiresAt: Option[Long])

final case class WorkerLease(
    workerId: String,
    incarnation: String,
    leaseEpoch: Long,
    leaseId: Option[String] = None)

final case class TaskLease(
    taskId: String,
    captureId: String,
    workerId: String,
    workerIncarnation: String,
    leaseEpoch: Long,
    lastLSeq: Long,
    lastJournalRevision: Long,
    lastTargetBatch: Option[String],
    captureOwnerEpoch: Option[Long] = None,
    workerEpoch: Option[Long] = None,
    leaseId: Option[String] = None,
    lastProgressId: Option[String] = None,
    lastProgressDigest: Option[String] = None,
    workerLeaseId: Option[String] = None)

/** Progress committed together with a task lease transition. */
final case class TaskProgressSnapshot(
    lastLSeq: Long,
    lastJournalRevision: Long,
    lastTargetBatch: Option[String])

/** Progress and acknowledgement are committed with the task lease renewal. */
final case class TaskProgressCommit(
    lease: TaskLease,
    progress: TaskProgressSnapshot,
    ackKind: Option[String] = None,
    ackDigest: Option[String] = None,
    progressId: Option[String] = None,
    progressDigest: Option[String] = None)

final case class CaptureSubscriptionRequest(
    captureId: String,
    requestId: String,
    tableName: String,
    generation: String)

final case class CaptureSubscriptionResult(
    captureId: String,
    requestId: String,
    tableName: String,
    generation: String,
    status: String)

/** Capture control is deliberately smaller than the table execution API. */
trait CaptureRuntimeControl {
  def acquire(captureId: String, sourceLineage: String, incarnation: String): Future[CaptureLease]
  def ensureSubscription(request: CaptureSubscriptionRequest): Future[CaptureSubscriptionResult]
  def cancelSubscription(captureId: String, requestId: String, reason: String): Future[CaptureSubscriptionResult]
  def renew(lease: CaptureLease): Future[Boolean]
  def release(lease: CaptureLease): Future[Boolean]
}

/** Worker scheduling owns task leases. Batch T/J/D remains in the existing store. */
trait WorkerRuntimeControl {
  def register(workerId: String, incarnation: String, capacityJson: String): Future[WorkerLease]
  def claim(worker: WorkerLease, limit: Int): Future[Vector[TaskLease]]
  def renew(lease: TaskLease): Future[TaskLease]

  def recordProgress(commit: TaskProgressCommit): Future[TaskLease]
  def release(lease: TaskLease, proof: String): Future[Boolean]
}
