package cn.xuyinyin.magic.cluster

import cn.xuyinyin.magic.ClusterListener
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.typed.Cluster

/**
 * @author : Xuxiaotuan
 * @since : 2024-09-21 22:18
 */
object PekkoGuardian {

  sealed trait Command

  def apply(): Behavior[Command] = Behaviors.setup { implicit ctx =>
    val selfMember = Cluster(ctx.system).selfMember
    ctx.log.info(s"Pekko System Guardian Launching, roles: ${selfMember.roles.mkString(",")}")

    // Create an actor that handles cluster domain events
    ctx.spawn(ClusterListener(), "ClusterListener")

    Behaviors.empty
  }
}
