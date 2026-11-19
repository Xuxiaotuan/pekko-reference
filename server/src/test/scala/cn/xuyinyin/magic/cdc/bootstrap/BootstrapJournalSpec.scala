package cn.xuyinyin.magic.cdc.bootstrap
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import com.typesafe.config.ConfigFactory
import scala.concurrent.Await
import scala.concurrent.duration._
class BootstrapJournalSpec extends AnyWordSpec with Matchers {
  "bootstrap Journal" should {
    "persist a validated receipt binding and stop issuing evidence after cancellation" in {
      val kit=ActorTestKit("bootstrap-receipt-binding",ConfigFactory.parseString("""pekko.persistence.journal.plugin="pekko.persistence.journal.inmem"
pekko.persistence.snapshot-store.plugin="pekko.persistence.snapshot-store.local"
pekko.persistence.snapshot-store.local.dir="target/bootstrap-receipt-binding-snapshots"""").withFallback(ConfigFactory.load("application-test")))
      implicit val ec:scala.concurrent.ExecutionContext=kit.system.executionContext
      implicit val scheduler:org.apache.pekko.actor.typed.Scheduler=kit.system.scheduler
      val id="receipt-"+System.nanoTime();val digest="a"*64;val consumed=new java.util.concurrent.atomic.AtomicBoolean(false)
      def actor()=kit.spawn(BootstrapJournal(id,validateReceipt=(p,d,_)=>scala.concurrent.Future.successful(p=="permit" && d==digest),
        validateConsumed=(p,d,_)=>scala.concurrent.Future.successful(p=="permit" && d==digest && consumed.get())))
      try {
        val first=actor();val a=new BootstrapJournalAccess(first)
        val paused=Await.result(a.update(_.copy(phase="PAUSED",definition="sealed",executionId="exec",failure=Some("local_release_unconfirmed"))),10.seconds)
        Await.result(a.resumePendingBWithReceipt(paused.revision,"exec","sealed","permit","b"*64),10.seconds) shouldBe "REJECTED"
        Await.result(a.persistedRecoveryReceipt(),10.seconds) shouldBe None
        Await.result(a.resumePendingBWithReceipt(paused.revision,"exec","sealed","permit",digest),10.seconds) shouldBe "ACCEPTED"
        val evidence=Await.result(a.persistedRecoveryReceipt(),10.seconds).get
        evidence.persistenceId shouldBe "bootstrap-v1-"+id;evidence.receiptDigest shouldBe digest;evidence.permitId shouldBe "permit"
        evidence.phase shouldBe "RECOVERY_PENDING"
        intercept[cn.xuyinyin.magic.cdc.recovery.CdcExecutionException](Await.result(a.update(_.copy(recoveryReceiptDigest=None)),10.seconds))
        kit.stop(first)
        val b=new BootstrapJournalAccess(actor());Await.result(b.persistedRecoveryReceipt(),10.seconds).get.receiptDigest shouldBe digest
        Await.result(b.cancel(),10.seconds).phase shouldBe "RECOVERY_PENDING"
        val pending=Await.result(b.read(),10.seconds)
        Await.result(b.completeRecoveryReceipt(pending.revision,"permit",digest),10.seconds) shouldBe "REJECTED"
        consumed.set(true)
        Await.result(b.completeRecoveryReceipt(pending.revision,"permit",digest),10.seconds) shouldBe "ACCEPTED"
        Await.result(b.persistedRecoveryReceipt(),10.seconds) shouldBe None
        Await.result(b.read(),10.seconds).recoveryReceiptDigest shouldBe Some(digest)
        Await.result(b.cancel(),10.seconds).phase shouldBe "CANCELING"
      } finally kit.shutdownTestKit()
    }
    "persist the exact accepted permit and validate replacement permits before rebinding" in {
      val kit=ActorTestKit("bootstrap-permit-binding",ConfigFactory.parseString("""pekko.persistence.journal.plugin="pekko.persistence.journal.inmem"
pekko.persistence.snapshot-store.plugin="pekko.persistence.snapshot-store.local"
pekko.persistence.snapshot-store.local.dir="target/bootstrap-permit-binding-snapshots"""").withFallback(ConfigFactory.load("application-test")))
      implicit val ec:scala.concurrent.ExecutionContext=kit.system.executionContext
      implicit val scheduler:org.apache.pekko.actor.typed.Scheduler=kit.system.scheduler
      val id="binding-unit-"+System.nanoTime()
      def binding(s:BootstrapProgress):Option[String]=s.productElementNames.zip(s.productIterator).toMap.get("recoveryPermit") match {
        case Some(value:Option[_])=>value.map(_.toString)
        case _=>None
      }
      def actor()=kit.spawn(BootstrapJournal(id,(p,_)=>scala.concurrent.Future.successful(Set("permit-1","permit-2")(p))))
      try {
        val first=actor();val access=new BootstrapJournalAccess(first)
        val paused=Await.result(access.update(_.copy(phase="PAUSED",definition="sealed",executionId="execution",failure=Some("local_release_unconfirmed"))),10.seconds)
        Await.result(access.resumePendingB(paused.revision,"execution","sealed","permit-1"),10.seconds) shouldBe "ACCEPTED"
        val accepted=Await.result(access.read(),10.seconds)
        binding(accepted) shouldBe Some("permit-1")
        val tampered=intercept[cn.xuyinyin.magic.cdc.recovery.CdcExecutionException] {
          Await.result(access.update(_.copy(recoveryPermit=None)),10.seconds)
        }
        tampered.failure.code shouldBe "checkpoint_rejected"
        kit.stop(first)
        val second=actor();val recovered=new BootstrapJournalAccess(second);val before=Await.result(recovered.read(),10.seconds)
        binding(before) shouldBe Some("permit-1")
        Await.result(recovered.resumePendingB(before.revision,"execution","sealed","bad-permit"),10.seconds) shouldBe "REJECTED"
        binding(Await.result(recovered.read(),10.seconds)) shouldBe Some("permit-1")
        Await.result(recovered.resumePendingB(before.revision,"execution","sealed","permit-2"),10.seconds) shouldBe "ACCEPTED"
        val rebound=Await.result(recovered.read(),10.seconds)
        binding(rebound) shouldBe Some("permit-2")
        rebound.revision shouldBe before.revision+1
        kit.stop(second)
        val finalAccess=new BootstrapJournalAccess(actor())
        binding(Await.result(finalAccess.read(),10.seconds)) shouldBe Some("permit-2")
        val canceled=Await.result(finalAccess.cancel(),10.seconds)
        canceled.phase shouldBe "CANCELING"
        binding(canceled) shouldBe Some("permit-2")
      }finally kit.shutdownTestKit()
    }
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
        val e=intercept[cn.xuyinyin.magic.cdc.recovery.CdcExecutionException](Await.result(access.update(_.copy(phase="STREAMING")),10.seconds))
        e.failure.code shouldBe "checkpoint_rejected"
        Await.result(access.read(),10.seconds).recoveryAttempts shouldBe 0
        Await.result(access.update(_.copy(phase="SCANNING",definition="sealed",executionId="execution")),10.seconds)
        Await.result(access.update(_.copy(recoveryAttempts=2)),10.seconds)
        intercept[cn.xuyinyin.magic.cdc.recovery.CdcExecutionException](Await.result(access.update(_.copy(recoveryAttempts=0)),10.seconds))
        Await.result(access.cancel(),10.seconds).phase shouldBe "CANCELING"
        Await.result(access.update(_.copy(phase="SCANNING")),10.seconds).phase shouldBe "CANCELING"
        Await.result(access.update(_.copy(phase="CANCELED")),10.seconds).phase shouldBe "CANCELED"
        intercept[cn.xuyinyin.magic.cdc.recovery.CdcExecutionException](Await.result(access.update(_.copy(phase="STREAMING")),10.seconds))
      }finally kit.shutdownTestKit()
    }
  }
}
