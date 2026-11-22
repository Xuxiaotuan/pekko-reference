package cn.xuyinyin.magic.connectors.mysql.source

import io.debezium.engine.{DebeziumEngine,RecordChangeEvent}
import org.apache.kafka.connect.source.SourceRecord
import org.apache.pekko.Done
import java.util.Properties
import java.util.concurrent.CountDownLatch
import scala.concurrent.Future

/** Test gate inside the real SDK runner, after its wrapped engine returns. */
final class RunnerFinallyGateFactory extends DebeziumEngineFactory {
  val entered=new CountDownLatch(1)
  val release=new CountDownLatch(1)
  @volatile var completion:Future[Done]=null
  def create(properties:Properties):DebeziumEngineAccess={
    val build=RealDebeziumEngineAccess.defaultEngineBuilder(properties)
    val delegate=new RealDebeziumEngineAccess(properties,(consumer,callback)=>{
      val engine=build(consumer,callback)
      new DebeziumEngine[RecordChangeEvent[SourceRecord]] {
        override def run():Unit=try engine.run() finally {entered.countDown();release.await()}
        override def close():Unit=engine.close()
      }
    })
    new DebeziumEngineAccess {
      def start(consumer:DebeziumBatchConsumer):Future[Done]={val f=delegate.start(consumer);completion=f;f}
      override def localTerminationConfirmed:Boolean=delegate.localTerminationConfirmed
      def close():Unit=delegate.close()
    }
  }
}
