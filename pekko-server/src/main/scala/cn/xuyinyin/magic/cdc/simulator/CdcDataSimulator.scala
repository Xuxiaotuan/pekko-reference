package cn.xuyinyin.magic.cdc.simulator

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import scala.util.Random
import cn.xuyinyin.magic.cdc.models.{CdcEvent, CdcEventType, User, Order, INSERT, UPDATE, DELETE}
import cn.xuyinyin.magic.common.CborSerializable
import com.typesafe.scalalogging.LazyLogging

/**
 * CDC数据模拟器
 * 模拟MySQL binlog事件，生成CDC数据流
 */
class CdcDataSimulator extends LazyLogging {
  
  private val eventIdGenerator = new AtomicLong(1L)
  private val binlogPositionGenerator = new AtomicLong(1L)
  private val random = new Random()
  
  // 模拟的用户数据
  private val users = scala.collection.mutable.Map[Long, User]()
  private val orders = scala.collection.mutable.Map[Long, Order]()
  
  // 初始化一些测试数据
  initializeTestData()
  
  /**
   * 初始化测试数据
   */
  private def initializeTestData(): Unit = {
    // 初始化用户数据
    for (i <- 1 to 10) {
      val user = User(
        id = i,
        name = s"用户$i",
        email = s"user$i@example.com",
        age = 20 + random.nextInt(50),
        createdAt = Instant.now().minusSeconds(random.nextInt(86400)),
        updatedAt = Instant.now()
      )
      users.put(i, user)
    }
    
    // 初始化订单数据
    for (i <- 1 to 20) {
      val order = Order(
        id = i,
        userId = 1 + random.nextInt(10),
        productName = s"产品$i",
        amount = BigDecimal(10 + random.nextInt(1000)),
        status = if (random.nextBoolean()) "已完成" else "进行中",
        createdAt = Instant.now().minusSeconds(random.nextInt(86400)),
        updatedAt = Instant.now()
      )
      orders.put(i, order)
    }
    
    logger.info(s"初始化测试数据完成: ${users.size}个用户, ${orders.size}个订单")
  }
  
  /**
   * 生成用户表的CDC事件
   */
  def generateUserCdcEvent(): CdcEvent = {
    val eventType = random.nextInt(3) match {
      case 0 => INSERT
      case 1 => UPDATE
      case 2 => DELETE
    }
    
    eventType match {
      case INSERT =>
        val newUserId = users.keys.max + 1
        val newUser = User(
          id = newUserId,
          name = s"新用户$newUserId",
          email = s"newuser$newUserId@example.com",
          age = 20 + random.nextInt(50),
          createdAt = Instant.now(),
          updatedAt = Instant.now()
        )
        users.put(newUserId, newUser)
        createCdcEvent("users", INSERT, None, Some(userToMap(newUser)))
        
      case UPDATE =>
        val userId = users.keys.toSeq(random.nextInt(users.size))
        val existingUser = users(userId)
        val updatedUser = existingUser.copy(
          name = s"更新用户$userId",
          age = existingUser.age + random.nextInt(10) - 5,
          updatedAt = Instant.now()
        )
        users.put(userId, updatedUser)
        createCdcEvent("users", UPDATE, Some(userToMap(existingUser)), Some(userToMap(updatedUser)))
        
      case DELETE =>
        val userId = users.keys.toSeq(random.nextInt(users.size))
        val deletedUser = users.remove(userId).get
        createCdcEvent("users", DELETE, Some(userToMap(deletedUser)), None)
    }
  }
  
