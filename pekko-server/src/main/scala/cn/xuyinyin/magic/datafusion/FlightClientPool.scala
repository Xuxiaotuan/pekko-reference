package cn.xuyinyin.magic.datafusion

import org.apache.commons.pool2.impl.{DefaultPooledObject, GenericObjectPool, GenericObjectPoolConfig}
import org.apache.commons.pool2.{BasePooledObjectFactory, PooledObject}
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * Flight Client连接池配置
 *
 * @param maxTotal 最大连接数
 * @param maxIdle 最大空闲连接数
 * @param minIdle 最小空闲连接数
 * @param maxWaitMillis 获取连接的最大等待时间（毫秒）
 * @param testOnBorrow 借用时是否测试连接
 * @param testOnReturn 归还时是否测试连接
 * @param testWhileIdle 空闲时是否测试连接
 */
case class FlightClientPoolConfig(
  maxTotal: Int = 20,
  maxIdle: Int = 10,
  minIdle: Int = 2,
  maxWaitMillis: Long = 5000,
  testOnBorrow: Boolean = true,
  testOnReturn: Boolean = false,
  testWhileIdle: Boolean = true
)

/**
 * Flight Client工厂
 * 
 * 用于Apache Commons Pool创建和管理DataFusionClient实例
 */
class FlightClientFactory(config: FlightClientConfig)(implicit ec: ExecutionContext)
  extends BasePooledObjectFactory[DataFusionClient] {
  
  private val logger = Logger(getClass)
  
  /**
   * 创建新的客户端实例
   */
  override def create(): DataFusionClient = {
    logger.debug("Creating new DataFusion Arrow Flight Client")
    new DataFusionClient(config)
  }
  
  /**
   * 包装客户端为池化对象
   */
  override def wrap(client: DataFusionClient): PooledObject[DataFusionClient] = {
    new DefaultPooledObject[DataFusionClient](client)
  }
  
  /**
   * 销毁客户端
   */
  override def destroyObject(p: PooledObject[DataFusionClient]): Unit = {
    logger.debug("Destroying DataFusion Arrow Flight Client")
    Try(p.getObject.close()) match {
      case Success(_) => // OK
      case Failure(e) => logger.error(s"Error destroying client: ${e.getMessage}", e)
    }
  }
  
  /**
   * 验证客户端是否可用
   */
  override def validateObject(p: PooledObject[DataFusionClient]): Boolean = {
    Try {
      // 使用健康检查验证连接
      import scala.concurrent.Await
      import scala.concurrent.duration._
      Await.result(p.getObject.healthCheck(), 5.seconds)
    } match {
      case Success(healthy) => healthy
      case Failure(e) =>
        logger.warn(s"Client validation failed: ${e.getMessage}")
        false
    }
  }
}

/**
 * Flight Client连接池
 * 
 * 管理DataFusionClient实例的连接池，提供连接复用和资源管理
 *
 * @param clientConfig Flight Client配置
 * @param poolConfig 连接池配置
 * @param ec 执行上下文
 */
class FlightClientPool(
  clientConfig: FlightClientConfig,
  poolConfig: FlightClientPoolConfig = FlightClientPoolConfig()
)(implicit ec: ExecutionContext) {
  
  private val logger = Logger(getClass)
  
  // 创建Apache Commons Pool配置
  private val apachePoolConfig = new GenericObjectPoolConfig[DataFusionClient]()
  apachePoolConfig.setMaxTotal(poolConfig.maxTotal)
  apachePoolConfig.setMaxIdle(poolConfig.maxIdle)
  apachePoolConfig.setMinIdle(poolConfig.minIdle)
  apachePoolConfig.setMaxWaitMillis(poolConfig.maxWaitMillis)
  apachePoolConfig.setTestOnBorrow(poolConfig.testOnBorrow)
  apachePoolConfig.setTestOnReturn(poolConfig.testOnReturn)
  apachePoolConfig.setTestWhileIdle(poolConfig.testWhileIdle)
  
  // 创建连接池
  private val pool = new GenericObjectPool[DataFusionClient](
    new FlightClientFactory(clientConfig),
    apachePoolConfig
  )
  
  logger.info(s"Arrow Flight Client Pool initialized: maxTotal=${poolConfig.maxTotal}, " +
    s"maxIdle=${poolConfig.maxIdle}, minIdle=${poolConfig.minIdle}")
  
  /**
   * 使用连接池中的客户端执行操作
   *
   * @param f 使用客户端的函数
   * @tparam T 返回类型
   * @return 操作结果
   */
  def withClient[T](f: DataFusionClient => T): T = {
    val client = pool.borrowObject()
    try {
      f(client)
    } finally {
      pool.returnObject(client)
    }
  }
  
  /**
   * 异步使用连接池中的客户端
   *
   * @param f 使用客户端的异步函数
   * @tparam T 返回类型
   * @return Future结果
   */
  def withClientAsync[T](f: DataFusionClient => Future[T]): Future[T] = {
    val client = pool.borrowObject()
    f(client).andThen {
      case _ => pool.returnObject(client)
    }
  }
  
  /**
   * 获取连接池统计信息
   */
  def getStats: PoolStats = {
    PoolStats(
      numActive = pool.getNumActive,
      numIdle = pool.getNumIdle,
      numWaiters = pool.getNumWaiters,
      maxTotal = poolConfig.maxTotal
    )
  }
  
  /**
   * 关闭连接池
   */
  def close(): Unit = {
    logger.info("Closing Arrow Flight Client Pool")
    Try(pool.close()) match {
      case Success(_) => logger.info("Arrow Flight Client Pool closed successfully")
      case Failure(e) => logger.error(s"Error closing pool: ${e.getMessage}", e)
    }
  }
}

/**
 * 连接池统计信息
 */
case class PoolStats(
  numActive: Int,
  numIdle: Int,
  numWaiters: Int,
  maxTotal: Int
) {
  def utilizationPercent: Double = (numActive.toDouble / maxTotal) * 100
  
  override def toString: String = {
    s"PoolStats(active=$numActive, idle=$numIdle, waiters=$numWaiters, " +
      s"max=$maxTotal, utilization=${utilizationPercent.formatted("%.1f")}%)"
  }
}

object FlightClientPool {
  
  /**
   * 创建连接池
   */
  def apply(
    clientConfig: FlightClientConfig,
    poolConfig: FlightClientPoolConfig = FlightClientPoolConfig()
  )(implicit ec: ExecutionContext): FlightClientPool = {
    new FlightClientPool(clientConfig, poolConfig)
  }
}