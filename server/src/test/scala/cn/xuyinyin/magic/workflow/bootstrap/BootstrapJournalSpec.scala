package cn.xuyinyin.magic.workflow.bootstrap
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import com.typesafe.config.ConfigFactory
import scala.concurrent.Await
import scala.concurrent.duration._
class BootstrapJournalSpec extends AnyWordSpec with Matchers {
  "bootstrap Journal" should {
    "reject illegal activation without spending recovery budget and keep cancellation sticky" in {
      val kit=ActorTestKit("bootstrap-journal-test",ConfigFactory.parseString("""pekko.persistence.journal.plugin="pekko.persistence.journal.inmem"
pekko.persistence.snapshot-store.plugin="pekko.persistence.snapshot-store.local"
pekko.persistence.snapshot-store.local.dir="target/bootstrap-journal-snapshots"""").withFallback(ConfigFactory.load("application-test")))
      implicit val ec:scala.concurrent.ExecutionContext=kit.system.executionContext
      implicit val scheduler:org.apache.pekko.actor.typed.Scheduler=kit.system.scheduler
      try {
        val ref=kit.spawn(BootstrapJournal("unit-"+System.nanoTime()));val access=new BootstrapJournalAccess(ref)
        val e=intercept[cn.xuyinyin.magic.workflow.engine.CdcExecutionException](Await.result(access.update(_.copy(phase="STREAMING")),10.seconds))
        e.failure.code shouldBe "checkpoint_rejected"
        Await.result(access.read(),10.seconds).recoveryAttempts shouldBe 0
        Await.result(access.update(_.copy(phase="SCANNING",definition="sealed",executionId="execution")),10.seconds)
        Await.result(access.update(_.copy(recoveryAttempts=2)),10.seconds)
        intercept[cn.xuyinyin.magic.workflow.engine.CdcExecutionException](Await.result(access.update(_.copy(recoveryAttempts=0)),10.seconds))
        Await.result(access.cancel(),10.seconds).phase shouldBe "CANCELING"
        Await.result(access.update(_.copy(phase="SCANNING")),10.seconds).phase shouldBe "CANCELING"
        Await.result(access.update(_.copy(phase="CANCELED")),10.seconds).phase shouldBe "CANCELED"
        intercept[cn.xuyinyin.magic.workflow.engine.CdcExecutionException](Await.result(access.update(_.copy(phase="STREAMING")),10.seconds))
      }finally kit.shutdownTestKit()
    }
  }
}
