package cn.xuyinyin.magic.datafusion

import com.typesafe.scalalogging.Logger
import org.apache.arrow.flight.{FlightClient, FlightDescriptor, Location, Ticket, Criteria}
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.VectorSchemaRoot
import spray.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

// JSON格式定义（用于结果转换）
case class QueryResponse(success: Boolean, data: Vector[Map[String, JsValue]], message: String, execution_time_ms: Long)

/**
 * DataFusion Arrow Flight Client
 * 
 * 连接到DataFusion Service并执行SQL查询，使用Arrow Flight协议
 *
 * @param config 客户端配置
 * @param ec 执行上下文
 */
class DataFusionClient(
  config: FlightClientConfig
)(implicit ec: ExecutionContext) {
  
  private val logger = Logger(getClass)
  
  // Arrow内存分配器
  private val allocator = new RootAllocator(Long.MaxValue)
  
  // Flight连接
  private val location = Location.forGrpcInsecure(config.host, config.port)
  private val client = FlightClient.builder(allocator, location).build()
  
  logger.info(s"DataFusion Arrow Flight Client initialized: ${config.host}:${config.port}")
  
  /**
   * 执行SQL查询
   *
   * @param sql SQL查询语句
   * @return 查询结果
   */
  def executeQuery(sql: String): Future[QueryResponse] = Future {
    logger.info(s"Executing SQL query via Arrow Flight: ${sql.take(100)}")
    
    val startTime = System.currentTimeMillis()
    
    try {
      // 创建Ticket（包含SQL查询）
      val ticket = new Ticket(sql.getBytes("UTF-8"))
      
      // 执行查询并获取数据流
      val stream = client.getStream(ticket)
      
      // 收集所有结果
      val results = scala.collection.mutable.ArrayBuffer[Map[String, JsValue]]()
      
      try {
        while (stream.next()) {
          val root = stream.getRoot
          val rowCount = root.getRowCount
          
          // 转换每一行
          for (rowIndex <- 0 until rowCount) {
            val row = scala.collection.mutable.Map[String, JsValue]()
            
            // 遍历所有列
            root.getFieldVectors.asScala.foreach { vector =>
              val fieldName = vector.getField.getName
              val value = extractValue(vector, rowIndex)
              row(fieldName) = value
            }
            
            results += row.toMap
          }
        }
      } finally {
        stream.close()
      }
      
      val duration = System.currentTimeMillis() - startTime
      logger.info(s"Query completed: ${results.size} rows, ${duration}ms")
      
      QueryResponse(
        success = true,
        data = results.toVector,
        message = s"Query executed successfully: $sql",
        execution_time_ms = duration
      )
    } catch {
      case e: Exception =>
        val duration = System.currentTimeMillis() - startTime
        logger.error(s"Failed to execute query: ${e.getMessage}", e)
        QueryResponse(
          success = false,
          data = Vector.empty,
          message = s"Query failed: ${e.getMessage}",
          execution_time_ms = duration
        )
    }
  }
  
  /**
   * 从Arrow Vector提取值
   */
  private def extractValue(vector: org.apache.arrow.vector.FieldVector, index: Int): JsValue = {
    import org.apache.arrow.vector._
    
    if (vector.isNull(index)) {
      return JsNull
    }
    
    vector match {
      case v: IntVector =>
        JsNumber(v.get(index))
        
      case v: BigIntVector =>
        JsNumber(v.get(index))
        
      case v: Float4Vector =>
        JsNumber(v.get(index).toDouble)
        
      case v: Float8Vector =>
        JsNumber(v.get(index))
        
      case v: VarCharVector =>
        JsString(new String(v.get(index), "UTF-8"))
        
      case v: BitVector =>
        JsBoolean(v.get(index) == 1)
        
      case _ =>
        logger.warn(s"Unsupported vector type: ${vector.getClass.getSimpleName}")
        JsString(vector.getObject(index).toString)
    }
  }
  
  /**
   * 获取查询的Schema信息
   *
   * @param sql SQL查询语句
   * @return Schema信息
   */
  def getQuerySchema(sql: String): Future[QueryResponse] = Future {
    logger.info(s"Getting schema for query: ${sql.take(100)}")
    
    try {
      // 创建FlightDescriptor
      val descriptor = FlightDescriptor.command(sql.getBytes("UTF-8"))
      
      // 获取Schema
      val schemaResult = client.getSchema(descriptor)
      val schema = schemaResult.getSchema
      
      // 转换Schema为JSON
      val schemaInfo = Map(
        "columns" -> JsArray(
          schema.getFields.asScala.map { field =>
            JsObject(
              "name" -> JsString(field.getName),
              "type" -> JsString(field.getType.toString),
              "nullable" -> JsBoolean(field.isNullable)
            )
          }.toVector
        )
      )
      
      logger.info("Schema retrieved successfully")
      
      QueryResponse(
        success = true,
        data = Vector(schemaInfo),
        message = "Schema retrieved successfully",
        execution_time_ms = 0
      )
    } catch {
      case e: Exception =>
        logger.error(s"Failed to get schema: ${e.getMessage}", e)
        QueryResponse(
          success = false,
          data = Vector.empty,
          message = s"Schema request failed: ${e.getMessage}",
          execution_time_ms = 0
        )
    }
  }
  
  /**
   * 健康检查
   *
   * @return 服务是否健康
   */
  def healthCheck(): Future[Boolean] = Future {
    try {
      // 使用listFlights作为健康检查
      val flights = client.listFlights(Criteria.ALL)
      // 只要能获取到flights就认为服务健康
      true
    } catch {
      case e: Exception =>
        logger.warn(s"Health check failed: ${e.getMessage}")
        false
    }
  }
  
  /**
   * 关闭客户端
   */
  def close(): Unit = {
    Try {
      client.close()
      allocator.close()
      logger.info("DataFusion Arrow Flight Client closed")
    } match {
      case Success(_) => // OK
      case Failure(e) => logger.error(s"Error closing client: ${e.getMessage}", e)
    }
  }
}

object DataFusionClient {
  
  /**
   * 创建客户端
   */
  def apply(config: FlightClientConfig)(implicit ec: ExecutionContext): DataFusionClient = {
    new DataFusionClient(config)
  }
  
  /**
   * 使用默认配置创建客户端
   */
  def apply()(implicit ec: ExecutionContext): DataFusionClient = {
    new DataFusionClient(FlightClientConfig.default)
  }
}