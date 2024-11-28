package cn.xuyinyin.magic.single

import cn.xuyinyin.magic.common.CborSerializable
import cn.xuyinyin.magic.common.PekkoActorImplicits.BehaviorWrapper
import cn.xuyinyin.magic.single.PNGCounterCache.{Decrement, GetValue}
import org.apache.pekko.actor.typed.scaladsl.Behaviors.{receiveMessagePartial, same, setup, withTimers}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.cluster.ddata.PNCounterKey
import org.apache.pekko.cluster.typed.{ClusterSingleton, SingletonActor}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

object PekkoGc {

  sealed trait Command                       extends CborSerializable
  final case object GC                       extends Command
  final case object SyncGcLimitFromDData     extends Command
  final case class UpdateGCLimit(value: Int) extends Command

  private val gcInterval: FiniteDuration = 3.seconds

  def apply(): Behavior[Command] = setup { implicit ctx =>
    val singletonMgr: ClusterSingleton = ClusterSingleton(ctx.system)
    ctx.log.info("PekkoGc starting")
    val limitCounterKey: PNCounterKey = PNCounterKey("gc-limitation")
    val gcCounter                     = ctx.spawn(PNGCounterCache.apply(limitCounterKey), "gcCounter")

    val gcProxy: ActorRef[Command] = singletonMgr.init(
      SingletonActor(
        Behaviors
          .supervise(active(gcCounter).beforeIt(ctx.self ! SyncGcLimitFromDData))
          .onFailure(SupervisorStrategy.restart),
        "PekkoGcActor")
    )
    receiveMessagePartial { case cmd => gcProxy ! cmd; same }
  }

  private def active(gcCounter: ActorRef[PNGCounterCache.Command])(implicit ctx: ActorContext[Command]): Behavior[Command] = setup { ctx =>
    withTimers[Command] { timer =>
      ctx.log.info("PekkoGc started.")
      timer.startTimerAtFixedRate(GC, gcInterval)
      var gcLimit = 0
      var gcCount = 0

      receiveMessagePartial[Command] {
        case GC =>
          if (gcCount < gcLimit) {
            gcCount = gcCount + 1
            gcCounter ! Decrement(1)
            ctx.log.info(s"PekkoGc remove cache. Today Gc times $gcCount ,gcLimit: $gcLimit")
          } else {
            ctx.log.info(s"PekkoGc -------------$gcLimit-------------------")
          }
          same
        case UpdateGCLimit(value) =>
          gcLimit = value
          same
        case SyncGcLimitFromDData =>
          gcCounter ! GetValue(ctx.messageAdapter(UpdateGCLimit))
          same
      }.onFailure(SupervisorStrategy.restart)
    }
  }

}
