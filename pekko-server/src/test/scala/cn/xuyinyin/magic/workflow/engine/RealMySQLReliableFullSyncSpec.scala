package cn.xuyinyin.magic.workflow.engine

import cn.xuyinyin.magic.tags.ExternalIntegration
import cn.xuyinyin.magic.workflow.checkpoint.{AlreadyCommitted, BatchCheckpoint, BatchCommitResult, Committed, SnapshotBoundary, SourceBatch}
import cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.model.WorkflowDSL.{Edge, Node, Position, Workflow, WorkflowMetadata}
import cn.xuyinyin.magic.workflow.nodes.sinks.MySQLSinkNode
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json.{JsArray, JsNumber, JsObject, JsString}

import java.sql.{Connection, DriverManager}
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.io.Source
import scala.jdk.CollectionConverters._

object RealMySQLReliableFullSyncSpec {
  private final case class Settings(host: String, port: Int, user: String, password: String) {
    val adminUrl: String =
      s"jdbc:mysql://$host:$port/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"

    def schemaUrl(schema: String): String =
      s"jdbc:mysql://$host:$port/$schema?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
  }

  private object Settings {
    def fromSystemProperties(): Option[Settings] = for {
      host <- sys.props.get("pekko.test.mysql.host").filter(_.nonEmpty)
      port <- sys.props.get("pekko.test.mysql.port").flatMap(_.toIntOption)
      user <- sys.props.get("pekko.test.mysql.user").filter(_.nonEmpty)
      password <- sys.props.get("pekko.test.mysql.password")
    } yield Settings(host, port, user, password)
  }

  private final case class CommitObservation(sequence: Long, outcome: String)
  private final case class LedgerRow(sequence: Long, cursor: String)
}

class RealMySQLReliableFullSyncSpec extends AnyWordSpec with Matchers {
  import RealMySQLReliableFullSyncSpec._

  "the reliable full-sync path" should {
    "replay a target-only committed batch against an isolated real MySQL schema" taggedAs ExternalIntegration in {
      val settings = Settings.fromSystemProperties().getOrElse {
        cancel("set pekko.test.mysql.{host,port,user,password} system properties")
      }
      val schema = s"pekko_test_reliable_${UUID.randomUUID().toString.replace("-", "").take(12)}"
      val workflowId = s"real-mysql-${UUID.randomUUID()}"
      val executionId = s"execution-${UUID.randomUUID()}"
      val sourceRows = Vector(1L -> "row-1", 2L -> "row-2", 5L -> "row-5", 9L -> "row-9", 12L -> "row-12")
      val boundaryRef = new AtomicReference[SnapshotBoundary]()
      val failFirstCheckpoint = new AtomicBoolean(true)
      val recoveredCheckpoints = new ConcurrentLinkedQueue[BatchCheckpoint]()
      val observingSink = new ObservingMySQLSink
      var testKit: ActorTestKit = null

      createSchema(settings, schema)
      try {
        initializeSchema(settings, schema, sourceRows)
        NodeRegistry.registerSink(observingSink)
        testKit = ActorTestKit(s"real-mysql-reliable-${UUID.randomUUID()}")
        implicit val executionContext: ExecutionContext = testKit.system.executionContext
        val engine = new WorkflowExecutionEngine()(testKit.system, executionContext)
        val workflow = fullSyncWorkflow(workflowId, settings, schema)

        val interrupted = Await.result(
          engine.execute(
            workflow,
            ReliableRunContext(
              executionId = executionId,
              workflowRevision = 1L,
              boundary = None,
              checkpoints = Vector.empty,
              initializeBoundary = boundary => {
                boundaryRef.compareAndSet(null, boundary) shouldBe true
                Future.successful(Done)
              },
              checkpointCommitted = checkpoint =>
                if (checkpoint.batchSequence == 0L && failFirstCheckpoint.compareAndSet(true, false))
                  Future.failed(new RuntimeException("injected failure after real MySQL target commit"))
                else Future.successful(Done)
            ),
            _ => ()
          ),
          60.seconds
        )

        interrupted.success shouldBe false
        Option(boundaryRef.get()).flatMap(_.upperBound) shouldBe Some("12")
        targetIdsAndCounts(settings, schema) shouldBe Vector(1L -> 1L, 2L -> 1L)
        ledgerRows(settings, schema) shouldBe Vector(LedgerRow(0L, "2"))
        observingSink.observations shouldBe Vector(CommitObservation(0L, "committed"))

        val recovered = Await.result(
          engine.execute(
            workflow,
            ReliableRunContext(
              executionId = executionId,
              workflowRevision = 1L,
              boundary = Option(boundaryRef.get()),
              checkpoints = Vector.empty,
              initializeBoundary = _ => Future.failed(new AssertionError("persisted boundary must be reused")),
              checkpointCommitted = checkpoint => {
                recoveredCheckpoints.add(checkpoint)
                Future.successful(Done)
              }
            ),
            _ => ()
          ),
          60.seconds
        )

        recovered.success shouldBe true
        targetIdsAndCounts(settings, schema) shouldBe sourceRows.map(_._1 -> 1L)
        ledgerRows(settings, schema) shouldBe Vector(
          LedgerRow(0L, "2"),
          LedgerRow(1L, "9"),
          LedgerRow(2L, "12")
        )
        recoveredCheckpoints.iterator().asScala.map(_.batchSequence).toVector shouldBe Vector(0L, 1L, 2L)
        observingSink.observations shouldBe Vector(
          CommitObservation(0L, "committed"),
          CommitObservation(0L, "already_committed"),
          CommitObservation(1L, "committed"),
          CommitObservation(2L, "committed")
        )
      } finally {
        if (testKit != null) testKit.shutdownTestKit()
        NodeRegistry.unregisterSink(observingSink.nodeType, observingSink)
        dropSchema(settings, schema)
      }
    }
  }

