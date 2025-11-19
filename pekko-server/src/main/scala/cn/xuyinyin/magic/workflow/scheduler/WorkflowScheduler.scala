package cn.xuyinyin.magic.workflow.scheduler

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.actors.WorkflowSupervisor
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import com.typesafe.scalalogging.Logger

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
class WorkflowScheduler(
  workflowSupervisor: ActorRef[WorkflowSupervisor.Command]
)(implicit system: ActorSystem[_]) {
  
  import WorkflowScheduler._
  
  private val logger = Logger(getClass)
  
  /**
   * 创建调度Actor
   */
  def scheduleWorkflow(
    workflow: WorkflowDSL.Workflow,
    config: ScheduleConfig
  ): ActorRef[SchedulerCommand] = {
    
    logger.info(s"创建工作流调度: ${workflow.id}")
    
    // 在现有 ActorSystem 中创建 Actor，而不是创建新的 ActorSystem
    system.systemActorOf(
      schedulerBehavior(workflow, config),
      s"workflow-scheduler-${workflow.id}"
    )
  }
  
  /**
   * 调度器Behavior
   */
  private def schedulerBehavior(
    workflow: WorkflowDSL.Workflow,
    config: ScheduleConfig
  ): Behavior[SchedulerCommand] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        
        // 根据调度类型设置定时器
        config.scheduleType match {
          case FixedDelay(delay) =>
            logger.info(s"固定延迟调度: $delay")
            timers.startTimerWithFixedDelay(ExecuteWorkflow, delay)
          
          case FixedRate(interval) =>
            logger.info(s"固定频率调度: $interval")
            timers.startTimerAtFixedRate(ExecuteWorkflow, interval)
          
          case CronSchedule(expression) =>
            logger.info(s"Cron调度: $expression")
            // TODO: 实现Cron解析
            // 可以使用 cron4s 库
            timers.startTimerAtFixedRate(ExecuteWorkflow, 1.hour)
          
          case Immediate =>
            logger.info("立即执行")
            context.self ! ExecuteWorkflow
        }
        
        // 处理消息
        Behaviors.receiveMessage {
          case ExecuteWorkflow =>
            if (config.enabled) {
              logger.info(s"执行工作流: ${workflow.id}")
              executeWorkflow(workflow)
            } else {
              logger.info(s"工作流调度已禁用: ${workflow.id}")
            }
            Behaviors.same
          
          case StopScheduler =>
            logger.info(s"停止调度: ${workflow.id}")
            Behaviors.stopped
          
          case PauseScheduler =>
            logger.info(s"暂停调度: ${workflow.id}")
            schedulerBehavior(workflow, config.copy(enabled = false))
          
          case ResumeScheduler =>
            logger.info(s"恢复调度: ${workflow.id}")
            schedulerBehavior(workflow, config.copy(enabled = true))
        }
      }
    }
  }
  
  /**
   * 执行工作流
   * 通过WorkflowSupervisor发送执行消息，保持Actor模型一致性
   */
  private def executeWorkflow(workflow: WorkflowDSL.Workflow): Unit = {
    logger.info(s"调度触发工作流执行: ${workflow.id}")
    
    // 先创建工作流 Actor（如果不存在），然后执行
    // 使用完整的 workflow 对象，确保可以创建新的 Actor
    workflowSupervisor ! WorkflowSupervisor.CreateAndExecuteWorkflow(workflow)
  }
}
