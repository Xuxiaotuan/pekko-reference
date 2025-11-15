package cn.xuyinyin.magic.workflow.scheduler

import cn.xuyinyin.magic.workflow.model.WorkflowDSL.Workflow
import cn.xuyinyin.magic.workflow.scheduler.WorkflowScheduler._
import org.apache.pekko.actor.typed.ActorSystem
import com.typesafe.scalalogging.Logger

import scala.collection.mutable
import scala.concurrent.duration._

/**
 * 调度管理器
 * 
 * 管理所有工作流的调度
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-15
 */
class SchedulerManager(scheduler: WorkflowScheduler) {
  
  private val logger = Logger(getClass)
  private val scheduledWorkflows = mutable.Map[String, ActorSystem[SchedulerCommand]]()
  
  /**
   * 添加调度
   */
  def addSchedule(workflow: Workflow, config: ScheduleConfig): Unit = {
    logger.info(s"添加工作流调度: ${workflow.id}")
    
    // 如果已存在，先停止
    stopSchedule(workflow.id)
    
    // 创建新的调度
    val system = scheduler.scheduleWorkflow(workflow, config)
    scheduledWorkflows.put(workflow.id, system)
  }
  
  /**
   * 停止调度
   */
  def stopSchedule(workflowId: String): Unit = {
    scheduledWorkflows.get(workflowId).foreach { system =>
      logger.info(s"停止工作流调度: $workflowId")
      system ! StopScheduler
      scheduledWorkflows.remove(workflowId)
    }
  }
  
  /**
   * 暂停调度
   */
  def pauseSchedule(workflowId: String): Unit = {
    scheduledWorkflows.get(workflowId).foreach { system =>
      logger.info(s"暂停工作流调度: $workflowId")
      system ! PauseScheduler
    }
  }
  
  /**
   * 恢复调度
   */
  def resumeSchedule(workflowId: String): Unit = {
    scheduledWorkflows.get(workflowId).foreach { system =>
      logger.info(s"恢复工作流调度: $workflowId")
      system ! ResumeScheduler
    }
  }
  
  /**
   * 获取所有调度
   */
  def listSchedules(): List[String] = {
    scheduledWorkflows.keys.toList
  }
  
  /**
   * 关闭所有调度
   */
  def shutdownAll(): Unit = {
    logger.info("关闭所有工作流调度")
    scheduledWorkflows.foreach { case (id, system) =>
      system ! StopScheduler
    }
    scheduledWorkflows.clear()
  }
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
