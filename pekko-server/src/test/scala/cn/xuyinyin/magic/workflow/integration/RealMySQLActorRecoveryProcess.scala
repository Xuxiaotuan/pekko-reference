package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.workflow.WorkflowFixtures
import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngine
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit

import java.sql.{Connection, DriverManager}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/**
 * Separate-JVM helper for the real MySQL journal/snapshot recovery check.
 * Invoke `write-crash` and `recover` in two distinct `sbt Test/runMain` processes.
 */
object RealMySQLActorRecoveryProcess {
  private final case class Settings(jdbcUrl: String, user: String, password: String, workflowId: String) {
    val persistenceId: String = s"workflow-$workflowId"
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 1 && Set("write-crash", "recover").contains(args.head), "expected mode: write-crash or recover")
    val settings = settingsFromSystemProperties()
    val mode = args.head
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
    val processId = ProcessHandle.current().pid()
    val testKit = ActorTestKit(s"real-mysql-actor-$mode-$processId", config)

    try {
      implicit val executionContext = testKit.system.executionContext
      val engine = new WorkflowExecutionEngine()(testKit.system, executionContext)
      val actor = testKit.spawn(EventSourcedWorkflowActor(settings.workflowId, engine), s"workflow-$mode")

      mode match {
        case "write-crash" =>
          writeAndSnapshot(testKit, actor, settings, processId)
          System.out.flush()
          Runtime.getRuntime.halt(23)
        case "recover" => recoverAndAdvance(testKit, actor, settings, processId)
      }
    } finally testKit.shutdownTestKit()
  }

  private def writeAndSnapshot(
    testKit: ActorTestKit,
    actor: org.apache.pekko.actor.typed.ActorRef[EventSourcedWorkflowActor.Command],
    settings: Settings,
    processId: Long
  ): Unit = {
    val replies = testKit.createTestProbe[EventSourcedWorkflowActor.Reply]()
    (0L until 101L).foreach { expectedRevision =>
      actor ! EventSourcedWorkflowActor.DefineWorkflow(WorkflowFixtures.linearWorkflow, expectedRevision, replies.ref)
      replies.expectMessage(EventSourcedWorkflowActor.Defined(settings.workflowId, expectedRevision + 1L))
    }

    awaitDatabase(settings) { connection =>
      journalMaxSequence(connection, settings.persistenceId) >= 101L &&
        snapshotMaxSequence(connection, settings.persistenceId) >= 100L
    }
    val counts = databaseState(settings)
    require(counts._1 >= 101L, s"journal sequence did not reach 101: ${counts._1}")
    require(counts._2 >= 100L, s"snapshot sequence did not reach 100: ${counts._2}")
    println(s"REAL_MYSQL_PROCESS_WRITE_OK pid=$processId revision=101 journal=${counts._1} snapshot=${counts._2}")
  }

  private def recoverAndAdvance(
    testKit: ActorTestKit,
    actor: org.apache.pekko.actor.typed.ActorRef[EventSourcedWorkflowActor.Command],
    settings: Settings,
    processId: Long
  ): Unit = {
    val summaryReply = testKit.createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
    actor ! EventSourcedWorkflowActor.GetSummary(summaryReply.ref)
    val recovered = summaryReply.receiveMessage(20.seconds)
    require(recovered.revision == 101L, s"expected recovered revision 101, got ${recovered.revision}")

    val defineReply = testKit.createTestProbe[EventSourcedWorkflowActor.Reply]()
    actor ! EventSourcedWorkflowActor.DefineWorkflow(WorkflowFixtures.linearWorkflow, 101L, defineReply.ref)
    defineReply.expectMessage(EventSourcedWorkflowActor.Defined(settings.workflowId, 102L))

    awaitDatabase(settings)(connection => journalMaxSequence(connection, settings.persistenceId) >= 102L)
    val counts = databaseState(settings)
    require(counts._1 >= 102L, s"journal sequence did not reach 102: ${counts._1}")
    require(counts._2 >= 100L, s"recovery lost the existing snapshot: ${counts._2}")
    println(s"REAL_MYSQL_PROCESS_RECOVER_OK pid=$processId recovered=101 advanced=102 journal=${counts._1} snapshot=${counts._2}")
  }

  private def settingsFromSystemProperties(): Settings = {
    def required(name: String): String =
      sys.props.get(name).filter(_.nonEmpty).getOrElse(throw new IllegalArgumentException(s"missing system property: $name"))

    Settings(
      jdbcUrl = required("pekko.test.mysql.jdbc-url"),
      user = required("pekko.test.mysql.user"),
      password = sys.props.getOrElse("pekko.test.mysql.password", throw new IllegalArgumentException("missing system property: pekko.test.mysql.password")),
      workflowId = required("pekko.test.mysql.workflow-id")
    )
  }

  private def awaitDatabase(settings: Settings)(condition: Connection => Boolean): Unit = {
    val deadline = 20.seconds.fromNow
    var satisfied = false
    var lastFailure: Throwable = null
    while (!satisfied && deadline.hasTimeLeft()) {
      try satisfied = withConnection(settings)(condition)
      catch { case failure: Throwable => lastFailure = failure }
      if (!satisfied) Thread.sleep(100L)
    }
    if (!satisfied) {
      if (lastFailure != null) throw new AssertionError("database condition was not satisfied", lastFailure)
      throw new AssertionError("database condition was not satisfied before timeout")
    }
  }

  private def databaseState(settings: Settings): (Long, Long) =
    withConnection(settings) { connection =>
      journalMaxSequence(connection, settings.persistenceId) -> snapshotMaxSequence(connection, settings.persistenceId)
    }

  private def journalMaxSequence(connection: Connection, persistenceId: String): Long =
    maxSequence(connection, "event_journal", persistenceId)

  private def snapshotMaxSequence(connection: Connection, persistenceId: String): Long =
    maxSequence(connection, "snapshot", persistenceId)

  private def maxSequence(connection: Connection, table: String, persistenceId: String): Long = {
    require(table == "event_journal" || table == "snapshot", s"unexpected table: $table")
    val statement = connection.prepareStatement(s"SELECT COALESCE(MAX(sequence_number), 0) FROM `$table` WHERE persistence_id = ?")
    try {
      statement.setString(1, persistenceId)
      val resultSet = statement.executeQuery()
      try {
        resultSet.next()
        resultSet.getLong(1)
      } finally resultSet.close()
    } finally statement.close()
  }

  private def withConnection[A](settings: Settings)(operation: Connection => A): A = {
    Class.forName("com.mysql.cj.jdbc.Driver")
    val connection = DriverManager.getConnection(settings.jdbcUrl, settings.user, settings.password)
    try operation(connection)
    finally connection.close()
  }
}
