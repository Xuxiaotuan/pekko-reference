package cn.xuyinyin.magic.cdc.runtime

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

/**
  * Worker-side control plane for the two-role CDC runtime.
  *
  * This class owns worker and task lease state only. Table execution, T/J/D
  * interpretation, GC, and Journal verification remain in the platform
  * kernel until the next extraction step.
  */
final class CdcWorkerRuntime(
    leases: CdcRuntimeLeaseStore,
    defaultWorkerId: String,
    defaultIncarnation: String,
    defaultCapacityJson: String
)(implicit ec: ExecutionContext) extends WorkerRuntimeControl {
  private val runtimeLeaseMillis = 30000L
  private val workerLease = new AtomicReference[Option[WorkerLease]](None)
  private val taskLeases = mutable.Map.empty[String, TaskLease]

  def currentWorker: Option[WorkerLease] = workerLease.get()

  def registerCurrent(): WorkerLease = synchronized {
    workerLease.get() match {
      case Some(existing) if existing.workerId == defaultWorkerId && existing.incarnation == defaultIncarnation => existing
      case Some(_) => throw new IllegalStateException("worker identity is already registered")
      case None =>
        val registered = leases.registerWorker(defaultWorkerId, defaultIncarnation, defaultCapacityJson)
        workerLease.set(Some(registered))
        registered
    }
  }

  override def register(workerId: String, incarnation: String, capacityJson: String): Future[WorkerLease] = Future {
    registerNow(workerId, incarnation, capacityJson)
  }

  def registerNow(workerId: String, incarnation: String, capacityJson: String): WorkerLease = {
    require(workerId == defaultWorkerId && incarnation == defaultIncarnation,
      "worker registration does not match the runtime identity")
    synchronized {
      workerLease.get() match {
        case Some(existing) => existing
        case None =>
          val registered = leases.registerWorker(workerId, incarnation, capacityJson)
          workerLease.set(Some(registered))
          registered
      }
    }
  }

  override def claim(worker: WorkerLease, limit: Int): Future[Vector[TaskLease]] = Future {
    claimNow(worker, limit)
  }

  def claimNow(worker: WorkerLease, limit: Int): Vector[TaskLease] = {
    requireRegistered(worker)
    val claimed = leases.claimTasks(worker, limit)
    claimed.foreach(track)
    claimed
  }

  def claimTask(worker: WorkerLease, taskId: String, captureId: String): Option[TaskLease] = synchronized {
    requireRegistered(worker)
    leases.claimTask(worker, taskId, captureId).map { lease =>
      track(lease)
      lease
    }
  }

  override def renew(lease: TaskLease): Future[TaskLease] = Future {
    renewNow(lease)
  }

  override def recordProgress(commit: TaskProgressCommit): Future[TaskLease] = Future {
    recordProgressNow(commit)
  }

  def recordProgressNow(commit: TaskProgressCommit): TaskLease = synchronized {
    requireRegistered(WorkerLease(commit.lease.workerId, commit.lease.workerIncarnation,
      commit.lease.workerEpoch.getOrElse(commit.lease.leaseEpoch), commit.lease.workerLeaseId.orElse(currentWorker.flatMap(_.leaseId))))
    val updated = leases.recordProgress(commit)
    track(updated)
    updated
  }

  def renewNow(lease: TaskLease): TaskLease = {
    requireRegistered(WorkerLease(lease.workerId, lease.workerIncarnation,
      lease.workerEpoch.getOrElse(lease.leaseEpoch), lease.workerLeaseId.orElse(currentWorker.flatMap(_.leaseId))))
    require(leases.renewTask(lease), "task lease is stale")
    lease
  }

  override def release(lease: TaskLease, proof: String): Future[Boolean] = Future {
    releaseNow(lease, proof)
  }

  def releaseNow(lease: TaskLease, proof: String): Boolean = {
    releaseTask(lease, proof, "RELEASED")
  }

  def track(lease: TaskLease): Unit = taskLeases.synchronized {
    taskLeases.update(lease.taskId, lease)
  }

  def untrack(taskId: String): Option[TaskLease] = taskLeases.synchronized {
    taskLeases.remove(taskId)
  }

  def renewTracked(activeGraphs: Int): Boolean = {
    val workerOk = workerLease.get().forall(worker => leases.heartbeatWorker(worker, activeGraphs, runtimeLeaseMillis))
    val (released, tasksOk) = taskLeases.synchronized {
      val releasedIds = Vector.newBuilder[String]
      val outcomes = taskLeases.values.map { lease =>
        if (leases.renewTask(lease, runtimeLeaseMillis)) true
        else if (leases.taskLeaseReleased(lease)) { releasedIds += lease.taskId; true }
        else false
      }
      releasedIds.result() -> outcomes.forall(identity)
    }
    released.foreach(untrack)
    if(!workerOk || !tasksOk)
      org.slf4j.LoggerFactory.getLogger(getClass).warn("CDC worker renewTracked failed workerPresent={} workerOk={} tasksOk={} trackedTasks={} activeGraphs={}",workerLease.get().nonEmpty,workerOk,tasksOk,taskLeases.synchronized(taskLeases.size),activeGraphs)
    workerOk && tasksOk
  }

  def releaseTask(lease: TaskLease, proof: String, state: String): Boolean = {
    val changed = leases.releaseTask(lease, proof, state)
    if (changed) untrack(lease.taskId)
    changed
  }

  def releaseCurrent(state: String): Boolean = workerLease.get().forall(worker => leases.releaseWorker(worker, state))

  private def requireRegistered(worker: WorkerLease): Unit = {
    require(workerLease.get().contains(worker), "worker lease is not registered")
  }
}
