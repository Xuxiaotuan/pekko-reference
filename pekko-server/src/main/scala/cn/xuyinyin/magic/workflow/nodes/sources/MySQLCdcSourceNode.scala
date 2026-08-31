package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.workflow.checkpoint.{BatchCheckpoint, BatchId, SnapshotBoundary, SourceBatch, SourceCursor}
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base.{CheckpointedNodeSource, NodeSource}
import cn.xuyinyin.magic.workflow.nodes.cdc.MySQLCdcEnvelope
import com.typesafe.config.ConfigFactory
import org.apache.kafka.connect.source.SourceRecord
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.stream.scaladsl.Source
import spray.json._

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.{Connection, DatabaseMetaData, DriverManager, ResultSet, Types}
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

private[sources] final class CdcAcknowledgementRegistry {
  final class Scope private[CdcAcknowledgementRegistry] () {
    private[CdcAcknowledgementRegistry] var closed = false
  }

  final class Acknowledgement private[CdcAcknowledgementRegistry] (
    val scope: Scope,
    val bridge: DebeziumBatchBridge,
    val deliveryToken: Long
  )

  private val entries = new ConcurrentHashMap[String, Acknowledgement]()
  private val lock = new AnyRef

  def openScope(): Scope = new Scope()

  def register(
    scope: Scope,
    batchId: String,
    bridge: DebeziumBatchBridge,
    deliveryToken: Long
  ): Unit = lock.synchronized {
    if (scope.closed) {
      reject(bridge, deliveryToken, batchId, new IllegalStateException("CDC acknowledgement scope is closed"))
    }
    val entry = new Acknowledgement(scope, bridge, deliveryToken)
    if (entries.putIfAbsent(batchId, entry) != null) {
      reject(
        bridge,
        deliveryToken,
        batchId,
        new IllegalStateException(s"duplicate CDC batch acknowledgement entry: $batchId")
      )
    }
  }

  def claim(batchId: String): Acknowledgement = lock.synchronized {
    Option(entries.remove(batchId)).getOrElse(
      throw new IllegalStateException(s"no pending acknowledgement for CDC batch $batchId")
    )
  }

  def close(scope: Scope): Unit = lock.synchronized {
    if (!scope.closed) {
      scope.closed = true
      entries.entrySet().asScala.foreach { entry =>
        if (entry.getValue.scope eq scope) entries.remove(entry.getKey, entry.getValue)
      }
    }
  }

  private def reject(
    bridge: DebeziumBatchBridge,
    deliveryToken: Long,
    batchId: String,
    error: IllegalStateException
  ): Nothing = {
    try bridge.fail(deliveryToken, batchId, error)
    catch { case NonFatal(_) => () }
    throw error
  }
}

