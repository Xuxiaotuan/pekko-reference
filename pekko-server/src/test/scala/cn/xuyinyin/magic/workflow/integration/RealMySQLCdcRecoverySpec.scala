package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.tags.ExternalIntegration
import cn.xuyinyin.magic.workflow.nodes.sources.{MySQLCdcSourceNode, MySQLCdcStateConfig}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.{BufferedWriter, File, OutputStreamWriter}
import java.net.URLClassLoader
import java.nio.file.Path
import java.sql.{Connection, DriverManager, Timestamp}
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.{ConcurrentLinkedDeque, LinkedBlockingQueue, TimeUnit}
import scala.concurrent.duration._
import scala.io.{Source => IoSource}

class RealMySQLCdcRecoverySpec extends AnyWordSpec with Matchers {
  import RealMySQLCdcRecoverySpec._

  "the checked-in Debezium JDBC schema" should {
    "preserve the Debezium 3.6.1 offset and history record identities" in {
      val sql = resourceText("db/mysql/pekko-cdc-schema.sql")
        .replaceAll("\\s+", " ")
        .toUpperCase(java.util.Locale.ROOT)

      sql should include(
        "RECORD_INSERT_SEQ INT NOT NULL, PRIMARY KEY (ID)"
      )
      sql should include(
        "RECORD_INSERT_SEQ INT NOT NULL, PRIMARY KEY (ID, HISTORY_DATA_SEQ)"
      )
    }
  }

