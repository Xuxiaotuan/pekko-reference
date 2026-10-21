package cn.xuyinyin.magic.connectors.mysql.source

import cn.xuyinyin.magic.workflow.checkpoint.{BatchCheckpoint, BatchId, SnapshotBoundary, SourceBatch, SourceCursor}
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.spi.{BatchSourceSession, CheckpointedNodeSource, NodeSource, SourceAcquisitionCleanupFailed}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.stream.scaladsl.Source
import spray.json._

import java.sql.{Connection, DatabaseMetaData, ResultSet, Types}
import scala.concurrent.{ExecutionContext, Future}

class MySQLSnapshotSourceNode extends NodeSource with CheckpointedNodeSource with cn.xuyinyin.magic.workflow.spi.SnapshotSplitSource {
  import MySQLSnapshotSourceNode._

  override val nodeType: String = "mysql.snapshot"

  override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] =
    Source.failed(new UnsupportedOperationException("mysql.snapshot requires checkpoint-aware execution"))

  protected[source] def getenv(name: String): Option[String] = sys.env.get(name)

  override def discoverBoundary(
    node: WorkflowDSL.Node,
    resumeFrom: Option[BatchCheckpoint],
    onLog: String => Unit
  )(implicit blockingEc: ExecutionContext): Future[SnapshotBoundary] = {
    val config = MySQLSnapshotSourceConfig.parse(node, getenv)
    Future {
      var dataSource: HikariDataSource = null
      var connection: Connection = null
      try {
        dataSource = if (node.config.fields.contains("splitRows"))
          createSplitDataSource(config.host, config.port, config.database, config.username, config.password)
        else createDataSource(config.host, config.port, config.database, config.username, config.password)
        connection = dataSource.getConnection
        val projection = resolveProjection(connection, config)
        val upperBound = maxPrimaryKey(connection, projection, node.config.fields.contains("splitRows"))
        SnapshotBoundary(node.id, PartitionId, upperBound)
      } finally {
        if (node.config.fields.contains("splitRows")) closeSplitMetadata(connection, dataSource)
        else { close(connection); close(dataSource) }
      }
    }
  }

  override def createBatches(
    node: WorkflowDSL.Node,
    executionId: String,
    boundary: SnapshotBoundary,
    resumeFrom: Option[BatchCheckpoint],
    onLog: String => Unit
  )(implicit blockingEc: ExecutionContext): Source[SourceBatch, NotUsed] =
    batchSession(node, executionId, boundary, resumeFrom, onLog, blockingEc, managed = false).source

  override def openBatches(node: WorkflowDSL.Node, executionId: String, boundary: SnapshotBoundary,
    resumeFrom: Option[BatchCheckpoint], onLog: String => Unit, closeEc: ExecutionContext)
    (implicit blockingEc: ExecutionContext): BatchSourceSession =
    batchSession(node, executionId, boundary, resumeFrom, onLog, closeEc, managed = true)

  private def batchSession(node: WorkflowDSL.Node, executionId: String, boundary: SnapshotBoundary,
    resumeFrom: Option[BatchCheckpoint], onLog: String => Unit, closeEc: ExecutionContext, managed: Boolean)
    (implicit blockingEc: ExecutionContext): BatchSourceSession = {
    val config = MySQLSnapshotSourceConfig.parse(node, getenv)
    validateBoundary(node, boundary, resumeFrom)

    boundary.upperBound match {
      case None => BatchSourceSession(Source.empty, () => Future.successful(Done))
      case Some(upperBound) =>
        val initialCursor = resumeFrom.map(_.cursor.value)
        val initialSequence = resumeFrom.map(_.batchSequence + 1L).getOrElse(0L)
        BatchSourceSession.resource[BatchReader](
          create = () => Future(openBatchReader(config, upperBound, initialCursor, initialSequence))(blockingEc),
          read = reader => Future(reader.nextBatch(node.id, executionId))(blockingEc),
          closeResource = reader => Future {
            reader.close()
            Done
          }(closeEc),
          managed = managed,
          drainReads = true
        )
    }
  }

  override def discoverSplit(node: WorkflowDSL.Node, plan: cn.xuyinyin.magic.workflow.snapshot.SnapshotSplitPlanState,
    lower: Option[String], index: Long)(implicit ec: ExecutionContext): Future[Option[cn.xuyinyin.magic.workflow.snapshot.SnapshotSplit]] = Future {
    val config = MySQLSnapshotSourceConfig.parse(node, getenv)
    plan.upper.flatMap { upper =>
      val ds = createSplitDataSource(config.host, config.port, config.database, config.username, config.password)
      var connection: Connection = null
      try {
        connection = ds.getConnection
        val projection = resolveProjection(connection, config)
        val predicate = lower.fold("")(_ => s"${quote(projection.primaryKey)} > ? AND ")
        val sql = s"SELECT MAX(chunk_key) FROM (SELECT ${quote(projection.primaryKey)} AS chunk_key FROM ${quote(projection.table)} " +
          s"WHERE $predicate${quote(projection.primaryKey)} <= ? ORDER BY ${quote(projection.primaryKey)} LIMIT ?) AS chunk_keys"
        val statement = connection.prepareStatement(sql)
        statement.setQueryTimeout(30)
        try {
          var p = 1
          lower.foreach { key => statement.setBigDecimal(p, new java.math.BigDecimal(key)); p += 1 }
          statement.setBigDecimal(p, new java.math.BigDecimal(upper)); statement.setInt(p + 1, plan.splitRows)
          val rs = statement.executeQuery()
          try { rs.next(); Option(rs.getObject(1)).map(v => cn.xuyinyin.magic.workflow.snapshot.SnapshotSplit(
            s"${plan.id}:$index", index, lower, canonicalDecimal(v))) } finally rs.close()
        } finally statement.close()
      } finally closeSplitMetadata(connection, ds)
    }
  }

  override def openSplit(node: WorkflowDSL.Node, executionId: String, plan: cn.xuyinyin.magic.workflow.snapshot.SnapshotSplitPlanState,
    split: cn.xuyinyin.magic.workflow.snapshot.SnapshotSplit, checkpoint: Option[BatchCheckpoint], onLog: String => Unit,
    closeEc: ExecutionContext)(implicit ec: ExecutionContext): BatchSourceSession = {
    require(plan.sourceNodeId == node.id && split.id == s"${plan.id}:${split.index}", "snapshot split identity mismatch")
    checkpoint.foreach { c => require(c.partitionId == split.id && c.sourceNodeId == node.id &&
      c.cursor.upperBound == split.upper && c.cursor.kind == cn.xuyinyin.magic.workflow.snapshot.SnapshotSplitState.CursorKind,
      "snapshot split checkpoint mismatch") }
    val config = MySQLSnapshotSourceConfig.parse(node, getenv).copy(chunkSize = plan.pageRows)
    BatchSourceSession.resource[BatchReader](
      () => Future {
        val reader = openBatchReader(config, split.upper, checkpoint.map(_.cursor.value).orElse(split.lower),
          checkpoint.fold(0L)(_.batchSequence + 1L), splitMode = true)
        reader.partition = split.id
        reader.cursorKind = cn.xuyinyin.magic.workflow.snapshot.SnapshotSplitState.CursorKind
        reader.maxPageBytes = plan.maxPageBytes
        reader
      }(ec),
      r => Future(r.nextBatch(node.id, executionId))(ec),
      r => Future { r.close(); Done }(closeEc), managed = false, drainReads = true)
  }

  private def closeSplitMetadata(resources: AutoCloseable*): Unit = {
    var failure: Throwable = null
    resources.filter(_ != null).foreach { resource =>
      try resource.close()
      catch { case scala.util.control.NonFatal(error) =>
        if (failure == null) failure = error else failure.addSuppressed(error)
      }
    }
    if (failure != null) throw new cn.xuyinyin.magic.workflow.engine.CdcExecutionException(
      cn.xuyinyin.magic.workflow.engine.ExecutionFailure("local_release_unconfirmed", "snapshot-metadata-close", releaseConfirmed = false), failure)
  }

  protected[source] def createSplitDataSource(host: String, port: Int, database: String, username: String, password: String): HikariDataSource = {
    val config = new HikariConfig()
    config.setJdbcUrl(s"jdbc:mysql://$host:$port/$database?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useCursorFetch=true&socketTimeout=30000&connectTimeout=5000")
    config.setUsername(username); config.setPassword(password); config.setDriverClassName("com.mysql.cj.jdbc.Driver")
    config.setMaximumPoolSize(1); config.setMinimumIdle(0); config.setConnectionTimeout(10000)
    new HikariDataSource(config)
  }

  protected[source] def createDataSource(
    host: String,
    port: Int,
    database: String,
    username: String,
    password: String
  ): HikariDataSource = {
    val config = new HikariConfig()
    config.setJdbcUrl(s"jdbc:mysql://$host:$port/$database?useSSL=false&serverTimezone=UTC")
    config.setUsername(username)
    config.setPassword(password)
    config.setDriverClassName("com.mysql.cj.jdbc.Driver")
    config.setMaximumPoolSize(1)
    config.setMinimumIdle(0)
    config.setConnectionTimeout(30000)
    config.setConnectionTestQuery("SELECT 1")
    new HikariDataSource(config)
  }

  private def openBatchReader(
    config: MySQLSnapshotSourceConfig,
    upperBound: String,
    cursor: Option[String],
    sequence: Long,
    splitMode: Boolean = false
  ): BatchReader = {
    var dataSource: HikariDataSource = null
    var connection: Connection = null
    try {
      dataSource = if (splitMode) createSplitDataSource(config.host, config.port, config.database, config.username, config.password)
        else createDataSource(config.host, config.port, config.database, config.username, config.password)
      connection = dataSource.getConnection
      val projection = resolveProjection(connection, config)
      new BatchReader(config, projection, dataSource, connection, upperBound, cursor, sequence)
    } catch {
      case error: Throwable =>
        var cleanup: Exception = null
        Vector[AutoCloseable](connection, dataSource).filter(_ != null).foreach { resource =>
          try resource.close()
          catch { case e: Exception => if (cleanup == null) cleanup = e else cleanup.addSuppressed(e) }
        }
        if (cleanup != null) throw new SourceAcquisitionCleanupFailed(error, cleanup)
        throw error
    }
  }

  private def validateBoundary(
    node: WorkflowDSL.Node,
    boundary: SnapshotBoundary,
    resumeFrom: Option[BatchCheckpoint]
  ): Unit = {
    require(boundary.sourceNodeId == node.id, "snapshot boundary source does not match node")
    require(boundary.partitionId == PartitionId, "snapshot boundary partition is unsupported")
    resumeFrom.foreach { checkpoint =>
      require(checkpoint.sourceNodeId == node.id, "checkpoint source does not match node")
      require(checkpoint.partitionId == PartitionId, "checkpoint partition is unsupported")
      require(boundary.upperBound.contains(checkpoint.cursor.upperBound), "checkpoint upper bound does not match snapshot boundary")
    }
  }

  private def resolveProjection(connection: Connection, config: MySQLSnapshotSourceConfig): ResolvedProjection = {
    val metadata = connection.getMetaData
    val catalog = Option(connection.getCatalog).filter(_.nonEmpty)
      .getOrElse(throw new IllegalArgumentException("snapshot source connection has no current catalog"))
    val schema = Option(connection.getSchema).filter(_.nonEmpty)
    val table = metadataIdentifier(metadata, config.table)
    val primaryKeys = primaryKeyColumns(metadata, catalog, schema, table)
    if (primaryKeys.size != 1) {
      throw new IllegalArgumentException("snapshot source requires exactly one primary key column")
    }
    if (!primaryKeys.head.equalsIgnoreCase(config.primaryKey)) {
      throw new IllegalArgumentException("configured primaryKey does not match table primary key")
    }
    if (!config.columns.exists(_.equalsIgnoreCase(config.primaryKey))) {
      throw new IllegalArgumentException("columns must include primaryKey")
    }

    val availableColumns = columnMetadata(metadata, catalog, schema, table)
    val resolvedColumns = config.columns.map { configured =>
      availableColumns.filter(_.name.equalsIgnoreCase(configured)) match {
        case Vector(column) => column
        case Vector() => throw new IllegalArgumentException(s"configured column metadata was not found: $configured")
        case _ => throw new IllegalArgumentException(s"configured column metadata is ambiguous: $configured")
      }
    }
    val primaryKey = primaryKeys.head
    val column = availableColumns.find(_.name == primaryKey)
      .getOrElse(throw new IllegalArgumentException("configured primaryKey column metadata was not found"))

    if (column.nullable != DatabaseMetaData.columnNoNulls) {
      throw new IllegalArgumentException("snapshot primary key must be non-null")
    }
    if (!NumericTypes.contains(column.jdbcType)) {
      throw new IllegalArgumentException("snapshot primary key must be numeric")
    }

    ResolvedProjection(
      table = table,
      columns = resolvedColumns.map(_.name),
      primaryKey = primaryKey,
      primaryKeyIndex = resolvedColumns.indexWhere(_.name == primaryKey) + 1
    )
  }

  private def primaryKeyColumns(
    metadata: DatabaseMetaData,
    catalog: String,
    schema: Option[String],
    table: String
  ): Vector[String] =
    // JDBC specifies the getPrimaryKeys table argument as a table name, not a pattern.
    using(metadata.getPrimaryKeys(catalog, schema.orNull, table)) { resultSet =>
      Iterator.continually(resultSet.next()).takeWhile(identity)
        .filter(_ => matchesMetadataRow(resultSet, catalog, schema, table, None))
        .map(_ => resultSet.getString("COLUMN_NAME"))
        .toVector
    }

  private def columnMetadata(
    metadata: DatabaseMetaData,
    catalog: String,
    schema: Option[String],
    table: String
  ): Vector[ColumnMetadata] =
    using(metadata.getColumns(
      catalog,
      schema.orNull,
      escapeMetadataPattern(metadata, table),
      null
    )) { resultSet =>
      Iterator.continually(resultSet.next()).takeWhile(identity)
        .filter(_ => matchesMetadataRow(resultSet, catalog, schema, table, None))
        .map(_ => ColumnMetadata(
          resultSet.getString("COLUMN_NAME"),
          resultSet.getInt("DATA_TYPE"),
          resultSet.getInt("NULLABLE")
        ))
        .toVector
    }

  private def matchesMetadataRow(
    resultSet: ResultSet,
    catalog: String,
    schema: Option[String],
    table: String,
    column: Option[String]
  ): Boolean =
    resultSet.getString("TABLE_CAT") == catalog &&
      schema.forall(expected => resultSet.getString("TABLE_SCHEM") == expected) &&
      resultSet.getString("TABLE_NAME") == table &&
      column.forall(expected => resultSet.getString("COLUMN_NAME") == expected)

  private def metadataIdentifier(metadata: DatabaseMetaData, identifier: String): String =
    if (metadata.storesUpperCaseIdentifiers) identifier.toUpperCase
    else if (metadata.storesLowerCaseIdentifiers) identifier.toLowerCase
    else identifier

  private def escapeMetadataPattern(metadata: DatabaseMetaData, identifier: String): String = {
    val escape = metadata.getSearchStringEscape
    if (escape == null || escape.isEmpty) identifier
    else identifier
      .replace(escape, escape + escape)
      .replace("_", escape + "_")
      .replace("%", escape + "%")
  }

  private def maxPrimaryKey(connection: Connection, projection: ResolvedProjection, bounded: Boolean = false): Option[String] = {
    val statement = connection.prepareStatement(s"SELECT MAX(${quote(projection.primaryKey)}) FROM ${quote(projection.table)}")
    if (bounded) statement.setQueryTimeout(30)
    try {
      val resultSet = statement.executeQuery()
      try {
        resultSet.next()
        Option(resultSet.getObject(1)).map(canonicalDecimal)
      } finally close(resultSet)
    } finally close(statement)
  }

  private def quote(identifier: String): String = s"`$identifier`"

  private def canonicalDecimal(value: Any): String =
    BigDecimal(value.toString).bigDecimal.toPlainString

  private def using[A <: AutoCloseable, B](resource: A)(f: A => B): B =
    try f(resource)
    finally close(resource)

  private def close(resource: AutoCloseable): Unit =
    if (resource != null) {
      try resource.close()
      catch { case _: Exception => () }
    }
}