class MySQLCdcSourceNode(
  engineFactory: DebeziumEngineFactory = DebeziumEngineFactory.real,
  loadStateConfig: () => MySQLCdcStateConfig = () => MySQLCdcStateConfig.load(ConfigFactory.load())
) extends NodeSource with CheckpointedNodeSource {
  import MySQLCdcSourceNode._

  private val acknowledgements = new CdcAcknowledgementRegistry

  override val nodeType: String = "mysql.cdc"

  override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] =
    Source.failed(new UnsupportedOperationException("mysql.cdc requires checkpoint-aware execution"))

  protected[sources] def getenv(name: String): Option[String] = sys.env.get(name)

  protected[sources] def openMetadataConnection(config: MySQLCdcSourceConfig): Connection =
    DriverManager.getConnection(
      metadataJdbcUrl(config),
      config.username,
      config.password
    )

  override def discoverBoundary(
    node: WorkflowDSL.Node,
    resumeFrom: Option[BatchCheckpoint],
    onLog: String => Unit
  )(implicit blockingEc: ExecutionContext): Future[SnapshotBoundary] = {
    val config = parseConfig(node)
    Future {
      var connection: Connection = null
      try {
        connection = openMetadataConnection(config)
        val metadata = resolveMetadata(connection, config)
        val identity = streamIdentity(config, metadata)
        val boundary = SnapshotBoundary(node.id, partitionId(config.connectorId), Some(identity))
        validateBoundary(node, config, boundary, resumeFrom)
        onLog(s"MySQL CDC boundary ready connectorId=${config.connectorId} database=${config.database} table=${config.table}")
        boundary
      } finally close(connection)
    }(blockingEc)
  }

  override def createBatches(
    node: WorkflowDSL.Node,
    executionId: String,
    boundary: SnapshotBoundary,
    resumeFrom: Option[BatchCheckpoint],
    onLog: String => Unit
  )(implicit blockingEc: ExecutionContext): Source[SourceBatch, NotUsed] = {
    val config = parseConfig(node)
    validateBoundary(node, config, boundary, resumeFrom)
    val state = loadStateConfig()
    val properties = connectorProperties(config, state)
    val identity = boundary.upperBound.get
    val firstSequence = resumeFrom match {
      case Some(checkpoint) =>
        if (checkpoint.batchSequence == Long.MaxValue) {
          throw new IllegalArgumentException("checkpoint batch sequence is exhausted")
        }
        checkpoint.batchSequence + 1L
      case None => 0L
    }

    Source.unfoldResourceAsync[SourceBatch, CdcResource](
      create = () => Future {
        var connection: Connection = null
        try {
          connection = openMetadataConnection(config)
          val currentIdentity = streamIdentity(config, resolveMetadata(connection, config))
          if (currentIdentity != identity) {
            throw new IllegalArgumentException(
              "MySQL CDC source schema identity changed since boundary discovery"
            )
          }
        } finally close(connection)
        onLog(s"MySQL CDC engine starting connectorId=${config.connectorId} database=${config.database} table=${config.table}")
        new CdcResource(node.id, executionId, config, identity, firstSequence, properties)
      }(blockingEc),
      read = resource => Future(resource.nextBatch())(blockingEc),
      close = resource => Future {
        resource.close()
        Done
      }(blockingEc)
    )
  }

  override def acknowledgeCommittedBatch(
    node: WorkflowDSL.Node,
    batch: SourceBatch,
    onLog: String => Unit
  )(implicit blockingEc: ExecutionContext): Future[Done] = Future {
    val config = parseConfig(node)
    if (batch.sourceNodeId != node.id) {
      throw new IllegalArgumentException("CDC batch source does not match node")
    }
    if (batch.partitionId != partitionId(config.connectorId)) {
      throw new IllegalArgumentException("CDC batch partition does not match connector")
    }

    val acknowledgement = acknowledgements.claim(batch.batchId)
    acknowledgement.bridge.acknowledge(acknowledgement.deliveryToken, batch.batchId)
    onLog(s"MySQL CDC batch acknowledged batchId=${batch.batchId}")
    Done
  }(blockingEc)

  private def parseConfig(node: WorkflowDSL.Node): MySQLCdcSourceConfig =
    if (node.config.fields.contains("password")) MySQLCdcSourceConfig.parseTrustedRuntime(node)
    else MySQLCdcSourceConfig.parse(node, getenv)

  private final class CdcResource(
    sourceNodeId: String,
    executionId: String,
    config: MySQLCdcSourceConfig,
    streamIdentity: String,
    initialSequence: Long,
    properties: Properties
  ) extends AutoCloseable {
    private val acknowledgementScope = acknowledgements.openScope()
    private val bridge = new DebeziumBatchBridge
    private val engine = engineFactory.create(properties)
    private val callbackSequence = new AtomicLong(initialSequence)
    private val outputSequence = new AtomicLong(initialSequence)
    private val terminal = new AtomicReference[Try[Done]]()
    private val closed = new AtomicBoolean(false)

    private val consumer = new DebeziumBatchConsumer {
      override def handleBatch(records: Vector[SourceRecord], commitHandle: CdcBatchCommitHandle): Unit = {
        if (records == null || records.isEmpty) {
          throw new IllegalArgumentException("Debezium delivered an empty source-record batch")
        }
        val sequence = callbackSequence.getAndIncrement()
        val batchId = BatchId.sha256(executionId, sourceNodeId, partitionId(config.connectorId), sequence)
        val rows = records.flatMap { record =>
          MySQLCdcEnvelope.decode(record, config.connectorId) match {
            case Right(Some(envelope)) =>
              if (envelope.source.database != config.database || envelope.source.table != config.table) {
                throw new IllegalArgumentException("Debezium record source does not match configured table")
              }
              Some(envelope.canonicalJson)
            case Right(None) => None
            case Left(error) => throw new IllegalArgumentException(error.message)
          }
        }
        bridge.publish(batchId, rows, cursorValue(records.last), commitHandle)
      }
    }

    private val engineDone = try engine.start(consumer)
    catch {
      case error: Throwable =>
        bridge.close()
        try engine.close()
        catch { case NonFatal(_) => () }
        throw error
    }

    engineDone.onComplete { result =>
      val observed = result match {
        case Success(_) if !closed.get() =>
          Failure(new IllegalStateException("Debezium engine terminated unexpectedly"))
        case other => other
      }
      terminal.compareAndSet(null, observed)
      bridge.close()
    }(ExecutionContext.parasitic)

    def nextBatch(): Option[SourceBatch] = {
      try {
        val bridged = bridge.take()
        val sequence = outputSequence.get()
        val expectedBatchId = BatchId.sha256(
          executionId,
          sourceNodeId,
          partitionId(config.connectorId),
          sequence
        )
        if (bridged.batchId != expectedBatchId) {
          val error = new IllegalStateException("Debezium callback sequence does not match source sequence")
          bridge.fail(bridged.deliveryToken, bridged.batchId, error)
          throw error
        }
        acknowledgements.register(
          acknowledgementScope,
          bridged.batchId,
          bridge,
          bridged.deliveryToken
        )
        outputSequence.incrementAndGet()
        Some(SourceBatch(
          sourceNodeId = sourceNodeId,
          partitionId = partitionId(config.connectorId),
          batchSequence = sequence,
          batchId = bridged.batchId,
          cursor = SourceCursor(CursorKind, bridged.cursorValue, streamIdentity),
          rows = bridged.rows
        ))
      } catch {
        case error: IllegalStateException =>
          terminal.get() match {
            case Failure(cause) => throw cause
            case Success(_) => None
            case null if closed.get() => None
            case _ => throw error
          }
      }
    }

    override def close(): Unit = {
      if (closed.compareAndSet(false, true)) {
        acknowledgements.close(acknowledgementScope)
        bridge.close()
        engine.close()
      }
    }
  }
}

