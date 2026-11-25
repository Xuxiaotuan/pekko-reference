package cn.xuyinyin.magic.connectors.mysql.source.capture

import cn.xuyinyin.magic.cdc.stream.TableGraphCapture
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.stream.scaladsl.Source
import spray.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/** Table-level view of the durable capture log used by one materialized Graph. */
private[capture] final class CaptureTableGraphAccess(store: MySQLCaptureStore, database: String) extends TableGraphCapture {

  override def tableSource(
      subscriptionId: String,
      sourceNodeId: String,
      executionId: String,
      after: Long,
      sequence: Long,
      batchSize: Int,
      consumerId: String)(implicit ec: ExecutionContext): Source[CdcCapturedBatch, NotUsed] = {
    require(subscriptionId.nonEmpty && sourceNodeId.nonEmpty && executionId.nonEmpty && consumerId.matches("[a-f0-9]{64}"))
    require(batchSize > 0 && batchSize <= 1000 && after >= 0 && sequence >= 0)
    val consumerKind = if (sourceNodeId == "bootstrap") "BOOTSTRAP" else "WORKFLOW"
    store.registerConsumer(consumerId, consumerKind, subscriptionId, sourceNodeId, after)
    val reader       = new CapturedLogReader(store, database)
    val cursor       = new AnyRef
    var current      = after
    var nextSequence = sequence
    Source
      .tick(Duration.Zero, 100.millis, ())
      .mapAsync(1) { _ =>
        Future {
          cursor.synchronized {
            reader.readBatch(subscriptionId, current, batchSize, sourceNodeId, executionId, nextSequence, Some(consumerId)).map { batch =>
              current = batch.cursor.value.parseJson.asJsObject.fields("sequence").asInstanceOf[JsNumber].value.toLongExact
              nextSequence += 1L
              batch
            }
          }
        }
      }
      .mapConcat(_.toList)
      .mapMaterializedValue(_ => NotUsed)
  }

  override def acknowledgeTableBatch(
      subscriptionId: String,
      sourceNodeId: String,
      consumerId: String,
      batch: CdcCapturedBatch,
      journalRevision: Long)(implicit ec: ExecutionContext): Future[Done] = Future {
    require(consumerId.matches("[a-f0-9]{64}") && journalRevision >= 0)
    val reader       = new CapturedLogReader(store, database)
    val floor        = reader.resumePosition(subscriptionId, batch.cursor)
    val consumerKind = if (sourceNodeId == "bootstrap") "BOOTSTRAP" else "WORKFLOW"
    store.projectConsumerJournal(consumerId, consumerKind, subscriptionId, sourceNodeId, floor, journalRevision, batch.batchId)
    Done
  }
}
