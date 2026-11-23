package cn.xuyinyin.magic.cdc.platform

import cn.xuyinyin.magic.cdc.bootstrap._
import cn.xuyinyin.magic.cdc.checkpoint.{BatchCheckpoint, Committed}
import cn.xuyinyin.magic.cdc.guardian.ManagedCdcJdbc
import cn.xuyinyin.magic.cdc.stream.{BootstrapJournalTableGraph, TableCdcGraph, TableCdcGraphRuntime, TableGraphSettings, TableGraphSink}
import cn.xuyinyin.magic.connectors.mysql.source.capture._
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.stream.SystemMaterializer
import spray.json._

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

private[platform] final case class CdcCaptureHandle(settings: CdcCaptureSettings, store: MySQLCaptureStore, reader: SharedMySQLCapture)

private[platform] final case class CdcTableSession(
    graph: TableCdcGraphRuntime,
    journal: ActorRef[BootstrapJournal.Command],
    target: BootstrapTarget,
    pool: String)

/** Builds one table's Journal, snapshot runner and materialized tail graph. */
private[platform] final class CdcTableSessionFactory(
    system: ActorSystem[_],
    managed: Boolean,
    recoveryStore: Option[CdcTaskStore],
    jdbc: CdcJdbcConnections,
    admission: BootstrapAdmission,
    graphBlockingEc: ExecutionContext,
    observe: (String, JsObject) => Unit) {

  private implicit val actorEc: ExecutionContext = system.executionContext
  private implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = system.scheduler

  def create(
      task: CdcTaskRequest,
      id: String,
      pool: String,
      capture: CdcCaptureHandle,
      stopJournal: ActorRef[BootstrapJournal.Command] => Unit): CdcTableSession = {
    val codec = new BootstrapRowCodec(capture.store.schema(task.table).getOrElse(throw new CdcPlatformException("SCHEMA_NOT_READY")).definition)
    val journal = system.systemActorOf(
      BootstrapJournal(
        task.captureId + "-" + id,
        validatePermit(id),
        validateReceipt(id),
        validateConsumed(id, capture)),
      "cdc-journal-" + UUID.randomUUID().toString)

    try {
      val access = new BootstrapJournalAccess(journal)
      val spool = new BootstrapStore(() => jdbc.material(capture.settings), id, capture.settings.maxSpoolBytes)
      val target = new BootstrapTarget(() => jdbc.state(capture.settings), task.targetTable, codec, id)
      val onEvent: (String, JsObject) => Unit = (kind, data) => observe(kind, JsObject(data.fields + ("taskId" -> JsString(id))))
      val source = new BootstrapSnapshot(
        () => jdbc.source(capture.settings),
        task.table,
        codec,
        capture.settings.bootstrapLimits.windowRows,
        capture.settings.bootstrapLimits.windowBytes,
        onEvent)
      val sink = new BootstrapReplaySink(_ => jdbc.state(capture.settings)).bind(CdcTargetConfig.forTarget(capture.settings, task.targetTable))
      val progressVerifier = createProgressVerifier(capture, id)
      val runner = new BootstrapRunner(
        id,
        task.table,
        task.generation,
        task.canonical,
        capture.reader,
        capture.store,
        spool,
        target,
        access,
        source,
        sink,
        capture.settings.source.database,
        () => jdbc.state(capture.settings),
        admission,
        capture.settings.bootstrapLimits,
        onEvent,
        progressVerifier)(ExecutionContext.parasitic)
      val consumerId = CaptureConsumerIdentity.bootstrap(capture.store.captureId, id, task.generation)
      val graphSink = new TableGraphSink {
        override def commit(subscriptionId: String, executionId: String, batch: CdcCapturedBatch)(implicit ec: ExecutionContext) =
          sink.commitBatch(subscriptionId, executionId, batch, _ => ())(ec).map { _ =>
            val cursor = cn.xuyinyin.magic.cdc.checkpoint.SourceCursor(batch.cursor.kind, batch.cursor.value, batch.cursor.upperBound)
            Committed(BatchCheckpoint(batch.sourceNodeId, batch.partitionId, batch.batchSequence, batch.batchId, cursor, batch.rows.size, batch.rows.size))
          }(ExecutionContext.parasitic)
      }
      val graphJournal = new BootstrapJournalTableGraph(
        access,
        receipt => progressVerifier.foreach { verifier =>
          capture.store.projectBootstrapVerified(consumerId, id, task.generation, runner.state.definition, receipt, verifier)
        })
      implicit val materializer: org.apache.pekko.stream.Materializer = SystemMaterializer(system).materializer
      val graph = new TableCdcGraph(capture.reader, TableGraphSettings(id, "bootstrap", id, consumerId), graphSink, graphJournal, graphBlockingEc)
      CdcTableSession(TableCdcGraphRuntime.fromSnapshot(runner, Some(graph)), journal, target, pool)
    } catch {
      case e: Throwable => stopJournal(journal); throw e
    }
  }

  private def validatePermit(id: String): (String, BootstrapProgress) => Future[Boolean] =
    (permit, _) => Future(scala.concurrent.blocking(recoveryStore.exists(_.authorizedBPermit(permit, id))))(ExecutionContext.global)

  private def validateReceipt(id: String): (String, String, BootstrapProgress) => Future[Boolean] =
    (permit, digest, _) => Future(scala.concurrent.blocking(recoveryStore.exists(_.receiptDigestForPermit(permit, id).contains(digest))))(ExecutionContext.global)

  private def validateConsumed(id: String, capture: CdcCaptureHandle): (String, String, BootstrapProgress) => Future[Boolean] =
    (permit, digest, state) => Future(scala.concurrent.blocking(recoveryStore.exists { store =>
      store.fenceRecoveryReady(permit, id, digest, state.executionId, CdcCaptureFenceVerifier.create(capture.settings, store))
    }))(ExecutionContext.global)

  private def createProgressVerifier(capture: CdcCaptureHandle, id: String): Option[BootstrapJournalProgressVerifier] = {
    if (!capture.store.discoveryComplete) None
    else {
      val journal = system.settings.config.getConfig("pekko-persistence-jdbc.shared-databases.slick.db")
      Some(
        BootstrapJournalProgressVerifier.create(
          system,
          () => jdbc.state(capture.settings),
          () => ManagedCdcJdbc.open(journal.getString("url"), journal.getString("user"), journal.getString("password"), "journal", managed)))
    }
  }
}
