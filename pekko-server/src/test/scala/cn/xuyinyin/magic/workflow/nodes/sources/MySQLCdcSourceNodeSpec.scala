package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.testkit.STSpec
import cn.xuyinyin.magic.workflow.checkpoint.{BatchCheckpoint, BatchId, SnapshotBoundary, SourceBatch, SourceCursor}
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.cdc.{CdcOperation, MySQLCdcEnvelope}
import ch.qos.logback.classic.{Logger => LogbackLogger}
import ch.qos.logback.classic.spi.{ILoggingEvent, ThrowableProxyUtil}
import ch.qos.logback.core.read.ListAppender
import com.typesafe.config.ConfigFactory
import io.debezium.config.Configuration
import io.debezium.engine.{DebeziumEngine, RecordChangeEvent}
import io.debezium.storage.jdbc.history.JdbcSchemaHistoryConfig
import io.debezium.storage.jdbc.offset.JdbcOffsetBackingStoreConfig
import org.apache.kafka.connect.data.{Decimal, Schema, SchemaBuilder, Struct}
import org.apache.kafka.connect.source.SourceRecord
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.scaladsl.Sink
import org.scalatest.OptionValues
import org.slf4j.LoggerFactory
import spray.json._

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.math.{BigDecimal => JBigDecimal}
import java.sql.{Connection, DatabaseMetaData, ResultSet, Types}
import java.util.Properties
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._

