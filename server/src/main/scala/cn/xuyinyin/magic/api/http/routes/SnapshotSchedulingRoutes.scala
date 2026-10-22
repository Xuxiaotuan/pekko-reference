package cn.xuyinyin.magic.api.http.routes

import cn.xuyinyin.magic.workflow.model.WorkflowDSL._
import cn.xuyinyin.magic.workflow.scheduling.TableSchedulingRuntime
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.{Directives,Route}
import spray.json._
import scala.concurrent.{ExecutionContext,Future}
import scala.util.control.NonFatal

/** Durable cold-task ingress and keyset metadata pages. Never asks a workflow entity. */
final class SnapshotSchedulingRoutes(runtime: Option[TableSchedulingRuntime])(implicit ec: ExecutionContext) extends Directives {
  private def error(e: Throwable): (org.apache.pekko.http.scaladsl.model.StatusCode,JsObject) = e match {
    case _: IllegalArgumentException => StatusCodes.BadRequest -> JsObject("error"->JsString("invalid managed snapshot definition or parameters"))
    case _: IllegalStateException => StatusCodes.Conflict -> JsObject("error"->JsString("request conflicts with a durable run; use its original request id"))
    case _ => HttpErrorMapping.status(e) -> JsObject("error"->JsString("snapshot scheduling unavailable; retry the same request id"))
  }
  val routes: Route = runtime match {
    case None => reject
    case Some(scheduling) => pathPrefix("api" / "v1" / "snapshot-tasks") {
      pathEndOrSingleSlash {
        concat(
          post {
            parameters("requestId", "revision".as[Long].withDefault(1L), "notBefore".as[Long].withDefault(0L)) { (request, revision, notBefore) =>
              entity(as[Workflow]) { workflow =>
                val accepted = try { require(notBefore >= 0L); scheduling.submit(workflow,request,revision,notBefore) }
                  catch { case NonFatal(e) => Future.failed(e) }
                complete(accepted.map(t => StatusCodes.Accepted -> JsObject("workflowId"->JsString(t.workflowId),
                  "executionId"->JsString(t.executionId.get),"phase"->JsString(t.state))).recover { case NonFatal(e) => error(e) })
              }
            }
          },
          get {
            parameters("after".?,"pageSize".as[Int].withDefault(50)) { (after,size) =>
              complete(scheduling.listAfter(after,size).map { rows => StatusCodes.OK -> JsObject(
                "items"->JsArray(rows.map(t=>JsObject("workflowId"->JsString(t.workflowId),"revision"->JsNumber(t.revision),
                  "phase"->JsString(t.state),"executionId"->t.executionId.map(JsString(_)).getOrElse(JsNull)))),
                "nextCursor"->rows.lastOption.map(t=>JsString(t.workflowId)).getOrElse(JsNull))
              }.recover { case NonFatal(e) => error(e) })
            }
          }
        )
      }
    }
  }
}