  private final class ObservingMySQLSink extends MySQLSinkNode {
    private val commits = new ConcurrentLinkedQueue[CommitObservation]()

    def observations: Vector[CommitObservation] = commits.iterator().asScala.toVector

    override def commitBatch(
      node: Node,
      workflowId: String,
      executionId: String,
      batch: SourceBatch,
      transformedRows: Vector[String],
      onLog: String => Unit
    )(implicit blockingEc: ExecutionContext): Future[BatchCommitResult] =
      super.commitBatch(node, workflowId, executionId, batch, transformedRows, onLog).map { result =>
        val outcome = result match {
          case Committed(_) => "committed"
          case AlreadyCommitted(_) => "already_committed"
        }
        commits.add(CommitObservation(batch.batchSequence, outcome))
        result
      }(blockingEc)
  }

  private def fullSyncWorkflow(workflowId: String, settings: Settings, schema: String): Workflow = {
    val connection = JsObject(
      "host" -> JsString(settings.host),
      "port" -> JsNumber(settings.port),
      "database" -> JsString(schema),
      "username" -> JsString(settings.user),
      "password" -> JsString(settings.password)
    )
    val source = Node(
      "source-1",
      "source",
      "mysql.snapshot",
      "Real MySQL snapshot source",
      Position(0, 0),
      JsObject(connection.fields ++ Map(
        "table" -> JsString("source_rows"),
        "columns" -> JsArray(JsString("id"), JsString("payload")),
        "primaryKey" -> JsString("id"),
        "chunkSize" -> JsNumber(2)
      ))
    )
    val sink = Node(
      "sink-1",
      "sink",
      "mysql.write",
      "Real MySQL idempotent sink",
      Position(1, 0),
      JsObject(connection.fields ++ Map(
        "table" -> JsString("sink_rows"),
        "batchSize" -> JsNumber(2),
        "mode" -> JsString("insert")
      ))
    )
    Workflow(
      workflowId,
      "real MySQL reliable full sync",
      "real driver target-commit/checkpoint-gap recovery",
      "1",
      "test",
      Nil,
      List(source, sink),
      List(Edge("source-to-sink", source.id, sink.id)),
      WorkflowMetadata("2026-08-30", "2026-08-30")
    )
  }

