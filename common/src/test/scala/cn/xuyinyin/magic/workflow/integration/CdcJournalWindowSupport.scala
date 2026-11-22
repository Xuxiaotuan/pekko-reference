package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.cdc.checkpoint.{BatchCheckpoint, SourceBatch}
import spray.json._
import java.io.{BufferedWriter, OutputStreamWriter}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.sql.{Connection, DriverManager}
import java.util.concurrent.{ConcurrentLinkedQueue, LinkedBlockingQueue, TimeUnit}
import scala.concurrent.duration._
import scala.io.{Source => IoSource}
import scala.jdk.CollectionConverters._

private[integration] object CdcJournalWindowSupport extends DefaultJsonProtocol {
  case class Settings(host: String, port: Int, user: String, password: String) {
    def url(schema: String): String = s"jdbc:mysql://$host:$port/$schema?useSSL=false&allowPublicKeyRetrieval=true" +
      "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8"
  }
  object Settings {
    def environment(): Settings = {
      val host = sys.env.getOrElse("CDC_WINDOW_MYSQL_HOST", "127.0.0.1")
      require(Set("127.0.0.1", "localhost").contains(host), "this fixture accepts only disposable loopback MySQL")
      Settings(
        host,
        sys.env("CDC_WINDOW_MYSQL_PORT").toInt,
        sys.env.getOrElse("CDC_WINDOW_MYSQL_USER", "cdc_window"),
        sys.env("CDC_WINDOW_MYSQL_PASSWORD"))
    }
  }
  def validateSchema(schema: String): Unit = require(
    schema.matches("pekko_test_cdc_window_[a-f0-9]{12}") ||
      (schema.matches("pekko_gc_[a-f0-9]{12}") && sys.env.get("RUN_CAPTURE_GC_MATRIX").contains("1") &&
        sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("capture-gc")))
  def withConnection[A](settings: Settings, schema: String)(body: Connection => A): A = {
    if (schema.nonEmpty) validateSchema(schema)
    // SBT may use a fresh test classloader for each testOnly invocation.
    Class.forName("com.mysql.cj.jdbc.Driver")
    val connection = DriverManager.getConnection(settings.url(schema), settings.user, settings.password)
    try body(connection)
    finally connection.close()
  }
  def executeSql(connection: Connection, sql: String): Unit = {
    val statement = connection.createStatement()
    try { statement.execute(sql); () }
    finally statement.close()
  }
  def query(connection: Connection, sql: String): JsArray = {
    val statement = connection.createStatement()
    val rows      = statement.executeQuery(sql)
    try {
      val metadata = rows.getMetaData
      val result   = Vector.newBuilder[JsValue]
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
        val sql =
          try IoSource.fromInputStream(input).mkString
          finally input.close()
        sql.split(";").iterator.map(_.trim).filter(_.nonEmpty).foreach(executeSql(connection, _))
      }
      val columns = "(id BIGINT PRIMARY KEY, label VARCHAR(128) NOT NULL, amount DECIMAL(18,4) NOT NULL, " +
        "note VARCHAR(240) NULL, updated_at DATETIME(6) NOT NULL, revision INT NOT NULL) ENGINE=InnoDB"
      executeSql(connection, s"CREATE TABLE source_rows $columns")
      executeSql(connection, s"CREATE TABLE target_rows $columns")
      executeSql(
        connection,
        "CREATE TABLE target_audit (audit_id BIGINT AUTO_INCREMENT PRIMARY KEY, op VARCHAR(1), " +
          "row_id BIGINT, old_revision INT, new_revision INT, old_label VARCHAR(128), new_label VARCHAR(128)) ENGINE=InnoDB")
      executeSql(
        connection,
        "CREATE TRIGGER audit_insert AFTER INSERT ON target_rows FOR EACH ROW INSERT INTO target_audit " +
          "(op,row_id,new_revision,new_label) VALUES ('I',NEW.id,NEW.revision,NEW.label)")
      executeSql(
        connection,
        "CREATE TRIGGER audit_update AFTER UPDATE ON target_rows FOR EACH ROW INSERT INTO target_audit " +
          "(op,row_id,old_revision,new_revision,old_label,new_label) VALUES ('U',NEW.id,OLD.revision,NEW.revision,OLD.label,NEW.label)")
      executeSql(
        connection,
        "CREATE TRIGGER audit_delete AFTER DELETE ON target_rows FOR EACH ROW INSERT INTO target_audit " +
          "(op,row_id,old_revision,old_label) VALUES ('D',OLD.id,OLD.revision,OLD.label)")
      (1 to 64).foreach { id =>
        executeSql(
          connection,
          s"INSERT INTO source_rows VALUES ($id,'初始-$id',${id}.1200," +
            (if (id % 2 == 0) "NULL" else "'  中文🙂  '") + ",'2026-10-18 01:02:03.654321',0)")
      }
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
    val suffix = MessageDigest.getInstance("SHA-256").digest(connector.getBytes(UTF_8)).map(b => f"${b & 0xFF}%02x").mkString.take(32)
    val table  = s"debezium_offset_storage_$suffix"
    query(connection, s"SELECT offset_key,offset_val FROM `$table` ORDER BY offset_key")
  }
  def comparison(settings: Settings, schema: String): JsObject = withConnection(settings, schema) { connection =>
    connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ)
    connection.setAutoCommit(false)
    val source = query(connection, "SELECT * FROM source_rows ORDER BY id")
    val target = query(connection, "SELECT * FROM target_rows ORDER BY id")
    connection.commit()
    def indexed(rows: JsArray) = rows.elements.map(_.asJsObject).map(row => row.fields("id").convertTo[String] -> row).toMap
    val left                   = indexed(source); val right = indexed(target)
    val differences = (left.keySet intersect right.keySet).toVector.sorted.flatMap { id =>
      left(id).fields.toVector.sortBy(_._1).collect {
        case (field, value) if right(id).fields(field) != value =>
          JsObject("id" -> JsString(id), "field" -> JsString(field), "source" -> value, "target" -> right(id).fields(field))
      }
    }
    val missing = (left.keySet -- right.keySet).toVector.sorted.map(JsString(_))
    val extra   = (right.keySet -- left.keySet).toVector.sorted.map(JsString(_))
    JsObject(
      "source"           -> source,
      "target"           -> target,
      "sourceCount"      -> JsNumber(source.elements.size),
      "targetCount"      -> JsNumber(target.elements.size),
      "comparedFields"   -> JsArray(Vector("id", "label", "amount", "note", "updated_at", "revision").map(JsString(_))),
      "missingKeys"      -> JsArray(missing),
      "extraKeys"        -> JsArray(extra),
      "fieldDifferences" -> JsArray(differences),
      "equal"            -> JsBoolean(missing.isEmpty && extra.isEmpty && differences.isEmpty))
  }
  def evidence(settings: Settings, schema: String, connector: String, j: JsObject): JsObject = JsObject(
    "T"          -> withConnection(settings, schema)(query(_, "SELECT * FROM pekko_sync_batch_ledger ORDER BY batch_sequence")),
    "J"          -> j,
    "D"          -> offsetState(settings, schema, connector),
    "comparison" -> comparison(settings, schema),
    "audit"      -> withConnection(settings, schema)(query(_, "SELECT * FROM target_audit ORDER BY audit_id")))
  def journalCheckpoint(state: JsObject): JsObject = state
    .fields("journal")
    .asInstanceOf[JsArray]
    .elements
    .map(_.asJsObject)
    .filter(_.fields("checkpoint") != JsNull)
    .last
    .fields("checkpoint")
    .asJsObject
  def checkpointJson(checkpoint: BatchCheckpoint): JsObject = JsObject(
    "sequence"   -> JsNumber(checkpoint.batchSequence),
    "batchId"    -> JsString(checkpoint.batchId),
    "cursor"     -> checkpoint.cursor.value.parseJson,
    "sourceRows" -> JsNumber(checkpoint.sourceRowsScanned),
    "targetRows" -> JsNumber(checkpoint.targetRowsWritten))
  def batchJson(batch: SourceBatch): JsObject = JsObject(
    "sequence" -> JsNumber(batch.batchSequence),
    "batchId"  -> JsString(batch.batchId),
    "cursor"   -> batch.cursor.value.parseJson,
    "rows"     -> JsArray(batch.rows.map(_.parseJson)))
  def emit(kind: String, fields: (String, JsValue)*): Unit = synchronized {
    println("WINDOW " + JsObject(fields.toMap ++ Map("kind" -> JsString(kind), "at" -> JsString(java.time.Instant.now().toString))).compactPrint)
    System.out.flush()
  }
  def write(directory: Path, name: String, value: JsValue): Unit = {
    Files.writeString(directory.resolve(name), value.prettyPrint + "\n", UTF_8); ()
  }
  def awaitCondition(description: String, timeout: FiniteDuration = 60.seconds)(condition: => Boolean): Unit = {
    val deadline = timeout.fromNow
    var done     = false
    while (!done && deadline.hasTimeLeft()) {
      done = condition
      if (!done) Thread.sleep(100)
    }
    require(done, s"condition timed out: $description")
  }
  final class Child(process: Process, log: Path) {
    private val output   = new LinkedBlockingQueue[JsObject]()
    private val observed = new ConcurrentLinkedQueue[JsObject]()
    private val input    = new BufferedWriter(new OutputStreamWriter(process.getOutputStream, UTF_8))
    private val drain = new Thread(() => {
      val writer = Files.newBufferedWriter(log, UTF_8)
      val lines  = IoSource.fromInputStream(process.getInputStream, "UTF-8")
      try
        lines.getLines().foreach { line =>
          writer.write(line); writer.newLine(); writer.flush()
          if (line.startsWith("WINDOW ")) {
            val event = line.stripPrefix("WINDOW ").parseJson.asJsObject
            observed.add(event); output.put(event)
          }
        }
      finally { lines.close(); writer.close() }
    })
    drain.setDaemon(true); drain.start()
    def pid: Long                          = process.pid()
    def alive: Boolean                     = process.isAlive
    def events: Vector[JsObject]           = observed.iterator().asScala.toVector
    def send(command: String): Unit        = { input.write(command); input.newLine(); input.flush() }
    def state(): JsObject                  = { send("STATE"); awaitEvent("STATE") }
    def awaitEvent(kind: String): JsObject = awaitOneOf(Set(kind))
    def awaitOneOf(kinds: Set[String]): JsObject = {
      val deadline = 60.seconds.fromNow
      while (deadline.hasTimeLeft()) {
        val event = output.poll(100, TimeUnit.MILLISECONDS)
        if (event != null && kinds(event.fields("kind").convertTo[String])) return event
        if (!process.isAlive && !drain.isAlive && output.isEmpty)
          throw new AssertionError(s"child exited before $kinds: ${process.exitValue()}; see $log")
      }
      throw new AssertionError(s"missing child event $kinds; see $log")
    }
    def exit(): Int = {
      require(process.waitFor(30, TimeUnit.SECONDS), s"child failed to exit; see $log")
      drain.join(5000); process.exitValue()
    }
    def kill(): Unit = { if (process.isAlive) { process.destroyForcibly(); process.waitFor(20, TimeUnit.SECONDS) }; drain.join(5000) }
  }
}
