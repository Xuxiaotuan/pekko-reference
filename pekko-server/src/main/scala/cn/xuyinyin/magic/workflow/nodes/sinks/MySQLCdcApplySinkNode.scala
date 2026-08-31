package cn.xuyinyin.magic.workflow.nodes.sinks

import cn.xuyinyin.magic.workflow.checkpoint.{AlreadyCommitted, BatchCheckpoint, BatchCommitResult, Committed, SnapshotBoundary, SourceBatch}
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base.{CheckpointedNodeSink, NodeSink}
import cn.xuyinyin.magic.workflow.nodes.cdc.{CdcOperation, MySQLCdcEnvelope}
import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.Sink
import spray.json._

import java.math.RoundingMode
import java.sql.{Connection, DatabaseMetaData, PreparedStatement, ResultSet, SQLException, Timestamp, Types}
import java.time.{Instant, LocalDate, LocalDateTime, LocalTime, OffsetDateTime, OffsetTime}
import java.util.{Calendar, Locale, TimeZone}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class MySQLCdcApplySinkNode extends NodeSink with CheckpointedNodeSink {
  import MySQLCdcApplySinkNode._

  override val nodeType: String = "mysql.cdc.apply"

  override def createSink(node: WorkflowDSL.Node, onLog: String => Unit)
                         (implicit ec: ExecutionContext): Sink[String, Future[Done]] =
    throw new UnsupportedOperationException("mysql.cdc.apply requires checkpoint-aware execution")

  protected[sinks] def getenv(name: String): Option[String] = sys.env.get(name)

  protected[sinks] def openConnection(config: MySQLCdcApplyConfig): Connection =
    java.sql.DriverManager.getConnection(
      MySQLCdcApplySinkNode.jdbcUrl(config),
      config.username,
      config.password
    )

  protected[sinks] def beforeLedgerClaim(batchId: String): Unit = ()

  protected[sinks] def targetTypeName(rows: ResultSet): String = requiredMetadata(rows, "TYPE_NAME")

  override def validateReady(
    node: WorkflowDSL.Node,
    onLog: String => Unit
  )(implicit blockingEc: ExecutionContext): Future[Done] = {
    val config = parseConfig(node)
    Future {
      var connection: Connection = null
      try {
        connection = openConnection(config)
        resolveTarget(connection, config)
        validateLedger(connection)
        safeLog(onLog, s"MySQL CDC apply target ready database=${config.database} table=${config.table}")
        Done
      } catch {
        case error: IllegalStateException => throw error
        case NonFatal(error) => throw new IllegalStateException("MySQL CDC apply readiness validation failed", error)
      } finally close(connection)
    }(blockingEc)
  }

  override def validateSourceBoundary(
    node: WorkflowDSL.Node,
    boundary: SnapshotBoundary,
    onLog: String => Unit
  )(implicit blockingEc: ExecutionContext): Future[Done] = {
    val config = parseConfig(node)
    val identity = parseBoundary(boundary)
    Future {
      var connection: Connection = null
      try {
        connection = openConnection(config)
        val target = resolveTarget(connection, config)
        validateCompatibility(identity, target)
        safeLog(onLog, s"MySQL CDC apply source boundary validated database=${identity.database} table=${identity.table}")
        Done
      } catch {
        case error: IllegalStateException => throw error
        case NonFatal(error) => throw new IllegalStateException("MySQL CDC source boundary validation failed", error)
      } finally close(connection)
    }(blockingEc)
  }

  override def commitBatch(
    node: WorkflowDSL.Node,
    workflowId: String,
    executionId: String,
    batch: SourceBatch,
    transformedRows: Vector[String],
    onLog: String => Unit
  )(implicit blockingEc: ExecutionContext): Future[BatchCommitResult] = {
    val config = parseConfig(node)
    Future {
      if (transformedRows.size != batch.rows.size) {
        throw new IllegalStateException("CDC transformed row count does not match source row count")
      }
      val identity = parseBatchIdentity(batch)
      val envelopes = transformedRows.map(parseEnvelope)
      var connection: Connection = null
      try {
        connection = openConnection(config)
        val target = resolveTarget(connection, config)
        validateCompatibility(identity, target)
        val planned = envelopes.map(envelope => plan(envelope, identity, target))
        val checkpoint = BatchCheckpoint(
          batch.sourceNodeId,
          batch.partitionId,
          batch.batchSequence,
          batch.batchId,
          batch.cursor,
          batch.rows.size.toLong,
          transformedRows.size.toLong
        )
        val expected = LedgerRecord(batch.batchId, workflowId, executionId, checkpoint)
        connection.setAutoCommit(false)
        val result = findLedger(connection, batch.batchId) match {
          case Some(existing) =>
            connection.rollback()
            if (existing == expected) AlreadyCommitted(existing.checkpoint)
            else throw conflictingLedger(batch.batchId)
          case None =>
            beforeLedgerClaim(batch.batchId)
            val duplicate = try {
              insertLedger(connection, expected)
              None
            } catch {
              case sql: SQLException if isDuplicateKey(sql) =>
                connection.rollback()
                Some(sql)
            }
            duplicate match {
              case Some(sql) =>
                findLedger(connection, expected.batchId) match {
                  case Some(existing) if existing == expected => AlreadyCommitted(existing.checkpoint)
                  case Some(_) => throw conflictingLedger(expected.batchId)
                  case None => throw new IllegalStateException("MySQL CDC ledger claim failed", sql)
                }
              case None =>
                planned.foreach(applyEvent(connection, target, _))
                connection.commit()
                Committed(checkpoint)
            }
        }
        safeLog(onLog, s"MySQL CDC batch applied batchId=${batch.batchId} events=${planned.size}")
        result
      } catch {
        case error: IllegalStateException =>
          rollback(connection)
          throw error
        case NonFatal(error) =>
          rollback(connection)
          throw new IllegalStateException("MySQL CDC batch apply failed", error)
      } finally close(connection)
    }(blockingEc)
  }

  private def parseConfig(node: WorkflowDSL.Node): MySQLCdcApplyConfig =
    if (node.config.fields.contains("password")) MySQLCdcApplyConfig.parseTrustedRuntime(node)
    else MySQLCdcApplyConfig.parse(node, getenv)

  private def resolveTarget(connection: Connection, config: MySQLCdcApplyConfig): TargetTable = {
    val metadata = connection.getMetaData
    val catalog = Option(connection.getCatalog).filter(_.nonEmpty).getOrElse(config.database)
    val schema = Option(connection.getSchema).filter(_.nonEmpty)
    val tableName = metadataIdentifier(metadata, config.table)
    val columns = using(metadata.getColumns(catalog, schema.orNull, escapePattern(metadata, tableName), null)) { rows =>
      val builder = Vector.newBuilder[TargetColumn]
      while (rows.next()) {
        if (matches(rows, catalog, schema, tableName)) {
          builder += TargetColumn(
            name = requiredMetadata(rows, "COLUMN_NAME"),
            jdbcType = rows.getInt("DATA_TYPE"),
            typeName = targetTypeName(rows),
            nullable = rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
            defaultValue = Option(rows.getString("COLUMN_DEF")),
            ordinal = rows.getInt("ORDINAL_POSITION"),
            size = rows.getInt("COLUMN_SIZE"),
            scale = rows.getInt("DECIMAL_DIGITS")
          )
        }
      }
      builder.result().sortBy(_.ordinal)
    }
    if (columns.isEmpty) throw new IllegalStateException("MySQL CDC target table metadata was not found")
    if (columns.map(_.lowerName).distinct.size != columns.size || columns.exists(_.ordinal <= 0)) {
      throw new IllegalStateException("MySQL CDC target column metadata is ambiguous or unordered")
    }
    columns.foreach { column =>
      requireIdentifier(column.name, "target column")
      if (IntegralTypes.contains(column.jdbcType) && column.typeName.toUpperCase(Locale.ROOT).contains("UNSIGNED")) {
        throw new IllegalStateException(s"unsupported target UNSIGNED integral type for ${column.name}: ${column.typeName}")
      }
      if (!supportedTarget(column)) {
        throw new IllegalStateException(s"unsupported target column type ${column.name}: ${column.typeName}")
      }
    }
    val primaryKeys = using(metadata.getPrimaryKeys(catalog, schema.orNull, tableName)) { rows =>
      val builder = Vector.newBuilder[(Int, String)]
      while (rows.next()) {
        if (matches(rows, catalog, schema, tableName)) {
          builder += rows.getInt("KEY_SEQ") -> requiredMetadata(rows, "COLUMN_NAME")
        }
      }
      builder.result().sortBy(_._1).map(_._2)
    }
    if (primaryKeys.size != 1) {
      throw new IllegalStateException("MySQL CDC apply target requires exactly one primary key")
    }
    val primaryKey = columns.find(_.name.equalsIgnoreCase(primaryKeys.head)).getOrElse(
      throw new IllegalStateException("MySQL CDC target primary key metadata is missing")
    )
    val quote = Option(metadata.getIdentifierQuoteString).map(_.trim).filter(_.nonEmpty).getOrElse(
      throw new IllegalStateException("JDBC target does not support quoted identifiers")
    )
    TargetTable(tableName, columns, primaryKey, quote)
  }

  private def validateCompatibility(identity: StreamIdentity, target: TargetTable): Unit = {
    if (!target.primaryKey.name.equalsIgnoreCase(identity.primaryKey)) {
      throw new IllegalStateException(
        s"source primary key ${identity.primaryKey} does not match target primary key ${target.primaryKey.name}"
      )
    }
    val sourceNames = identity.columns.map(_.lowerName).toSet
    identity.columns.foreach { source =>
      val targetColumn = target.byLowerName.getOrElse(source.lowerName, {
        throw new IllegalStateException(s"target is missing source column ${source.name}")
      })
      if (!compatibleType(source, targetColumn)) {
        throw new IllegalStateException(
          s"source type ${source.typeName} (${source.jdbcType}) for ${source.name} cannot be applied losslessly " +
            s"to target type ${targetColumn.typeName} (${targetColumn.jdbcType})"
        )
      }
    }
    target.columns.find(column => !sourceNames.contains(column.lowerName) && !column.nullable && column.defaultValue.isEmpty)
      .foreach(column => throw new IllegalStateException(
        s"required extra target column ${column.name} has no default"
      ))
  }

  private def plan(envelope: MySQLCdcEnvelope, identity: StreamIdentity, target: TargetTable): PlannedEvent = {
    if (envelope.version != 1) throw new IllegalStateException("unsupported CDC envelope version")
    if (envelope.source.connectorId != identity.connectorId ||
      envelope.source.database != identity.database || envelope.source.table != identity.table) {
      throw new IllegalStateException("CDC envelope source does not match batch stream identity")
    }
    val keyValue = exactField(envelope.key, identity.primaryKey, "key")
    val boundKey = bindValue(target.primaryKey, keyValue)
    def checkedImage(image: JsObject, path: String): Vector[(TargetColumn, BoundValue)] = {
      val expected = identity.columns.map(_.lowerName).toSet
      val actual = image.fields.keys.map(_.toLowerCase(Locale.ROOT)).toSet
      if (actual != expected) {
        throw new IllegalStateException(s"CDC $path image is not a complete source row")
      }
      identity.columns.map { source =>
        val column = target.byLowerName(source.lowerName)
        column -> bindValue(column, exactField(image, source.name, path))
      }
    }
    def requireSameKey(image: JsObject, path: String): Unit = {
      if (exactField(image, identity.primaryKey, path) != keyValue) {
        throw new IllegalStateException(s"CDC $path primary key does not match event key")
      }
    }
    envelope.op match {
      case CdcOperation.Read | CdcOperation.Create =>
        val after = envelope.after.getOrElse(throw new IllegalStateException("CDC upsert requires after image"))
        requireSameKey(after, "after")
        Upsert(checkedImage(after, "after"))
      case CdcOperation.Update =>
        val before = envelope.before.getOrElse(throw new IllegalStateException("CDC update requires before image"))
        val after = envelope.after.getOrElse(throw new IllegalStateException("CDC update requires after image"))
        requireSameKey(before, "before")
        requireSameKey(after, "after")
        Upsert(checkedImage(after, "after"))
      case CdcOperation.Delete =>
        val before = envelope.before.getOrElse(throw new IllegalStateException("CDC delete requires before image"))
        requireSameKey(before, "before")
        Delete(boundKey)
    }
  }

  private def applyEvent(connection: Connection, target: TargetTable, event: PlannedEvent): Unit = event match {
    case Upsert(values) =>
      val columns = values.map(_._1)
      val nonKey = columns.filterNot(_.name.equalsIgnoreCase(target.primaryKey.name))
      val assignments = if (nonKey.nonEmpty) nonKey.map(column =>
        s"${target.quoted(column.name)}=VALUES(${target.quoted(column.name)})"
      ) else Vector(s"${target.quoted(target.primaryKey.name)}=VALUES(${target.quoted(target.primaryKey.name)})")
      val sql = s"INSERT INTO ${target.quoted(target.name)} (${columns.map(c => target.quoted(c.name)).mkString(", ")}) " +
        s"VALUES (${Vector.fill(columns.size)("?").mkString(", ")}) ON DUPLICATE KEY UPDATE ${assignments.mkString(", ")}"
      using(connection.prepareStatement(sql)) { statement =>
        values.zipWithIndex.foreach { case ((_, value), index) => set(statement, index + 1, value) }
        statement.executeUpdate()
      }
    case Delete(key) =>
      val sql = s"DELETE FROM ${target.quoted(target.name)} WHERE ${target.quoted(target.primaryKey.name)} = ?"
      using(connection.prepareStatement(sql)) { statement =>
        set(statement, 1, key)
        statement.executeUpdate()
      }
  }

  private def bindValue(column: TargetColumn, value: JsValue): BoundValue = {
    if (value == JsNull) {
      if (!column.nullable) throw new IllegalStateException(s"target column ${column.name} does not allow null")
      NullValue(column.jdbcType)
    } else column.jdbcType match {
      case Types.BOOLEAN | Types.BIT => value match {
        case JsBoolean(v) => ObjectValue(column.jdbcType, Boolean.box(v))
        case _ => conversion(column)
      }
      case Types.TINYINT =>
        val number = exactLong(value, column)
        if (number < Byte.MinValue || number > Byte.MaxValue) conversion(column)
        IntegralValue(column.jdbcType, number.toByte)
      case Types.SMALLINT =>
        val number = exactLong(value, column)
        if (number < Short.MinValue || number > Short.MaxValue) conversion(column)
        IntegralValue(column.jdbcType, number.toShort)
      case Types.INTEGER =>
        val number = exactLong(value, column)
        if (number < Int.MinValue || number > Int.MaxValue) conversion(column)
        IntegralValue(column.jdbcType, number.toInt)
      case Types.BIGINT => IntegralValue(column.jdbcType, exactLong(value, column))
      case Types.NUMERIC | Types.DECIMAL =>
        val decimal = value match {
          case JsString(text) => parseDecimal(text, column)
          case JsNumber(number) => number.bigDecimal
          case _ => conversion(column)
        }
        val scaled = try decimal.setScale(column.scale, RoundingMode.UNNECESSARY)
        catch { case _: ArithmeticException => conversion(column) }
        if (column.size > 0 && scaled.precision() > column.size) conversion(column)
        ObjectValue(column.jdbcType, scaled)
      case Types.REAL =>
        val original = jsonNumber(value, column)
        val converted = original.toFloat
        if (!java.lang.Float.isFinite(converted) || BigDecimal.decimal(converted) != original) conversion(column)
        ObjectValue(column.jdbcType, Float.box(converted))
      case Types.FLOAT | Types.DOUBLE =>
        val original = jsonNumber(value, column)
        val converted = original.toDouble
        if (!java.lang.Double.isFinite(converted) || BigDecimal.decimal(converted) != original) conversion(column)
        ObjectValue(column.jdbcType, Double.box(converted))
      case Types.CHAR | Types.VARCHAR | Types.LONGVARCHAR | Types.NCHAR | Types.NVARCHAR | Types.LONGNVARCHAR =>
        val text = value match { case JsString(v) => v; case _ => conversion(column) }
        if (column.size > 0 && text.length > column.size) conversion(column)
        ObjectValue(column.jdbcType, text)
      case Types.DATE =>
        val text = string(value, column)
        ObjectValue(column.jdbcType, java.sql.Date.valueOf(parseDate(text, column)))
      case Types.TIME =>
        val text = string(value, column)
        val time = parseTime(text, column)
        requireFractionalPrecision(time.getNano, column)
        ObjectValue(column.jdbcType, time)
      case Types.TIME_WITH_TIMEZONE =>
        val text = string(value, column)
        val time = parseOffsetTime(text, column)
        requireFractionalPrecision(time.getNano, column)
        ObjectValue(column.jdbcType, time)
      case Types.TIMESTAMP =>
        val text = string(value, column)
        parseTimestamp(text, column)
      case Types.TIMESTAMP_WITH_TIMEZONE =>
        val text = string(value, column)
        ObjectValue(column.jdbcType, parseOffsetTimestamp(text, column))
      case Types.OTHER if column.typeName.equalsIgnoreCase("JSON") =>
        ObjectValue(column.jdbcType, string(value, column))
      case _ => throw new IllegalStateException(s"unsupported target column type ${column.name}: ${column.typeName}")
    }
  }

  private def exactLong(value: JsValue, column: TargetColumn): Long = value match {
    case JsNumber(number) if number.isWhole && number.isValidLong => number.toLong
    case _ => conversion(column)
  }

  private def jsonNumber(value: JsValue, column: TargetColumn): BigDecimal = value match {
    case JsNumber(number) => number
    case _ => conversion(column)
  }

  private def parseDecimal(value: String, column: TargetColumn): java.math.BigDecimal =
    try new java.math.BigDecimal(value) catch { case _: NumberFormatException => conversion(column) }

  private def string(value: JsValue, column: TargetColumn): String = value match {
    case JsString(text) => text
    case _ => conversion(column)
  }

  private def parseDate(value: String, column: TargetColumn): LocalDate =
    try LocalDate.parse(value) catch { case NonFatal(_) => conversion(column) }

  private def parseTime(value: String, column: TargetColumn): LocalTime =
    try LocalTime.parse(value) catch { case NonFatal(_) => conversion(column) }

  private def parseOffsetTime(value: String, column: TargetColumn): OffsetTime =
    try OffsetTime.parse(value) catch { case NonFatal(_) => conversion(column) }

  private def parseTimestamp(value: String, column: TargetColumn): BoundValue = {
    try InstantValue(Instant.parse(value))
    catch {
      case NonFatal(_) =>
        try ObjectValue(column.jdbcType, Timestamp.valueOf(LocalDateTime.parse(value)))
        catch { case NonFatal(_) => conversion(column) }
    }
  }

  private def parseOffsetTimestamp(value: String, column: TargetColumn): OffsetDateTime =
    try OffsetDateTime.parse(value) catch { case NonFatal(_) => conversion(column) }

  private def requireFractionalPrecision(nanos: Int, column: TargetColumn): Unit = {
    if (column.scale < 0 || column.scale > 9) {
      throw new IllegalStateException(s"target column ${column.name} has unsupported fractional precision ${column.scale}")
    }
    val quantum = TenPowers(9 - column.scale)
    if (nanos % quantum != 0) conversion(column)
  }

  private def conversion(column: TargetColumn): Nothing =
    throw new IllegalStateException(s"CDC value cannot be converted losslessly for target column ${column.name}")

  private def set(statement: PreparedStatement, index: Int, value: BoundValue): Unit = value match {
    case NullValue(jdbcType) => statement.setNull(index, jdbcType)
    case IntegralValue(Types.TINYINT, v: Byte) => statement.setByte(index, v)
    case IntegralValue(Types.SMALLINT, v: Short) => statement.setShort(index, v)
    case IntegralValue(Types.INTEGER, v: Int) => statement.setInt(index, v)
    case IntegralValue(Types.BIGINT, v: Long) => statement.setLong(index, v)
    case InstantValue(v) => statement.setTimestamp(index, Timestamp.from(v), Calendar.getInstance(UtcTimeZone))
    case ObjectValue(jdbcType, v) => statement.setObject(index, v, jdbcType)
    case other => statement.setObject(index, other.value, other.jdbcType)
  }

  private def validateLedger(connection: Connection): Unit = using(connection.prepareStatement(
    s"SELECT batch_id, workflow_id, execution_id, source_node_id, partition_id, batch_sequence, " +
      s"cursor_kind, cursor_value, upper_bound, source_rows, target_rows, committed_at FROM $LedgerTable WHERE 1 = 0"
  ))(_.executeQuery().close())

  private def findLedger(connection: Connection, batchId: String): Option[LedgerRecord] = using(
    connection.prepareStatement(
      s"SELECT workflow_id, execution_id, source_node_id, partition_id, batch_sequence, cursor_kind, " +
        s"cursor_value, upper_bound, source_rows, target_rows FROM $LedgerTable WHERE batch_id = ?"
    )
  ) { statement =>
    statement.setString(1, batchId)
    using(statement.executeQuery()) { rows =>
      Option.when(rows.next()) {
        LedgerRecord(
          batchId,
          rows.getString("workflow_id"),
          rows.getString("execution_id"),
          BatchCheckpoint(
            rows.getString("source_node_id"),
            rows.getString("partition_id"),
            rows.getLong("batch_sequence"),
            batchId,
            cn.xuyinyin.magic.workflow.checkpoint.SourceCursor(
              rows.getString("cursor_kind"),
              rows.getString("cursor_value"),
              rows.getString("upper_bound")
            ),
            rows.getLong("source_rows"),
            rows.getLong("target_rows")
          )
        )
      }
    }
  }

  private def insertLedger(connection: Connection, record: LedgerRecord): Unit = using(
    connection.prepareStatement(
      s"INSERT INTO $LedgerTable (batch_id, workflow_id, execution_id, source_node_id, partition_id, " +
        s"batch_sequence, cursor_kind, cursor_value, upper_bound, source_rows, target_rows) " +
        s"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    )
  ) { statement =>
    val checkpoint = record.checkpoint
    statement.setString(1, record.batchId)
    statement.setString(2, record.workflowId)
    statement.setString(3, record.executionId)
    statement.setString(4, checkpoint.sourceNodeId)
    statement.setString(5, checkpoint.partitionId)
    statement.setLong(6, checkpoint.batchSequence)
    statement.setString(7, checkpoint.cursor.kind)
    statement.setString(8, checkpoint.cursor.value)
    statement.setString(9, checkpoint.cursor.upperBound)
    statement.setLong(10, checkpoint.sourceRowsScanned)
    statement.setLong(11, checkpoint.targetRowsWritten)
    statement.executeUpdate()
  }

  private def parseBatchIdentity(batch: SourceBatch): StreamIdentity = {
    if (!batch.partitionId.startsWith("mysql-cdc:") || batch.cursor.kind != "mysql.binlog.v1") {
      throw new IllegalStateException("CDC batch partition or cursor kind is unsupported")
    }
    if (batch.cursor.upperBound.isEmpty) throw new IllegalStateException("CDC batch stream identity is missing")
    val identity = parseIdentity(batch.cursor.upperBound)
    if (batch.partitionId != s"mysql-cdc:${identity.connectorId}") {
      throw new IllegalStateException("CDC batch partition does not match connector")
    }
    identity
  }

  private def parseBoundary(boundary: SnapshotBoundary): StreamIdentity = {
    if (!boundary.partitionId.startsWith("mysql-cdc:")) {
      throw new IllegalStateException("CDC boundary partition is unsupported")
    }
    val identity = parseIdentity(boundary.upperBound.getOrElse(
      throw new IllegalStateException("CDC boundary stream identity is missing")
    ))
    if (boundary.partitionId != s"mysql-cdc:${identity.connectorId}") {
      throw new IllegalStateException("CDC boundary partition does not match connector")
    }
    identity
  }

  private def parseIdentity(json: String): StreamIdentity = {
    val fields = try json.parseJson.asJsObject.fields
    catch { case NonFatal(_) => throw new IllegalStateException("CDC stream identity is malformed") }
    val expected = Set("version", "connectorId", "database", "table", "primaryKey", "columns", "schemaFingerprint")
    if (fields.keySet != expected || fields.get("version") != Some(JsNumber(1))) {
      throw new IllegalStateException("CDC stream identity version or fields are invalid")
    }
    def required(name: String): String = fields.get(name) match {
      case Some(JsString(value)) if value.nonEmpty => value
      case _ => throw new IllegalStateException(s"CDC stream identity $name is invalid")
    }
    val columns = fields("columns") match {
      case JsArray(values) if values.nonEmpty => values.map {
        case JsObject(column) if column.keySet == Set("name", "jdbcType", "typeName") =>
          val name = column.get("name") match {
            case Some(JsString(value)) => requireIdentifier(value, "source column")
            case _ => throw new IllegalStateException("CDC stream identity column name is invalid")
          }
          val jdbcType = column.get("jdbcType") match {
            case Some(JsNumber(value)) if value.isValidInt && SupportedSourceTypes.contains(value.toInt) => value.toInt
            case _ => throw new IllegalStateException("CDC stream identity column type is invalid")
          }
          val typeName = column.get("typeName") match {
            case Some(JsString(value)) if value.nonEmpty => value
            case _ => throw new IllegalStateException("CDC stream identity column type name is invalid")
          }
          if (IntegralTypes.contains(jdbcType) && typeName.toUpperCase(Locale.ROOT).contains("UNSIGNED")) {
            throw new IllegalStateException(s"unsupported source UNSIGNED integral type for $name: $typeName")
          }
          SourceColumn(name, jdbcType, typeName)
        case _ => throw new IllegalStateException("CDC stream identity columns are invalid")
      }.toVector
      case _ => throw new IllegalStateException("CDC stream identity columns are missing")
    }
    if (columns.map(_.lowerName).distinct.size != columns.size) {
      throw new IllegalStateException("CDC stream identity column names are ambiguous")
    }
    val primaryKey = requireIdentifier(required("primaryKey"), "source primary key")
    if (!columns.exists(_.name.equalsIgnoreCase(primaryKey))) {
      throw new IllegalStateException("CDC stream identity primary key is not a source column")
    }
    val fingerprint = required("schemaFingerprint")
    if (!fingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalStateException("CDC stream identity schema fingerprint is invalid")
    }
    StreamIdentity(
      required("connectorId"),
      requireIdentifier(required("database"), "source database"),
      requireIdentifier(required("table"), "source table"),
      primaryKey,
      columns,
      fingerprint
    )
  }

  private def parseEnvelope(json: String): MySQLCdcEnvelope = MySQLCdcEnvelope.parse(json) match {
    case Right(envelope) => envelope
    case Left(failure) => throw new IllegalStateException(s"invalid CDC envelope: ${failure.message}")
  }

  private def exactField(value: JsObject, name: String, path: String): JsValue = {
    val matches = value.fields.collect { case (field, fieldValue) if field.equalsIgnoreCase(name) => fieldValue }.toVector
    if (matches.size != 1) throw new IllegalStateException(s"CDC $path must contain exactly one $name field")
    matches.head
  }

  private def conflictingLedger(batchId: String): IllegalStateException =
    new IllegalStateException(s"conflicting MySQL CDC ledger identity for batch $batchId")

  private def isDuplicateKey(error: SQLException): Boolean =
    error.getErrorCode == 1062 || Option(error.getSQLState).exists(state => state == "23505" || state == "23000")

  private def supportedTarget(column: TargetColumn): Boolean = column.jdbcType match {
    case Types.BOOLEAN | Types.BIT | Types.TINYINT | Types.SMALLINT | Types.INTEGER | Types.BIGINT |
         Types.NUMERIC | Types.DECIMAL | Types.REAL | Types.FLOAT | Types.DOUBLE |
         Types.CHAR | Types.VARCHAR | Types.LONGVARCHAR | Types.NCHAR | Types.NVARCHAR | Types.LONGNVARCHAR |
         Types.DATE | Types.TIME | Types.TIME_WITH_TIMEZONE | Types.TIMESTAMP | Types.TIMESTAMP_WITH_TIMEZONE => true
    case Types.OTHER if column.typeName.equalsIgnoreCase("JSON") => true
    case _ => false
  }

  private def compatibleType(source: SourceColumn, target: TargetColumn): Boolean = {
    (IntegralRanks.get(source.jdbcType), IntegralRanks.get(target.jdbcType)) match {
      case (Some(sourceRank), Some(targetRank)) => targetRank >= sourceRank
      case (Some(_), None) | (None, Some(_)) => false
      case (None, None) if BooleanTypes.contains(source.jdbcType) => BooleanTypes.contains(target.jdbcType)
      case (None, None) if DecimalTypes.contains(source.jdbcType) => DecimalTypes.contains(target.jdbcType)
      case (None, None) => source.jdbcType == target.jdbcType
    }
  }

  private def matches(rows: ResultSet, catalog: String, schema: Option[String], table: String): Boolean =
    Option(rows.getString("TABLE_CAT")).forall(_.equalsIgnoreCase(catalog)) &&
      schema.forall(expected => Option(rows.getString("TABLE_SCHEM")).exists(_.equalsIgnoreCase(expected))) &&
      Option(rows.getString("TABLE_NAME")).exists(_.equalsIgnoreCase(table))

  private def metadataIdentifier(metadata: DatabaseMetaData, value: String): String =
    if (metadata.storesUpperCaseIdentifiers) value.toUpperCase(Locale.ROOT)
    else if (metadata.storesLowerCaseIdentifiers) value.toLowerCase(Locale.ROOT)
    else value

  private def escapePattern(metadata: DatabaseMetaData, value: String): String = {
    val escape = Option(metadata.getSearchStringEscape).getOrElse("")
    if (escape.isEmpty) value
    else value.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%")
  }

  private def requiredMetadata(rows: ResultSet, name: String): String =
    Option(rows.getString(name)).filter(_.nonEmpty).getOrElse(
      throw new IllegalStateException(s"JDBC target metadata $name is missing")
    )

  private def requireIdentifier(value: String, label: String): String = {
    if (value.matches("[A-Za-z_][A-Za-z0-9_]*")) value
    else throw new IllegalStateException(s"$label must be a valid identifier")
  }

  private def rollback(connection: Connection): Unit =
    if (connection != null) try connection.rollback() catch { case NonFatal(_) => () }

  private def close(connection: Connection): Unit =
    if (connection != null) try connection.close() catch { case NonFatal(_) => () }

  private def safeLog(onLog: String => Unit, message: String): Unit =
    try onLog(message) catch { case NonFatal(_) => () }

  private def using[A <: AutoCloseable, B](resource: A)(body: A => B): B =
    try body(resource) finally if (resource != null) resource.close()
}

object MySQLCdcApplySinkNode {
  private val UtcTimeZone = TimeZone.getTimeZone("UTC")

  private[sinks] def jdbcUrl(config: MySQLCdcApplyConfig): String =
    s"jdbc:mysql://${config.host}:${config.port}/${config.database}" +
      "?useSSL=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"

  private val LedgerTable = "pekko_sync_batch_ledger"
  private val IntegralRanks = Map(Types.TINYINT -> 1, Types.SMALLINT -> 2, Types.INTEGER -> 3, Types.BIGINT -> 4)
  private val IntegralTypes = IntegralRanks.keySet
  private val BooleanTypes = Set(Types.BOOLEAN, Types.BIT)
  private val DecimalTypes = Set(Types.NUMERIC, Types.DECIMAL)
  private val SupportedSourceTypes = BooleanTypes ++ IntegralTypes ++ DecimalTypes ++ Set(
    Types.REAL,
    Types.FLOAT,
    Types.DOUBLE,
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
  private val TenPowers = Array(1, 10, 100, 1000, 10000, 100000, 1000000, 10000000, 100000000, 1000000000)

  private final case class SourceColumn(name: String, jdbcType: Int, typeName: String) {
    val lowerName: String = name.toLowerCase(Locale.ROOT)
  }

  private final case class StreamIdentity(
    connectorId: String,
    database: String,
    table: String,
    primaryKey: String,
    columns: Vector[SourceColumn],
    schemaFingerprint: String
  )

  private final case class TargetColumn(
    name: String,
    jdbcType: Int,
    typeName: String,
    nullable: Boolean,
    defaultValue: Option[String],
    ordinal: Int,
    size: Int,
    scale: Int
  ) {
    val lowerName: String = name.toLowerCase(Locale.ROOT)
  }

  private final case class TargetTable(
    name: String,
    columns: Vector[TargetColumn],
    primaryKey: TargetColumn,
    quote: String
  ) {
    val byLowerName: Map[String, TargetColumn] = columns.map(column => column.lowerName -> column).toMap
    def quoted(identifier: String): String = s"$quote$identifier$quote"
  }

  private sealed trait BoundValue { def jdbcType: Int; def value: Any }
  private final case class NullValue(jdbcType: Int) extends BoundValue { val value: Any = null }
  private final case class IntegralValue(jdbcType: Int, value: Any) extends BoundValue
  private final case class InstantValue(value: Instant) extends BoundValue { val jdbcType: Int = Types.TIMESTAMP }
  private final case class ObjectValue(jdbcType: Int, value: Any) extends BoundValue

  private sealed trait PlannedEvent
  private final case class Upsert(values: Vector[(TargetColumn, BoundValue)]) extends PlannedEvent
  private final case class Delete(key: BoundValue) extends PlannedEvent

  private final case class LedgerRecord(
    batchId: String,
    workflowId: String,
    executionId: String,
    checkpoint: BatchCheckpoint
  )

}
