package cn.xuyinyin.magic.server.http

import cn.xuyinyin.magic.api.http.routes.HttpRoutes
import cn.xuyinyin.magic.cluster.PekkoGuardian
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/** Binding ownership and coordinated ingress/drain; no workflow or recovery decisions. */
private[magic] object HttpServerLifecycle {
  def start(system: ActorSystem[PekkoGuardian.Command]): Future[Http.ServerBinding] = {
    implicit val ec: ExecutionContext = system.executionContext
    implicit val timeout: Timeout = Timeout(5.seconds)
    implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = system.scheduler
    val health = system.ask(ref => PekkoGuardian.GetHealthChecker(ref))
    val coordinator = system.ask(ref => PekkoGuardian.GetSchedulerCoordinator(ref))
    val supervisor = system.ask(ref => PekkoGuardian.GetWorkflowSupervisor(ref))
    for { h <- health; c <- coordinator; w <- supervisor
      binding <- bind(system, HttpRoutes.createRoutes(system,h,system,c,w))
    } yield binding
  }

  def bind(system: ActorSystem[_], routes: Route): Future[Http.ServerBinding] = {
    implicit val classic: org.apache.pekko.actor.ActorSystem = system.classicSystem
    implicit val ec: ExecutionContext = system.executionContext
    val config = system.settings.config
    val grace = shutdownGrace(config)
    Http().newServerAt(config.getString("http.host"),config.getInt("http.port")).bind(routes)
      .map(_.addToCoordinatedShutdown(grace))
  }
  private[server] def shutdownGrace(config: com.typesafe.config.Config): FiniteDuration = {
    val grace = config.getDuration("http.shutdown-grace").toMillis.millis
    val phase = config.getDuration("pekko.coordinated-shutdown.phases.service-requests-done.timeout").toMillis.millis
    require(grace.length > 0 && grace < phase, "http shutdown grace must be positive and less than service-requests-done timeout")
    grace
  }

}