object MySQLCdcSourceNode {
  private val CursorKind = "mysql.binlog.v1"
  private val OffsetTableDdl =
    "CREATE TABLE %s (id VARCHAR(36) NOT NULL, offset_key VARCHAR(1255), offset_val VARCHAR(1255), " +
      "record_insert_ts TIMESTAMP(6) NOT NULL, record_insert_seq INT NOT NULL, PRIMARY KEY (id))"
  private val HistoryTableDdl =
    "CREATE TABLE %s (id VARCHAR(36) NOT NULL, history_data LONGTEXT, history_data_seq INT, " +
      "record_insert_ts TIMESTAMP(6) NOT NULL, record_insert_seq INT NOT NULL, " +
      "PRIMARY KEY (id, history_data_seq))"
  private val FingerprintPattern = "[0-9a-f]{64}".r
  private val SupportedJdbcTypes = Set(
    Types.BIT,
    Types.BOOLEAN,
    Types.TINYINT,
    Types.SMALLINT,
    Types.INTEGER,
    Types.BIGINT,
    Types.REAL,
    Types.FLOAT,
    Types.DOUBLE,
    Types.NUMERIC,
    Types.DECIMAL,
    Types.CHAR,
    Types.VARCHAR,
    Types.LONGVARCHAR,
    Types.NCHAR,
    Types.NVARCHAR,
    Types.LONGNVARCHAR,
    Types.DATE,
    Types.TIME,
    Types.TIME_WITH_TIMEZONE,
    Types.TIMESTAMP,
    Types.TIMESTAMP_WITH_TIMEZONE
  )

