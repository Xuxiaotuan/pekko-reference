package cn.xuyinyin.magic.cluster

import cn.xuyinyin.magic.ClusterListener
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

/**
 * @author : XuJiaWei
 * @since : 2024-09-21 22:18
 */
object PekkoGuardian {

  sealed trait Command

  def apply(): Behavior[Command] = Behaviors.setup { implicit ctx =>
    // Create an actor that handles cluster domain events
    ctx.spawn(ClusterListener(), "ClusterListener")

    Behaviors.empty
  }
}
