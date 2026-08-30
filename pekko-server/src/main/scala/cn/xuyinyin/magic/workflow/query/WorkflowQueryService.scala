package cn.xuyinyin.magic.workflow.query

import cn.xuyinyin.magic.workflow.actors.{EventSourcedWorkflowActor, WorkflowSupervisor}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.persistence.jdbc.query.scaladsl.JdbcReadJournal
import org.apache.pekko.persistence.query.PersistenceQuery
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.{Materializer, SystemMaterializer}
import org.apache.pekko.util.Timeout
import spray.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

object WorkflowQueryService {
  final case class WorkflowPage(items: Vector[EventSourcedWorkflowActor.WorkflowSummary], page: Int, pageSize: Int)
  implicit val workflowPageFormat: RootJsonWriter[WorkflowPage] = new RootJsonWriter[WorkflowPage] {
    override def write(value: WorkflowPage): JsObject = JsObject(
      "page" -> JsNumber(value.page),
      "pageSize" -> JsNumber(value.pageSize),
      "items" -> JsArray(value.items.map { summary =>
        JsObject("workflowId" -> JsString(summary.workflowId), "revision" -> JsNumber(summary.revision), "status" -> JsString(summary.status.value))
      })
    )
  }
}

/** Reads durable workflow ids, then asks their sharded entities for current summaries. */
class WorkflowQueryService(
  system: ActorSystem[_],
  supervisor: ActorRef[WorkflowSupervisor.Command],
  pageSizeCap: Int = 100
)(implicit ec: ExecutionContext) {
  import WorkflowQueryService._

  private implicit val timeout: Timeout = 5.seconds
  private implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = system.scheduler
  private implicit val materializer: Materializer = SystemMaterializer(system).materializer
  private lazy val readJournal = PersistenceQuery(system).readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)

  def list(page: Int, requestedPageSize: Int): Future[WorkflowPage] = {
    val normalizedPage = math.max(0, page)
    val pageSize = math.max(1, math.min(pageSizeCap, requestedPageSize))
    Future(readJournal).flatMap(_.currentPersistenceIds()
      .filter(_.startsWith("workflow-"))
      .drop(normalizedPage.toLong * pageSize)
      .take(pageSize)
      .runWith(Sink.seq)
      .flatMap(ids => Future.sequence(ids.map { persistenceId =>
        val workflowId = persistenceId.stripPrefix("workflow-")
        supervisor.ask[EventSourcedWorkflowActor.WorkflowSummary](replyTo => WorkflowSupervisor.GetWorkflowSummary(workflowId, replyTo))
      }).map(summaries => WorkflowPage(summaries.toVector, normalizedPage, pageSize))))
  }
}
