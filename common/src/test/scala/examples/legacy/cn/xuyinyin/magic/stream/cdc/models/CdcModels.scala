package cn.xuyinyin.magic.stream.cdc.models

import cn.xuyinyin.magic.common.CborSerializable

import java.time.Instant

/**
 * CDC事件类型
 */
sealed trait CdcEventType extends CborSerializable
case object INSERT extends CdcEventType
case object UPDATE extends CdcEventType
case object DELETE extends CdcEventType

/**
 * CDC事件
 */
case class CdcEvent(
  eventId: String,
  tableName: String,
  eventType: CdcEventType,
  timestamp: Instant,
  beforeData: Option[Map[String, Any]] = None,
  afterData: Option[Map[String, Any]] = None,
  binlogPosition: Long = 0L
) extends CborSerializable

/**
 * 水位线事件
 */
case class WatermarkEvent(
  watermark: Long,
  timestamp: Instant,
  source: String
) extends CborSerializable

/**
 * 同步状态
 */
sealed trait SyncStatus extends CborSerializable
case object FULL_SYNC extends SyncStatus
case object INCREMENTAL_SYNC extends SyncStatus
case object PAUSED extends SyncStatus

/**
 * 同步配置
 */
case class SyncConfig(
  tableName: String,
  batchSize: Int = 1000,
  highWatermark: Long = Long.MaxValue,
  lowWatermark: Long = 0L,
  syncInterval: Long = 1000L, // 毫秒
  status: SyncStatus = FULL_SYNC
) extends CborSerializable

/**
 * 同步结果
 */
case class SyncResult(
  tableName: String,
  processedCount: Int,
  successCount: Int,
  errorCount: Int,
  lastProcessedPosition: Long,
  timestamp: Instant
) extends CborSerializable

/**
 * 用户数据模型（示例）
 */
case class User(
  id: Long,
  name: String,
  email: String,
  age: Int,
  createdAt: Instant,
  updatedAt: Instant
) extends CborSerializable

/**
 * 订单数据模型（示例）
 */
case class Order(
  id: Long,
  userId: Long,
  productName: String,
  amount: BigDecimal,
  status: String,
  createdAt: Instant,
  updatedAt: Instant
) extends CborSerializable
