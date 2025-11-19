package cn.xuyinyin.magic.api.http.routes

import cn.xuyinyin.magic.workflow.actors.{EventSourcedWorkflowActor, WorkflowSupervisor}
import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor._
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * 执行历史查询 HTTP API
 * 
 * 提供：
 * - 查询工作流执行历史
 * - 查询节点执行详情
 * - 查询执行时间线
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-16
 */
class EventHistoryRoutes(
  workflowSupervisor: ActorRef[_]
)(implicit system: ActorSystem[_], ec: ExecutionContext) {
  
  implicit val timeout: Timeout = 5.seconds
  
  // JSON 格式化
  implicit val nodeExecutionDetailFormat: RootJsonFormat[NodeExecutionDetail] = jsonFormat8(NodeExecutionDetail.apply)
  implicit val executionDetailFormat: RootJsonFormat[ExecutionDetail] = jsonFormat7(ExecutionDetail.apply)
  implicit val executionHistoryResponseFormat: RootJsonFormat[ExecutionHistoryResponse] = jsonFormat2(ExecutionHistoryResponse.apply)
  implicit val executionInfoFormat: RootJsonFormat[ExecutionInfo] = jsonFormat6(ExecutionInfo.apply)
  implicit val executionSummaryFormat: RootJsonFormat[ExecutionSummary] = jsonFormat5(ExecutionSummary.apply)
  implicit val statusResponseFormat: RootJsonFormat[StatusResponse] = jsonFormat4(StatusResponse.apply)
  
  val routes: Route =
    pathPrefix("api" / "history") {
      concat(
        // GET /api/history/:workflowId - 获取工作流执行历史
        path(Segment) { workflowId =>
          get {
            val futureResponse = queryExecutionHistory(workflowId)
            onSuccess(futureResponse) { response =>
              complete(StatusCodes.OK, response.toJson)
            }
          }
        },
        
        // GET /api/history/:workflowId/status - 获取工作流状态
        path(Segment / "status") { workflowId =>
          get {
            val futureResponse = queryWorkflowStatus(workflowId)
            onSuccess(futureResponse) { response =>
              complete(StatusCodes.OK, response.toJson)
            }
          }
        },
        
        // GET /api/history/:workflowId/execution/:executionId - 获取特定执行详情
        path(Segment / "execution" / Segment) { (workflowId, executionId) =>
          get {
            val futureResponse = queryExecutionDetail(workflowId, executionId)
            onSuccess(futureResponse) { response =>
              response match {
                case Some(detail) => complete(StatusCodes.OK, detail.toJson)
                case None => complete(StatusCodes.NotFound, s"Execution $executionId not found")
              }
            }
          }
        },
        
        // GET /api/history/:workflowId/timeline - 获取执行时间线
        path(Segment / "timeline") { workflowId =>
          get {
            parameters("executionId".optional) { executionIdOpt =>
              val futureResponse = queryExecutionTimeline(workflowId, executionIdOpt)
              onSuccess(futureResponse) { timeline =>
                complete(StatusCodes.OK, timeline.toJson)
              }
            }
          }
        }
      )
    }
  
  /**
   * 查询工作流执行历史
   */
  private def queryExecutionHistory(workflowId: String): Future[ExecutionHistoryResponse] = {
    // 通过 WorkflowSupervisor 查询真实的执行历史
    workflowSupervisor
      .asInstanceOf[ActorRef[WorkflowSupervisor.Command]]
      .ask[ExecutionHistoryResponse](ref => 
        WorkflowSupervisor.GetExecutionHistory(workflowId, ref)
      )(timeout, system.scheduler)
      .recover {
        case ex: Exception =>
          system.log.error(s"Failed to query execution history for $workflowId", ex)
          // 出错时返回空历史
          ExecutionHistoryResponse(workflowId, List.empty)
      }
  }
  
  /**
   * 查询工作流状态
   */
  private def queryWorkflowStatus(workflowId: String): Future[StatusResponse] = {
    // TODO: 实现通过 WorkflowSupervisor 查询
    Future.successful(StatusResponse(
      workflowId = workflowId,
      state = "idle",
      currentExecution = None,
      allExecutions = List.empty
    ))
  }
  
  /**
   * 查询特定执行详情
   */
  private def queryExecutionDetail(workflowId: String, executionId: String): Future[Option[ExecutionDetail]] = {
    queryExecutionHistory(workflowId).map { history =>
      history.executions.find(_.executionId == executionId)
    }
  }
  
  /**
   * 查询执行时间线
   */
  private def queryExecutionTimeline(workflowId: String, executionIdOpt: Option[String]): Future[JsValue] = {
    queryExecutionHistory(workflowId).map { history =>
      val execution = executionIdOpt match {
        case Some(execId) => history.executions.find(_.executionId == execId)
        case None => history.executions.headOption
      }
      
      execution match {
        case Some(exec) =>
          JsObject(
            "executionId" -> JsString(exec.executionId),
            "startTime" -> JsNumber(exec.startTime),
            "endTime" -> exec.endTime.map(JsNumber(_)).getOrElse(JsNull),
            "duration" -> exec.duration.map(JsNumber(_)).getOrElse(JsNull),
            "nodes" -> JsArray(exec.nodes.map { node =>
              JsObject(
                "nodeId" -> JsString(node.nodeId),
                "nodeType" -> JsString(node.nodeType),
                "startTime" -> JsNumber(node.startTime),
                "endTime" -> node.endTime.map(JsNumber(_)).getOrElse(JsNull),
                "duration" -> node.duration.map(JsNumber(_)).getOrElse(JsNull),
                "status" -> JsString(node.status),
                "recordsProcessed" -> JsNumber(node.recordsProcessed),
                "error" -> node.error.map(JsString(_)).getOrElse(JsNull)
              )
            }.toVector)
          )
        case None =>
          JsObject("error" -> JsString("Execution not found"))
      }
    }
  }
}
