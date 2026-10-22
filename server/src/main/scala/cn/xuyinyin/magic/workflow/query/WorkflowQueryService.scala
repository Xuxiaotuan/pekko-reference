package cn.xuyinyin.magic.workflow.query

import cn.xuyinyin.magic.workflow.actors.{EventSourcedWorkflowActor, WorkflowSupervisor}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.persistence.jdbc.query.scaladsl.JdbcReadJournal
import org.apache.pekko.persistence.query.PersistenceQuery
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.stream.{Materializer, SystemMaterializer}
import org.apache.pekko.util.Timeout
import spray.json._

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
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

  require(pageSizeCap > 0, "pageSizeCap must be positive")
  private val config = system.settings.config.getConfig("pekko.workflow.query")
  private val parallelism = config.getInt("summary-parallelism")
  private val maxLists = config.getInt("max-concurrent-lists")
  private val deadline = config.getDuration("timeout").toMillis.millis
  private val askDeadline = config.getDuration("summary-timeout").toMillis.millis
  require(parallelism > 0 && maxLists > 0 && deadline > Duration.Zero && askDeadline > Duration.Zero,
    "workflow query limits must be positive")
  private val maxAsks = Math.multiplyExact(parallelism, maxLists)
  private val activeLists = new AtomicInteger()
  private val activeAsks = new AtomicInteger()
  private implicit val timeout: Timeout = askDeadline
  private implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = system.scheduler
  private implicit val materializer: Materializer = SystemMaterializer(system).materializer
  private lazy val readJournal = PersistenceQuery(system).readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)
  private lazy val tableScheduling = cn.xuyinyin.magic.workflow.scheduling.TableSchedulingExtension(system).runtime

  protected[query] def persistenceIds(): Source[String, NotUsed] = readJournal.currentPersistenceIds()

  // Reject instead of queueing. Asks keep their permits until their own future completes,
  // even when their enclosing stream fails or times out and cancels upstream.
  private def admitted[A](counter: AtomicInteger, limit: Int)(work: => Future[A]): Future[A] = {
    if (counter.incrementAndGet() > limit) {
      counter.decrementAndGet()
      Future.failed(new RejectedExecutionException("workflow query capacity exhausted"))
    } else {
      val running = try work catch { case NonFatal(error) => Future.failed(error) }
      running.transform { result => counter.decrementAndGet(); result }(ExecutionContext.parasitic)
    }
  }

  private[query] def inFlightSummaries: Int = activeAsks.get()

  def list(page: Int, requestedPageSize: Int): Future[WorkflowPage] = admitted(activeLists, maxLists) {
    val normalizedPage = math.max(0, page)
    val pageSize = math.max(1, math.min(pageSizeCap, requestedPageSize))
    Source.futureSource(Future(persistenceIds()))
      .filter(_.startsWith("workflow-"))
      .drop(normalizedPage.toLong * pageSize)
      .take(pageSize)
      .mapAsync(parallelism) { persistenceId =>
        val workflowId = persistenceId.stripPrefix("workflow-")
        def liveSummary() = admitted(activeAsks, maxAsks) {
          supervisor.ask[EventSourcedWorkflowActor.WorkflowSummary](replyTo => WorkflowSupervisor.GetWorkflowSummary(workflowId, replyTo))
        }
        tableScheduling match {
          case None => liveSummary()
          case Some(runtime) => runtime.summary(workflowId).flatMap {
            case Some(t) => Future.successful(EventSourcedWorkflowActor.WorkflowSummary(t.workflowId,t.revision,
              EventSourcedWorkflowActor.WorkflowStatus(if(t.state=="completed") "completed" else "snapshot_"+t.state),None,Vector.empty))
            case None => liveSummary()
          }
        }
      }
      .completionTimeout(deadline)
      .runWith(Sink.seq)
      .map(summaries => WorkflowPage(summaries.toVector, normalizedPage, pageSize))
  }
}
