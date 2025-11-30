package cn.xuyinyin.magic.api.http.routes

import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngine
import cn.xuyinyin.magic.workflow.actors.{EventSourcedWorkflowActor, WorkflowSupervisor, WorkflowActor}
import cn.xuyinyin.magic.workflow.scheduler.{SchedulerManager, WorkflowScheduler}
import cn.xuyinyin.magic.workflow.scheduler.WorkflowScheduler._
import cn.xuyinyin.magic.monitoring.PrometheusMetrics
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.util.Timeout
import spray.json._
import spray.json.DefaultJsonProtocol._
import com.typesafe.scalalogging.Logger

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import java.time.Instant
import java.util.UUID

/**
 * 错误处理辅助对象
 */
object ErrorHandler {
  private val logger = Logger(getClass)
  
  /**
   * 处理Future异常并返回适当的HTTP响应
   */
  def handleError(workflowId: String, operation: String): PartialFunction[Throwable, (StatusCode, JsObject)] = {
    case ex: org.apache.pekko.pattern.AskTimeoutException =>
      logger.error(s"Ask timeout during $operation for workflow $workflowId", ex)
      StatusCodes.RequestTimeout -> JsObject(
        "error" -> JsString(s"$operation request timeout"),
        "workflowId" -> JsString(workflowId),
        "message" -> JsString("The actor did not respond in time. Please try again.")
      )
    
    case ex: java.util.concurrent.TimeoutException =>
      logger.error(s"Timeout during $operation for workflow $workflowId", ex)
      StatusCodes.RequestTimeout -> JsObject(
        "error" -> JsString(s"$operation request timeout"),
        "workflowId" -> JsString(workflowId),
        "message" -> JsString("The operation took too long to complete. Please try again.")
      )
    
    case ex: NoSuchElementException =>
      logger.warn(s"Entity not found during $operation for workflow $workflowId", ex)
      StatusCodes.NotFound -> JsObject(
        "error" -> JsString("Workflow not found"),
        "workflowId" -> JsString(workflowId),
        "message" -> JsString("The requested workflow does not exist.")
      )
    
    case ex: IllegalArgumentException =>
      logger.warn(s"Invalid argument during $operation for workflow $workflowId", ex)
      StatusCodes.BadRequest -> JsObject(
        "error" -> JsString("Invalid request"),
        "workflowId" -> JsString(workflowId),
        "message" -> JsString(ex.getMessage)
      )
    
    case ex: IllegalStateException =>
      logger.warn(s"Invalid state during $operation for workflow $workflowId", ex)
      StatusCodes.Conflict -> JsObject(
        "error" -> JsString("Invalid workflow state"),
        "workflowId" -> JsString(workflowId),
        "message" -> JsString(ex.getMessage)
      )
    
    case ex: Exception =>
      logger.error(s"Unexpected error during $operation for workflow $workflowId", ex)
      StatusCodes.InternalServerError -> JsObject(
        "error" -> JsString(s"Failed to $operation"),
        "workflowId" -> JsString(workflowId),
        "message" -> JsString(ex.getMessage),
        "type" -> JsString(ex.getClass.getSimpleName)
      )
  }
}

/**
 * 增强的工作流HTTP路由
 * 
 * 提供完整的工作流管理功能：
 * - CRUD操作
 * - 工作流执行
 * - 执行历史
 * - 执行日志
 * 
 * @param workflowSupervisor 可选的WorkflowSupervisor，用于Event Sourcing
 * @author : Xuxiaotuan
 * @since : 2024-11-15
 */
