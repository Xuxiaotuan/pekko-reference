package cn.xuyinyin.magic.workflow.nodes.sinks

import cn.xuyinyin.magic.workflow.checkpoint.{AlreadyCommitted, BatchCheckpoint, BatchCommitResult, Committed, SourceBatch}
import cn.xuyinyin.magic.workflow.nodes.base.{CheckpointedNodeSink, NodeSink}
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import spray.json._
import java.sql.{Connection, PreparedStatement, ResultSet, SQLException}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

/**
 * MySQL Sink实现
 * 
 * 实现pekko-server定义的NodeSink接口
 * 支持批量写入、INSERT/UPSERT模式
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-15
 */
class MySQLSinkNode extends NodeSink with CheckpointedNodeSink {

  private val LedgerTable = "pekko_sync_batch_ledger"

  private final case class SinkConfig(
    host: String,
    port: Int,
    database: String,
    table: String,
    username: String,
    password: String,
    batchSize: Int,
    mode: String
  )

  private final case class LedgerRecord(
    batchId: String,
    workflowId: String,
    executionId: String,
    checkpoint: BatchCheckpoint
  )
  
  override def nodeType: String = "mysql.write"
  
  override def createSink(node: WorkflowDSL.Node, onLog: String => Unit)
                        (implicit ec: ExecutionContext): Sink[String, Future[Done]] = {
    val config = parseConfig(node)

    onLog(s"[MySQL Sink] 连接MySQL: ${config.host}:${config.port}/${config.database}")
    onLog(s"[MySQL Sink] 写入表: ${config.table} (模式: ${config.mode}, 批量: ${config.batchSize})")
    
    Sink.lazyInitAsync[String, Future[Done]] { () =>
      var dataSource: HikariDataSource = null
      try {
        dataSource = createDataSource(config.host, config.port, config.database, config.username, config.password)
        Future.successful(createInnerSink(dataSource, config.table, config.batchSize, config.mode, onLog))
      } catch {
        case NonFatal(exception) =>
          if (dataSource != null) closeDataSource(dataSource)
          Future.failed(exception)
      }
    }.mapMaterializedValue(_.flatMap(_.getOrElse(Future.successful(Done))))
  }

  override def validateReady(
    node: WorkflowDSL.Node,
    onLog: String => Unit
  )(implicit blockingEc: ExecutionContext): Future[Done] = Future {
    val config = parseConfig(node)
    val dataSource = createDataSource(config.host, config.port, config.database, config.username, config.password)
    var connection: Connection = null
    var statement: PreparedStatement = null
    var resultSet: ResultSet = null
    try {
      connection = dataSource.getConnection
      statement = connection.prepareStatement(
        s"SELECT batch_id, workflow_id, execution_id, source_node_id, partition_id, batch_sequence, " +
          s"cursor_value, upper_bound, source_rows, target_rows, committed_at FROM $LedgerTable WHERE 1 = 0"
      )
      resultSet = statement.executeQuery()
      onLog(s"[MySQL Sink] 幂等账本已就绪: $LedgerTable")
      Done
    } catch {
      case NonFatal(exception) =>
        throw new IllegalStateException(s"MySQL sink idempotency ledger $LedgerTable is not ready", exception)
    } finally {
      closeResultSet(resultSet)
      closeStatement(statement)
      closeConnection(connection)
      closeDataSource(dataSource)
    }
  }

  override def commitBatch(
    node: WorkflowDSL.Node,
    workflowId: String,
    executionId: String,
    batch: SourceBatch,
    transformedRows: Vector[String],
    onLog: String => Unit
  )(implicit blockingEc: ExecutionContext): Future[BatchCommitResult] = Future {
    val config = parseConfig(node)
    val dataSource = createDataSource(config.host, config.port, config.database, config.username, config.password)
    try {
      val result = commitCheckpointedBatch(dataSource, config, workflowId, executionId, batch, transformedRows)
      try onLog(s"[MySQL Sink] 批次 ${batch.batchId} 已确认，目标行数: ${resultCheckpoint(result).targetRowsWritten}")
      catch { case NonFatal(_) => () }
      result
    } finally closeDataSource(dataSource)
  }

