package cn.xuyinyin.magic.connectors.mysql

import cn.xuyinyin.magic.workflow.nodes.base.NodeSink
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.Sink
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import spray.json._
import spray.json.DefaultJsonProtocol._
import scala.concurrent.{ExecutionContext, Future}

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
    // 解析配置
    val host = node.config.fields.get("host").map(_.convertTo[String]).getOrElse("localhost")
    val port = node.config.fields.get("port").map(_.convertTo[Int]).getOrElse(3306)
    val database = node.config.fields.get("database").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("MySQL sink缺少database配置"))
    val table = node.config.fields.get("table").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("MySQL sink缺少table配置"))
    val username = node.config.fields.get("username").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("MySQL sink缺少username配置"))
    val password = node.config.fields.get("password").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("MySQL sink缺少password配置"))
    val batchSize = node.config.fields.get("batchSize").map(_.convertTo[Int]).getOrElse(1000)
    val mode = node.config.fields.get("mode").map(_.convertTo[String]).getOrElse("insert")
    
    onLog(s"[MySQL Sink] 连接MySQL: $host:$port/$database")
    onLog(s"[MySQL Sink] 写入表: $table (模式: $mode, 批量: $batchSize)")
    
    // 创建连接池
    val dataSource = createDataSource(host, port, database, username, password)
    
    // 创建Sink
    Sink.fold[Int, String](0) { (count, jsonRow) =>
      try {
        writeRecord(dataSource, table, jsonRow, mode, onLog)
        val newCount = count + 1
        
        if (newCount % batchSize == 0) {
          onLog(s"[MySQL Sink] 已写入 $newCount 行到 $table")
        }
        
        newCount
      } catch {
        case ex: Exception =>
          onLog(s"[MySQL Sink] 写入失败: ${ex.getMessage}")
          count
      }
    }.mapMaterializedValue { future =>
      future.map { totalCount =>
        onLog(s"[MySQL Sink] 写入完成，总计: $totalCount 行")
        dataSource.close()
        Done
      }
    }
  }
  
  /**
   * 创建HikariCP连接池
   */
  private def createDataSource(
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
  
  /**
   * 写入单条记录
   */
  private def writeRecord(
    dataSource: HikariDataSource,
    table: String,
    jsonRow: String,
    mode: String,
    onLog: String => Unit
  ): Unit = {
    val connection = dataSource.getConnection
    connection.setAutoCommit(false)
    
    try {
      // 解析JSON
      val json = jsonRow.parseJson.asJsObject
      val columns = json.fields.keys.toList
      val values = json.fields.values.toList
      
      // 生成SQL
      val sql = generateSQL(table, columns, mode)
      val statement = connection.prepareStatement(sql)
      
      // 设置参数
      values.zipWithIndex.foreach { case (value, idx) =>
        val stringValue = value match {
          case JsString(s) => s
          case JsNumber(n) => n.toString
          case JsBoolean(b) => b.toString
          case JsNull => null
          case other => other.toString.replaceAll("\"", "")
        }
        statement.setString(idx + 1, stringValue)
      }
      
      // 执行写入
      statement.executeUpdate()
      connection.commit()
      statement.close()
    } catch {
      case ex: Exception =>
        connection.rollback()
        throw ex
    } finally {
      connection.close()
    }
  }
  
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
