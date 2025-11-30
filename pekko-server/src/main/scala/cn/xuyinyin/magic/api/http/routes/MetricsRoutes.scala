package cn.xuyinyin.magic.api.http.routes

import cn.xuyinyin.magic.monitoring.PrometheusMetrics
import io.prometheus.client.exporter.common.TextFormat
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import com.typesafe.scalalogging.Logger

import java.io.StringWriter

/**
 * Prometheus指标HTTP路由
 * 
 * 提供Prometheus格式的指标端点：
 * - GET /metrics - 返回所有Prometheus指标
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-28
 */
class MetricsRoutes {
  
  private val logger = Logger(getClass)
  
  /**
   * 指标路由
   */
  val routes: Route = path("metrics") {
    get {
      logger.debug("GET /metrics - Prometheus metrics requested")
      
      try {
        // 生成Prometheus格式的指标
        val writer = new StringWriter()
        TextFormat.write004(writer, PrometheusMetrics.registry.metricFamilySamples())
        val metricsText = writer.toString
        
        // 返回text/plain格式的指标
        complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, metricsText))
      } catch {
        case ex: Exception =>
          logger.error("Failed to generate Prometheus metrics", ex)
          complete(StatusCodes.InternalServerError, s"Failed to generate metrics: ${ex.getMessage}")
      }
    }
  }
}
