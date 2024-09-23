package cn.xuyinyin.magic.cluster

import cn.xuyinyin.magic.ClusterListener
import cn.xuyinyin.magic.single.PekkoGc
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.Behaviors.same
import org.apache.pekko.cluster.typed.Cluster

/**
 * @author : Xuxiaotuan
 * @since : 2024-09-21 22:18
 */
object PekkoGuardian {

  sealed trait Command
  final case class GetNodeRoles(reply: ActorRef[Set[String]]) extends Command

  def apply(): Behavior[Command] = Behaviors.setup { implicit ctx =>
    val selfMember = Cluster(ctx.system).selfMember
    ctx.log.info(s"Pekko System Guardian Launching, roles: ${selfMember.roles.mkString(",")}")

    // Create an actor that handles cluster domain events
    ctx.spawn(ClusterListener(), "ClusterListener")

    ctx.spawn(PekkoGc(), "PekkoGc")

    Behaviors.receiveMessagePartial {
      case GetNodeRoles(reply) =>
        reply ! selfMember.roles
        same
    }
  }
}
