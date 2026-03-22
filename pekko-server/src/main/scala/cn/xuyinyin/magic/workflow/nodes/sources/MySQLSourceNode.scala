package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.workflow.nodes.base.NodeSource
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import spray.json._
import spray.json.DefaultJsonProtocol._

/**
 * MySQL Source实现
 * 
 * 实现pekko-server定义的NodeSource接口
 * 使用HikariCP连接池 + 流式查询
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-15
 */
class MySQLSourceNode extends NodeSource {
  
  override def nodeType: String = "mysql.query"
  
  override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = {
    import spray.json._
    
    // 辅助方法：安全提取字符串
    def getString(key: String, default: Option[String] = None): String = {
      node.config.fields.get(key) match {
        case Some(JsString(v)) => v
        case None => default.getOrElse(throw new IllegalArgumentException(s"MySQL source缺少${key}配置"))
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
    val username = getString("username")
    val password = getString("password")
    val sql = getString("sql")
    val fetchSize = getInt("fetchSize", 1000)
    
    onLog(s"[MySQL Source] 连接MySQL: $host:$port/$database")
    onLog(s"[MySQL Source] 执行查询: $sql")
    onLog(s"[MySQL Source] 批量大小: $fetchSize")
    
    // 创建连接池
    val dataSource = createDataSource(host, port, database, username, password)
    
    // 使用unfoldResource流式读取
    Source.unfoldResource[String, (java.sql.Connection, java.sql.PreparedStatement, java.sql.ResultSet)](
      // 1. 创建资源（打开连接）
      create = () => {
        onLog(s"[MySQL Source] 打开数据库连接...")
        val connection = dataSource.getConnection
        connection.setAutoCommit(false)
        
        val statement = connection.prepareStatement(
          sql,
          java.sql.ResultSet.TYPE_FORWARD_ONLY,
          java.sql.ResultSet.CONCUR_READ_ONLY
        )
        statement.setFetchSize(fetchSize)
        
        val resultSet = statement.executeQuery()
        onLog(s"[MySQL Source] 查询执行成功，开始读取数据...")
        (connection, statement, resultSet)
      },
      
      // 2. 读取数据（流式读取）
      read = { case (conn, stmt, rs) =>
        if (rs.next()) {
          Some(resultSetToJson(rs))
        } else {
          None  // 数据读取完毕
        }
      },
      
      // 3. 关闭资源
      close = { case (conn, stmt, rs) =>
        onLog(s"[MySQL Source] 关闭数据库连接...")
        try { rs.close() } catch { case _: Exception => }
        try { stmt.close() } catch { case _: Exception => }
        try { conn.close() } catch { case _: Exception => }
        try { dataSource.close() } catch { case _: Exception => }
        onLog(s"[MySQL Source] 资源已释放")
      }
    )
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
      s"jdbc:mysql://$host:$port/$database?useSSL=false&serverTimezone=UTC&useCursorFetch=true"
    )
    config.setUsername(username)
    config.setPassword(password)
    config.setDriverClassName("com.mysql.cj.jdbc.Driver")
    
    // 连接池配置
    config.setMaximumPoolSize(5)
    config.setMinimumIdle(1)
    config.setConnectionTimeout(30000)
    config.setIdleTimeout(600000)       // 10分钟
    config.setMaxLifetime(1800000)      // 30分钟
    
    // 连接测试
    config.setConnectionTestQuery("SELECT 1")
    
    new HikariDataSource(config)
  }
  
  /**
   * ResultSet转JSON字符串
   */
  private def resultSetToJson(rs: java.sql.ResultSet): String = {
    val metadata = rs.getMetaData
    val columnCount = metadata.getColumnCount
    
    val values = (1 to columnCount).map { i =>
      val columnName = metadata.getColumnName(i)
      val value = rs.getObject(i)
      columnName -> (if (value == null) JsNull else JsString(value.toString))
    }.toMap
    
    JsObject(values).compactPrint
  }
}
