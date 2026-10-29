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
    "retry recovery after a transient proof lookup failure without activating early" in {
      val url="jdbc:h2:mem:cdc_recovery_retry_"+UUID.randomUUID().toString.replace('-','_')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
      val limits=CdcPlatformLimits(snapshotSteps=1,tailSteps=1);val attempts=new AtomicInteger();val starts=new AtomicInteger()
      val kernel=new CdcPlatformKernel {
        override def prepareManagedManifest():String="{}"
        def start():Unit={starts.incrementAndGet();()}
        def captures:Vector[CdcCaptureView]=Vector.empty
        def run(task:CdcTaskRequest,id:String,cancel:Boolean,continuation:Option[BootstrapProgress],stop:()=>Boolean,workPool:String):CdcKernelOutcome=throw new AssertionError("no tasks")
        def closeAndVerify():Boolean=true
      }
      val store=new CdcTaskStore(()=>DriverManager.getConnection(url),limits)
      val identity=CdcManagedOwner("boot","d","host","guardian","key",123,"start","profile")
      val runtime=new CdcPlatformRuntime(store,kernel,limits,polling=false,managedOwner=Some(identity),recoverManaged=()=>{
        if(attempts.incrementAndGet()==1)throw new IllegalStateException("test observation unavailable")
      })
      try {
        Await.result(runtime.listAfter(None,1),10.seconds)
        runtime.status.ownerState shouldBe "UNKNOWN";starts.get() shouldBe 0
        Thread.sleep(1100);Await.result(runtime.pollNow(),10.seconds)
        runtime.status.ownerState shouldBe "ACTIVE";attempts.get() shouldBe 2;starts.get() shouldBe 1
      } finally Await.result(runtime.stopRuntime(),10.seconds)
    }
    "stop resources when initialization fails immediately during construction" in {
      val url="jdbc:h2:mem:cdc_immediate_failure_"+UUID.randomUUID().toString.replace('-','_')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
      val limits=CdcPlatformLimits(snapshotSteps=1,tailSteps=1)
      val first=new java.util.concurrent.atomic.AtomicBoolean(true)
      val stopSignaled=new CountDownLatch(1)
      val kernel=new CdcPlatformKernel {
        def start():Unit=throw new AssertionError("must not start")
        def captures:Vector[CdcCaptureView]=Vector.empty
        def run(task:CdcTaskRequest,id:String,cancel:Boolean,continuation:Option[BootstrapProgress],stop:()=>Boolean,workPool:String):CdcKernelOutcome=throw new AssertionError("no tasks")
        override def requestStop():Unit=stopSignaled.countDown()
        def closeAndVerify():Boolean=true
      }
      val store=new CdcTaskStore(()=>{
        if(first.compareAndSet(true,false))throw new java.sql.SQLException("immediate initialization failure")
        DriverManager.getConnection(url)
      },limits)
      val identity=CdcManagedOwner("boot","d","host","guardian","key",123,"start","profile")
      val runtime=new CdcPlatformRuntime(store,kernel,limits,polling=true,managedOwner=Some(identity))
      stopSignaled.await(5,TimeUnit.SECONDS) shouldBe true
      Await.result(runtime.stopRuntime(),5.seconds) shouldBe false
      runtime.status.ownerState should not be "STOPPED"
    }
    "request stop after partial reader startup before a blocked owner-state write" in {
      val url="jdbc:h2:mem:cdc_partial_start_"+UUID.randomUUID().toString.replace('-','_')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
      val limits=CdcPlatformLimits(snapshotSteps=1,tailSteps=1)
      val block=new java.util.concurrent.atomic.AtomicBoolean(false)
      val release=new CountDownLatch(1);val stopSignaled=new CountDownLatch(1);val closed=new CountDownLatch(1)
      val started=new java.util.concurrent.atomic.AtomicBoolean(false)
      val kernel=new CdcPlatformKernel {
        override def prepareManagedManifest():String="{}"
        def start():Unit={started.set(true);block.set(true);throw new IllegalStateException("second capture failed")}
        def captures:Vector[CdcCaptureView]=Vector.empty
        def run(task:CdcTaskRequest,id:String,cancel:Boolean,continuation:Option[BootstrapProgress],stop:()=>Boolean,workPool:String):CdcKernelOutcome=throw new AssertionError("no tasks")
        override def requestStop():Unit={started.set(false);stopSignaled.countDown()}
        def closeAndVerify():Boolean={closed.countDown();true}
      }
      val store=new CdcTaskStore(()=>{
        if(block.get())release.await()
        DriverManager.getConnection(url)
      },limits)
      val identity=CdcManagedOwner("boot","d","host","guardian","key",123,"start","profile")
      val runtime=new CdcPlatformRuntime(store,kernel,limits,polling=false,managedOwner=Some(identity))
      try {
        stopSignaled.await(5,TimeUnit.SECONDS) shouldBe true
        started.get() shouldBe false
        release.countDown();block.set(false)
        closed.await(5,TimeUnit.SECONDS) shouldBe true
        Await.result(runtime.stopRuntime(),10.seconds) shouldBe false
        store.ownerStatus shouldBe "UNKNOWN"
      }finally release.countDown()
    }
    "never report a clean stop when an owner commit reply and its readback are lost" in {
      val url="jdbc:h2:mem:cdc_owner_lost_reply_"+UUID.randomUUID().toString.replace('-','_')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
      val limits=CdcPlatformLimits(snapshotSteps=1,tailSteps=1)
      val arm=new java.util.concurrent.atomic.AtomicBoolean(false)
      val unavailable=new java.util.concurrent.atomic.AtomicBoolean(false)
      val attestations=new AtomicInteger();val stopped=new CountDownLatch(1)
      val kernel=new CdcPlatformKernel {
        override def prepareManagedManifest():String="{}"
        def start():Unit=throw new AssertionError("must not start")
        def captures:Vector[CdcCaptureView]=Vector.empty
        def run(task:CdcTaskRequest,id:String,cancel:Boolean,continuation:Option[BootstrapProgress],stop:()=>Boolean,workPool:String):CdcKernelOutcome=throw new AssertionError("no tasks")
        override def requestStop():Unit=stopped.countDown()
        def closeAndVerify():Boolean=true
      }
      val store=new CdcTaskStore(()=>{
        if(unavailable.get())throw new java.sql.SQLException("injected readback unavailable")
        val c=DriverManager.getConnection(url)
        java.lang.reflect.Proxy.newProxyInstance(classOf[java.sql.Connection].getClassLoader,Array(classOf[java.sql.Connection]),
          (_,m:java.lang.reflect.Method,args:Array[AnyRef])=>try {
            val result=m.invoke(c,Option(args).getOrElse(Array.empty[AnyRef]):_*)
            if(m.getName=="commit" && arm.compareAndSet(true,false)){
              unavailable.set(true);throw new java.sql.SQLException("injected committed owner reply loss")
            }
            result
          }catch {case e:java.lang.reflect.InvocationTargetException=>throw e.getCause}).asInstanceOf[java.sql.Connection]
      },limits)
      val identity=CdcManagedOwner("boot","d","host","guardian","key",123,"start","profile")
      val runtime=new CdcPlatformRuntime(store,kernel,limits,polling=false,managedOwner=Some(identity),guardianCheck=()=>{
        if(attestations.incrementAndGet()==2)arm.set(true)
      })
      try {
        stopped.await(5,TimeUnit.SECONDS) shouldBe true
        Await.result(runtime.stopRuntime(),5.seconds) shouldBe false
        runtime.status.ownerState should not be "STOPPED"
        unavailable.set(false)
        val deadline=System.nanoTime()+5000000000L
        while(store.ownerStatus!="UNKNOWN" && System.nanoTime()<deadline)Thread.sleep(10)
        store.ownerStatus shouldBe "UNKNOWN"
      }finally unavailable.set(false)
    }
    "pause and retain the owner when guardian attestation is lost" in {
      val url="jdbc:h2:mem:cdc_guardian_loss_"+UUID.randomUUID().toString.replace('-','_')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
      val limits=CdcPlatformLimits(snapshotSteps=1,tailSteps=1);val available=new java.util.concurrent.atomic.AtomicBoolean(true)
      val kernel=new CdcPlatformKernel {
        override def prepareManagedManifest():String="{}"
        def start():Unit=()
        def captures:Vector[CdcCaptureView]=Vector.empty
        def run(task:CdcTaskRequest,id:String,cancel:Boolean,continuation:Option[BootstrapProgress],stop:()=>Boolean,workPool:String):CdcKernelOutcome=throw new AssertionError("no tasks")
        def closeAndVerify():Boolean=true
      }
      val store=new CdcTaskStore(()=>DriverManager.getConnection(url),limits)
      val identity=CdcManagedOwner("boot","d","host","guardian","key",123,"start","profile")
      val runtime=new CdcPlatformRuntime(store,kernel,limits,polling=false,managedOwner=Some(identity),guardianCheck=()=>require(available.get(),"guardian unavailable"))
      try {
        Await.result(runtime.listAfter(None,1),10.seconds)
        Await.result(runtime.pollNow(),10.seconds)
        available.set(false)
        val deadline=System.nanoTime()+5000000000L
        while((runtime.status.ownerState!="UNKNOWN" || store.ownerStatus!="UNKNOWN") && System.nanoTime()<deadline)Thread.sleep(10)
        runtime.status.ownerState shouldBe "UNKNOWN"
        store.ownerStatus shouldBe "UNKNOWN"
        Await.result(runtime.stopRuntime(),10.seconds) shouldBe false
      } finally available.set(false)
    }
    "signal reader stop on guardian loss even when both control threads are blocked" in {
      val url="jdbc:h2:mem:cdc_guardian_busy_"+UUID.randomUUID().toString.replace('-','_')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
      val limits=CdcPlatformLimits(snapshotSteps=1,tailSteps=1)
      val available=new java.util.concurrent.atomic.AtomicBoolean(true)
      val block=new java.util.concurrent.atomic.AtomicBoolean(false)
      val entered=new CountDownLatch(2);val release=new CountDownLatch(1);val stopSignaled=new CountDownLatch(1)
      val kernel=new CdcPlatformKernel {
        override def prepareManagedManifest():String="{}"
        def start():Unit=()
        def captures:Vector[CdcCaptureView]=Vector.empty
        def run(task:CdcTaskRequest,id:String,cancel:Boolean,continuation:Option[BootstrapProgress],stop:()=>Boolean,workPool:String):CdcKernelOutcome=throw new AssertionError("no tasks")
        override def requestStop():Unit=stopSignaled.countDown()
        def closeAndVerify():Boolean=true
      }
      val store=new CdcTaskStore(()=>{
        if(block.get() && Thread.currentThread().getName.contains("cdc-platform-control")){entered.countDown();release.await()}
        DriverManager.getConnection(url)
      },limits)
      val identity=CdcManagedOwner("boot","d","host","guardian","key",123,"start","profile")
      val runtime=new CdcPlatformRuntime(store,kernel,limits,polling=false,managedOwner=Some(identity),guardianCheck=()=>require(available.get(),"guardian unavailable"))
      try {
        Await.result(runtime.listAfter(None,1),10.seconds)
        block.set(true);runtime.listAfter(None,1);runtime.listAfter(None,1)
        entered.await(5,TimeUnit.SECONDS) shouldBe true
        available.set(false)
        stopSignaled.await(5,TimeUnit.SECONDS) shouldBe true
        runtime.status.ownerState shouldBe "UNKNOWN"
        runtime.status.reason shouldBe Some("GUARDIAN_UNAVAILABLE")
        release.countDown();block.set(false)
        Await.result(runtime.stopRuntime(),10.seconds) shouldBe false
        store.ownerStatus shouldBe "UNKNOWN"
      }finally {release.countDown()}
    }
    "never reactivate when guardian is lost during reader startup" in {
      val url="jdbc:h2:mem:cdc_guardian_start_"+UUID.randomUUID().toString.replace('-','_')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
      val limits=CdcPlatformLimits(snapshotSteps=1,tailSteps=1)
      val available=new java.util.concurrent.atomic.AtomicBoolean(true)
      val entered=new CountDownLatch(1);val release=new CountDownLatch(1);val stopSignaled=new CountDownLatch(1)
      val kernel=new CdcPlatformKernel {
        override def prepareManagedManifest():String="{}"
        def start():Unit={entered.countDown();release.await()}
        def captures:Vector[CdcCaptureView]=Vector.empty
        def run(task:CdcTaskRequest,id:String,cancel:Boolean,continuation:Option[BootstrapProgress],stop:()=>Boolean,workPool:String):CdcKernelOutcome=throw new AssertionError("no tasks")
        override def requestStop():Unit=stopSignaled.countDown()
        def closeAndVerify():Boolean=true
      }
      val store=new CdcTaskStore(()=>DriverManager.getConnection(url),limits)
      val identity=CdcManagedOwner("boot","d","host","guardian","key",123,"start","profile")
      val runtime=new CdcPlatformRuntime(store,kernel,limits,polling=false,managedOwner=Some(identity),guardianCheck=()=>require(available.get(),"guardian unavailable"))
      try {
        entered.await(5,TimeUnit.SECONDS) shouldBe true
        available.set(false);stopSignaled.await(5,TimeUnit.SECONDS) shouldBe true
        release.countDown()
        Await.result(runtime.stopRuntime(),10.seconds) shouldBe false
        runtime.status.ownerState shouldBe "UNKNOWN"
        store.ownerStatus shouldBe "UNKNOWN"
      }finally {release.countDown()}
    }
    "bind a unique durable owner attempt to its managed worker identity" in {
      val url=sys.env.getOrElse("CDC_MANAGED_TEST_JDBC_URL","jdbc:h2:mem:cdc_managed_"+UUID.randomUUID().toString.replace('-','_')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
      val limits=CdcPlatformLimits(snapshotSteps=1,tailSteps=1)
      val kernel=new CdcPlatformKernel {
        override def prepareManagedManifest():String="{\"version\":1,\"accounts\":[\"test\"]}"
        def start():Unit=()
        def captures:Vector[CdcCaptureView]=Vector.empty
        def run(task:CdcTaskRequest,id:String,cancel:Boolean,continuation:Option[BootstrapProgress],stop:()=>Boolean,workPool:String):CdcKernelOutcome=throw new AssertionError("no tasks")
        def closeAndVerify():Boolean=true
      }
      val boot="managed-"+UUID.randomUUID().toString
      val store=new CdcTaskStore(()=>sys.env.get("CDC_MANAGED_TEST_JDBC_URL") match {
        case Some(_)=>DriverManager.getConnection(url,sys.env("CDC_MANAGED_TEST_USER"),sys.env("CDC_MANAGED_TEST_PASSWORD"))
        case None=>DriverManager.getConnection(url)
      },limits)
      val managed=CdcManagedOwner(boot,"deployment","host","guardian","public-key",42L,"start","profile")
      val runtime=new CdcPlatformRuntime(store,kernel,limits,polling=false,managedOwner=Some(managed))
      try {
        Await.result(runtime.listAfter(None,1),10.seconds)
        runtime.runtimeAttemptId should not be boot
        store.managedOwner(runtime.runtimeAttemptId) shouldBe Some(managed)
        store.managedManifest(runtime.runtimeAttemptId) shouldBe Some("{\"version\":1,\"accounts\":[\"test\"]}")
        store.beginOwner("another-worker",System.currentTimeMillis()) shouldBe false
        store.beginOwner(runtime.runtimeAttemptId,System.currentTimeMillis(),Some(managed)) shouldBe true
        intercept[IllegalArgumentException](store.beginOwner(runtime.runtimeAttemptId,System.currentTimeMillis(),Some(managed.copy(bootId="other"))))
      } finally Await.result(runtime.stopRuntime(),10.seconds)
    }
    "not start the reader when its managed resource manifest cannot be proven" in {
      val url="jdbc:h2:mem:cdc_manifest_failure_"+UUID.randomUUID().toString.replace('-','_')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
      val limits=CdcPlatformLimits(snapshotSteps=1,tailSteps=1)
      val starts=new AtomicInteger()
      val kernel=new CdcPlatformKernel {
        override def prepareManagedManifest():String=throw new IllegalStateException("missing source account")
        def start():Unit={starts.incrementAndGet();()}
        def captures:Vector[CdcCaptureView]=Vector.empty
        def run(task:CdcTaskRequest,id:String,cancel:Boolean,continuation:Option[BootstrapProgress],stop:()=>Boolean,workPool:String):CdcKernelOutcome=throw new AssertionError("no tasks")
        def closeAndVerify():Boolean=true
      }
      val store=new CdcTaskStore(()=>DriverManager.getConnection(url),limits)
      val identity=CdcManagedOwner("boot","deployment","host","guardian","key",42L,"start","profile")
      val runtime=new CdcPlatformRuntime(store,kernel,limits,polling=false,managedOwner=Some(identity))
      try {
        intercept[CdcPlatformException](Await.result(runtime.listAfter(None,1),10.seconds))
        starts.get() shouldBe 0
        store.managedManifest(runtime.runtimeAttemptId) shouldBe None
        val deadline=System.nanoTime()+5000000000L
        while(store.ownerStatus!="UNKNOWN" && System.nanoTime()<deadline)Thread.sleep(10)
        store.ownerStatus shouldBe "UNKNOWN"
        store.beginOwner("another",System.currentTimeMillis()) shouldBe false
      } finally Await.result(runtime.stopRuntime(),10.seconds)
    }
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
