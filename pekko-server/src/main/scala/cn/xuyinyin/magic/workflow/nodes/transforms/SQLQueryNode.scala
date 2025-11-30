package cn.xuyinyin.magic.workflow.nodes.transforms

import cn.xuyinyin.magic.datafusion._
import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base.NodeTransform
import com.typesafe.scalalogging.Logger
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
 * SQL查询节点
 * 
 * 使用DataFusion执行SQL查询，实现高性能数据转换
 * 
 * 节点配置示例：
 * {{{
 * {
 *   "id": "sql-1",
 *   "type": "transform",
 *   "nodeType": "sql.query",
 *   "config": {
 *     "sql": "SELECT * FROM input WHERE value > 100",
 *     "batchSize": 1000,
 *     "timeout": 30,
 *     "parameters": {
 *       "threshold": 100
 *     }
 *   }
 * }
 * }}}
 *
 * @param pool Flight Client连接池
 * @param config SQL节点配置
 * @param system Actor系统
 * @param ec 执行上下文
 */
class SQLQueryNode(
  pool: FlightClientPool,
  config: SQLNodeConfig
)(implicit system: ActorSystem[_], ec: ExecutionContext) extends NodeTransform {
  
  private val logger = Logger(getClass)
  
  override def nodeType: String = "sql.query"
  
  override def createTransform(
    node: WorkflowDSL.Node,
    onLog: String => Unit
  )(implicit ec: ExecutionContext): Flow[String, String, NotUsed] = {
    
    onLog(s"初始化SQL查询节点: ${node.label}")
    onLog(s"SQL: ${config.sql.take(100)}")
    
    // 验证配置
    config.validate() match {
      case Left(error) =>
        onLog(s"配置错误: $error")
        throw new IllegalArgumentException(s"Invalid SQL node config: $error")
      case Right(_) =>
        onLog("配置验证通过")
    }
    
    // 绑定参数
    val boundSql = if (config.parameters.nonEmpty) {
      val sql = config.bindParameters()
      onLog(s"参数绑定后的SQL: ${sql.take(100)}")
      sql
    } else {
      config.sql
    }
    
    Flow[String]
      .grouped(config.batchSize)
      .mapAsync(1) { batch =>
        executeQueryWithRetry(boundSql, batch, onLog)
      }
      .mapConcat(identity)
  }
  
  /**
   * 执行SQL查询（带重试）
   */
  private def executeQueryWithRetry(
    sql: String,
    batch: Seq[String],
    onLog: String => Unit
  ): Future[Seq[String]] = {
    
    val startTime = System.currentTimeMillis()
    
    RetryPolicy.withRetry(maxRetries = 3, backoff = 1.second) {
      pool.withClientAsync { client =>
        client.executeQuery(sql).map { queryResponse =>
          try {
            onLog(s"执行SQL查询，批次大小: ${batch.size}")
            
            // 处理查询结果
            val results = if (queryResponse.success) {
              // 转换查询结果为JSON字符串
              ArrowConverter.mapToJsonLines(queryResponse.data.map(_.toMap))
            } else {
              onLog(s"查询失败: ${queryResponse.message}")
              Seq.empty[String]
            }
            
            val duration = System.currentTimeMillis() - startTime
            onLog(s"查询完成: ${results.size}行, 耗时${duration}ms")
            
            results
          } catch {
            case e: Exception =>
              val duration = System.currentTimeMillis() - startTime
              onLog(s"查询失败: ${e.getMessage}, 耗时${duration}ms")
              logger.error(s"SQL query failed: ${e.getMessage}", e)
              throw e
          }
        }
      }
    }
  }
}

object SQLQueryNode {
  
  /**
   * 创建SQL查询节点
   */
  def apply(
    pool: FlightClientPool,
    config: SQLNodeConfig
  )(implicit system: ActorSystem[_], ec: ExecutionContext): SQLQueryNode = {
    new SQLQueryNode(pool, config)
  }
  
  /**
   * 从WorkflowDSL.Node创建
   */
  def fromNode(
    node: WorkflowDSL.Node,
    pool: FlightClientPool
  )(implicit system: ActorSystem[_], ec: ExecutionContext): Either[String, SQLQueryNode] = {
    SQLNodeConfig.fromJson(node.config).map { config =>
      new SQLQueryNode(pool, config)
    }
  }
}
