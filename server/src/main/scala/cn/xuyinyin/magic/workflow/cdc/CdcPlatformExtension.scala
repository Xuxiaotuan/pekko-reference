package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.connectors.mysql.source.MySQLCdcStateConfig
import org.apache.pekko.Done
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.typed.{ActorSystem,Extension,ExtensionId}
import java.sql.DriverManager
import scala.concurrent.ExecutionContext

/** Explicitly opt-in. The existing workflow/static-snapshot runtime stays independent. */
final class CdcPlatformExtension(system:ActorSystem[_]) extends Extension {
  val service:Option[CdcTaskService]=CdcPlatformSettings.load(system.settings.config).map {settings=>
    val state=MySQLCdcStateConfig.load(system.settings.config)
    val store=new CdcTaskStore(()=>DriverManager.getConnection(CdcJdbc.withTimeouts(state.jdbcUrl),state.username,state.password),settings.limits)
    val runtime=new CdcPlatformRuntime(store,new CdcBootstrapKernel(settings,system),settings.limits,
      polling=true,pollIntervalMillis=settings.pollIntervalMillis)
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
