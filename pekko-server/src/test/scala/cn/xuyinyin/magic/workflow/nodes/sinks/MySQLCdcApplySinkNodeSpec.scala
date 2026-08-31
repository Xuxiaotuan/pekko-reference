package cn.xuyinyin.magic.workflow.nodes.sinks

import cn.xuyinyin.magic.testkit.STSpec
import cn.xuyinyin.magic.workflow.checkpoint.{AlreadyCommitted, BatchId, Committed, SnapshotBoundary, SourceBatch, SourceCursor}
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.cdc.{CdcOperation, MySQLCdcEnvelope, MySQLCdcSourcePosition}
import org.apache.pekko.Done
import spray.json._

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.{Connection, DriverManager, Types}
import java.time.{Instant, LocalTime}
import java.util.UUID
import java.util.concurrent.{CyclicBarrier, Executors, TimeUnit}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

class MySQLCdcApplySinkNodeSpec extends STSpec {
  import MySQLCdcApplySinkNodeSpec.SourceColumn

  private implicit val ec: ExecutionContext = ExecutionContext.global

  "MySQLCdcApplyConfig" should {
    "force the MySQL session to the same UTC zone used for instant conversion" in {
      val config = MySQLCdcApplyConfig("mysql", 3306, "target_db", "target_rows", "writer", "secret")

      MySQLCdcApplySinkNode.jdbcUrl(config) shouldBe
        "jdbc:mysql://mysql:3306/target_db?useSSL=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
    }

    "resolve only an environment-backed password" in {
      val parsed = MySQLCdcApplyConfig.parse(
        sinkNode(passwordEnv = Some("TARGET_PASSWORD")),
        name => Option.when(name == "TARGET_PASSWORD")("runtime-secret")
      )

      parsed.password shouldBe "runtime-secret"
      sinkNode(passwordEnv = Some("TARGET_PASSWORD")).toJson.compactPrint should not include "runtime-secret"
      parsed.toString should not include "runtime-secret"
      parsed.toString should include("<redacted>")
    }

    "reject inline passwords, missing credentials, unsafe identifiers, and unsupported modes" in {
      intercept[IllegalArgumentException] {
        MySQLCdcApplyConfig.parse(sinkNode(passwordEnv = None, extra = Map("password" -> JsString("secret"))))
      }.getMessage should include("passwordEnv")
      intercept[IllegalArgumentException] {
        MySQLCdcApplyConfig.parse(sinkNode(passwordEnv = None))
      }.getMessage should include("passwordEnv")

      Vector("target rows", "target`rows", "target;rows", "id-name").foreach { unsafe =>
        intercept[IllegalArgumentException] {
          MySQLCdcApplyConfig.parse(sinkNode(table = unsafe), _ => Some("secret"))
        }.getMessage should include("identifier")
      }
      intercept[IllegalArgumentException] {
        MySQLCdcApplyConfig.parse(sinkNode(extra = Map("mode" -> JsString("insert"))), _ => Some("secret"))
      }.getMessage should include("mode")
    }

    "parse an engine-prepared password only through the trusted runtime entry point" in {
      val runtimeNode = sinkNode(passwordEnv = None, extra = Map("password" -> JsString("runtime-secret")))

      MySQLCdcApplyConfig.parseTrustedRuntime(runtimeNode).password shouldBe "runtime-secret"
      intercept[IllegalArgumentException](MySQLCdcApplyConfig.parse(runtimeNode)).getMessage should include("passwordEnv")
    }

    "require non-empty connection fields and a valid port" in {
      intercept[IllegalArgumentException] {
        MySQLCdcApplyConfig.parse(sinkNode(host = ""), _ => Some("secret"))
      }.getMessage should include("host")
      intercept[IllegalArgumentException] {
        MySQLCdcApplyConfig.parse(sinkNode(username = ""), _ => Some("secret"))
      }.getMessage should include("username")
      intercept[IllegalArgumentException] {
        MySQLCdcApplyConfig.parse(sinkNode(port = 0), _ => Some("secret"))
      }.getMessage should include("port")
    }
  }

