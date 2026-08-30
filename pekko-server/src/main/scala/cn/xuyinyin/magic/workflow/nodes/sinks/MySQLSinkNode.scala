package cn.xuyinyin.magic.workflow.nodes.sinks

import cn.xuyinyin.magic.workflow.nodes.base.NodeSink
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import spray.json._
import java.sql.{Connection, PreparedStatement}
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
class MySQLSinkNode extends NodeSink {
  
  override def nodeType: String = "mysql.write"
  
  override def createSink(node: WorkflowDSL.Node, onLog: String => Unit)
                        (implicit ec: ExecutionContext): Sink[String, Future[Done]] = {
    import spray.json._
    
    // 辅助方法：安全提取字符串
    def getString(key: String, default: Option[String] = None): String = {
      node.config.fields.get(key) match {
        case Some(JsString(v)) => v
        case None => default.getOrElse(throw new IllegalArgumentException(s"MySQL sink缺少${key}配置"))
        case _ => throw new IllegalArgumentException(s"${key}必须是字符串类型")
      }
    }
    
    // 辅助方法：安全提取数字
    def getInt(key: String, default: Int): Int = {
      node.config.fields.get(key) match {
        case Some(JsNumber(v)) => v.toInt
        case None => default
        case _ => throw new IllegalArgumentException(s"${key}必须是数字类型")
      }
    }
    
    // 解析配置
    val host = getString("host", Some("localhost"))
    val port = getInt("port", 3306)
    val database = getString("database")
    val table = getString("table")
    val username = getString("username")
    val password = getString("password")
    val batchSize = getInt("batchSize", 1000)
    val mode = getString("mode", Some("insert"))

    require(batchSize > 0, "MySQL sink的batchSize必须大于0")

    onLog(s"[MySQL Sink] 连接MySQL: $host:$port/$database")
    onLog(s"[MySQL Sink] 写入表: $table (模式: $mode, 批量: $batchSize)")
    
    Sink.lazyInitAsync[String, Future[Done]] { () =>
      var dataSource: HikariDataSource = null
      try {
        dataSource = createDataSource(host, port, database, username, password)
        Future.successful(createInnerSink(dataSource, table, batchSize, mode, onLog))
      } catch {
        case NonFatal(exception) =>
          if (dataSource != null) closeDataSource(dataSource)
          Future.failed(exception)
      }
    }.mapMaterializedValue(_.flatMap(_.getOrElse(Future.successful(Done))))
  }

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
