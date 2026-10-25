package cn.xuyinyin.magic.connectors.mysql.source

import io.debezium.engine.{DebeziumEngine,RecordChangeEvent}
import org.apache.kafka.connect.source.SourceRecord
import java.lang.reflect.{InvocationHandler,InvocationTargetException,Method,Proxy}
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters._
import spray.json._
import org.apache.kafka.connect.data.Struct

/** Records actual SDK invocations; gates never manufacture SourceRecords or ACKs. */
final class CaptureObservedFactory(emit:(String,JsObject)=>Unit) extends DebeziumEngineFactory {
  val armed=new AtomicReference[String]("")
  private val gate=new CountDownLatch(1)
  private def take(mode:String):Boolean={val current=armed.get();current==mode && armed.compareAndSet(current,"")}
  def release():Unit=gate.countDown()
  private def raw(v:Any):JsValue=v match {
    case null=>JsNull
    case s:Struct=>JsObject(s.schema().fields().asScala.map(f=>f.name()->raw(s.getWithoutDefault(f.name()))).toMap)
    case s:String=>JsString(s)
    case b:java.lang.Boolean=>JsBoolean(b.booleanValue())
    case n:java.math.BigDecimal=>JsString(n.toPlainString)
    case n:java.lang.Number=>JsNumber(BigDecimal(n.toString))
    case xs:java.util.List[_]=>JsArray(xs.asScala.toVector.map(raw))
    case bytes:Array[Byte]=>JsString(java.util.Base64.getEncoder.encodeToString(bytes))
    case m:java.util.Map[_,_]=>JsObject(m.asScala.map {case(k,x)=>k.toString->raw(x)}.toMap)
    case x=>JsString(x.toString)
  }
  def create(props:Properties):DebeziumEngineAccess=new RealDebeziumEngineAccess(props,(consumer,completion)=>{
    val wrapped=new DebeziumEngine.ChangeConsumer[RecordChangeEvent[SourceRecord]] {
      def handleBatch(records:java.util.List[RecordChangeEvent[SourceRecord]],committer:DebeziumEngine.RecordCommitter[RecordChangeEvent[SourceRecord]]):Unit={
        records.asScala.foreach {event=>val r=event.record()
          emit("ORIGINAL_RECORD",JsObject("schema"->JsString(String.valueOf(r.valueSchema())),"value"->JsString(String.valueOf(r.value())),"offset"->MySQLCdcSourceNode.cursorValue(r).parseJson,
            "key"->raw(r.key()),"data"->raw(r.value()),"headers"->JsArray(r.headers().iterator().asScala.map(h=>JsObject("name"->JsString(h.key()),"value"->raw(h.value()))).toVector),
            "pkDelete"->JsBoolean(r.headers().lastWithName("__debezium.newkey")!=null),"pkCreate"->JsBoolean(r.headers().lastWithName("__debezium.oldkey")!=null)))
        }
        val committerClass=classOf[DebeziumEngine.RecordCommitter[RecordChangeEvent[SourceRecord]]]
        var pk=false
        val proxy=Proxy.newProxyInstance(committerClass.getClassLoader,Array(committerClass),new InvocationHandler {
          def invoke(o:Any,m:Method,args:Array[AnyRef]):AnyRef={
            val result=try m.invoke(committer,Option(args).getOrElse(Array.empty[AnyRef]):_*)catch {case e:InvocationTargetException=>throw e.getCause}
            if(m.getName=="markProcessed") {
              val r=args(0).asInstanceOf[RecordChangeEvent[SourceRecord]].record()
              pk=r.headers().lastWithName("__debezium.newkey")!=null
              emit("SDK_MARK_PROCESSED_RETURNED",JsObject("offset"->MySQLCdcSourceNode.cursorValue(r).parseJson))
              if(pk && take("MID_ACK")){emit("CUTPOINT",JsObject("window"->JsString("MID_ACK")));gate.await()}
            }
            if(m.getName=="markBatchFinished") {
              emit("SDK_MARK_BATCH_FINISHED_RETURNED",JsObject())
              if(pk && take("BETWEEN_ACK")){emit("CUTPOINT",JsObject("window"->JsString("BETWEEN_ACK")));gate.await()}
            }
            result
          }
        }).asInstanceOf[DebeziumEngine.RecordCommitter[RecordChangeEvent[SourceRecord]]]
        consumer.handleBatch(records,proxy)
        if(records.asScala.exists(_.record().headers().lastWithName("__debezium.newkey")!=null))emit("DELETE_CALLBACK_RETURNED",JsObject())
        val marker=records.asScala.exists(r=>String.valueOf(r.record().value()).contains("table=pekko_capture_control") && String.valueOf(r.record().value()).contains("op=c"))
        if(marker && take("MARKER_RETURNED")){emit("CUTPOINT",JsObject("window"->JsString("MARKER_RETURNED")));gate.await()}
      }
      override def supportsTombstoneEvents():Boolean=true
    }
    RealDebeziumEngineAccess.defaultEngineBuilder(props)(wrapped,completion)
  })
}