  private def createSchema(settings: Settings, schema: String): Unit = {
    require(schema.matches("pekko_test_reliable_[a-f0-9]{12}"), s"unsafe test schema: $schema")
    withConnection(settings.adminUrl, settings) { connection =>
      val statement = connection.createStatement()
      try statement.executeUpdate(s"CREATE DATABASE `$schema`")
      finally statement.close()
    }
  }

  private def initializeSchema(settings: Settings, schema: String, rows: Vector[(Long, String)]): Unit =
    withConnection(settings.schemaUrl(schema), settings) { connection =>
      val statement = connection.createStatement()
      try {
        statement.executeUpdate("CREATE TABLE source_rows (id BIGINT UNSIGNED PRIMARY KEY, payload VARCHAR(255) NOT NULL)")
        statement.executeUpdate("CREATE TABLE sink_rows (id BIGINT UNSIGNED PRIMARY KEY, payload VARCHAR(255) NOT NULL)")
      } finally statement.close()
      executeLedgerSchema(connection)

      val insert = connection.prepareStatement("INSERT INTO source_rows (id, payload) VALUES (?, ?)")
      try {
        rows.foreach { case (id, payload) =>
          insert.setLong(1, id)
          insert.setString(2, payload)
          insert.addBatch()
        }
        insert.executeBatch()
      } finally insert.close()
    }

  private def executeLedgerSchema(connection: Connection): Unit = {
    val input = Option(getClass.getClassLoader.getResourceAsStream("db/mysql/pekko-sync-ledger-schema.sql"))
      .getOrElse(throw new IllegalStateException("missing MySQL ledger schema resource"))
    val sql = try Source.fromInputStream(input).mkString finally input.close()
    val statement = connection.createStatement()
    try sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(statement.execute)
    finally statement.close()
  }

  private def targetIdsAndCounts(settings: Settings, schema: String): Vector[(Long, Long)] =
    withConnection(settings.schemaUrl(schema), settings) { connection =>
      val statement = connection.createStatement()
      try {
        val resultSet = statement.executeQuery("SELECT id, COUNT(*) FROM sink_rows GROUP BY id ORDER BY id")
        try {
          val result = Vector.newBuilder[(Long, Long)]
          while (resultSet.next()) result += resultSet.getLong(1) -> resultSet.getLong(2)
          result.result()
        } finally resultSet.close()
      } finally statement.close()
    }

  private def ledgerRows(settings: Settings, schema: String): Vector[LedgerRow] =
    withConnection(settings.schemaUrl(schema), settings) { connection =>
      val statement = connection.createStatement()
      try {
        val resultSet = statement.executeQuery(
          "SELECT batch_sequence, cursor_value FROM pekko_sync_batch_ledger ORDER BY batch_sequence"
        )
        try {
          val result = Vector.newBuilder[LedgerRow]
          while (resultSet.next()) result += LedgerRow(resultSet.getLong(1), resultSet.getString(2))
          result.result()
        } finally resultSet.close()
      } finally statement.close()
    }

  private def dropSchema(settings: Settings, schema: String): Unit = {
    require(schema.matches("pekko_test_reliable_[a-f0-9]{12}"), s"unsafe test schema: $schema")
    withConnection(settings.adminUrl, settings) { connection =>
      val statement = connection.createStatement()
      try statement.executeUpdate(s"DROP DATABASE IF EXISTS `$schema`")
      finally statement.close()
    }
  }

  private def withConnection[A](url: String, settings: Settings)(operation: Connection => A): A = {
    Class.forName("com.mysql.cj.jdbc.Driver")
    val connection = DriverManager.getConnection(url, settings.user, settings.password)
    try operation(connection)
    finally connection.close()
  }
}
