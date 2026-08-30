package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.tags.ExternalIntegration
import cn.xuyinyin.magic.workflow.WorkflowFixtures
import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngine
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

import java.sql.{Connection, DriverManager}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class MySQLPersistenceRecoverySpec extends AnyWordSpec with Matchers with Eventually {
  implicit val patience: PatienceConfig = PatienceConfig(timeout = Span(45, Seconds), interval = Span(250, Millis))

  "MySQL JDBC persistence" should {
    "write journal and snapshot rows and recover from an isolated test schema" taggedAs ExternalIntegration in {
      val settings = MySQLPersistenceRecoverySpec.settingsFromEnvironment().getOrElse {
        cancel(
          "external_blocked: set PEKKO_TEST_MYSQL_JDBC_URL, PEKKO_TEST_MYSQL_SCHEMA, " +
            "PEKKO_TEST_MYSQL_USER, and PEKKO_TEST_MYSQL_PASSWORD for a dedicated pekko_test_* schema"
        )
      }
      MySQLPersistenceRecoverySpec.validateIsolation(settings) match {
        case Left(reason) => cancel(s"external_blocked: $reason")
        case Right(_) =>
      }

      Class.forName("com.mysql.cj.jdbc.Driver")
      val bootstrapConnection = DriverManager.getConnection(settings.jdbcUrl, settings.user, settings.password)
      try {
        MySQLPersistenceRecoverySpec.requireSchemaTables(bootstrapConnection)
      } finally bootstrapConnection.close()

      val configValues = Map[String, AnyRef](
        "pekko-persistence-jdbc.shared-databases.slick.profile" -> "slick.jdbc.MySQLProfile$",
        "pekko-persistence-jdbc.shared-databases.slick.db.driver" -> "com.mysql.cj.jdbc.Driver",
        "pekko-persistence-jdbc.shared-databases.slick.db.url" -> settings.jdbcUrl,
        "pekko-persistence-jdbc.shared-databases.slick.db.user" -> settings.user,
        "pekko-persistence-jdbc.shared-databases.slick.db.password" -> settings.password,
        "pekko.remote.artery.canonical.hostname" -> "127.0.0.1",
        "pekko.remote.artery.canonical.port" -> Int.box(0)
      )
      val config = ConfigFactory.parseMap(configValues.asJava).withFallback(ConfigFactory.load("application-test"))
      val testKit = ActorTestKit(s"task8-mysql-${java.util.UUID.randomUUID()}", config)

      try {
        implicit val executionContext = testKit.system.executionContext
        val engine = new WorkflowExecutionEngine()(testKit.system, executionContext)
        val workflowId = s"mysql-${java.util.UUID.randomUUID()}"
        val persistenceId = s"workflow-$workflowId"
        val replies = testKit.createTestProbe[EventSourcedWorkflowActor.Reply]()
        val before = testKit.spawn(EventSourcedWorkflowActor(workflowId, engine), "mysql-persistence-before")

        (0L to 100L).foreach { expectedRevision =>
          before ! EventSourcedWorkflowActor.DefineWorkflow(WorkflowFixtures.linearWorkflow, expectedRevision, replies.ref)
          replies.expectMessage(EventSourcedWorkflowActor.Defined(workflowId, expectedRevision + 1L))
        }

        eventually {
          val connection = DriverManager.getConnection(settings.jdbcUrl, settings.user, settings.password)
          try {
            MySQLPersistenceRecoverySpec.rowCount(connection, "event_journal", persistenceId) should be > 0L
            MySQLPersistenceRecoverySpec.rowCount(connection, "snapshot", persistenceId) should be > 0L
          } finally connection.close()
        }

        testKit.stop(before)
        val recovered = testKit.spawn(EventSourcedWorkflowActor(workflowId, engine), "mysql-persistence-after")
        val summaryReply = testKit.createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
        recovered ! EventSourcedWorkflowActor.GetSummary(summaryReply.ref)
        summaryReply.receiveMessage(10.seconds).revision shouldBe 101L
      } finally testKit.shutdownTestKit()
    }
  }
}

private object MySQLPersistenceRecoverySpec {
  final case class Settings(jdbcUrl: String, schema: String, user: String, password: String)
  private val JdbcUrl = "^jdbc:mysql://[^/]+/([^?;]+)(?:[?;].*)?$".r

  def settingsFromEnvironment(): Option[Settings] = for {
    jdbcUrl <- sys.env.get("PEKKO_TEST_MYSQL_JDBC_URL").filter(_.nonEmpty)
    schema <- sys.env.get("PEKKO_TEST_MYSQL_SCHEMA").filter(_.nonEmpty)
    user <- sys.env.get("PEKKO_TEST_MYSQL_USER").filter(_.nonEmpty)
    password <- sys.env.get("PEKKO_TEST_MYSQL_PASSWORD")
  } yield Settings(jdbcUrl, schema, user, password)

  def validateIsolation(settings: Settings): Either[String, Unit] = {
    val schemaInUrl = settings.jdbcUrl match {
      case JdbcUrl(schema) => Some(schema)
      case _ => None
    }
    if (!settings.schema.startsWith("pekko_test_"))
      Left("PEKKO_TEST_MYSQL_SCHEMA must start with pekko_test_; user or shared application databases are refused")
    else if (!schemaInUrl.contains(settings.schema))
      Left("PEKKO_TEST_MYSQL_JDBC_URL must target exactly PEKKO_TEST_MYSQL_SCHEMA")
    else Right(())
  }

  def requireSchemaTables(connection: Connection): Unit = {
    Vector("event_journal", "event_tag", "snapshot").foreach { table =>
      val statement = connection.prepareStatement(
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?"
      )
      try {
        statement.setString(1, connection.getCatalog)
        statement.setString(2, table)
        val result = statement.executeQuery()
        try {
          result.next()
          require(result.getInt(1) == 1, s"required table $table is missing; initialize the dedicated schema first")
        } finally result.close()
      } finally statement.close()
    }
  }

  def rowCount(connection: Connection, table: String, persistenceId: String): Long = {
    require(table == "event_journal" || table == "snapshot", s"unexpected table: $table")
    val statement = connection.prepareStatement(s"SELECT COUNT(*) FROM `$table` WHERE persistence_id = ?")
    try {
      statement.setString(1, persistenceId)
      val result = statement.executeQuery()
      try {
        result.next()
        result.getLong(1)
      } finally result.close()
    } finally statement.close()
  }
}
