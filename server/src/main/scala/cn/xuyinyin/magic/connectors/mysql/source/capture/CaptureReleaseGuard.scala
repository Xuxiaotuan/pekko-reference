package cn.xuyinyin.magic.connectors.mysql.source.capture

import java.util.UUID
import java.util.concurrent.{Executors, SynchronousQueue, ThreadFactory, ThreadPoolExecutor, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal

final case class CaptureCloseBudget(localMillis:Long=5000,serverMillis:Long=5000) {
  require(localMillis>0 && serverMillis>0 && localMillis+serverMillis<=60000)
}
final case class CaptureServerRelease(runtimeAttemptId:String,status:String,reason:Option[String],
  sourceSessions:Vector[Long]=Vector.empty,stateSessions:Vector[Long]=Vector.empty,transactions:Vector[Long]=Vector.empty)
final case class CaptureReleaseEvidence(runtimeAttemptId:String,operationId:String,acceptingCommands:Boolean,
  localTermination:String,serverRelease:String,ownerReleased:Boolean,reusable:Boolean,reasonCode:Option[String],observedAt:Long)

private object CaptureCloseWorkers {
  private val sequence=new AtomicLong()
  private val threads:ThreadFactory=(r:Runnable)=>{
    val t=new Thread(r,"capture-close-"+sequence.incrementAndGet());t.setDaemon(true);t
  }
  // A stuck SDK close retains its original operation, not an unbounded succession of threads.
  val workers=new ThreadPoolExecutor(0,4,30,TimeUnit.SECONDS,new SynchronousQueue[Runnable](),threads)
  val timer=Executors.newSingleThreadScheduledExecutor((r:Runnable)=>{
    val t=new Thread(r,"capture-close-deadlines");t.setDaemon(true);t
  })
}

/** A bounded response is distinct from actual termination. No cancellation of an in-flight COMMIT. */
final class CaptureReleaseGuard(attempt:String,closeLocal:()=>Unit,localConfirmed:()=>Boolean,
  observeServer:Boolean=>CaptureServerRelease,closeOwner:()=>Unit,ownerConfirmed:()=>Boolean,
  closeObserver:()=>Unit,budget:CaptureCloseBudget=CaptureCloseBudget()) {
  private val operation=UUID.randomUUID().toString
  private val busy=new AtomicBoolean(false)
  private val localInvoked=new AtomicBoolean(false)
  private val localReturned=new AtomicBoolean(false)
  private val ownerInvoked=new AtomicBoolean(false)
  private val observerClosed=new AtomicBoolean(false)
  @volatile private var localFailure=false
  @volatile private var ownerFailure=false
  private var first:Future[CaptureReleaseEvidence]=null
  private var current=CaptureReleaseEvidence(attempt,operation,false,"UNKNOWN","UNKNOWN",false,false,Some("CLOSE_IN_PROGRESS"),System.currentTimeMillis())
  def evidence:CaptureReleaseEvidence=synchronized(current)
  private def update(local:String="UNKNOWN",server:String="UNKNOWN",reason:Option[String]):Unit=synchronized {
    val released=ownerConfirmed() && !ownerFailure
    current=current.copy(localTermination=local,serverRelease=server,ownerReleased=released,
      reusable=local=="CONFIRMED" && server=="CONFIRMED" && released && observerClosed.get(),
      reasonCode=reason,observedAt=System.currentTimeMillis())
  }
  def request():Future[CaptureReleaseEvidence]=synchronized {
    if(first==null)first=launch()
    else if(!localInvoked.get() && !busy.get())launch()
    first
  }
  def refresh():Future[CaptureReleaseEvidence]=synchronized {
    if(first==null)request()
    else if(current.reusable || busy.get())Future.successful(current)
    else launch()
  }
  private def launch():Future[CaptureReleaseEvidence]={
    val result=Promise[CaptureReleaseEvidence]()
    if(!busy.compareAndSet(false,true))return Future.successful(evidence)
    val timer=CaptureCloseWorkers.timer.schedule(new Runnable {
      def run():Unit=result.trySuccess(evidence)
    },budget.localMillis+budget.serverMillis,TimeUnit.MILLISECONDS)
    try CaptureCloseWorkers.workers.execute(new Runnable {
      def run():Unit={
        val localDeadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(budget.localMillis)
        try {
          if(localInvoked.compareAndSet(false,true)) {
            try {closeLocal();localReturned.set(true)}
            catch {case NonFatal(_)=>localFailure=true;update(reason=Some("LOCAL_RELEASE_UNKNOWN"))}
          }
          while(!localFailure && localReturned.get() && !localConfirmed() && System.nanoTime()<localDeadline)Thread.sleep(5)
          if(localFailure || !localReturned.get() || !localConfirmed())update(reason=Some("LOCAL_RELEASE_UNKNOWN"))
          else {
            update(local="CONFIRMED",reason=Some("SERVER_RELEASE_UNKNOWN"))
            val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(budget.serverMillis)
            var continue=true
            while(continue) {
              val observed=observeServer(ownerInvoked.get())
              if(observed.runtimeAttemptId!=attempt) {
                update(local="CONFIRMED",reason=Some("RELEASE_ATTEMPT_MISMATCH"));continue=false
              }else if(observed.status=="CONFIRMED") {
                if(ownerInvoked.compareAndSet(false,true)) {
                  try closeOwner() catch {case NonFatal(_)=>ownerFailure=true}
                }
                if(ownerFailure || !ownerConfirmed()) {
                  update(local="CONFIRMED",reason=Some("OWNER_RELEASE_UNKNOWN"));continue=false
                }else {
                  val finalCheck=observeServer(true)
                  if(finalCheck.runtimeAttemptId==attempt && finalCheck.status=="CONFIRMED") {
                    closeObserver();observerClosed.set(true)
                    update(local="CONFIRMED",server="CONFIRMED",reason=None);continue=false
                  }else update(local="CONFIRMED",reason=finalCheck.reason.orElse(Some("SERVER_RELEASE_UNKNOWN")))
                }
              }else update(local="CONFIRMED",reason=observed.reason.orElse(Some("SERVER_RELEASE_UNKNOWN")))
              if(continue && (System.nanoTime()>=deadline || observed.reason.contains("OBSERVER_UNAVAILABLE")))continue=false
              if(continue)Thread.sleep(20)
            }
          }
        }catch {
          case _:InterruptedException=>Thread.currentThread().interrupt();update(reason=Some("CLOSE_INTERRUPTED"))
          case NonFatal(_)=>update(local=if(localReturned.get() && !localFailure && localConfirmed())"CONFIRMED"else"UNKNOWN",reason=Some("RELEASE_OBSERVATION_UNKNOWN"))
        }finally {busy.set(false);timer.cancel(false);result.trySuccess(evidence)}
      }
    })catch {case NonFatal(_)=>busy.set(false);timer.cancel(false);update(reason=Some("CLOSE_WORKER_CAPACITY_UNKNOWN"));result.trySuccess(evidence)}
    result.future
  }
}
