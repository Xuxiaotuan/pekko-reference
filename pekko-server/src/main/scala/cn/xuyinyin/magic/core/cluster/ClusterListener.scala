package cn.xuyinyin.magic.core.cluster

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.cluster.ClusterEvent._
import org.apache.pekko.cluster.Member
import org.apache.pekko.cluster.typed.{Cluster, Subscribe}

/**
 * 集群监听器 - 监控集群成员变化和可达性事件
 * 
 * 负责监控集群状态变化，包括节点加入、离开、故障检测等，
 * 并根据节点角色进行相应的处理和日志记录。
 * 
 * @author : Xuxiaotuan
 * @since : 2024-09-21 22:18
 */
object ClusterListener {

  /**
   * 集群监听器事件类型
   */
  sealed trait Event
  
  // 内部适配的集群事件
  private final case class ReachabilityChange(reachabilityEvent: ReachabilityEvent) extends Event
  private final case class MemberChange(event: MemberEvent) extends Event
  private final case class LeaderChange(event: LeaderChanged) extends Event
  
  // 外部查询事件
  final case class GetClusterStatus(replyTo: ActorRef[ClusterStatus]) extends Event
  final case class GetMembersByRole(role: String, replyTo: ActorRef[List[Member]]) extends Event

  /**
   * 集群状态信息
   */
  final case class ClusterStatus(
    leader: Option[Member],
    members: List[Member],
    unreachableMembers: List[Member],
    seenBy: Set[Member]
  )

  /**
   * 获取成员的角色
   */
  private def getMemberRole(member: Member): String = {
    member.roles.headOption.getOrElse("unknown")
  }

  /**
   * 检查成员是否为特定角色
   */
  private def isMemberOfRole(member: Member, role: String): Boolean = {
    getMemberRole(member) == role
  }

  def apply(): Behavior[Event] = Behaviors.setup { ctx =>
    ctx.log.info("ClusterListener starting up")

    // 订阅成员事件
    val memberEventAdapter: ActorRef[MemberEvent] = ctx.messageAdapter(MemberChange)
    Cluster(ctx.system).subscriptions ! Subscribe(memberEventAdapter, classOf[MemberEvent])

    // 订阅可达性事件
    val reachabilityAdapter = ctx.messageAdapter(ReachabilityChange)
    Cluster(ctx.system).subscriptions ! Subscribe(reachabilityAdapter, classOf[ReachabilityEvent])

    // 订阅领导者变更事件
    val leaderChangedAdapter = ctx.messageAdapter[LeaderChanged](LeaderChange)
    Cluster(ctx.system).subscriptions ! Subscribe(leaderChangedAdapter, classOf[LeaderChanged])

    Behaviors.receiveMessage {

      // 处理可达性变化事件
      case ReachabilityChange(reachabilityEvent) =>
        reachabilityEvent match {
          case UnreachableMember(member) =>
            val role = getMemberRole(member)
            ctx.log.warn("🚨 Member detected as unreachable: {} [Role: {}] [Address: {}]",
              member.uniqueAddress, role, member.address)

            // 如果是关键节点不可达，需要特殊处理
            if (role == NodeRole.COORDINATOR) {
              ctx.log.error("⚠️ COORDINATOR node is unreachable! This may affect task scheduling.")
            }

          case ReachableMember(member) =>
            val role = getMemberRole(member)
            ctx.log.info("✅ Member back to reachable: {} [Role: {}] [Address: {}]",
              member.uniqueAddress, role, member.address)

            if (role == NodeRole.COORDINATOR) {
              ctx.log.info("🎯 COORDINATOR node is back online - task scheduling resumed.")
            }
        }
        Behaviors.same

      // 处理成员变化事件
      case MemberChange(changeEvent) =>
        changeEvent match {
          case MemberUp(member) =>
            val role = getMemberRole(member)
            ctx.log.info("🚀 Member is Up: {} [Role: {}] [Address: {}]",
              member.uniqueAddress, role, member.address)

            // 根据角色进行特定处理
            role match {
              case NodeRole.COORDINATOR =>
                ctx.log.info("📋 New COORDINATOR joined - available for task coordination")
              case NodeRole.WORKER =>
                ctx.log.info("⚙️ New WORKER joined - available for data processing")
              case NodeRole.STORAGE =>
                ctx.log.info("💾 New STORAGE node joined - available for data storage")
              case NodeRole.API_GATEWAY =>
                ctx.log.info("🌐 New API_GATEWAY joined - available for external requests")
              case _ =>
                ctx.log.info("❓ Unknown role member joined: {}", role)
            }

          case MemberRemoved(member, previousStatus) =>
            val role = getMemberRole(member)
            ctx.log.warn("👋 Member is Removed: {} [Role: {}] [Previous Status: {}] [Address: {}]",
              member.uniqueAddress, role, previousStatus, member.address)

            // 如果是关键节点离开，需要警告
            role match {
              case NodeRole.COORDINATOR =>
                ctx.log.error("⚠️ COORDINATOR node left the cluster!")
              case NodeRole.WORKER =>
                ctx.log.warn("⚠️ WORKER node left - processing capacity reduced")
              case NodeRole.STORAGE =>
                ctx.log.warn("⚠️ STORAGE node left - storage capacity may be affected")
              case NodeRole.API_GATEWAY =>
                ctx.log.warn("⚠️ API_GATEWAY node left - external API availability reduced")
              case _ =>
                ctx.log.info("👋 Member left: {}", role)
            }

          case MemberExited(member) =>
            val role = getMemberRole(member)
            ctx.log.info("🚪 Member is Exiting: {} [Role: {}] [Address: {}]",
              member.uniqueAddress, role, member.address)

          case MemberWeaklyUp(member) =>
            val role = getMemberRole(member)
            ctx.log.info("💪 Member is WeaklyUp: {} [Role: {}] [Address: {}]",
              member.uniqueAddress, role, member.address)

          case _: MemberEvent =>
            // 忽略其他成员事件
            ctx.log.debug("Ignoring member event: {}", changeEvent.getClass.getSimpleName)
        }
        Behaviors.same

      // 处理领导者变更
      case LeaderChange(event) =>
        val cluster = Cluster(ctx.system)
        val leader = cluster.selfMember.address == cluster.state.leader.getOrElse(cluster.selfMember.address)
        if (leader) {
          ctx.log.info("👑 This node is now the cluster leader")
        } else {
          ctx.log.info("🤝 Cluster leader changed")
        }
        Behaviors.same

      // 处理集群状态查询
      case GetClusterStatus(replyTo) =>
        val cluster = Cluster(ctx.system)
        val state = cluster.state
        val status = ClusterStatus(
          leader = state.leader.flatMap(address => state.members.find(_.address == address)),
          members = state.members.toList,
          unreachableMembers = state.unreachable.toList,
          seenBy = state.seenBy.flatMap(address => state.members.find(_.address == address))
        )
        replyTo ! status
        Behaviors.same

      // 处理按角色查询成员
      case GetMembersByRole(role, replyTo) =>
        val cluster = Cluster(ctx.system)
        val members = cluster.state.members.filter(isMemberOfRole(_, role)).toList
        replyTo ! members
        Behaviors.same
    }
  }
}