  "the real MySQL CDC mirror" should {
    "snapshot, stream, resume in another JVM, and safely replay an applied change" taggedAs ExternalIntegration in {
      if (!sys.env.get("RUN_MYSQL_CDC_EXTERNAL").contains("1")) {
        cancel("external_blocked: set RUN_MYSQL_CDC_EXTERNAL=1 to run the real MySQL CDC suite")
      }
      val settings = Settings.requireEnvironment()
      val runId = UUID.randomUUID().toString.replace("-", "")
      val connectorId = s"cdc-$runId"
      val executionId = s"execution-$runId"
      val idStart = java.lang.Long.parseUnsignedLong(runId.take(12), 16) * 10L
      val idEnd = idStart + 3L
      val first = idStart
      val second = idStart + 1L
      val third = idStart + 2L
      val fourth = idStart + 3L
      var child: ChildProcess = null

      withConnection(settings) { connection =>
        executeResource(connection, "db/mysql/pekko-cdc-schema.sql")
        executeResource(connection, "db/mysql/pekko-sync-ledger-schema.sql")
        deleteAcceptanceRange(connection, idStart, idEnd)
        insertRow(connection, TestRow(first, runId, "baseline-one", BigDecimal("10.10"), Some("one"), at(1)))
        insertRow(connection, TestRow(second, runId, "baseline-two", BigDecimal("20.20"), None, at(2)))
        insertRow(connection, TestRow(third, runId, "baseline-three", BigDecimal("30.30"), Some("three"), at(3)))
      }

      try {
        child = startChild("snapshot-and-stream", settings, connectorId, executionId, idStart, idEnd, flushMillis = 0)
        child.awaitLine("CDC_STATUS ", 30.seconds)
        awaitTarget(settings, idStart, idEnd, Vector(
          TestRow(first, runId, "baseline-one", BigDecimal("10.10"), Some("one"), at(1)),
          TestRow(second, runId, "baseline-two", BigDecimal("20.20"), None, at(2)),
          TestRow(third, runId, "baseline-three", BigDecimal("30.30"), Some("three"), at(3))
        ))

        withConnection(settings) { connection =>
          insertRow(connection, TestRow(fourth, runId, "live-insert", BigDecimal("40.40"), Some("four"), at(4)))
          updateRow(connection, TestRow(first, runId, "live-updated", BigDecimal("11.11"), Some("one-updated"), at(5)))
          deleteSourceRow(connection, second)
        }
        awaitTarget(settings, idStart, idEnd, Vector(
          TestRow(first, runId, "live-updated", BigDecimal("11.11"), Some("one-updated"), at(5)),
          TestRow(third, runId, "baseline-three", BigDecimal("30.30"), Some("three"), at(3)),
          TestRow(fourth, runId, "live-insert", BigDecimal("40.40"), Some("four"), at(4))
        ))
        requireConnectorState(settings, connectorId)
        val firstPid = child.pid
        child.stopGracefully()
        child.awaitExit(30.seconds) shouldBe 0
        child = null

        child = startChild("resume-stream", settings, connectorId, executionId, idStart, idEnd, flushMillis = 0)
        child.pid should not be firstPid
        child.awaitLine("CDC_STATUS ", 30.seconds)
        withConnection(settings) { connection =>
          updateRow(connection, TestRow(third, runId, "resumed-updated", BigDecimal("33.33"), Some("three-resumed"), at(6)))
          deleteSourceRow(connection, fourth)
        }
        awaitTarget(settings, idStart, idEnd, Vector(
          TestRow(first, runId, "live-updated", BigDecimal("11.11"), Some("one-updated"), at(5)),
          TestRow(third, runId, "resumed-updated", BigDecimal("33.33"), Some("three-resumed"), at(6))
        ))
        child.stopGracefully()
        child.awaitExit(30.seconds) shouldBe 0
        child = null

        child = startChild(
          "resume-stream-crash-after-commit",
          settings,
          connectorId,
          executionId,
          idStart,
          idEnd,
          flushMillis = 0
        )
        child.awaitLine("CDC_STATUS ", 30.seconds)
        val offsetBeforeReplayWindow = storedOffset(settings, connectorId).getOrElse(
          fail("connector offset row was absent before the replay-window mutation")
        )
        withConnection(settings) { connection =>
          updateRow(connection, TestRow(first, runId, "replay-window", BigDecimal("12.12"), Some("eligible"), at(7)))
        }
        awaitTarget(settings, idStart, idEnd, Vector(
          TestRow(first, runId, "replay-window", BigDecimal("12.12"), Some("eligible"), at(7)),
          TestRow(third, runId, "resumed-updated", BigDecimal("33.33"), Some("three-resumed"), at(6))
        ))
        child.awaitExit(30.seconds) should not be 0
        child = null
        storedOffset(settings, connectorId) shouldBe Some(offsetBeforeReplayWindow)
        val sequenceBeforeCrash = maxLedgerSequence(settings, executionId)

        child = startChild("resume-stream", settings, connectorId, executionId, idStart, idEnd, flushMillis = 0)
        child.awaitLine("CDC_STATUS ", 30.seconds)
        awaitCondition(45.seconds, "an already-applied change was not replayed after the forced offset gap") {
          hasNonEmptyLedgerBatchAfter(settings, executionId, sequenceBeforeCrash)
        }
        withConnection(settings) { connection =>
          updateRow(connection, TestRow(third, runId, "recovery-final", BigDecimal("34.34"), None, at(8)))
          deleteSourceRow(connection, first)
        }
        awaitTarget(settings, idStart, idEnd, Vector(
          TestRow(third, runId, "recovery-final", BigDecimal("34.34"), None, at(8))
        ))
        requireConnectorState(settings, connectorId)
        child.stopGracefully()
        child.awaitExit(30.seconds) shouldBe 0
        child = null
      } finally {
        if (child != null) {
          child.killForcibly()
          child.awaitExit(30.seconds)
        }
        withConnection(settings)(connection => deleteAcceptanceRange(connection, idStart, idEnd))
      }
    }
  }
}

