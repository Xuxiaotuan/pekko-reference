package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.tags.ExternalIntegration
import cn.xuyinyin.magic.workflow.bootstrap.{BootstrapJournal,BootstrapJournalAccess}
import com.typesafe.config.ConfigFactory
import java.nio.file.{Files,Path}
import java.sql.DriverManager
import java.util.UUID
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.{Await,Future}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import spray.json._

/** Isolated Journal transition/restart only. It is not an unknown-B end-to-end test. */
class ManagedBootstrapJournalRecoverySpec extends AnyWordSpec with Matchers {
  "unknown-B Journal transition" should {
    "survive actor restarts in a real MySQL Journal" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_CDC_MANAGED_JOURNAL").contains("1"))cancel("dedicated MySQL required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").exists(_.nonEmpty))
      val url=sys.env("CDC_MANAGED_TEST_JDBC_URL");require(url.contains("/pekko_test_"),"dedicated schema required")
      val user=sys.env("CDC_MANAGED_TEST_USER");val password=sys.env("CDC_MANAGED_TEST_PASSWORD")
      val expected=sys.env("CAPTURE_EXPECT_STATE_UUID")
      val check=DriverManager.getConnection(url,user,password)
      try {val q=check.createStatement();try {val r=q.executeQuery("SELECT @@server_uuid");try {require(r.next() && r.getString(1)==expected)}finally r.close()}finally q.close()}finally check.close()
      val config=ConfigFactory.parseMap(Map[String,AnyRef](
        "pekko.persistence.journal.plugin"->"jdbc-journal","pekko.persistence.snapshot-store.plugin"->"jdbc-snapshot-store",
        "pekko-persistence-jdbc.shared-databases.slick.profile"->"slick.jdbc.MySQLProfile$",
        "pekko-persistence-jdbc.shared-databases.slick.db.driver"->"com.mysql.cj.jdbc.Driver",
        "pekko-persistence-jdbc.shared-databases.slick.db.url"->url,"pekko-persistence-jdbc.shared-databases.slick.db.user"->user,
        "pekko-persistence-jdbc.shared-databases.slick.db.password"->password,"pekko.remote.artery.canonical.port"->Int.box(0)).asJava)
        .withFallback(ConfigFactory.load("application-test"))
      val kit=ActorTestKit("cdc-b-journal-"+UUID.randomUUID().toString.take(8),config)
      implicit val ec:scala.concurrent.ExecutionContext=kit.system.executionContext
      implicit val scheduler:org.apache.pekko.actor.typed.Scheduler=kit.system.scheduler
      val id="b-real-"+UUID.randomUUID().toString
      def actor()=kit.spawn(BootstrapJournal(id,(p,_)=>Future.successful(p=="test-permit")))
      try {
        val first=actor();val access=new BootstrapJournalAccess(first)
        val paused=Await.result(access.update(_.copy(phase="PAUSED",definition="definition",executionId="execution",failure=Some("local_release_unconfirmed"))),15.seconds)
        kit.stop(first)
        val second=actor();val recovered=new BootstrapJournalAccess(second)
        Await.result(recovered.read(),15.seconds) shouldBe paused
        Await.result(recovered.resumePendingB(paused.revision,"execution","definition","invalid"),15.seconds) shouldBe "REJECTED"
        Await.result(recovered.resumePendingB(paused.revision,"execution","definition","test-permit"),15.seconds) shouldBe "ACCEPTED"
        val resumed=Await.result(recovered.read(),15.seconds);resumed.phase shouldBe "QUEUED"
        kit.stop(second)
        val third=actor();val after=Await.result(new BootstrapJournalAccess(third).read(),15.seconds);after shouldBe resumed
        val connection=DriverManager.getConnection(url,user,password)
        val journalRows=try {val q=connection.prepareStatement("SELECT sequence_number FROM event_journal WHERE persistence_id=? ORDER BY sequence_number")
          try {q.setString(1,"bootstrap-v1-"+id);val rs=q.executeQuery();val b=Vector.newBuilder[JsValue];try while(rs.next())b+=JsNumber(rs.getLong(1)) finally rs.close();b.result()}finally q.close()}finally connection.close()
        journalRows shouldBe Vector(JsNumber(1),JsNumber(2))
        val result=JsObject("scope"->JsString("real MySQL Actor Journal; injected permit validator; no guardian, Reader or Sink"),"serverUuid"->JsString(expected),
          "persistenceId"->JsString("bootstrap-v1-"+id),"pausedRevision"->JsNumber(paused.revision),"resumedRevision"->JsNumber(after.revision),
          "resumedPhase"->JsString(after.phase),"journalSequenceNumbers"->JsArray(journalRows),"invalidPermitRejected"->JsBoolean(true))
        val dir=Path.of(sys.env("CDC_MANAGED_JOURNAL_EVIDENCE"));Files.createDirectories(dir);Files.writeString(dir.resolve("result.json"),result.prettyPrint)
      } finally kit.shutdownTestKit()
    }
  }
}
