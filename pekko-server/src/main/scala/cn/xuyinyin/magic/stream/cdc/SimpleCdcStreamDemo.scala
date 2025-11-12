package cn.xuyinyin.magic.stream.cdc

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import org.apache.pekko.stream.{Materializer, OverflowStrategy}
import cn.xuyinyin.magic.cdc.models.{CdcEvent, FULL_SYNC, INCREMENTAL_SYNC, SyncConfig, SyncResult}
import cn.xuyinyin.magic.cdc.simulator.CdcDataSimulator
import cn.xuyinyin.magic.cdc.watermark.WatermarkManager
import com.typesafe.scalalogging.LazyLogging
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors

/**
 * 简化的基于Pekko Stream的CDC演示
 * 专注于展示流式处理和高低水位线算法
 */
object SimpleCdcStreamDemo extends LazyLogging {
  
  def main(args: Array[String]): Unit = {
    logger.info("=== 简化Pekko Stream CDC演示开始 ===")
    
    // 创建组件
    val simulator = new CdcDataSimulator()
    val watermarkManager = new WatermarkManager()
    
    // 先全量，再增量
    demonstrateFullThenIncremental(simulator, watermarkManager)
    
    logger.info("=== 简化Pekko Stream CDC演示完成 ===")
  }
  
  /**
   * 演示流式CDC处理
   */
  private def demonstrateFullThenIncremental(
    simulator: CdcDataSimulator,
    watermarkManager: WatermarkManager
  ): Unit = {
    implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "CdcStreamSystem")
    implicit val mat: Materializer = Materializer(system)
    implicit val ec: ExecutionContext = system.executionContext

    logger.info("开始全量->增量CDC处理演示...")
    
    // 创建同步配置
    val fullConfig = SyncConfig(
      tableName = "users",
      batchSize = 50,
      highWatermark = 100L,
      lowWatermark = 0L,
      syncInterval = 1000L,
      status = FULL_SYNC
    )
    
    // 初始化水位线（按全量）
    watermarkManager.initializeFromConfig(fullConfig)
    logger.info("初始化水位线:")
    printWatermarkStatus(watermarkManager)
    
    // 执行全量阶段（模拟：推进到目标高水位100，并让模拟器从该位点之后产生增量）
    watermarkManager.updateHighWatermark(100L)
    watermarkManager.advanceCurrentWatermark(100L)
    simulator.advanceBinlogPositionTo(101L)
    logger.info("全量阶段完成(边界=100，增量起点=101):")
    printWatermarkStatus(watermarkManager)
    
    // 切换增量配置
    val incConfig = fullConfig.copy(status = INCREMENTAL_SYNC)
    
    // 为增量阶段放开高水位线（允许新位点不断进入处理范围）
    watermarkManager.updateHighWatermark(Long.MaxValue)
    logger.info("切换到增量阶段: 放开高水位线为Long.MaxValue，允许持续消费新事件")

    // 创建CDC事件流（增量）
    val cdcEventStream = createCdcEventStream(simulator, incConfig)
    
    // 处理CDC事件流
    val processingResult = cdcEventStream
      .via(validateEventFlow(incConfig, watermarkManager))
      .via(processEventFlow(incConfig))
      .via(updateWatermarkFlow(watermarkManager))
      .via(monitorFlow())
      .runWith(Sink.foreach { result =>
        logger.info(s"[增量] 已处理位点=${result.lastProcessedPosition} (current=${watermarkManager.getCurrentWatermark}, high=${watermarkManager.getHighWatermark})")
      })
    
    // 运行一段时间后停止
    Thread.sleep(10000) // 增量运行10秒
    
    logger.info("流式CDC处理演示完成:")
    printWatermarkStatus(watermarkManager)
    
