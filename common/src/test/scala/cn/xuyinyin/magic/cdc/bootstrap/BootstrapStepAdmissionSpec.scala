package cn.xuyinyin.magic.cdc.bootstrap

import cn.xuyinyin.magic.connectors.mysql.source.{MySQLCdcSourceConfig,MySQLCdcStateConfig}
import cn.xuyinyin.magic.connectors.mysql.source.capture._
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await,Future}
import scala.concurrent.duration._
import spray.json._

/** Real Runner + real in-memory Actor Journal; JDBC must remain unopened at these gates. */
class BootstrapStepAdmissionSpec extends AnyWordSpec with Matchers {
  "the legacy bootstrap constructor" should {
    "retain the Workflow sink parameter for previously compiled callers" in {
      classOf[BootstrapRunner].getConstructors.exists(_.getParameterTypes.contains(classOf[CdcReplaySink])) shouldBe true
    }
  }
  "an activation fence" should {
    "bind the covered F marker to the subscription, generation, schema and B boundary" in {
      val b=CapturePoint("mysql-bin.000001",10,0)
      val end=CapturePoint("mysql-bin.000001",20,0)
      val sub=CaptureSubscription("task","orders","g1","CAPTURE_READY","schema",Some(b),None,Some(2))
      val context="{\"bootstrap\":\"task\",\"schema\":\"schema\",\"window\":\"F\"}"
      val fence=CaptureFence("task_F","task","F",context,Some(end),Some(5),covered=true)
      def check(s:CaptureSubscription=sub,f:CaptureFence=fence):Unit=
        BootstrapActivationFence.verify("task","orders","g1","schema",context,s,f)
      noException should be thrownBy check()
      intercept[IllegalArgumentException](check(f=fence.copy(action="HIGH")))
      intercept[IllegalArgumentException](check(f=fence.copy(subscriptionId="other")))
      intercept[IllegalArgumentException](check(f=fence.copy(context="{}")))
      intercept[IllegalArgumentException](check(s=sub.copy(generation="g2")))
      intercept[IllegalArgumentException](check(s=sub.copy(schemaId="other")))
      intercept[IllegalArgumentException](check(f=fence.copy(point=Some(b))))
      intercept[IllegalArgumentException](check(f=fence.copy(sequence=Some(2))))
      intercept[IllegalArgumentException](check(f=fence.copy(covered=false)))
    }
  }
  private def fixture(body:(BootstrapRunner,BootstrapJournalAccess,BootstrapAdmission,AtomicInteger,AtomicInteger)=>Unit):Unit={
    val kit=ActorTestKit("bootstrap-step-"+System.nanoTime(),ConfigFactory.parseString("""pekko.persistence.journal.plugin="pekko.persistence.journal.inmem"
pekko.persistence.snapshot-store.plugin="pekko.persistence.snapshot-store.local"
pekko.persistence.snapshot-store.local.dir="target/bootstrap-step-snapshots"""").withFallback(ConfigFactory.load("application-test")))
    implicit val ec:scala.concurrent.ExecutionContext=kit.system.executionContext
    implicit val scheduler:org.apache.pekko.actor.typed.Scheduler=kit.system.scheduler
    val opened=new AtomicInteger();val readiness=new AtomicInteger()
    def forbidden():Connection={opened.incrementAndGet();throw new AssertionError("step gate opened JDBC")}
    val store=new MySQLCaptureStore(()=>forbidden(),"capture","lineage",1048576)
    val capture=new SharedMySQLCapture(MySQLCdcSourceConfig("capture","localhost",1,"source","rows","user","unused",1,128,100),
      MySQLCdcStateConfig("jdbc:mysql://localhost/state","user","unused","offsets","history",100),store) {
      override def cachedStartupEvidence:CaptureStartupEvidence={
        readiness.incrementAndGet()
        CaptureStartupEvidence("runtime","STARTING",Some("CAPTURE_STARTING"),"MISSING","UNKNOWN","MISSING",0,0,0)
      }
    }
    val access=new BootstrapJournalAccess(kit.spawn(BootstrapJournal("step-unit-"+System.nanoTime(),
      validateReceipt=(_,_,_)=>Future.successful(true))))
    val codec=new BootstrapRowCodec("""{"primaryKeyColumnNames":["id"],"columns":[{"name":"id","jdbcType":-5}]}""")
    val admission=new BootstrapAdmission(runners=2)
    val runner=new BootstrapRunner("step-unit","rows","generation","definition",capture,store,
      new BootstrapStore(()=>forbidden(),"step-unit",1048576),new BootstrapTarget(()=>forbidden(),"target_rows",codec,"step-unit"),
      access,new BootstrapSnapshot(()=>forbidden(),"rows",codec,32,1048576),
      new cn.xuyinyin.magic.cdc.CdcReplaySink {
        def commitBatch(subscriptionId:String,executionId:String,batch:CdcCapturedBatch,log:String=>Unit)
          (implicit ec:scala.concurrent.ExecutionContext):Future[Unit]=Future.failed(new AssertionError("step gate invoked sink"))
      },
      "source",()=>forbidden(),admission)
    try body(runner,access,admission,opened,readiness) finally kit.shutdownTestKit()
  }

  "the actual bounded Runner admission" should {
    "yield repeated not-ready attempts without opening JDBC or spending a recovery budget" in fixture { (runner,journal,admission,opened,_)=>
      (1 to 20).foreach { _=>
        val result=runner.step()
        result.didWork shouldBe false
        result.validationComplete shouldBe false
        result.releaseConfirmed shouldBe true
        result.nextDelayMillis should be > 0L
      }
      val state=Await.result(journal.read(),10.seconds)
      state.revision shouldBe 0
      state.recoveryAttempts shouldBe 0
      opened.get() shouldBe 0
      admission.evidence.fields("runAvailable") shouldBe JsNumber(2)
    }
    "treat maintenance stop as yield without requesting capture readiness or cancellation" in fixture { (runner,journal,_,opened,readiness)=>
      val result=runner.step(stop=()=>true)
      result.phase shouldBe "QUEUED"
      result.didWork shouldBe false
      opened.get() shouldBe 0
      readiness.get() shouldBe 0
      Await.result(journal.read(),10.seconds).cancelRequested shouldBe false
    }
    "return a durable paused state without claiming it can resume" in fixture { (runner,journal,_,opened,readiness)=>
      Await.result(journal.update(_.copy(phase="PAUSED",failure=Some("test"))),10.seconds)
      val result=runner.step()
      result.terminal shouldBe true
      result.paused shouldBe true
      opened.get() shouldBe 0
      readiness.get() shouldBe 0
    }
    "leave RECOVERY_PENDING untouched without source or sink work" in fixture { (runner,journal,admission,opened,readiness)=>
      val initial=Await.result(journal.update(_.copy(definition="definition",executionId="execution")),10.seconds)
      Await.result(journal.resumePendingBWithReceipt(initial.revision,"execution","definition","permit","a"*64),10.seconds) shouldBe "ACCEPTED"
      val before=Await.result(journal.read(),10.seconds)
      before.phase shouldBe "RECOVERY_PENDING"
      val result=runner.step();result.didWork shouldBe false;result.validationComplete shouldBe false
      runner.run()
      Await.result(journal.read(),10.seconds) shouldBe before
      opened.get() shouldBe 0
      readiness.get() shouldBe 0
      admission.evidence.fields("runAvailable") shouldBe JsNumber(2)
    }
  }
}
