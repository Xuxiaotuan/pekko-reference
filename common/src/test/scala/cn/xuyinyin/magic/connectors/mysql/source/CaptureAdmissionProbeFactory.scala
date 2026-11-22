package cn.xuyinyin.magic.connectors.mysql.source

import io.debezium.embedded.Connect
import io.debezium.engine.{DebeziumEngine, RecordChangeEvent}
import io.debezium.engine.format.ChangeEventFormat
import org.apache.kafka.connect.source.SourceRecord
import spray.json._
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters._

object CaptureAdmissionProbeFactory {
  /** SDK component comparison: same relevant properties, without SharedMySQLCapture.start's control DDL. */
  def sdkProperties(source: MySQLCdcSourceConfig, state: MySQLCdcStateConfig): Properties = {
    val p = MySQLCdcSourceNode.connectorProperties(source, state)
    p.setProperty("snapshot.mode", "no_data")
    p.setProperty("table.include.list", java.util.regex.Pattern.quote(source.database) + "\\..*")
    p.setProperty("include.schema.changes", "true")
    p.setProperty("provide.transaction.metadata", "true")
    p.setProperty("skipped.operations", "none")
    p.setProperty("offset.flush.timeout.ms", "2000")
    p.setProperty("max.queue.size.in.bytes", (16L * 1024 * 1024).toString)
    p.setProperty("schema.history.internal.store.only.captured.databases.ddl", "true")
    p
  }
}

/** Test-only observation. All records and original committers pass through unchanged. */
final class CaptureAdmissionProbeFactory(heartbeatMillis: Int, emit: (String, JsObject) => Unit, holdFirstCallback:Boolean=false)
  extends DebeziumEngineFactory {
  private val gate=new CountDownLatch(1)
  private val gateArmed=new AtomicBoolean(holdFirstCallback)
  def releaseGate():Unit=gate.countDown()
  override def create(props: Properties): DebeziumEngineAccess = {
    props.setProperty("heartbeat.interval.ms", heartbeatMillis.toString)
    emit("EFFECTIVE_CONFIG", JsObject("heartbeatMillis" -> JsNumber(heartbeatMillis),
      "snapshotMode" -> JsString(props.getProperty("snapshot.mode")),
      "batchSize" -> JsString(props.getProperty("max.batch.size"))))
    new RealDebeziumEngineAccess(props, (consumer, completion) => {
      val observed = new DebeziumEngine.ChangeConsumer[RecordChangeEvent[SourceRecord]] {
        override def supportsTombstoneEvents(): Boolean = true
        override def handleBatch(records: java.util.List[RecordChangeEvent[SourceRecord]],
          committer: DebeziumEngine.RecordCommitter[RecordChangeEvent[SourceRecord]]): Unit = {
          records.asScala.foreach { e =>
            val r = e.record()
            emit("ORIGINAL_RECORD", JsObject("schema" -> JsString(Option(r.valueSchema()).map(_.name()).getOrElse("null")),
              "partition" -> JsObject(r.sourcePartition().asScala.map { case (k, v) => k -> JsString(String.valueOf(v)) }.toMap),
              "offset" -> MySQLCdcSourceNode.cursorValue(r).parseJson,
              "value" -> JsString(String.valueOf(r.value()))))
          }
          var interrupted=false
          if(gateArmed.compareAndSet(true,false)){
            emit("TEST_CALLBACK_HELD",JsObject("records"->JsNumber(records.size)))
            var released=false
            while(!released)try {gate.await();released=true}catch {case _:InterruptedException=>interrupted=true;emit("TEST_CALLBACK_GATE_INTERRUPTED",JsObject())}
          }
          try {
            consumer.handleBatch(records, committer)
            emit("ORIGINAL_CALLBACK_RETURNED", JsObject())
          } catch {case e:Throwable=>emit("ORIGINAL_CALLBACK_FAILED",JsObject("errorKind"->JsString(e.getClass.getName)));throw e}
          finally {if(interrupted)Thread.currentThread().interrupt()}
        }
      }
      val lifecycle = new DebeziumEngine.ConnectorCallback {
        override def connectorStarted(): Unit = emit("CONNECTOR_STARTED", JsObject())
        override def taskStarted(): Unit = emit("TASK_STARTED", JsObject())
        override def pollingStarted(): Unit = emit("POLLING_STARTED", JsObject())
        override def pollingStopped(): Unit = emit("POLLING_STOPPED", JsObject())
        override def taskStopped(): Unit = emit("TASK_STOPPED", JsObject())
        override def connectorStopped(): Unit = emit("CONNECTOR_STOPPED", JsObject())
      }
      val observedCompletion = new DebeziumEngine.CompletionCallback {
        override def handle(success: Boolean, message: String, error: Throwable): Unit = {
          emit("ENGINE_COMPLETION", JsObject("success" -> JsBoolean(success),
            "errorKind" -> Option(error).map(e => JsString(e.getClass.getName)).getOrElse(JsNull)))
          completion.handle(success, message, error)
        }
      }
      DebeziumEngine.create[SourceRecord, Connect](ChangeEventFormat.of[Connect](classOf[Connect]))
        .using(props).using(observedCompletion).using(lifecycle).notifying(observed).build()
    })
  }
}
