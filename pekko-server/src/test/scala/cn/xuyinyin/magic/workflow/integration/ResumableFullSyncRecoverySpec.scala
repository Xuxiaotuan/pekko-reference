package cn.xuyinyin.magic.workflow.engine {
  import cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry
  import cn.xuyinyin.magic.workflow.nodes.base.{NodeSink, NodeSource}

  private[workflow] object ResumableRecoveryRegistryCleanup {
    def unregister(source: NodeSource): Unit = NodeRegistry.unregisterSource(source.nodeType, source)
    def unregister(sink: NodeSink): Unit = NodeRegistry.unregisterSink(sink.nodeType, sink)
  }
}

package cn.xuyinyin.magic.workflow.integration {

import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor
import cn.xuyinyin.magic.workflow.checkpoint.{AlreadyCommitted, BatchCheckpoint, BatchCommitResult, Committed, SourceBatch}
import cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.engine.{ExecutionResult, ReliableRunContext, WorkflowExecutionEngine}
import cn.xuyinyin.magic.workflow.model.WorkflowDSL.{Edge, Node, Position, Workflow, WorkflowMetadata}
import cn.xuyinyin.magic.workflow.nodes.sinks.MySQLSinkNode
import cn.xuyinyin.magic.workflow.nodes.sources.MySQLSnapshotSourceNode
import cn.xuyinyin.magic.workflow.sharding.WorkflowSharding
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.cluster.typed.Cluster
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import spray.json.{JsArray, JsNumber, JsObject, JsString}

import java.nio.file.Files
import java.sql.{Connection, DriverManager}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.io.Source
import scala.jdk.CollectionConverters._

object ResumableFullSyncRecoverySpec {
  private final case class CommitObservation(executionId: String, sequence: Long, outcome: String)
  private final case class LedgerRow(batchId: String, executionId: String, sequence: Long, cursor: String)
}

class ResumableFullSyncRecoverySpec extends AnyWordSpec with Matchers with Eventually {
  import ResumableFullSyncRecoverySpec._

  implicit val patience: PatienceConfig = PatienceConfig(timeout = Span(45, Seconds), interval = Span(250, Millis))

  "a resumable mysql.snapshot workflow" should {
    "replay a target-committed batch on a second node before completing the original execution" in {
      val database = MultiNodeTestSupport.newDatabase("two-node-workflow-resumable-full-sync-")
      val sourceUrl = h2Url(database.directory.resolve("source"))
      val targetUrl = h2Url(database.directory.resolve("target"))
      val clusterName = s"resumable-full-sync-${java.util.UUID.randomUUID()}"
      val workflowId = s"full-sync-${java.util.UUID.randomUUID()}"
      val sourceRows = Vector(1 -> "row-1", 2 -> "row-2", 5 -> "row-5", 9 -> "row-9", 12 -> "row-12")
      val source = new H2SnapshotSource
      val sink = new H2ObservingSink
      val originalSource = NodeRegistry.findSource(source.nodeType).get
      val originalSink = NodeRegistry.findSink(sink.nodeType).get
      val sequenceZeroCommitted = Promise[BatchCheckpoint]()
      val finalCheckpointPersisted = Promise[BatchCheckpoint]()
      val releaseFinalCheckpoint = Promise[Done]()
      var node1: ActorTestKit = null
      var node2: ActorTestKit = null
      var node1Port = -1
      var node2Port = -1

      try {
        initializeSource(sourceUrl, sourceRows)
        initializeTarget(targetUrl)
        NodeRegistry.registerSource(source)
        NodeRegistry.registerSink(sink)

        Vector(database.url, sourceUrl, targetUrl).foreach { url =>
          url should startWith("jdbc:h2:file:")
          url.toLowerCase should not include "tailscale"
        }

        node1 = ActorTestKit(clusterName, MultiNodeTestSupport.nodeConfig(database.url))
        MultiNodeTestSupport.joinSelf(node1)
        eventually {
          Cluster(node1.system).selfMember.status shouldBe MemberStatus.Up
        }
        node1Port = Cluster(node1.system).selfMember.address.port.get

        implicit val node1ExecutionContext: ExecutionContext = node1.system.executionContext
        val node1Engine = new BeforeCheckpointGateEngine(sequenceZeroCommitted)(node1.system, node1ExecutionContext)
        val region1 = WorkflowSharding.init(node1.system, node1Engine)(node1ExecutionContext)
        val workflow = fullSyncWorkflow(workflowId, sourceUrl, targetUrl)

        val defineReply = node1.createTestProbe[EventSourcedWorkflowActor.Reply]()
        region1 ! ShardingEnvelope(
          workflowId,
          EventSourcedWorkflowActor.DefineWorkflow(workflow, 0L, defineReply.ref)
        )
        defineReply.expectMessage(EventSourcedWorkflowActor.Defined(workflowId, 1L))

        val executionReply = node1.createTestProbe[EventSourcedWorkflowActor.Reply]()
        region1 ! ShardingEnvelope(
          workflowId,
          EventSourcedWorkflowActor.ExecuteManual("controlled-crash", executionReply.ref)
        )
        val originalExecutionId = executionReply.expectMessageType[EventSourcedWorkflowActor.ExecutionAccepted].executionId

        val committedBeforeCheckpoint = Await.result(sequenceZeroCommitted.future, 20.seconds)
        committedBeforeCheckpoint.batchSequence shouldBe 0L
        committedBeforeCheckpoint.cursor.value shouldBe "2"
        targetIdsAndCounts(targetUrl) shouldBe Vector(1 -> 1L, 2 -> 1L)
        ledgerRows(targetUrl) should matchPattern {
          case Vector(LedgerRow(_, `originalExecutionId`, 0L, "2")) =>
        }
        sink.commitObservations shouldBe Vector(CommitObservation(originalExecutionId, 0L, "committed"))

        val beforeCrashReply = node1.createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
        region1 ! ShardingEnvelope(workflowId, EventSourcedWorkflowActor.GetSummary(beforeCrashReply.ref))
        val beforeCrash = beforeCrashReply.receiveMessage(5.seconds)
        beforeCrash.status shouldBe EventSourcedWorkflowActor.Running
        beforeCrash.currentExecution.map(_.executionId) shouldBe Some(originalExecutionId)
        beforeCrash.currentExecution.flatMap(_.boundary.flatMap(_.upperBound)) shouldBe Some("12")
        beforeCrash.currentExecution.toVector.flatMap(_.checkpoints) shouldBe empty

        node2 = ActorTestKit(clusterName, MultiNodeTestSupport.nodeConfig(database.url))
        MultiNodeTestSupport.join(node2, node1)
        eventually {
          Cluster(node1.system).state.members.count(_.status == MemberStatus.Up) shouldBe 2
          Cluster(node2.system).state.members.count(_.status == MemberStatus.Up) shouldBe 2
        }
        node2Port = Cluster(node2.system).selfMember.address.port.get
        node2Port should not be node1Port
        info(
          s"allocated local resources: ports=$node1Port,$node2Port persistence=${database.url} source=$sourceUrl target=$targetUrl"
        )

        val node1Address = Cluster(node1.system).selfMember.address
        MultiNodeTestSupport.terminate(node1)
        MultiNodeTestSupport.downFrom(node2, node1Address)
        eventually {
          Cluster(node2.system).state.members.map(_.address) shouldBe Set(Cluster(node2.system).selfMember.address)
        }

        implicit val node2ExecutionContext: ExecutionContext = node2.system.executionContext
        val node2Engine = new AfterCheckpointGateEngine(
          sequence = 2L,
          finalCheckpointPersisted,
          releaseFinalCheckpoint.future
        )(node2.system, node2ExecutionContext)
        val region2 = WorkflowSharding.init(node2.system, node2Engine)(node2ExecutionContext)

        val activationReply = node2.createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
        region2 ! ShardingEnvelope(workflowId, EventSourcedWorkflowActor.GetSummary(activationReply.ref))
        activationReply.receiveMessage(10.seconds).currentExecution.map(_.executionId) shouldBe Some(originalExecutionId)

        val finalCheckpoint = Await.result(finalCheckpointPersisted.future, 30.seconds)
        finalCheckpoint.batchSequence shouldBe 2L
        finalCheckpoint.cursor.value shouldBe "12"

        val finalCheckpointReply = node2.createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
        region2 ! ShardingEnvelope(workflowId, EventSourcedWorkflowActor.GetSummary(finalCheckpointReply.ref))
        val atFinalCheckpoint = finalCheckpointReply.receiveMessage(5.seconds)
        atFinalCheckpoint.status shouldBe EventSourcedWorkflowActor.Running
        atFinalCheckpoint.currentExecution.map(_.executionId) shouldBe Some(originalExecutionId)
        atFinalCheckpoint.currentExecution.toVector.flatMap(_.checkpoints) shouldBe Vector(finalCheckpoint)

        releaseFinalCheckpoint.success(Done)
        eventually {
          val completedReply = node2.createTestProbe[EventSourcedWorkflowActor.WorkflowSummary]()
          region2 ! ShardingEnvelope(workflowId, EventSourcedWorkflowActor.GetSummary(completedReply.ref))
          val completed = completedReply.receiveMessage(5.seconds)
          completed.status shouldBe EventSourcedWorkflowActor.Completed
          completed.currentExecution shouldBe None
          completed.recentExecutions.map(execution => execution.executionId -> execution.status) shouldBe
            Vector(originalExecutionId -> "completed")
        }

        targetIdsAndCounts(targetUrl) shouldBe sourceRows.map(_._1 -> 1L)
        selectCount(targetUrl, "sink_rows") shouldBe sourceRows.size.toLong

        val ledger = ledgerRows(targetUrl)
        ledger.map(_.batchId).distinct should have size 3
        ledger.map(_.batchId) should have size 3
        ledger.map(_.executionId).distinct shouldBe Vector(originalExecutionId)
        ledger.map(_.sequence) shouldBe Vector(0L, 1L, 2L)
        ledger.map(_.cursor) shouldBe Vector("2", "9", "12")

        sink.commitObservations.filter(_.sequence == 0L) shouldBe Vector(
          CommitObservation(originalExecutionId, 0L, "committed"),
          CommitObservation(originalExecutionId, 0L, "already_committed")
        )
        sink.commitObservations.map(_.sequence) shouldBe Vector(0L, 0L, 1L, 2L)
      } finally {
        releaseFinalCheckpoint.trySuccess(Done)
        try {
          if (node1 != null) MultiNodeTestSupport.shutdown(node1)
          if (node2 != null) MultiNodeTestSupport.shutdown(node2)
          eventually {
            source.openDataSources shouldBe 0
            sink.openDataSources shouldBe 0
          }
        } finally {
          cn.xuyinyin.magic.workflow.engine.ResumableRecoveryRegistryCleanup.unregister(source)
          cn.xuyinyin.magic.workflow.engine.ResumableRecoveryRegistryCleanup.unregister(sink)
          MultiNodeTestSupport.cleanupDatabase(database)
        }
      }

      node1.system.whenTerminated.isCompleted shouldBe true
      node2.system.whenTerminated.isCompleted shouldBe true
      NodeRegistry.findSource(source.nodeType).get should be theSameInstanceAs originalSource
      NodeRegistry.findSink(sink.nodeType).get should be theSameInstanceAs originalSink
      source.openDataSources shouldBe 0
      sink.openDataSources shouldBe 0
      Files.exists(database.directory) shouldBe false
      info(s"released local resources: ports=$node1Port,$node2Port databases=deleted actorSystems=terminated")
    }
  }

  private final class BeforeCheckpointGateEngine(signal: Promise[BatchCheckpoint])(
    implicit system: ActorSystem[_],
    executionContext: ExecutionContext
  ) extends WorkflowExecutionEngine() {
    private val blocked = Promise[Done]()
    private val intercepted = new AtomicBoolean(false)

    override def execute(
      workflow: Workflow,
      runContext: ReliableRunContext,
      onLog: String => Unit
    ): Future[ExecutionResult] = {
      val checkpointCommitted = runContext.checkpointCommitted
      super.execute(
        workflow,
        runContext.copy(checkpointCommitted = checkpoint => {
          if (checkpoint.batchSequence == 0L && intercepted.compareAndSet(false, true)) {
            signal.trySuccess(checkpoint)
            blocked.future
          } else checkpointCommitted(checkpoint)
        }),
        onLog
      )
    }
  }

  private final class AfterCheckpointGateEngine(
    sequence: Long,
    signal: Promise[BatchCheckpoint],
    release: Future[Done]
  )(
    implicit system: ActorSystem[_],
    executionContext: ExecutionContext
  ) extends WorkflowExecutionEngine() {
    private val intercepted = new AtomicBoolean(false)

    override def execute(
      workflow: Workflow,
      runContext: ReliableRunContext,
      onLog: String => Unit
    ): Future[ExecutionResult] = {
      val checkpointCommitted = runContext.checkpointCommitted
      super.execute(
        workflow,
        runContext.copy(checkpointCommitted = checkpoint => {
          val persisted = checkpointCommitted(checkpoint)
          if (checkpoint.batchSequence == sequence && intercepted.compareAndSet(false, true)) {
            persisted.flatMap { _ =>
              signal.trySuccess(checkpoint)
              release
            }(executionContext)
          } else persisted
        }),
        onLog
      )
    }
  }

  private final class H2SnapshotSource extends MySQLSnapshotSourceNode {
    private val activeDataSources = new AtomicInteger(0)

    def openDataSources: Int = activeDataSources.get()

    override def createDataSource(
      host: String,
      port: Int,
      database: String,
      username: String,
      password: String
    ): HikariDataSource = trackedDataSource(database, username, password, activeDataSources)
  }

  private final class H2ObservingSink extends MySQLSinkNode {
    private val activeDataSources = new AtomicInteger(0)
    private val commits = new ConcurrentLinkedQueue[CommitObservation]()

    def openDataSources: Int = activeDataSources.get()
    def commitObservations: Vector[CommitObservation] = commits.iterator().asScala.toVector

    override def createDataSource(
      host: String,
      port: Int,
      database: String,
      username: String,
      password: String
    ): HikariDataSource = trackedDataSource(database, username, password, activeDataSources)

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
        commits.add(CommitObservation(executionId, batch.batchSequence, outcome))
        result
      }(blockingEc)
  }

  private def trackedDataSource(
    url: String,
    username: String,
    password: String,
    active: AtomicInteger
  ): HikariDataSource = {
    val config = new HikariConfig()
    config.setJdbcUrl(url)
    config.setDriverClassName("org.h2.Driver")
    config.setUsername(username)
    config.setPassword(password)
    config.setMaximumPoolSize(1)
    config.setMinimumIdle(0)
    val closed = new AtomicBoolean(false)
    val dataSource = new HikariDataSource(config) {
      override def close(): Unit =
        if (closed.compareAndSet(false, true)) {
          try super.close()
          finally active.decrementAndGet()
        }
    }
    active.incrementAndGet()
    dataSource
  }

  private def fullSyncWorkflow(workflowId: String, sourceUrl: String, targetUrl: String): Workflow = {
    val source = Node(
      "source-1",
      "source",
      "mysql.snapshot",
      "H2 snapshot source",
      Position(0, 0),
      JsObject(
        "host" -> JsString("unused-h2"),
        "port" -> JsNumber(3306),
        "database" -> JsString(sourceUrl),
        "username" -> JsString("sa"),
        "password" -> JsString("test"),
        "table" -> JsString("source_rows"),
        "columns" -> JsArray(JsString("id"), JsString("payload")),
        "primaryKey" -> JsString("id"),
        "chunkSize" -> JsNumber(2)
      )
    )
    val sink = Node(
      "sink-1",
      "sink",
      "mysql.write",
      "H2 idempotent sink",
      Position(1, 0),
      JsObject(
        "host" -> JsString("unused-h2"),
        "port" -> JsNumber(3306),
        "database" -> JsString(targetUrl),
        "table" -> JsString("sink_rows"),
        "username" -> JsString("sa"),
        "password" -> JsString("test"),
        "batchSize" -> JsNumber(2),
        "mode" -> JsString("insert")
      )
    )
    Workflow(
      workflowId,
      "resumable full sync",
      "controlled sink-commit/checkpoint recovery",
      "1",
      "test",
      Nil,
      List(source, sink),
      List(Edge("source-to-sink", source.id, sink.id)),
      WorkflowMetadata("2026-08-30", "2026-08-30")
    )
  }

  private def initializeSource(url: String, rows: Vector[(Int, String)]): Unit =
    withConnection(url) { connection =>
      val statement = connection.createStatement()
      try statement.executeUpdate("CREATE TABLE source_rows (id INT PRIMARY KEY, payload VARCHAR(255) NOT NULL)")
      finally statement.close()

      val insert = connection.prepareStatement("INSERT INTO source_rows (id, payload) VALUES (?, ?)")
      try {
        rows.foreach { case (id, payload) =>
          insert.setInt(1, id)
          insert.setString(2, payload)
          insert.addBatch()
        }
        insert.executeBatch()
      } finally insert.close()
    }

  private def initializeTarget(url: String): Unit =
    withConnection(url) { connection =>
      val statement = connection.createStatement()
      try statement.executeUpdate("CREATE TABLE sink_rows (id INT PRIMARY KEY, payload VARCHAR(255) NOT NULL)")
      finally statement.close()
      executeResource(connection, "schema/h2/pekko-sync-ledger-schema.sql")
    }

  private def targetIdsAndCounts(url: String): Vector[(Int, Long)] =
    withConnection(url) { connection =>
      val statement = connection.createStatement()
      try {
        val resultSet = statement.executeQuery("SELECT id, COUNT(*) FROM sink_rows GROUP BY id ORDER BY id")
        try {
          val rows = Vector.newBuilder[(Int, Long)]
          while (resultSet.next()) rows += resultSet.getInt(1) -> resultSet.getLong(2)
          rows.result()
        } finally resultSet.close()
      } finally statement.close()
    }

  private def ledgerRows(url: String): Vector[LedgerRow] =
    withConnection(url) { connection =>
      val statement = connection.createStatement()
      try {
        val resultSet = statement.executeQuery(
          "SELECT batch_id, execution_id, batch_sequence, cursor_value FROM pekko_sync_batch_ledger ORDER BY batch_sequence"
        )
        try {
          val rows = Vector.newBuilder[LedgerRow]
          while (resultSet.next()) {
            rows += LedgerRow(resultSet.getString(1), resultSet.getString(2), resultSet.getLong(3), resultSet.getString(4))
          }
          rows.result()
        } finally resultSet.close()
      } finally statement.close()
    }

  private def selectCount(url: String, table: String): Long =
    withConnection(url) { connection =>
      val statement = connection.createStatement()
      try {
        val resultSet = statement.executeQuery(s"SELECT COUNT(*) FROM $table")
        try {
          resultSet.next()
          resultSet.getLong(1)
        } finally resultSet.close()
      } finally statement.close()
    }

  private def executeResource(connection: Connection, resource: String): Unit = {
    val input = Option(getClass.getClassLoader.getResourceAsStream(resource))
      .getOrElse(throw new IllegalStateException(s"missing test resource: $resource"))
    val sql = try Source.fromInputStream(input).mkString finally input.close()
    val statement = connection.createStatement()
    try sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(statement.execute)
    finally statement.close()
  }

  private def withConnection[A](url: String)(operation: Connection => A): A = {
    Class.forName("org.h2.Driver")
    val connection = DriverManager.getConnection(url, "sa", "test")
    try operation(connection)
    finally connection.close()
  }

private def h2Url(path: java.nio.file.Path): String =
    s"jdbc:h2:file:${path.toAbsolutePath};MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000"
}

}
