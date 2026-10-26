package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.workflow.bootstrap.BootstrapProgress
import cn.xuyinyin.magic.workflow.engine.ExecutionFailure
import java.util.UUID
import java.util.concurrent.{Executors,ScheduledFuture,ThreadFactory,TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean,AtomicInteger}
import scala.concurrent.{ExecutionContext,Future,Promise}
import scala.util.{Failure,Success}
import scala.util.control.NonFatal

final case class CdcKernelOutcome(progress:BootstrapProgress,releaseConfirmed:Boolean,didWork:Boolean,
  validationComplete:Boolean,nextDelayMillis:Long,initializationEvidence:Option[String])
private[cdc] final class CdcKernelDeferred extends IllegalStateException("bounded validation cache occupied")
trait CdcPlatformKernel {
  def start():Unit
  def captures:Vector[CdcCaptureView]
  def run(task:CdcTaskRequest,id:String,cancel:Boolean,continuation:Option[BootstrapProgress],stop:()=>Boolean,workPool:String):CdcKernelOutcome
  def closeAndVerify():Boolean
  /** None permits a cold activation; Some restricts admission to the bounded pinned validation set. */
  def admissibleTasks(pool:String):Option[Set[String]]=None
  def dispatchCaptureIds:Set[String]=Set.empty
}

