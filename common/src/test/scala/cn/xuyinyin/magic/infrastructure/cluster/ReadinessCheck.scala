package cn.xuyinyin.magic.infrastructure.cluster

import org.apache.pekko.actor.typed.ActorSystem
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.FiniteDuration

/** One actual probe at a time. An observer deadline never frees unfinished JDBC work. */
private[magic] final class ReadinessCheck(probes: HealthChecker.ReadinessProbes, timeout: FiniteDuration)
  (implicit system: ActorSystem[_], ec: ExecutionContext) {
  require(timeout.length > 0, "readiness timeout must be positive")
  private var active = Option.empty[Future[HealthChecker.ReadinessStatus]]

  def check(): Future[HealthChecker.ReadinessStatus] = synchronized {
    active.getOrElse {
      val observed = Promise[HealthChecker.ReadinessStatus]()
      active = Some(observed.future)
      val timer = system.scheduler.scheduleOnce(timeout, new Runnable {
        def run(): Unit = observed.trySuccess(HealthChecker.ReadinessStatus(false, false, false))
      })(ec)
      HealthChecker.readiness(probes).onComplete { outcome =>
        synchronized { active = None }
        timer.cancel()
        observed.tryComplete(outcome)
      }
      observed.future
    }
  }
}
