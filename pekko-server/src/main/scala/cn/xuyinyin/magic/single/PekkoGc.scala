package cn.xuyinyin.magic.single

import cn.xuyinyin.magic.common.CborSerializable
import cn.xuyinyin.magic.common.PekkoActorImplicits.BehaviorWrapper
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.Behaviors.{receiveMessagePartial, same, setup, withTimers}
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.cluster.typed.{ClusterSingleton, SingletonActor}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

object PekkoGc {

  sealed trait Command         extends CborSerializable
  final case object GC extends Command

  private val gcInterval: FiniteDuration = 1.seconds

  def apply(): Behavior[Command] = setup { implicit ctx =>
    val singletonMgr: ClusterSingleton = ClusterSingleton(ctx.system)
    ctx.log.info("PekkoGc starting")
    val gcProxy: ActorRef[Command] = singletonMgr.init(
      SingletonActor(
        Behaviors
          .supervise(active())
          .onFailure(SupervisorStrategy.restart),
        "PekkoGcActor")
    )
    receiveMessagePartial { case cmd => gcProxy ! cmd; same }
  }

   private def active(): Behavior[Command] = setup { ctx =>
    withTimers[Command] { timer =>
      ctx.log.info("PekkoGc started.")
      timer.startTimerAtFixedRate(GC, gcInterval)

      receiveMessagePartial[Command] { case GC =>
        ctx.log.info(s"PekkoGc remove cache.")
        same
      }.onFailure(SupervisorStrategy.restart)
    }
  }

}
