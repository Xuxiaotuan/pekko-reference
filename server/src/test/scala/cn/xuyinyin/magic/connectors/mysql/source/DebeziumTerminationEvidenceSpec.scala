package cn.xuyinyin.magic.connectors.mysql.source

import cn.xuyinyin.magic.testkit.STSpec
import io.debezium.engine.{DebeziumEngine,RecordChangeEvent}
import org.apache.kafka.connect.source.SourceRecord
import java.util.Properties
import java.util.concurrent.{CountDownLatch,TimeUnit}
import scala.concurrent.Await
import scala.concurrent.duration._

class DebeziumTerminationEvidenceSpec extends STSpec {
  "local SDK termination evidence" should {
    "remain false after an early completion until the actual runner and executor end" in {
      val entered=new CountDownLatch(1);val leave=new CountDownLatch(1)
      val access=new RealDebeziumEngineAccess(new Properties(),(_,callback)=>new DebeziumEngine[RecordChangeEvent[SourceRecord]] {
        def run():Unit={callback.handle(true,"early completion",null);entered.countDown();leave.await()}
        def close():Unit=leave.countDown()
      })
      val done=access.start((_,_)=>())
      try {
        entered.await(1,TimeUnit.SECONDS) shouldBe true
        intercept[IllegalStateException](Await.result(done,1.second))
        access.localTerminationConfirmed shouldBe false
      }finally {leave.countDown();access.close()}
      access.localTerminationConfirmed shouldBe true
    }
  }
}
