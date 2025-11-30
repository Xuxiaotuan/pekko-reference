package cn.xuyinyin.magic.datafusion

import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.Logger
import org.apache.pekko.actor.typed.ActorSystem

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

/**
 * SQL节点注册表
 * 
 * 管理SQL查询节点的配置和FlightClientPool
 */
object SQLNodeRegistry {
  
  private val logger = Logger(getClass)
  
  /**
   * 从配置创建FlightClientPool
   * 
   * 配置示例：
   * {{{
   * datafusion {
   *   enabled = true
   *   host = "localhost"
   *   port = 50051
   *   pool {
   *     maxTotal = 10
   *     maxIdle = 5
   *     minIdle = 2
   *   }
   * }
   * }}}
   */
  def createClientPoolFromConfig(
    config: Config = ConfigFactory.load()
  )(implicit system: ActorSystem[_], ec: ExecutionContext): Option[FlightClientPool] = {
    
    Try {
      if (config.hasPath("datafusion.enabled") && config.getBoolean("datafusion.enabled")) {
        logger.info("DataFusion integration is enabled")
        
        val host = if (config.hasPath("datafusion.host")) {
          config.getString("datafusion.host")
        } else {
          "localhost"
        }
        
        val port = if (config.hasPath("datafusion.port")) {
          config.getInt("datafusion.port")
        } else {
          50051
        }
        
        val maxTotal = if (config.hasPath("datafusion.pool.maxTotal")) {
          config.getInt("datafusion.pool.maxTotal")
        } else {
          10
        }
        
        val maxIdle = if (config.hasPath("datafusion.pool.maxIdle")) {
          config.getInt("datafusion.pool.maxIdle")
        } else {
          5
        }
        
        val minIdle = if (config.hasPath("datafusion.pool.minIdle")) {
          config.getInt("datafusion.pool.minIdle")
        } else {
          2
        }
        
        val clientConfig = FlightClientConfig(host = host, port = port)
        val poolConfig = FlightClientPoolConfig(
          maxTotal = maxTotal,
          maxIdle = maxIdle,
          minIdle = minIdle
        )
        
        logger.info(s"Creating FlightClientPool: $host:$port (pool: max=$maxTotal, idle=$maxIdle-$minIdle)")
        
        val pool = FlightClientPool(clientConfig, poolConfig)
        Some(pool)
      } else {
        logger.info("DataFusion integration is disabled")
        None
      }
    } match {
      case Success(pool) => pool
      case Failure(e) =>
        logger.warn(s"Failed to create FlightClientPool: ${e.getMessage}. SQL query nodes will not be available.", e)
        None
    }
  }
  
  /**
   * 检查SQL节点是否可用
   */
  def isSQLNodeAvailable(pool: Option[FlightClientPool]): Boolean = {
    pool.isDefined
  }
  
  /**
   * 获取SQL节点类型
   */
  def sqlNodeType: String = "sql.query"
  
  /**
   * 获取支持的SQL节点类型列表
   */
  def supportedSQLNodeTypes: Set[String] = Set(sqlNodeType)
  
  /**
   * 验证SQL节点配置
   */
  def validateSQLNodeConfig(config: spray.json.JsObject): Either[String, Unit] = {
    SQLNodeConfig.fromJson(config).flatMap { sqlConfig =>
      sqlConfig.validate()
    }
  }
}

/**
 * SQL节点注册表配置
 */
case class SQLNodeRegistryConfig(
  enabled: Boolean = false,
  host: String = "localhost",
  port: Int = 50051,
  poolConfig: FlightClientPoolConfig = FlightClientPoolConfig()
)

object SQLNodeRegistryConfig {
  
  /**
   * 从Typesafe Config加载
   */
  def fromConfig(config: Config = ConfigFactory.load()): SQLNodeRegistryConfig = {
    val enabled = if (config.hasPath("datafusion.enabled")) {
      config.getBoolean("datafusion.enabled")
    } else {
      false
    }
    
    val host = if (config.hasPath("datafusion.host")) {
      config.getString("datafusion.host")
    } else {
      "localhost"
    }
    
    val port = if (config.hasPath("datafusion.port")) {
      config.getInt("datafusion.port")
    } else {
      50051
    }
    
    val maxTotal = if (config.hasPath("datafusion.pool.maxTotal")) {
      config.getInt("datafusion.pool.maxTotal")
    } else {
      10
    }
    
    val maxIdle = if (config.hasPath("datafusion.pool.maxIdle")) {
      config.getInt("datafusion.pool.maxIdle")
    } else {
      5
    }
    
    val minIdle = if (config.hasPath("datafusion.pool.minIdle")) {
      config.getInt("datafusion.pool.minIdle")
    } else {
      2
    }
    
    val poolConfig = FlightClientPoolConfig(
      maxTotal = maxTotal,
      maxIdle = maxIdle,
      minIdle = minIdle
    )
    
    SQLNodeRegistryConfig(
      enabled = enabled,
      host = host,
      port = port,
      poolConfig = poolConfig
    )
  }
}
