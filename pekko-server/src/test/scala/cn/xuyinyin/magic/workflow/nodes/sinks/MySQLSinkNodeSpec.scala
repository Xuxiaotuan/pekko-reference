package cn.xuyinyin.magic.workflow.nodes.sinks

import cn.xuyinyin.magic.testkit.STSpec
import cn.xuyinyin.magic.workflow.checkpoint.{AlreadyCommitted, BatchId, Committed, SourceBatch, SourceCursor}
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.{KillSwitches, Materializer}
import org.apache.pekko.stream.scaladsl.{Keep, Source}
import spray.json._

import java.sql.{Connection, DriverManager}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CyclicBarrier, Executors, TimeUnit}
import java.util.UUID
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.io.Source.fromInputStream

class MySQLSinkNodeSpec extends STSpec {
  private implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](
    Behaviors.empty[Nothing],
    "mysql-sink-node-spec",
    ConfigFactory.parseString(
      """pekko.actor.provider = local
        |pekko.remote.artery.enabled = false
        |pekko.coordinated-shutdown.exit-jvm = off""".stripMargin
    )
  )
  private implicit val ec: ExecutionContext = system.executionContext
  private implicit val materializer: Materializer = Materializer(system)

  override protected def afterAll(): Unit = {
    system.terminate()
    Await.result(system.whenTerminated, 5.seconds)
    super.afterAll()
  }

  "MySQLSinkNode" should {
    "write every row across complete and partial batches" in {
      withFixture { fixture =>
        val result = Await.result(runSink(fixture, rows = 250, batchSize = 100), 5.seconds)

        result shouldBe Done
        fixture.selectCount("sink_rows") shouldBe 250
        fixture.lastDataSource.isClosed shouldBe true
        fixture.activeConnections shouldBe 0
      }
    }

    "defer datasource allocation until a materialized stream receives its first element" in {
      withFixture { fixture =>
        val sink = fixture.node.createSink(sinkNode(batchSize = 100), _ => ())

        fixture.dataSourceCount shouldBe 0
        Await.result(Source.empty[String].runWith(sink), 5.seconds) shouldBe Done
        fixture.dataSourceCount shouldBe 0
      }
    }

    "close the datasource when inner sink setup fails after allocation" in {
      withFixture(failInnerSink = true) { fixture =>
        val failure = intercept[IllegalStateException] {
          Await.result(Source.single(row(1)).runWith(fixture.node.createSink(sinkNode(batchSize = 100), _ => ())), 5.seconds)
        }

        failure.getMessage should include("inner sink setup failed")
        fixture.lastDataSource.isClosed shouldBe true
      }
    }

    "roll back a failing batch and fail the materialized Future" in {
      withFixture { fixture =>
        val failure = intercept[IllegalStateException] {
          Await.result(runBatchContainingDuplicateKey(fixture), 5.seconds)
        }

        failure.getMessage should include("batch write failed")
        fixture.selectCount("sink_rows") shouldBe 0
        fixture.lastDataSource.isClosed shouldBe true
      }
    }

    "close its datasource when upstream cancellation completes the sink" in {
      withFixture { fixture =>
        val sink = fixture.node.createSink(sinkNode(batchSize = 100), _ => ())
        val (killSwitch, result) = Source
          .single(row(1))
          .concat(Source.never)
          .viaMat(KillSwitches.single)(Keep.right)
          .toMat(sink)(Keep.both)
          .run()

        Await.result(fixture.dataSourceCreated, 5.seconds)
        killSwitch.abort(new RuntimeException("abort"))

        intercept[RuntimeException] {
          Await.result(result, 5.seconds)
        }.getMessage shouldBe "abort"
        fixture.lastDataSource.isClosed shouldBe true
      }
    }

    "wrap malformed JSON as a failed batch write" in {
      withFixture { fixture =>
        val failure = intercept[IllegalStateException] {
          Await.result(Source.single("{not-json").runWith(fixture.node.createSink(sinkNode(batchSize = 100), _ => ())), 5.seconds)
        }

        failure.getMessage should include("batch write failed")
        fixture.selectCount("sink_rows") shouldBe 0
      }
    }

    "provide the required primary and unique ledger identities" in {
      withFixture { fixture =>
        fixture.initializeLedger()

        fixture.primaryKeyColumns("pekko_sync_batch_ledger") shouldBe Set("batch_id")
        fixture.hasUniqueIndex(
          "pekko_sync_batch_ledger",
          Set("execution_id", "source_node_id", "partition_id", "batch_sequence")
        ) shouldBe true
      }
    }

    "validate ledger readiness without changing data" in {
      withFixture { fixture =>
        val failure = intercept[IllegalStateException] {
          Await.result(fixture.node.validateReady(sinkNode(batchSize = 100), _ => ()), 5.seconds)
        }
        failure.getMessage should include("pekko_sync_batch_ledger")

        fixture.initializeLedger()
        Await.result(fixture.node.validateReady(sinkNode(batchSize = 100), _ => ()), 5.seconds) shouldBe Done
        fixture.selectCount("sink_rows") shouldBe 0
        fixture.selectCount("pekko_sync_batch_ledger") shouldBe 0
      }
    }

    "reject a ledger missing only the committed_at column" in {
      withFixture { fixture =>
        fixture.initializeLedgerWithoutCommittedAt()

        val failure = intercept[IllegalStateException] {
          Await.result(fixture.node.validateReady(sinkNode(batchSize = 100), _ => ()), 5.seconds)
        }

        failure.getMessage should include("pekko_sync_batch_ledger")
        fixture.selectCount("sink_rows") shouldBe 0
        fixture.selectCount("pekko_sync_batch_ledger") shouldBe 0
      }
    }

    "commit target rows and ledger once and recognize an identical replay" in {
      withFixture { fixture =>
        fixture.initializeLedger()
        val batch = sourceBatch(sequence = 0L, cursor = "2", rows = Vector(row(1), row(2)))

        val first = Await.result(
          fixture.node.commitBatch(sinkNode(100), "workflow-1", "execution-1", batch, batch.rows, _ => ()),
          5.seconds
        )
        first shouldBe Committed(first.checkpoint)
        first.checkpoint.sourceRowsScanned shouldBe 2L
        first.checkpoint.targetRowsWritten shouldBe 2L

        val replay = Await.result(
          fixture.node.commitBatch(sinkNode(100), "workflow-1", "execution-1", batch, batch.rows, _ => ()),
          5.seconds
        )
        replay shouldBe AlreadyCommitted(first.checkpoint)
        fixture.selectCount("sink_rows") shouldBe 2
        fixture.selectCount("pekko_sync_batch_ledger") shouldBe 1
      }
    }

    "resolve two simultaneous ledger claims as one commit and one replay" in {
      val claimBarrier = new CyclicBarrier(2)
      withClaimHook(_ => claimBarrier.await(5, TimeUnit.SECONDS)) { fixture =>
        fixture.initializeLedger()
        val batch = sourceBatch(sequence = 0L, cursor = "2", rows = Vector(row(1), row(2)))
        val executor = Executors.newFixedThreadPool(2)
        val commitEc = ExecutionContext.fromExecutorService(executor)
        try {
          val attempts = Vector.fill(2) {
            fixture.node.commitBatch(
              sinkNode(100),
              "workflow-1",
              "execution-1",
              batch,
              batch.rows,
              _ => ()
            )(commitEc)
          }
          val results = Await.result(Future.sequence(attempts), 10.seconds)

          results.count(_.isInstanceOf[Committed]) shouldBe 1
          results.count(_.isInstanceOf[AlreadyCommitted]) shouldBe 1
          results.map(_.checkpoint).distinct shouldBe Vector(results.head.checkpoint)
          fixture.selectCount("sink_rows") shouldBe 2
          fixture.selectCount("pekko_sync_batch_ledger") shouldBe 1
        } finally {
          commitEc.shutdownNow()
        }
      }
    }

    "roll back both ledger and target rows when a target batch fails" in {
      withFixture { fixture =>
        fixture.initializeLedger()
        val batch = sourceBatch(sequence = 0L, cursor = "2", rows = Vector(row(1), row(1)))

        intercept[IllegalStateException] {
          Await.result(
            fixture.node.commitBatch(sinkNode(100), "workflow-1", "execution-1", batch, batch.rows, _ => ()),
            5.seconds
          )
        }

        fixture.selectCount("sink_rows") shouldBe 0
        fixture.selectCount("pekko_sync_batch_ledger") shouldBe 0
      }
    }

    "treat a target NOT NULL violation as an ordinary failed batch" in {
      withFixture { fixture =>
        fixture.initializeLedger()
        val batch = sourceBatch(
          sequence = 0L,
          cursor = "1",
          rows = Vector("""{"id":1,"payload":null}""")
        )

        intercept[IllegalStateException] {
          Await.result(
            fixture.node.commitBatch(sinkNode(100), "workflow-1", "execution-1", batch, batch.rows, _ => ()),
            5.seconds
          )
        }

        fixture.selectCount("sink_rows") shouldBe 0
        fixture.selectCount("pekko_sync_batch_ledger") shouldBe 0
      }
    }

    "reject malformed transformed rows before reaching the ledger claim" in {
      val claimCalls = new AtomicInteger(0)
      withClaimHook(_ => claimCalls.incrementAndGet()) { fixture =>
        fixture.initializeLedger()
        val batch = sourceBatch(sequence = 0L, cursor = "1", rows = Vector(row(1)))

        intercept[IllegalStateException] {
          Await.result(
            fixture.node.commitBatch(
              sinkNode(100),
              "workflow-1",
              "execution-1",
              batch,
              Vector("{not-json"),
              _ => ()
            ),
            5.seconds
          )
        }

        claimCalls.get() shouldBe 0
        fixture.selectCount("sink_rows") shouldBe 0
        fixture.selectCount("pekko_sync_batch_ledger") shouldBe 0
      }
    }

    "reject conflicting durable metadata without changing target rows" in {
      withFixture { fixture =>
        fixture.initializeLedger()
        val batch = sourceBatch(sequence = 0L, cursor = "2", rows = Vector(row(1), row(2)))
        fixture.insertConflictingLedger(batch.batchId)

        val failure = intercept[IllegalStateException] {
          Await.result(
            fixture.node.commitBatch(sinkNode(100), "workflow-1", "execution-1", batch, batch.rows, _ => ()),
            5.seconds
          )
        }

        failure.getMessage should include("conflicting")
        fixture.selectCount("sink_rows") shouldBe 0
        fixture.selectCount("pekko_sync_batch_ledger") shouldBe 1
      }
    }

    "advance an empty transformed batch by committing only its ledger" in {
      withFixture { fixture =>
        fixture.initializeLedger()
        val batch = sourceBatch(sequence = 0L, cursor = "2", rows = Vector(row(1), row(2)))

        val result = Await.result(
          fixture.node.commitBatch(sinkNode(100), "workflow-1", "execution-1", batch, Vector.empty, _ => ()),
          5.seconds
        )

        result.checkpoint.sourceRowsScanned shouldBe 2L
        result.checkpoint.targetRowsWritten shouldBe 0L
        fixture.selectCount("sink_rows") shouldBe 0
        fixture.selectCount("pekko_sync_batch_ledger") shouldBe 1
      }
    }

    "keep a committed result successful when its log callback throws" in {
      withFixture { fixture =>
        fixture.initializeLedger()
        val batch = sourceBatch(sequence = 0L, cursor = "1", rows = Vector(row(1)))

        val result = Await.result(
          fixture.node.commitBatch(
            sinkNode(100),
            "workflow-1",
            "execution-1",
            batch,
            batch.rows,
            _ => throw new RuntimeException("log callback failed")
          ),
          5.seconds
        )

        result shouldBe Committed(result.checkpoint)
        fixture.selectCount("sink_rows") shouldBe 1
        fixture.selectCount("pekko_sync_batch_ledger") shouldBe 1
      }
    }
  }

  private implicit class BatchCommitResultOps(private val result: cn.xuyinyin.magic.workflow.checkpoint.BatchCommitResult) {
    def checkpoint = result match {
      case Committed(value) => value
      case AlreadyCommitted(value) => value
    }
  }

  private def runSink(fixture: H2Fixture, rows: Int, batchSize: Int): Future[Done] =
    Source(1 to rows)
      .map(row)
      .runWith(fixture.node.createSink(sinkNode(batchSize), _ => ()))

  private def runBatchContainingDuplicateKey(fixture: H2Fixture): Future[Done] =
    Source(List(row(1), row(2), row(1)))
      .runWith(fixture.node.createSink(sinkNode(batchSize = 100), _ => ()))

  private def sinkNode(batchSize: Int): WorkflowDSL.Node =
    WorkflowDSL.Node(
      id = "mysql-sink",
      `type` = "sink",
      nodeType = "mysql.write",
      label = "MySQL",
      position = WorkflowDSL.Position(0, 0),
      config = JsObject(
        "host" -> JsString("unused"),
        "port" -> JsNumber(3306),
        "database" -> JsString("unused"),
        "table" -> JsString("sink_rows"),
        "username" -> JsString("sa"),
        "password" -> JsString(""),
        "batchSize" -> JsNumber(batchSize),
        "mode" -> JsString("insert")
      )
    )

  private def row(id: Int): String = s"""{"id":$id,"payload":"row-$id"}"""

  private def sourceBatch(sequence: Long, cursor: String, rows: Vector[String]): SourceBatch =
    SourceBatch(
      sourceNodeId = "source-1",
      partitionId = "pk-range-0",
      batchSequence = sequence,
      batchId = BatchId.sha256("execution-1", "source-1", "pk-range-0", sequence),
      cursor = SourceCursor("mysql.numeric-pk", cursor, "12"),
      rows = rows
    )

  private def withFixture(test: H2Fixture => Any): Unit = withFixture(failInnerSink = false)(test)

  private def withFixture(failInnerSink: Boolean)(test: H2Fixture => Any): Unit = {
    val fixture = new H2Fixture(failInnerSink, _ => ())
    try test(fixture)
    finally fixture.close()
  }

  private def withClaimHook(hook: String => Unit)(test: H2Fixture => Any): Unit = {
    val fixture = new H2Fixture(failInnerSink = false, claimHook = hook)
    try test(fixture)
    finally fixture.close()
  }

  private final class H2Fixture(failInnerSink: Boolean, claimHook: String => Unit) {
    private val jdbcUrl = s"jdbc:h2:mem:mysql_sink_${UUID.randomUUID().toString.replace('-', '_')};MODE=MySQL;DB_CLOSE_DELAY=0"
    private val inspectionConnection: Connection = DriverManager.getConnection(jdbcUrl, "sa", "")
    private var dataSources = Vector.empty[HikariDataSource]
    private val created = Promise[HikariDataSource]()

    val node = new MySQLSinkNode {
      override protected[sinks] def createDataSource(
        host: String,
        port: Int,
        database: String,
        username: String,
        password: String
      ): HikariDataSource = {
        val config = new HikariConfig()
        config.setJdbcUrl(jdbcUrl)
        config.setDriverClassName("org.h2.Driver")
        config.setUsername(username)
        config.setPassword(password)
        config.setMaximumPoolSize(1)
        config.setMinimumIdle(0)
        val dataSource = new HikariDataSource(config)
        dataSources :+= dataSource
        created.trySuccess(dataSource)
        dataSource
      }

      override protected[sinks] def createInnerSink(
        dataSource: HikariDataSource,
        table: String,
        batchSize: Int,
        mode: String,
        onLog: String => Unit
      )(implicit executionContext: ExecutionContext) = {
        if (failInnerSink) throw new IllegalStateException("inner sink setup failed")
        super.createInnerSink(dataSource, table, batchSize, mode, onLog)(executionContext)
      }

      override protected[sinks] def beforeLedgerClaim(batchId: String): Unit = claimHook(batchId)
    }

    initializeTable()

    def dataSourceCount: Int = dataSources.size
    def dataSourceCreated: Future[HikariDataSource] = created.future
    def lastDataSource: HikariDataSource = dataSources.last
    def activeConnections: Int = lastDataSource.getHikariPoolMXBean.getActiveConnections

    def selectCount(table: String): Int = {
      val statement = inspectionConnection.createStatement()
      try {
        val resultSet = statement.executeQuery(s"SELECT COUNT(*) FROM $table")
        try {
          resultSet.next()
          resultSet.getInt(1)
        } finally resultSet.close()
      } finally statement.close()
    }

    def initializeLedger(): Unit = {
      val input = Option(getClass.getClassLoader.getResourceAsStream("schema/h2/pekko-sync-ledger-schema.sql"))
        .getOrElse(throw new IllegalStateException("missing H2 ledger schema"))
      val sql = try fromInputStream(input).mkString finally input.close()
      val statement = inspectionConnection.createStatement()
      try sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(statement.execute)
      finally statement.close()
    }

    def initializeLedgerWithoutCommittedAt(): Unit = {
      val statement = inspectionConnection.createStatement()
      try {
        statement.executeUpdate(
          """CREATE TABLE pekko_sync_batch_ledger (
            |  batch_id VARCHAR(64) PRIMARY KEY,
            |  workflow_id VARCHAR(255) NOT NULL,
            |  execution_id VARCHAR(255) NOT NULL,
            |  source_node_id VARCHAR(255) NOT NULL,
            |  partition_id VARCHAR(128) NOT NULL,
            |  batch_sequence BIGINT NOT NULL,
            |  cursor_value VARCHAR(128) NOT NULL,
            |  upper_bound VARCHAR(128) NOT NULL,
            |  source_rows BIGINT NOT NULL,
            |  target_rows BIGINT NOT NULL,
            |  CONSTRAINT uq_execution_partition_sequence UNIQUE
            |    (execution_id, source_node_id, partition_id, batch_sequence)
            |)""".stripMargin
        )
      } finally statement.close()
    }

    def primaryKeyColumns(table: String): Set[String] = {
      val result = inspectionConnection.getMetaData.getPrimaryKeys(null, null, table.toUpperCase)
      try {
        val columns = Set.newBuilder[String]
        while (result.next()) columns += result.getString("COLUMN_NAME").toLowerCase
        columns.result()
      }
      finally result.close()
    }

    def hasUniqueIndex(table: String, expectedColumns: Set[String]): Boolean = {
      val result = inspectionConnection.getMetaData.getIndexInfo(null, null, table.toUpperCase, true, false)
      try {
        val indexes = scala.collection.mutable.Map.empty[String, Set[String]].withDefaultValue(Set.empty)
        while (result.next()) {
          val indexName = result.getString("INDEX_NAME")
          val columnName = result.getString("COLUMN_NAME")
          if (indexName != null && columnName != null) {
            indexes.update(indexName, indexes(indexName) + columnName.toLowerCase)
          }
        }
        indexes.values.exists(_ == expectedColumns)
      } finally result.close()
    }

    def insertConflictingLedger(batchId: String): Unit = {
      val statement = inspectionConnection.prepareStatement(
        """INSERT INTO pekko_sync_batch_ledger
          |(batch_id, workflow_id, execution_id, source_node_id, partition_id, batch_sequence,
          | cursor_value, upper_bound, source_rows, target_rows)
          |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
      )
      try {
        val values = Vector[Any](batchId, "workflow-1", "execution-1", "source-1", "pk-range-0", 0L, "999", "12", 2L, 2L)
        values.zipWithIndex.foreach { case (value, index) => statement.setObject(index + 1, value) }
        statement.executeUpdate()
      } finally statement.close()
    }

    def close(): Unit = inspectionConnection.close()

    private def initializeTable(): Unit = {
      val statement = inspectionConnection.createStatement()
      try statement.executeUpdate("CREATE TABLE sink_rows (id INT PRIMARY KEY, payload VARCHAR(255) NOT NULL)")
      finally statement.close()
    }
  }
}
