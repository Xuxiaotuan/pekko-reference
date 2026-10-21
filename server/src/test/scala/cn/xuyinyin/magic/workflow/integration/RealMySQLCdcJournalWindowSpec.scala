package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.tags.ExternalIntegration
import cn.xuyinyin.magic.workflow.checkpoint.{BatchCheckpoint, SourceBatch}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

import java.io.{BufferedWriter, File, OutputStreamWriter}
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.sql.{Connection, DriverManager}
import java.util.UUID
import java.util.concurrent.{ConcurrentLinkedQueue, LinkedBlockingQueue, TimeUnit}
import scala.concurrent.duration._
import scala.io.{Source => IoSource}
import scala.jdk.CollectionConverters._

/** Reproduction assertions describe the unmodified protocol, NOT a recovery-correctness certification.
  * Requires a disposable, loopback MySQL with ROW/FULL binlog. Unique schemas and evidence are retained.
  */
class RealMySQLCdcJournalWindowSpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._

  "the real Actor Journal CDC commit gap" should {
    for (scenario <- Vector("exact-replay", "split-replay")) {
      s"record T/J/D and full-row reconciliation for $scenario" taggedAs ExternalIntegration in {
        if (!sys.env.get("RUN_MYSQL_CDC_JOURNAL_WINDOW").contains("1"))
          cancel("external_blocked: set RUN_MYSQL_CDC_JOURNAL_WINDOW=1 for this isolated reproduction")
        val settings = Settings.environment()
        val suffix = UUID.randomUUID().toString.replace("-", "").take(12)
        val schema = s"pekko_test_cdc_window_$suffix"
        val connector = s"window_$suffix"
        val directory = Path.of(sys.env.getOrElse("CDC_WINDOW_EVIDENCE_DIR", "target/cdc-journal-window"),
          s"$scenario-$suffix").toAbsolutePath
        Files.createDirectories(directory)
        var original: Child = null
        var recovered: Child = null
        try {
          initialize(settings, schema)
          write(directory, "environment.json", JsObject("schema" -> JsString(schema),
            "connector" -> JsString(connector), "scenario" -> JsString(scenario),
            "mysql" -> withConnection(settings, schema)(query(_, "SELECT VERSION() AS version, @@binlog_format AS format, @@binlog_row_image AS row_image, @@gtid_mode AS gtid_mode")),
            "debezium" -> JsString("3.6.1.Final"), "pekko" -> JsString("1.1.3"),
            "java" -> JsString(System.getProperty("java.version"))))
          original = startChild(settings, schema, connector, "fresh", if (scenario == "exact-replay") 1 else 2,
            directory.resolve("original.log"))
          original.awaitEvent("PROCESS_READY")
          awaitCondition("initial snapshot full comparison", 180.seconds) { comparison(settings, schema).fields("equal") == JsBoolean(true) }
          // A real streaming mutation separates this experiment from unfinished initial snapshots.
          withConnection(settings, schema)(executeSql(_, "UPDATE source_rows SET label='streaming-ready' WHERE id=64"))
          awaitCondition("streaming mutation and a durable non-snapshot offset") {
            comparison(settings, schema).fields("equal") == JsBoolean(true) &&
              offsetState(settings, schema, connector).elements.exists { row =>
                val value = row.asJsObject.fields("offset_val").convertTo[String].parseJson.asJsObject
                value.fields.get("snapshot").forall(v => v == JsBoolean(false) || v == JsString("false"))
              }
          }
          // Do not ARM while a preceding commit/ACK/offset flush is still in flight.
          awaitCondition("baseline T/J/D and source acknowledgement agree") {
            val state = original.state()
            val checkpoint = journalCheckpoint(state)
            val t = withConnection(settings, schema)(query(_, "SELECT batch_id FROM pekko_sync_batch_ledger ORDER BY batch_sequence DESC LIMIT 1"))
            t.elements.last.asJsObject.fields("batch_id") == checkpoint.fields("batchId") &&
              original.events.exists(e => e.fields("kind") == JsString("ACK_RETURNED") &&
                e.fields.get("batchId").contains(checkpoint.fields("batchId"))) &&
              offsetState(settings, schema, connector).elements.exists(row =>
                row.asJsObject.fields("offset_val").convertTo[String].parseJson == checkpoint.fields("cursor").asJsObject.fields("offset"))
          }
          val baselineJ = original.state()
          val baseline = evidence(settings, schema, connector, baselineJ)
          write(directory, "baseline.json", baseline)
          baseline.fields("comparison").asJsObject.fields("equal") shouldBe JsBoolean(true)

          original.send("ARM")
          original.awaitEvent("ARMED")
          val predicate = if (scenario == "exact-replay") " WHERE id=1" else ""
          withConnection(settings, schema)(executeSql(_, "UPDATE source_rows SET revision=1, label=CONCAT('window-',id), " +
            "amount=amount+0.0100, note=IF(MOD(id,2)=0,NULL,'窗口-🙂'), updated_at='2026-10-18 12:34:56.123456'" + predicate))
          val cut = original.awaitEvent("CUTPOINT")
          val cutBatch = cut.fields("batch").asJsObject
          cutBatch.fields("rows").asInstanceOf[JsArray].elements.foreach { row =>
            row.asJsObject.fields("after").asJsObject.fields("revision") shouldBe JsNumber(1)
          }
          val jAtCut = original.state()
          val atCut = evidence(settings, schema, connector, jAtCut)
          write(directory, "cutpoint.json", JsObject(atCut.fields + ("cutBatch" -> cutBatch)))
          val latestT = atCut.fields("T").asInstanceOf[JsArray].elements.last.asJsObject
          val n = cutBatch.fields("sequence").convertTo[Long]
          latestT.fields("batch_sequence").convertTo[String].toLong shouldBe n
          journalCheckpoint(jAtCut).fields("sequence") shouldBe JsNumber(n - 1)
          jAtCut.fields("actorCheckpoints").asInstanceOf[JsArray].elements.last shouldBe journalCheckpoint(jAtCut)
          latestT.fields("batch_id") shouldBe cutBatch.fields("batchId")
          original.events.exists(e => e.fields("kind") == JsString("ACK_BEGIN") &&
            e.fields.get("batchId").contains(cutBatch.fields("batchId"))) shouldBe false
          val executionId = jAtCut.fields("executionId")

          if (scenario == "split-replay") tailMutations(settings, schema)
          write(directory, "before-crash.json", evidence(settings, schema, connector, original.state()))
          original.send("CRASH")
          original.exit() shouldBe 86
          val dAfterCrash = offsetState(settings, schema, connector)
          write(directory, "after-crash-D.json", dAfterCrash)
          dAfterCrash shouldBe atCut.fields("D")

          recovered = startChild(settings, schema, connector, "recover", 9999, directory.resolve("recovered.log"))
          val resumed = recovered.awaitEvent("READER_RESUME")
          resumed.fields("executionId") shouldBe executionId
          resumed.fields("checkpoint") shouldBe journalCheckpoint(jAtCut)
          val firstReplay = recovered.awaitEvent("SOURCE_BATCH").fields("batch").asJsObject
          firstReplay.fields("batchId") shouldBe cutBatch.fields("batchId")
          val oldRows = cutBatch.fields("rows").asInstanceOf[JsArray].elements
          val replayRows = firstReplay.fields("rows").asInstanceOf[JsArray].elements
          var outcome = ""
          if (scenario == "exact-replay") {
            oldRows.size shouldBe 1
            replayRows shouldBe oldRows
            firstReplay.fields("cursor") shouldBe cutBatch.fields("cursor")
            recovered.awaitEvent("SINK_COMMITTED").fields("result") shouldBe JsString("AlreadyCommitted")
            recovered.awaitEvent("ACK_RETURNED")
            val afterReplay = evidence(settings, schema, connector, recovered.state())
            write(directory, "after-replay-before-new-data.json", afterReplay)
            journalCheckpoint(afterReplay.fields("J").asJsObject).fields("sequence") shouldBe JsNumber(n)
            afterReplay.fields("audit") shouldBe atCut.fields("audit")
            afterReplay.fields("comparison").asJsObject.fields("equal") shouldBe JsBoolean(true)
            tailMutations(settings, schema)
            awaitCondition("post-recovery full field equality") { comparison(settings, schema).fields("equal") == JsBoolean(true) }
            outcome = "exact_batch_replayed_as_AlreadyCommitted_and_new_data_converged"
          } else {
            oldRows.size should be >= 2
            replayRows.size shouldBe 1
            // The event identity is the same, not merely an equal business payload.
            replayRows.head shouldBe oldRows.head
            val failure = recovered.awaitEvent("SINK_FAILED")
            failure.fields("error").convertTo[String] should include("conflict")
            awaitCondition("Actor persists failed execution") { recovered.state().fields("status") == JsString("failed") }
            withConnection(settings, schema)(query(_, "SELECT * FROM target_audit ORDER BY audit_id")) shouldBe atCut.fields("audit")
            comparison(settings, schema).fields("equal") shouldBe JsBoolean(false)
            outcome = "same_batch_id_different_batch_contents_conflict_and_actor_failed"
          }
          val finalEvidence = evidence(settings, schema, connector, recovered.state())
          val replayProof = JsObject("originalBatch" -> cutBatch, "firstReplayedBatch" -> firstReplay,
            "originalPid" -> JsNumber(original.pid), "recoveredPid" -> JsNumber(recovered.pid),
            "executionId" -> executionId, "outcome" -> JsString(outcome))
          write(directory, "replay.json", replayProof)
          write(directory, "final.json", finalEvidence)
          println(s"CDC_JOURNAL_WINDOW_REPRODUCED scenario=$scenario evidence=$directory outcome=$outcome")
        } catch {
          case error: Throwable =>
            write(directory, "harness-failure.json", JsObject("error" -> JsString(error.toString)))
            try {
              val observer = Option(recovered).filter(_.alive).orElse(Option(original).filter(_.alive))
              observer.foreach(child => write(directory, "failure-evidence.json", evidence(settings, schema, connector, child.state())))
            } catch { case scala.util.control.NonFatal(observation) =>
              write(directory, "failure-observation-error.json", JsString(observation.toString))
            }
            throw error
        } finally {
          if (original != null) original.kill()
          if (recovered != null) recovered.kill()
        }
      }
    }
  }
}

