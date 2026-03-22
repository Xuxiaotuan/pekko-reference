package cn.xuyinyin.magic.workflow.nodes.sinks

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
