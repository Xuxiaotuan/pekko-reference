package cn.xuyinyin.magic.cdc

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import cn.xuyinyin.magic.cdc.models.{SyncConfig, SyncStatus, FULL_SYNC, INCREMENTAL_SYNC}
import cn.xuyinyin.magic.cdc.simulator.CdcDataSimulator
import cn.xuyinyin.magic.cdc.sync.{SimpleFullSyncProcessor, SimpleIncrementalSyncProcessor}
import cn.xuyinyin.magic.cdc.watermark.WatermarkManager
import com.typesafe.scalalogging.LazyLogging

/**
 * 基础CDC演示程序
 * 不使用复杂的Actor和Stream，专注于核心CDC概念演示
 */
object BasicCdcDemo extends LazyLogging {
  
  def main(args: Array[String]): Unit = {
    logger.info("=== 基础CDC演示开始 ===")
    
    // 演示水位线算法
    demonstrateWatermarkAlgorithm()
    
    // 演示CDC事件生成
    demonstrateCdcEventGeneration()
    
    // 演示全量到增量同步流程
    demonstrateFullToIncrementalSync()
    
    logger.info("=== 基础CDC演示完成 ===")
  }
  
  /**
   * 演示水位线算法
   */
  private def demonstrateWatermarkAlgorithm(): Unit = {
    logger.info("=== 水位线算法演示 ===")
    
    val watermarkManager = new WatermarkManager()
    
    // 初始状态
    logger.info("初始水位线状态:")
    printWatermarkStatus(watermarkManager)
    
    // 设置初始范围
    watermarkManager.updateLowWatermark(100L)
    watermarkManager.updateHighWatermark(1000L)
    watermarkManager.advanceCurrentWatermark(200L)
    
    logger.info("设置初始范围后的状态:")
    printWatermarkStatus(watermarkManager)
    
    // 演示安全范围检查
    logger.info("安全范围检查:")
    logger.info(s"位置150在安全范围内: ${watermarkManager.isInSafeRange(150L)}")
    logger.info(s"位置50在安全范围内: ${watermarkManager.isInSafeRange(50L)}")
    logger.info(s"位置1500在安全范围内: ${watermarkManager.isInSafeRange(1500L)}")
    
    // 演示水位线推进
    watermarkManager.advanceCurrentWatermark(500L)
    logger.info("推进当前水位线到500后的状态:")
    printWatermarkStatus(watermarkManager)
    
    // 演示高水位线推进
    if (watermarkManager.canAdvanceHighWatermark(500L)) {
      watermarkManager.updateHighWatermark(500L)
      logger.info("推进高水位线到500后的状态:")
      printWatermarkStatus(watermarkManager)
    }
    
    logger.info("水位线算法演示完成\n")
  }
  
  /**
   * 演示CDC事件生成
   */
  private def demonstrateCdcEventGeneration(): Unit = {
    logger.info("=== CDC事件生成演示 ===")
    
    val simulator = new CdcDataSimulator()
    
    // 生成一些CDC事件
    logger.info("生成用户表CDC事件:")
    for (i <- 1 to 5) {
      val event = simulator.generateUserCdcEvent()
      logger.info(s"事件$i: ${event.eventType} - ${event.tableName} - 位置: ${event.binlogPosition}")
      logger.info(s"  时间戳: ${event.timestamp}")
      if (event.beforeData.isDefined) {
        logger.info(s"  更新前数据: ${event.beforeData.get}")
      }
      if (event.afterData.isDefined) {
        logger.info(s"  更新后数据: ${event.afterData.get}")
      }
    }
    
    logger.info("生成订单表CDC事件:")
    for (i <- 1 to 5) {
      val event = simulator.generateOrderCdcEvent()
      logger.info(s"事件$i: ${event.eventType} - ${event.tableName} - 位置: ${event.binlogPosition}")
      logger.info(s"  时间戳: ${event.timestamp}")
      if (event.beforeData.isDefined) {
        logger.info(s"  更新前数据: ${event.beforeData.get}")
      }
      if (event.afterData.isDefined) {
        logger.info(s"  更新后数据: ${event.afterData.get}")
      }
    }
    
    // 显示数据统计
    val stats = simulator.getDataStats
    logger.info(s"数据统计: $stats")
    logger.info("CDC事件生成演示完成\n")
  }
  
