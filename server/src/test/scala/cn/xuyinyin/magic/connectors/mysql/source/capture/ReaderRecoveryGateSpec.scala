package cn.xuyinyin.magic.connectors.mysql.source.capture

import cn.xuyinyin.magic.connectors.mysql.source.{MySQLCdcSourceConfig,MySQLCdcStateConfig}
import cn.xuyinyin.magic.workflow.cdc.CdcTaskStore
import java.sql.Connection
import java.util.concurrent.{CountDownLatch,Executors,TimeUnit}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.{Await,ExecutionContext,Future}
import scala.concurrent.duration._
import scala.util.Try

/** Reflection creates a non-authoritative fixture solely to test installation serialization. */
class ReaderRecoveryGateSpec extends AnyWordSpec with Matchers {
  "reader recovery gate installation" should {
    "accept only one same-capture installation and reject installation after stop" in {
      def gate(id:String):CdcTaskStore.ReaderRecoveryGate={
        val constructor=classOf[CdcTaskStore.ReaderRecoveryGate].getDeclaredConstructors.find(_.getParameterCount==4).get
        constructor.setAccessible(true)
        val consume: (Connection,String,String,String)=>Unit=(_,_,_,_)=>()
        val check:()=>Unit=()=>()
        constructor.newInstance(id,Long.box(1L),consume,check).asInstanceOf[CdcTaskStore.ReaderRecoveryGate]
      }
      val capture="gate_test";val source=MySQLCdcSourceConfig(capture,"127.0.0.1",3307,"db","t","u","p",1,1,1)
      val state=MySQLCdcStateConfig("jdbc:mysql://127.0.0.1:3308/db","u","p","offsets","history",0)
      val store=new MySQLCaptureStore(()=>throw new IllegalStateException("unexpected connection"),capture,"lineage",1024)
      val reader=new SharedMySQLCapture(source,state,store,sourceFenceRequired=true)
      intercept[IllegalArgumentException](reader.installRecoveryGate(gate("other")))
      val executor=Executors.newFixedThreadPool(2);implicit val ec:ExecutionContext=ExecutionContext.fromExecutorService(executor)
      val start=new CountDownLatch(1)
      try {
        val attempts=Vector.fill(2)(Future{start.await();Try(reader.installRecoveryGate(gate(capture)))})
        start.countDown();val results=Await.result(Future.sequence(attempts),5.seconds)
        results.count(_.isSuccess) shouldBe 1;results.count(_.isFailure) shouldBe 1
        Await.result(reader.requestClose(),5.seconds)
        intercept[IllegalArgumentException](reader.installRecoveryGate(gate(capture)))
        val starting=new SharedMySQLCapture(source,state,store,sourceFenceRequired=true)
        intercept[Throwable](starting.start())
        intercept[IllegalArgumentException](starting.installRecoveryGate(gate(capture)))
        Await.result(starting.requestClose(),5.seconds)
      }finally {executor.shutdownNow();executor.awaitTermination(5,TimeUnit.SECONDS)}
    }
  }
}