  private[sources] def metadataJdbcUrl(config: MySQLCdcSourceConfig): String =
    s"jdbc:mysql://${config.host}:${config.port}/${config.database}" +
      "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"

  private final case class ColumnMetadata(
    name: String,
    jdbcType: Int,
    typeName: String,
    nullable: Int,
    ordinal: Int,
    size: Int,
    scale: Int
  )

  private final case class ResolvedMetadata(primaryKey: String, columns: Vector[ColumnMetadata])

  private[sources] def connectorProperties(
    source: MySQLCdcSourceConfig,
    state: MySQLCdcStateConfig
  ): Properties = {
    val properties = new Properties()
    val (offsetTable, historyTable) = connectorStateTables(state, source.connectorId)
    Vector(
      "name" -> source.connectorId,
      "connector.class" -> "io.debezium.connector.mysql.MySqlConnector",
      "database.hostname" -> source.host,
      "database.port" -> source.port.toString,
      "database.user" -> source.username,
      "database.password" -> source.password,
      "driver.allowPublicKeyRetrieval" -> "true",
      "driver.forceConnectionTimeZoneToSession" -> "true",
      "database.connectionTimeZone" -> "UTC",
      "database.server.id" -> source.serverId.toString,
      "topic.prefix" -> source.connectorId,
      "database.include.list" -> source.database,
      "table.include.list" -> s"${source.database}.${source.table}",
      "snapshot.mode" -> "initial",
      "snapshot.locking.mode" -> "none",
      "record.processing.order" -> "ORDERED",
      "record.processing.threads" -> "1",
      "offset.flush.interval.ms" -> state.offsetFlushIntervalMillis.toString,
      "decimal.handling.mode" -> "string",
      "include.schema.changes" -> "false",
      "tombstones.on.delete" -> "false",
      "max.batch.size" -> source.maxBatchSize.toString,
      "poll.interval.ms" -> source.pollIntervalMillis.toString,
      "offset.storage" -> "io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore",
      "offset.storage.jdbc.connection.url" -> state.jdbcUrl,
      "offset.storage.jdbc.connection.user" -> state.username,
      "offset.storage.jdbc.connection.password" -> state.password,
      "offset.storage.jdbc.table.name" -> offsetTable,
      "offset.storage.jdbc.table.ddl" -> OffsetTableDdl,
      "schema.history.internal" -> "io.debezium.storage.jdbc.history.JdbcSchemaHistory",
      "schema.history.internal.jdbc.connection.url" -> state.jdbcUrl,
      "schema.history.internal.jdbc.connection.user" -> state.username,
      "schema.history.internal.jdbc.connection.password" -> state.password,
      "schema.history.internal.jdbc.table.name" -> historyTable,
      "schema.history.internal.jdbc.table.ddl" -> HistoryTableDdl
    ).foreach { case (key, value) => properties.setProperty(key, value) }
    properties
  }

  private[workflow] def connectorStateTables(
    state: MySQLCdcStateConfig,
    connectorId: String
  ): (String, String) = {
    val suffix = sha256(connectorId).take(32)
    def scoped(base: String): String = s"${base.take(31)}_$suffix"
    scoped(state.offsetTable) -> scoped(state.historyTable)
  }

  private def partitionId(connectorId: String): String = s"mysql-cdc:$connectorId"

