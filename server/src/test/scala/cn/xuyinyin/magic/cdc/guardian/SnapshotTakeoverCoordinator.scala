package cn.xuyinyin.magic.cdc.guardian

import cn.xuyinyin.magic.cdc.control.ManagedOwnerRegistry

/** Called by the managed supervisor on a bounded timer. Restarting this coordinator preserves its SQL operation IDs. */
final class SnapshotTakeoverCoordinator(ownership:ManagedOwnerRegistry,isolation:ManagedJvmIsolation,
  observe:(String,(String,String,Long))=>SnapshotDatabaseObservation,minObservationIntervalMillis:Long=250) {
  require(minObservationIntervalMillis>0)
  isolation.bindRegistry(id=>ownership.owner(id).nonEmpty)
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
