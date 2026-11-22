package cn.xuyinyin.magic.stream.cdc.sync

import cn.xuyinyin.magic.common.CborSerializable
import cn.xuyinyin.magic.stream.cdc.models.{SyncConfig, SyncResult}
import cn.xuyinyin.magic.stream.cdc.simulator.CdcDataSimulator
import cn.xuyinyin.magic.stream.cdc.watermark.WatermarkManager
import com.typesafe.scalalogging.LazyLogging

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

/**
 * 简化的全量数据同步处理器
 * 不使用复杂的Stream处理，专注于核心逻辑
 */
class SimpleFullSyncProcessor(
  simulator: CdcDataSimulator,
  watermarkManager: WatermarkManager
)(implicit ec: ExecutionContext) extends LazyLogging {

  /**
   * 执行全量同步
   */
  def performFullSync(config: SyncConfig): Future[SyncResult] = {
    logger.info(s"开始执行全量同步: 表=${config.tableName}, 批次大小=${config.batchSize}")
    
    val startTime = Instant.now()
    
    Future {
      var processedCount = 0
      var successCount = 0
      var errorCount = 0
      var lastProcessedPosition = 0L
      
      // 根据表名获取全量数据
      val fullData = getFullDataForTable(config.tableName)
      
      // 按批次处理数据
      fullData.grouped(config.batchSize).foreach { batch =>
        batch.foreach { record =>
          try {
            // 模拟数据处理逻辑
            processRecord(record, config)
            successCount += 1
            lastProcessedPosition = record.get("id").map(_.toString.toLong).getOrElse(0L)
          } catch {
            case e: Exception =>
              logger.error(s"处理记录失败: $record", e)
              errorCount += 1
          }
          processedCount += 1
        }
        
        // 更新当前水位线
        watermarkManager.advanceCurrentWatermark(lastProcessedPosition)
        
        // 模拟处理延迟
        Thread.sleep(10)
      }
      
      // 更新高水位线
      watermarkManager.updateHighWatermark(lastProcessedPosition)
      
      val result = SyncResult(
        tableName = config.tableName,
        processedCount = processedCount,
        successCount = successCount,
        errorCount = errorCount,
        lastProcessedPosition = lastProcessedPosition,
        timestamp = Instant.now()
      )
      
      logger.info(s"全量同步完成: $result")
      result
    }
  }
  
  /**
   * 根据表名获取全量数据
   */
  private def getFullDataForTable(tableName: String): List[Map[String, Any]] = {
    tableName match {
      case "users" => 
        // 模拟从数据库读取用户全量数据
        (1 to 100).map { i =>
          Map(
            "id" -> i.toLong,
            "name" -> s"用户$i",
            "email" -> s"user$i@example.com",
            "age" -> (20 + scala.util.Random.nextInt(50)),
            "created_at" -> Instant.now().toString,
            "updated_at" -> Instant.now().toString
          )
        }.toList
        
      case "orders" =>
        // 模拟从数据库读取订单全量数据
        (1 to 200).map { i =>
          Map(
            "id" -> i.toLong,
            "user_id" -> (1 + scala.util.Random.nextInt(100)).toLong,
            "product_name" -> s"产品$i",
            "amount" -> (10 + scala.util.Random.nextInt(1000)).toString,
            "status" -> (if (scala.util.Random.nextBoolean()) "已完成" else "进行中"),
            "created_at" -> Instant.now().toString,
            "updated_at" -> Instant.now().toString
          )
        }.toList
        
      case _ =>
        logger.warn(s"未知的表名: $tableName")
        List.empty
    }
  }
  
  /**
   * 处理单条记录
   */
  private def processRecord(record: Map[String, Any], config: SyncConfig): Unit = {
    // 模拟数据处理逻辑，比如写入目标数据库、发送到消息队列等
    logger.debug(s"处理记录: 表=${config.tableName}, 记录=$record")
    
    // 模拟处理延迟
    Thread.sleep(5)
    
    // 这里可以添加实际的数据处理逻辑，比如：
    // 1. 写入目标数据库
    // 2. 发送到消息队列
    // 3. 更新索引
    // 4. 触发下游处理
  }
  
  /**
   * 检查全量同步是否完成
   */
  def isFullSyncComplete(config: SyncConfig): Boolean = {
    // 检查是否所有数据都已处理完成
    val currentWatermark = watermarkManager.getCurrentWatermark
    val highWatermark = watermarkManager.getHighWatermark
    
    currentWatermark >= highWatermark
  }
  
  /**
   * 获取全量同步进度
   */
  def getFullSyncProgress(config: SyncConfig): SyncProgress = {
    val current = watermarkManager.getCurrentWatermark
    val high = watermarkManager.getHighWatermark
    val low = watermarkManager.getLowWatermark
    
    val total = high - low
    val processed = current - low
    val percentage = if (total > 0) (processed.toDouble / total * 100).toInt else 0
    
    SyncProgress(
      tableName = config.tableName,
      totalRecords = total,
      processedRecords = processed,
      percentage = percentage,
      currentWatermark = current,
      highWatermark = high,
      lowWatermark = low
    )
  }
}

/**
 * 同步进度
 */
case class SyncProgress(
  tableName: String,
  totalRecords: Long,
  processedRecords: Long,
  percentage: Int,
  currentWatermark: Long,
  highWatermark: Long,
  lowWatermark: Long
) extends CborSerializable