  private def resolveMetadata(connection: Connection, config: MySQLCdcSourceConfig): ResolvedMetadata = {
    val metadata = connection.getMetaData
    val catalog = config.database
    val schema = Option(connection.getSchema).filter(_.nonEmpty)
    val table = metadataIdentifier(metadata, config.table)
    val primaryKeys = using(metadata.getPrimaryKeys(catalog, schema.orNull, table)) { resultSet =>
      Iterator.continually(resultSet.next()).takeWhile(identity)
        .filter(_ => matchesMetadataRow(resultSet, catalog, schema, table))
        .map(_ => resultSet.getInt("KEY_SEQ") -> resultSet.getString("COLUMN_NAME"))
        .toVector
        .sortBy(_._1)
        .map(_._2)
    }
    if (primaryKeys.size != 1) {
      throw new IllegalArgumentException("MySQL CDC source requires exactly one primary key")
    }

    val columns = using(metadata.getColumns(
      catalog,
      schema.orNull,
      escapeMetadataPattern(metadata, table),
      null
    )) { resultSet =>
      Iterator.continually(resultSet.next()).takeWhile(identity)
        .filter(_ => matchesMetadataRow(resultSet, catalog, schema, table))
        .map(_ => ColumnMetadata(
          name = resultSet.getString("COLUMN_NAME"),
          jdbcType = resultSet.getInt("DATA_TYPE"),
          typeName = resultSet.getString("TYPE_NAME"),
          nullable = resultSet.getInt("NULLABLE"),
          ordinal = resultSet.getInt("ORDINAL_POSITION"),
          size = resultSet.getInt("COLUMN_SIZE"),
          scale = resultSet.getInt("DECIMAL_DIGITS")
        ))
        .toVector
        .sortBy(_.ordinal)
    }
    if (columns.isEmpty) {
      throw new IllegalArgumentException("MySQL CDC source table metadata was not found")
    }
    if (columns.exists(column => column.name == null || column.name.isEmpty || column.typeName == null || column.typeName.isEmpty)) {
      throw new IllegalArgumentException("MySQL CDC source column metadata is incomplete")
    }
    if (columns.map(_.ordinal).exists(_ <= 0) || columns.map(_.ordinal).distinct.size != columns.size) {
      throw new IllegalArgumentException("MySQL CDC source column order is invalid")
    }
    if (columns.map(_.name.toLowerCase(java.util.Locale.ROOT)).distinct.size != columns.size) {
      throw new IllegalArgumentException("MySQL CDC source column names are ambiguous")
    }
    columns.find(column => column.name.equalsIgnoreCase(primaryKeys.head)).getOrElse(
      throw new IllegalArgumentException("MySQL CDC primary key column metadata was not found")
    )
    columns.find(column => !SupportedJdbcTypes.contains(column.jdbcType)).foreach { column =>
      throw new IllegalArgumentException(
        s"unsupported source column type for ${column.name}: ${column.typeName} (${column.jdbcType})"
      )
    }
    ResolvedMetadata(primaryKeys.head, columns)
  }

  private def streamIdentity(config: MySQLCdcSourceConfig, metadata: ResolvedMetadata): String = {
    val schema = JsObject(
      "version" -> JsNumber(1),
      "primaryKey" -> JsString(metadata.primaryKey),
      "columns" -> JsArray(metadata.columns.map { column => JsObject(
        "name" -> JsString(column.name),
        "jdbcType" -> JsNumber(column.jdbcType),
        "typeName" -> JsString(column.typeName),
        "nullable" -> JsNumber(column.nullable),
        "ordinal" -> JsNumber(column.ordinal),
        "size" -> JsNumber(column.size),
        "scale" -> JsNumber(column.scale)
      ) })
    )
    val fingerprint = sha256(canonicalJson(schema))
    canonicalJson(JsObject(
      "version" -> JsNumber(1),
      "connectorId" -> JsString(config.connectorId),
      "database" -> JsString(config.database),
      "table" -> JsString(config.table),
      "primaryKey" -> JsString(metadata.primaryKey),
      "columns" -> JsArray(metadata.columns.map { column => JsObject(
        "name" -> JsString(column.name),
        "jdbcType" -> JsNumber(column.jdbcType),
        "typeName" -> JsString(column.typeName)
      ) }),
      "schemaFingerprint" -> JsString(fingerprint)
    ))
  }

