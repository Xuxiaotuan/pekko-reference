package cn.xuyinyin.magic.api.http.routes

import cn.xuyinyin.magic.workflow.actors.{EventSourcedWorkflowActor, WorkflowSupervisor}
import cn.xuyinyin.magic.workflow.query.WorkflowQueryService
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.pattern.AskTimeoutException
import org.apache.pekko.util.Timeout
import spray.json._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

private[routes] object HttpErrorMapping {
  def status(failure: Throwable): StatusCode = failure match {
    case _: AskTimeoutException | _: java.util.concurrent.TimeoutException => StatusCodes.GatewayTimeout
    case _: java.util.concurrent.RejectedExecutionException => StatusCodes.ServiceUnavailable
    case value if causes(value)(candidate => candidate.isInstanceOf[java.sql.SQLException] || candidate.getClass.getName.toLowerCase.contains("persistence") || candidate.getClass.getName.toLowerCase.contains("jdbc")) => StatusCodes.ServiceUnavailable
    case _ => StatusCodes.InternalServerError
  }
  private def causes(failure: Throwable)(matches: Throwable => Boolean): Boolean = Iterator.iterate(Option(failure))(_.flatMap(value => Option(value.getCause))).takeWhile(_.nonEmpty).flatten.exists(matches)
}

/** HTTP boundary for the durable workflow entities. */
class EnhancedWorkflowRoutes(
  workflowSupervisor: ActorRef[WorkflowSupervisor.Command],
  queryService: Option[WorkflowQueryService] = None
)(implicit system: ActorSystem[_], ec: ExecutionContext) {
  import cn.xuyinyin.magic.workflow.model.WorkflowDSL._

  private implicit val timeout: Timeout = 5.seconds
  private val workflows = queryService.getOrElse(new WorkflowQueryService(system, workflowSupervisor))

  val routes: Route = pathPrefix("api" / "v1" / "workflows") {
    concat(
      pathEnd {
        concat(
          get {
            parameters("page".as[Int].withDefault(0), "pageSize".as[Int].withDefault(50)) { (page, pageSize) =>
              complete(workflows.list(page, pageSize).map(pageResponse => StatusCodes.OK -> pageResponse.toJson).recover(systemFailure("workflow", "list")))
            }
          },
          post {
            entity(as[Workflow]) { workflow =>
              val defined = workflowSupervisor.ask[EventSourcedWorkflowActor.Reply](replyTo =>
                WorkflowSupervisor.DefineWorkflow(workflow, expectedRevision = 0L, replyTo)
              )
              complete(defined.map(replyResponse(workflow.id, "define", _)).recover(systemFailure(workflow.id, "define")))
            }
          }
        )
      },
      path(Segment) { workflowId =>
        get {
          val summary = workflowSupervisor.ask[EventSourcedWorkflowActor.WorkflowSummary](replyTo =>
            WorkflowSupervisor.GetWorkflowSummary(workflowId, replyTo)
          )
          complete(summary.map(value => if (value.revision == 0L) StatusCodes.NotFound -> error(workflowId, "summary", "workflow not found") else StatusCodes.OK -> workflowSummaryJson(value)).recover(systemFailure(workflowId, "summary")))
        }
      },
      path(Segment / "status") { workflowId =>
        get {
          val status = workflowSupervisor.ask[EventSourcedWorkflowActor.StatusResponse](replyTo => WorkflowSupervisor.GetWorkflowStatus(workflowId, replyTo))
          complete(status.map(value => if (value.state == "uninitialized") StatusCodes.NotFound -> error(workflowId, "status", "workflow not found") else StatusCodes.OK -> JsObject("workflowId" -> JsString(value.workflowId), "state" -> JsString(value.state))).recover(systemFailure(workflowId, "status")))
        }
      },
      path(Segment / "execute") { workflowId =>
        post {
          parameters("requestId".withDefault(java.util.UUID.randomUUID().toString)) { requestId =>
            val result = workflowSupervisor.ask[EventSourcedWorkflowActor.Reply](replyTo =>
              WorkflowSupervisor.ExecuteManual(workflowId, requestId, replyTo)
            )
            complete(result.map(replyResponse(workflowId, "execute", _)).recover(systemFailure(workflowId, "execute")))
          }
        }
      }
    )
  }

  private def replyResponse(workflowId: String, operation: String, reply: EventSourcedWorkflowActor.Reply): (StatusCode, JsObject) = reply match {
    case EventSourcedWorkflowActor.Defined(id, revision) => StatusCodes.Created -> JsObject("workflowId" -> JsString(id), "revision" -> JsNumber(revision))
    case EventSourcedWorkflowActor.ExecutionAccepted(executionId) => StatusCodes.Accepted -> JsObject("workflowId" -> JsString(workflowId), "executionId" -> JsString(executionId))
    case EventSourcedWorkflowActor.DuplicateExecution(requestId, executionId) => StatusCodes.Conflict -> JsObject("workflowId" -> JsString(workflowId), "requestId" -> JsString(requestId), "executionId" -> JsString(executionId), "error" -> JsString("duplicate execution"))
    case EventSourcedWorkflowActor.AlreadyRunning(executionId) => StatusCodes.Conflict -> JsObject("workflowId" -> JsString(workflowId), "executionId" -> JsString(executionId), "error" -> JsString("workflow already running"))
    case EventSourcedWorkflowActor.RevisionConflict(id, expected, actual) => StatusCodes.Conflict -> JsObject("workflowId" -> JsString(id), "expectedRevision" -> JsNumber(expected), "actualRevision" -> JsNumber(actual), "error" -> JsString("revision conflict"))
    case EventSourcedWorkflowActor.NotInitialized(id) => StatusCodes.NotFound -> JsObject("workflowId" -> JsString(id), "error" -> JsString("workflow not found"))
    case EventSourcedWorkflowActor.DefinitionRejected(id, errors) => StatusCodes.BadRequest -> JsObject("workflowId" -> JsString(id), "errors" -> JsArray(errors.map(JsString(_))))
    case other => StatusCodes.InternalServerError -> JsObject("workflowId" -> JsString(workflowId), "operation" -> JsString(operation), "error" -> JsString(s"unexpected reply: ${other.getClass.getSimpleName}"))
  }

  private def systemFailure(workflowId: String, operation: String): PartialFunction[Throwable, (StatusCode, JsObject)] = {
    case failure => HttpErrorMapping.status(failure) -> error(workflowId, operation, Option(failure.getMessage).getOrElse(failure.getClass.getSimpleName))
  }

  private def error(workflowId: String, operation: String, message: String): JsObject = JsObject("workflowId" -> JsString(workflowId), "operation" -> JsString(operation), "error" -> JsString(message))

  private def workflowSummaryJson(summary: EventSourcedWorkflowActor.WorkflowSummary): JsObject = JsObject(
    "workflowId" -> JsString(summary.workflowId), "revision" -> JsNumber(summary.revision), "status" -> JsString(summary.status.value),
    "recentExecutions" -> JsArray(summary.recentExecutions.map(execution => JsObject("executionId" -> JsString(execution.executionId), "status" -> JsString(execution.status))))
  )
}
