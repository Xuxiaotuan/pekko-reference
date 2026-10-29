package cn.xuyinyin.magic.workflow.bootstrap
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import com.typesafe.config.ConfigFactory
import scala.concurrent.Await
import scala.concurrent.duration._
class BootstrapJournalSpec extends AnyWordSpec with Matchers {
  "bootstrap Journal" should {
    "resume only a matching, uninitialized unknown-B state" in {
      val kit=ActorTestKit("bootstrap-b-resume-test",ConfigFactory.parseString("""pekko.persistence.journal.plugin="pekko.persistence.journal.inmem"
pekko.persistence.snapshot-store.plugin="pekko.persistence.snapshot-store.local"
pekko.persistence.snapshot-store.local.dir="target/bootstrap-b-resume-snapshots"""").withFallback(ConfigFactory.load("application-test")))
      implicit val ec:scala.concurrent.ExecutionContext=kit.system.executionContext
      implicit val scheduler:org.apache.pekko.actor.typed.Scheduler=kit.system.scheduler
      try {
        val access=new BootstrapJournalAccess(kit.spawn(BootstrapJournal("resume-unit-"+System.nanoTime(),(p,_)=>scala.concurrent.Future.successful(p=="permit-1"))))
        val paused=Await.result(access.update(_.copy(phase="PAUSED",definition="sealed",executionId="execution",failure=Some("local_release_unconfirmed"))),10.seconds)
        Await.result(access.resumePendingB(paused.revision,"wrong","sealed","permit-1"),10.seconds) shouldBe "REJECTED"
        Await.result(access.resumePendingB(paused.revision,"execution","sealed","bad-permit"),10.seconds) shouldBe "REJECTED"
        Await.result(access.read(),10.seconds).phase shouldBe "PAUSED"
        Await.result(access.resumePendingB(paused.revision,"execution","sealed","permit-1"),10.seconds) shouldBe "ACCEPTED"
        val resumed=Await.result(access.read(),10.seconds)
        resumed.phase shouldBe "QUEUED";resumed.executionId shouldBe "execution";resumed.revision shouldBe paused.revision+1
        Await.result(access.resumePendingB(paused.revision,"execution","sealed","permit-1"),10.seconds) shouldBe "REJECTED"
      } finally kit.shutdownTestKit()
    }
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
