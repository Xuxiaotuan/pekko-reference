package cn.xuyinyin.magic.api.http.routes

import cn.xuyinyin.magic.workflow.scheduler.ScheduleCalculator.{CronSchedule, FixedRate}
import cn.xuyinyin.magic.workflow.scheduler.{ScheduleCalculator, SchedulerCoordinator}
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

/** HTTP boundary for the persistent SchedulerCoordinator singleton. */
class SchedulerRoutes(coordinator: ActorRef[SchedulerCoordinator.Command])(implicit system: ActorSystem[_], ec: ExecutionContext) {
  private implicit val timeout: Timeout = 5.seconds

  final case class AddScheduleRequest(id: String, workflowId: String, scheduleType: String, interval: Option[String], cronExpression: Option[String])
  implicit val addScheduleRequestFormat: RootJsonFormat[AddScheduleRequest] = jsonFormat5(AddScheduleRequest.apply)

  val routes: Route = pathPrefix("api" / "v1" / "schedules") {
    concat(
      pathEnd {
        concat(
          get {
            val listed = coordinator.ask[SchedulerCoordinator.Schedules](SchedulerCoordinator.ListSchedules.apply)
            complete(listed.map(schedules => StatusCodes.OK -> JsObject("schedules" -> JsArray(schedules.values.map(scheduleJson)), "count" -> JsNumber(schedules.values.size))).recover(systemFailure("list")))
          },
          post { entity(as[AddScheduleRequest]) { request => complete(add(request)) } }
        )
      },
      path("add") { post { entity(as[AddScheduleRequest]) { request => complete(add(request)) } } },
      path(Segment) { id => put { entity(as[AddScheduleRequest]) { request => complete(update(request.copy(id = id))) } } },
      path(Segment / "pause") { id => post { complete(change(id, SchedulerCoordinator.Pause.apply)) } },
      path(Segment / "resume") { id => post { complete(change(id, SchedulerCoordinator.Resume.apply)) } },
      path(Segment) { id => delete { complete(change(id, SchedulerCoordinator.Remove.apply)) } }
    )
  }

  private def add(request: AddScheduleRequest): Future[(StatusCode, JsObject)] = definition(request).fold(
    error => Future.successful(StatusCodes.BadRequest -> JsObject("error" -> JsString(error))),
    value => coordinator.ask[SchedulerCoordinator.ScheduleReply](replyTo => SchedulerCoordinator.Add(SchedulerCoordinator.Schedule(request.id, request.workflowId, value), replyTo)).map(replyResponse).recover(systemFailure("add"))
  )

  private def change(id: String, command: (String, ActorRef[SchedulerCoordinator.ScheduleReply]) => SchedulerCoordinator.Command): Future[(StatusCode, JsObject)] =
    coordinator.ask[SchedulerCoordinator.ScheduleReply](replyTo => command(id, replyTo)).map(replyResponse).recover(systemFailure(id))

  private def update(request: AddScheduleRequest): Future[(StatusCode, JsObject)] = definition(request).fold(
    error => Future.successful(StatusCodes.BadRequest -> JsObject("error" -> JsString(error))),
    value => coordinator.ask[SchedulerCoordinator.ScheduleReply](replyTo => SchedulerCoordinator.Update(SchedulerCoordinator.Schedule(request.id, request.workflowId, value), replyTo)).map(replyResponse).recover(systemFailure(request.id))
  )

  private def definition(request: AddScheduleRequest): Either[String, ScheduleCalculator.Definition] = request.scheduleType match {
    case "fixed_rate" => parseDuration(request.interval).map(FixedRate.apply)
    case "cron" => Right(CronSchedule(request.cronExpression.getOrElse("")))
    case other => Left(s"unsupported schedule type: $other")
  }

  private def parseDuration(value: Option[String]): Either[String, FiniteDuration] = value match {
    case Some(raw) => scala.util.Try(Duration(raw)).toOption.collect { case duration: FiniteDuration if duration > Duration.Zero => duration }.toRight("interval must be a positive duration")
    case None => Left("interval is required for fixed_rate")
  }

  private def replyResponse(reply: SchedulerCoordinator.ScheduleReply): (StatusCode, JsObject) = reply match {
    case SchedulerCoordinator.ScheduleAdded(id) => StatusCodes.Created -> JsObject("scheduleId" -> JsString(id))
    case SchedulerCoordinator.ScheduleUpdated(id) => StatusCodes.OK -> JsObject("scheduleId" -> JsString(id))
    case SchedulerCoordinator.ScheduleRemoved(id) => StatusCodes.OK -> JsObject("scheduleId" -> JsString(id))
    case SchedulerCoordinator.ScheduleRejected(reason) if reason.contains("not found") => StatusCodes.NotFound -> JsObject("error" -> JsString(reason))
    case SchedulerCoordinator.ScheduleRejected(reason) if reason.contains("already exists") => StatusCodes.Conflict -> JsObject("error" -> JsObject("message" -> JsString(reason)))
    case SchedulerCoordinator.ScheduleRejected(reason) => StatusCodes.BadRequest -> JsObject("error" -> JsString(reason))
  }

  private def systemFailure(operation: String): PartialFunction[Throwable, (StatusCode, JsObject)] = {
    case _: AskTimeoutException | _: java.util.concurrent.TimeoutException => StatusCodes.GatewayTimeout -> JsObject("operation" -> JsString(operation), "error" -> JsString("request timed out"))
    case _: java.util.concurrent.RejectedExecutionException => StatusCodes.ServiceUnavailable -> JsObject("operation" -> JsString(operation), "error" -> JsString("service unavailable"))
    case failure if persistenceFailure(failure) => StatusCodes.ServiceUnavailable -> JsObject("operation" -> JsString(operation), "error" -> JsString("persistence dependency unavailable"))
    case failure => StatusCodes.InternalServerError -> JsObject("operation" -> JsString(operation), "error" -> JsString(Option(failure.getMessage).getOrElse(failure.getClass.getSimpleName)))
  }

  private def scheduleJson(schedule: SchedulerCoordinator.Schedule): JsObject = JsObject("id" -> JsString(schedule.id), "workflowId" -> JsString(schedule.workflowId), "enabled" -> JsBoolean(schedule.enabled), "definition" -> JsString(schedule.definition.toString))
  private def persistenceFailure(failure: Throwable): Boolean = Iterator.iterate(Option(failure))(_.flatMap(value => Option(value.getCause))).takeWhile(_.nonEmpty).flatten.exists(value => value.isInstanceOf[java.sql.SQLException] || value.getClass.getName.toLowerCase.contains("persistence") || value.getClass.getName.toLowerCase.contains("jdbc"))
}