private object RealMySQLCdcRecoverySpec {
  private val RequiredEnvironment = Vector(
    "MYSQL_CDC_TEST_HOST",
    "MYSQL_CDC_TEST_PORT",
    "MYSQL_CDC_TEST_DATABASE",
    "MYSQL_CDC_TEST_WRITER_USER",
    "MYSQL_CDC_TEST_WRITER_PASSWORD",
    "MYSQL_CDC_TEST_READER_USER",
    "MYSQL_CDC_TEST_READER_PASSWORD",
    "MYSQL_CDC_TEST_SERVER_ID"
  )

  private final case class Settings(
    host: String,
    port: Int,
    database: String,
    writerUser: String,
    writerPassword: String,
    readerUser: String,
    readerPassword: String,
    serverId: Long
  ) {
    val jdbcUrl: String =
      s"jdbc:mysql://$host:$port/$database?useSSL=false&allowPublicKeyRetrieval=true" +
        "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
    val secrets: Vector[String] = Vector(writerPassword, readerPassword).filter(_.nonEmpty).distinct
    val stateConfig: MySQLCdcStateConfig = MySQLCdcStateConfig(
      jdbcUrl,
      writerUser,
      writerPassword,
      "debezium_offset_storage",
      "debezium_database_history",
      0
    )
  }

  private object Settings {
    def requireEnvironment(): Settings = {
      val missing = RequiredEnvironment.filter(name => sys.env.get(name).forall(_.isEmpty))
      if (missing.nonEmpty) {
        org.scalatest.Assertions.fail(s"missing required MySQL CDC environment variables: ${missing.mkString(", ")}")
      }
      val port = parseLong("MYSQL_CDC_TEST_PORT", 1L, 65535L).toInt
      val serverId = parseLong("MYSQL_CDC_TEST_SERVER_ID", 1L, 4294967295L)
      Settings(
        host = sys.env("MYSQL_CDC_TEST_HOST"),
        port = port,
        database = requireIdentifier("MYSQL_CDC_TEST_DATABASE"),
        writerUser = sys.env("MYSQL_CDC_TEST_WRITER_USER"),
        writerPassword = sys.env("MYSQL_CDC_TEST_WRITER_PASSWORD"),
        readerUser = sys.env("MYSQL_CDC_TEST_READER_USER"),
        readerPassword = sys.env("MYSQL_CDC_TEST_READER_PASSWORD"),
        serverId = serverId
      )
    }

    private def parseLong(name: String, minimum: Long, maximum: Long): Long = {
      val parsed = try sys.env(name).toLong
      catch { case _: NumberFormatException => org.scalatest.Assertions.fail(s"$name must be an integer") }
      if (parsed < minimum || parsed > maximum) {
        org.scalatest.Assertions.fail(s"$name must be between $minimum and $maximum")
      }
      parsed
    }

