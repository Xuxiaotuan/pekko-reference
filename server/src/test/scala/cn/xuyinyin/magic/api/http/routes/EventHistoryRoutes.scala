package cn.xuyinyin.magic.api.http.routes

import cn.xuyinyin.magic.workflow.actors.{EventSourcedWorkflowActor, WorkflowSupervisor}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.pattern.AskTimeoutException
import org.apache.pekko.util.Timeout
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/** Read-only HTTP projection over typed workflow entity queries. */
class EventHistoryRoutes(workflowSupervisor: ActorRef[WorkflowSupervisor.Command])(implicit system: ActorSystem[_], ec: ExecutionContext) {
  import EventSourcedWorkflowActor._

  private implicit val timeout: Timeout = 5.seconds
  implicit val nodeExecutionDetailFormat: RootJsonFormat[NodeExecutionDetail] = jsonFormat8(NodeExecutionDetail.apply)
  implicit val executionDetailFormat: RootJsonFormat[ExecutionDetail] = jsonFormat7(ExecutionDetail.apply)
  implicit val executionHistoryResponseFormat: RootJsonFormat[ExecutionHistoryResponse] = jsonFormat2(ExecutionHistoryResponse.apply)
  implicit val executionInfoFormat: RootJsonFormat[ExecutionInfo] = jsonFormat6(ExecutionInfo.apply)
  implicit val executionSummaryFormat: RootJsonFormat[ExecutionSummary] = jsonFormat5(ExecutionSummary.apply)
  implicit val statusResponseFormat: RootJsonFormat[StatusResponse] = jsonFormat4(StatusResponse.apply)

  val routes: Route = pathPrefix("api" / "history") {
    concat(
      path(Segment / "status") { workflowId => get { complete(status(workflowId)) } },
      path(Segment) { workflowId => get { complete(history(workflowId)) } },
      path(Segment / "execution" / Segment) { (workflowId, executionId) =>
        get { complete(detail(workflowId, executionId)) }
      }
    )
  }

  private def status(workflowId: String): Future[(StatusCode, JsValue)] =
    workflowSupervisor.ask[StatusResponse](replyTo => WorkflowSupervisor.GetWorkflowStatus(workflowId, replyTo))
      .map(response => if (response.state == "uninitialized") StatusCodes.NotFound -> JsObject("workflowId" -> JsString(workflowId), "error" -> JsString("workflow not found")) else StatusCodes.OK -> response.toJson)
      .recover(systemFailure(workflowId))

  private def history(workflowId: String): Future[(StatusCode, JsValue)] =
    workflowSupervisor.ask[EventSourcedWorkflowActor.WorkflowSummary](replyTo => WorkflowSupervisor.GetWorkflowSummary(workflowId, replyTo)).flatMap { summary =>
      if (summary.revision == 0L) Future.successful(StatusCodes.NotFound -> JsObject("workflowId" -> JsString(workflowId), "error" -> JsString("workflow not found")))
      else workflowSupervisor.ask[ExecutionHistoryResponse](replyTo => WorkflowSupervisor.GetExecutionHistory(workflowId, replyTo)).map(response => StatusCodes.OK -> response.toJson)
    }
      .recover(systemFailure(workflowId))

  private def detail(workflowId: String, executionId: String): Future[(StatusCode, JsValue)] =
    workflowSupervisor.ask[ExecutionHistoryResponse](replyTo => WorkflowSupervisor.GetPagedExecutionHistory(workflowId, 0, 100, replyTo))
      .map { response =>
        response.executions.find(_.executionId == executionId)
          .map(value => StatusCodes.OK -> value.toJson)
          .getOrElse(StatusCodes.NotFound -> JsObject("error" -> JsString("execution not found")))
      }
      .recover(systemFailure(workflowId))

  private def systemFailure(workflowId: String): PartialFunction[Throwable, (StatusCode, JsValue)] = {
    case _: AskTimeoutException | _: java.util.concurrent.TimeoutException => StatusCodes.GatewayTimeout -> JsObject("workflowId" -> JsString(workflowId), "error" -> JsString("request timed out"))
    case _: java.util.concurrent.RejectedExecutionException => StatusCodes.ServiceUnavailable -> JsObject("workflowId" -> JsString(workflowId), "error" -> JsString("service unavailable"))
    case failure if persistenceFailure(failure) => StatusCodes.ServiceUnavailable -> JsObject("workflowId" -> JsString(workflowId), "error" -> JsString("persistence dependency unavailable"))
    case failure => StatusCodes.InternalServerError -> JsObject("workflowId" -> JsString(workflowId), "error" -> JsString(Option(failure.getMessage).getOrElse(failure.getClass.getSimpleName)))
  }
  private def persistenceFailure(failure: Throwable): Boolean = Iterator.iterate(Option(failure))(_.flatMap(value => Option(value.getCause))).takeWhile(_.nonEmpty).flatten.exists(value => value.isInstanceOf[java.sql.SQLException] || value.getClass.getName.toLowerCase.contains("persistence") || value.getClass.getName.toLowerCase.contains("jdbc"))
}