  protected[sinks] def beforeLedgerClaim(batchId: String): Unit = ()

  protected[sinks] def createInnerSink(
    dataSource: HikariDataSource,
    table: String,
    batchSize: Int,
    mode: String,
    onLog: String => Unit
  )(implicit ec: ExecutionContext): Sink[String, Future[Done]] =
    Flow[String].grouped(batchSize).toMat(Sink.foldAsync[Int, Seq[String]](0) { (count, rows) =>
      Future {
        val written = writeBatch(dataSource, table, rows, mode)
        val totalCount = count + written
        onLog(s"[MySQL Sink] 已写入 $totalCount 行到 $table")
        totalCount
      }
    })(Keep.right).mapMaterializedValue { future =>
      future.transform {
        case Success(totalCount) =>
          try {
            onLog(s"[MySQL Sink] 写入完成，总计: $totalCount 行")
            Success(Done)
          } finally closeDataSource(dataSource)
        case Failure(exception) =>
          closeDataSource(dataSource)
          Failure(exception)
      }
    }

  private def parseConfig(node: WorkflowDSL.Node): SinkConfig = {
    def getString(key: String, default: Option[String] = None): String =
      node.config.fields.get(key) match {
        case Some(JsString(value)) => value
        case None => default.getOrElse(throw new IllegalArgumentException(s"MySQL sink缺少${key}配置"))
        case _ => throw new IllegalArgumentException(s"${key}必须是字符串类型")
      }

    def getInt(key: String, default: Int): Int =
      node.config.fields.get(key) match {
        case Some(JsNumber(value)) => value.toInt
        case None => default
        case _ => throw new IllegalArgumentException(s"${key}必须是数字类型")
      }

    val config = SinkConfig(
      getString("host", Some("localhost")),
      getInt("port", 3306),
      getString("database"),
      getString("table"),
      getString("username"),
      getString("password"),
      getInt("batchSize", 1000),
      getString("mode", Some("insert"))
    )
    require(config.batchSize > 0, "MySQL sink的batchSize必须大于0")
    config
  }

  private def commitCheckpointedBatch(
    dataSource: HikariDataSource,
    config: SinkConfig,
    workflowId: String,
    executionId: String,
    batch: SourceBatch,
    transformedRows: Vector[String]
  ): BatchCommitResult = {
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
    var connection: Connection = null
    try {
      val records = parseRows(transformedRows)
      connection = dataSource.getConnection
      connection.setAutoCommit(false)
      findLedger(connection, batch.batchId) match {
        case Some(existing) =>
          connection.rollback()
          if (existing == expected) AlreadyCommitted(existing.checkpoint)
          else throw conflictingLedger(batch.batchId)
        case None =>
          beforeLedgerClaim(batch.batchId)
          val duplicateClaim =
            try {
              insertLedger(connection, expected)
              None
            } catch {
              case sql: SQLException if isDuplicateKey(sql) => Some(sql)
            }
          duplicateClaim match {
            case None =>
              writeRows(connection, config.table, records, config.mode)
              connection.commit()
              Committed(checkpoint)
            case Some(sql) =>
              connection.rollback()
              findLedger(connection, batch.batchId) match {
                case Some(existing) if existing == expected => AlreadyCommitted(existing.checkpoint)
                case Some(_) => throw conflictingLedger(batch.batchId)
                case None => throw new IllegalStateException("batch write failed", sql)
              }
          }
      }
    } catch {
      case exception: IllegalStateException =>
        rollback(connection)
        throw exception
      case NonFatal(exception) =>
        rollback(connection)
        throw new IllegalStateException("batch write failed", exception)
    } finally closeConnection(connection)
  }

