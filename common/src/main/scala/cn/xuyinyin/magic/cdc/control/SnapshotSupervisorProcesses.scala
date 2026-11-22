package cn.xuyinyin.magic.cdc.control


import cn.xuyinyin.magic.cdc.guardian.{ManagedJvmIsolation, SnapshotGuardianWire, SnapshotSupervisorSettings}
import java.nio.file.Path
import java.util.UUID

object SnapshotGuardianMain {
  def main(args:Array[String]):Unit={
    require(args.length==1,"operator-owned supervisor config required")
    val settings=new SnapshotSupervisorSettings(Path.of(args(0)))
    var lock:AutoCloseable=null
    try {
      lock=settings.lock("guardian");settings.validateWorkerProfile();settings.metadata.enroll()
      val self=ProcessHandle.current();settings.metadata.bindGuardianProcess(self.pid(),self.info().startInstant().orElseThrow().toString)
      val profile=settings.profileHash;val key=settings.signingKey
      // Key match is verified before accepting commands, not on first successful response.
      val probe=SnapshotGuardianWire.sign(spray.json.JsObject.empty,key)
      SnapshotGuardianWire.verified(probe,settings.binding.publicKey)
      val service=settings.metadata.guardianService(new ManagedJvmIsolation(settings.binding.deploymentId,settings.maxWorkers),settings.builder(profile),key)
      val server=new SnapshotGuardianWire.Server(settings.socket,settings.secret,settings.binding,key,service.handle,attest=service.attest)
      try server.run()finally server.close()
    }finally {try {if(lock!=null)lock.close()}finally settings.close()}
  }
}

object SnapshotSupervisorMain {
  def main(args:Array[String]):Unit={
    require(args.length==1,"operator-owned supervisor config required")
    val settings=new SnapshotSupervisorSettings(Path.of(args(0)))
    var lock:AutoCloseable=null
    try {
      lock=settings.lock("supervisor");settings.validateWorkerProfile();val boot=UUID.randomUUID().toString
      val control=try settings.metadata.claimController(boot)catch{case e:Exception=>settings.metadata.current.filter(_.bootId==boot).getOrElse(throw e)}
      val client=new SnapshotGuardianWire.Client(settings.socket,settings.secret,control)
      val profile=settings.profileHash
      val runtime=new SnapshotSupervisorRuntime(settings.metadata,control,client,settings.observer.observe,profile,event=(kind,value)=>
        org.slf4j.LoggerFactory.getLogger(getClass).info("Snapshot supervisor {} ({})",kind,value),
        needsIsolation=boot=>settings.cdcNeedsIsolation(boot,profile),wake=(boot,instance)=>{
          require(settings.profileHash==profile,"worker configuration changed")
          settings.wakeCdc(boot,instance)
        })
      while(!Thread.currentThread().isInterrupted){runtime.tick();Thread.sleep(250)}
    }finally {try {if(lock!=null)lock.close()}finally settings.close()}
  }
}
