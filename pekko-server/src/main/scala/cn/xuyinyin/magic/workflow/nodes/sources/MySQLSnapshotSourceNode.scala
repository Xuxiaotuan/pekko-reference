package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.workflow.checkpoint.{BatchCheckpoint, BatchId, SnapshotBoundary, SourceBatch, SourceCursor}
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base.{CheckpointedNodeSource, NodeSource}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.stream.scaladsl.Source
import spray.json._

import java.sql.{Connection, DatabaseMetaData, ResultSet, Types}
import scala.concurrent.{ExecutionContext, Future}

class MySQLSnapshotSourceNode extends NodeSource with CheckpointedNodeSource {
  import MySQLSnapshotSourceNode._

  override val nodeType: String = "mysql.snapshot"

  override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] =
    Source.failed(new UnsupportedOperationException("mysql.snapshot requires checkpoint-aware execution"))

  override def discoverBoundary(node: WorkflowDSL.Node, onLog: String => Unit)
    (implicit blockingEc: ExecutionContext): Future[SnapshotBoundary] = {
    val config = MySQLSnapshotSourceConfig.parse(node)
    Future {
      var dataSource: HikariDataSource = null
      var connection: Connection = null
      try {
        dataSource = createDataSource(config.host, config.port, config.database, config.username, config.password)
        connection = dataSource.getConnection
        val projection = resolveProjection(connection, config)
        val upperBound = maxPrimaryKey(connection, projection)
        SnapshotBoundary(node.id, PartitionId, upperBound)
      } finally {
        close(connection)
        close(dataSource)
      }
    }
  }

  override def createBatches(
    node: WorkflowDSL.Node,
    executionId: String,
    boundary: SnapshotBoundary,
    resumeFrom: Option[BatchCheckpoint],
    onLog: String => Unit
  )(implicit blockingEc: ExecutionContext): Source[SourceBatch, NotUsed] = {
    val config = MySQLSnapshotSourceConfig.parse(node)
    validateBoundary(node, boundary, resumeFrom)

    boundary.upperBound match {
      case None => Source.empty
      case Some(upperBound) =>
        val initialCursor = resumeFrom.map(_.cursor.value)
        val initialSequence = resumeFrom.map(_.batchSequence + 1L).getOrElse(0L)
        Source.unfoldResourceAsync[SourceBatch, BatchReader](
          create = () => Future(openBatchReader(config, upperBound, initialCursor, initialSequence))(blockingEc),
          read = reader => Future(reader.nextBatch(node.id, executionId))(blockingEc),
          close = reader => Future {
            reader.close()
            Done
          }(blockingEc)
        )
    }
  }

  protected[sources] def createDataSource(
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
    sequence: Long
  ): BatchReader = {
    var dataSource: HikariDataSource = null
    var connection: Connection = null
    try {
      dataSource = createDataSource(config.host, config.port, config.database, config.username, config.password)
      connection = dataSource.getConnection
      val projection = resolveProjection(connection, config)
      new BatchReader(config, projection, dataSource, connection, upperBound, cursor, sequence)
    } catch {
      case error: Throwable =>
        close(connection)
        close(dataSource)
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

  private def maxPrimaryKey(connection: Connection, projection: ResolvedProjection): Option[String] = {
    val statement = connection.prepareStatement(s"SELECT MAX(${quote(projection.primaryKey)}) FROM ${quote(projection.table)}")
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
          while (resultSet.next()) {
            rows += rowAsJson(resultSet)
            lastCursor = Some(canonicalDecimal(resultSet.getObject(projection.primaryKeyIndex)))
          }
          val batchRows = rows.result()
          lastCursor.map { nextCursor =>
            val batch = SourceBatch(
              sourceNodeId = sourceNodeId,
              partitionId = PartitionId,
              batchSequence = sequence,
              batchId = BatchId.sha256(executionId, sourceNodeId, PartitionId, sequence),
              cursor = SourceCursor("mysql.numeric-pk", nextCursor, upperBound),
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
      close(connection)
      close(dataSource)
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
