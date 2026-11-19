package cn.xuyinyin.magic.connectors.mysql.source

import ch.qos.logback.classic.{Level,Logger}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import spray.json._

/** Test-only synchronous SDK snapshot observation, with unchanged no_data/locking=none.
  * Debezium 3.6.1 RelationalSnapshotChangeEventSource emits this event immediately before
  * no_data's preSnapshotCompletion/postSnapshotCompletion. Schema events are already queued.
  * A changed SDK log boundary fails to hit the gate, rather than falling back to a sleep.
  */
final class CaptureStartupRecoveryFactory(emit:(String,JsObject)=>Unit,gateSchema:Boolean)
  extends DebeziumEngineFactory {
  private val original=new CaptureObservedFactory(emit)
  val armed=original.armed
  private val latch=new CountDownLatch(1)
  private val gate=new AtomicBoolean(gateSchema)
  private val logger=LoggerFactory.getLogger("io.debezium.relational.RelationalSnapshotChangeEventSource").asInstanceOf[Logger]
  private val oldLevel=logger.getLevel
  private val streamingLogger=LoggerFactory.getLogger("io.debezium.connector.binlog.BinlogStreamingChangeEventSource").asInstanceOf[Logger]
  private val oldStreamingLevel=streamingLogger.getLevel
  private val appender=new AppenderBase[ILoggingEvent] {
    override def append(event:ILoggingEvent):Unit={
      if(event.getMessage=="Emitted {} {} record(s) for event: {}" && Option(event.getArgumentArray).exists(a=>a.length==3 && a(0).toString=="1" && a(1).toString=="CREATE"))
        emit("SDK_B_ROW_ENQUEUED",JsObject("message"->JsString(event.getFormattedMessage),"scope"->JsString("exclusive source: only control INSERT is issued after SDK startup")))
      if(event.getFormattedMessage=="Snapshot step 7 - Skipping snapshotting of data" && gate.compareAndSet(true,false)) {
        emit("SDK_CUTPOINT",JsObject("window"->JsString("SCHEMA_EMITTED"),"logger"->JsString(event.getLoggerName),"message"->JsString(event.getFormattedMessage)))
        latch.await()
        emit("SDK_CUTPOINT_RELEASED",JsObject("window"->JsString("SCHEMA_EMITTED")))
      }
    }
  }
  appender.setContext(logger.getLoggerContext);appender.start()
  logger.setLevel(Level.INFO);logger.addAppender(appender)
  streamingLogger.setLevel(Level.DEBUG);streamingLogger.addAppender(appender)
  def release():Unit={latch.countDown();original.release()}
  def create(props:Properties):DebeziumEngineAccess={
    require(props.getProperty("snapshot.mode")=="no_data" && props.getProperty("snapshot.locking.mode")=="none","probe requires original snapshot profile")
    val engine=original.create(props)
    new DebeziumEngineAccess {
      def start(consumer:DebeziumBatchConsumer)=engine.start(consumer)
      override def localTerminationConfirmed:Boolean=engine.localTerminationConfirmed
      def close():Unit=try engine.close()finally {logger.detachAppender(appender);logger.setLevel(oldLevel);streamingLogger.detachAppender(appender);streamingLogger.setLevel(oldStreamingLevel);appender.stop()}
    }
  }
}
