package cn.xuyinyin.magic.single

import cn.xuyinyin.magic.common.CborSerializable
import cn.xuyinyin.magic.single.PNGCounterCache.{Decrement, GetValue}
import org.apache.pekko.actor.typed.scaladsl.Behaviors.{receiveMessagePartial, same, setup, withTimers}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.cluster.ddata.PNCounterKey

import scala.concurrent.duration.{DurationInt, FiniteDuration}

object PekkoGc {

  sealed trait Command                       extends CborSerializable
  final case object GC                       extends Command
  final case class UpdateGCLimit(value: Int) extends Command

  private val gcInterval: FiniteDuration = 3.seconds

  def apply(): Behavior[Command] = setup { ctx =>
    ctx.log.info("PekkoGc starting as cluster singleton")
    val limitCounterKey: PNCounterKey = PNCounterKey("gc-limitation")
    val gcCounter = ctx.spawn(PNGCounterCache.apply(limitCounterKey), "gcCounter")

    // 初始同步GC限制
    gcCounter ! GetValue(ctx.messageAdapter(UpdateGCLimit))

    active(gcCounter)(ctx)
  }

  private def active(gcCounter: ActorRef[PNGCounterCache.Command])(implicit ctx: ActorContext[Command]): Behavior[Command] = setup { ctx =>
    withTimers[Command] { timer =>
      ctx.log.info("PekkoGc started.")
      timer.startTimerAtFixedRate(GC, gcInterval)
      var gcLimit = 0
      var gcCount = 0

      receiveMessagePartial[Command] {
        case GC =>
          ctx.log.info(s"PekkoGc GC triggered. gcCount=$gcCount, gcLimit=$gcLimit")
          if (gcCount < gcLimit) {
            gcCount = gcCount + 1
            gcCounter ! Decrement(1)
            ctx.log.info(s"PekkoGc remove cache. Today Gc times $gcCount ,gcLimit: $gcLimit")
          } else {
            ctx.log.info(s"PekkoGc -------------$gcLimit-------------------")
          }
          same
        case UpdateGCLimit(value) =>
          ctx.log.info(s"PekkoGc updating GC limit from $gcLimit to $value")
          gcLimit = value
          same
      }
    }
  }

}