  /**
   * 生成订单表的CDC事件
   */
  def generateOrderCdcEvent(): CdcEvent = {
    val eventType = random.nextInt(3) match {
      case 0 => INSERT
      case 1 => UPDATE
      case 2 => DELETE
    }
    
    eventType match {
      case INSERT =>
        val newOrderId = orders.keys.max + 1
        val newOrder = Order(
          id = newOrderId,
          userId = 1 + random.nextInt(10),
          productName = s"新产品$newOrderId",
          amount = BigDecimal(10 + random.nextInt(1000)),
          status = "进行中",
          createdAt = Instant.now(),
          updatedAt = Instant.now()
        )
        orders.put(newOrderId, newOrder)
        createCdcEvent("orders", INSERT, None, Some(orderToMap(newOrder)))
        
      case UPDATE =>
        val orderId = orders.keys.toSeq(random.nextInt(orders.size))
        val existingOrder = orders(orderId)
        val updatedOrder = existingOrder.copy(
          status = if (existingOrder.status == "进行中") "已完成" else "进行中",
          amount = existingOrder.amount + BigDecimal(random.nextInt(100) - 50),
          updatedAt = Instant.now()
        )
        orders.put(orderId, updatedOrder)
        createCdcEvent("orders", UPDATE, Some(orderToMap(existingOrder)), Some(orderToMap(updatedOrder)))
        
      case DELETE =>
        val orderId = orders.keys.toSeq(random.nextInt(orders.size))
        val deletedOrder = orders.remove(orderId).get
        createCdcEvent("orders", DELETE, Some(orderToMap(deletedOrder)), None)
    }
  }
  
  /**
   * 随机生成CDC事件
   */
  def generateRandomCdcEvent(): CdcEvent = {
    random.nextInt(2) match {
      case 0 => generateUserCdcEvent()
      case 1 => generateOrderCdcEvent()
    }
  }
  
  /**
   * 创建CDC事件
   */
  private def createCdcEvent(
    tableName: String,
    eventType: CdcEventType,
    beforeData: Option[Map[String, Any]],
    afterData: Option[Map[String, Any]]
  ): CdcEvent = {
    CdcEvent(
      eventId = s"event_${eventIdGenerator.getAndIncrement()}",
      tableName = tableName,
      eventType = eventType,
      timestamp = Instant.now(),
      beforeData = beforeData,
      afterData = afterData,
      binlogPosition = binlogPositionGenerator.getAndIncrement()
    )
  }
  
  /**
   * 将User对象转换为Map
   */
  private def userToMap(user: User): Map[String, Any] = {
    Map(
      "id" -> user.id,
      "name" -> user.name,
      "email" -> user.email,
      "age" -> user.age,
      "created_at" -> user.createdAt.toString,
      "updated_at" -> user.updatedAt.toString
    )
  }
  
  /**
   * 将Order对象转换为Map
   */
  private def orderToMap(order: Order): Map[String, Any] = {
    Map(
      "id" -> order.id,
      "user_id" -> order.userId,
      "product_name" -> order.productName,
      "amount" -> order.amount.toString,
      "status" -> order.status,
      "created_at" -> order.createdAt.toString,
      "updated_at" -> order.updatedAt.toString
    )
  }
  
  /**
   * 获取当前数据统计
   */
  def getDataStats: DataStats = {
    DataStats(
      userCount = users.size,
      orderCount = orders.size,
      lastEventId = eventIdGenerator.get() - 1,
      lastBinlogPosition = binlogPositionGenerator.get() - 1
    )
  }
  
  /**
   * 重置模拟器
   */
  def reset(): Unit = {
    users.clear()
    orders.clear()
    eventIdGenerator.set(1L)
    binlogPositionGenerator.set(1L)
    initializeTestData()
    logger.info("CDC数据模拟器已重置")
  }

  /**
   * 将binlog位置推进到不小于给定的最小位置
   */
  def advanceBinlogPositionTo(minPosition: Long): Unit = {
    val before = binlogPositionGenerator.get()
    if (before < minPosition) binlogPositionGenerator.set(minPosition)
    val after = binlogPositionGenerator.get()
    logger.info(s"模拟器binlog位置推进: $before -> $after")
  }
}

/**
 * 数据统计信息
 */
case class DataStats(
  userCount: Int,
  orderCount: Int,
  lastEventId: Long,
  lastBinlogPosition: Long
) extends CborSerializable
