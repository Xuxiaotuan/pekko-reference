package cn.xuyinyin.magic.connectors.mysql.source

import cn.xuyinyin.magic.connectors.mysql.source.capture._
import cn.xuyinyin.magic.testkit.STSpec
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.Await
import scala.concurrent.duration._

class CaptureReleaseGuardSpec extends STSpec with org.scalatest.concurrent.Eventually {
  private class Fixture {
    val local = new AtomicBoolean(true)
    val server = new AtomicBoolean(false)
    val owners = new AtomicInteger()
    val closes = new AtomicInteger()
    val ownerClosed = new AtomicBoolean(false)
    val observerFailed = new AtomicBoolean(false)
    val wrongAttempt = new AtomicBoolean(false)
    val entered = new CountDownLatch(1)
    val leave = new CountDownLatch(1)
    @volatile var block = false
    val guard = new CaptureReleaseGuard("attempt", () => {
      closes.incrementAndGet(); entered.countDown()
      if (block) leave.await()
    }, () => local.get(), includeOwner => {
      if (observerFailed.get()) throw new IllegalStateException("observer unavailable")
      CaptureServerRelease(if(wrongAttempt.get()) "old-attempt" else "attempt",
        if(server.get()) "CONFIRMED" else "UNKNOWN", None)
    }, () => {owners.incrementAndGet(); ownerClosed.set(true)}, () => ownerClosed.get(), () => (),
      CaptureCloseBudget(localMillis=50, serverMillis=50))
  }

  "managed capture release" should {
    "bound a blocked synchronous close and retain one tracked operation" in {
      val f=new Fixture; f.block=true
      try {
        val first=f.guard.request()
        f.entered.await(1,TimeUnit.SECONDS) shouldBe true
        val result=Await.result(first,2.seconds)
        result.localTermination shouldBe "UNKNOWN"
        result.reusable shouldBe false
        f.guard.request() shouldBe first
        f.closes.get() shouldBe 1
        f.owners.get() shouldBe 0
        f.server.set(true);f.leave.countDown()
        eventually {Await.result(f.guard.refresh(),2.seconds).reusable shouldBe true}
        f.guard.evidence.operationId shouldBe result.operationId
        f.closes.get() shouldBe 1
      } finally f.leave.countDown()
    }
    "not accept completion while runner or callbacks are still alive" in {
      val f=new Fixture;f.local.set(false);f.server.set(true)
      val result=Await.result(f.guard.request(),2.seconds)
      result.reusable shouldBe false;f.owners.get() shouldBe 0
      f.local.set(true)
      Await.result(f.guard.refresh(),2.seconds).reusable shouldBe true
    }
    "keep the owner while real server release remains unknown" in {
      val f=new Fixture
      val first=Await.result(f.guard.request(),2.seconds)
      first.localTermination shouldBe "CONFIRMED"
      first.serverRelease shouldBe "UNKNOWN"
      first.reusable shouldBe false;f.owners.get() shouldBe 0
      f.server.set(true)
      Await.result(f.guard.refresh(),2.seconds).reusable shouldBe true
      f.owners.get() shouldBe 1
      Await.result(f.guard.refresh(),2.seconds).reusable shouldBe true
      f.owners.get() shouldBe 1
    }
    "treat an observer failure as unknown rather than zero sessions" in {
      val f=new Fixture;f.server.set(true);f.observerFailed.set(true)
      Await.result(f.guard.request(),2.seconds).reusable shouldBe false
      f.owners.get() shouldBe 0
    }
    "reject a late release reply for another runtime attempt" in {
      val f=new Fixture;f.server.set(true);f.wrongAttempt.set(true)
      Await.result(f.guard.request(),2.seconds).reusable shouldBe false
      f.owners.get() shouldBe 0
      f.wrongAttempt.set(false)
      Await.result(f.guard.refresh(),2.seconds).reusable shouldBe true
    }
    "retry the same legacy close operation after global close-worker admission was rejected" in {
      val held=Vector.fill(4)(new Fixture)
      val store=new MySQLCaptureStore(()=>throw new AssertionError("no JDBC for an unstarted capture"),"retry_close","lineage",1024)
      val capture=new SharedMySQLCapture(MySQLCdcSourceConfig("retry_close","localhost",3306,"db","a","u","p",10001,128,100),
        MySQLCdcStateConfig("jdbc:h2:mem:retry_close","sa","","offsets","history",0),store,
        closeBudget=CaptureCloseBudget(50,50))
      try {
        held.foreach {f=>f.block=true;f.server.set(true);f.guard.request();f.entered.await(1,TimeUnit.SECONDS) shouldBe true}
        intercept[cn.xuyinyin.magic.workflow.engine.CdcExecutionException](capture.close()).failure.releaseConfirmed shouldBe false
        val operation=capture.releaseEvidence.operationId
        held.foreach(_.leave.countDown())
        eventually {held.forall(_.guard.evidence.reusable) shouldBe true}
        capture.close()
        capture.releaseEvidence.operationId shouldBe operation
      }finally {held.foreach(_.leave.countDown());capture.refreshRelease()}
    }
  }
}
