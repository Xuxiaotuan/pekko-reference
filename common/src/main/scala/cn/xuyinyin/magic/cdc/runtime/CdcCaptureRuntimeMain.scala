package cn.xuyinyin.magic.cdc.runtime

import cn.xuyinyin.magic.cdc.platform.CdcPlatformSettings
import com.typesafe.config.ConfigFactory
import java.sql.DriverManager
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.concurrent.Await

/** Capture-only entry point. Worker scheduling remains in CdcRuntimeMain. */
object CdcCaptureRuntimeMain {
  def main(args: Array[String]): Unit = {
    require(args.isEmpty, "configure CaptureRuntime through the application configuration")
    val config                        = ConfigFactory.load()
    val settings                      = CdcPlatformSettings.load(config).getOrElse(throw new IllegalArgumentException("CDC platform must be enabled"))
    val state                         = settings.captures.head.controlState
    val leases                        = new CdcRuntimeLeaseStore(() => DriverManager.getConnection(state.jdbcUrl, state.username, state.password))
    implicit val ec: ExecutionContext = ExecutionContext.global
    val runtime                       = new CdcCaptureRuntime(settings, leases)
    runtime.start()
    sys.addShutdownHook(Await.result(runtime.stop(), Duration.Inf))
    Await.result(scala.concurrent.Promise[Unit]().future, Duration.Inf)
  }
}