object MySQLSnapshotSourceNode {
  private val PartitionId = "pk-range-0"
  private val NumericTypes = Set(Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC, Types.DECIMAL)

  private final case class ColumnMetadata(name: String, jdbcType: Int, nullable: Int)
  private final case class ResolvedProjection(
    table: String,
    columns: Vector[String],
    primaryKey: String,
    primaryKeyIndex: Int
  )

  private final class BatchReader(
    config: MySQLSnapshotSourceConfig,
    projection: ResolvedProjection,
    dataSource: HikariDataSource,
    connection: Connection,
    upperBound: String,
    initialCursor: Option[String],
    initialSequence: Long
  ) extends AutoCloseable {
    var partition: String = PartitionId
    var cursorKind: String = "mysql.numeric-pk"
    var maxPageBytes: Int = Int.MaxValue
    private var cursor = initialCursor
    private var sequence = initialSequence

    def nextBatch(sourceNodeId: String, executionId: String): Option[SourceBatch] = {
      val (sql, hasCursor) = cursor match {
        case Some(_) =>
          (s"SELECT ${projection.columns.map(quote).mkString(", ")} FROM ${quote(projection.table)} " +
            s"WHERE ${quote(projection.primaryKey)} > ? AND ${quote(projection.primaryKey)} <= ? " +
            s"ORDER BY ${quote(projection.primaryKey)} ASC LIMIT ?", true)
        case None =>
          (s"SELECT ${projection.columns.map(quote).mkString(", ")} FROM ${quote(projection.table)} " +
            s"WHERE ${quote(projection.primaryKey)} <= ? ORDER BY ${quote(projection.primaryKey)} ASC LIMIT ?", false)
      }
      val statement = connection.prepareStatement(sql)
      if (cursorKind == cn.xuyinyin.magic.workflow.snapshot.SnapshotSplitState.CursorKind) {
        statement.setQueryTimeout(30)
        statement.setFetchSize(math.min(config.chunkSize, 128))
      }
      try {
        var parameter = 1
        if (hasCursor) {
          statement.setBigDecimal(parameter, new java.math.BigDecimal(cursor.get))
          parameter += 1
        }
        statement.setBigDecimal(parameter, new java.math.BigDecimal(upperBound))
        statement.setInt(parameter + 1, config.chunkSize)

        val resultSet = statement.executeQuery()
        try {
          val rows = Vector.newBuilder[String]
          var lastCursor: Option[String] = None
          var encodedBytes = 0L
          while (resultSet.next()) {
            val row = rowAsJson(resultSet)
            if (maxPageBytes != Int.MaxValue) {
              encodedBytes += row.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
              require(encodedBytes <= maxPageBytes, "snapshot page exceeds maxPageBytes; pagination must not change within an execution")
            }
            rows += row
            lastCursor = Some(canonicalDecimal(resultSet.getObject(projection.primaryKeyIndex)))
          }
          val batchRows = rows.result()
          lastCursor.map { nextCursor =>
            val batch = SourceBatch(
              sourceNodeId = sourceNodeId,
              partitionId = partition,
              batchSequence = sequence,
              batchId = BatchId.sha256(executionId, sourceNodeId, partition, sequence),
              cursor = SourceCursor(cursorKind, nextCursor, upperBound),
              rows = batchRows
            )
            cursor = Some(nextCursor)
            sequence += 1L
            batch
          }
        } finally close(resultSet)
      } finally close(statement)
    }

    override def close(): Unit = {
      var failure: Exception = null
      Vector[AutoCloseable](connection, dataSource).filter(_ != null).foreach { resource =>
        try resource.close()
        catch { case e: Exception => if (failure == null) failure = e else failure.addSuppressed(e) }
      }
      if (failure != null) throw failure
    }

    private def rowAsJson(resultSet: ResultSet): String = {
      val metadata = resultSet.getMetaData
      val fields = (1 to metadata.getColumnCount).map { index =>
        val value = resultSet.getObject(index)
        metadata.getColumnLabel(index) -> (if (value == null) JsNull else JsString(value.toString))
      }.toMap
      JsObject(fields).compactPrint
    }

    private def canonicalDecimal(value: Any): String =
      BigDecimal(value.toString).bigDecimal.toPlainString

    private def quote(identifier: String): String = s"`$identifier`"

    private def close(resource: AutoCloseable): Unit =
      if (resource != null) {
        try resource.close()
        catch { case _: Exception => () }
      }
  }
}