  private def validateBoundary(
    node: WorkflowDSL.Node,
    config: MySQLCdcSourceConfig,
    boundary: SnapshotBoundary,
    resumeFrom: Option[BatchCheckpoint]
  ): Unit = {
    if (boundary.sourceNodeId != node.id) {
      throw new IllegalArgumentException("CDC boundary source does not match node")
    }
    if (boundary.partitionId != partitionId(config.connectorId)) {
      throw new IllegalArgumentException("CDC boundary partition does not match connector")
    }
    val identity = boundary.upperBound.getOrElse(
      throw new IllegalArgumentException("CDC boundary stream identity is missing")
    )
    validateStreamIdentity(identity, config)
    resumeFrom.foreach { checkpoint =>
      if (checkpoint.sourceNodeId != node.id) {
        throw new IllegalArgumentException("CDC checkpoint source does not match node")
      }
      if (checkpoint.partitionId != boundary.partitionId) {
        throw new IllegalArgumentException("CDC checkpoint partition does not match boundary")
      }
      if (checkpoint.cursor.kind != CursorKind) {
        throw new IllegalArgumentException("CDC checkpoint cursor kind is unsupported")
      }
      if (checkpoint.cursor.upperBound != identity) {
        throw new IllegalArgumentException("CDC checkpoint stream identity does not match boundary")
      }
    }
  }

  private def validateStreamIdentity(identity: String, config: MySQLCdcSourceConfig): Unit = {
    val parsed = try identity.parseJson
    catch { case NonFatal(_) => throw new IllegalArgumentException("CDC stream identity is malformed") }
    if (canonicalJson(parsed) != identity) {
      throw new IllegalArgumentException("CDC stream identity is not canonical")
    }
    val fields = parsed match {
      case JsObject(value) => value
      case _ => throw new IllegalArgumentException("CDC stream identity must be an object")
    }
    val expected = Set("version", "connectorId", "database", "table", "primaryKey", "columns", "schemaFingerprint")
    if (fields.keySet != expected || fields.get("version") != Some(JsNumber(1))) {
      throw new IllegalArgumentException("CDC stream identity version or fields are invalid")
    }
    def string(name: String): String = fields.get(name) match {
      case Some(JsString(value)) if value.nonEmpty => value
      case _ => throw new IllegalArgumentException(s"CDC stream identity $name is invalid")
    }
    if (string("connectorId") != config.connectorId ||
      string("database") != config.database ||
      string("table") != config.table) {
      throw new IllegalArgumentException("CDC stream identity does not match source configuration")
    }
    val primaryKey = string("primaryKey")
    val columns = fields("columns") match {
      case JsArray(values) if values.nonEmpty => values.map {
        case JsObject(column) if column.keySet == Set("name", "jdbcType", "typeName") =>
          val name = column.get("name") match {
            case Some(JsString(value)) if value.nonEmpty => value
            case _ => throw new IllegalArgumentException("CDC stream identity column name is invalid")
          }
          column.get("jdbcType") match {
            case Some(JsNumber(value)) if value.isValidInt && SupportedJdbcTypes.contains(value.toInt) => ()
            case _ => throw new IllegalArgumentException("CDC stream identity column type is invalid")
          }
          column.get("typeName") match {
            case Some(JsString(value)) if value.nonEmpty => ()
            case _ => throw new IllegalArgumentException("CDC stream identity column type name is invalid")
          }
          name
        case _ => throw new IllegalArgumentException("CDC stream identity columns are invalid")
      }
      case _ => throw new IllegalArgumentException("CDC stream identity columns are missing")
    }
    if (!columns.exists(_.equalsIgnoreCase(primaryKey))) {
      throw new IllegalArgumentException("CDC stream identity primary key is not a source column")
    }
    if (!FingerprintPattern.pattern.matcher(string("schemaFingerprint")).matches()) {
      throw new IllegalArgumentException("CDC stream identity schema fingerprint is invalid")
    }
  }

