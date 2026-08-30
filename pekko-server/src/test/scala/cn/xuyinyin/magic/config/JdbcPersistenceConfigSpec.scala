package cn.xuyinyin.magic.config

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.persistence.jdbc.query.scaladsl.JdbcReadJournal
import org.apache.pekko.persistence.query.PersistenceQuery
import org.apache.pekko.stream.SystemMaterializer
import org.apache.pekko.stream.scaladsl.Sink
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Await
import scala.concurrent.duration._

class JdbcPersistenceConfigSpec extends AnyWordSpec with Matchers {
  "JDBC persistence configuration" should {
    "configure MySQL JDBC persistence for production" in {
      val prod = ConfigFactory.load("application-prod.conf")

      prod.getString("pekko.persistence.journal.plugin") shouldBe "jdbc-journal"
      prod.getString("pekko.persistence.snapshot-store.plugin") shouldBe "jdbc-snapshot-store"
      prod.getString("pekko-persistence-jdbc.shared-databases.slick.profile") shouldBe "slick.jdbc.MySQLProfile$"
      prod.getString("pekko-persistence-jdbc.shared-databases.slick.db.url") should startWith("jdbc:mysql:")
      sharedDatabasePlugins(prod) shouldBe Seq("slick", "slick", "slick")
      prod.getString("jdbc-journal.tables.event_journal.schemaName") shouldBe ""
      prod.getString("jdbc-snapshot-store.tables.snapshot.schemaName") shouldBe ""
      ConfigValidator.validate(prod) shouldBe None
    }

    "configure H2 JDBC persistence for tests" in {
      val test = ConfigFactory.load("application-test.conf")

      test.getString("pekko.persistence.journal.plugin") shouldBe "jdbc-journal"
      test.getString("pekko.persistence.snapshot-store.plugin") shouldBe "jdbc-snapshot-store"
      test.getString("pekko-persistence-jdbc.shared-databases.slick.profile") shouldBe "slick.jdbc.H2Profile$"
      test.getString("pekko-persistence-jdbc.shared-databases.slick.db.url") should startWith("jdbc:h2:mem:")
      test.getString("pekko-persistence-jdbc.shared-databases.slick.db.url") should not include "DB_CLOSE_DELAY"
      sharedDatabasePlugins(test) shouldBe Seq("slick", "slick", "slick")
    }

    "initialize the development H2 database from the bundled schema" in {
      val dev = ConfigFactory.load("application-dev.conf")

      dev.getString("pekko-persistence-jdbc.shared-databases.slick.profile") shouldBe "slick.jdbc.H2Profile$"
      dev.getString("pekko-persistence-jdbc.shared-databases.slick.db.url") should include("INIT=RUNSCRIPT FROM 'classpath:db/h2/pekko-persistence-schema.sql'")
      Option(getClass.getClassLoader.getResource("db/h2/pekko-persistence-schema.sql")) should not be empty
      ConfigValidator.validate(dev) shouldBe None
    }

    "configure the default application for the JDBC read path" in {
      val default = ConfigFactory.load()

      default.getString("pekko.persistence.journal.plugin") shouldBe "jdbc-journal"
      default.getString("pekko.persistence.snapshot-store.plugin") shouldBe "jdbc-snapshot-store"
      default.getString("pekko-persistence-jdbc.shared-databases.slick.profile") shouldBe "slick.jdbc.H2Profile$"
      default.getString("pekko-persistence-jdbc.shared-databases.slick.db.url") should startWith("jdbc:h2:file:")
      sharedDatabasePlugins(default) shouldBe Seq("slick", "slick", "slick")
      ConfigValidator.validate(default) shouldBe None
    }

    "serve persistence ids through the default JDBC read journal" in {
      val databaseName = s"default-jdbc-read-path-${System.nanoTime()}"
      val runtime = ConfigFactory.parseString(s"""
        pekko.actor.provider = local
        pekko-persistence-jdbc.shared-databases.slick.db.url =
          "jdbc:h2:mem:$databaseName;MODE=MySQL;INIT=RUNSCRIPT FROM 'classpath:db/h2/pekko-persistence-schema.sql'"
      """).withFallback(ConfigFactory.load())
      val system = ActorSystem[Nothing](Behaviors.empty, "default-jdbc-read-path", runtime)
      implicit val materializer = SystemMaterializer(system).materializer

      try {
        val readJournal = PersistenceQuery(system).readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)
        Await.result(readJournal.currentPersistenceIds().take(1).runWith(Sink.seq), 10.seconds) shouldBe empty
      } finally {
        system.terminate()
        Await.result(system.whenTerminated, 10.seconds)
      }
    }

    "reject inconsistent or incompatible JDBC settings" in {
      val mismatchedPlugins = ConfigFactory.parseString(
        "pekko.persistence.snapshot-store.plugin = pekko.persistence.snapshot-store.local"
      ).withFallback(ConfigFactory.load())
      val incompatibleDriver = ConfigFactory.parseString(
        "pekko-persistence-jdbc.shared-databases.slick.db.driver = com.mysql.cj.jdbc.Driver"
      ).withFallback(ConfigFactory.load("application-dev.conf"))

      ConfigValidator.validate(mismatchedPlugins).get should contain("JDBC journal and snapshot-store plugins must be configured together")
      ConfigValidator.validate(incompatibleDriver).get should contain("H2 Slick profile requires an H2 JDBC driver and jdbc:h2: URL")
    }
  }

  private def sharedDatabasePlugins(config: com.typesafe.config.Config): Seq[String] =
    Seq("jdbc-journal", "jdbc-snapshot-store", "jdbc-read-journal").map { plugin =>
      config.getString(s"$plugin.use-shared-db")
    }
}