  "MySQLCdcApplySinkNode" should {
    "consume an engine-prepared runtime password through the real sink parser" in {
      val connectionAttempted = new java.util.concurrent.atomic.AtomicBoolean(false)
      val sink = new MySQLCdcApplySinkNode {
        override protected[sinks] def openConnection(config: MySQLCdcApplyConfig): Connection = {
          config.password shouldBe "runtime-secret"
          connectionAttempted.set(true)
          throw new IllegalStateException("trusted runtime parse reached JDBC")
        }
      }
      val runtimeNode = sinkNode(passwordEnv = None, extra = Map("password" -> JsString("runtime-secret")))

      intercept[IllegalStateException] {
        Await.result(sink.validateReady(runtimeNode, _ => ()), 5.seconds)
      }.getMessage should include("trusted runtime parse reached JDBC")
      connectionAttempted.get shouldBe true
    }

    "report its checkpoint-only node type" in withFixture() { fixture =>
      fixture.node.nodeType shouldBe "mysql.cdc.apply"
      intercept[UnsupportedOperationException] {
        fixture.node.createSink(fixture.sinkNode, _ => ())
      }.getMessage should include("checkpoint")
    }

    "keep the runtime password out of config rendering, logs, and readiness errors" in {
      val logs = ArrayBuffer.empty[String]
      val sink = new MySQLCdcApplySinkNode {
        override protected[sinks] def getenv(name: String): Option[String] = Some("runtime-secret")
        override protected[sinks] def openConnection(config: MySQLCdcApplyConfig): Connection = {
          config.toString should not include "runtime-secret"
          throw new IllegalStateException("jdbc setup failed")
        }
      }

      val failure = intercept[IllegalStateException] {
        Await.result(sink.validateReady(sinkNode(), logs += _), 5.seconds)
      }
      failure.getMessage should not include "runtime-secret"
      logs.mkString("\n") should not include "runtime-secret"
    }

    "validate a compatible target and ledger without changing either" in withFixture() { fixture =>
      Await.result(fixture.node.validateReady(fixture.sinkNode, _ => ()), 5.seconds) shouldBe Done
      Await.result(fixture.node.validateSourceBoundary(fixture.sinkNode, fixture.boundary(), _ => ()), 5.seconds) shouldBe Done
      fixture.targetCount shouldBe 0
      fixture.ledgerCount shouldBe 0
    }

    "reject a target with no primary key" in withFixture(
      targetDdl = compatibleTarget.replace(" PRIMARY KEY", "")
    ) { fixture =>
      readinessFailure(fixture) should include("exactly one primary key")
    }

    "reject a target with a composite primary key" in withFixture(
      targetDdl = compatibleTarget.replace("id BIGINT PRIMARY KEY", "id BIGINT, PRIMARY KEY (id, run_id)")
    ) { fixture =>
      readinessFailure(fixture) should include("exactly one primary key")
    }

    "reject unsupported BLOB and spatial target columns" in {
      Vector("payload BLOB NULL", "shape GEOMETRY NULL").foreach { unsupported =>
        withFixture(targetDdl = compatibleTarget.dropRight(1) + s", $unsupported)") { fixture =>
          readinessFailure(fixture) should include("unsupported target column type")
        }
      }
    }

    "reject a source primary key name mismatch before batches are requested" in withFixture() { fixture =>
      val failure = intercept[IllegalStateException] {
        Await.result(fixture.node.validateSourceBoundary(
          fixture.sinkNode,
          fixture.boundary(primaryKey = "source_id"),
          _ => ()
        ), 5.seconds)
      }
      failure.getMessage should include("primary key")
    }

    "reject a source column missing from the target before batches are requested" in withFixture() { fixture =>
      val failure = intercept[IllegalStateException] {
        Await.result(fixture.node.validateSourceBoundary(
          fixture.sinkNode,
          fixture.boundary(columns = sourceColumns :+ SourceColumn("missing_column", Types.VARCHAR, "VARCHAR", 1, 7, 255, 0)),
          _ => ()
        ), 5.seconds)
      }
      failure.getMessage should include("missing_column")
    }

    "reject a required extra target column without a default" in withFixture(
      targetDdl = compatibleTarget.dropRight(1) + ", required_extra VARCHAR(20) NOT NULL)"
    ) { fixture =>
      val failure = intercept[IllegalStateException] {
        Await.result(fixture.node.validateSourceBoundary(fixture.sinkNode, fixture.boundary(), _ => ()), 5.seconds)
      }
      failure.getMessage should include("required_extra")
    }

    "allow nullable or defaulted extra target columns" in withFixture(
      targetDdl = compatibleTarget.dropRight(1) +
        ", nullable_extra VARCHAR(20) NULL, defaulted_extra VARCHAR(20) NOT NULL DEFAULT 'ok')"
    ) { fixture =>
      Await.result(fixture.node.validateSourceBoundary(fixture.sinkNode, fixture.boundary(), _ => ()), 5.seconds) shouldBe Done
    }

    "validate independent executions without retaining process-local fingerprint state" in withFixture() { fixture =>
      val original = fixture.boundary()
      Await.result(fixture.node.validateSourceBoundary(fixture.sinkNode, original, _ => ()), 5.seconds) shouldBe Done

      val changed = original.copy(upperBound = original.upperBound.map(value => canonicalJson(JsObject(
        value.parseJson.asJsObject.fields.updated("schemaFingerprint", JsString("f" * 64))
      ))))
      Await.result(fixture.node.validateSourceBoundary(fixture.sinkNode, changed, _ => ()), 5.seconds) shouldBe Done
    }

    "reject lossy and unsupported source-to-target type mappings during boundary validation" in {
      val cases = Vector(
        compatibleTarget.replace("run_id VARCHAR(40)", "run_id BIGINT") -> sourceColumns -> "run_id",
        compatibleTarget.replace("amount DECIMAL(18,2)", "amount DOUBLE") -> sourceColumns -> "amount",
        compatibleTarget -> sourceColumns.map(column =>
          if (column.name == "note") column.copy(jdbcType = Types.BLOB, typeName = "BLOB") else column
        ) -> "unsupported source"
      )
      cases.foreach { case ((ddl, columns), clue) =>
        withFixture(targetDdl = ddl) { fixture =>
          val failure = intercept[IllegalStateException] {
            Await.result(fixture.node.validateSourceBoundary(fixture.sinkNode, fixture.boundary(columns = columns), _ => ()), 5.seconds)
          }
          withClue(clue) { failure.getMessage.toLowerCase should (include("type") or include("unsupported")) }
        }
      }
    }

    "reject every unsigned integral source type during boundary validation" in withFixture() { fixture =>
      Vector(
        Types.TINYINT -> "TINYINT UNSIGNED",
        Types.SMALLINT -> "SMALLINT UNSIGNED",
        Types.INTEGER -> "INT UNSIGNED",
        Types.BIGINT -> "BIGINT UNSIGNED"
      ).foreach { case (jdbcType, typeName) =>
        val columns = sourceColumns.map(column =>
          if (column.name == "id") column.copy(jdbcType = jdbcType, typeName = typeName) else column
        )
        val failure = intercept[IllegalStateException] {
          Await.result(fixture.node.validateSourceBoundary(fixture.sinkNode, fixture.boundary(columns = columns), _ => ()), 5.seconds)
        }
        withClue(typeName) { failure.getMessage should include("UNSIGNED") }
      }
    }

    "reject signed sources mapped to unsigned integral target types" in {
      Vector(
        ("INT UNSIGNED", "INT", Types.INTEGER, "INT"),
        ("BIGINT UNSIGNED", "BIGINT", Types.BIGINT, "BIGINT")
      ).foreach { case (targetTypeName, ddlType, sourceJdbcType, sourceTypeName) =>
        val ddl = compatibleTarget.replace("id BIGINT PRIMARY KEY", s"id $ddlType PRIMARY KEY")
        withFixture(targetDdl = ddl, targetTypeOverrides = Map("id" -> targetTypeName)) { fixture =>
          val columns = sourceColumns.map(column =>
            if (column.name == "id") column.copy(jdbcType = sourceJdbcType, typeName = sourceTypeName) else column
          )
          val failure = intercept[IllegalStateException] {
            Await.result(
              fixture.node.validateSourceBoundary(fixture.sinkNode, fixture.boundary(columns = columns), _ => ()),
              5.seconds
            )
          }
          withClue(targetTypeName) { failure.getMessage should include("UNSIGNED") }
        }
      }
    }

    "allow a signed integral source to widen without loss" in withFixture() { fixture =>
      val columns = sourceColumns.map(column =>
        if (column.name == "id") column.copy(jdbcType = Types.INTEGER, typeName = "INT") else column
      )
      Await.result(
        fixture.node.validateSourceBoundary(fixture.sinkNode, fixture.boundary(columns = columns), _ => ()),
        5.seconds
      ) shouldBe Done
    }

    "reject a boundary partition that names a different connector" in withFixture() { fixture =>
      val failure = intercept[IllegalStateException] {
        Await.result(fixture.node.validateSourceBoundary(
          fixture.sinkNode,
          fixture.boundary().copy(partitionId = "mysql-cdc:other-connector"),
          _ => ()
        ), 5.seconds)
      }
      failure.getMessage should include("connector")
    }

    "apply snapshot, create, update, and delete events in original order atomically with the ledger" in withFixture() { fixture =>
      val events = Vector(
        fixture.event(CdcOperation.Read, 1L, afterStatus = Some("new")),
        fixture.event(CdcOperation.Create, 2L, afterStatus = Some("new")),
        fixture.event(CdcOperation.Update, 1L, beforeStatus = Some("new"), afterStatus = Some("paid")),
        fixture.event(CdcOperation.Delete, 2L, beforeStatus = Some("new"))
      )
      val batch = fixture.batch(events)

      val result = Await.result(
        fixture.node.commitBatch(fixture.sinkNode, "workflow-cdc", "execution-cdc", batch, events.map(_.canonicalJson), _ => ()),
        5.seconds
      )

      result shouldBe a[Committed]
      val checkpoint = result.asInstanceOf[Committed].checkpoint
      checkpoint.sourceRowsScanned shouldBe 4L
      checkpoint.targetRowsWritten shouldBe 4L
      fixture.selectStatus(1L) shouldBe Some("paid")
      fixture.selectStatus(2L) shouldBe None
      fixture.ledgerCount(batch.batchId) shouldBe 1
    }

    "return AlreadyCommitted for an exact replay without reapplying target mutations" in withFixture() { fixture =>
      val events = Vector(fixture.event(CdcOperation.Create, 1L, afterStatus = Some("new")))
      val batch = fixture.batch(events)
      val first = Await.result(
        fixture.node.commitBatch(fixture.sinkNode, "workflow-cdc", "execution-cdc", batch, events.map(_.canonicalJson), _ => ()),
        5.seconds
      )
      fixture.updateStatus(1L, "outside")

      val replay = Await.result(
        fixture.node.commitBatch(fixture.sinkNode, "workflow-cdc", "execution-cdc", batch, events.map(_.canonicalJson), _ => ()),
        5.seconds
      )

      replay shouldBe AlreadyCommitted(first.asInstanceOf[Committed].checkpoint)
      fixture.selectStatus(1L) shouldBe Some("outside")
      fixture.ledgerCount(batch.batchId) shouldBe 1
    }

    "reject a conflicting ledger identity" in withFixture() { fixture =>
      val events = Vector(fixture.event(CdcOperation.Create, 1L, afterStatus = Some("new")))
      val batch = fixture.batch(events)
      fixture.insertConflictingLedger(batch.batchId)

      intercept[IllegalStateException] {
        Await.result(
          fixture.node.commitBatch(fixture.sinkNode, "workflow-cdc", "execution-cdc", batch, events.map(_.canonicalJson), _ => ()),
          5.seconds
        )
      }.getMessage should include("conflicting")
      fixture.targetCount shouldBe 0
    }

    "treat deleting an absent row as a successful ordered event" in withFixture() { fixture =>
      val events = Vector(fixture.event(CdcOperation.Delete, 999L, beforeStatus = Some("gone")))
      val batch = fixture.batch(events)

      Await.result(
        fixture.node.commitBatch(fixture.sinkNode, "workflow-cdc", "execution-cdc", batch, events.map(_.canonicalJson), _ => ()),
        5.seconds
      ) shouldBe a[Committed]
      fixture.targetCount shouldBe 0
      fixture.ledgerCount(batch.batchId) shouldBe 1
    }

    "preserve two updates to the same key in their source order" in withFixture() { fixture =>
      fixture.insertTarget(1L, "old")
      val events = Vector(
        fixture.event(CdcOperation.Update, 1L, beforeStatus = Some("old"), afterStatus = Some("middle")),
        fixture.event(CdcOperation.Update, 1L, beforeStatus = Some("middle"), afterStatus = Some("final"))
      )
      val batch = fixture.batch(events)

      Await.result(
        fixture.node.commitBatch(fixture.sinkNode, "workflow-cdc", "execution-cdc", batch, events.map(_.canonicalJson), _ => ()),
        5.seconds
      )
      fixture.selectStatus(1L) shouldBe Some("final")
    }

    "apply a primary-key change represented by delete then create" in withFixture() { fixture =>
      fixture.insertTarget(1L, "old")
      val events = Vector(
        fixture.event(CdcOperation.Delete, 1L, beforeStatus = Some("old")),
        fixture.event(CdcOperation.Create, 3L, afterStatus = Some("moved"))
      )
      val batch = fixture.batch(events)

      Await.result(
        fixture.node.commitBatch(fixture.sinkNode, "workflow-cdc", "execution-cdc", batch, events.map(_.canonicalJson), _ => ()),
        5.seconds
      )
      fixture.selectStatus(1L) shouldBe None
      fixture.selectStatus(3L) shouldBe Some("moved")
    }

    "parse and validate every transformed envelope before claiming the ledger" in withFixture() { fixture =>
      val event = fixture.event(CdcOperation.Create, 1L, afterStatus = Some("new"))
      val batch = fixture.batch(Vector(event))
      val malformed = Vector("{not-json")

      intercept[IllegalStateException] {
        Await.result(
          fixture.node.commitBatch(fixture.sinkNode, "workflow-cdc", "execution-cdc", batch, malformed, _ => ()),
          5.seconds
        )
      }.getMessage should include("CDC envelope")
      fixture.ledgerCount shouldBe 0
      fixture.targetCount shouldBe 0
    }

    "reject unsupported operations before claiming the ledger" in withFixture() { fixture =>
      val event = fixture.event(CdcOperation.Create, 1L, afterStatus = Some("new"))
      val batch = fixture.batch(Vector(event))
      val truncate = JsObject(
        event.canonicalJson.parseJson.asJsObject.fields.updated("op", JsString("t"))
      ).compactPrint

      intercept[IllegalStateException] {
        Await.result(
          fixture.node.commitBatch(fixture.sinkNode, "workflow-cdc", "execution-cdc", batch, Vector(truncate), _ => ()),
          5.seconds
        )
      }.getMessage should include("operation")
      fixture.ledgerCount shouldBe 0
    }

    "reject transformed row loss before claiming the ledger" in withFixture() { fixture =>
      val events = Vector(
        fixture.event(CdcOperation.Create, 1L, afterStatus = Some("one")),
        fixture.event(CdcOperation.Create, 2L, afterStatus = Some("two"))
      )
      val batch = fixture.batch(events)

      intercept[IllegalStateException] {
        Await.result(
          fixture.node.commitBatch(fixture.sinkNode, "workflow-cdc", "execution-cdc", batch, Vector(events.head.canonicalJson), _ => ()),
          5.seconds
        )
      }.getMessage should include("row count")
      fixture.ledgerCount shouldBe 0
    }

    "reject a batch partition that names a different connector before claiming the ledger" in withFixture() { fixture =>
      val event = fixture.event(CdcOperation.Create, 1L, afterStatus = Some("new"))
      val batch = fixture.batch(Vector(event)).copy(partitionId = "mysql-cdc:other-connector")

      intercept[IllegalStateException] {
        Await.result(
          fixture.node.commitBatch(fixture.sinkNode, "workflow-cdc", "execution-cdc", batch, Vector(event.canonicalJson), _ => ()),
          5.seconds
        )
      }.getMessage should include("connector")
      fixture.ledgerCount shouldBe 0
    }

    "reject a single update that changes its primary key before claiming the ledger" in withFixture() { fixture =>
      val before = fixture.image(1L, "old")
      val after = fixture.image(2L, "new")
      val event = fixture.envelope(CdcOperation.Update, 2L, Some(before), Some(after))
      val batch = fixture.batch(Vector(event))

      intercept[IllegalStateException] {
        Await.result(
          fixture.node.commitBatch(fixture.sinkNode, "workflow-cdc", "execution-cdc", batch, Vector(event.canonicalJson), _ => ()),
          5.seconds
        )
      }.getMessage should include("primary key")
      fixture.ledgerCount shouldBe 0
    }

    "reject lossy typed conversion before claiming the ledger" in withFixture() { fixture =>
      val event = fixture.envelope(
        CdcOperation.Create,
        1L,
        None,
        Some(fixture.image(1L, "new").copy(fields = fixture.image(1L, "new").fields.updated("amount", JsString("1.234"))))
      )
      val batch = fixture.batch(Vector(event))

      intercept[IllegalStateException] {
        Await.result(
          fixture.node.commitBatch(fixture.sinkNode, "workflow-cdc", "execution-cdc", batch, Vector(event.canonicalJson), _ => ()),
          5.seconds
        )
      }.getMessage should include("amount")
      fixture.ledgerCount shouldBe 0
    }

    "preserve TIME(6) microseconds through JDBC 4.2 binding" in withFixture(
      targetDdl = compatibleTarget.dropRight(1) + ", event_time TIME(6) NOT NULL)"
    ) { fixture =>
      val columns = sourceColumns :+ SourceColumn("event_time", Types.TIME, "TIME", 0, 7, 15, 6)
      val after = JsObject(fixture.image(1L, "new").fields.updated("event_time", JsString("12:34:56.123456")))
      val event = fixture.envelope(CdcOperation.Create, 1L, None, Some(after))
      val batch = fixture.batch(Vector(event), columns = columns)

      Await.result(
        fixture.node.commitBatch(fixture.sinkNode, "workflow-cdc", "execution-cdc", batch, Vector(event.canonicalJson), _ => ()),
        5.seconds
      ) shouldBe a[Committed]
      fixture.selectLocalTime(1L, "event_time") shouldBe LocalTime.parse("12:34:56.123456")
    }

    "reject TIME values exceeding target fractional precision before ledger claim" in withFixture(
      targetDdl = compatibleTarget.dropRight(1) + ", event_time TIME(6) NOT NULL)"
    ) { fixture =>
      val columns = sourceColumns :+ SourceColumn("event_time", Types.TIME, "TIME", 0, 7, 15, 6)
      val after = JsObject(fixture.image(1L, "new").fields.updated("event_time", JsString("12:34:56.1234567")))
      val event = fixture.envelope(CdcOperation.Create, 1L, None, Some(after))
      val batch = fixture.batch(Vector(event), columns = columns)

      intercept[IllegalStateException] {
        Await.result(
          fixture.node.commitBatch(fixture.sinkNode, "workflow-cdc", "execution-cdc", batch, Vector(event.canonicalJson), _ => ()),
          5.seconds
        )
      }.getMessage should include("event_time")
      fixture.ledgerCount shouldBe 0
      fixture.targetCount shouldBe 0
    }

    "reject non-finite or non-round-trippable floating values before ledger claim" in {
      val cases = Vector(
        ("float_value", "REAL", Types.REAL, JsNumber(BigDecimal(16777217))),
        ("double_value", "DOUBLE", Types.DOUBLE, JsNumber(BigDecimal("1e400")))
      )
      cases.foreach { case (name, ddlType, jdbcType, value) =>
        withFixture(targetDdl = compatibleTarget.dropRight(1) + s", $name $ddlType NOT NULL)") { fixture =>
          val columns = sourceColumns :+ SourceColumn(name, jdbcType, ddlType, 0, 7, 32, 0)
          val after = JsObject(fixture.image(1L, "new").fields.updated(name, value))
          val event = fixture.envelope(CdcOperation.Create, 1L, None, Some(after))
          val batch = fixture.batch(Vector(event), columns = columns)

          val failure = intercept[IllegalStateException] {
            Await.result(
              fixture.node.commitBatch(fixture.sinkNode, "workflow-cdc", "execution-cdc", batch, Vector(event.canonicalJson), _ => ()),
              5.seconds
            )
          }
          withClue(name) { failure.getMessage should include(name) }
          fixture.ledgerCount shouldBe 0
        }
      }
    }

    "resolve two simultaneous claims as one commit and one exact replay" in {
      val barrier = new CyclicBarrier(2)
      withFixture(claimHook = _ => barrier.await(5, TimeUnit.SECONDS)) { fixture =>
        val event = fixture.event(CdcOperation.Create, 1L, afterStatus = Some("new"))
        val batch = fixture.batch(Vector(event))
        val pool = Executors.newFixedThreadPool(2)
        implicit val concurrentEc: ExecutionContext = ExecutionContext.fromExecutorService(pool)
        try {
          val commits = Vector.fill(2)(fixture.node.commitBatch(
            fixture.sinkNode,
            "workflow-cdc",
            "execution-cdc",
            batch,
            Vector(event.canonicalJson),
            _ => ()
          )(concurrentEc))
          val results = commits.map(commit => Await.result(commit, 10.seconds))

          results.count(_.isInstanceOf[Committed]) shouldBe 1
          results.count(_.isInstanceOf[AlreadyCommitted]) shouldBe 1
          fixture.ledgerCount(batch.batchId) shouldBe 1
          fixture.selectStatus(1L) shouldBe Some("new")
        } finally {
          pool.shutdownNow()
          pool.awaitTermination(5, TimeUnit.SECONDS)
        }
      }
    }

    "roll back target mutations and the ledger together when a later target write fails" in withFixture(
      targetDdl = compatibleTarget.replace("status VARCHAR(40) NOT NULL", "status VARCHAR(40) NOT NULL CHECK (status <> 'boom')")
    ) { fixture =>
      fixture.insertTarget(1L, "old")
      val events = Vector(
        fixture.event(CdcOperation.Update, 1L, beforeStatus = Some("old"), afterStatus = Some("good")),
        fixture.event(CdcOperation.Create, 2L, afterStatus = Some("boom"))
      )
      val batch = fixture.batch(events)

      intercept[IllegalStateException] {
        Await.result(
          fixture.node.commitBatch(fixture.sinkNode, "workflow-cdc", "execution-cdc", batch, events.map(_.canonicalJson), _ => ()),
          5.seconds
        )
      }.getMessage should include("batch apply failed")
      fixture.selectStatus(1L) shouldBe Some("old")
      fixture.selectStatus(2L) shouldBe None
      fixture.ledgerCount(batch.batchId) shouldBe 0
    }
  }

