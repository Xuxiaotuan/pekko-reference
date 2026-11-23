package cn.xuyinyin.magic.cdc.stream

import cn.xuyinyin.magic.cdc.bootstrap.{BootstrapJournal, BootstrapJournalAccess}
import cn.xuyinyin.magic.cdc.checkpoint.{AlreadyCommitted, BatchCheckpoint, BatchCommitResult, Committed}
import cn.xuyinyin.magic.connectors.mysql.sink.{MySQLCdcApplyConfig, MySQLCdcApplyEngine}
import cn.xuyinyin.magic.connectors.mysql.source.capture.{CdcCapturedBatch, CdcCapturedBatchWorkflowAdapter}
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.stream.{KillSwitches, Materializer, UniqueKillSwitch}
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import java.util.concurrent.atomic.AtomicReference
import spray.json._

/** A table graph owns one table's Source, Sink, Journal handoff and ACK order. */
trait TableGraphSink {
  def commit(subscriptionId: String, executionId: String, batch: CdcCapturedBatch)(implicit ec: ExecutionContext): Future[BatchCommitResult]
}

/** Capture capability required by a table graph. Reader lifecycle stays outside the graph. */
trait TableGraphCapture {
  def tableSource(
      subscriptionId: String,
      sourceNodeId: String,
      executionId: String,
      after: Long,
      sequence: Long,
      batchSize: Int,
      consumerId: String)(implicit ec: ExecutionContext): Source[CdcCapturedBatch, NotUsed]

  def acknowledgeTableBatch(
      subscriptionId: String,
      sourceNodeId: String,
      consumerId: String,
      batch: CdcCapturedBatch,
      journalRevision: Long)(implicit ec: ExecutionContext): Future[Done]
}

trait TableGraphJournal {

  /** The returned revision is the durable Journal revision used by the L projection. */
  def persist(checkpoint: BatchCheckpoint)(implicit ec: ExecutionContext): Future[Long]
  def latest: Option[BatchCheckpoint]
  /** Run proof projection only after the Reader ACK has advanced observed L. */
  def afterAck(checkpoint: BatchCheckpoint, revision: Long)(implicit ec: ExecutionContext): Future[Done] =
    Future.successful(Done)
  def recover(settings: TableGraphSettings)(implicit ec: ExecutionContext): Future[TableGraphSettings] =
    Future.successful(
      latest.fold(settings)(checkpoint =>
        settings.copy(
          after = checkpoint.cursor.value.parseJson.asJsObject.fields("sequence").asInstanceOf[spray.json.JsNumber].value.toLongExact,
          nextBatchSequence = checkpoint.batchSequence + 1L)))
}

final case class TableGraphSettings(
    subscriptionId: String,
    sourceNodeId: String,
    executionId: String,
    consumerId: String,
    after: Long = 0L,
    nextBatchSequence: Long = 0L,
    batchSize: Int = 128)

final case class TableGraphMaterialized(
    killSwitch: UniqueKillSwitch,
    completion: Future[Done],
    latestCheckpoint: () => Option[BatchCheckpoint],
    failure: () => Option[Throwable] = () => None) {
  def stop(): Future[Done] = { killSwitch.shutdown(); completion }
}

final class TableCdcGraph(
    capture: TableGraphCapture,
    settings: TableGraphSettings,
    sink: TableGraphSink,
    journal: TableGraphJournal,
    blockingEc: ExecutionContext = ExecutionContext.global
  )(implicit materializer: Materializer,
    ec: ExecutionContext) {
  require(settings.consumerId.matches("[a-f0-9]{64}"))
  def materialize(): TableGraphMaterialized = {
    materialize(settings)
  }
  def materializeRecovered()(implicit ec: ExecutionContext): Future[TableGraphMaterialized] =
    journal.recover(settings).map(materialize)
  private def materialize(activeSettings: TableGraphSettings): TableGraphMaterialized = {
    val source = capture.tableSource(
      activeSettings.subscriptionId,
      activeSettings.sourceNodeId,
      activeSettings.executionId,
      activeSettings.after,
      activeSettings.nextBatchSequence,
      activeSettings.batchSize,
      activeSettings.consumerId)(blockingEc)
    val (kill, done) = source
      .viaMat(KillSwitches.single)(Keep.right)
      .mapAsync(1) { batch =>
        sink.commit(activeSettings.subscriptionId, activeSettings.executionId, batch)(blockingEc).flatMap { result =>
          val checkpoint = result match {
            case Committed(value)        => value
            case AlreadyCommitted(value) => value
          }
          journal.persist(checkpoint).flatMap { revision =>
            capture
              .acknowledgeTableBatch(activeSettings.subscriptionId, activeSettings.sourceNodeId, activeSettings.consumerId, batch, revision)
              .flatMap(_ => journal.afterAck(checkpoint, revision))
          }
        }
      }
      .toMat(Sink.ignore)(Keep.both)
      .run()
    val failureRef = new AtomicReference[Option[Throwable]](None)
    done.onComplete {
      case Failure(error) => failureRef.set(Some(error))
      case Success(_)     => ()
    }(ExecutionContext.parasitic)
    TableGraphMaterialized(kill, done, () => journal.latest, () => failureRef.get())
  }
}

/** Adapter for the existing transactional MySQL ledger and DML engine. */
final class MySQLTableGraphSink(config: MySQLCdcApplyConfig) extends TableGraphSink {
  private val engine = new MySQLCdcApplyEngine
  override def commit(
      subscriptionId: String,
      executionId: String,
      batch: CdcCapturedBatch
    )(implicit ec: ExecutionContext): Future[BatchCommitResult] =
    engine.commitBatchConfigured(config, subscriptionId, executionId, CdcCapturedBatchWorkflowAdapter.toWorkflow(batch), batch.rows, _ => ())
}

/** Adapter that keeps the existing EventSourcedBehavior and its CAS protocol. */
final class BootstrapJournalTableGraph(
    journal: BootstrapJournalAccess,
    afterPersist: BootstrapJournal.PersistedProgress => Unit = (_: BootstrapJournal.PersistedProgress) => ())
    extends TableGraphJournal {
  private val latestRef                        = new AtomicReference[Option[BatchCheckpoint]](None)
  private val pendingReceipt                  = new AtomicReference[Option[BootstrapJournal.PersistedProgress]](None)
  override def latest: Option[BatchCheckpoint] = latestRef.get()
  override def recover(settings: TableGraphSettings)(implicit ec: ExecutionContext): Future[TableGraphSettings] =
    journal.read().map(state => settings.copy(after = state.deltaAfter, nextBatchSequence = state.batchSequence + 1L))
  override def persist(checkpoint: BatchCheckpoint)(implicit ec: ExecutionContext): Future[Long] = {
    latestRef.set(Some(checkpoint))
    journal
      .update { state =>
        state.copy(
          batchSequence = checkpoint.batchSequence,
          deltaAfter = checkpoint.cursor.value.parseJson.asJsObject.fields("sequence").asInstanceOf[spray.json.JsNumber].value.toLongExact)
      }
      .flatMap { _ =>
        journal.persistedProgress().map { receipt =>
          val value = receipt.getOrElse(throw new IllegalStateException("persisted graph Journal progress missing"))
          pendingReceipt.set(Some(value))
          value.sequenceNr
        }
      }
  }
  override def afterAck(checkpoint: BatchCheckpoint, revision: Long)(implicit ec: ExecutionContext): Future[Done] = Future {
    pendingReceipt.getAndSet(None).foreach(afterPersist)
    Done
  }
}
