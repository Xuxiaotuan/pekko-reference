package cn.xuyinyin.magic.cdc.platform

import cn.xuyinyin.magic.cdc.bootstrap.BootstrapProgress
import cn.xuyinyin.magic.cdc.recovery.ExecutionFailure
import cn.xuyinyin.magic.cdc.savepoint.{CdcSavepointHeader, TableCheckpointRef}
import java.util.UUID
import java.util.concurrent.{Executors, ScheduledFuture, ThreadFactory, TimeUnit}
import scala.concurrent.duration._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Failure
import scala.util.control.NonFatal
import cn.xuyinyin.magic.cdc.runtime.{CdcRuntimeLeaseStore, CdcWorkerRuntime, TaskLease, WorkerLease, WorkerRuntimeControl}

final case class CdcKernelOutcome(
    progress: BootstrapProgress,
    releaseConfirmed: Boolean,
    didWork: Boolean,
    validationComplete: Boolean,
    nextDelayMillis: Long,
    initializationEvidence: Option[String])
final private[cdc] class CdcKernelDeferred extends IllegalStateException("bounded validation cache occupied")
trait CdcPlatformKernel {
  def gcEnabled: Boolean                                   = false
  def gcViews: Vector[spray.json.JsObject]                 = Vector.empty
  def maintainGc(owner: String, stop: () => Boolean): Unit = ()
  def prepareManagedManifest(): String                     = throw new IllegalStateException("managed resource manifest unavailable")
  def start(): Unit
  def captures: Vector[CdcCaptureView]
  def run(
      task: CdcTaskRequest,
      id: String,
      cancel: Boolean,
      continuation: Option[BootstrapProgress],
      stop: () => Boolean,
      workPool: String): CdcKernelOutcome
  def runWithContinuation(
      task: CdcTaskRequest,
      id: String,
      cancel: Boolean,
      continuation: Option[BootstrapProgress],
      sameOwner: Boolean,
      stop: () => Boolean,
      workPool: String): CdcKernelOutcome = run(task, id, cancel, continuation, stop, workPool)
  def requestStop(): Unit = ()
  def closeAndVerify(): Boolean

  /** None permits a cold activation; Some restricts admission to the bounded pinned validation set. */
  def admissibleTasks(pool: String): Option[Set[String]] = None
  def dispatchCaptureIds: Set[String]                    = Set.empty

  /** Captures whose readers are paused solely to resume a claimed v2 permit. */
  def recoveryCaptureIds: Set[String] = Set.empty
  def createSavepoint(captureId: String, savepointId: String, ownerEpoch: String): CdcSavepointHeader =
    throw new CdcPlatformException("SAVEPOINT_UNAVAILABLE")
  def restoreSavepoint(savepointId: String): Option[(CdcSavepointHeader, Vector[TableCheckpointRef])] =
    throw new CdcPlatformException("SAVEPOINT_UNAVAILABLE")
}

