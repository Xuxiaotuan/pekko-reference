package cn.xuyinyin.magic.cdc.runtime

import cn.xuyinyin.magic.api.http.routes.CdcTaskRoutes
import cn.xuyinyin.magic.cdc.platform.{CdcPlatformExtension, CdcPlatformRuntime, CdcPlatformSettings, CdcTaskService}
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.Logger
import org.apache.pekko.Done
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Route

import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.control.NonFatal
import java.util.concurrent.TimeoutException

/** Standalone CDC entry point. Binding the API does not imply that capture is ready. */
object CdcRuntime {
  private val logger = Logger(getClass)
  final case class Running(system: ActorSystem[Nothing], service: CdcTaskService, binding: Http.ServerBinding)

  private[magic] def routes(service: CdcTaskService)(implicit ec: ExecutionContext): Route =
    new CdcTaskRoutes(Some(service)).routes

  private[magic] def httpAddress(config: Config): (String, Int) = {
    val prefix = "pekko.cdc-standalone.http."
    val host = if (config.hasPath(prefix + "host")) config.getString(prefix + "host") else "127.0.0.1"
    require(Set("127.0.0.1", "localhost", "::1").contains(host), "CDC standalone API requires a loopback host")
    require(config.hasPath(prefix + "port"), "CDC standalone API port must be configured")
    val port = config.getInt(prefix + "port")
    require(port >= 0 && port <= 65535, "invalid CDC standalone API port")
    host -> port
  }

  private[magic] def shutdownWait(config: Config): FiniteDuration = {
    val path = "pekko.coordinated-shutdown.phases.before-actor-system-terminate.timeout"
    val timeout = if (config.hasPath(path)) config.getDuration(path).toMillis.millis
      else config.getDuration("pekko.coordinated-shutdown.default-phase-timeout").toMillis.millis
    require(timeout > 500.millis, "CDC shutdown phase timeout must exceed 500 milliseconds")
    (timeout - 250.millis).min(15.seconds)
  }

  private[magic] def standaloneConfig(config: Config): Config = {
    // Only this entry gets a longer release observation window. An explicitly
    // configured phase timeout retains precedence over this fallback.
    val phaseDefault = ConfigFactory.parseString(
      "pekko.coordinated-shutdown.phases.before-actor-system-terminate.timeout = 20s")
    ConfigFactory.parseString("pekko.actor.provider = local").withFallback(config).withFallback(phaseDefault).resolve()
  }

  /** Wait for the existing close operation; timeout never certifies resource release. */
  private[magic] def awaitStopped(requestStop: () => Unit, status: () => String, timeout: FiniteDuration)
    (implicit system: ActorSystem[_], ec: ExecutionContext): Future[Done] = {
    val result = Promise[Done]()
    val deadline = System.nanoTime() + timeout.toNanos
    def observe(): Unit = try {
      if (status() == "STOPPED") result.trySuccess(Done)
      else if (System.nanoTime() >= deadline) result.tryFailure(new TimeoutException("CDC shutdown remains unconfirmed"))
      else system.scheduler.scheduleOnce(100.millis, new Runnable { def run(): Unit = observe() })
    } catch { case NonFatal(error) => result.tryFailure(error) }
    try { requestStop(); observe() } catch { case NonFatal(error) => result.tryFailure(error) }
    result.future
  }

  def start(config: Config): Future[Running] = {
    val settings = CdcPlatformSettings.load(config).getOrElse(throw new IllegalArgumentException("CDC platform must be enabled"))
    val managed=config.hasPath("pekko.cdc-standalone.managed-worker") && config.getBoolean("pekko.cdc-standalone.managed-worker")
    val supervised=config.hasPath("pekko.workflow.snapshot-ha.supervisor-config")
    val haEnabled=config.hasPath("pekko.workflow.snapshot-ha.enabled") && config.getBoolean("pekko.workflow.snapshot-ha.enabled")
    require(if(managed) supervised && haEnabled else !supervised && !haEnabled,
      "CDC managed startup requires an explicit supervisor launch")
    require(!settings.captures.exists(_.sourceFenceV2), "CDC standalone does not support source fence v2")
    val (host, port) = httpAddress(config)
    val grace = config.getDuration("http.shutdown-grace").toMillis.millis
    val phase = config.getDuration("pekko.coordinated-shutdown.phases.service-requests-done.timeout").toMillis.millis
    require(grace.length > 0 && grace < phase, "HTTP shutdown grace must be less than the coordinated shutdown timeout")
    // A local actor provider starts no cluster, sharding, scheduler, or frontend.
    // All other settings, including journal and serialization, remain unchanged.
    val prepared=if(managed) cn.xuyinyin.magic.cdc.guardian.SnapshotOwnerBootstrap.prepare(config) else config
    val local = standaloneConfig(prepared)
    val stopWait = shutdownWait(local)
    val system = try ActorSystem[Nothing](Behaviors.empty, config.getString("pekko.pekko-sys"), local)
      catch {case NonFatal(error)=>if(managed)cn.xuyinyin.magic.cdc.guardian.ManagedCdcJdbc.context.foreach(_.close());throw error}
    implicit val ec: ExecutionContext = system.executionContext
    if(managed)system.whenTerminated.onComplete(_=>cn.xuyinyin.magic.cdc.guardian.ManagedCdcJdbc.context.foreach(_.close()))(ExecutionContext.parasitic)
    implicit val classic: org.apache.pekko.actor.ActorSystem = system.classicSystem
    try {
      val service = CdcPlatformExtension(system).service.getOrElse(throw new IllegalStateException("CDC platform unavailable"))
      val runtime = service match {
        case value: CdcPlatformRuntime => value
        case _ => throw new IllegalStateException("CDC platform runtime unavailable")
      }
      CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate, "wait-cdc-release") { () =>
        awaitStopped(() => { runtime.stopRuntime(); () }, () => runtime.status.ownerState, stopWait)(system, ec)
      }
      Http().newServerAt(host, port).bind(routes(service))
        .map(binding => Running(system, service, binding.addToCoordinatedShutdown(grace)))
        .recoverWith { case NonFatal(error) => system.terminate(); Future.failed(error) }
    } catch {
      case NonFatal(error) => system.terminate(); throw error
    }
  }

  def main(args: Array[String]): Unit = {
    require(args.isEmpty, "configure CDC standalone through the application configuration")
    val running = Await.result(start(ConfigFactory.load()), Duration.Inf)
    logger.info("CDC standalone API bound at {}; runtime status is available at /api/v1/cdc-status", running.binding.localAddress)
    try Await.result(running.system.whenTerminated, Duration.Inf)
    catch { case _: InterruptedException => running.system.terminate() }
  }
}
