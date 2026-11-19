package cn.xuyinyin.magic.cdc.bootstrap

import cn.xuyinyin.magic.tags.ExternalIntegration
import com.typesafe.config.ConfigFactory
import java.nio.file.{Files, Path}
import java.sql.DriverManager
import java.util.UUID
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import spray.json._
import spray.json.DefaultJsonProtocol._

/** Replays an actual historical Journal event in an isolated MySQL Journal. */
class LegacyBootstrapJournalMySqlSpec extends AnyWordSpec with Matchers {
  "bootstrap class migration" should {
    "replay an old event, persist a new event, and recover again" taggedAs ExternalIntegration in {
      if (!sys.env.get("RUN_CDC_MANAGED_JOURNAL").contains("1")) cancel("dedicated MySQL required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").exists(_.nonEmpty))
      val url = sys.env("CDC_MANAGED_TEST_JDBC_URL")
      require(url.contains("/pekko_test_"), "dedicated schema required")
      val user = sys.env("CDC_MANAGED_TEST_USER")
      val password = sys.env("CDC_MANAGED_TEST_PASSWORD")
      val expectedUuid = sys.env("CAPTURE_EXPECT_STATE_UUID")
      val fixture = Path.of("docs/experiments/2026-11-05-cdc-flow-refactor/managed/C1-7bc6494fa216/03-recovered.json")
      val record = Files.readString(fixture).parseJson.asJsObject.fields("J").asInstanceOf[JsArray].elements.head.asJsObject.fields
      val id = "legacy-compat-" + UUID.randomUUID().toString
      val persistenceId = "bootstrap-v1-" + id
      val connection = DriverManager.getConnection(url, user, password)
      try {
        val check = connection.createStatement()
        try { val rows = check.executeQuery("SELECT @@server_uuid"); try { require(rows.next() && rows.getString(1) == expectedUuid) } finally rows.close() } finally check.close()
        val insert = connection.prepareStatement("INSERT INTO event_journal(persistence_id,sequence_number,writer,write_timestamp,adapter_manifest,event_payload,event_ser_id,event_ser_manifest) VALUES (?,1,'compat-fixture',0,'',?,?,?)")
        try {
          insert.setString(1, persistenceId)
          insert.setBytes(2, java.util.HexFormat.of.parseHex(record("payload_hex").convertTo[String]))
          insert.setInt(3, record("event_ser_id").convertTo[String].toInt)
          insert.setString(4, record("event_ser_manifest").convertTo[String])
          insert.executeUpdate() shouldBe 1
        } finally insert.close()
      } finally connection.close()
      val config = ConfigFactory.parseMap(Map[String, AnyRef](
        "pekko.persistence.journal.plugin" -> "jdbc-journal", "pekko.persistence.snapshot-store.plugin" -> "jdbc-snapshot-store",
        "pekko-persistence-jdbc.shared-databases.slick.profile" -> "slick.jdbc.MySQLProfile$",
        "pekko-persistence-jdbc.shared-databases.slick.db.driver" -> "com.mysql.cj.jdbc.Driver",
        "pekko-persistence-jdbc.shared-databases.slick.db.url" -> url,
        "pekko-persistence-jdbc.shared-databases.slick.db.user" -> user,
        "pekko-persistence-jdbc.shared-databases.slick.db.password" -> password,
        "pekko.remote.artery.canonical.port" -> Int.box(0)).asJava).withFallback(ConfigFactory.load("application-test"))
      val kit = ActorTestKit("legacy-bootstrap-mysql-" + UUID.randomUUID().toString.take(8), config)
      implicit val ec: scala.concurrent.ExecutionContext = kit.system.executionContext
      implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = kit.system.scheduler
      def actor() = kit.spawn(BootstrapJournal(id, (_, _) => Future.successful(false)))
      try {
        val first = actor()
        val firstAccess = new BootstrapJournalAccess(first)
        val old = Await.result(firstAccess.read(), 15.seconds)
        old.revision shouldBe 1L
        old.phase shouldBe "QUEUED"
        val next = Await.result(firstAccess.update(_.copy(phase = "PAUSED", failure = Some("compatibility-probe"))), 15.seconds)
        next.revision shouldBe 2L
        kit.stop(first)
        val second = actor()
        val recovered = Await.result(new BootstrapJournalAccess(second).read(), 15.seconds)
        recovered shouldBe next
        kit.stop(second)
        val evidence = JsObject("scope" -> JsString("isolated MySQL Actor Journal; historical event fixture; no Reader, guardian or Sink"),
          "serverUuid" -> JsString(expectedUuid), "persistenceId" -> JsString(persistenceId),
          "fixture" -> JsString(fixture.toString), "oldRevision" -> JsNumber(old.revision),
          "newRevision" -> JsNumber(next.revision), "recoveredRevision" -> JsNumber(recovered.revision))
        val dir = Path.of(sys.env("CDC_MANAGED_JOURNAL_EVIDENCE")); Files.createDirectories(dir)
        Files.writeString(dir.resolve("legacy-event-restart.json"), evidence.prettyPrint)
      } finally kit.shutdownTestKit()
    }
  }
}
