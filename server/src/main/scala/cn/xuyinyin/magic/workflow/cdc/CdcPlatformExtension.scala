package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.connectors.mysql.source.MySQLCdcStateConfig
import org.apache.pekko.Done
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.typed.{ActorSystem,Extension,ExtensionId}
import java.nio.file.Path
import scala.concurrent.ExecutionContext
import cn.xuyinyin.magic.workflow.scheduling.{SnapshotGuardianWire,SnapshotOwnerJdbc,SnapshotSupervisorSettings}

/** Explicitly opt-in. The existing workflow/static-snapshot runtime stays independent. */
final class CdcPlatformExtension(system:ActorSystem[_]) extends Extension {
  val service:Option[CdcTaskService]=CdcPlatformSettings.load(system.settings.config).map {settings=>
    val state=MySQLCdcStateConfig.load(system.settings.config)
    val config=system.settings.config
    var guardianCheck:()=>Unit=()=>()
    val managed=if(config.hasPath("pekko.workflow.snapshot-ha.supervisor-config")) {
      val identity=SnapshotOwnerJdbc.context.getOrElse(throw new IllegalStateException("managed owner bootstrap missing")).identity
      val self=ProcessHandle.current()
      require(identity.pid==self.pid() && self.info().startInstant().isPresent && identity.startIdentity==self.info().startInstant().get().toString,
        "managed worker is not the current process")
      val supervisor=new SnapshotSupervisorSettings(Path.of(config.getString("pekko.workflow.snapshot-ha.supervisor-config")))
      try {
        val profile=config.getString("pekko.workflow.snapshot-ha.launch-profile")
        supervisor.metadata.verifyWorker(identity.bootId,identity,profile)
        val b=supervisor.binding
        val client=new SnapshotGuardianWire.WorkerClient(supervisor.socket,supervisor.secret.clone(),b)
        guardianCheck=()=>client.attest(identity,profile)
        Some(CdcManagedOwner(identity.bootId,identity.deploymentId,b.hostId,b.bootId,b.publicKey,identity.pid,identity.startIdentity,profile))
      } finally supervisor.close()
    }else None
    val store=new CdcTaskStore(()=>SnapshotOwnerJdbc.open(CdcJdbc.withTimeouts(state.jdbcUrl),state.username,state.password,"control",managed.nonEmpty),settings.limits)
    val recover=managed.map { _ =>
      val supervisorConfig=Path.of(config.getString("pekko.workflow.snapshot-ha.supervisor-config"))
      () => CdcManagedRecovery.recover(store,settings,supervisorConfig,guardianCheck)
    }.getOrElse(() => ())
    val kernel=new CdcBootstrapKernel(settings,system,managed=managed.nonEmpty,
      recoveryStore=if(managed.nonEmpty)Some(store)else None)
    val runtime=new CdcPlatformRuntime(store,kernel,settings.limits,
      polling=true,pollIntervalMillis=settings.pollIntervalMillis,managedOwner=managed,recoverManaged=recover,guardianCheck=guardianCheck)
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate,"stop-cdc-platform") {()=>
      // UNKNOWN remains in the durable owner/grant records; process exit is never a release proof.
      runtime.stopRuntime().map(_=>Done)(ExecutionContext.parasitic)
    }
    runtime
  }
}
object CdcPlatformExtension extends ExtensionId[CdcPlatformExtension] {
  override def createExtension(system:ActorSystem[_]):CdcPlatformExtension=new CdcPlatformExtension(system)
}
