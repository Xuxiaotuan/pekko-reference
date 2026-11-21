package cn.xuyinyin.magic.cdc.runtime

import scala.concurrent.Future

/** The two deployable roles of the CDC runtime. */
sealed trait CdcRuntimeRole
object CdcRuntimeRole {
  case object Capture extends CdcRuntimeRole
  case object Worker extends CdcRuntimeRole
}

final case class CaptureLease(
  captureId: String,
  sourceLineage: String,
  ownerAttempt: String,
  ownerEpoch: Long,
  incarnation: String
)

final case class WorkerLease(
  workerId: String,
  incarnation: String,
  leaseEpoch: Long
)

final case class TaskLease(
  taskId: String,
  captureId: String,
  workerId: String,
  workerIncarnation: String,
  leaseEpoch: Long,
  lastLSeq: Long,
  lastJournalRevision: Long,
  lastTargetBatch: Option[String]
)

/** Capture control is deliberately smaller than the table execution API. */
trait CaptureRuntimeControl {
  def acquire(captureId: String, sourceLineage: String, incarnation: String): Future[CaptureLease]
  def release(lease: CaptureLease): Future[Boolean]
}

/** Worker scheduling owns task leases. Batch T/J/D remains in the existing store. */
trait WorkerRuntimeControl {
  def register(workerId: String, incarnation: String, capacityJson: String): Future[WorkerLease]
  def claim(worker: WorkerLease, limit: Int): Future[Vector[TaskLease]]
  def renew(lease: TaskLease): Future[TaskLease]
  def release(lease: TaskLease, proof: String): Future[Boolean]
}
