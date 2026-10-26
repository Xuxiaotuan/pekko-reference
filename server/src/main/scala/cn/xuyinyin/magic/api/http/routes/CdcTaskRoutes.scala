package cn.xuyinyin.magic.api.http.routes

import cn.xuyinyin.magic.workflow.cdc._
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.{Directives, ExceptionHandler, Route}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import spray.json._

/** Metadata commands/queries only. The service owns persistence, bounded work and cancellation. */
final class CdcTaskRoutes(service: Option[CdcTaskService])(implicit ec: ExecutionContext) extends Directives {
  private def optional(value: Option[String]): JsValue = value.map(JsString(_)).getOrElse(JsNull)
  private def view(task: CdcTaskView): JsObject = JsObject(
    "taskId" -> JsString(task.taskId), "requestId" -> JsString(task.requestId), "captureId" -> JsString(task.captureId),
    "table" -> JsString(task.table), "generation" -> JsString(task.generation), "targetTable" -> JsString(task.targetTable),
    "definitionFingerprint" -> JsString(task.definitionFingerprint), "desiredState" -> JsString(task.desiredState),
    "controlRevision" -> JsNumber(task.controlRevision), "phase" -> JsString(task.phase), "journalRevision" -> JsNumber(task.journalRevision),
    "executionId" -> optional(task.executionId), "captureReadiness" -> JsString(task.captureReadiness),
    "initializationEvidence" -> optional(task.initializationEvidence), "runtimeHealth" -> JsString(task.runtimeHealth),
    "releaseStatus" -> JsString(task.releaseStatus), "failureCode" -> optional(task.failureCode),
    "observedAt" -> JsNumber(task.observedAt), "lastVerifiedAt" -> task.lastVerifiedAt.map(JsNumber(_)).getOrElse(JsNull),
    "cancelAllowed" -> JsBoolean(task.cancelAllowed), "cancellationEvidence" -> optional(task.cancellationEvidence))
  private def status(value: CdcPlatformStatus): JsObject = JsObject("enabled" -> JsBoolean(value.enabled),
    "ownerState" -> JsString(value.ownerState), "reason" -> optional(value.reason),
    "activeSteps" -> JsNumber(value.activeSteps), "maxActiveSteps" -> JsNumber(value.maxActiveSteps))
  private def capture(value: CdcCaptureView): JsObject = JsObject("captureId" -> JsString(value.captureId),
    "database" -> JsString(value.database), "readiness" -> JsString(value.readiness),
    "runtimeHealth" -> JsString(value.runtimeHealth), "reason" -> optional(value.reason))

  private val invalid = Set("INVALID_DEFINITION", "INVALID_PAGE", "INVALID_TASK_ID", "CAPTURE_NOT_CONFIGURED")
  private val conflicts = Set("CONFLICT", "CANCEL_NOT_PERMITTED")
  private val unavailable = Set("OWNER_RELEASE_UNPROVEN", "CDC_PLATFORM_STOPPING", "CDC_PLATFORM_BUSY", "RELEASE_UNKNOWN", "STORAGE_UNAVAILABLE", "CDC_PLATFORM_DISABLED")
  private def failure(error: Throwable): (StatusCode, JsValue) = {
    val code = error match {
      case e: CdcPlatformException if invalid(e.code) || conflicts(e.code) || unavailable(e.code) || e.code == "NOT_FOUND" => e.code
      case _ => "STORAGE_UNAVAILABLE"
    }
    val http = if (invalid(code)) StatusCodes.BadRequest else if (conflicts(code)) StatusCodes.Conflict
      else if (code == "NOT_FOUND") StatusCodes.NotFound else StatusCodes.ServiceUnavailable
    val message = if (invalid(code)) "CDC 请求参数或捕获域无效" else if (conflicts(code)) "请求与现有任务或操作许可冲突"
      else if (code == "NOT_FOUND") "CDC 任务不存在" else "CDC 平台暂不可用；受理结果不明时请保留并重试原 requestId"
    http -> JsObject("error" -> JsObject("code" -> JsString(code), "message" -> JsString(message)))
  }
  private def withService(f: CdcTaskService => Route): Route = service match {
    case Some(value) if value.status.enabled => f(value)
    case _ => complete(failure(new CdcPlatformException("CDC_PLATFORM_DISABLED")))
  }
  private def completeResult(call: => Future[(StatusCode, JsValue)]): Route = {
    val result = try call catch { case NonFatal(e) => Future.failed(e) }
    complete(result.recover { case NonFatal(e) => failure(e) })
  }
  private def identifier(value: String): Unit =
    if (!value.matches("[A-Za-z0-9_-]{1,128}")) throw new CdcPlatformException("INVALID_TASK_ID")
  private def definition(value: JsValue): CdcTaskRequest = {
    val fields = value match { case o: JsObject => o.fields; case _ => throw new CdcPlatformException("INVALID_DEFINITION") }
    val names = Set("requestId", "captureId", "table", "generation", "targetTable")
    if (fields.keySet != names || fields.values.exists { case JsString(_) => false; case _ => true })
      throw new CdcPlatformException("INVALID_DEFINITION")
    def string(name: String) = fields(name).asInstanceOf[JsString].value
    val request = CdcTaskRequest(string("requestId"), string("captureId"), string("table"), string("generation"), string("targetTable"))
    request.validate(); request
  }

  val routes: Route = handleExceptions(ExceptionHandler { case NonFatal(error) => complete(failure(error)) }) {
    pathPrefix("api" / "v1") {
      concat(
        path("cdc-status") { get { withService(s => complete(StatusCodes.OK -> status(s.status))) } },
        path("cdc-captures") { get { withService(s => completeResult(s.captures().map(rows => StatusCodes.OK -> JsObject("items" -> JsArray(rows.map(capture)))))) } },
        pathPrefix("cdc-tasks") {
          withService { runtime =>
            concat(
              pathEndOrSingleSlash {
                concat(
                  post { entity(as[JsValue]) { body => completeResult {
                    runtime.submit(definition(body)).map(accepted => StatusCodes.Accepted -> JsObject("task" -> view(accepted.task), "created" -> JsBoolean(accepted.created)))
                  } } },
                  get { parameters("after".?, "pageSize".as[Int].withDefault(50)) { (after, limit) => completeResult {
                    if (limit < 1 || limit > 100) throw new CdcPlatformException("INVALID_PAGE")
                    after.foreach(identifier)
                    runtime.listAfter(after, limit).map(page => StatusCodes.OK -> JsObject("items" -> JsArray(page.items.map(view)), "nextAfter" -> optional(page.nextAfter)))
                  } } }
                )
              },
              path(Segment / "cancel") { id => post { completeResult {
                identifier(id); runtime.cancel(id).map(task => StatusCodes.Accepted -> view(task))
              } } },
              path(Segment) { id => get { completeResult {
                identifier(id); runtime.get(id).map {
                  case Some(task) => StatusCodes.OK -> view(task)
                  case None => failure(new CdcPlatformException("NOT_FOUND"))
                }
              } } }
            )
          }
        }
      )
    }
  }
}