private[integration] object CdcJournalWindowSupport extends DefaultJsonProtocol {
  case class Settings(host: String, port: Int, user: String, password: String) {
    def url(schema: String): String = s"jdbc:mysql://$host:$port/$schema?useSSL=false&allowPublicKeyRetrieval=true" +
      "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8"
  }
  object Settings {
    def environment(): Settings = {
      val host = sys.env.getOrElse("CDC_WINDOW_MYSQL_HOST", "127.0.0.1")
      require(Set("127.0.0.1", "localhost").contains(host), "this fixture accepts only disposable loopback MySQL")
      Settings(host, sys.env("CDC_WINDOW_MYSQL_PORT").toInt,
        sys.env.getOrElse("CDC_WINDOW_MYSQL_USER", "cdc_window"), sys.env("CDC_WINDOW_MYSQL_PASSWORD"))
    }
  }
  def validateSchema(schema: String): Unit = require(schema.matches("pekko_test_cdc_window_[a-f0-9]{12}"))
  def withConnection[A](settings: Settings, schema: String)(body: Connection => A): A = {
    if (schema.nonEmpty) validateSchema(schema)
    val connection = DriverManager.getConnection(settings.url(schema), settings.user, settings.password)
    try body(connection) finally connection.close()
  }
  def executeSql(connection: Connection, sql: String): Unit = {
    val statement = connection.createStatement()
    try { statement.execute(sql); () } finally statement.close()
  }
  def query(connection: Connection, sql: String): JsArray = {
    val statement = connection.createStatement()
    val rows = statement.executeQuery(sql)
    try {
      val metadata = rows.getMetaData
      val result = Vector.newBuilder[JsValue]
      while (rows.next()) result += JsObject((1 to metadata.getColumnCount).map { index =>
        metadata.getColumnLabel(index) -> Option(rows.getString(index)).map(JsString(_)).getOrElse(JsNull)
      }.toMap)
      JsArray(result.result())
    } finally { rows.close(); statement.close() }
  }
  def initialize(settings: Settings, schema: String): Unit = {
    validateSchema(schema)
    withConnection(settings, "")(executeSql(_, s"CREATE DATABASE `$schema` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin"))
    withConnection(settings, schema) { connection =>
      Vector("db/mysql/pekko-persistence-schema.sql", "db/mysql/pekko-sync-ledger-schema.sql").foreach { resource =>
        val input = getClass.getClassLoader.getResourceAsStream(resource)
        require(input != null, resource)
        val sql = try IoSource.fromInputStream(input).mkString finally input.close()
        sql.split(";").iterator.map(_.trim).filter(_.nonEmpty).foreach(executeSql(connection, _))
      }
      val columns = "(id BIGINT PRIMARY KEY, label VARCHAR(128) NOT NULL, amount DECIMAL(18,4) NOT NULL, " +
        "note VARCHAR(240) NULL, updated_at DATETIME(6) NOT NULL, revision INT NOT NULL) ENGINE=InnoDB"
      executeSql(connection, s"CREATE TABLE source_rows $columns")
      executeSql(connection, s"CREATE TABLE target_rows $columns")
      executeSql(connection, "CREATE TABLE target_audit (audit_id BIGINT AUTO_INCREMENT PRIMARY KEY, op VARCHAR(1), " +
        "row_id BIGINT, old_revision INT, new_revision INT, old_label VARCHAR(128), new_label VARCHAR(128)) ENGINE=InnoDB")
      executeSql(connection, "CREATE TRIGGER audit_insert AFTER INSERT ON target_rows FOR EACH ROW INSERT INTO target_audit " +
        "(op,row_id,new_revision,new_label) VALUES ('I',NEW.id,NEW.revision,NEW.label)")
      executeSql(connection, "CREATE TRIGGER audit_update AFTER UPDATE ON target_rows FOR EACH ROW INSERT INTO target_audit " +
        "(op,row_id,old_revision,new_revision,old_label,new_label) VALUES ('U',NEW.id,OLD.revision,NEW.revision,OLD.label,NEW.label)")
      executeSql(connection, "CREATE TRIGGER audit_delete AFTER DELETE ON target_rows FOR EACH ROW INSERT INTO target_audit " +
        "(op,row_id,old_revision,old_label) VALUES ('D',OLD.id,OLD.revision,OLD.label)")
      (1 to 64).foreach { id => executeSql(connection,
        s"INSERT INTO source_rows VALUES ($id,'初始-$id',${id}.1200," +
          (if (id % 2 == 0) "NULL" else "'  中文🙂  '") + ",'2026-10-18 01:02:03.654321',0)") }
    }
  }
  def tailMutations(settings: Settings, schema: String): Unit = withConnection(settings, schema) { connection =>
    connection.setAutoCommit(false)
    executeSql(connection, "UPDATE source_rows SET revision=2,label='after-cut',amount=-123.4500,note='' WHERE id=1")
    executeSql(connection, "DELETE FROM source_rows WHERE id=64")
    executeSql(connection, "INSERT INTO source_rows VALUES (65,'新增🙂',99999999999999.9999,NULL,'2026-10-18 23:59:59.999999',2)")
    connection.commit()
  }
  def offsetState(settings: Settings, schema: String, connector: String): JsArray = withConnection(settings, schema) { connection =>
    val suffix = MessageDigest.getInstance("SHA-256").digest(connector.getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString.take(32)
    val table = s"debezium_offset_storage_$suffix"
    query(connection, s"SELECT offset_key,offset_val FROM `$table` ORDER BY offset_key")
  }
  def comparison(settings: Settings, schema: String): JsObject = withConnection(settings, schema) { connection =>
    connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ)
    connection.setAutoCommit(false)
    val source = query(connection, "SELECT * FROM source_rows ORDER BY id")
    val target = query(connection, "SELECT * FROM target_rows ORDER BY id")
    connection.commit()
    def indexed(rows: JsArray) = rows.elements.map(_.asJsObject).map(row => row.fields("id").convertTo[String] -> row).toMap
    val left = indexed(source); val right = indexed(target)
    val differences = (left.keySet intersect right.keySet).toVector.sorted.flatMap { id =>
      left(id).fields.toVector.sortBy(_._1).collect { case (field, value) if right(id).fields(field) != value =>
        JsObject("id" -> JsString(id), "field" -> JsString(field), "source" -> value, "target" -> right(id).fields(field))
      }
    }
    val missing = (left.keySet -- right.keySet).toVector.sorted.map(JsString(_))
    val extra = (right.keySet -- left.keySet).toVector.sorted.map(JsString(_))
    JsObject("source" -> source, "target" -> target, "sourceCount" -> JsNumber(source.elements.size),
      "targetCount" -> JsNumber(target.elements.size), "comparedFields" -> JsArray(Vector("id","label","amount","note","updated_at","revision").map(JsString(_))),
      "missingKeys" -> JsArray(missing), "extraKeys" -> JsArray(extra), "fieldDifferences" -> JsArray(differences),
      "equal" -> JsBoolean(missing.isEmpty && extra.isEmpty && differences.isEmpty))
  }
  def evidence(settings: Settings, schema: String, connector: String, j: JsObject): JsObject = JsObject(
    "T" -> withConnection(settings, schema)(query(_, "SELECT * FROM pekko_sync_batch_ledger ORDER BY batch_sequence")),
    "J" -> j, "D" -> offsetState(settings, schema, connector), "comparison" -> comparison(settings, schema),
    "audit" -> withConnection(settings, schema)(query(_, "SELECT * FROM target_audit ORDER BY audit_id")))
  def journalCheckpoint(state: JsObject): JsObject = state.fields("journal").asInstanceOf[JsArray].elements
    .map(_.asJsObject).filter(_.fields("checkpoint") != JsNull).last.fields("checkpoint").asJsObject
  def checkpointJson(checkpoint: BatchCheckpoint): JsObject = JsObject("sequence" -> JsNumber(checkpoint.batchSequence),
    "batchId" -> JsString(checkpoint.batchId), "cursor" -> checkpoint.cursor.value.parseJson,
    "sourceRows" -> JsNumber(checkpoint.sourceRowsScanned), "targetRows" -> JsNumber(checkpoint.targetRowsWritten))
  def batchJson(batch: SourceBatch): JsObject = JsObject("sequence" -> JsNumber(batch.batchSequence),
    "batchId" -> JsString(batch.batchId), "cursor" -> batch.cursor.value.parseJson,
    "rows" -> JsArray(batch.rows.map(_.parseJson)))
  def emit(kind: String, fields: (String, JsValue)*): Unit = synchronized {
    println("WINDOW " + JsObject(fields.toMap ++ Map("kind" -> JsString(kind), "at" -> JsString(java.time.Instant.now().toString))).compactPrint)
    System.out.flush()
  }
  def write(directory: Path, name: String, value: JsValue): Unit = {
    Files.writeString(directory.resolve(name), value.prettyPrint + "\n", UTF_8); ()
  }
  def awaitCondition(description: String, timeout: FiniteDuration = 60.seconds)(condition: => Boolean): Unit = {
    val deadline = timeout.fromNow
    var done = false
    while (!done && deadline.hasTimeLeft()) {
      done = condition
      if (!done) Thread.sleep(100)
    }
    require(done, s"condition timed out: $description")
  }
  final class Child(process: Process, log: Path) {
    private val output = new LinkedBlockingQueue[JsObject]()
    private val observed = new ConcurrentLinkedQueue[JsObject]()
    private val input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream, UTF_8))
    private val drain = new Thread(() => {
      val writer = Files.newBufferedWriter(log, UTF_8)
      val lines = IoSource.fromInputStream(process.getInputStream, "UTF-8")
      try lines.getLines().foreach { line =>
        writer.write(line); writer.newLine(); writer.flush()
        if (line.startsWith("WINDOW ")) {
          val event = line.stripPrefix("WINDOW ").parseJson.asJsObject
          observed.add(event); output.put(event)
        }
      } finally { lines.close(); writer.close() }
    })
    drain.setDaemon(true); drain.start()
    def pid: Long = process.pid()
    def alive: Boolean = process.isAlive
    def events: Vector[JsObject] = observed.iterator().asScala.toVector
    def send(command: String): Unit = { input.write(command); input.newLine(); input.flush() }
    def state(): JsObject = { send("STATE"); awaitEvent("STATE") }
    def awaitEvent(kind: String): JsObject = {
      val deadline = 60.seconds.fromNow
      while (deadline.hasTimeLeft()) {
        val event = output.poll(100, TimeUnit.MILLISECONDS)
        if (event != null && event.fields("kind") == JsString(kind)) return event
        if (!process.isAlive && !drain.isAlive && output.isEmpty)
          throw new AssertionError(s"child exited before $kind: ${process.exitValue()}; see $log")
      }
      throw new AssertionError(s"missing child event $kind; see $log")
    }
    def exit(): Int = {
      require(process.waitFor(30, TimeUnit.SECONDS), s"child failed to exit; see $log")
      drain.join(5000); process.exitValue()
    }
    def kill(): Unit = { if (process.isAlive) { process.destroyForcibly(); process.waitFor(20, TimeUnit.SECONDS) }; drain.join(5000) }
  }
  def startChild(settings: Settings, schema: String, connector: String, mode: String, minimum: Int, log: Path): Child = {
    val loaders = Iterator.iterate(Option(getClass.getClassLoader))(_.flatMap(l => Option(l.getParent)))
      .takeWhile(_.nonEmpty).flatten.collect { case l: URLClassLoader => l.getURLs.iterator }.flatten
      .filter(_.getProtocol == "file").map(u => Path.of(u.toURI).toString)
    val entries = (loaders ++ System.getProperty("java.class.path", "").split(File.pathSeparator).iterator)
      .filter(_.nonEmpty).toVector.distinct.mkString(File.pathSeparator)
    val builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString,
      "-Xmx512m", "-Duser.timezone=UTC", "-cp", entries, RealMySQLCdcJournalWindowProcess.getClass.getName.stripSuffix("$"),
      mode, schema, connector, minimum.toString)
    builder.redirectErrorStream(true)
    builder.environment().put("CDC_WINDOW_MYSQL_HOST", settings.host)
    builder.environment().put("CDC_WINDOW_MYSQL_PORT", settings.port.toString)
    builder.environment().put("CDC_WINDOW_MYSQL_USER", settings.user)
    builder.environment().put("CDC_WINDOW_MYSQL_PASSWORD", settings.password)
    new Child(builder.start(), log)
  }
}