class EnhancedWorkflowRoutes(
  workflowSupervisor: Option[ActorRef[_]] = None,
  schedulerManager: Option[cn.xuyinyin.magic.workflow.scheduler.SchedulerManager] = None
)(implicit system: ActorSystem[_], ec: ExecutionContext) {
  
  private val logger = Logger(getClass)
  
  // 配置合理的Ask超时（5秒）
  private implicit val askTimeout: Timeout = 5.seconds
  
  // 真实的Pekko Stream执行引擎
  private val executionEngine = new WorkflowExecutionEngine()
  
  // 内存存储（生产环境应该用数据库）
  private val workflows = mutable.Map.empty[String, Workflow]
  private val executions = mutable.Map.empty[String, ExecutionInfo]
  
  // 执行信息
  case class ExecutionInfo(
    executionId: String,
    workflowId: String,
    status: String,
    startTime: String,
    endTime: Option[String],
    logs: List[String],
    result: Option[JsValue]
  )
  
  /**
   * 工作流路由
   */
  val routes: Route = pathPrefix("api" / "v1" / "workflows") {
    concat(
      // 获取节点类型定义 - 必须放在最前面
      path("node-types") {
        get {
          logger.info("GET /api/v1/workflows/node-types")
          complete(StatusCodes.OK, getNodeTypeDefinitions())
        }
      },
      
      // 获取所有工作流 & 创建工作流
      pathEnd {
        concat(
          get {
            logger.info("GET /api/v1/workflows - List all workflows")
            complete(StatusCodes.OK, workflows.values.toList)
          },
          post {
            entity(as[Workflow]) { workflow =>
              logger.info(s"POST /api/v1/workflows - Create: ${workflow.id}")
              
              workflowSupervisor match {
                case Some(supervisor) =>
                  // 通过WorkflowSupervisor创建工作流（使用Sharding）
                  val createFuture: Future[WorkflowSupervisor.WorkflowCreated] = 
                    supervisor.asInstanceOf[ActorRef[WorkflowSupervisor.Command]]
                      .ask(ref => WorkflowSupervisor.CreateWorkflow(workflow, ref))(askTimeout, system.scheduler)
                  
                  complete {
                    createFuture.map { created =>
                      // 存储到本地Map（用于列表查询）
                      workflows.put(workflow.id, workflow)
                      logger.info(s"Workflow created via Sharding: ${workflow.id}")
                      
                      // 记录指标
                      PrometheusMetrics.recordWorkflowCreated("success")
                      
                      // 检查是否有调度配置
                      val scheduleInfo = workflow.metadata.schedule match {
                        case Some(scheduleConfig) if scheduleConfig.enabled =>
                          handleScheduleCreation(workflow, scheduleConfig)
                        case _ =>
                          logger.info(s"Workflow ${workflow.id} is an immediate task (no schedule)")
                          "Immediate task - execute manually"
                      }
                      
                      StatusCodes.Created -> JsObject(
                        "message" -> JsString("Workflow created successfully via Sharding"),
                        "workflowId" -> JsString(workflow.id),
                        "scheduleStatus" -> JsString(scheduleInfo)
                      )
                    }.recover {
                      case ex: Throwable =>
                        PrometheusMetrics.recordWorkflowCreated("failed")
                        ErrorHandler.handleError(workflow.id, "create workflow")(ex)
                    }
                  }
                  
                case None =>
                  // Fallback to local storage if supervisor not available
                  if (workflows.contains(workflow.id)) {
                    complete(StatusCodes.Conflict, JsObject(
                      "error" -> JsString("Workflow already exists"),
                      "workflowId" -> JsString(workflow.id)
                    ))
                  } else {
                    workflows.put(workflow.id, workflow)
                    logger.info(s"Workflow created (local): ${workflow.id}")
                    
                    val scheduleInfo = workflow.metadata.schedule match {
                      case Some(scheduleConfig) if scheduleConfig.enabled =>
                        handleScheduleCreation(workflow, scheduleConfig)
                      case _ =>
                        "Immediate task - execute manually"
                    }
                    
                    complete(StatusCodes.Created, JsObject(
                      "message" -> JsString("Workflow created successfully"),
                      "workflowId" -> JsString(workflow.id),
                      "scheduleStatus" -> JsString(scheduleInfo)
                    ))
                  }
              }
            }
          }
        )
      },
      
      // 工作流执行历史
      path(Segment / "executions") { workflowId =>
        get {
          logger.info(s"GET /api/v1/workflows/$workflowId/executions")
          
          val workflowExecutions = executions.values
            .filter(_.workflowId == workflowId)
            .toList
            .sortBy(_.startTime).reverse
          
          complete(StatusCodes.OK, JsArray(
            workflowExecutions.map(exec => JsObject(
              "executionId" -> JsString(exec.executionId),
              "status" -> JsString(exec.status),
              "startTime" -> JsString(exec.startTime),
              "endTime" -> exec.endTime.map(JsString(_)).getOrElse(JsNull),
              "logsCount" -> JsNumber(exec.logs.length)
            )).toVector
          ))
        }
      },
      
      // 获取执行日志
      path(Segment / "executions" / Segment / "logs") { (workflowId, executionId) =>
        get {
          logger.info(s"GET /api/v1/workflows/$workflowId/executions/$executionId/logs")
          
          executions.get(executionId) match {
            case Some(exec) if exec.workflowId == workflowId =>
              complete(StatusCodes.OK, JsObject(
                "executionId" -> JsString(executionId),
                "logs" -> JsArray(exec.logs.map(JsString(_)).toVector)
              ))
            case _ =>
              complete(StatusCodes.NotFound, JsObject(
                "error" -> JsString("Execution not found")
              ))
          }
        }
      },
      
      // 执行工作流（使用Sharding）
      path(Segment / "execute") { workflowId =>
        post {
          logger.info(s"POST /api/v1/workflows/$workflowId/execute")
          
          workflowSupervisor match {
            case Some(supervisor) =>
              // 通过WorkflowSupervisor执行工作流（使用Sharding）
              val executeFuture: Future[EventSourcedWorkflowActor.ExecutionResponse] = 
                supervisor.asInstanceOf[ActorRef[WorkflowSupervisor.Command]]
                  .ask(ref => WorkflowSupervisor.ExecuteWorkflow(workflowId, ref))(askTimeout, system.scheduler)
              
              complete {
                val startTime = System.nanoTime()
                executeFuture.map { response =>
                  val durationSeconds = (System.nanoTime() - startTime) / 1e9
                  logger.info(s"Workflow execution started via Sharding: $workflowId, executionId: ${response.executionId}")
                  
                  // 记录指标
                  PrometheusMetrics.recordWorkflowExecution(workflowId, response.status, durationSeconds)
                  
                  StatusCodes.OK -> JsObject(
                    "message" -> JsString("Workflow execution started via Sharding"),
                    "executionId" -> JsString(response.executionId),
                    "workflowId" -> JsString(workflowId),
                    "status" -> JsString(response.status),
                    "note" -> JsString(s"Check status at: GET /api/v1/workflows/$workflowId/status")
                  )
                }.recover(ErrorHandler.handleError(workflowId, "execute workflow"))
              }
              
            case None =>
              // Fallback to direct execution if supervisor not available
              workflows.get(workflowId) match {
                case Some(workflow) =>
                  val executionId = s"exec_${UUID.randomUUID().toString.take(8)}"
                  val startTime = Instant.now().toString
                  
                  val logs = mutable.ListBuffer[String]()
                  def onLog(message: String): Unit = {
                    val timestamp = Instant.now().toString
                    logs += s"[$timestamp] $message"
                    logger.info(s"[$executionId] $message")
                  }
                  
                  onLog(s"开始执行工作流: ${workflow.name}")
                  
                  Future {
                    try {
                      val result = scala.concurrent.Await.result(
                        executionEngine.execute(workflow, executionId, onLog),
                        scala.concurrent.duration.Duration.Inf
                      )
                      
                      val endTime = Instant.now().toString
                      onLog(s"工作流执行完成，状态: ${result.status}")
                      
                      val execution = ExecutionInfo(
                        executionId = executionId,
                        workflowId = workflowId,
                        status = result.status,
                        startTime = startTime,
                        endTime = Some(endTime),
                        logs = logs.toList,
                        result = Some(JsObject(
                          "success" -> JsBoolean(result.success),
                          "message" -> JsString(result.message),
                          "rowsProcessed" -> result.rowsProcessed.map(JsNumber(_)).getOrElse(JsNull)
                        ))
                      )
                      
                      executions.put(executionId, execution)
                      logger.info(s"Execution completed: $executionId")
                      
                    } catch {
                      case ex: Throwable =>
                        val endTime = Instant.now().toString
                        val errorMsg = s"执行异常: ${ex.getMessage}"
                        onLog(errorMsg)
                        logger.error(s"Execution failed: $executionId", ex)
                        
                        val execution = ExecutionInfo(
                          executionId = executionId,
                          workflowId = workflowId,
                          status = "failed",
                          startTime = startTime,
                          endTime = Some(endTime),
                          logs = logs.toList,
                          result = Some(JsObject(
                            "success" -> JsBoolean(false),
                            "message" -> JsString(errorMsg)
                          ))
                        )
                        
                        executions.put(executionId, execution)
                    }
                  }
                  
                  complete(StatusCodes.OK, JsObject(
                    "message" -> JsString("Workflow execution started (local)"),
                    "executionId" -> JsString(executionId),
                    "workflowId" -> JsString(workflowId),
                    "status" -> JsString("running"),
                    "startTime" -> JsString(startTime)
                  ))
                  
                case None =>
                  complete(StatusCodes.NotFound, JsObject(
                    "error" -> JsString("Workflow not found"),
                    "workflowId" -> JsString(workflowId)
                  ))
              }
          }
        }
      },
      
      // 获取工作流状态（使用Sharding）
      path(Segment / "status") { workflowId =>
        get {
          logger.info(s"GET /api/v1/workflows/$workflowId/status")
          
          workflowSupervisor match {
            case Some(supervisor) =>
              // 通过WorkflowSupervisor查询状态（使用Sharding）
              val statusFuture: Future[EventSourcedWorkflowActor.StatusResponse] = 
                supervisor.asInstanceOf[ActorRef[WorkflowSupervisor.Command]]
                  .ask(ref => WorkflowSupervisor.GetWorkflowStatus(workflowId, ref))(askTimeout, system.scheduler)
              
              complete {
                statusFuture.map { response =>
                  logger.info(s"Workflow status retrieved via Sharding: $workflowId, state: ${response.state}")
                  
                  StatusCodes.OK -> JsObject(
                    "workflowId" -> JsString(response.workflowId),
                    "state" -> JsString(response.state),
                    "executionCount" -> JsNumber(response.allExecutions.length),
                    "currentExecution" -> (response.currentExecution match {
                      case Some(exec) =>
                        JsObject(
                          "executionId" -> JsString(exec.executionId),
                          "status" -> JsString(exec.status),
                          "startTime" -> JsNumber(exec.startTime),
                          "endTime" -> exec.endTime.map(JsNumber(_)).getOrElse(JsNull)
                        )
                      case None => JsNull
                    }),
                    "lastExecution" -> (if (response.allExecutions.nonEmpty) {
                      val lastExec = response.allExecutions.last
                      JsObject(
                        "executionId" -> JsString(lastExec.executionId),
                        "status" -> JsString(lastExec.status),
                        "startTime" -> JsNumber(lastExec.startTime),
                        "endTime" -> lastExec.endTime.map(JsNumber(_)).getOrElse(JsNull)
                      )
                    } else JsNull)
                  )
                }.recover(ErrorHandler.handleError(workflowId, "get workflow status"))
              }
              
            case None =>
              // Fallback: return basic info from local storage
              workflows.get(workflowId) match {
                case Some(workflow) =>
                  complete(StatusCodes.OK, JsObject(
                    "workflowId" -> JsString(workflowId),
                    "status" -> JsString("unknown"),
                    "message" -> JsString("Status tracking not available (supervisor not configured)")
                  ))
                case None =>
                  complete(StatusCodes.NotFound, JsObject(
                    "error" -> JsString("Workflow not found"),
                    "workflowId" -> JsString(workflowId)
                  ))
              }
          }
        }
      },
      
      // 执行工作流（使用 Event Sourcing）- 保留用于向后兼容
      path(Segment / "execute-es") { workflowId =>
        post {
          logger.info(s"POST /api/v1/workflows/$workflowId/execute-es (Event Sourcing)")
          
          workflowSupervisor match {
            case Some(supervisor) =>
              workflows.get(workflowId) match {
                case Some(workflow) =>
                  implicit val askTimeout: Timeout = 10.seconds
                  
                  // 通过 WorkflowSupervisor 创建 EventSourced Actor 并执行
                  val createFuture: Future[WorkflowSupervisor.WorkflowCreated] = 
                    supervisor.asInstanceOf[ActorRef[WorkflowSupervisor.Command]]
                      .ask(ref => WorkflowSupervisor.CreateWorkflow(workflow, ref))(askTimeout, system.scheduler)
                  
                  val executeFuture: Future[EventSourcedWorkflowActor.ExecutionResponse] = createFuture.flatMap { created =>
                    logger.info(s"EventSourced workflow actor created: ${created.workflowId}")
                    
                    supervisor.asInstanceOf[ActorRef[WorkflowSupervisor.Command]]
                      .ask(ref => WorkflowSupervisor.ExecuteWorkflow(workflowId, ref))(askTimeout, system.scheduler)
                  }
                  
                  complete {
                    executeFuture.map { response =>
                      StatusCodes.OK -> JsObject(
                        "message" -> JsString("Workflow execution started with Event Sourcing"),
                        "executionId" -> JsString(response.executionId),
                        "workflowId" -> JsString(workflowId),
                        "status" -> JsString(response.status),
                        "note" -> JsString(s"Events will be persisted. Check status at: GET /api/v1/workflows/$workflowId/status")
                      )
                    }
                  }
                  
                case None =>
                  complete(StatusCodes.NotFound, JsObject(
                    "error" -> JsString("Workflow not found"),
                    "workflowId" -> JsString(workflowId)
                  ))
              }
              
            case None =>
              complete(StatusCodes.ServiceUnavailable, JsObject(
                "error" -> JsString("Event Sourcing not available"),
                "message" -> JsString("WorkflowSupervisor not configured")
              ))
          }
        }
      },
      
      // 获取单个工作流 & 更新 & 删除
      path(Segment) { workflowId =>
        concat(
          get {
            logger.info(s"GET /api/v1/workflows/$workflowId")
            
            workflows.get(workflowId) match {
              case Some(workflow) =>
                complete(StatusCodes.OK, workflow)
              case None =>
                complete(StatusCodes.NotFound, JsObject(
                  "error" -> JsString("Workflow not found"),
                  "workflowId" -> JsString(workflowId)
                ))
            }
          },
          put {
            entity(as[Workflow]) { workflow =>
              logger.info(s"PUT /api/v1/workflows/$workflowId")
              
              if (workflow.id != workflowId) {
                complete(StatusCodes.BadRequest, JsObject(
                  "error" -> JsString("Workflow ID mismatch")
                ))
              } else if (!workflows.contains(workflowId)) {
                complete(StatusCodes.NotFound, JsObject(
                  "error" -> JsString("Workflow not found"),
                  "workflowId" -> JsString(workflowId)
                ))
              } else {
                workflows.put(workflowId, workflow)
                logger.info(s"Workflow updated: $workflowId")
                complete(StatusCodes.OK, JsObject(
                  "message" -> JsString("Workflow updated successfully"),
                  "workflowId" -> JsString(workflowId)
                ))
              }
            }
          },
          delete {
            logger.info(s"DELETE /api/v1/workflows/$workflowId")
            
            workflows.remove(workflowId) match {
              case Some(_) =>
                // 同时删除相关执行记录
                executions.filterInPlace((_, exec) => exec.workflowId != workflowId)
                logger.info(s"Workflow deleted: $workflowId")
                complete(StatusCodes.OK, JsObject(
                  "message" -> JsString("Workflow deleted successfully"),
                  "workflowId" -> JsString(workflowId)
                ))
              case None =>
                complete(StatusCodes.NotFound, JsObject(
                  "error" -> JsString("Workflow not found"),
                  "workflowId" -> JsString(workflowId)
                ))
            }
          }
        )
      }
    )
  }
  
  /**
   * 获取节点类型定义
   */
  private def getNodeTypeDefinitions(): JsObject = {
    JsObject(
      "source" -> JsArray(
        JsObject(
          "type" -> JsString("file.csv"),
          "displayName" -> JsString("CSV文件"),
          "icon" -> JsString("📁"),
          "category" -> JsString("source"),
          "description" -> JsString("读取CSV文件"),
          "config" -> JsObject(
            "path" -> JsObject("type" -> JsString("string"), "required" -> JsBoolean(true), "label" -> JsString("文件路径")),
            "delimiter" -> JsObject("type" -> JsString("string"), "default" -> JsString(","), "label" -> JsString("分隔符"))
          )
        ),
        JsObject(
          "type" -> JsString("file.text"),
          "displayName" -> JsString("文本文件"),
          "icon" -> JsString("📄"),
          "category" -> JsString("source"),
          "description" -> JsString("按行读取文本文件"),
          "config" -> JsObject(
            "path" -> JsObject("type" -> JsString("string"), "required" -> JsBoolean(true), "label" -> JsString("文件路径"))
          )
        ),
        JsObject(
          "type" -> JsString("memory.collection"),
          "displayName" -> JsString("内存集合"),
          "icon" -> JsString("💾"),
          "category" -> JsString("source"),
          "description" -> JsString("从内存数据创建流"),
          "config" -> JsObject(
            "data" -> JsObject("type" -> JsString("array"), "required" -> JsBoolean(true), "label" -> JsString("数据"))
          )
        ),
        JsObject(
          "type" -> JsString("random.numbers"),
          "displayName" -> JsString("随机数"),
          "icon" -> JsString("🎲"),
          "category" -> JsString("source"),
          "description" -> JsString("生成随机数序列"),
          "config" -> JsObject(
            "count" -> JsObject("type" -> JsString("number"), "default" -> JsNumber(100), "label" -> JsString("数量")),
            "min" -> JsObject("type" -> JsString("number"), "default" -> JsNumber(1), "label" -> JsString("最小值")),
            "max" -> JsObject("type" -> JsString("number"), "default" -> JsNumber(100), "label" -> JsString("最大值"))
          )
        ),
        JsObject(
          "type" -> JsString("sequence.numbers"),
          "displayName" -> JsString("数字序列"),
          "icon" -> JsString("🔢"),
          "category" -> JsString("source"),
          "description" -> JsString("生成连续数字序列"),
          "config" -> JsObject(
            "start" -> JsObject("type" -> JsString("number"), "default" -> JsNumber(1), "label" -> JsString("起始值")),
            "end" -> JsObject("type" -> JsString("number"), "default" -> JsNumber(100), "label" -> JsString("结束值")),
            "step" -> JsObject("type" -> JsString("number"), "default" -> JsNumber(1), "label" -> JsString("步长"))
          )
        )
      ),
      "transform" -> JsArray(
        JsObject(
          "type" -> JsString("filter"),
          "displayName" -> JsString("过滤"),
          "icon" -> JsString("🔍"),
          "category" -> JsString("transform"),
          "description" -> JsString("根据条件过滤数据"),
          "config" -> JsObject(
            "condition" -> JsObject("type" -> JsString("string"), "required" -> JsBoolean(true), "label" -> JsString("过滤条件"))
          )
        ),
        JsObject(
          "type" -> JsString("map"),
          "displayName" -> JsString("映射"),
          "icon" -> JsString("🔄"),
          "category" -> JsString("transform"),
          "description" -> JsString("转换每个元素"),
          "config" -> JsObject(
            "expression" -> JsObject("type" -> JsString("string"), "required" -> JsBoolean(true), "label" -> JsString("转换表达式"))
          )
        ),
        JsObject(
          "type" -> JsString("distinct"),
          "displayName" -> JsString("去重"),
          "icon" -> JsString("✨"),
          "category" -> JsString("transform"),
          "description" -> JsString("移除重复元素"),
          "config" -> JsObject()
        ),
        JsObject(
          "type" -> JsString("batch"),
          "displayName" -> JsString("批处理"),
          "icon" -> JsString("📦"),
          "category" -> JsString("transform"),
          "description" -> JsString("分批处理数据"),
          "config" -> JsObject(
            "batchSize" -> JsObject("type" -> JsString("number"), "required" -> JsBoolean(true), "label" -> JsString("批次大小"))
          )
        )
      ),
      "sink" -> JsArray(
        JsObject(
          "type" -> JsString("file.text"),
          "displayName" -> JsString("文本文件输出"),
          "icon" -> JsString("💾"),
          "category" -> JsString("sink"),
          "description" -> JsString("写入文本文件"),
          "config" -> JsObject(
            "path" -> JsObject("type" -> JsString("string"), "required" -> JsBoolean(true), "label" -> JsString("输出路径"))
          )
        ),
        JsObject(
          "type" -> JsString("console.log"),
          "displayName" -> JsString("控制台输出"),
          "icon" -> JsString("🖥️"),
          "category" -> JsString("sink"),
          "description" -> JsString("打印到控制台"),
          "config" -> JsObject(
            "limit" -> JsObject("type" -> JsString("number"), "default" -> JsNumber(100), "label" -> JsString("最大行数"))
          )
        ),
        JsObject(
          "type" -> JsString("aggregate.count"),
          "displayName" -> JsString("计数"),
          "icon" -> JsString("🔢"),
          "category" -> JsString("sink"),
          "description" -> JsString("统计数据行数"),
          "config" -> JsObject()
        )
      )
    )
  }
  
  /**
   * 处理调度创建
   */
  private def handleScheduleCreation(workflow: Workflow, scheduleConfig: cn.xuyinyin.magic.workflow.model.WorkflowDSL.ScheduleConfig): String = {
    (schedulerManager, workflowSupervisor) match {
      case (Some(manager), Some(supervisor)) =>
        try {
          // 解析调度类型
          val scheduleType = scheduleConfig.scheduleType match {
            case "fixed_rate" =>
              val duration = parseDuration(scheduleConfig.interval.getOrElse("1h"))
              FixedRate(duration)
            
            case "cron" =>
              CronSchedule(scheduleConfig.cronExpression.getOrElse("0 0 * * *"))
            
            case "immediate" =>
              Immediate
            
            case other =>
              logger.warn(s"Unknown schedule type: $other, defaulting to immediate")
              Immediate
          }
          
          // 创建调度配置
          val config = WorkflowScheduler.ScheduleConfig(
            workflowId = workflow.id,
            scheduleType = scheduleType,
            enabled = scheduleConfig.enabled
          )
          
          // 添加到调度管理器
          manager.addSchedule(workflow, config)
          
          val scheduleDesc = scheduleConfig.scheduleType match {
            case "fixed_rate" => s"Fixed Rate: ${scheduleConfig.interval.getOrElse("?")}"
            case "cron" => s"Cron: ${scheduleConfig.cronExpression.getOrElse("?")}"
            case _ => "Immediate"
          }
          
          logger.info(s"Schedule created for workflow ${workflow.id}: $scheduleDesc")
          s"Scheduled: $scheduleDesc"
          
        } catch {
          case ex: Exception =>
            logger.error(s"Failed to create schedule for ${workflow.id}", ex)
            s"Schedule creation failed: ${ex.getMessage}"
        }
      
      case _ =>
        logger.warn(s"SchedulerManager or WorkflowSupervisor not available for workflow ${workflow.id}")
        "Schedule not available (scheduler not configured)"
    }
  }
  
  /**
   * 解析时间间隔字符串
   */
  private def parseDuration(interval: String): FiniteDuration = {
    interval.toLowerCase match {
      case s if s.endsWith("s") => s.dropRight(1).toLong.seconds
      case s if s.endsWith("m") => s.dropRight(1).toLong.minutes
      case s if s.endsWith("h") => s.dropRight(1).toLong.hours
      case s if s.endsWith("d") => s.dropRight(1).toLong.days
      case _ => 
        logger.warn(s"Invalid duration format: $interval, defaulting to 1 hour")
        1.hour
    }
  }
}
