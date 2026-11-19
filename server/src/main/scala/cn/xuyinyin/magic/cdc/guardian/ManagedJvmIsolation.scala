package cn.xuyinyin.magic.cdc.guardian




import cn.xuyinyin.magic.cdc.guardian.{SnapshotOwnerIdentity, SnapshotIsolationReceipt}
import java.util.concurrent.TimeUnit
import scala.collection.mutable
import spray.json._

final case class ManagedSnapshotChild(identity:SnapshotOwnerIdentity,process:Process)

/** Trusted supervisor capability: only children created here are eligible. No PID-adoption or shell fencing commands. */
final class ManagedJvmIsolation(deploymentId:String,maxWorkers:Int) {
  require(deploymentId.nonEmpty && maxWorkers>0)
  private[guardian] val capabilityId:String=java.util.UUID.randomUUID().toString
  private val children=mutable.Map.empty[String,ManagedSnapshotChild]
  // A successful fork is never equivalent to NOT_STARTED, even if its identity lookup fails.
  private val unidentified=mutable.Map.empty[String,Process]
  @volatile private var usedBoot:String=>Boolean = _=>false
  private[guardian] def bindRegistry(used:String=>Boolean):Unit={usedBoot=used}
  private[guardian] def retire(id:String):Unit=synchronized {children.remove(id);()}
  private[guardian] def hasUnidentified(id:String):Boolean=synchronized(unidentified.contains(id))
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
