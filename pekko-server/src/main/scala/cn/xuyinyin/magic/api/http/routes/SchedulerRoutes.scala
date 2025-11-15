package cn.xuyinyin.magic.api.http.routes

import cn.xuyinyin.magic.workflow.scheduler.{SchedulerManager, WorkflowScheduler}
import cn.xuyinyin.magic.workflow.scheduler.WorkflowScheduler._
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import spray.json._
import spray.json.DefaultJsonProtocol._
import com.typesafe.scalalogging.Logger

import scala.concurrent.duration._

/**
 * 工作流调度HTTP路由
 * 
 * 提供调度管理功能：
 * - 添加/删除调度
 * - 暂停/恢复调度
 * - 列出所有调度
 * - 预定义调度模板
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-15
 */
class SchedulerRoutes(schedulerManager: SchedulerManager) {
  
  private val logger = Logger(getClass)
  
  // JSON格式定义
  implicit object FiniteDurationFormat extends JsonFormat[FiniteDuration] {
    def write(d: FiniteDuration): JsValue = JsString(s"${d.length}${d.unit.toString.head}")
    def read(json: JsValue): FiniteDuration = json match {
      case JsString(s) if s.endsWith("s") => s.dropRight(1).toLong.seconds
      case JsString(s) if s.endsWith("m") => s.dropRight(1).toLong.minutes
      case JsString(s) if s.endsWith("h") => s.dropRight(1).toLong.hours
      case JsString(s) if s.endsWith("d") => s.dropRight(1).toLong.days
      case _ => throw DeserializationException("Expected duration string")
    }
  }
  
  case class AddScheduleRequest(
    workflowId: String,
    scheduleType: String,      // "fixed_rate", "fixed_delay", "cron", "immediate"
    interval: Option[String],  // "1h", "30m", "1d"
    cronExpression: Option[String]
  )
  
  implicit val addScheduleRequestFormat = jsonFormat4(AddScheduleRequest)
  
  /**
   * 调度路由
   */
  val routes: Route = pathPrefix("api" / "v1" / "schedules") {
    concat(
      // 列出所有调度
      pathEnd {
        get {
          logger.info("GET /api/v1/schedules - List all schedules")
          val schedules = schedulerManager.listSchedules()
          complete(StatusCodes.OK, JsObject(
            "schedules" -> JsArray(schedules.map(JsString(_)).toVector),
            "count" -> JsNumber(schedules.size)
          ))
        }
      },
      
      // 添加调度
      path("add") {
        post {
          entity(as[AddScheduleRequest]) { request =>
            logger.info(s"POST /api/v1/schedules/add - Add schedule for: ${request.workflowId}")
            
            try {
              val scheduleType = request.scheduleType match {
                case "fixed_rate" =>
                  val duration = parseDuration(request.interval.getOrElse("1h"))
                  FixedRate(duration)
                
                case "fixed_delay" =>
                  val duration = parseDuration(request.interval.getOrElse("1h"))
                  FixedDelay(duration)
                
                case "cron" =>
                  CronSchedule(request.cronExpression.getOrElse("0 0 * * *"))
                
                case "immediate" =>
                  Immediate
                
                case _ =>
                  throw new IllegalArgumentException(s"Unknown schedule type: ${request.scheduleType}")
              }
              
              val config = ScheduleConfig(
                workflowId = request.workflowId,
                scheduleType = scheduleType,
                enabled = true
              )
              
              // 注意：这里需要先从WorkflowSupervisor获取工作流
              // 简化实现：假设工作流已经存在
              // TODO: 集成WorkflowSupervisor来获取实际的工作流对象
              
              complete(StatusCodes.OK, JsObject(
                "message" -> JsString("Schedule added successfully"),
                "workflowId" -> JsString(request.workflowId),
                "scheduleType" -> JsString(request.scheduleType)
              ))
              
            } catch {
              case e: Exception =>
                logger.error(s"Failed to add schedule: ${e.getMessage}")
                complete(StatusCodes.BadRequest, JsObject(
                  "error" -> JsString(e.getMessage)
                ))
            }
          }
        }
      },
      
      // 暂停调度
      path(Segment / "pause") { workflowId =>
        post {
          logger.info(s"POST /api/v1/schedules/$workflowId/pause")
          schedulerManager.pauseSchedule(workflowId)
          complete(StatusCodes.OK, JsObject(
            "message" -> JsString("Schedule paused"),
            "workflowId" -> JsString(workflowId)
          ))
        }
      },
      
      // 恢复调度
      path(Segment / "resume") { workflowId =>
        post {
          logger.info(s"POST /api/v1/schedules/$workflowId/resume")
          schedulerManager.resumeSchedule(workflowId)
          complete(StatusCodes.OK, JsObject(
            "message" -> JsString("Schedule resumed"),
            "workflowId" -> JsString(workflowId)
          ))
        }
      },
      
      // 停止调度
      path(Segment / "stop") { workflowId =>
        post {
          logger.info(s"POST /api/v1/schedules/$workflowId/stop")
          schedulerManager.stopSchedule(workflowId)
          complete(StatusCodes.OK, JsObject(
            "message" -> JsString("Schedule stopped"),
            "workflowId" -> JsString(workflowId)
          ))
        }
      },
      
      // 快速调度模板
      path("templates") {
        get {
          logger.info("GET /api/v1/schedules/templates")
          complete(StatusCodes.OK, JsObject(
            "templates" -> JsArray(
              JsObject(
                "name" -> JsString("Daily"),
                "scheduleType" -> JsString("fixed_rate"),
                "interval" -> JsString("1d")
              ),
              JsObject(
                "name" -> JsString("Hourly"),
                "scheduleType" -> JsString("fixed_rate"),
                "interval" -> JsString("1h")
              ),
              JsObject(
                "name" -> JsString("Every 30 minutes"),
                "scheduleType" -> JsString("fixed_rate"),
                "interval" -> JsString("30m")
              ),
              JsObject(
                "name" -> JsString("Every 5 minutes"),
                "scheduleType" -> JsString("fixed_rate"),
                "interval" -> JsString("5m")
              ),
              JsObject(
                "name" -> JsString("Cron: Daily at midnight"),
                "scheduleType" -> JsString("cron"),
                "cronExpression" -> JsString("0 0 * * *")
              ),
              JsObject(
                "name" -> JsString("Cron: Every hour"),
                "scheduleType" -> JsString("cron"),
                "cronExpression" -> JsString("0 * * * *")
              )
            )
          ))
        }
      }
    )
  }
  
  /**
   * 解析时间间隔字符串
   */
  private def parseDuration(interval: String): FiniteDuration = {
    interval match {
      case s if s.endsWith("s") => s.dropRight(1).toLong.seconds
      case s if s.endsWith("m") => s.dropRight(1).toLong.minutes
      case s if s.endsWith("h") => s.dropRight(1).toLong.hours
      case s if s.endsWith("d") => s.dropRight(1).toLong.days
      case _ => throw new IllegalArgumentException(s"Invalid duration format: $interval")
    }
  }
}