class MySQLCdcSourceNodeSpec extends STSpec with OptionValues {
  private implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](
    Behaviors.empty[Nothing],
    "mysql-cdc-source-node-spec",
    ConfigFactory.parseString(
      """pekko.actor.provider = local
        |pekko.coordinated-shutdown.exit-jvm = off""".stripMargin
    ).withFallback(ConfigFactory.load("application-test"))
  )
  private implicit val ec: ExecutionContext = system.executionContext

  private val sourceSecret = "source-password-must-not-leak"
  private val stateSecret = "state-password-must-not-leak"
  private val sourceConfig = MySQLCdcSourceConfig(
    connectorId = "orders-cdc-v1",
    host = "mysql",
    port = 3306,
    database = "pekko_workflow",
    table = "source_orders",
    username = "pekko_cdc",
    password = sourceSecret,
    serverId = 54001L,
    maxBatchSize = 100,
    pollIntervalMillis = 500
  )
  private val stateConfig = MySQLCdcStateConfig(
    jdbcUrl = "jdbc:mysql://mysql:3306/pekko_workflow",
    username = "pekko_workflow",
    password = stateSecret,
    offsetTable = "debezium_offset_storage",
    historyTable = "debezium_database_history",
    offsetFlushIntervalMillis = 0
  )

  private val expectedFingerprint = "11659dfa2c32a7a3e726a8d57317259a8bdddedab75809f7afae94bd48aa122a"
  private val expectedStreamIdentity =
    s"""{"columns":[{"jdbcType":4,"name":"id","typeName":"INT"},{"jdbcType":12,"name":"status","typeName":"VARCHAR"},{"jdbcType":3,"name":"amount","typeName":"DECIMAL"}],"connectorId":"orders-cdc-v1","database":"pekko_workflow","primaryKey":"id","schemaFingerprint":"$expectedFingerprint","table":"source_orders","version":1}"""
  private val expectedBoundary = SnapshotBoundary(
    sourceNodeId = "source-1",
    partitionId = "mysql-cdc:orders-cdc-v1",
    upperBound = Some(expectedStreamIdentity)
  )

  private val keySchema = SchemaBuilder.struct()
    .name("pekko_workflow.source_orders.Key")
    .field("id", Schema.INT32_SCHEMA)
    .build()
  private val rowSchema = SchemaBuilder.struct()
    .name("pekko_workflow.source_orders.Value")
    .optional()
    .field("id", Schema.INT32_SCHEMA)
    .field("status", Schema.OPTIONAL_STRING_SCHEMA)
    .field("amount", Decimal.builder(2).optional().build())
    .build()
  private val sourceSchema = SchemaBuilder.struct()
    .name("io.debezium.connector.mysql.Source")
    .field("db", Schema.STRING_SCHEMA)
    .field("table", Schema.STRING_SCHEMA)
    .field("snapshot", Schema.OPTIONAL_STRING_SCHEMA)
    .field("file", Schema.OPTIONAL_STRING_SCHEMA)
    .field("pos", Schema.OPTIONAL_INT64_SCHEMA)
    .field("row", Schema.OPTIONAL_INT32_SCHEMA)
    .field("ts_ms", Schema.OPTIONAL_INT64_SCHEMA)
    .build()
  private val envelopeSchema = SchemaBuilder.struct()
    .name("pekko_workflow.source_orders.Envelope")
    .field("before", rowSchema)
    .field("after", rowSchema)
    .field("source", sourceSchema)
    .field("op", Schema.STRING_SCHEMA)
    .field("ts_ms", Schema.OPTIONAL_INT64_SCHEMA)
    .build()

  override protected def afterAll(): Unit = {
    system.terminate()
    Await.result(system.whenTerminated, 5.seconds)
    super.afterAll()
  }

  "MySQLCdcSourceNode connector properties" should {
    "use the exact Debezium 3.6.1 connector and current JDBC storage property names" in {
      val actual = MySQLCdcSourceNode.connectorProperties(sourceConfig, stateConfig).asScala.toMap

      actual shouldBe Map(
        "name" -> "orders-cdc-v1",
        "connector.class" -> "io.debezium.connector.mysql.MySqlConnector",
        "database.hostname" -> "mysql",
        "database.port" -> "3306",
        "database.user" -> "pekko_cdc",
        "database.password" -> sourceSecret,
        "driver.allowPublicKeyRetrieval" -> "true",
        "driver.forceConnectionTimeZoneToSession" -> "true",
        "database.connectionTimeZone" -> "UTC",
        "database.server.id" -> "54001",
        "topic.prefix" -> "orders-cdc-v1",
        "database.include.list" -> "pekko_workflow",
        "table.include.list" -> "pekko_workflow.source_orders",
        "snapshot.mode" -> "initial",
        "snapshot.locking.mode" -> "none",
        "record.processing.order" -> "ORDERED",
        "record.processing.threads" -> "1",
        "offset.flush.interval.ms" -> "0",
        "decimal.handling.mode" -> "string",
        "include.schema.changes" -> "false",
        "tombstones.on.delete" -> "false",
        "max.batch.size" -> "100",
        "poll.interval.ms" -> "500",
        "offset.storage" -> "io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore",
        "offset.storage.jdbc.connection.url" -> "jdbc:mysql://mysql:3306/pekko_workflow",
        "offset.storage.jdbc.connection.user" -> "pekko_workflow",
        "offset.storage.jdbc.connection.password" -> stateSecret,
        "offset.storage.jdbc.table.name" -> "debezium_offset_storage_44e22282ff7839903a506bf00220fd5f",
        "offset.storage.jdbc.table.ddl" -> (
          "CREATE TABLE %s (id VARCHAR(36) NOT NULL, offset_key VARCHAR(1255), offset_val VARCHAR(1255), " +
            "record_insert_ts TIMESTAMP(6) NOT NULL, record_insert_seq INT NOT NULL, PRIMARY KEY (id))"),
        "schema.history.internal" -> "io.debezium.storage.jdbc.history.JdbcSchemaHistory",
        "schema.history.internal.jdbc.connection.url" -> "jdbc:mysql://mysql:3306/pekko_workflow",
        "schema.history.internal.jdbc.connection.user" -> "pekko_workflow",
        "schema.history.internal.jdbc.connection.password" -> stateSecret,
        "schema.history.internal.jdbc.table.name" -> "debezium_database_history_44e22282ff7839903a506bf00220fd5f",
        "schema.history.internal.jdbc.table.ddl" -> (
          "CREATE TABLE %s (id VARCHAR(36) NOT NULL, history_data LONGTEXT, history_data_seq INT, " +
            "record_insert_ts TIMESTAMP(6) NOT NULL, record_insert_seq INT NOT NULL, " +
            "PRIMARY KEY (id, history_data_seq))")
      )
    }

    "isolate JDBC offset and schema history tables by connector" in {
      val first = MySQLCdcSourceNode.connectorProperties(sourceConfig, stateConfig)
      val second = MySQLCdcSourceNode.connectorProperties(
        sourceConfig.copy(connectorId = "orders-cdc-v2"),
        stateConfig
      )
      val keys = Vector("offset.storage.jdbc.table.name", "schema.history.internal.jdbc.table.name")

      keys.foreach { key =>
        first.getProperty(key) should not be second.getProperty(key)
        first.getProperty(key) should fullyMatch regex "[A-Za-z_][A-Za-z0-9_]{0,63}"
        second.getProperty(key) should fullyMatch regex "[A-Za-z_][A-Za-z0-9_]{0,63}"
      }
      new JdbcOffsetBackingStoreConfig(Configuration.from(first)).getTableName shouldBe
        first.getProperty("offset.storage.jdbc.table.name")
      new JdbcSchemaHistoryConfig(Configuration.from(first)).getTableName shouldBe
        first.getProperty("schema.history.internal.jdbc.table.name")
    }
  }

  "MySQLCdcSourceNode metadata JDBC URL" should {
    "support MySQL 8 public-key authentication when TLS is disabled" in {
      MySQLCdcSourceNode.metadataJdbcUrl(sourceConfig) shouldBe
        "jdbc:mysql://mysql:3306/pekko_workflow?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    }
  }

  "MySQLCdcSourceNode boundary discovery" should {
    "consume an engine-prepared runtime password through the real source parser" in {
      val factory = new FakeEngineFactory(Vector.empty)
      val metadata = validMetadata
      val source = testSource(factory, () => metadata.connection())
      val runtimeNode = cdcNode().copy(config = JsObject(
        (cdcNode().config.fields - "passwordEnv") + ("password" -> JsString(sourceSecret))
      ))

      Await.result(source.discoverBoundary(runtimeNode, None, _ => ()), 2.seconds) shouldBe expectedBoundary
      metadata.closedConnections.get() shouldBe 1
    }

    "validate and order JDBC metadata before engine creation and return a stable schema identity" in {
      val factory = new FakeEngineFactory(Vector.empty)
      val metadata = MetadataFixture(
        primaryKeys = Vector(primaryKey("id", 1)),
        columns = Vector(
          column("amount", Types.DECIMAL, "DECIMAL", DatabaseMetaData.columnNullable, 3, 12, 2),
          column("id", Types.INTEGER, "INT", DatabaseMetaData.columnNoNulls, 1, 11, 0),
          column("status", Types.VARCHAR, "VARCHAR", DatabaseMetaData.columnNullable, 2, 255, 0)
        )
      )
      val source = testSource(factory, () => metadata.connection())

      val boundary = Await.result(source.discoverBoundary(cdcNode(), None, _ => ()), 2.seconds)

      boundary shouldBe expectedBoundary
      boundary.upperBound.value should not include sourceSecret
      boundary.upperBound.value should not include stateSecret
      factory.createCalls.get() shouldBe 0
      metadata.closedConnections.get() shouldBe 1
    }

    "reject no primary key, composite primary keys, and unsupported types before engine creation" in {
      val cases = Vector(
        "exactly one primary key" -> MetadataFixture(
          Vector.empty,
          Vector(column("id", Types.INTEGER, "INT", DatabaseMetaData.columnNoNulls, 1, 11, 0))
        ),
        "exactly one primary key" -> MetadataFixture(
          Vector(primaryKey("id", 1), primaryKey("tenant_id", 2)),
          Vector(
            column("id", Types.INTEGER, "INT", DatabaseMetaData.columnNoNulls, 1, 11, 0),
            column("tenant_id", Types.INTEGER, "INT", DatabaseMetaData.columnNoNulls, 2, 11, 0)
          )
        ),
        "unsupported source column type" -> MetadataFixture(
          Vector(primaryKey("id", 1)),
          Vector(
            column("id", Types.INTEGER, "INT", DatabaseMetaData.columnNoNulls, 1, 11, 0),
            column("payload", Types.BINARY, "BINARY", DatabaseMetaData.columnNullable, 2, 16, 0)
          )
        )
      )

      cases.foreach { case (expectedMessage, metadata) =>
        val factory = new FakeEngineFactory(Vector.empty)
        val source = testSource(factory, () => metadata.connection())

        val failure = intercept[IllegalArgumentException] {
          Await.result(source.discoverBoundary(cdcNode(), None, _ => ()), 2.seconds)
        }

        failure.getMessage should include(expectedMessage)
        failure.getMessage should not include sourceSecret
        factory.createCalls.get() shouldBe 0
        metadata.closedConnections.get() shouldBe 1
      }
    }

    "validate recovered node, partition, cursor kind, and stream identity before engine creation" in {
      val factory = new FakeEngineFactory(Vector.empty)
      val source = testSource(factory, () => validMetadata.connection())
      val mismatches = Vector(
        checkpoint(expectedBoundary.copy(sourceNodeId = "other-node"), sequence = 4L),
        checkpoint(expectedBoundary.copy(partitionId = "mysql-cdc:other"), sequence = 4L),
        checkpoint(expectedBoundary, sequence = 4L).copy(cursor = SourceCursor(
          "other-kind", "{}", expectedStreamIdentity
        )),
        checkpoint(expectedBoundary, sequence = 4L).copy(cursor = SourceCursor(
          "mysql.binlog.v1", "{}", expectedStreamIdentity.replace("source_orders", "other_table")
        ))
      )

      mismatches.foreach { resume =>
        intercept[IllegalArgumentException] {
          Await.result(source.discoverBoundary(cdcNode(), Some(resume), _ => ()), 2.seconds)
        }
      }
      factory.createCalls.get() shouldBe 0
    }
  }

  "MySQLCdcSourceNode streaming" should {
    "emit canonical ordered batches at sequence zero and acknowledge each callback exactly once" in {
      val firstRecords = Vector(record("r", None, Some(row(42, "new")), snapshot = true, position = 100L))
      val secondRecords = Vector(
        record("u", Some(row(42, "new")), Some(row(42, "paid")), snapshot = false, position = 200L),
        record("d", Some(row(42, "paid")), None, snapshot = false, position = 220L)
      )
      val factory = new FakeEngineFactory(Vector(EnginePlan(Vector(firstRecords, secondRecords))))
      val source = testSource(factory, () => validMetadata.connection())
      val queue = source.createBatches(cdcNode(), "execution-1", expectedBoundary, None, _ => ())
        .runWith(Sink.queue())

      val first = pull(queue)
      first.batchSequence shouldBe 0L
      first.batchId shouldBe BatchId.sha256("execution-1", "source-1", "mysql-cdc:orders-cdc-v1", 0L)
      first.cursor shouldBe SourceCursor(
        "mysql.binlog.v1",
        "{\"offset\":{\"file\":\"binlog.000001\",\"pos\":100,\"row\":0,\"snapshot\":true},\"version\":1}",
        expectedStreamIdentity
      )
      first.productElementNames.toVector shouldBe Vector(
        "sourceNodeId", "partitionId", "batchSequence", "batchId", "cursor", "rows"
      )
      first.productElementNames.toVector should not contain "deliveryToken"
      val firstEnvelope = parse(first.rows.head)
      firstEnvelope.op shouldBe CdcOperation.Read
      firstEnvelope.source.snapshot shouldBe true
      factory.engines.head.handles.asScala.head.calls.get() shouldBe 0

      Await.result(source.acknowledgeCommittedBatch(cdcNode(), first, _ => ()), 2.seconds) shouldBe Done
      eventually(factory.engines.head.handles.asScala.head.calls.get() == 1)
      val repeated = intercept[IllegalStateException] {
        Await.result(source.acknowledgeCommittedBatch(cdcNode(), first, _ => ()), 2.seconds)
      }
      repeated.getMessage should include("acknowledgement")
      factory.engines.head.handles.asScala.head.calls.get() shouldBe 1

      val second = pull(queue)
      second.batchSequence shouldBe 1L
      second.batchId shouldBe BatchId.sha256("execution-1", "source-1", "mysql-cdc:orders-cdc-v1", 1L)
      second.rows.map(parse).map(_.op) shouldBe Vector(CdcOperation.Update, CdcOperation.Delete)
      factory.engines.head.handles.asScala.drop(1).head.calls.get() shouldBe 0
      Await.result(source.acknowledgeCommittedBatch(cdcNode(), second, _ => ()), 2.seconds) shouldBe Done
      eventually(factory.engines.head.handles.asScala.drop(1).head.calls.get() == 1)

      queue.cancel()
      eventually(factory.engines.head.closed.get())
      eventually(!factory.engines.head.thread.get().isAlive)
    }

    "resume at the checkpoint sequence plus one with the same BatchId rules" in {
      val factory = new FakeEngineFactory(Vector(EnginePlan(Vector(Vector(
        record("c", None, Some(row(43, "created")), snapshot = false, position = 300L)
      )))))
      val source = testSource(factory, () => validMetadata.connection())
      val resume = checkpoint(expectedBoundary, sequence = 4L)
      val queue = source.createBatches(cdcNode(), "execution-resume", expectedBoundary, Some(resume), _ => ())
        .runWith(Sink.queue())

      val batch = pull(queue)
      batch.batchSequence shouldBe 5L
      batch.batchId shouldBe BatchId.sha256(
        "execution-resume", "source-1", "mysql-cdc:orders-cdc-v1", 5L
      )
      Await.result(source.acknowledgeCommittedBatch(cdcNode(), batch, _ => ()), 2.seconds)
      queue.cancel()
      eventually(factory.engines.head.closed.get())
    }

    "fail closed instead of overwriting an acknowledgement entry for the same batch ID" in {
      val duplicate = Vector(record("r", None, Some(row(42, "new")), snapshot = true, position = 100L))
      val factory = new FakeEngineFactory(Vector(
        EnginePlan(Vector(duplicate)),
        EnginePlan(Vector(duplicate))
      ))
      val source = testSource(factory, () => validMetadata.connection())
      val firstQueue = source.createBatches(cdcNode(), "execution-duplicate", expectedBoundary, None, _ => ())
        .runWith(Sink.queue())
      val first = pull(firstQueue)
      val secondQueue = source.createBatches(cdcNode(), "execution-duplicate", expectedBoundary, None, _ => ())
        .runWith(Sink.queue())
      val collision = intercept[IllegalStateException] {
        Await.result(secondQueue.pull(), 2.seconds)
      }
      collision.getMessage should include("duplicate")
      factory.engines(0).handles.asScala.head.calls.get() shouldBe 0
      factory.engines(1).handles.asScala.head.calls.get() shouldBe 0

      Await.result(source.acknowledgeCommittedBatch(cdcNode(), first, _ => ()), 2.seconds)
      eventually(factory.engines(0).handles.asScala.head.calls.get() == 1)
      factory.engines(1).handles.asScala.head.calls.get() shouldBe 0
      firstQueue.cancel()
      secondQueue.cancel()
      eventually(factory.engines.forall(_.closed.get()))
    }

    "allow two distinct materializations to retain and acknowledge one pending batch each" in {
      val records = Vector(record("r", None, Some(row(42, "new")), snapshot = true, position = 100L))
      val factory = new FakeEngineFactory(Vector(
        EnginePlan(Vector(records)),
        EnginePlan(Vector(records))
      ))
      val source = testSource(factory, () => validMetadata.connection())
      val firstQueue = source.createBatches(cdcNode(), "execution-distinct-1", expectedBoundary, None, _ => ())
        .runWith(Sink.queue())
      val secondQueue = source.createBatches(cdcNode(), "execution-distinct-2", expectedBoundary, None, _ => ())
        .runWith(Sink.queue())

      val first = pull(firstQueue)
      val second = pull(secondQueue)
      first.batchId should not be second.batchId
      factory.engines.foreach(_.handles.asScala.head.calls.get() shouldBe 0)

      Await.result(source.acknowledgeCommittedBatch(cdcNode(), first, _ => ()), 2.seconds)
      Await.result(source.acknowledgeCommittedBatch(cdcNode(), second, _ => ()), 2.seconds)
      eventually(factory.engines.forall(_.handles.asScala.head.calls.get() == 1))

      firstQueue.cancel()
      secondQueue.cancel()
      eventually(factory.engines.forall(_.closed.get()))
    }

    "fail the bridge without retaining an acknowledgement when close wins registration" in {
      val registry = new CdcAcknowledgementRegistry
      val scope = registry.openScope()
      val bridge = new DebeziumBatchBridge
      val handle = new FakeCommitHandle
      val publisherFailure = new AtomicReference[Throwable]()
      val publisher = new Thread(() => {
        try bridge.publish("race-batch", Vector("{}"), "{}", handle)
        catch { case error: Throwable => publisherFailure.set(error) }
      })
      publisher.setDaemon(true)
      publisher.start()
      val delivered = bridge.take()

      registry.close(scope)
      val failure = intercept[IllegalStateException] {
        registry.register(scope, delivered.batchId, bridge, delivered.deliveryToken)
      }

      failure.getMessage should include("closed")
      publisher.join(2000L)
      publisher.isAlive shouldBe false
      publisherFailure.get() should not be null
      handle.calls.get() shouldBe 0
      intercept[IllegalStateException](registry.claim(delivered.batchId))
      bridge.close()
    }

    "close the fake engine and bridge when downstream cancels an unacknowledged batch" in {
      val factory = new FakeEngineFactory(Vector(EnginePlan(Vector(Vector(
        record("r", None, Some(row(42, "new")), snapshot = true, position = 100L)
      )))))
      val source = testSource(factory, () => validMetadata.connection())
      val queue = source.createBatches(cdcNode(), "execution-cancel", expectedBoundary, None, _ => ())
        .runWith(Sink.queue())

      pull(queue)
      factory.engines.head.handles.asScala.head.calls.get() shouldBe 0
      queue.cancel()

      eventually(factory.engines.head.closed.get())
      eventually(!factory.engines.head.thread.get().isAlive)
      factory.engines.head.handles.asScala.head.calls.get() shouldBe 0
    }

    "fail the Pekko stream when the engine terminates exceptionally" in {
      val failure = new RuntimeException("engine-boom")
      val factory = new FakeEngineFactory(Vector(EnginePlan(Vector.empty, Some(failure))))
      val source = testSource(factory, () => validMetadata.connection())
      val queue = source.createBatches(cdcNode(), "execution-failure", expectedBoundary, None, _ => ())
        .runWith(Sink.queue())

      val observed = intercept[RuntimeException] {
        Await.result(queue.pull(), 2.seconds)
      }
      observed shouldBe failure
      eventually(factory.engines.head.closed.get())
    }

    "fail the Pekko stream when the engine completes normally without a requested close" in {
      val factory = new FakeEngineFactory(Vector(EnginePlan(
        batches = Vector.empty,
        completesWithoutClose = true
      )))
      val source = testSource(factory, () => validMetadata.connection())
      val queue = source.createBatches(cdcNode(), "execution-unexpected-stop", expectedBoundary, None, _ => ())
        .runWith(Sink.queue())

      val failure = intercept[IllegalStateException] {
        Await.result(queue.pull(), 2.seconds)
      }

      failure.getMessage should include("unexpectedly")
      eventually(factory.engines.head.closed.get())
    }

    "re-query current metadata and reject schema drift before creating the engine" in {
      val factory = new FakeEngineFactory(Vector(EnginePlan(Vector(Vector(
        record("r", None, Some(row(42, "new")), snapshot = true, position = 100L)
      )))))
      val drifted = MetadataFixture(
        primaryKeys = Vector(primaryKey("id", 1)),
        columns = validColumns :+ column(
          "new_column", Types.VARCHAR, "VARCHAR", DatabaseMetaData.columnNullable, 4, 64, 0
        )
      )
      val source = testSource(factory, () => drifted.connection())
      val queue = source.createBatches(cdcNode(), "execution-schema-drift", expectedBoundary, None, _ => ())
        .runWith(Sink.queue())

      val failure = intercept[IllegalArgumentException] {
        Await.result(queue.pull(), 2.seconds)
      }

      failure.getMessage should include("schema")
      factory.createCalls.get() shouldBe 0
      drifted.closedConnections.get() shouldBe 1
    }

    "validate the supplied boundary and recovery identity before materializing an engine" in {
      val factory = new FakeEngineFactory(Vector.empty)
      val source = testSource(factory, () => validMetadata.connection())
      val resume = checkpoint(expectedBoundary, sequence = 1L)
      val invalid = Vector(
        expectedBoundary.copy(sourceNodeId = "other-node") -> None,
        expectedBoundary.copy(partitionId = "mysql-cdc:other") -> None,
        expectedBoundary.copy(upperBound = Some(expectedStreamIdentity.replace("source_orders", "other_table"))) -> None,
        expectedBoundary -> Some(resume.copy(sourceNodeId = "other-node")),
        expectedBoundary -> Some(resume.copy(cursor = resume.cursor.copy(upperBound = "other-identity")))
      )

      invalid.foreach { case (boundary, recovery) =>
        intercept[IllegalArgumentException] {
          source.createBatches(cdcNode(), "execution-invalid", boundary, recovery, _ => ())
        }
      }
      factory.createCalls.get() shouldBe 0
    }

    "log only safe connector identity and never a Properties rendering or either password" in {
      val factory = new FakeEngineFactory(Vector(EnginePlan(Vector(Vector(
        record("r", None, Some(row(42, "new")), snapshot = true, position = 100L)
      )))))
      val source = testSource(factory, () => validMetadata.connection())
      val logs = mutable.Buffer.empty[String]
      val boundary = Await.result(source.discoverBoundary(cdcNode(), None, logs += _), 2.seconds)
      val queue = source.createBatches(cdcNode(), "execution-log", boundary, None, logs += _)
        .runWith(Sink.queue())
      val batch = pull(queue)
      val propertyRendering = MySQLCdcSourceNode.connectorProperties(sourceConfig, stateConfig).toString

      logs.mkString("\n") should include("orders-cdc-v1")
      logs.mkString("\n") should not include sourceSecret
      logs.mkString("\n") should not include stateSecret
      logs.mkString("\n") should not include "database.password"
      logs.mkString("\n") should not include propertyRendering

      Await.result(source.acknowledgeCommittedBatch(cdcNode(), batch, logs += _), 2.seconds)
      queue.cancel()
      eventually(factory.engines.head.closed.get())
    }

    "reject the legacy row source API" in {
      val source = testSource(new FakeEngineFactory(Vector.empty), () => validMetadata.connection())

      val failure = intercept[UnsupportedOperationException] {
        Await.result(source.createSource(cdcNode(), _ => ()).runWith(Sink.seq), 2.seconds)
      }

      failure.getMessage shouldBe "mysql.cdc requires checkpoint-aware execution"
    }
  }

  "RealDebeziumEngineAccess" should {
    "mark every exact delivered RecordChangeEvent in order and finish the batch once" in {
      val sourceRecords = Vector(
        record("u", Some(row(42, "new")), Some(row(42, "paid")), snapshot = false, position = 200L),
        record("d", Some(row(42, "paid")), None, snapshot = false, position = 220L)
      )
      val events = sourceRecords.map(changeEvent)
      val committer = new RecordingCommitter

      new DebeziumRecordCommitHandle(events.asJava, committer).markProcessedAndFinished()

      committer.processed shouldBe events
      committer.finished.get() shouldBe 1
    }

    "run on one named daemon executor and terminate that thread on bounded close" in {
      val engine = new BlockingDebeziumEngine
      val properties = new Properties()
      properties.setProperty("name", "orders-cdc-v1")
      val access = new RealDebeziumEngineAccess(properties, (_, _) => engine)

      val done = access.start((_, _) => ())
      engine.started.await(2, TimeUnit.SECONDS) shouldBe true
      val worker = engine.thread.get()
      worker.getName should startWith("mysql-cdc-debezium-engine-")
      worker.isDaemon shouldBe true

      access.close()

      Await.result(done, 2.seconds) shouldBe Done
      worker.isAlive shouldBe false
      engine.closeCalls.get() shouldBe 1
    }

    "redact source and state passwords from real Debezium JDBC-store startup failure logs" in {
      val actualSourceSecret = "real-source-secret-internal-log-boundary"
      val actualStateSecret = "real-state-secret-internal-log-boundary"
      val properties = MySQLCdcSourceNode.connectorProperties(
        sourceConfig.copy(password = actualSourceSecret),
        stateConfig.copy(
          jdbcUrl = "jdbc:no-such-driver:task-6-redaction",
          password = actualStateSecret
        )
      )
      properties.setProperty(
        "connector.class",
        "org.apache.kafka.connect.file.FileStreamSourceConnector"
      )
      properties.setProperty("topic", "task-6-redaction")
      properties.remove("offset.storage.jdbc.connection.url")
      val logger = LoggerFactory.getLogger("io.debezium.embedded.async.AsyncEmbeddedEngine")
        .asInstanceOf[LogbackLogger]
      val jdbcLogger = LoggerFactory.getLogger("io.debezium.storage.jdbc")
        .asInstanceOf[LogbackLogger]
      val previousAdditive = logger.isAdditive
      val previousJdbcAdditive = jdbcLogger.isAdditive
      val initialTurboFilters = logger.getLoggerContext.getTurboFilterList.size()
      val appender = new ListAppender[ILoggingEvent]()
      val jdbcAppender = new ListAppender[ILoggingEvent]()
      appender.setContext(logger.getLoggerContext)
      jdbcAppender.setContext(jdbcLogger.getLoggerContext)
      appender.start()
      jdbcAppender.start()
      logger.addAppender(appender)
      jdbcLogger.addAppender(jdbcAppender)
      logger.setAdditive(false)
      jdbcLogger.setAdditive(false)
      val access = new RealDebeziumEngineAccess(properties)

      try {
        intercept[IllegalStateException] {
          Await.result(access.start((_, _) => ()), 10.seconds)
        }
        val rendered = (appender.list.asScala ++ jdbcAppender.list.asScala).map { event =>
          event.getFormattedMessage + Option(event.getThrowableProxy)
            .map(proxy => "\n" + ThrowableProxyUtil.asString(proxy))
            .getOrElse("")
        }.mkString("\n")

        rendered should include("jdbc.connection.url")
        rendered should not include actualSourceSecret
        rendered should not include actualStateSecret
      } finally {
        try access.close()
        finally {
          logger.detachAppender(appender)
          jdbcLogger.detachAppender(jdbcAppender)
          logger.setAdditive(previousAdditive)
          jdbcLogger.setAdditive(previousJdbcAdditive)
          appender.stop()
          jdbcAppender.stop()
        }
      }
      logger.getLoggerContext.getTurboFilterList.size() shouldBe initialTurboFilters
    }

    "scope secret filtering to Debezium namespaces while ordinary application logs remain visible" in {
      val applicationLogger = LoggerFactory.getLogger(
        "cn.xuyinyin.magic.workflow.nodes.sources.SecretBoundaryApplication"
      ).asInstanceOf[LogbackLogger]
      val debeziumLogger = LoggerFactory.getLogger(
        "io.debezium.storage.jdbc.JdbcCommonConfig"
      ).asInstanceOf[LogbackLogger]
      val applicationAppender = new ListAppender[ILoggingEvent]()
      val debeziumAppender = new ListAppender[ILoggingEvent]()
      val previousApplicationAdditive = applicationLogger.isAdditive
      val previousDebeziumAdditive = debeziumLogger.isAdditive
      val initialTurboFilters = applicationLogger.getLoggerContext.getTurboFilterList.size()
      Vector(applicationAppender, debeziumAppender).foreach { appender =>
        appender.setContext(applicationLogger.getLoggerContext)
        appender.start()
      }
      applicationLogger.addAppender(applicationAppender)
      debeziumLogger.addAppender(debeziumAppender)
      applicationLogger.setAdditive(false)
      debeziumLogger.setAdditive(false)
      val properties = new Properties()
      properties.setProperty("database.password", "x")
      val engine = new BlockingDebeziumEngine
      val access = new RealDebeziumEngineAccess(properties, (_, _) => engine)

      try {
        access.start((_, _) => ())
        engine.started.await(2, TimeUnit.SECONDS) shouldBe true

        applicationLogger.info("text")
        debeziumLogger.info("x")

        applicationAppender.list.asScala.map(_.getFormattedMessage) should contain("text")
        debeziumAppender.list.asScala.map(_.getFormattedMessage) should not contain "x"
      } finally {
        try access.close()
        finally {
          applicationLogger.detachAppender(applicationAppender)
          debeziumLogger.detachAppender(debeziumAppender)
          applicationLogger.setAdditive(previousApplicationAdditive)
          debeziumLogger.setAdditive(previousDebeziumAdditive)
          applicationAppender.stop()
          debeziumAppender.stop()
        }
      }
      applicationLogger.getLoggerContext.getTurboFilterList.size() shouldBe initialTurboFilters
    }

    "keep concurrent secret filters independent without suppressing ordinary logs" in {
      val applicationLogger = LoggerFactory.getLogger(
        "cn.xuyinyin.magic.workflow.nodes.sources.ConcurrentSecretBoundaryApplication"
      ).asInstanceOf[LogbackLogger]
      val debeziumLogger = LoggerFactory.getLogger(
        "io.debezium.embedded.async.AsyncEmbeddedEngine"
      ).asInstanceOf[LogbackLogger]
      val applicationAppender = new ListAppender[ILoggingEvent]()
      val debeziumAppender = new ListAppender[ILoggingEvent]()
      val previousApplicationAdditive = applicationLogger.isAdditive
      val previousDebeziumAdditive = debeziumLogger.isAdditive
      val context = applicationLogger.getLoggerContext
      val initialTurboFilters = context.getTurboFilterList.size()
      Vector(applicationAppender, debeziumAppender).foreach { appender =>
        appender.setContext(context)
        appender.start()
      }
      applicationLogger.addAppender(applicationAppender)
      debeziumLogger.addAppender(debeziumAppender)
      applicationLogger.setAdditive(false)
      debeziumLogger.setAdditive(false)
      val firstProperties = new Properties()
      firstProperties.setProperty("database.password", "x")
      val secondProperties = new Properties()
      secondProperties.setProperty("database.password", "q")
      val firstEngine = new BlockingDebeziumEngine
      val secondEngine = new BlockingDebeziumEngine
      val firstAccess = new RealDebeziumEngineAccess(firstProperties, (_, _) => firstEngine)
      val secondAccess = new RealDebeziumEngineAccess(secondProperties, (_, _) => secondEngine)

      try {
        firstAccess.start((_, _) => ())
        secondAccess.start((_, _) => ())
        firstEngine.started.await(2, TimeUnit.SECONDS) shouldBe true
        secondEngine.started.await(2, TimeUnit.SECONDS) shouldBe true
        context.getTurboFilterList.size() shouldBe initialTurboFilters + 2

        applicationLogger.info("x q")
        debeziumLogger.info("x")
        debeziumLogger.info("q")
        applicationAppender.list.asScala.map(_.getFormattedMessage) should contain("x q")
        debeziumAppender.list shouldBe empty

        firstAccess.close()
        context.getTurboFilterList.size() shouldBe initialTurboFilters + 1
        debeziumLogger.info("x")
        debeziumLogger.info("q")
        debeziumAppender.list.asScala.map(_.getFormattedMessage) should contain("x")
        debeziumAppender.list.asScala.map(_.getFormattedMessage) should not contain "q"

        applicationLogger.info("x q")
        applicationAppender.list.asScala.count(_.getFormattedMessage == "x q") shouldBe 2

        secondAccess.close()
        context.getTurboFilterList.size() shouldBe initialTurboFilters
        debeziumLogger.info("q")
        debeziumAppender.list.asScala.map(_.getFormattedMessage) should contain("q")
      } finally {
        try firstAccess.close()
        finally {
          try secondAccess.close()
          finally {
            applicationLogger.detachAppender(applicationAppender)
            debeziumLogger.detachAppender(debeziumAppender)
            applicationLogger.setAdditive(previousApplicationAdditive)
            debeziumLogger.setAdditive(previousDebeziumAdditive)
            applicationAppender.stop()
            debeziumAppender.stop()
          }
        }
      }
      context.getTurboFilterList.size() shouldBe initialTurboFilters
    }

    "retry close after the runner leaves the Debezium task-starting state" in {
      val engine = new StartingDebeziumEngine
      val access = new RealDebeziumEngineAccess(new Properties(), (_, _) => engine)
      val done = access.start((_, _) => ())
      engine.started.await(2, TimeUnit.SECONDS) shouldBe true

      access.close()

      Await.result(done, 2.seconds) shouldBe Done
      engine.closeCalls.get() should be >= 2
      engine.thread.get().isAlive shouldBe false
    }

    "preserve and report a terminal engine close failure" in {
      val engine = new UncloseableDebeziumEngine
      val access = new RealDebeziumEngineAccess(new Properties(), (_, _) => engine)
      val done = access.start((_, _) => ())
      engine.started.await(2, TimeUnit.SECONDS) shouldBe true

      val failure = intercept[IllegalStateException](access.close())

      failure.getMessage should include("close")
      intercept[IllegalStateException](Await.result(done, 2.seconds))
      engine.closeCalls.get() should be >= 1
      engine.thread.get().isAlive shouldBe false
    }

    "treat an unrequested normal runner return as exceptional termination" in {
      val engine = new ReturningDebeziumEngine
      val access = new RealDebeziumEngineAccess(new Properties(), (_, _) => engine)

      val failure = intercept[IllegalStateException] {
        Await.result(access.start((_, _) => ()), 2.seconds)
      }

      failure.getMessage should include("unexpectedly")
      access.close()
    }
  }

  private def cdcNode(): WorkflowDSL.Node = WorkflowDSL.Node(
    id = "source-1",
    `type` = "source",
    nodeType = "mysql.cdc",
    label = "MySQL CDC",
    position = WorkflowDSL.Position(0, 0),
    config = JsObject(
      "connectorId" -> JsString("orders-cdc-v1"),
      "host" -> JsString("mysql"),
      "port" -> JsNumber(3306),
      "database" -> JsString("pekko_workflow"),
      "table" -> JsString("source_orders"),
      "username" -> JsString("pekko_cdc"),
      "passwordEnv" -> JsString("MYSQL_CDC_PASSWORD"),
      "serverId" -> JsNumber(54001),
      "maxBatchSize" -> JsNumber(100),
      "pollIntervalMillis" -> JsNumber(500)
    )
  )

  private def testSource(
    factory: DebeziumEngineFactory,
    connection: () => Connection
  ): MySQLCdcSourceNode = new MySQLCdcSourceNode(factory, () => stateConfig) {
    override protected[sources] def getenv(name: String): Option[String] =
      Option.when(name == "MYSQL_CDC_PASSWORD")(sourceSecret)

    override protected[sources] def openMetadataConnection(config: MySQLCdcSourceConfig): Connection =
      connection()
  }

  private def checkpoint(boundary: SnapshotBoundary, sequence: Long): BatchCheckpoint = BatchCheckpoint(
    sourceNodeId = boundary.sourceNodeId,
    partitionId = boundary.partitionId,
    batchSequence = sequence,
    batchId = BatchId.sha256("previous-execution", boundary.sourceNodeId, boundary.partitionId, sequence),
    cursor = SourceCursor("mysql.binlog.v1", "{\"offset\":{},\"version\":1}", boundary.upperBound.value),
    sourceRowsScanned = 1L,
    targetRowsWritten = 1L
  )

  private def validColumns: Vector[Map[String, Any]] = Vector(
    column("id", Types.INTEGER, "INT", DatabaseMetaData.columnNoNulls, 1, 11, 0),
    column("status", Types.VARCHAR, "VARCHAR", DatabaseMetaData.columnNullable, 2, 255, 0),
    column("amount", Types.DECIMAL, "DECIMAL", DatabaseMetaData.columnNullable, 3, 12, 2)
  )

  private def validMetadata: MetadataFixture = MetadataFixture(
    primaryKeys = Vector(primaryKey("id", 1)),
    columns = validColumns
  )

  private def primaryKey(name: String, sequence: Int): Map[String, Any] = Map(
    "TABLE_CAT" -> "pekko_workflow",
    "TABLE_SCHEM" -> null,
    "TABLE_NAME" -> "source_orders",
    "COLUMN_NAME" -> name,
    "KEY_SEQ" -> sequence
  )

  private def column(
    name: String,
    jdbcType: Int,
    typeName: String,
    nullable: Int,
    ordinal: Int,
    size: Int,
    scale: Int
  ): Map[String, Any] = Map(
    "TABLE_CAT" -> "pekko_workflow",
    "TABLE_SCHEM" -> null,
    "TABLE_NAME" -> "source_orders",
    "COLUMN_NAME" -> name,
    "DATA_TYPE" -> jdbcType,
    "TYPE_NAME" -> typeName,
    "NULLABLE" -> nullable,
    "ORDINAL_POSITION" -> ordinal,
    "COLUMN_SIZE" -> size,
    "DECIMAL_DIGITS" -> scale
  )

  private def row(id: Int, status: String): Struct = new Struct(rowSchema)
    .put("id", id)
    .put("status", status)
    .put("amount", new JBigDecimal("12.30"))

  private def record(
    operation: String,
    before: Option[Struct],
    after: Option[Struct],
    snapshot: Boolean,
    position: Long
  ): SourceRecord = {
    val key = new Struct(keySchema).put("id", 42)
    val source = new Struct(sourceSchema)
      .put("db", "pekko_workflow")
      .put("table", "source_orders")
      .put("snapshot", if (snapshot) "true" else "false")
      .put("file", "binlog.000001")
      .put("pos", position)
      .put("row", 0)
      .put("ts_ms", 1788100000000L + position)
    val value = new Struct(envelopeSchema)
      .put("before", before.orNull)
      .put("after", after.orNull)
      .put("source", source)
      .put("op", operation)
      .put("ts_ms", 1788100000000L + position)
    val offset = Map[String, AnyRef](
      "snapshot" -> Boolean.box(snapshot),
      "file" -> "binlog.000001",
      "pos" -> Long.box(position),
      "row" -> Int.box(0)
    ).asJava

    new SourceRecord(
      Map[String, String]("server" -> "orders-cdc-v1").asJava,
      offset,
      "orders-cdc-v1.pekko_workflow.source_orders",
      keySchema,
      key,
      envelopeSchema,
      value
    )
  }

  private def parse(json: String): MySQLCdcEnvelope = MySQLCdcEnvelope.parse(json) match {
    case Right(value) => value
    case Left(error) => fail(s"expected canonical CDC envelope, got ${error.message}")
  }

  private def pull(queue: org.apache.pekko.stream.scaladsl.SinkQueueWithCancel[SourceBatch]): SourceBatch =
    Await.result(queue.pull(), 2.seconds).value

  private def eventually(assertion: => Boolean): Unit = {
    val deadline = System.nanoTime() + 2.seconds.toNanos
    while (!assertion && System.nanoTime() < deadline) Thread.`yield`()
    assertion shouldBe true
  }

  private def changeEvent(sourceRecord: SourceRecord): RecordChangeEvent[SourceRecord] =
    new RecordChangeEvent[SourceRecord] {
      override def record(): SourceRecord = sourceRecord
    }

  private final class RecordingCommitter
    extends DebeziumEngine.RecordCommitter[RecordChangeEvent[SourceRecord]] {
    private val marked = mutable.Buffer.empty[RecordChangeEvent[SourceRecord]]
    val finished = new AtomicInteger(0)

    def processed: Vector[RecordChangeEvent[SourceRecord]] = marked.toVector

    override def markProcessed(record: RecordChangeEvent[SourceRecord]): Unit = marked += record
    override def markBatchFinished(): Unit = finished.incrementAndGet()
    override def markProcessed(
      record: RecordChangeEvent[SourceRecord],
      offsets: DebeziumEngine.Offsets
    ): Unit = marked += record
    override def buildOffsets(): DebeziumEngine.Offsets = null
  }

  private final class BlockingDebeziumEngine extends DebeziumEngine[RecordChangeEvent[SourceRecord]] {
    val started = new CountDownLatch(1)
    val thread = new AtomicReference[Thread]()
    val closeCalls = new AtomicInteger(0)
    private val release = new CountDownLatch(1)

    override def run(): Unit = {
      thread.set(Thread.currentThread())
      started.countDown()
      release.await()
      ()
    }

    override def close(): Unit = {
      closeCalls.incrementAndGet()
      release.countDown()
    }
  }

  private final class StartingDebeziumEngine extends DebeziumEngine[RecordChangeEvent[SourceRecord]] {
    val started = new CountDownLatch(1)
    val thread = new AtomicReference[Thread]()
    val closeCalls = new AtomicInteger(0)
    private val firstCloseAttempt = new CountDownLatch(1)
    private val stopped = new CountDownLatch(1)
    private val starting = new AtomicBoolean(true)

    override def run(): Unit = {
      thread.set(Thread.currentThread())
      started.countDown()
      firstCloseAttempt.await()
      starting.set(false)
      stopped.await()
      ()
    }

    override def close(): Unit = {
      closeCalls.incrementAndGet()
      if (starting.get()) {
        firstCloseAttempt.countDown()
        throw new IllegalStateException(
          "Cannot stop engine while tasks are starting, this may lead to leaked resource. Wait for the tasks to be fully started."
        )
      }
      stopped.countDown()
    }
  }

  private final class UncloseableDebeziumEngine extends DebeziumEngine[RecordChangeEvent[SourceRecord]] {
    val started = new CountDownLatch(1)
    val thread = new AtomicReference[Thread]()
    val closeCalls = new AtomicInteger(0)

    override def run(): Unit = {
      thread.set(Thread.currentThread())
      started.countDown()
      new CountDownLatch(1).await()
    }

    override def close(): Unit = {
      closeCalls.incrementAndGet()
      throw new IllegalStateException("permanent close failure")
    }
  }

  private final class ReturningDebeziumEngine extends DebeziumEngine[RecordChangeEvent[SourceRecord]] {
    override def run(): Unit = ()
    override def close(): Unit = ()
  }

  private final class EnginePlan(
    val batches: Vector[Vector[SourceRecord]],
    val terminalFailure: Option[Throwable],
    val completesWithoutClose: Boolean
  )

  private object EnginePlan {
    def apply(
      batches: Vector[Vector[SourceRecord]],
      terminalFailure: Option[Throwable] = None,
      completesWithoutClose: Boolean = false
    ): EnginePlan = new EnginePlan(batches, terminalFailure, completesWithoutClose)
  }

  private final class FakeEngineFactory(plans: Vector[EnginePlan]) extends DebeziumEngineFactory {
    private val remaining = mutable.Queue.from(plans)
    val createCalls = new AtomicInteger(0)
    val engines = mutable.Buffer.empty[FakeEngine]
    val properties = mutable.Buffer.empty[Properties]

    override def create(actual: Properties): DebeziumEngineAccess = synchronized {
      createCalls.incrementAndGet()
      val copy = new Properties()
      copy.putAll(actual)
      properties += copy
      if (remaining.isEmpty) throw new IllegalStateException("no fake engine plan")
      val engine = new FakeEngine(remaining.dequeue())
      engines += engine
      engine
    }
  }

  private final class FakeEngine(plan: EnginePlan) extends DebeziumEngineAccess {
    val handles = new ConcurrentLinkedQueue[FakeCommitHandle]()
    val closed = new AtomicBoolean(false)
    val thread = new AtomicReference[Thread]()
    private val closeSignal = new CountDownLatch(1)
    private val completion = Promise[Done]()

    override def start(consumer: DebeziumBatchConsumer): Future[Done] = {
      val worker = new Thread(
        () => {
          try {
            thread.set(Thread.currentThread())
            plan.terminalFailure match {
              case Some(error) if plan.batches.isEmpty => throw error
              case _ => ()
            }
            plan.batches.foreach { records =>
              val handle = new FakeCommitHandle
              handles.add(handle)
              consumer.handleBatch(records, handle)
            }
            plan.terminalFailure.foreach(throw _)
            if (!plan.completesWithoutClose) closeSignal.await()
            completion.trySuccess(Done)
          } catch {
            case error: Throwable => completion.tryFailure(error)
          }
        },
        "fake-debezium-engine"
      )
      worker.setDaemon(true)
      worker.start()
      Future.successful(()).foreach(_ => ())(ExecutionContext.parasitic)
      completion.future
    }

    override def close(): Unit = {
      if (closed.compareAndSet(false, true)) {
        closeSignal.countDown()
        val worker = thread.get()
        if (worker != null && (worker ne Thread.currentThread())) {
          worker.join(1000)
          if (worker.isAlive) {
            worker.interrupt()
            worker.join(1000)
          }
        }
      }
    }
  }

  private final class FakeCommitHandle extends CdcBatchCommitHandle {
    val calls = new AtomicInteger(0)
    override def markProcessedAndFinished(): Unit = {
      calls.incrementAndGet()
      ()
    }
  }

  private final class MetadataFixture(
    primaryKeys: Vector[Map[String, Any]],
    columns: Vector[Map[String, Any]]
  ) {
    val closedConnections = new AtomicInteger(0)

    def connection(): Connection = {
      val metadata = jdbcProxy[DatabaseMetaData] {
        case ("storesUpperCaseIdentifiers", _) => Boolean.box(false)
        case ("storesLowerCaseIdentifiers", _) => Boolean.box(false)
        case ("getSearchStringEscape", _) => "\\"
        case ("getPrimaryKeys", _) => resultSet(primaryKeys)
        case ("getColumns", _) => resultSet(columns)
      }
      jdbcProxy[Connection] {
        case ("getMetaData", _) => metadata
        case ("getCatalog", _) => "pekko_workflow"
        case ("getSchema", _) => null
        case ("close", _) => closedConnections.incrementAndGet(); null
        case ("isClosed", _) => Boolean.box(closedConnections.get() > 0)
      }
    }
  }

  private object MetadataFixture {
    def apply(
      primaryKeys: Vector[Map[String, Any]],
      columns: Vector[Map[String, Any]]
    ): MetadataFixture = new MetadataFixture(primaryKeys, columns)
  }

  private def resultSet(rows: Vector[Map[String, Any]]): ResultSet = {
    var index = -1
    jdbcProxy[ResultSet] {
      case ("next", _) =>
        index += 1
        Boolean.box(index < rows.size)
      case ("getString", arguments) => Option(rows(index)(arguments.head.toString)).map(_.toString).orNull
      case ("getInt", arguments) => Int.box(rows(index)(arguments.head.toString).asInstanceOf[Int])
      case ("close", _) => null
      case ("wasNull", _) => Boolean.box(false)
    }
  }

  private def jdbcProxy[A](handler: PartialFunction[(String, Vector[AnyRef]), AnyRef])
    (implicit tag: reflect.ClassTag[A]): A = {
    val invocationHandler = new InvocationHandler {
      override def invoke(proxy: Any, method: Method, arguments: Array[AnyRef]): AnyRef = {
        val args = Option(arguments).map(_.toVector).getOrElse(Vector.empty)
        if (method.getName == "toString") s"Fake${tag.runtimeClass.getSimpleName}"
        else if (handler.isDefinedAt(method.getName -> args)) handler(method.getName -> args)
        else defaultValue(method.getReturnType)
      }
    }
    Proxy.newProxyInstance(
      tag.runtimeClass.getClassLoader,
      Array(tag.runtimeClass),
      invocationHandler
    ).asInstanceOf[A]
  }

  private def defaultValue(returnType: Class[_]): AnyRef = {
    if (!returnType.isPrimitive) null
    else if (returnType == java.lang.Boolean.TYPE) Boolean.box(false)
    else if (returnType == java.lang.Byte.TYPE) Byte.box(0.toByte)
    else if (returnType == java.lang.Short.TYPE) Short.box(0.toShort)
    else if (returnType == java.lang.Integer.TYPE) Int.box(0)
    else if (returnType == java.lang.Long.TYPE) Long.box(0L)
    else if (returnType == java.lang.Float.TYPE) Float.box(0f)
    else if (returnType == java.lang.Double.TYPE) Double.box(0d)
    else null
  }
}