  private def readinessFailure(fixture: Fixture): String = intercept[IllegalStateException] {
    Await.result(fixture.node.validateReady(fixture.sinkNode, _ => ()), 5.seconds)
  }.getMessage

  private def withFixture(
    targetDdl: String = compatibleTarget,
    claimHook: String => Unit = _ => (),
    targetTypeOverrides: Map[String, String] = Map.empty
  )(test: Fixture => Unit): Unit = {
    val fixture = new Fixture(targetDdl, claimHook, targetTypeOverrides)
    try test(fixture) finally fixture.close()
  }

  private def sinkNode(
    host: String = "localhost",
    port: Int = 3306,
    database: String = "target_db",
    table: String = "target_rows",
    username: String = "target_user",
    passwordEnv: Option[String] = Some("TARGET_PASSWORD"),
    extra: Map[String, JsValue] = Map.empty
  ): WorkflowDSL.Node = WorkflowDSL.Node(
    id = "sink-cdc",
    `type` = "sink",
    nodeType = "mysql.cdc.apply",
    label = "CDC apply",
    position = WorkflowDSL.Position(0, 0),
    config = JsObject(Map(
      "host" -> JsString(host),
      "port" -> JsNumber(port),
      "database" -> JsString(database),
      "table" -> JsString(table),
      "username" -> JsString(username)
    ) ++ passwordEnv.map(value => "passwordEnv" -> JsString(value)) ++ extra)
  )

