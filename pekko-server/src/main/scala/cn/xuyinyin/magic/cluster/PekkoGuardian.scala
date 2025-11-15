package cn.xuyinyin.magic.cluster

import cn.xuyinyin.magic.single.PekkoGc
import cn.xuyinyin.magic.workflow.actors.WorkflowSupervisor
import cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngine
import cn.xuyinyin.magic.workflow.scheduler.{SchedulerManager, WorkflowScheduler}
import com.typesafe.scalalogging.Logger
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.cluster.typed.Cluster

import scala.concurrent.duration.DurationInt

/**
 * @author : Xuxiaotuan
 * @since : 2024-09-21 22:18
 */
object PekkoGuardian {
  private val logger = Logger(getClass)

  sealed trait Command
  private case class GetNodeRoles(reply: ActorRef[Set[String]]) extends Command
  case class GetHealthChecker(reply: ActorRef[ActorRef[HealthChecker.Command]]) extends Command
  case class GetWorkflowSupervisor(reply: ActorRef[ActorRef[WorkflowSupervisor.Command]]) extends Command
  case class GetSchedulerManager(reply: ActorRef[SchedulerManager]) extends Command
  private case object CheckLeadership extends Command

  def apply(): Behavior[Command] = Behaviors.setup { implicit ctx =>
    val cluster = Cluster(ctx.system)
    val selfMember = cluster.selfMember
    logger.info(s"Pekko System Guardian Launching, roles: ${selfMember.roles.mkString(",")}")

    // Create an actor that handles cluster domain events
    ctx.spawn(ClusterListener(), "ClusterListener")
    
    // Create and start health checker
    val healthChecker = ctx.spawn(HealthChecker(), "HealthChecker")
    healthChecker ! HealthChecker.StartPeriodicCheck(30000) // 每30秒检查一次
    
    // Create workflow supervisor (工作流管理器)
    implicit val ec = ctx.executionContext
    val executionEngine = new WorkflowExecutionEngine()(ctx.system, ec)
    val workflowSupervisor = ctx.spawn(
      WorkflowSupervisor(executionEngine),
      "WorkflowSupervisor"
    )
    logger.info("WorkflowSupervisor created")
    
    // Create workflow scheduler and scheduler manager (调度管理器)
    val workflowScheduler = new WorkflowScheduler(workflowSupervisor)(ctx.system)
    val schedulerManager = new SchedulerManager(workflowScheduler)
    logger.info("SchedulerManager created")

    // Start periodic leadership check
    import org.apache.pekko.actor.typed.scaladsl.Behaviors.withTimers
    withTimers { timer =>
      timer.startTimerAtFixedRate(CheckLeadership, 5.seconds)
      
      // Initial check
      managePekkoGc(cluster)

      Behaviors.receiveMessage {
        case CheckLeadership =>
          managePekkoGc(cluster)
          Behaviors.same
          
        case GetNodeRoles(reply) =>
          reply ! selfMember.roles
          Behaviors.same
          
        case GetHealthChecker(reply) =>
          reply ! healthChecker
          Behaviors.same
        
        case GetWorkflowSupervisor(reply) =>
          reply ! workflowSupervisor
          Behaviors.same
        
        case GetSchedulerManager(reply) =>
          reply ! schedulerManager
          Behaviors.same
      }
    }
  }
  
  private def managePekkoGc(cluster: Cluster)(implicit ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command]): Unit = {
    // TODO: PekkoGc暂时注释，学习用
    /*
    val currentLeader = cluster.state.leader
    val isLeader = currentLeader.contains(cluster.selfMember.address)
    
    ctx.log.info(s"Leadership check - Current leader: $currentLeader, Self address: ${cluster.selfMember.address}, Is leader: $isLeader")
    
    if (isLeader) {
      // This node is the leader, start PekkoGc
      ctx.child("PekkoGcActor") match {
        case None =>
          ctx.log.info("This node is the leader, starting PekkoGc")
          ctx.spawn(Behaviors.supervise(PekkoGc()).onFailure[Exception](org.apache.pekko.actor.typed.SupervisorStrategy.restart), "PekkoGcActor")
        case Some(_) =>
          ctx.log.debug("PekkoGc already running on this leader node")
      }
    } else {
      // This node is not the leader, stop PekkoGc if it's running
      ctx.child("PekkoGcActor") match {
        case Some(ref) =>
          ctx.log.info("This node is no longer the leader, stopping PekkoGc")
          ctx.stop(ref)
        case None =>
          ctx.log.debug("PekkoGc not running on this follower node")
      }
    }
    */
  }
}