/** One coordinator, two independent work budgets, no Future or Actor allocated per cold index row. */
final class CdcPlatformRuntime(val store:CdcTaskStore,kernel:CdcPlatformKernel,val limits:CdcPlatformLimits,
  polling:Boolean=true,pollIntervalMillis:Long=100L) extends CdcTaskService {
  val runtimeAttemptId:String=UUID.randomUUID().toString
  private def threads(label:String):ThreadFactory=(r:Runnable)=>{val t=new Thread(r,"cdc-platform-"+label+"-"+runtimeAttemptId.take(8));t.setDaemon(true);t}
  private val controls=Executors.newFixedThreadPool(2,threads("control"))
  private val workers=Executors.newFixedThreadPool(limits.snapshotSteps+limits.tailSteps,threads("step"))
  private val clock=Executors.newSingleThreadScheduledExecutor(threads("clock"))
  private val controlEc=ExecutionContext.fromExecutorService(controls)
  private val workerEc=ExecutionContext.fromExecutorService(workers)
  private val active=new AtomicInteger();private val pending=new AtomicInteger();private val ticking=new AtomicBoolean()
  private val stopping=new AtomicBoolean();private val stopped=new AtomicBoolean();private val closingCheck=new AtomicBoolean()
  @volatile private var ownerGranted=false
  @volatile private var ownerState="INITIALIZING"
  @volatile private var reason=Option.empty[String]
  @volatile private var lastDispatchAt=0L
  @volatile private var dispatchCaughtUp=false
  private var recoveryAfter=Option.empty[String]
  private var recoveryComplete=false
  private val initialized=Promise[Unit]()
  private val stopRequested=Promise[Boolean]()
  private def now=System.currentTimeMillis()
  Future {
    store.initialize()
    if(store.beginOwner(runtimeAttemptId,now)){
      ownerGranted=true
      if(!stopping.get()){kernel.start();ownerState="ACTIVE"}
    }else {ownerState="UNKNOWN";reason=Some("OWNER_RELEASE_UNPROVEN")}
    initialized.trySuccess(())
  }(controlEc).failed.foreach {e=>ownerState="FAILED";reason=Some("STORAGE_UNAVAILABLE");initialized.tryFailure(new CdcPlatformException("STORAGE_UNAVAILABLE"))}(ExecutionContext.parasitic)
  private val schedule:Option[ScheduledFuture[_]]=if(polling)Some(clock.scheduleWithFixedDelay(new Runnable {def run():Unit={pollNow();()}},pollIntervalMillis,pollIntervalMillis,TimeUnit.MILLISECONDS))else None
  def status:CdcPlatformStatus=CdcPlatformStatus(true,ownerState,reason,active.get(),limits.snapshotSteps+limits.tailSteps)
  private def io[A](f: =>A):Future[A]={
    if(stopped.get())return Future.failed(new CdcPlatformException("CDC_PLATFORM_STOPPING"))
    if(pending.incrementAndGet()>limits.maxPendingOperations){pending.decrementAndGet();Future.failed(new CdcPlatformException("CDC_PLATFORM_BUSY"))}
    else initialized.future.flatMap(_=>Future(f)(controlEc))(ExecutionContext.parasitic).transform {r=>pending.decrementAndGet();r}(ExecutionContext.parasitic)
  }
  private def observed(v:CdcTaskView):CdcTaskView={
    val capture=kernel.captures.find(_.captureId==v.captureId)
    val healthy=ownerState=="ACTIVE" && capture.exists(_.runtimeHealth=="HEALTHY")
    v.copy(captureReadiness=capture.map(_.readiness).getOrElse("UNKNOWN"),
      runtimeHealth=if(v.phase=="CANCELED")"STOPPED"else if(!healthy)"UNKNOWN"else if(v.runtimeHealth=="HEALTHY" && (lastDispatchAt==0L || now-lastDispatchAt>5000L))"STALE"else if(v.runtimeHealth=="HEALTHY" && !dispatchCaughtUp)"CATCHING_UP"else v.runtimeHealth,
      releaseStatus=if(ownerState!="ACTIVE" && v.releaseStatus=="IN_USE")"UNKNOWN"else v.releaseStatus,
      cancelAllowed=ownerState=="ACTIVE" && !stopping.get() && v.cancelAllowed &&
        (healthy || (v.phase=="QUEUED" && v.executionId.isEmpty && v.releaseStatus=="RELEASED")))
  }
  def captures():Future[Vector[CdcCaptureView]]=io(kernel.captures.map(v=>if(ownerState=="ACTIVE")v else v.copy(readiness="UNKNOWN",runtimeHealth="UNKNOWN",reason=reason)))
  def submit(request:CdcTaskRequest):Future[CdcTaskAcceptance]=io {
    if(stopping.get())throw new CdcPlatformException("CDC_PLATFORM_STOPPING")
    request.validate()
    if(!kernel.captures.exists(_.captureId==request.captureId))throw new CdcPlatformException("CAPTURE_NOT_CONFIGURED")
    val result=store.submit(request,now);result.copy(task=observed(result.task))
  }
  def get(taskId:String):Future[Option[CdcTaskView]]=io(store.get(taskId).map(observed))
  def listAfter(after:Option[String],limit:Int):Future[CdcTaskPage]=io {val p=store.listAfter(after,limit);p.copy(items=p.items.map(observed))}
  def cancel(taskId:String):Future[CdcTaskView]=io {
    if(ownerState!="ACTIVE" || stopping.get())throw new CdcPlatformException("CANCEL_NOT_PERMITTED")
    val current=store.get(taskId).getOrElse(throw new CdcPlatformException("NOT_FOUND"))
    if(current.desiredState=="CANCELED" || current.phase=="CANCELED")observed(current)
    else {
      if(!observed(current).cancelAllowed)throw new CdcPlatformException("CANCEL_NOT_PERMITTED")
      observed(store.cancel(taskId,now))
    }
  }
  def pollNow():Future[Unit]={
    if(stopping.get() || ownerState!="ACTIVE" || !ticking.compareAndSet(false,true))Future.successful(())
    else {
      val workEntered=new AtomicBoolean()
      io {
      workEntered.set(true)
      if(!recoveryComplete){recoveryAfter=store.recoverColdPage(runtimeAttemptId,recoveryAfter,128,now);recoveryComplete=recoveryAfter.isEmpty}
      val dispatch=store.dispatch(runtimeAttemptId,kernel.dispatchCaptureIds,128,now)
      lastDispatchAt=now;dispatchCaughtUp=dispatch.caughtUp
      val ready=kernel.captures.filter(c=>c.readiness=="ADMISSION_READY" && c.runtimeHealth=="HEALTHY").map(_.captureId).toSet
      for(pool<-Vector("TAIL","SNAPSHOT")){
        val maximum=if(pool=="TAIL")limits.tailSteps else limits.snapshotSteps
        var left=maximum;var more=true
        while(left>0 && more && !stopping.get()){
          val grant=store.claim(runtimeAttemptId,pool,ready,now,kernel.admissibleTasks(pool))
          grant match {
            case None=>more=false
            case Some(g)=>left-=1;active.incrementAndGet()
              Future {
                val current=store.getRecord(g.task.view.taskId).get
                val out=kernel.run(current.definition,current.view.taskId,current.view.desiredState=="CANCELED",store.continuation(g),()=>stopping.get(),g.pool)
                if(out.releaseConfirmed)store.finish(g,out.progress,out.initializationEvidence,"ADMISSION_READY",out.didWork,out.validationComplete,
                  if(out.progress.phase=="STREAMING")limits.tailDelayMillis else math.max(limits.snapshotDelayMillis,out.nextDelayMillis),now)
                else store.finishUnknown(g,ExecutionFailure.ReleaseUnknown,now)
              }(workerEc).onComplete {result=>
                result match {
                  case Failure(_:CdcKernelDeferred)=>
                    try store.defer(g,limits.tailDelayMillis,now)catch {case NonFatal(_)=>ownerState="UNKNOWN";reason=Some("STORAGE_UNAVAILABLE")}
                  case Failure(e)=>
                    val failure=ExecutionFailure.from(e,"cdc-step")
                    val code=failure.code
                    // A failed disposition is not permission to drop a persistent grant.
                    try {if(failure.releaseConfirmed)store.finishFailureReleased(g,code,now)else store.finishUnknown(g,code,now)}catch {case NonFatal(_)=>ownerState="UNKNOWN";reason=Some("STORAGE_UNAVAILABLE")}
                  case _=>()
                }
                active.decrementAndGet()
              }(ExecutionContext.parasitic)
          }
        }
      }
    }.transform {r=>
      ticking.set(false)
      val preAdmissionBusy= !workEntered.get() && r.failed.toOption.exists {
        case e:CdcPlatformException=>e.code=="CDC_PLATFORM_BUSY"
        case _=>false
      }
      if(r.isFailure && !preAdmissionBusy && !stopping.get()){ownerState="UNKNOWN";reason=Some("STORAGE_UNAVAILABLE")}
      r
    }(ExecutionContext.parasitic)
    }
  }
  /** Stop is maintenance: never requests a business K or changes desiredState to CANCELED.
    * The caller's response is bounded; a late physical release stays attached to this operation.
    */
  def stopRuntime():Future[Boolean]={
    if(stopping.compareAndSet(false,true)){
      schedule.foreach(_.cancel(false));ownerState="STOPPING"
      clock.schedule(new Runnable {def run():Unit={
        if(!stopped.get()){ownerState="UNKNOWN";reason=Some("RELEASE_UNKNOWN");stopRequested.trySuccess(false)}
      }},10L,TimeUnit.SECONDS)
      clock.scheduleWithFixedDelay(new Runnable {def run():Unit={
        if(initialized.isCompleted && active.get()==0 && !ticking.get() && !stopped.get() && closingCheck.compareAndSet(false,true)){
          Future {
            val clean=kernel.closeAndVerify()
            val persisted=if(ownerGranted)store.endOwner(runtimeAttemptId,clean,now)else clean
            if(persisted){
              stopped.set(true);ownerState="STOPPED";reason=None;stopRequested.trySuccess(true)
              clock.shutdown();workers.shutdown();controls.shutdown()
            }else {ownerState="UNKNOWN";reason=Some("RELEASE_UNKNOWN");stopRequested.trySuccess(false)}
          }(controlEc).onComplete {result=>
            result match {case Failure(_)=>ownerState="UNKNOWN";reason=Some("RELEASE_UNKNOWN");stopRequested.trySuccess(false);case _=>()}
            closingCheck.set(false)
          }(ExecutionContext.parasitic)
        }
      }},0L,250L,TimeUnit.MILLISECONDS)
    }
    stopRequested.future
  }
}
