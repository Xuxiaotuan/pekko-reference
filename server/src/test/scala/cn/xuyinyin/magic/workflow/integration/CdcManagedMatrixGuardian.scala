package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.workflow.scheduling._
import java.nio.file.Path

/** Independent JVM: actual guardian service and Process handles; worker is a test-only entry point. */
object CdcManagedMatrixGuardian {
  def main(args:Array[String]):Unit={
    val Array(config,worker,directory)=args
    val settings=new SnapshotSupervisorSettings(Path.of(config));val lock=settings.lock("guardian")
    try {
      settings.metadata.enroll()
      val self=ProcessHandle.current();settings.metadata.bindGuardianProcess(self.pid(),self.info().startInstant().orElseThrow().toString)
      val isolation=new ManagedJvmIsolation(settings.binding.deploymentId,1)
      def builder(t:SnapshotWorkerLaunch):ProcessBuilder={
        val p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx768m","-Duser.timezone=UTC",
          "-Dpekko.workflow.snapshot-ha.launch-profile="+t.profileHash,"-cp",System.getProperty("java.class.path"),
          "cn.xuyinyin.magic.workflow.cdc.CdcManagedMatrixWorker",config,worker,t.bootId,directory)
        p.redirectErrorStream(true);p.redirectOutput(Path.of(directory,t.bootId+"-worker.log").toFile);p
      }
      val service=new SnapshotGuardianService(settings.metadata,isolation,builder,settings.signingKey)
      val server=new SnapshotGuardianWire.Server(settings.socket,settings.secret,settings.binding,settings.signingKey,service.handle,attest=service.attest)
      try server.run()finally server.close()
    }finally {try lock.close()finally settings.close()}
  }
}
