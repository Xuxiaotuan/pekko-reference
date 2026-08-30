package cn.xuyinyin.magic.workflow.scheduler

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.actors.WorkflowSupervisor
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}

import scala.concurrent.duration._

/**
 * 工作流调度器伴生对象
 */
object WorkflowScheduler {
  /**
   * 调度配置
   */
  case class ScheduleConfig(
    workflowId: String,
    scheduleType: ScheduleType,
    enabled: Boolean = true
  )
  
  /**
   * 调度类型
   */
  sealed trait ScheduleType
  case class FixedDelay(delay: FiniteDuration) extends ScheduleType      // 固定延迟
  case class FixedRate(interval: FiniteDuration) extends ScheduleType    // 固定频率
  case class CronSchedule(expression: String) extends ScheduleType       // Cron表达式
  case object Immediate extends ScheduleType                             // 立即执行
  
  /**
   * 调度器命令
   */
  sealed trait SchedulerCommand
  case object ExecuteWorkflow extends SchedulerCommand
  case object StopScheduler extends SchedulerCommand
  case object PauseScheduler extends SchedulerCommand
  case object ResumeScheduler extends SchedulerCommand
}

/**
 * 工作流调度器
 * 
 * 支持定时执行工作流
 * 通过WorkflowSupervisor来执行工作流，保持Actor模型的一致性
 * 
 * 功能：
 * - Cron表达式调度
 * - 固定延迟调度
 * - 固定频率调度
 * - 立即执行
 * 
 * @param workflowSupervisor 工作流监督器的引用
 * @author : Xuxiaotuan
 * @since : 2024-11-15
 */
@deprecated("Use the persistent SchedulerCoordinator Cluster Singleton", "0.2")
class WorkflowScheduler(
  workflowSupervisor: ActorRef[WorkflowSupervisor.Command]
)(implicit system: ActorSystem[_]) {
  import WorkflowScheduler._

  /** Legacy entry point intentionally fails before any actor or timer is created. */
  def scheduleWorkflow(
    workflow: WorkflowDSL.Workflow,
    config: ScheduleConfig
  ): ActorRef[SchedulerCommand] =
    throw new UnsupportedOperationException("WorkflowScheduler is retired; use SchedulerCoordinator")
}