    private def requireIdentifier(name: String): String = {
      val value = sys.env(name)
      if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
        org.scalatest.Assertions.fail(s"$name must be a SQL identifier")
      }
      value
    }
  }

  private final case class TestRow(
    id: Long,
    runId: String,
    status: String,
    amount: BigDecimal,
    note: Option[String],
    updatedAt: LocalDateTime
  )

  private final class ChildProcess(process: Process, secrets: Vector[String]) {
    private val output = new LinkedBlockingQueue[String]()
    private val tail = new ConcurrentLinkedDeque[String]()
    private val input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream))
    private val drain = new Thread(() => {
      val source = IoSource.fromInputStream(process.getInputStream)
      try source.getLines().foreach { raw =>
        val line = redact(raw)
        output.put(line)
        tail.addLast(line)
        while (tail.size() > 200) tail.removeFirst()
      }
      finally source.close()
    }, s"mysql-cdc-child-output-${process.pid()}")
    drain.setDaemon(true)
    drain.start()

    def pid: Long = process.pid()

    def awaitLine(prefix: String, timeout: FiniteDuration): String = {
      val deadline = timeout.fromNow
      while (deadline.hasTimeLeft()) {
        val line = output.poll(math.max(1L, math.min(100L, deadline.timeLeft.toMillis)), TimeUnit.MILLISECONDS)
        if (line != null && line.startsWith(prefix)) return line
        if (line == null && !process.isAlive && !drain.isAlive && output.isEmpty) {
          throw new AssertionError(s"child exited before $prefix; exit=${exitValue}; tail=${tailText}")
        }
      }
      throw new AssertionError(s"child output did not contain $prefix; exit=${exitValue}; tail=${tailText}")
    }

    def stopGracefully(): Unit = input.synchronized {
      input.write("STOP")
      input.newLine()
      input.flush()
    }

    def killForcibly(): Unit = if (process.isAlive) process.destroyForcibly()

    def awaitExit(timeout: FiniteDuration): Int = {
      if (!process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS)) {
        throw new AssertionError(s"child did not exit within $timeout; tail=${tailText}")
      }
      process.exitValue()
    }

    private def redact(value: String): String =
      secrets.foldLeft(Option(value).getOrElse(""))((current, secret) => current.replace(secret, "<redacted>"))

    private def exitValue: String = if (process.isAlive) "running" else process.exitValue().toString
    private def tailText: String = tail.toArray.mkString(" | ")
  }

  private def startChild(
    mode: String,
    settings: Settings,
    connectorId: String,
    executionId: String,
    idStart: Long,
    idEnd: Long,
    flushMillis: Int
  ): ChildProcess = {
    val mainClass = RealMySQLCdcProcess.getClass.getName.stripSuffix("$")
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString
    val builder = new ProcessBuilder(
      java,
      "-cp",
      runtimeClasspath,
      mainClass,
      mode,
      connectorId,
      executionId,
      idStart.toString,
      idEnd.toString
    )
    builder.redirectErrorStream(true)
    val environment = builder.environment()
    environment.put("MYSQL_CDC_TEST_HOST", settings.host)
    environment.put("MYSQL_CDC_TEST_PORT", settings.port.toString)
    environment.put("MYSQL_CDC_TEST_DATABASE", settings.database)
    environment.put("MYSQL_CDC_TEST_WRITER_USER", settings.writerUser)
    environment.put("MYSQL_CDC_TEST_WRITER_PASSWORD", settings.writerPassword)
    environment.put("MYSQL_CDC_TEST_READER_USER", settings.readerUser)
    environment.put("MYSQL_CDC_TEST_READER_PASSWORD", settings.readerPassword)
    environment.put("MYSQL_CDC_TEST_SERVER_ID", settings.serverId.toString)
    environment.put("MYSQL_CDC_TEST_OFFSET_FLUSH_INTERVAL_MS", flushMillis.toString)
    new ChildProcess(builder.start(), settings.secrets)
  }

  private def runtimeClasspath: String = {
    val loaderEntries = Iterator
      .iterate(Option(getClass.getClassLoader))(_.flatMap(loader => Option(loader.getParent)))
      .takeWhile(_.nonEmpty)
      .flatten
      .collect { case loader: URLClassLoader => loader.getURLs.iterator }
      .flatten
      .filter(_.getProtocol == "file")
      .map(url => Path.of(url.toURI).toString)
    val propertyEntries = System.getProperty("java.class.path", "")
      .split(File.pathSeparator).iterator.filter(_.nonEmpty)
    val testClasses = Option(getClass.getProtectionDomain.getCodeSource)
      .map(_.getLocation).filter(_.getProtocol == "file").iterator
      .map(url => Path.of(url.toURI).toString)
    (testClasses ++ loaderEntries ++ propertyEntries).toSeq.distinct.mkString(File.pathSeparator)
  }

  private def withConnection[A](settings: Settings)(operation: Connection => A): A = {
    Class.forName("com.mysql.cj.jdbc.Driver")
    val connection = DriverManager.getConnection(settings.jdbcUrl, settings.writerUser, settings.writerPassword)
    try operation(connection)
    finally connection.close()
  }

  private def executeResource(connection: Connection, resource: String): Unit = {
    val sql = resourceText(resource)
    val statement = connection.createStatement()
    try sql.split(";").iterator.map(_.trim).filter(_.nonEmpty).foreach(statement.execute)
    finally statement.close()
  }

  private def resourceText(resource: String): String = {
    val input = Option(getClass.getClassLoader.getResourceAsStream(resource))
      .getOrElse(throw new IllegalStateException(s"missing SQL resource: $resource"))
    try IoSource.fromInputStream(input).mkString
    finally input.close()
  }

  private def insertRow(connection: Connection, row: TestRow): Unit = {
    val statement = connection.prepareStatement(
      "INSERT INTO pekko_cdc_source_acceptance (id, run_id, status, amount, note, updated_at) VALUES (?, ?, ?, ?, ?, ?)"
    )
    try {
      bindRow(statement, row)
      require(statement.executeUpdate() == 1, s"baseline insert did not affect exactly one row: ${row.id}")
    } finally statement.close()
  }

  private def updateRow(connection: Connection, row: TestRow): Unit = {
    val statement = connection.prepareStatement(
      "UPDATE pekko_cdc_source_acceptance SET run_id = ?, status = ?, amount = ?, note = ?, updated_at = ? WHERE id = ?"
    )
    try {
      statement.setString(1, row.runId)
      statement.setString(2, row.status)
      statement.setBigDecimal(3, row.amount.bigDecimal)
      row.note.fold(statement.setNull(4, java.sql.Types.VARCHAR))(statement.setString(4, _))
      statement.setTimestamp(5, Timestamp.valueOf(row.updatedAt))
      statement.setLong(6, row.id)
      require(statement.executeUpdate() == 1, s"source update did not affect exactly one row: ${row.id}")
    } finally statement.close()
  }

  private def bindRow(statement: java.sql.PreparedStatement, row: TestRow): Unit = {
    statement.setLong(1, row.id)
    statement.setString(2, row.runId)
    statement.setString(3, row.status)
    statement.setBigDecimal(4, row.amount.bigDecimal)
    row.note.fold(statement.setNull(5, java.sql.Types.VARCHAR))(statement.setString(5, _))
    statement.setTimestamp(6, Timestamp.valueOf(row.updatedAt))
  }

  private def deleteSourceRow(connection: Connection, id: Long): Unit = {
    val statement = connection.prepareStatement("DELETE FROM pekko_cdc_source_acceptance WHERE id = ?")
    try {
      statement.setLong(1, id)
      require(statement.executeUpdate() == 1, s"source delete did not affect exactly one row: $id")
    } finally statement.close()
  }

  private def deleteAcceptanceRange(connection: Connection, idStart: Long, idEnd: Long): Unit = {
    Vector("pekko_cdc_source_acceptance", "pekko_cdc_target_acceptance").foreach { table =>
      val statement = connection.prepareStatement(s"DELETE FROM $table WHERE id BETWEEN ? AND ?")
      try {
        statement.setLong(1, idStart)
        statement.setLong(2, idEnd)
        statement.executeUpdate()
      } finally statement.close()
    }
  }

  private def targetRows(settings: Settings, idStart: Long, idEnd: Long): Vector[TestRow] =
    withConnection(settings) { connection =>
      val statement = connection.prepareStatement(
        "SELECT id, run_id, status, amount, note, updated_at FROM pekko_cdc_target_acceptance " +
          "WHERE id BETWEEN ? AND ? ORDER BY id"
      )
      try {
        statement.setLong(1, idStart)
        statement.setLong(2, idEnd)
        val rows = statement.executeQuery()
        try {
          val result = Vector.newBuilder[TestRow]
          while (rows.next()) {
            result += TestRow(
              rows.getLong("id"),
              rows.getString("run_id"),
              rows.getString("status"),
              BigDecimal(rows.getBigDecimal("amount")),
              Option(rows.getString("note")),
              rows.getTimestamp("updated_at").toLocalDateTime
            )
          }
          result.result()
        } finally rows.close()
      } finally statement.close()
    }

  private def awaitTarget(settings: Settings, idStart: Long, idEnd: Long, expected: Vector[TestRow]): Unit = {
    var actual = Vector.empty[TestRow]
    awaitCondition(
      60.seconds,
      s"target range $idStart-$idEnd did not reach the literal expected state; expected=$expected actual=$actual"
    ) {
      actual = targetRows(settings, idStart, idEnd)
      actual == expected
    }
  }

  private def requireConnectorState(settings: Settings, connectorId: String): Unit = withConnection(settings) { connection =>
    val (offsetTable, historyTable) = MySQLCdcSourceNode.connectorStateTables(settings.stateConfig, connectorId)
    val offset = connection.prepareStatement(
      s"SELECT COUNT(*) FROM $offsetTable WHERE offset_key LIKE ?"
    )
    try {
      offset.setString(1, s"%$connectorId%")
      val rows = offset.executeQuery()
      try {
        rows.next()
        require(rows.getLong(1) > 0L, s"offset state is absent for connector $connectorId")
      } finally rows.close()
    } finally offset.close()

    val history = connection.prepareStatement(
      s"SELECT COUNT(*) FROM $historyTable WHERE history_data LIKE ?"
    )
    try {
      history.setString(1, s"%$connectorId%")
      val rows = history.executeQuery()
      try {
        rows.next()
        require(rows.getLong(1) > 0L, "schema-history state is absent")
      } finally rows.close()
    } finally history.close()
  }

  private def storedOffset(settings: Settings, connectorId: String): Option[String] = withConnection(settings) { connection =>
    val (offsetTable, _) = MySQLCdcSourceNode.connectorStateTables(settings.stateConfig, connectorId)
    val statement = connection.prepareStatement(
      s"SELECT offset_val FROM $offsetTable WHERE offset_key LIKE ? " +
        "ORDER BY record_insert_ts DESC, record_insert_seq DESC LIMIT 1"
    )
    try {
      statement.setString(1, s"%$connectorId%")
      val rows = statement.executeQuery()
      try {
        Option.when(rows.next())(rows.getString(1))
      } finally rows.close()
    } finally statement.close()
  }

  private def maxLedgerSequence(settings: Settings, executionId: String): Long = withConnection(settings) { connection =>
    val statement = connection.prepareStatement(
      "SELECT COALESCE(MAX(batch_sequence), -1) FROM pekko_sync_batch_ledger WHERE execution_id = ?"
    )
    try {
      statement.setString(1, executionId)
      val rows = statement.executeQuery()
      try {
        rows.next()
        rows.getLong(1)
      } finally rows.close()
    } finally statement.close()
  }

  private def hasNonEmptyLedgerBatchAfter(settings: Settings, executionId: String, sequence: Long): Boolean =
    withConnection(settings) { connection =>
      val statement = connection.prepareStatement(
        "SELECT COUNT(*) FROM pekko_sync_batch_ledger " +
          "WHERE execution_id = ? AND batch_sequence > ? AND source_rows > 0 AND target_rows > 0"
      )
      try {
        statement.setString(1, executionId)
        statement.setLong(2, sequence)
        val rows = statement.executeQuery()
        try {
          rows.next()
          rows.getLong(1) > 0L
        } finally rows.close()
      } finally statement.close()
    }

  private def awaitCondition(timeout: FiniteDuration, failure: => String)(condition: => Boolean): Unit = {
    val deadline = timeout.fromNow
    var satisfied = condition
    while (!satisfied && deadline.hasTimeLeft()) {
      Thread.sleep(200L)
      satisfied = condition
    }
    if (!satisfied) org.scalatest.Assertions.fail(failure)
  }

  private def at(second: Int): LocalDateTime = LocalDateTime.of(2026, 8, 30, 12, 0, second, second * 1000)
}
