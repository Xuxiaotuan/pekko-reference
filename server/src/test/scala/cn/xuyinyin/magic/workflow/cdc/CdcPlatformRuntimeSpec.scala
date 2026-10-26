package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.testkit.STSpec
import cn.xuyinyin.magic.workflow.bootstrap.BootstrapProgress
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.{CountDownLatch,TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration._

class CdcPlatformRuntimeSpec extends STSpec {
  "bounded CDC runtime" should {
    "let idle streaming tasks yield while another table initializes" in {
      val url="jdbc:h2:mem:cdc_runtime_"+UUID.randomUUID().toString.replace('-','_')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
      val limits=CdcPlatformLimits(snapshotSteps=1,tailSteps=1,tailDelayMillis=1)
      val calls=new AtomicInteger()
      val kernel=new CdcPlatformKernel {
        def start():Unit=()
        def captures:Vector[CdcCaptureView]=Vector(CdcCaptureView("capture","source","ADMISSION_READY","HEALTHY",None))
        def run(task:CdcTaskRequest,id:String,cancel:Boolean,continuation:Option[BootstrapProgress],stop:()=>Boolean,workPool:String):CdcKernelOutcome={
          calls.incrementAndGet()
          CdcKernelOutcome(BootstrapProgress(revision=continuation.fold(1L)(_.revision+1),phase=if(cancel)"CANCELED"else"STREAMING",definition="test",executionId=id),
            releaseConfirmed=true,didWork=true,validationComplete=true,1L,Some("test-only-proof"))
        }
        def closeAndVerify():Boolean=true
      }
      val runtime=new CdcPlatformRuntime(new CdcTaskStore(()=>DriverManager.getConnection(url),limits),kernel,limits,polling=false)
      try {
        (1 to 12).foreach {i=>Await.result(runtime.submit(CdcTaskRequest("r"+i,"capture","table_"+i,"g1","target_"+i)),10.seconds)}
        (1 to 40).foreach {_=>Await.result(runtime.pollNow(),10.seconds);Thread.sleep(10)}
        val page=Await.result(runtime.listAfter(None,100),10.seconds)
        page.items.count(_.phase=="STREAMING") shouldBe 12
        runtime.status.activeSteps should be <= 2
        calls.get() should be >= 12
      }finally Await.result(runtime.stopRuntime(),10.seconds)
    }
    "bound a blocked maintenance close without releasing its durable owner" in {
      val url="jdbc:h2:mem:cdc_stop_"+UUID.randomUUID().toString.replace('-','_')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
      val limits=CdcPlatformLimits(snapshotSteps=1,tailSteps=1)
      val entered=new CountDownLatch(1);val release=new CountDownLatch(1);val closes=new AtomicInteger()
      val kernel=new CdcPlatformKernel {
        def start():Unit=()
        def captures:Vector[CdcCaptureView]=Vector(CdcCaptureView("capture","source","STARTING","STARTING",None))
        def run(task:CdcTaskRequest,id:String,cancel:Boolean,continuation:Option[BootstrapProgress],stop:()=>Boolean,workPool:String):CdcKernelOutcome=
          throw new AssertionError("no work admitted while starting")
        def closeAndVerify():Boolean={closes.incrementAndGet();entered.countDown();release.await();true}
      }
      val runtime=new CdcPlatformRuntime(new CdcTaskStore(()=>DriverManager.getConnection(url),limits),kernel,limits,polling=false)
      try {
        val queued=Await.result(runtime.submit(CdcTaskRequest("queued","capture","rows","g1","target")),10.seconds).task
        queued.cancelAllowed shouldBe true
        val response=runtime.stopRuntime()
        entered.await(5,TimeUnit.SECONDS) shouldBe true
        Await.result(response,11.seconds) shouldBe false
        runtime.store.ownerStatus should not be "STOPPED"
        runtime.status.ownerState shouldBe "UNKNOWN"
        closes.get() shouldBe 1
        release.countDown()
        val deadline=System.nanoTime()+5000000000L
        while(runtime.status.ownerState!="STOPPED" && System.nanoTime()<deadline)Thread.sleep(10)
        runtime.status.ownerState shouldBe "STOPPED"
        runtime.store.ownerStatus shouldBe "STOPPED"
        intercept[CdcPlatformException](Await.result(runtime.get(queued.taskId),2.seconds)).code shouldBe "CDC_PLATFORM_STOPPING"
      }finally {release.countDown()}
    }
  }
}