  private val compatibleTarget =
    """CREATE TABLE target_rows (
      | id BIGINT PRIMARY KEY,
      | run_id VARCHAR(40) NOT NULL,
      | status VARCHAR(40) NOT NULL,
      | amount DECIMAL(18,2) NOT NULL,
      | note VARCHAR(255) NULL,
      | updated_at TIMESTAMP NOT NULL
      |)""".stripMargin

  private val sourceColumns = Vector(
    SourceColumn("id", Types.BIGINT, "BIGINT", 0, 1, 64, 0),
    SourceColumn("run_id", Types.VARCHAR, "VARCHAR", 0, 2, 40, 0),
    SourceColumn("status", Types.VARCHAR, "VARCHAR", 0, 3, 40, 0),
    SourceColumn("amount", Types.DECIMAL, "DECIMAL", 0, 4, 18, 2),
    SourceColumn("note", Types.VARCHAR, "VARCHAR", 1, 5, 255, 0),
    SourceColumn("updated_at", Types.TIMESTAMP, "TIMESTAMP", 0, 6, 26, 6)
  )

  private final class Fixture(
    targetDdl: String,
    claimHook: String => Unit,
    targetTypeOverrides: Map[String, String]
  ) {
    private val databaseName = s"target_${UUID.randomUUID().toString.replace('-', '_')}"
    private val url = s"jdbc:h2:mem:$databaseName;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    Class.forName("org.h2.Driver")
    private val keeper = DriverManager.getConnection(url, "sa", "")

    val sinkNode: WorkflowDSL.Node = MySQLCdcApplySinkNodeSpec.this.sinkNode(
      database = databaseName,
      username = "sa"
    )
    val node: MySQLCdcApplySinkNode = new MySQLCdcApplySinkNode {
      override protected[sinks] def getenv(name: String): Option[String] =
        Option.when(name == "TARGET_PASSWORD")("runtime-secret")

      override protected[sinks] def openConnection(config: MySQLCdcApplyConfig): Connection = {
        DriverManager.getConnection(url, "sa", "")
      }

      override protected[sinks] def beforeLedgerClaim(batchId: String): Unit = claimHook(batchId)

      override protected[sinks] def targetTypeName(rows: java.sql.ResultSet): String =
        targetTypeOverrides.getOrElse(rows.getString("COLUMN_NAME").toLowerCase, super.targetTypeName(rows))
    }

    initialize(targetDdl)

    def boundary(
      primaryKey: String = "id",
      columns: Vector[SourceColumn] = sourceColumns
    ): SnapshotBoundary = SnapshotBoundary(
      sourceNodeId = "source-cdc",
      partitionId = "mysql-cdc:orders-cdc-v1",
      upperBound = Some(streamIdentity(primaryKey, columns))
    )

    def event(
      op: CdcOperation,
      id: Long,
      beforeStatus: Option[String] = None,
      afterStatus: Option[String] = None
    ): MySQLCdcEnvelope = envelope(
      op,
      id,
      beforeStatus.map(image(id, _)),
      afterStatus.map(image(id, _))
    )

    def envelope(
      op: CdcOperation,
      keyId: Long,
      before: Option[JsObject],
      after: Option[JsObject]
    ): MySQLCdcEnvelope = MySQLCdcEnvelope(
      version = 1,
      op = op,
      key = JsObject("id" -> JsNumber(keyId)),
      before = before,
      after = after,
      source = MySQLCdcSourcePosition(
        connectorId = "orders-cdc-v1",
        database = "source_db",
        table = "source_orders",
        snapshot = op == CdcOperation.Read,
        file = Some("binlog.000001"),
        position = Some(100L),
        row = Some(0),
        eventTimestampMillis = Some(Instant.parse("2026-08-30T00:00:00Z").toEpochMilli)
      )
    )

    def image(id: Long, status: String): JsObject = JsObject(
      "id" -> JsNumber(id),
      "run_id" -> JsString("run-1"),
      "status" -> JsString(status),
      "amount" -> JsString("12.30"),
      "note" -> JsNull,
      "updated_at" -> JsString("2026-08-30T00:00:00Z")
    )

    def batch(
      events: Vector[MySQLCdcEnvelope],
      primaryKey: String = "id",
      columns: Vector[SourceColumn] = sourceColumns
    ): SourceBatch = {
      val sequence = 0L
      SourceBatch(
        sourceNodeId = "source-cdc",
        partitionId = "mysql-cdc:orders-cdc-v1",
        batchSequence = sequence,
        batchId = BatchId.sha256("execution-cdc", "source-cdc", "mysql-cdc:orders-cdc-v1", sequence),
        cursor = SourceCursor("mysql.binlog.v1", "{\"version\":1}", boundary(primaryKey, columns).upperBound.get),
        rows = events.map(_.canonicalJson)
      )
    }

    def targetCount: Int = count("target_rows")
    def ledgerCount: Int = count("pekko_sync_batch_ledger")
    def ledgerCount(batchId: String): Int = scalarInt(
      "SELECT COUNT(*) FROM pekko_sync_batch_ledger WHERE batch_id = ?",
      statement => statement.setString(1, batchId)
    )

    def selectStatus(id: Long): Option[String] = {
      val statement = keeper.prepareStatement("SELECT status FROM target_rows WHERE id = ?")
      try {
        statement.setLong(1, id)
        val result = statement.executeQuery()
        try Option.when(result.next())(result.getString(1)) finally result.close()
      } finally statement.close()
    }

    def selectLocalTime(id: Long, column: String): LocalTime = {
      val statement = keeper.prepareStatement(s"SELECT $column FROM target_rows WHERE id = ?")
      try {
        statement.setLong(1, id)
        val result = statement.executeQuery()
        try {
          result.next() shouldBe true
          result.getObject(1, classOf[LocalTime])
        } finally result.close()
      } finally statement.close()
    }

    def insertTarget(id: Long, status: String): Unit = {
      val statement = keeper.prepareStatement(
        "INSERT INTO target_rows(id, run_id, status, amount, note, updated_at) VALUES (?, 'run-1', ?, 12.30, NULL, TIMESTAMP '2026-08-30 00:00:00')"
      )
      try {
        statement.setLong(1, id)
        statement.setString(2, status)
        statement.executeUpdate()
      } finally statement.close()
    }

    def updateStatus(id: Long, status: String): Unit = {
      val statement = keeper.prepareStatement("UPDATE target_rows SET status = ? WHERE id = ?")
      try {
        statement.setString(1, status)
        statement.setLong(2, id)
        statement.executeUpdate()
      } finally statement.close()
    }

    def insertConflictingLedger(batchId: String): Unit = {
      val statement = keeper.prepareStatement(
        """INSERT INTO pekko_sync_batch_ledger
          |(batch_id, workflow_id, execution_id, source_node_id, partition_id, batch_sequence,
          | cursor_kind, cursor_value, upper_bound, source_rows, target_rows)
          |VALUES (?, 'other-workflow', 'execution-cdc', 'source-cdc', 'mysql-cdc:orders-cdc-v1',
          | 0, 'mysql.binlog.v1', '{"version":1}', ?, 1, 1)""".stripMargin
      )
      try {
        statement.setString(1, batchId)
        statement.setString(2, boundary().upperBound.get)
        statement.executeUpdate()
      } finally statement.close()
    }

    def close(): Unit = {
      keeper.close()
      val cleanup = DriverManager.getConnection(url, "sa", "")
      try cleanup.createStatement().execute("DROP ALL OBJECTS") finally cleanup.close()
    }

    private def initialize(targetDdl: String): Unit = {
      val statement = keeper.createStatement()
      try {
        statement.execute(targetDdl)
        statement.execute(
          """CREATE TABLE pekko_sync_batch_ledger (
            | batch_id VARCHAR(64) PRIMARY KEY,
            | workflow_id VARCHAR(255) NOT NULL,
            | execution_id VARCHAR(255) NOT NULL,
            | source_node_id VARCHAR(255) NOT NULL,
            | partition_id VARCHAR(128) NOT NULL,
            | batch_sequence BIGINT NOT NULL,
            | cursor_kind VARCHAR(64) NOT NULL,
            | cursor_value VARCHAR(2048) NOT NULL,
            | upper_bound VARCHAR(2048) NOT NULL,
            | source_rows BIGINT NOT NULL,
            | target_rows BIGINT NOT NULL,
            | committed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            | CONSTRAINT uq_cdc_execution_sequence UNIQUE
            |   (execution_id, source_node_id, partition_id, batch_sequence)
            |)""".stripMargin
        )
      } finally statement.close()
    }

    private def streamIdentity(primaryKey: String, columns: Vector[SourceColumn]): String = {
      val schema = JsObject(
        "version" -> JsNumber(1),
        "primaryKey" -> JsString(primaryKey),
        "columns" -> JsArray(columns.map(column => JsObject(
          "name" -> JsString(column.name),
          "jdbcType" -> JsNumber(column.jdbcType),
          "typeName" -> JsString(column.typeName),
          "nullable" -> JsNumber(column.nullable),
          "ordinal" -> JsNumber(column.ordinal),
          "size" -> JsNumber(column.size),
          "scale" -> JsNumber(column.scale)
        )))
      )
      val fingerprint = sha256(canonicalJson(schema))
      canonicalJson(JsObject(
        "version" -> JsNumber(1),
        "connectorId" -> JsString("orders-cdc-v1"),
        "database" -> JsString("source_db"),
        "table" -> JsString("source_orders"),
        "primaryKey" -> JsString(primaryKey),
        "columns" -> JsArray(columns.map(column => JsObject(
          "name" -> JsString(column.name),
          "jdbcType" -> JsNumber(column.jdbcType),
          "typeName" -> JsString(column.typeName)
        ))),
        "schemaFingerprint" -> JsString(fingerprint)
      ))
    }

    private def count(table: String): Int = {
      val result = keeper.createStatement().executeQuery(s"SELECT COUNT(*) FROM $table")
      try { result.next(); result.getInt(1) } finally { val statement = result.getStatement; result.close(); statement.close() }
    }

    private def scalarInt(sql: String, bind: java.sql.PreparedStatement => Unit): Int = {
      val statement = keeper.prepareStatement(sql)
      try {
        bind(statement)
        val result = statement.executeQuery()
        try { result.next(); result.getInt(1) } finally result.close()
      } finally statement.close()
    }
  }

  private def canonicalJson(value: JsValue): String = value match {
    case JsObject(fields) => fields.toVector.sortBy(_._1).map { case (key, field) =>
      s"${JsString(key).compactPrint}:${canonicalJson(field)}"
    }.mkString("{", ",", "}")
    case JsArray(elements) => elements.map(canonicalJson).mkString("[", ",", "]")
    case other => other.compactPrint
  }

  private def sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.getBytes(StandardCharsets.UTF_8))
    .map(byte => f"${byte & 0xff}%02x").mkString
}

private object MySQLCdcApplySinkNodeSpec {
  final case class SourceColumn(
    name: String,
    jdbcType: Int,
    typeName: String,
    nullable: Int,
    ordinal: Int,
    size: Int,
    scale: Int
  )
}
