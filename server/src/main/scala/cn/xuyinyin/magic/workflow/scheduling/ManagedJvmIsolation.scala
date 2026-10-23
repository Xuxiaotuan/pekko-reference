package cn.xuyinyin.magic.workflow.scheduling

import java.util.concurrent.TimeUnit
import scala.collection.mutable
import spray.json._

final case class ManagedSnapshotChild(identity:SnapshotOwnerIdentity,process:Process)

/** Trusted supervisor capability: only children created here are eligible. No PID-adoption or shell fencing commands. */
final class ManagedJvmIsolation(deploymentId:String,maxWorkers:Int) {
  require(deploymentId.nonEmpty && maxWorkers>0)
  private[scheduling] val capabilityId:String=java.util.UUID.randomUUID().toString
  private val children=mutable.Map.empty[String,ManagedSnapshotChild]
  // A successful fork is never equivalent to NOT_STARTED, even if its identity lookup fails.
  private val unidentified=mutable.Map.empty[String,Process]
  @volatile private var usedBoot:String=>Boolean = _=>false
  private[scheduling] def bindRegistry(ownership:SnapshotOwnershipStore):Unit={usedBoot=id=>ownership.owner(id).nonEmpty}
  private[scheduling] def retire(id:String):Unit=synchronized {children.remove(id);()}
  private[scheduling] def hasUnidentified(id:String):Boolean=synchronized(unidentified.contains(id))
  def launch(bootId:String,builder:ProcessBuilder):ManagedSnapshotChild=synchronized {
    require(!children.contains(bootId) && !unidentified.contains(bootId) && !usedBoot(bootId),"boot identity cannot be reused")
    if(children.size+unidentified.size>=maxWorkers)throw new IllegalStateException("managed worker capacity exhausted")
    val process=builder.start()
    unidentified.update(bootId,process)
    try {
      val start=process.info().startInstant().orElseThrow(()=>new IllegalStateException("process start identity unavailable")).toString
      val child=ManagedSnapshotChild(SnapshotOwnerIdentity(bootId,deploymentId,process.pid(),start),process)
      children.update(bootId,child);unidentified.remove(bootId);child
    } catch {case e:Exception=>
      try process.destroyForcibly().waitFor(10,TimeUnit.SECONDS)
      catch {case scala.util.control.NonFatal(cleanup)=>e.addSuppressed(cleanup)}
      // No verified launch identity/receipt: retain the original handle and slot for manual isolation.
      throw e
    }
  }
  def managed:Vector[ManagedSnapshotChild]=synchronized(children.values.toVector)
  def confirm(identity:SnapshotOwnerIdentity,operation:String,terminate:Boolean):Option[SnapshotIsolationReceipt]={
    val child=synchronized(children.get(identity.bootId)).filter(_.identity==identity)
    child.flatMap { c=>
      if(terminate && c.process.isAlive)c.process.destroyForcibly()
      if(c.process.isAlive && terminate)c.process.waitFor(5,TimeUnit.SECONDS)
      if(c.process.isAlive)None else Some(SnapshotIsolationReceipt(operation,identity,"managed-child-jvm",System.currentTimeMillis(),
        JsObject("alive"->JsBoolean(false),"exitCode"->JsNumber(c.process.exitValue()),"automaticRestart"->JsBoolean(false)).compactPrint))
    }
  }
}

/** Called by the managed supervisor on a bounded timer. Restarting this coordinator preserves its SQL operation IDs. */
final class SnapshotTakeoverCoordinator(ownership:SnapshotOwnershipStore,isolation:ManagedJvmIsolation,
  observe:(String,(String,String,Long))=>SnapshotDatabaseObservation,minObservationIntervalMillis:Long=250) {
  require(minObservationIntervalMillis>0)
  isolation.bindRegistry(ownership)
  def start(intervalMillis:Long=250):AutoCloseable={
    require(intervalMillis>0)
    val executor=java.util.concurrent.Executors.newSingleThreadScheduledExecutor((task:Runnable)=>{
      val thread=new Thread(task,"managed-snapshot-takeover");thread.setDaemon(true);thread
    })
    executor.scheduleWithFixedDelay(new Runnable {def run():Unit=try tick() catch {
      case scala.util.control.NonFatal(error)=>org.slf4j.LoggerFactory.getLogger(getClass).warn("Takeover observation failed; grants remain charged ({})",error.getClass.getName)
    }},0,intervalMillis,java.util.concurrent.TimeUnit.MILLISECONDS)
    ()=>{executor.shutdown();if(!executor.awaitTermination(15,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("takeover coordinator shutdown is unconfirmed")}
  }
  def tick():Unit=isolation.managed.foreach { child=>
    ownership.owner(child.identity.bootId) match {
      case None if !child.process.isAlive=>
        isolation.confirm(child.identity,java.util.UUID.randomUUID().toString,terminate=false).foreach { receipt=>
          if(ownership.recordFailedStart(receipt))isolation.retire(child.identity.bootId)
        }
      case None=>()
      case Some(initial) if initial.phase=="startup_failed" && initial.identity==child.identity && !child.process.isAlive=>
        // The tombstone transaction may have committed even when its reply was lost.
        isolation.retire(child.identity.bootId)
      case Some(initial)=>
      if(child.process.isAlive && initial.phase=="active") {
        ownership.instances(child.identity.bootId).foreach { instance=>
          val observation=observe(child.identity.bootId,instance)
          if(observation.complete)ownership.forgetEndedSessions(child.identity.bootId,instance._1,observation.endedSessions)
        }
      } else {
        val operation=ownership.beginRevocation(child.identity.bootId,java.util.UUID.randomUUID().toString)
        isolation.confirm(child.identity,operation,terminate=initial.phase=="revoking").foreach(ownership.confirmIsolation)
        ownership.owner(child.identity.bootId).foreach { current=>
          if(Set("fenced","drain_wait").contains(current.phase))ownership.instances(child.identity.bootId).foreach { instance=>
            ownership.recordObservation(child.identity.bootId,observe(child.identity.bootId,instance),minObservationIntervalMillis)
          }
          ownership.releaseDrained(child.identity.bootId,100)
          if(ownership.owner(child.identity.bootId).exists(_.phase=="db_drained") && !ownership.hasOutstandingQuota(child.identity.bootId))isolation.retire(child.identity.bootId)
        }
      }
    }
  }
}
