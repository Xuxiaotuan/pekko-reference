package cn.xuyinyin.magic.cluster

import cn.xuyinyin.magic.monitoring.ClusterEventLogger
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.ClusterEvent._
import org.apache.pekko.cluster.typed.{Cluster, Subscribe}
import com.typesafe.scalalogging.Logger

/**
 * 集群事件监听器
 * 
 * 监听并记录所有集群事件，包括：
 * - 成员加入/离开
 * - Leader变更
 * - 不可达节点
 * - 可达节点恢复
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-28
 */
object ClusterEventListener {
  
  private val logger = Logger(getClass)
  
  sealed trait Command
  private case class MemberEventWrapper(event: MemberEvent) extends Command
  private case class ReachabilityEventWrapper(event: ReachabilityEvent) extends Command
  private case class LeaderChangedWrapper(event: LeaderChanged) extends Command
  
  def apply(): Behavior[Command] = Behaviors.setup { context =>
    val cluster = Cluster(context.system)
    val selfAddress = cluster.selfMember.address.toString
    
    // 订阅集群事件
    val memberEventAdapter = context.messageAdapter[MemberEvent](MemberEventWrapper)
    cluster.subscriptions ! Subscribe(memberEventAdapter, classOf[MemberEvent])
    
    val reachabilityEventAdapter = context.messageAdapter[ReachabilityEvent](ReachabilityEventWrapper)
    cluster.subscriptions ! Subscribe(reachabilityEventAdapter, classOf[ReachabilityEvent])
    
    val leaderChangedAdapter = context.messageAdapter[LeaderChanged](LeaderChangedWrapper)
    cluster.subscriptions ! Subscribe(leaderChangedAdapter, classOf[LeaderChanged])
    
    logger.info("ClusterEventListener started, monitoring cluster events")
    
    var currentLeader: Option[String] = None
    
    Behaviors.receiveMessage {
      case MemberEventWrapper(event) =>
        event match {
          case MemberJoined(member) =>
            logger.info(s"Member joined: ${member.address}")
            ClusterEventLogger.logMemberEvent(
              member.address.toString,
              "joining",
              member.roles
            )
            
          case MemberUp(member) =>
            logger.info(s"Member is Up: ${member.address}")
            ClusterEventLogger.logMemberEvent(
              member.address.toString,
              "up",
              member.roles
            )
            
          case MemberLeft(member) =>
            logger.info(s"Member left: ${member.address}")
            ClusterEventLogger.logMemberEvent(
              member.address.toString,
              "leaving",
              member.roles
            )
            
          case MemberExited(member) =>
            logger.info(s"Member exited: ${member.address}")
            ClusterEventLogger.logMemberEvent(
              member.address.toString,
              "exiting",
              member.roles
            )
            
          case MemberRemoved(member, previousStatus) =>
            logger.info(s"Member removed: ${member.address} (was $previousStatus)")
            ClusterEventLogger.logMemberEvent(
              member.address.toString,
              "removed",
              member.roles
            )
            
          case _ =>
            logger.debug(s"Other member event: $event")
        }
        Behaviors.same
        
      case ReachabilityEventWrapper(event) =>
        event match {
          case UnreachableMember(member) =>
            logger.warn(s"Member detected as unreachable: ${member.address}")
            ClusterEventLogger.logUnreachableMember(
              member.address.toString,
              selfAddress
            )
            
          case ReachableMember(member) =>
            logger.info(s"Member is reachable again: ${member.address}")
            ClusterEventLogger.logReachableMember(member.address.toString)
        }
        Behaviors.same
        
      case LeaderChangedWrapper(LeaderChanged(leaderOption)) =>
        val newLeader = leaderOption.map(_.toString).getOrElse("none")
        logger.info(s"Leader changed: ${currentLeader.getOrElse("none")} -> $newLeader")
        
        if (leaderOption.isDefined) {
          ClusterEventLogger.logLeaderChanged(currentLeader, newLeader)
          currentLeader = Some(newLeader)
        }
        
        Behaviors.same
    }
  }
}