  private def findLedger(connection: Connection, batchId: String): Option[LedgerRecord] = {
    val statement = connection.prepareStatement(
      s"SELECT workflow_id, execution_id, source_node_id, partition_id, batch_sequence, " +
        s"cursor_value, upper_bound, source_rows, target_rows FROM $LedgerTable WHERE batch_id = ?"
    )
    var resultSet: ResultSet = null
    try {
      statement.setString(1, batchId)
      resultSet = statement.executeQuery()
      Option.when(resultSet.next()) {
        val checkpoint = BatchCheckpoint(
          resultSet.getString("source_node_id"),
          resultSet.getString("partition_id"),
          resultSet.getLong("batch_sequence"),
          batchId,
          cn.xuyinyin.magic.workflow.checkpoint.SourceCursor(
            "mysql.numeric-pk",
            resultSet.getString("cursor_value"),
            resultSet.getString("upper_bound")
          ),
          resultSet.getLong("source_rows"),
          resultSet.getLong("target_rows")
        )
        LedgerRecord(
          batchId,
          resultSet.getString("workflow_id"),
          resultSet.getString("execution_id"),
          checkpoint
        )
      }
    } finally {
      closeResultSet(resultSet)
      closeStatement(statement)
    }
  }

  private def insertLedger(connection: Connection, record: LedgerRecord): Unit = {
    val statement = connection.prepareStatement(
      s"""INSERT INTO $LedgerTable
         |(batch_id, workflow_id, execution_id, source_node_id, partition_id, batch_sequence,
         | cursor_value, upper_bound, source_rows, target_rows)
         |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    )
    try {
      val checkpoint = record.checkpoint
      statement.setString(1, record.batchId)
      statement.setString(2, record.workflowId)
      statement.setString(3, record.executionId)
      statement.setString(4, checkpoint.sourceNodeId)
      statement.setString(5, checkpoint.partitionId)
      statement.setLong(6, checkpoint.batchSequence)
      statement.setString(7, checkpoint.cursor.value)
      statement.setString(8, checkpoint.cursor.upperBound)
      statement.setLong(9, checkpoint.sourceRowsScanned)
      statement.setLong(10, checkpoint.targetRowsWritten)
      statement.executeUpdate()
    } finally closeStatement(statement)
  }

  private def parseRows(rows: Vector[String]): Vector[(Vector[String], Vector[JsValue])] = {
    val records = rows.map(parseRecord)
    records.headOption.foreach { case (columns, _) =>
      if (records.exists(_._1 != columns)) {
        throw new IllegalArgumentException("all rows in a batch must have the same columns")
      }
    }
    records
  }

  private def writeRows(
    connection: Connection,
    table: String,
    records: Vector[(Vector[String], Vector[JsValue])],
    mode: String
  ): Unit =
    records.headOption.foreach { case (columns, _) =>
      val statement = connection.prepareStatement(generateSQL(table, columns.toList, mode))
      try {
        records.foreach { case (_, values) =>
          values.zipWithIndex.foreach { case (value, index) => statement.setString(index + 1, jdbcValue(value)) }
          statement.addBatch()
        }
        statement.executeBatch()
      } finally closeStatement(statement)
    }

  private def resultCheckpoint(result: BatchCommitResult): BatchCheckpoint = result match {
    case Committed(checkpoint) => checkpoint
    case AlreadyCommitted(checkpoint) => checkpoint
  }

  private def conflictingLedger(batchId: String): IllegalStateException =
    new IllegalStateException(s"conflicting durable metadata for batch $batchId")

  private def isDuplicateKey(exception: SQLException): Boolean =
    exception.getSQLState == "23505" ||
      (exception.getSQLState == "23000" && exception.getErrorCode == 1062)
  
  /**
   * 创建HikariCP连接池
   */
  protected[sinks] def createDataSource(
    host: String, 
    port: Int, 
    database: String, 
    username: String, 
    password: String
  ): HikariDataSource = {
    val config = new HikariConfig()
    
    config.setJdbcUrl(
      s"jdbc:mysql://$host:$port/$database?rewriteBatchedStatements=true&useSSL=false"
    )
    config.setUsername(username)
    config.setPassword(password)
    config.setDriverClassName("com.mysql.cj.jdbc.Driver")
    
    // 连接池配置
    config.setMaximumPoolSize(10)
    config.setMinimumIdle(2)
    config.setConnectionTimeout(30000)
    
    new HikariDataSource(config)
  }
  
  private def writeBatch(
    dataSource: HikariDataSource,
    table: String,
    rows: Seq[String],
    mode: String
  ): Int = {
    var connection: Connection = null
    var statement: PreparedStatement = null
    try {
      val records = rows.map(parseRecord)
      val columns = records.head._1
      if (records.exists(_._1 != columns)) {
        throw new IllegalArgumentException("all rows in a batch must have the same columns")
      }
      connection = dataSource.getConnection
      connection.setAutoCommit(false)
      statement = connection.prepareStatement(generateSQL(table, columns.toList, mode))
      records.foreach { record =>
        record._2.zipWithIndex.foreach { case (value, index) =>
          statement.setString(index + 1, jdbcValue(value))
        }
        statement.addBatch()
      }
      statement.executeBatch()
      connection.commit()
      rows.size
    } catch {
      case NonFatal(exception) =>
        rollback(connection)
        throw new IllegalStateException("batch write failed", exception)
    } finally {
      closeStatement(statement)
      closeConnection(connection)
    }
  }

  private def parseRecord(jsonRow: String): (Vector[String], Vector[JsValue]) = {
    val fields = jsonRow.parseJson.asJsObject.fields.toVector
    (fields.map(_._1), fields.map(_._2))
  }

  private def jdbcValue(value: JsValue): String = value match {
    case JsString(stringValue) => stringValue
    case JsNumber(numberValue) => numberValue.toString
    case JsBoolean(booleanValue) => booleanValue.toString
    case JsNull => null
    case other => other.toString.replaceAll("\"", "")
  }

  private def rollback(connection: Connection): Unit =
    if (connection != null) {
      try connection.rollback()
      catch { case NonFatal(_) => () }
    }

  private def closeStatement(statement: PreparedStatement): Unit =
    if (statement != null) {
      try statement.close()
      catch { case NonFatal(_) => () }
    }

  private def closeResultSet(resultSet: ResultSet): Unit =
    if (resultSet != null) {
      try resultSet.close()
      catch { case NonFatal(_) => () }
    }

  private def closeConnection(connection: Connection): Unit =
    if (connection != null) {
      try connection.close()
      catch { case NonFatal(_) => () }
    }

  private def closeDataSource(dataSource: HikariDataSource): Unit =
    try dataSource.close()
    catch { case NonFatal(_) => () }

  /**
   * 生成SQL语句
   */
  private def generateSQL(table: String, columns: List[String], mode: String): String = {
    val columnsPart = columns.mkString(", ")
    val placeholders = columns.map(_ => "?").mkString(", ")
    
    mode match {
      case "insert" =>
        s"INSERT INTO $table ($columnsPart) VALUES ($placeholders)"
      
      case "upsert" =>
        // INSERT ON DUPLICATE KEY UPDATE
        val updatePart = columns.map(col => s"$col = VALUES($col)").mkString(", ")
        s"INSERT INTO $table ($columnsPart) VALUES ($placeholders) ON DUPLICATE KEY UPDATE $updatePart"
      
      case "replace" =>
        s"REPLACE INTO $table ($columnsPart) VALUES ($placeholders)"
      
      case _ =>
        s"INSERT INTO $table ($columnsPart) VALUES ($placeholders)"
    }
  }
}
