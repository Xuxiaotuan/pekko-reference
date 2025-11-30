package cn.xuyinyin.magic.datafusion

import spray.json._
import com.typesafe.scalalogging.Logger

import scala.util.{Failure, Success, Try}

/**
 * JSON数据转换器
 * 
 * 提供JSON格式的数据转换功能，替代原来的Arrow转换器
 * 由于现在使用HTTP协议，不再需要Arrow格式转换
 */
object ArrowConverter {
  
  private val logger = Logger(getClass)
  
  /**
   * 从JSON字符串推断Schema信息
   *
   * @param jsonLines JSON行序列
   * @return Schema信息的Map
   */
  def inferSchema(jsonLines: Seq[String]): Map[String, String] = {
    logger.debug(s"Inferring schema from ${jsonLines.size} JSON lines")
    
    if (jsonLines.isEmpty) {
      throw new IllegalArgumentException("Cannot infer schema from empty data")
    }
    
    // 解析第一行JSON获取字段类型
    val firstJson = jsonLines.head.parseJson.asJsObject
    firstJson.fields.map { case (name, value) =>
      val dataType = inferDataType(value)
      name -> dataType
    }
  }
  
  /**
   * 从JSON值推断数据类型
   */
  private def inferDataType(value: JsValue): String = value match {
    case JsNull => "string" // 默认为字符串
    case _: JsBoolean => "boolean"
    case JsNumber(n) =>
      if (n.isValidInt) {
        "int32"
      } else if (n.isValidLong) {
        "int64"
      } else {
        "double"
      }
    case _: JsString => "string"
    case _: JsArray => "array"
    case _: JsObject => "object"
  }
  
  /**
   * 验证JSON数据格式
   *
   * @param jsonLines JSON行序列
   * @return 验证结果
   */
  def validateJsonData(jsonLines: Seq[String]): Try[Unit] = {
    Try {
      jsonLines.foreach { line =>
        line.parseJson // 验证JSON格式
      }
      logger.debug(s"Validated ${jsonLines.size} JSON lines")
    }
  }
  
  /**
   * 转换查询结果为标准格式
   *
   * @param queryResponse DataFusion查询响应
   * @return 标准化的JSON数据
   */
  def convertQueryResult(queryResponse: QueryResponse): Seq[Map[String, JsValue]] = {
    queryResponse.data.map(_.toMap)
  }
  
  /**
   * 将Map数据转换为JSON字符串
   *
   * @param data 数据Map序列
   * @return JSON字符串序列
   */
  def mapToJsonLines(data: Seq[Map[String, JsValue]]): Seq[String] = {
    data.map { row =>
      JsObject(row).compactPrint
    }
  }
  
  /**
   * 从JSON字符串解析为Map数据
   *
   * @param jsonLines JSON字符串序列
   * @return 数据Map序列
   */
  def jsonLinesToMap(jsonLines: Seq[String]): Seq[Map[String, JsValue]] = {
    jsonLines.map { line =>
      line.parseJson.asJsObject.fields
    }
  }
  
  /**
   * 获取数据统计信息
   *
   * @param data 数据序列
   * @return 统计信息
   */
  def getDataStats(data: Seq[Map[String, JsValue]]): DataStats = {
    if (data.isEmpty) {
      DataStats(0, 0, Map.empty)
    } else {
      val rowCount = data.size
      val columnCount = data.head.size
      val columnTypes = data.head.map { case (name, value) =>
        name -> inferDataType(value)
      }
      
      DataStats(rowCount, columnCount, columnTypes)
    }
  }
  
  /**
   * 清理资源（兼容性方法）
   */
  def close(): Unit = {
    logger.debug("JSON converter closed (no resources to clean)")
  }
}

/**
 * 数据统计信息
 */
case class DataStats(
  rowCount: Int,
  columnCount: Int,
  columnTypes: Map[String, String]
) {
  override def toString: String = {
    s"DataStats(rows=$rowCount, columns=$columnCount, types=${columnTypes.size})"
  }
}