  /**
   * 演示全量到增量同步流程
   */
  private def demonstrateFullToIncrementalSync(): Unit = {
    logger.info("=== 全量到增量同步流程演示 ===")
    
    val simulator = new CdcDataSimulator()
    val watermarkManager = new WatermarkManager()
    
    // 创建同步配置
    val config = SyncConfig(
      tableName = "users",
      batchSize = 10,
      highWatermark = 100L,
      lowWatermark = 0L,
      syncInterval = 1000L,
      status = FULL_SYNC
    )
    
    logger.info(s"同步配置: $config")
    
    // 初始化水位线
    watermarkManager.initializeFromConfig(config)
    logger.info("初始化水位线:")
    printWatermarkStatus(watermarkManager)
    
    // 模拟全量同步
    logger.info("开始全量同步...")
    simulateFullSync(config, watermarkManager)
    
    // 切换到增量同步
    logger.info("切换到增量同步模式...")
    val incrementalConfig = config.copy(status = INCREMENTAL_SYNC)
    simulateIncrementalSync(incrementalConfig, simulator, watermarkManager)
    
    logger.info("全量到增量同步流程演示完成\n")
  }
  
  /**
   * 模拟全量同步
   */
  private def simulateFullSync(config: SyncConfig, watermarkManager: WatermarkManager): Unit = {
    logger.info("执行全量数据同步...")
    
    implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global
    val simulator = new CdcDataSimulator()
    val fullSyncProcessor = new SimpleFullSyncProcessor(simulator, watermarkManager)
    
    // 执行全量同步
    val result = fullSyncProcessor.performFullSync(config)
    
    // 等待同步完成
    scala.concurrent.Await.result(result, scala.concurrent.duration.Duration.Inf)
    
    logger.info("全量同步完成:")
    printWatermarkStatus(watermarkManager)
  }
  
  /**
   * 模拟增量同步
   */
  private def simulateIncrementalSync(
    config: SyncConfig,
    simulator: CdcDataSimulator,
    watermarkManager: WatermarkManager
  ): Unit = {
    logger.info("开始增量同步...")
    
    implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global
    val incrementalSyncProcessor = new SimpleIncrementalSyncProcessor(simulator, watermarkManager)
    
    // 启动增量同步
    val future = incrementalSyncProcessor.startIncrementalSync(config)
    
    // 运行一段时间后停止
    Thread.sleep(5000) // 运行5秒
    incrementalSyncProcessor.stopIncrementalSync()
    
    // 等待停止完成
    scala.concurrent.Await.result(future, scala.concurrent.duration.Duration.Inf)
    
    logger.info("增量同步演示完成:")
    printWatermarkStatus(watermarkManager)
  }
  
  /**
   * 打印水位线状态
   */
  private def printWatermarkStatus(watermarkManager: WatermarkManager): Unit = {
    val status = watermarkManager.getWatermarkStatus
    logger.info(s"  当前水位线: ${status.current}")
    logger.info(s"  高水位线: ${status.high}")
    logger.info(s"  低水位线: ${status.low}")
    logger.info(s"  时间戳: ${status.timestamp}")
  }
}

/**
 * 水位线算法详细演示
 */
object WatermarkAlgorithmDemo extends LazyLogging {
  
  def main(args: Array[String]): Unit = {
    logger.info("=== 水位线算法详细演示 ===")
    
    val watermarkManager = new WatermarkManager()
    
    // 场景1: 初始状态
    logger.info("场景1: 初始状态")
    printWatermarkStatus(watermarkManager)
    
    // 场景2: 设置处理范围
    logger.info("场景2: 设置处理范围 [100, 1000]")
    watermarkManager.updateLowWatermark(100L)
    watermarkManager.updateHighWatermark(1000L)
    printWatermarkStatus(watermarkManager)
    
    // 场景3: 开始处理数据
    logger.info("场景3: 开始处理数据，推进当前水位线")
    for (pos <- 100L to 300L by 50L) {
      watermarkManager.advanceCurrentWatermark(pos)
      logger.info(s"  处理到位置 $pos")
      printWatermarkStatus(watermarkManager)
    }
    
    // 场景4: 推进高水位线
    logger.info("场景4: 推进高水位线")
    if (watermarkManager.canAdvanceHighWatermark(300L)) {
      watermarkManager.updateHighWatermark(300L)
      logger.info("  成功推进高水位线到300")
    }
    printWatermarkStatus(watermarkManager)
    
    // 场景5: 安全范围检查
    logger.info("场景5: 安全范围检查")
    val testPositions = List(50L, 150L, 250L, 350L, 500L)
    testPositions.foreach { pos =>
      val isSafe = watermarkManager.isInSafeRange(pos)
      logger.info(s"  位置 $pos 在安全范围内: $isSafe")
    }
    
    // 场景6: 重置水位线
    logger.info("场景6: 重置水位线")
    watermarkManager.reset()
    printWatermarkStatus(watermarkManager)
    
    logger.info("水位线算法详细演示完成")
  }
  
  private def printWatermarkStatus(watermarkManager: WatermarkManager): Unit = {
    val status = watermarkManager.getWatermarkStatus
    logger.info(s"  当前水位线: ${status.current}")
    logger.info(s"  高水位线: ${status.high}")
    logger.info(s"  低水位线: ${status.low}")
    logger.info(s"  时间戳: ${status.timestamp}")
  }
}
