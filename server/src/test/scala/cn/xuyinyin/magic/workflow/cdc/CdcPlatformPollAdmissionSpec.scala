package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.testkit.STSpec
import cn.xuyinyin.magic.workflow.bootstrap.BootstrapProgress
import java.sql.{DriverManager,SQLException}
import java.util.UUID
import java.util.concurrent.{CountDownLatch,TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean,AtomicInteger}
import scala.concurrent.Await
import scala.concurrent.duration._

/** Control admission tests only: the kernel is bounded and no source/Sink data proof is claimed. */
class CdcPlatformPollAdmissionSpec extends STSpec {
  private class ProbeKernel extends CdcPlatformKernel {
    val holdCaptures=new AtomicBoolean(false)
    val captureEntered=new CountDownLatch(1)
    val releaseCapture=new CountDownLatch(1)
    val steps=new AtomicInteger()
    @volatile var dispatchFailure=Option.empty[Throwable]
    def start():Unit=()
    def captures:Vector[CdcCaptureView]={
      if(holdCaptures.get()){captureEntered.countDown();releaseCapture.await()}
      Vector(CdcCaptureView("capture","source","ADMISSION_READY","HEALTHY",None))
    }
    override def dispatchCaptureIds:Set[String]={dispatchFailure.foreach(throw _);Set.empty}
    def run(task:CdcTaskRequest,id:String,cancel:Boolean,continuation:Option[BootstrapProgress],stop:()=>Boolean,workPool:String):CdcKernelOutcome={
      steps.incrementAndGet()
      CdcKernelOutcome(BootstrapProgress(revision=1,phase="STREAMING",executionId=id),true,true,true,1L,Some("control-test-only-proof"))
    }
    def closeAndVerify():Boolean=true
  }
  private def fixture():(CdcPlatformRuntime,ProbeKernel)={
    val url="jdbc:h2:mem:cdc_poll_admission_"+UUID.randomUUID().toString.replace('-','_')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    val kernel=new ProbeKernel
    val limits=CdcPlatformLimits(snapshotSteps=1,tailSteps=1,maxPendingOperations=1)
    val runtime=new CdcPlatformRuntime(new CdcTaskStore(()=>DriverManager.getConnection(url),limits),kernel,limits,polling=false)
    Await.result(runtime.listAfter(None,1),5.seconds)
    runtime.status.ownerState shouldBe "ACTIVE"
    runtime -> kernel
  }
  "poll admission" should {
    "yield when the HTTP control budget is full without poisoning a healthy owner" in {
      val (runtime,kernel)=fixture()
      try {
        kernel.holdCaptures.set(true)
        val held=runtime.captures()
        kernel.captureEntered.await(5,TimeUnit.SECONDS) shouldBe true
        intercept[CdcPlatformException](Await.result(runtime.pollNow(),2.seconds)).code shouldBe "CDC_PLATFORM_BUSY"
        runtime.status.ownerState shouldBe "ACTIVE"
        runtime.status.reason shouldBe None
        kernel.releaseCapture.countDown()
        Await.result(held,5.seconds)
        Await.result(runtime.submit(CdcTaskRequest("request","capture","rows","g1","target")),5.seconds)
        Await.result(runtime.pollNow(),5.seconds)
        val deadline=System.nanoTime()+5000000000L
        while(runtime.status.activeSteps>0 && System.nanoTime()<deadline)Thread.sleep(10)
        kernel.steps.get() shouldBe 1
        runtime.status.ownerState shouldBe "ACTIVE"
      }finally {kernel.releaseCapture.countDown();Await.result(runtime.stopRuntime(),5.seconds)}
    }
    "retain UNKNOWN for an admitted storage failure" in {
      val (runtime,kernel)=fixture()
      try {
        kernel.dispatchFailure=Some(new SQLException("injected admitted storage failure"))
        intercept[SQLException](Await.result(runtime.pollNow(),5.seconds))
        runtime.status.ownerState shouldBe "UNKNOWN"
        runtime.status.reason shouldBe Some("STORAGE_UNAVAILABLE")
      }finally Await.result(runtime.stopRuntime(),5.seconds)
    }
    "not mistake a BUSY code thrown inside admitted work for pre-admission backpressure" in {
      val (runtime,kernel)=fixture()
      try {
        kernel.dispatchFailure=Some(new CdcPlatformException("CDC_PLATFORM_BUSY"))
        intercept[CdcPlatformException](Await.result(runtime.pollNow(),5.seconds)).code shouldBe "CDC_PLATFORM_BUSY"
        runtime.status.ownerState shouldBe "UNKNOWN"
        runtime.status.reason shouldBe Some("STORAGE_UNAVAILABLE")
      }finally Await.result(runtime.stopRuntime(),5.seconds)
    }
  }
}
