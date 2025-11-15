package cn.xuyinyin.magic.api.http.routes

import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngine
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import spray.json._
import spray.json.DefaultJsonProtocol._
import com.typesafe.scalalogging.Logger

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant
import java.util.UUID

/**
 * 增强的工作流HTTP路由
 * 
 * 提供完整的工作流管理功能：
 * - CRUD操作
 * - 工作流执行
 * - 执行历史
 * - 执行日志
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-15
 */
class EnhancedWorkflowRoutes(implicit system: ActorSystem[_], ec: ExecutionContext) {
  
  private val logger = Logger(getClass)
  
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
              
              if (workflows.contains(workflow.id)) {
                complete(StatusCodes.Conflict, JsObject(
                  "error" -> JsString("Workflow already exists"),
                  "workflowId" -> JsString(workflow.id)
                ))
              } else {
                workflows.put(workflow.id, workflow)
                logger.info(s"Workflow created: ${workflow.id}")
                complete(StatusCodes.Created, JsObject(
                  "message" -> JsString("Workflow created successfully"),
                  "workflowId" -> JsString(workflow.id)
                ))
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
      
      // 执行工作流
      path(Segment / "execute") { workflowId =>
        post {
          logger.info(s"POST /api/v1/workflows/$workflowId/execute")
          
          workflows.get(workflowId) match {
            case Some(workflow) =>
              // 创建执行记录
              val executionId = s"exec_${UUID.randomUUID().toString.take(8)}"
              val startTime = Instant.now().toString
              
              val logs = mutable.ListBuffer[String]()
              
              // 日志回调函数
              def onLog(message: String): Unit = {
                val timestamp = Instant.now().toString
                logs += s"[$timestamp] $message"
                logger.info(s"[$executionId] $message")
              }
              
              onLog(s"开始执行工作流: ${workflow.name}")
              onLog(s"工作流ID: ${workflow.id}")
              onLog(s"节点数量: ${workflow.nodes.length}")
              onLog(s"连线数量: ${workflow.edges.length}")
              
              // 使用真实的Pekko Stream执行引擎
              Future {
                try {
                  // 同步等待执行结果（在Future中，不阻塞HTTP响应）
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
                "message" -> JsString("Workflow execution started"),
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
}