    system.terminate()
  }
  
  /**
   * 创建CDC事件流
   */
  private def createCdcEventStream(
    simulator: CdcDataSimulator,
    config: SyncConfig
  ): org.apache.pekko.stream.scaladsl.Source[CdcEvent, org.apache.pekko.NotUsed] = {
    Source.tick(0.seconds, config.syncInterval.millis, ())
      .map(_ => generateCdcEvent(simulator, config.tableName))
      .filter(_.isDefined)
      .map(_.get)
      .buffer(100, OverflowStrategy.backpressure)
      .mapMaterializedValue(_ => org.apache.pekko.NotUsed)
  }
  
  /**
   * 生成CDC事件
   */
  private def generateCdcEvent(simulator: CdcDataSimulator, tableName: String): Option[CdcEvent] = {
    tableName match {
      case "users" => Some(simulator.generateUserCdcEvent())
      case "orders" => Some(simulator.generateOrderCdcEvent())
      case _ => 
        logger.warn(s"未知的表名: $tableName")
        None
    }
  }
  
  /**
   * 事件验证流
   */
  private def validateEventFlow(
    config: SyncConfig,
    watermarkManager: WatermarkManager
  ): Flow[CdcEvent, CdcEvent, org.apache.pekko.NotUsed] = {
    Flow[CdcEvent]
      .filter { event =>
        // 检查事件是否在安全的水位线范围内
        val isValid = watermarkManager.isInSafeRange(event.binlogPosition)
        if (!isValid) {
          val status = watermarkManager.getWatermarkStatus
          logger.warn(s"事件超出安全范围，跳过: id=${event.eventId}, pos=${event.binlogPosition}, current=${status.current}, high=${status.high}")
        } else {
          val status = watermarkManager.getWatermarkStatus
          logger.info(s"处理CDC事件: id=${event.eventId}, pos=${event.binlogPosition}, current=${status.current}, high=${status.high}")
        }
        isValid
      }
      .filter { event =>
        // 检查事件是否属于目标表
        val isTargetTable = event.tableName == config.tableName
        if (!isTargetTable) {
          logger.warn(s"事件不属于目标表，跳过处理: eventId=${event.eventId}, table=${event.tableName}")
        }
        isTargetTable
      }
  }
  
  /**
   * 事件处理流
   */
  private def processEventFlow(config: SyncConfig): Flow[CdcEvent, SyncResult, org.apache.pekko.NotUsed] = {
    Flow[CdcEvent]
      .map { event =>
        processCdcEvent(event, config)
      }
  }
  
  /**
   * 水位线更新流
   */
  private def updateWatermarkFlow(watermarkManager: WatermarkManager): Flow[SyncResult, SyncResult, org.apache.pekko.NotUsed] = {
    Flow[SyncResult]
      .map { result =>
        // 更新当前水位线
        watermarkManager.advanceCurrentWatermark(result.lastProcessedPosition)
        
        // 检查是否可以推进高水位线
        if (watermarkManager.canAdvanceHighWatermark(result.lastProcessedPosition)) {
          watermarkManager.updateHighWatermark(result.lastProcessedPosition)
          logger.debug(s"推进高水位线到: ${result.lastProcessedPosition}")
        }
        
        result
      }
  }
  
  /**
   * 监控流
   */
  private def monitorFlow(): Flow[SyncResult, SyncResult, org.apache.pekko.NotUsed] = {
    Flow[SyncResult]
      .map { result =>
        // 记录处理统计
        logger.debug(s"处理CDC事件: 表=${result.tableName}, 成功=${result.successCount}, 失败=${result.errorCount}, 位置=${result.lastProcessedPosition}")
        result
      }
  }
  
  /**
   * 处理单个CDC事件
   */
  private def processCdcEvent(event: CdcEvent, config: SyncConfig): SyncResult = {
    val (ok, err) = event.eventType match {
      case cn.xuyinyin.magic.cdc.models.INSERT => processInsertEvent(event, config); (1, 0)
      case cn.xuyinyin.magic.cdc.models.UPDATE => processUpdateEvent(event, config); (1, 0)
      case cn.xuyinyin.magic.cdc.models.DELETE => processDeleteEvent(event, config); (1, 0)
    }
    SyncResult(
      tableName = config.tableName,
      processedCount = 1,
      successCount = ok,
      errorCount = err,
      lastProcessedPosition = event.binlogPosition,
      timestamp = Instant.now()
    )
  }
  
  /**
   * 处理INSERT事件
   */
  private def processInsertEvent(event: CdcEvent, config: SyncConfig): Unit = {
    event.afterData match {
      case Some(data) =>
        logger.info(s"处理INSERT事件: 表=${event.tableName}, 数据=$data")
        simulateDataProcessing(data, "INSERT")
      case None =>
        logger.warn(s"INSERT事件缺少afterData: eventId=${event.eventId}")
    }
  }
  
  /**
   * 处理UPDATE事件
   */
  private def processUpdateEvent(event: CdcEvent, config: SyncConfig): Unit = {
    (event.beforeData, event.afterData) match {
      case (Some(before), Some(after)) =>
        logger.info(s"处理UPDATE事件: 表=${event.tableName}, 更新前=$before, 更新后=$after")
        simulateDataProcessing(after, "UPDATE")
      case _ =>
        logger.warn(s"UPDATE事件数据不完整: eventId=${event.eventId}")
    }
  }
  
  /**
   * 处理DELETE事件
   */
  private def processDeleteEvent(event: CdcEvent, config: SyncConfig): Unit = {
    event.beforeData match {
      case Some(data) =>
        logger.info(s"处理DELETE事件: 表=${event.tableName}, 数据=$data")
        simulateDataProcessing(data, "DELETE")
      case None =>
        logger.warn(s"DELETE事件缺少beforeData: eventId=${event.eventId}")
    }
  }
  
  /**
   * 模拟数据处理
   */
  private def simulateDataProcessing(data: Map[String, Any], operation: String): Unit = {
    // 模拟数据处理延迟
    Thread.sleep(10)
    logger.debug(s"模拟数据处理: 操作=$operation, 数据=$data")
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
 * 水位线算法流演示
 */
object WatermarkStreamAlgorithmDemo extends LazyLogging {
  
  def main(args: Array[String]): Unit = {
    logger.info("=== 水位线算法流演示开始 ===")
    
    val watermarkManager = new WatermarkManager()
    
    // 演示水位线操作
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
    
    logger.info("水位线算法流演示完成")
  }
  
  private def printWatermarkStatus(watermarkManager: WatermarkManager): Unit = {
    val status = watermarkManager.getWatermarkStatus
    logger.info(s"  当前水位线: ${status.current}")
    logger.info(s"  高水位线: ${status.high}")
    logger.info(s"  低水位线: ${status.low}")
    logger.info(s"  时间戳: ${status.timestamp}")
  }
}
