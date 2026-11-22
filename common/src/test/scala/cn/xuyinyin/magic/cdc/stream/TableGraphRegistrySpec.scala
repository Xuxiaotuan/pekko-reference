package cn.xuyinyin.magic.cdc.stream

import cn.xuyinyin.magic.cdc.checkpoint.{BatchCheckpoint, Committed}
import cn.xuyinyin.magic.connectors.mysql.source.{MySQLCdcSourceConfig, MySQLCdcStateConfig}
import cn.xuyinyin.magic.connectors.mysql.source.capture.{CdcCaptureCursor, CdcCapturedBatch, MySQLCaptureStore, SharedMySQLCapture}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.SystemMaterializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.{Done, NotUsed}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

class TableGraphRegistrySpec extends AnyWordSpec with Matchers {
  "the table graph registry" should {
    "add, stop and re-add independent table graphs" in {
      implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](Behaviors.empty, "table-registry-test")
      implicit val ec: ExecutionContext         = system.executionContext
      implicit val materializer                 = SystemMaterializer(system).materializer
      val capture                               = new FakeCapture
      val registry                              = system.systemActorOf(TableGraphRegistry(), "table-graph-registry")
      val probe                                 = org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[TableGraphRegistry.Reply]()(system)

      registry ! TableGraphRegistry.Add("orders", () => capture.graph("orders"), probe.ref)
      probe.expectMessage(TableGraphRegistry.Added)
      registry ! TableGraphRegistry.Add("customers", () => capture.graph("customers"), probe.ref)
      probe.expectMessage(TableGraphRegistry.Added)
      registry ! TableGraphRegistry.Status("orders", probe.ref)
      probe.expectMessage(TableGraphRegistry.Running(true))
      registry ! TableGraphRegistry.Remove("orders", probe.ref)
      probe.expectMessage(TableGraphRegistry.RemovedReply)
      registry ! TableGraphRegistry.Status("orders", probe.ref)
      probe.expectMessage(TableGraphRegistry.Absent)
      registry ! TableGraphRegistry.Add("orders", () => capture.graph("orders-restarted"), probe.ref)
      probe.expectMessage(TableGraphRegistry.Added)
      registry ! TableGraphRegistry.StopAll(probe.ref)
      probe.expectMessage(TableGraphRegistry.AllStoppedReply)
      registry ! TableGraphRegistry.Status("customers", probe.ref)
      probe.expectMessage(TableGraphRegistry.Absent)
      system.terminate()
      Await.result(system.whenTerminated, 5.seconds)
    }
  }

  final private class FakeCapture extends SharedMySQLCapture(
        MySQLCdcSourceConfig("cap", "127.0.0.1", 3306, "db", "source_rows", "u", "p", 1, 1, 1),
        MySQLCdcStateConfig("jdbc:mysql://127.0.0.1/db", "u", "p", "offsets", "history", 0),
        new MySQLCaptureStore(() => null, "cap", "lineage", 1024L)) {
    private val acknowledgements = new AtomicInteger(0)
    def graph(id: String)(implicit materializer: org.apache.pekko.stream.Materializer, ec: ExecutionContext): TableCdcGraph =
      new TableCdcGraph(
        this,
        TableGraphSettings(id, "source-1", "execution-" + id, ("c" * 64)),
        new TableGraphSink {
          override def commit(
              subscriptionId: String,
              executionId: String,
              b: CdcCapturedBatch
            )(implicit ec: ExecutionContext): Future[cn.xuyinyin.magic.cdc.checkpoint.BatchCommitResult] =
            Future.successful(
              Committed(
                BatchCheckpoint(
                  b.sourceNodeId,
                  b.partitionId,
                  b.batchSequence,
                  b.batchId,
                  cn.xuyinyin.magic.connectors.mysql.source.capture.CdcCapturedBatchWorkflowAdapter.toWorkflow(b).cursor,
                  b.rows.size,
                  b.rows.size)))
        },
        new TableGraphJournal {
          @volatile private var latestValue: Option[BatchCheckpoint] = None
          override def persist(checkpoint: BatchCheckpoint)(implicit ec: ExecutionContext): Future[Long] = {
            latestValue = Some(checkpoint); Future.successful(1L)
          }
          override def latest: Option[BatchCheckpoint] = latestValue
        })
    override def tableSource(
        subscriptionId: String,
        sourceNodeId: String,
        executionId: String,
        after: Long,
        sequence: Long,
        batchSize: Int,
        consumerId: String
      )(implicit ec: ExecutionContext): Source[CdcCapturedBatch, NotUsed] =
      Source.single(
        CdcCapturedBatch(
          sourceNodeId,
          "mysql-cdc:capture",
          sequence,
          "batch-" + subscriptionId,
          CdcCaptureCursor("mysql.capture.groups.v1", JsObject("sequence" -> JsNumber(sequence + 1), "version" -> JsNumber(1)).compactPrint, "{}"),
          Vector("{\"id\":1}")))
    override def acknowledgeTableBatch(
        subscriptionId: String,
        sourceNodeId: String,
        consumerId: String,
        b: CdcCapturedBatch,
        journalRevision: Long
      )(implicit ec: ExecutionContext): Future[Done] = {
      acknowledgements.incrementAndGet(); Future.successful(Done)
    }
  }
}
