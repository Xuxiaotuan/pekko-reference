package cn.xuyinyin.magic

import cn.xuyinyin.magic.cdc.platform.CdcTaskService
import com.typesafe.config.Config
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.server.Route

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration

/**
 * Compatibility facade for existing launch scripts and test fixtures.
 * Production uses [[cn.xuyinyin.magic.cdc.runtime.CdcRuntimeMain]].
 */
@deprecated("Use cn.xuyinyin.magic.cdc.runtime.CdcRuntimeMain", "0.2")
object CdcStandalone {
  type Running = cn.xuyinyin.magic.cdc.runtime.CdcRuntime.Running

  private[magic] def routes(service: CdcTaskService)(implicit ec: ExecutionContext): Route =
    cn.xuyinyin.magic.cdc.runtime.CdcRuntime.routes(service)

  private[magic] def httpAddress(config: Config): (String, Int) =
    cn.xuyinyin.magic.cdc.runtime.CdcRuntime.httpAddress(config)

  private[magic] def shutdownWait(config: Config): FiniteDuration =
    cn.xuyinyin.magic.cdc.runtime.CdcRuntime.shutdownWait(config)

  private[magic] def standaloneConfig(config: Config): Config =
    cn.xuyinyin.magic.cdc.runtime.CdcRuntime.standaloneConfig(config)

  private[magic] def awaitStopped(requestStop: () => Unit, status: () => String, timeout: FiniteDuration)
    (implicit system: ActorSystem[_], ec: ExecutionContext): Future[Done] =
    cn.xuyinyin.magic.cdc.runtime.CdcRuntime.awaitStopped(requestStop, status, timeout)

  def start(config: Config): Future[Running] = cn.xuyinyin.magic.cdc.runtime.CdcRuntime.start(config)

  def main(args: Array[String]): Unit = cn.xuyinyin.magic.cdc.runtime.CdcRuntime.main(args)
}
