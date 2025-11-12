package cn.xuyinyin.magic.cdc.watermark

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import cn.xuyinyin.magic.cdc.models.{WatermarkEvent, SyncConfig, SyncStatus}
import cn.xuyinyin.magic.common.CborSerializable
import com.typesafe.scalalogging.LazyLogging

/**
 * 高低水位线管理器
 * 用于管理CDC数据同步的水位线，确保数据一致性和顺序性
 */
class WatermarkManager extends LazyLogging {

  private val currentWatermark = new AtomicLong(0L)
  private val highWatermark = new AtomicLong(Long.MaxValue)
  private val lowWatermark = new AtomicLong(0L)
  
  /**
   * 更新高水位线
   * 高水位线表示已经确认处理完成的最大位置
   */
  def updateHighWatermark(newHighWatermark: Long): Unit = {
    val oldValue = highWatermark.get()
    val next = math.max(oldValue, newHighWatermark)
    if (next != oldValue) {
      highWatermark.set(next)
      logger.info(s"高水位线更新: $oldValue -> $next")
    } else {
      logger.debug(s"高水位线保持不变: $oldValue")
    }
  }
  
  /**
   * 更新低水位线
   * 低水位线表示开始处理的最小位置
   */
  def updateLowWatermark(newLowWatermark: Long): Unit = {
    val oldValue = lowWatermark.getAndSet(newLowWatermark)
    logger.info(s"低水位线更新: $oldValue -> $newLowWatermark")
  }
  
  /**
   * 推进当前水位线
   * 当前水位线表示正在处理的位置
   */
  def advanceCurrentWatermark(newPosition: Long): Unit = {
    val oldValue = currentWatermark.getAndSet(newPosition)
    logger.debug(s"当前水位线推进: $oldValue -> $newPosition")
  }
  
  /**
   * 获取当前水位线
   */
  def getCurrentWatermark: Long = currentWatermark.get()
  
  /**
   * 获取高水位线
   */
  def getHighWatermark: Long = highWatermark.get()
  
  /**
   * 获取低水位线
   */
  def getLowWatermark: Long = lowWatermark.get()
  
  /**
   * 检查是否在安全范围内处理数据
   * 只有在低水位线和高水位线之间的数据才是安全的
   */
  def isInSafeRange(position: Long): Boolean = {
    val current = currentWatermark.get()
    val high = highWatermark.get()
    position > current && position <= high
  }
  
  /**
   * 检查是否可以推进高水位线
   * 只有当所有小于等于新位置的数据都已处理完成时才能推进
   */
  def canAdvanceHighWatermark(newPosition: Long): Boolean = {
    val currentHigh = highWatermark.get()
    newPosition > currentHigh
  }
  
  /**
   * 获取水位线状态
   */
  def getWatermarkStatus: WatermarkStatus = {
    WatermarkStatus(
      current = currentWatermark.get(),
      high = highWatermark.get(),
      low = lowWatermark.get(),
      timestamp = Instant.now()
    )
  }
  
  /**
   * 重置水位线
   */
  def reset(): Unit = {
    currentWatermark.set(0L)
    highWatermark.set(Long.MaxValue)
    lowWatermark.set(0L)
    logger.info("水位线已重置")
  }
  
  /**
   * 根据同步配置初始化水位线
   */
  def initializeFromConfig(config: SyncConfig): Unit = {
    lowWatermark.set(config.lowWatermark)
    highWatermark.set(config.highWatermark)
    currentWatermark.set(config.lowWatermark)
    logger.info(s"根据配置初始化水位线: low=${config.lowWatermark}, high=${config.highWatermark}")
  }
}

/**
 * 水位线状态
 */
case class WatermarkStatus(
  current: Long,
  high: Long,
  low: Long,
  timestamp: Instant
) extends CborSerializable

/**
 * 水位线事件处理器
 */
object WatermarkEventHandler {
  
  /**
   * 处理水位线事件
   */
  def handleWatermarkEvent(
    event: WatermarkEvent,
    manager: WatermarkManager
  ): Unit = {
    event.source match {
      case "high" => 
        if (manager.canAdvanceHighWatermark(event.watermark)) {
          manager.updateHighWatermark(event.watermark)
        } else {
          // 记录警告，但不能推进高水位线
          println(s"警告: 无法推进高水位线到 ${event.watermark}，当前水位线: ${manager.getCurrentWatermark}")
        }
      case "low" => 
        manager.updateLowWatermark(event.watermark)
      case "current" => 
        manager.advanceCurrentWatermark(event.watermark)
      case _ => 
        println(s"未知的水位线事件源: ${event.source}")
    }
  }
}
