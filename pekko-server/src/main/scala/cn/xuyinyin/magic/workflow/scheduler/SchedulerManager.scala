package cn.xuyinyin.magic.workflow.scheduler

import cn.xuyinyin.magic.workflow.model.WorkflowDSL.Workflow
import cn.xuyinyin.magic.workflow.scheduler.WorkflowScheduler._
import scala.concurrent.duration._

/**
 * 调度管理器
 * 
 * 管理所有工作流的调度
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-15
 */
@deprecated("Use the persistent SchedulerCoordinator Cluster Singleton", "0.2")
class SchedulerManager(scheduler: WorkflowScheduler) {
  private def retired(): Nothing =
    throw new UnsupportedOperationException("SchedulerManager is retired; use SchedulerCoordinator")

  /**
   * 添加调度
   */
  def addSchedule(workflow: Workflow, config: ScheduleConfig): Unit = retired()
  
  /**
   * 停止调度
   */
  def stopSchedule(workflowId: String): Unit = retired()
  
  /**
   * 暂停调度
   */
  def pauseSchedule(workflowId: String): Unit = retired()
  
  /**
   * 恢复调度
   */
  def resumeSchedule(workflowId: String): Unit = retired()
  
  /**
   * 获取所有调度
   */
  def listSchedules(): List[String] = retired()
  
  /**
   * 关闭所有调度
   */
  def shutdownAll(): Unit = retired()
}

/**
 * 调度管理器工厂
 */
object SchedulerManager {
  
  /**
   * 创建简单的每日调度
   */
  def dailySchedule(workflowId: String): ScheduleConfig = {
    ScheduleConfig(
      workflowId = workflowId,
      scheduleType = FixedRate(1.day)
    )
  }
  
  /**
   * 创建每小时调度
   */
  def hourlySchedule(workflowId: String): ScheduleConfig = {
    ScheduleConfig(
      workflowId = workflowId,
      scheduleType = FixedRate(1.hour)
    )
  }
  
  /**
   * 创建每分钟调度
   */
  def minutelySchedule(workflowId: String): ScheduleConfig = {
    ScheduleConfig(
      workflowId = workflowId,
      scheduleType = FixedRate(1.minute)
    )
  }
  
  /**
   * 创建Cron调度
   */
  def cronSchedule(workflowId: String, cronExpression: String): ScheduleConfig = {
    ScheduleConfig(
      workflowId = workflowId,
      scheduleType = CronSchedule(cronExpression)
    )
  }
}