/** One coordinator, two independent work budgets, no Future or Actor allocated per cold index row. */
final class CdcPlatformRuntime(
    val store: CdcTaskStore,
    kernel: CdcPlatformKernel,
    val limits: CdcPlatformLimits,
    polling: Boolean = true,
    pollIntervalMillis: Long = 100L,
    private[cdc] val managedOwner: Option[CdcManagedOwner] = None,
    private[cdc] val recoverManaged: () => Unit = () => (),
    private[cdc] val guardianCheck: () => Unit = () => (),
    private[cdc] val runtimeLeases: Option[CdcRuntimeLeaseStore] = None)
    extends CdcTaskService with WorkerRuntimeControl {
  val runtimeAttemptId: String = UUID.randomUUID().toString
  private def threads(label: String): ThreadFactory = (r: Runnable) => {
    val t = new Thread(r, "cdc-platform-" + label + "-" + runtimeAttemptId.take(8)); t.setDaemon(true); t
  }
  private val controls              = Executors.newFixedThreadPool(2, threads("control"))
  private val workers               = Executors.newFixedThreadPool(limits.snapshotSteps + limits.tailSteps, threads("step"))
  private val gcWorker              = if (kernel.gcEnabled) Some(Executors.newSingleThreadExecutor(threads("gc"))) else None
  private val gcBusy                = new AtomicBoolean()
  private val clock                 = Executors.newSingleThreadScheduledExecutor(threads("clock"))
  private val guardianClock         = if (managedOwner.nonEmpty) Some(Executors.newSingleThreadScheduledExecutor(threads("guardian"))) else None
  private val workerHeartbeatClock  = runtimeLeases.map(_ => Executors.newSingleThreadScheduledExecutor(threads("worker-heartbeat")))
  private val controlEc             = ExecutionContext.fromExecutorService(controls)
  private val workerEc              = ExecutionContext.fromExecutorService(workers)
  private val active                = new AtomicInteger(); private val pending = new AtomicInteger(); private val ticking      = new AtomicBoolean()
  private val stopping              = new AtomicBoolean(); private val stopped = new AtomicBoolean(); private val closingCheck = new AtomicBoolean()
  private val logger                = org.slf4j.LoggerFactory.getLogger(getClass)
  private val markingStartupFailure = new AtomicBoolean()
  private val startupFailedFlag     = new AtomicBoolean()
  private val guardianLost          = new AtomicBoolean()
  private val workerRuntime = runtimeLeases.map(leases =>
    new CdcWorkerRuntime(
      leases,
      runtimeAttemptId,
      runtimeAttemptId,
      s"{\"snapshotSteps\":${limits.snapshotSteps},\"tailSteps\":${limits.tailSteps}}")(ExecutionContext.parasitic))
  private val unknownTaskLeases               = scala.collection.mutable.Set.empty[String]
  private val savepointInProgress             = new AtomicBoolean()
  @volatile private var startupFailurePending = false
  @volatile private var recoveryPending       = false
  @volatile private var nextRecoveryNanos     = 0L
  @volatile private var ownerGranted          = false
  @volatile private var ownerState            = "INITIALIZING"
  @volatile private var reason                = Option.empty[String]
  @volatile private var lastDispatchAt        = 0L
  @volatile private var dispatchCaughtUp      = false
  private var recoveryAfter                   = Option.empty[String]
  private var recoveryComplete                = false
  private val initialized                     = Promise[Unit]()
  private val stopRequested                   = Promise[Boolean]()
  private def now                             = System.currentTimeMillis()
  private def startOwner(): Unit = {
    var exhausted = false
    val recoveryProven = if (managedOwner.nonEmpty) try { guardianCheck(); recoverManaged(); guardianCheck(); true }
    catch {
      case e: CdcPlatformException if e.code == "AUTOMATIC_RECOVERY_EXHAUSTED" => exhausted = true; false
      case NonFatal(e) => org.slf4j.LoggerFactory.getLogger(getClass).warn("CDC managed recovery remains paused ({})", e.getClass.getName); false
    }
    else true
    if (!recoveryProven) {
      recoveryPending = !exhausted; nextRecoveryNanos = System.nanoTime() + 1000000000L; ownerState = "UNKNOWN";
      reason = Some(if (exhausted) "AUTOMATIC_RECOVERY_EXHAUSTED" else "OWNER_RELEASE_UNPROVEN")
    } else if (!stopping.get() && {
        runtimeLeases.foreach(_.recoverExpiredOwner(now))
        runtimeLeases.foreach(_.requeueAuditedUnknownTasks(now))
        store.beginOwner(runtimeAttemptId, now, managedOwner)
      }) {
      logger.info("CDC owner admission granted attempt={} persisted={}", runtimeAttemptId, store.ownerAttempt.map(_._1).getOrElse("none"))
      recoveryPending = false
      ownerGranted = true
      workerRuntime.foreach(_.registerCurrent())
      if (!stopping.get()) {
        if (managedOwner.nonEmpty) { store.recordManagedManifest(runtimeAttemptId, kernel.prepareManagedManifest(), now); guardianCheck() }
        if (!stopping.get() && !guardianLost.get()) {
          kernel.start()
          if (!stopping.get() && !guardianLost.get()) ownerState = "ACTIVE"
        }
      }
    } else {
      logger.warn(
        "CDC owner admission rejected attempt={} persisted={}",
        runtimeAttemptId,
        store.ownerAttempt.map(x => x._1 + ":" + x._2).getOrElse("none"))
      recoveryPending = !stopping.get(); nextRecoveryNanos = System.nanoTime() + 1000000000L; ownerState = "UNKNOWN";
      reason = Some("OWNER_RELEASE_UNPROVEN")
    }
  }
  private def markStartupFailure(): Unit = if (startupFailurePending && markingStartupFailure.compareAndSet(false, true)) try {
    store.ownerAttempt match {
      case Some((attempt, status)) if attempt == runtimeAttemptId && Set("ACTIVE", "UNKNOWN")(status) =>
        store.endOwner(runtimeAttemptId, confirmed = false, now)
        if (store.ownerAttempt.contains(runtimeAttemptId -> "UNKNOWN")) startupFailurePending = false
      case _ => startupFailurePending = false
    }
  } catch { case NonFatal(_) => () }
  finally markingStartupFailure.set(false)
  private def startupFailed(e: Throwable): Unit = {
    logger.warn("CDC runtime startup failed; release status will remain conservative", e)
    startupFailedFlag.set(true)
    recoveryPending = false; startupFailurePending = managedOwner.nonEmpty
    ownerState = if (startupFailurePending) "UNKNOWN" else "FAILED";
    reason = Some(if (startupFailurePending) "RELEASE_UNKNOWN" else "STORAGE_UNAVAILABLE")
    initialized.tryFailure(new CdcPlatformException("STORAGE_UNAVAILABLE"))
    // A partially started Reader must stop before any potentially blocking database read.
    stopRuntime()
    markStartupFailure()
  }
  private val schedule: Option[ScheduledFuture[_]] =
    if (polling)
      Some(clock
        .scheduleWithFixedDelay(new Runnable { def run(): Unit = { pollNow(); () } }, pollIntervalMillis, pollIntervalMillis, TimeUnit.MILLISECONDS))
    else None
  // Safety checks are independent of scheduling and of both control threads, even with polling disabled.
  guardianClock.foreach(
    _.scheduleWithFixedDelay(
      new Runnable {
        def run(): Unit = {
          if (!stopping.get())
            try guardianCheck()
            catch {
              case NonFatal(_) =>
                if (guardianLost.compareAndSet(false, true)) {
                  startupFailedFlag.set(true); startupFailurePending = true; recoveryPending = false
                  ownerState = "UNKNOWN"; reason = Some("GUARDIAN_UNAVAILABLE")
                  stopRuntime()
                }
            }
        }
      },
      100L,
      1000L,
      TimeUnit.MILLISECONDS))
  workerHeartbeatClock.foreach(
    _.scheduleWithFixedDelay(
      new Runnable {
        def run(): Unit = {
          if (!stopping.get()) workerRuntime.foreach { runtime =>
            val leasesOk = runtime.renewTracked(active.get())
            if (!leasesOk) {
              logger.warn(
                "CDC worker lease heartbeat lost attempt={} activeSteps={} worker={}",
                runtimeAttemptId,
                active.get(),
                runtime.currentWorker.map(_.workerId).getOrElse("none"))
              ownerState = "UNKNOWN"; reason = Some("RUNTIME_LEASE_LOST"); startupFailurePending = true; stopRuntime()
            }
          }
        }
      },
      1000L,
      1000L,
      TimeUnit.MILLISECONDS))
  // Start only after stopRuntime's scheduled handles have been initialized.
  Future {
    store.initialize()
    startOwner()
    initialized.trySuccess(())
  }(controlEc).failed.foreach(startupFailed)(ExecutionContext.parasitic)
  def status: CdcPlatformStatus = CdcPlatformStatus(true, ownerState, reason, active.get(), limits.snapshotSteps + limits.tailSteps)

  /** WorkerRuntime control surface. The scheduler below remains the single grant owner. */
  override def register(workerId: String, incarnation: String, capacityJson: String): Future[WorkerLease] = io {
    workerRuntime.map(_.registerNow(workerId, incarnation, capacityJson)).getOrElse(throw new CdcPlatformException("WORKER_LEASE_UNAVAILABLE"))
  }

  override def claim(worker: WorkerLease, limit: Int): Future[Vector[TaskLease]] = io {
    workerRuntime.map(_.claimNow(worker, limit)).getOrElse(throw new CdcPlatformException("WORKER_LEASE_UNAVAILABLE"))
  }

  override def renew(lease: TaskLease): Future[TaskLease] = io {
    workerRuntime.map(_.renewNow(lease)).getOrElse(throw new CdcPlatformException("WORKER_LEASE_UNAVAILABLE"))
  }
  override def recordProgress(commit: cn.xuyinyin.magic.cdc.runtime.TaskProgressCommit): Future[TaskLease] = io {
    workerRuntime.map(_.recordProgressNow(commit)).getOrElse(throw new CdcPlatformException("WORKER_LEASE_UNAVAILABLE"))
  }

  override def release(lease: TaskLease, proof: String): Future[Boolean] = io {
    workerRuntime.map(_.releaseNow(lease, proof)).getOrElse(false)
  }

  private def io[A](f: => A): Future[A] = {
    if (stopped.get()) return Future.failed(new CdcPlatformException("CDC_PLATFORM_STOPPING"))
    if (pending.incrementAndGet() > limits.maxPendingOperations) {
      pending.decrementAndGet(); Future.failed(new CdcPlatformException("CDC_PLATFORM_BUSY"))
    } else
      initialized.future
        .flatMap(_ => Future(f)(controlEc))(ExecutionContext.parasitic)
        .transform { r => pending.decrementAndGet(); r }(ExecutionContext.parasitic)
  }
  private def observed(v: CdcTaskView): CdcTaskView = {
    val capture = kernel.captures.find(_.captureId == v.captureId)
    val healthy = ownerState == "ACTIVE" && capture.exists(_.runtimeHealth == "HEALTHY")
    v.copy(
      captureReadiness = capture.map(_.readiness).getOrElse("UNKNOWN"),
      runtimeHealth =
        if (v.phase == "CANCELED") "STOPPED"
        else if (!healthy) "UNKNOWN"
        else if (v.runtimeHealth == "HEALTHY" && (lastDispatchAt == 0L || now - lastDispatchAt > 5000L)) "STALE"
        else if (v.runtimeHealth == "HEALTHY" && !dispatchCaughtUp) "CATCHING_UP"
        else v.runtimeHealth,
      releaseStatus = if (ownerState != "ACTIVE" && v.releaseStatus == "IN_USE") "UNKNOWN" else v.releaseStatus,
      cancelAllowed = ownerState == "ACTIVE" && !stopping.get() && v.cancelAllowed &&
        (healthy || (v.phase == "QUEUED" && v.executionId.isEmpty && v.releaseStatus == "RELEASED")))
  }
  override def gcStatus(): Future[Vector[spray.json.JsObject]] = io(kernel.gcViews)
  def captures(): Future[Vector[CdcCaptureView]] = io(
    kernel.captures.map(v => if (ownerState == "ACTIVE") v else v.copy(readiness = "UNKNOWN", runtimeHealth = "UNKNOWN", reason = reason)))
  def submit(request: CdcTaskRequest): Future[CdcTaskAcceptance] = io {
    if (savepointInProgress.get()) throw new CdcPlatformException("SAVEPOINT_ADMISSION_FROZEN")
    if (stopping.get()) throw new CdcPlatformException("CDC_PLATFORM_STOPPING")
    request.validate()
    if (!kernel.captures.exists(_.captureId == request.captureId)) throw new CdcPlatformException("CAPTURE_NOT_CONFIGURED")
    val result = store.submit(request, now); result.copy(task = observed(result.task))
  }
  def get(taskId: String): Future[Option[CdcTaskView]] = io(store.get(taskId).map(observed))
  def listAfter(after: Option[String], limit: Int): Future[CdcTaskPage] = io {
    val p = store.listAfter(after, limit); p.copy(items = p.items.map(observed))
  }
  def cancel(taskId: String): Future[CdcTaskView] = io {
    if (savepointInProgress.get()) throw new CdcPlatformException("SAVEPOINT_ADMISSION_FROZEN")
    if (ownerState != "ACTIVE" || stopping.get()) throw new CdcPlatformException("CANCEL_NOT_PERMITTED")
    val current = store.get(taskId).getOrElse(throw new CdcPlatformException("NOT_FOUND"))
    if (current.desiredState == "CANCELED" || current.phase == "CANCELED") observed(current)
    else {
      if (!observed(current).cancelAllowed) throw new CdcPlatformException("CANCEL_NOT_PERMITTED")
      observed(store.cancel(taskId, now))
    }
  }
  override def createSavepoint(captureId: String, savepointId: String): Future[CdcSavepointHeader] = io {
    if (ownerState != "ACTIVE" || stopping.get()) throw new CdcPlatformException("SAVEPOINT_NOT_PERMITTED")
    if (!savepointInProgress.compareAndSet(false, true)) throw new CdcPlatformException("SAVEPOINT_ALREADY_ALIGNING")
    try {
      val deadline = System.nanoTime() + 10.seconds.toNanos
      while (active.get() > 0 && System.nanoTime() < deadline) { Thread.sleep(10L) }
      if (active.get() > 0) throw new CdcPlatformException("SAVEPOINT_ACTIVE_STEPS")
      kernel.createSavepoint(captureId, savepointId, runtimeAttemptId)
    } finally savepointInProgress.set(false)
  }
  override def restoreSavepoint(savepointId: String): Future[Option[(CdcSavepointHeader, Vector[TableCheckpointRef])]] = io {
    kernel.restoreSavepoint(savepointId)
  }
  def pollNow(): Future[Unit] = {
    if (startupFailurePending && !stopping.get()) Future { markStartupFailure() }(controlEc)
    else if (recoveryPending && !stopping.get() && System.nanoTime() >= nextRecoveryNanos && ticking.compareAndSet(false, true)) {
      Future { startOwner() }(controlEc).transform { r => ticking.set(false); r.failed.foreach(startupFailed); r }(ExecutionContext.parasitic)
    } else if (stopping.get() || ownerState != "ACTIVE" || savepointInProgress.get() || !ticking.compareAndSet(false, true)) Future.successful(())
    else {
      val workEntered = new AtomicBoolean()
      io {
        workEntered.set(true)
        if (!stopping.get() && gcWorker.nonEmpty && gcBusy.compareAndSet(false, true)) {
          Future(kernel.maintainGc(runtimeAttemptId, () => stopping.get() || guardianLost.get()))(ExecutionContext.fromExecutorService(gcWorker.get))
            .onComplete { r =>
              r.failed.foreach { e =>
                if (!ExecutionFailure.from(e, "gc-maintenance").releaseConfirmed) {
                  ownerState = "UNKNOWN"; reason = Some("RELEASE_UNKNOWN"); stopRuntime()
                }
              }
              gcBusy.set(false)
            }(ExecutionContext.parasitic)
        }
        if (!recoveryComplete) {
          recoveryAfter = store.recoverColdPage(runtimeAttemptId, recoveryAfter, 128, now); recoveryComplete = recoveryAfter.isEmpty
        }
        val dispatch = store.dispatch(runtimeAttemptId, kernel.dispatchCaptureIds, 128, now)
        lastDispatchAt = now; dispatchCaughtUp = dispatch.caughtUp
        // A worker-only kernel has no local Reader view. Its dispatch set is
        // the capture-side admission domain, while claimWithLease still checks
        // the durable CAPTURE_READY projection for each task.
        val ready = {
          val dispatched = kernel.dispatchCaptureIds
          if (dispatched.nonEmpty) dispatched
          else kernel.captures.filter(c => c.readiness == "ADMISSION_READY" && c.runtimeHealth == "HEALTHY").map(_.captureId).toSet
        }
        def launch(g: CdcTaskGrant, preclaimed: Option[TaskLease] = None): Unit = {
          logger.info("CDC task grant claimed task={} grant={} pool={} owner={} phase={} dueAt={}",
            g.task.view.taskId, g.id, g.pool, runtimeAttemptId, g.task.view.phase, g.task.dueAt)
          val lease = preclaimed.orElse(workerRuntime.flatMap { runtime =>
            runtime.currentWorker.flatMap(worker => runtime.claimTask(worker, g.task.view.taskId, g.task.view.captureId))
          })
          if (runtimeLeases.nonEmpty && lease.isEmpty) {
            logger.warn("CDC task lease unavailable task={} worker={}", g.task.view.taskId, runtimeAttemptId)
            store.defer(g, limits.tailDelayMillis, now)
          } else {
            lease.foreach(value => workerRuntime.foreach(_.track(value)))
            active.incrementAndGet()
            Future {
              if (stopping.get() || guardianLost.get()) throw new CdcKernelDeferred
              val current                   = store.getRecord(g.task.view.taskId).get
              val (continuation, sameOwner) = store.continuationWithProvenance(g)
              val out = kernel.runWithContinuation(
                current.definition,
                current.view.taskId,
                current.view.desiredState == "CANCELED",
                continuation,
                sameOwner,
                () => stopping.get(),
                g.pool)
              if (out.releaseConfirmed) {
                store.finish(
                  g,
                  out.progress,
                  out.initializationEvidence,
                  "ADMISSION_READY",
                  out.didWork,
                  out.validationComplete,
                  if (out.progress.phase == "STREAMING") limits.tailDelayMillis else math.max(limits.snapshotDelayMillis, out.nextDelayMillis),
                  now,
                  lease)
                logger.info("CDC task grant released task={} grant={} phase={} didWork={} validationComplete={} nextDelay={}",
                  g.task.view.taskId, g.id, out.progress.phase, out.didWork, out.validationComplete, out.nextDelayMillis)
              } else {
                unknownTaskLeases.synchronized(unknownTaskLeases += g.id)
                store.finishUnknown(g, ExecutionFailure.ReleaseUnknown, now)
                if (managedOwner.nonEmpty) { ownerState = "UNKNOWN"; reason = Some("RELEASE_UNKNOWN") }
              }
            }(workerEc).onComplete { result =>
              result match {
                case Failure(_: CdcKernelDeferred) =>
                  logger.warn("CDC task deferred before execution task={} owner={}", g.task.view.taskId, runtimeAttemptId)
                  try {
                    // A bounded validation deferral is safe only while this
                    // owner still controls the runtime. Once stop or guardian
                    // loss has started, leaving the grant ACTIVE would make a
                    // replacement owner wait forever. Persist the conservative
                    // UNKNOWN result so the next owner can audit and replay it.
                    if (stopping.get() || guardianLost.get())
                      store.finishUnknown(g, ExecutionFailure.ReleaseUnknown, now, lease)
                    else store.defer(g, limits.tailDelayMillis, now, lease)
                  }
                  catch { case NonFatal(_) => ownerState = "UNKNOWN"; reason = Some("STORAGE_UNAVAILABLE") }
                case Failure(e) =>
                  val failure = ExecutionFailure.from(e, "cdc-step")
                  try {
                    if (failure.releaseConfirmed) store.finishFailureReleased(g, failure.code, now, lease)
                    else {
                      store.finishUnknown(g, failure.code, now, lease);
                      if (managedOwner.nonEmpty) { ownerState = "UNKNOWN"; reason = Some("RELEASE_UNKNOWN") }
                    }
                  } catch { case NonFatal(_) => ownerState = "UNKNOWN"; reason = Some("STORAGE_UNAVAILABLE") }
                case _ => ()
              }
              workerRuntime.flatMap(_.untrack(g.id)).foreach { value =>
                val unknown = unknownTaskLeases.synchronized(unknownTaskLeases.remove(g.id))
                val state   = if (unknown || result.failed.toOption.exists(!_.isInstanceOf[CdcKernelDeferred])) "UNKNOWN" else "RELEASED"
                workerRuntime.foreach(_.releaseTask(value, if (unknown) "release-unknown" else "released", state))
              }
              active.decrementAndGet()
            }(ExecutionContext.parasitic)
          }
        }
        if (!stopping.get() && managedOwner.nonEmpty)
          runtimeLeases match {
            case Some(leases) =>
              workerRuntime
                .flatMap(_.currentWorker)
                .flatMap { worker =>
                  store.claimFenceRecoveryWithLease(runtimeAttemptId, kernel.recoveryCaptureIds, now, worker, leases)
                }
                .foreach { case (grant, lease) => launch(grant, Some(lease)) }
            case None => store.claimFenceRecovery(runtimeAttemptId, kernel.recoveryCaptureIds, now).foreach(g => launch(g))
          }
        for (pool <- Vector("TAIL", "SNAPSHOT")) {
          val maximum = if (pool == "TAIL") limits.tailSteps else limits.snapshotSteps
          var left    = maximum; var more = true
          while (left > 0 && more && !stopping.get()) {
            val claimed: Option[(CdcTaskGrant, Option[TaskLease])] = runtimeLeases match {
              case Some(leases) =>
                workerRuntime.flatMap(_.currentWorker).flatMap { worker =>
                  store
                    .claimWithLease(runtimeAttemptId, pool, ready, now, kernel.admissibleTasks(pool), worker, leases)
                    .map { case (grant, lease) => grant -> Some(lease) }
                }
              case None => store.claim(runtimeAttemptId, pool, ready, now, kernel.admissibleTasks(pool)).map(_ -> None)
            }
            claimed match {
              case None             => more = false
              case Some((g, lease)) => left -= 1; launch(g, lease)
            }
          }
        }
      }.transform { r =>
        ticking.set(false)
        val preAdmissionBusy = !workEntered.get() && r.failed.toOption.exists {
          case e: CdcPlatformException => e.code == "CDC_PLATFORM_BUSY"
          case _                       => false
        }
        if (r.isFailure && !preAdmissionBusy && !stopping.get()) {
          logger.warn(
            "CDC poll failed attempt={} ownerState={} reason={} message={}",
            runtimeAttemptId,
            ownerState,
            r.failed.toOption.map(_.getClass.getName).getOrElse("unknown"),
            r.failed.toOption.flatMap(e => Option(e.getMessage)).getOrElse(""))
          ownerState = "UNKNOWN"; reason = Some("STORAGE_UNAVAILABLE")
        }
        r
      }(ExecutionContext.parasitic)
    }
  }

  /**
   * Stop is maintenance: never requests a business K or changes desiredState to CANCELED.
   * The caller's response is bounded; a late physical release stays attached to this operation.
   */
  def stopRuntime(): Future[Boolean] = {
    if (stopping.compareAndSet(false, true)) {
      logger.warn(
        "CDC runtime stop requested attempt={} ownerState={} reason={} activeSteps={} guardianLost={}",
        runtimeAttemptId,
        ownerState,
        reason.getOrElse("none"),
        active.get(),
        guardianLost.get())
      guardianClock.foreach(_.shutdown()); schedule.foreach(_.cancel(false))
      workerHeartbeatClock.foreach(_.shutdown())
      if (guardianLost.get()) { ownerState = "UNKNOWN"; reason = Some("GUARDIAN_UNAVAILABLE") }
      else ownerState = "STOPPING"
      try kernel.requestStop()
      catch { case NonFatal(_) => startupFailedFlag.set(true) }
      clock.schedule(
        new Runnable {
          def run(): Unit = {
            if (!stopped.get()) {
              ownerState = "UNKNOWN"; reason = Some("RELEASE_UNKNOWN")
              // A bounded shutdown timeout is not a release proof. Persist the
              // conservative owner state before the JVM may be terminated so a
              // replacement cannot mistake an ACTIVE owner for a clean stop.
              try if (ownerGranted) store.endOwner(runtimeAttemptId, confirmed = false, now)
              catch { case NonFatal(_) => () }
              stopRequested.trySuccess(false)
            }
          }
        },
        10L,
        TimeUnit.SECONDS)
      clock.scheduleWithFixedDelay(
        new Runnable {
          def run(): Unit = {
            if (initialized.isCompleted && active.get() == 0 && !gcBusy.get() && !ticking.get() && !stopped.get() && closingCheck
                .compareAndSet(false, true)) {
              Future {
                val clean = kernel.closeAndVerify()
                val persisted =
                  if (ownerGranted) store.endOwner(runtimeAttemptId, clean && !startupFailedFlag.get(), now)
                  else if (startupFailedFlag.get()) { markStartupFailure(); false } // beginOwner may have committed without its reply
                  else clean
                val workerReleased = workerRuntime.forall(_.releaseCurrent(if (persisted) "STOPPED" else "UNKNOWN"))
                if (persisted && workerReleased) {
                  stopped.set(true); ownerState = "STOPPED"; reason = None; stopRequested.trySuccess(true)
                  clock.shutdown(); workers.shutdown(); gcWorker.foreach(_.shutdown()); controls.shutdown()
                } else { ownerState = "UNKNOWN"; reason = Some("RELEASE_UNKNOWN"); stopRequested.trySuccess(false) }
              }(controlEc).onComplete { result =>
                result match {
                  case Failure(_) => ownerState = "UNKNOWN"; reason = Some("RELEASE_UNKNOWN"); stopRequested.trySuccess(false); case _ => ()
                }
                closingCheck.set(false)
              }(ExecutionContext.parasitic)
            }
          }
        },
        0L,
        250L,
        TimeUnit.MILLISECONDS)
    }
    stopRequested.future
  }
}