  private def cursorValue(record: SourceRecord): String = {
    if (record == null || record.sourceOffset() == null) {
      throw new IllegalArgumentException("Debezium source offset is missing")
    }
    val offset = record.sourceOffset().asScala.toVector.map { case (key, value) =>
      key -> offsetValue(value, s"sourceOffset.$key")
    }.toMap
    canonicalJson(JsObject(
      "version" -> JsNumber(1),
      "offset" -> JsObject(offset)
    ))
  }

  private def offsetValue(value: Any, path: String): JsValue = value match {
    case null => JsNull
    case v: java.lang.Boolean => JsBoolean(v.booleanValue())
    case v: java.lang.Byte => JsNumber(v.longValue())
    case v: java.lang.Short => JsNumber(v.longValue())
    case v: java.lang.Integer => JsNumber(v.longValue())
    case v: java.lang.Long => JsNumber(v.longValue())
    case v: java.math.BigInteger => JsNumber(BigDecimal(v))
    case v: java.math.BigDecimal => JsNumber(BigDecimal(v))
    case v: java.lang.Float if java.lang.Float.isFinite(v) => JsNumber(BigDecimal.decimal(v.floatValue()))
    case v: java.lang.Double if java.lang.Double.isFinite(v) => JsNumber(BigDecimal.decimal(v.doubleValue()))
    case v: String => JsString(v)
    case other => throw new IllegalArgumentException(s"unsupported Debezium offset value at $path: ${other.getClass.getSimpleName}")
  }

  private def canonicalJson(value: JsValue): String = value match {
    case JsObject(fields) => fields.toVector.sortBy(_._1).map { case (key, field) =>
      s"${JsString(key).compactPrint}:${canonicalJson(field)}"
    }.mkString("{", ",", "}")
    case JsArray(elements) => elements.map(canonicalJson).mkString("[", ",", "]")
    case other => other.compactPrint
  }

  private def sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private def matchesMetadataRow(
    resultSet: ResultSet,
    catalog: String,
    schema: Option[String],
    table: String
  ): Boolean =
    Option(resultSet.getString("TABLE_CAT")).exists(_.equalsIgnoreCase(catalog)) &&
      schema.map(expected => Option(resultSet.getString("TABLE_SCHEM")).exists(_.equalsIgnoreCase(expected)))
        .getOrElse(resultSet.getString("TABLE_SCHEM") == null) &&
      Option(resultSet.getString("TABLE_NAME")).exists(_.equalsIgnoreCase(table))

  private def metadataIdentifier(metadata: DatabaseMetaData, identifier: String): String =
    if (metadata.storesUpperCaseIdentifiers) identifier.toUpperCase(java.util.Locale.ROOT)
    else if (metadata.storesLowerCaseIdentifiers) identifier.toLowerCase(java.util.Locale.ROOT)
    else identifier

  private def escapeMetadataPattern(metadata: DatabaseMetaData, identifier: String): String = {
    val escape = metadata.getSearchStringEscape
    if (escape == null || escape.isEmpty) identifier
    else identifier
      .replace(escape, escape + escape)
      .replace("_", escape + "_")
      .replace("%", escape + "%")
  }

  private def using[A <: AutoCloseable, B](resource: A)(body: A => B): B =
    try body(resource)
    finally close(resource)

  private def close(resource: AutoCloseable): Unit =
    if (resource != null) {
      try resource.close()
      catch { case NonFatal(_) => () }
    }
}
