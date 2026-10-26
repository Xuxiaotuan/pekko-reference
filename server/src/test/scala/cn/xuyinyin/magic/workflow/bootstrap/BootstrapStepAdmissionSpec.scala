package cn.xuyinyin.magic.workflow.bootstrap

import cn.xuyinyin.magic.connectors.mysql.source.{MySQLCdcSourceConfig,MySQLCdcStateConfig}
import cn.xuyinyin.magic.connectors.mysql.source.capture._
import cn.xuyinyin.magic.workflow.model.WorkflowDSL.{Node,Position}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration._
import spray.json._

/** Real Runner + real in-memory Actor Journal; JDBC must remain unopened at these gates. */
class BootstrapStepAdmissionSpec extends AnyWordSpec with Matchers {
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
    val access=new BootstrapJournalAccess(kit.spawn(BootstrapJournal("step-unit-"+System.nanoTime())))
    val codec=new BootstrapRowCodec("""{"primaryKeyColumnNames":["id"],"columns":[{"name":"id","jdbcType":-5}]}""")
    val admission=new BootstrapAdmission(runners=2)
    val runner=new BootstrapRunner("step-unit","rows","generation","definition",capture,store,
      new BootstrapStore(()=>forbidden(),"step-unit",1048576),new BootstrapTarget(()=>forbidden(),"target_rows",codec,"step-unit"),
      access,new BootstrapSnapshot(()=>forbidden(),"rows",codec,32,1048576),
      new BootstrapReplaySink(_=>forbidden()),Node("sink","sink","mysql.cdc.apply","sink",Position(0,0),JsObject()),
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
  }
}
