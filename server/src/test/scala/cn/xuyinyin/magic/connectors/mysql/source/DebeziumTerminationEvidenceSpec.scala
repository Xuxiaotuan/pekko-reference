package cn.xuyinyin.magic.connectors.mysql.source

import cn.xuyinyin.magic.testkit.STSpec
import io.debezium.engine.{DebeziumEngine,RecordChangeEvent}
import org.apache.kafka.connect.source.SourceRecord
import java.util.Properties
import java.util.concurrent.{CountDownLatch,TimeUnit}
import scala.concurrent.{Await,Future,ExecutionContext}
import scala.concurrent.duration._

class DebeziumTerminationEvidenceSpec extends STSpec {
  "local SDK termination evidence" should {
    "keep a managed close pending while task startup remains active" in {
      val entered=new CountDownLatch(1);val startRelease=new CountDownLatch(1);val stop=new CountDownLatch(1)
      val attempts=new java.util.concurrent.atomic.AtomicInteger()
      val access=new RealDebeziumEngineAccess(new Properties(),(_,_)=>new DebeziumEngine[RecordChangeEvent[SourceRecord]] {
        def run():Unit={entered.countDown();startRelease.await();stop.await()}
        def close():Unit={attempts.incrementAndGet();if(startRelease.getCount>0)throw new IllegalStateException("tasks are starting");stop.countDown()}
      })
      implicit val ec:ExecutionContext=ExecutionContext.global
      access.start((_,_)=>());entered.await(2,TimeUnit.SECONDS) shouldBe true
      val closing=Future(access.closeManaged())
      try {
        Thread.sleep(2300)
        closing.isCompleted shouldBe false;access.localTerminationConfirmed shouldBe false
        startRelease.countDown();Await.result(closing,5.seconds)
        access.localTerminationConfirmed shouldBe true;attempts.get() should be > 1
      }finally {startRelease.countDown();stop.countDown();scala.util.Try(Await.result(closing,5.seconds))}
    }
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
