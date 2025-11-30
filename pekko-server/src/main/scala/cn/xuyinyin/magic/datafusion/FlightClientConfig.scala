package cn.xuyinyin.magic.datafusion

import scala.concurrent.duration._

/**
 * Flight Client配置
 *
 * @param host DataFusion Service地址
 * @param port DataFusion Service端口
 * @param connectionTimeout 连接超时
 * @param requestTimeout 请求超时
 * @param maxRetries 最大重试次数
 * @param retryBackoff 重试退避时间
 */
case class FlightClientConfig(
  host: String = "localhost",
  port: Int = 50051,
  connectionTimeout: FiniteDuration = 10.seconds,
  requestTimeout: FiniteDuration = 30.seconds,
  maxRetries: Int = 3,
  retryBackoff: FiniteDuration = 1.second
)

object FlightClientConfig {
  
  /**
   * 从配置文件加载
   */
  def fromConfig(config: com.typesafe.config.Config): FlightClientConfig = {
    FlightClientConfig(
      host = config.getString("datafusion.service.host"),
      port = config.getInt("datafusion.service.port"),
      connectionTimeout = config.getDuration("datafusion.service.connection-timeout").toMillis.millis,
      requestTimeout = config.getDuration("datafusion.service.request-timeout").toMillis.millis,
      maxRetries = config.getInt("datafusion.service.max-retries"),
      retryBackoff = config.getDuration("datafusion.service.retry-backoff").toMillis.millis
    )
  }
  
  /**
   * 默认配置
   */
  def default: FlightClientConfig = FlightClientConfig()
}
