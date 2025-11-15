package cn.xuyinyin.magic.workflow.engine.executors

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{FileIO, Framing, Source}
import org.apache.pekko.util.ByteString
import spray.json._
import spray.json.DefaultJsonProtocol._

import java.nio.file.Paths

/**
 * Source节点执行器
 * 
 * 处理所有数据源类型节点
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-15
 */
class SourceExecutor extends NodeExecutor {
  
  override def supportedTypes: Set[String] = Set(
    "file.csv",
    "file.text",
    "memory.collection",
    "random.numbers",
    "sequence.numbers",
    "sql.query",        // 从 SqlTaskExecutor 迁移
    "kafka.consumer"    // 从 KafkaTaskExecutor 迁移
  )
  
  /**
   * 创建Source
   */
  def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = {
    node.nodeType match {
      case "file.csv" =>
        createCsvSource(node, onLog)
      
      case "file.text" =>
        createTextSource(node, onLog)
      
      case "memory.collection" =>
        createMemorySource(node, onLog)
      
      case "random.numbers" =>
        createRandomSource(node, onLog)
      
      case "sequence.numbers" =>
        createSequenceSource(node, onLog)
      
      case "sql.query" =>
        createSqlSource(node, onLog)
      
      case "kafka.consumer" =>
        createKafkaSource(node, onLog)
      
      case _ =>
        throw new IllegalArgumentException(s"不支持的Source类型: ${node.nodeType}")
    }
  }
  
  /**
   * CSV文件数据源
   */
  private def createCsvSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = {
    val path = node.config.fields.get("path").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("CSV source缺少path配置"))
    val delimiter = node.config.fields.get("delimiter").map(_.convertTo[String]).getOrElse(",")
    
    onLog(s"读取CSV文件: $path (分隔符: $delimiter)")
    
    FileIO.fromPath(Paths.get(path))
      .mapMaterializedValue(_ => NotUsed)
      .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 8192, allowTruncation = true))
      .map(_.utf8String)
      .filter(_.nonEmpty)
  }
  
  /**
   * 文本文件数据源
   */
  private def createTextSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = {
    val path = node.config.fields.get("path").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("Text source缺少path配置"))
    
    onLog(s"读取文本文件: $path")
    
    FileIO.fromPath(Paths.get(path))
      .mapMaterializedValue(_ => NotUsed)
      .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 8192, allowTruncation = true))
      .map(_.utf8String)
  }
  
  /**
   * 内存数据源
   */
  private def createMemorySource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = {
    val dataField = node.config.fields.get("data")
      .getOrElse(throw new IllegalArgumentException("Memory source缺少data配置"))
    
    val data = dataField match {
      case JsArray(elements) => elements.map(_.toString).toList
      case JsString(str) => List(str)
      case _ => throw new IllegalArgumentException("Memory source的data必须是数组或字符串")
    }
    
    onLog(s"使用内存数据: ${data.length}条记录")
    Source(data)
  }
  
  /**
   * 随机数数据源
   */
  private def createRandomSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = {
    val count = node.config.fields.get("count").map(_.convertTo[Int]).getOrElse(100)
    val min = node.config.fields.get("min").map(_.convertTo[Int]).getOrElse(1)
    val max = node.config.fields.get("max").map(_.convertTo[Int]).getOrElse(100)
    
    onLog(s"生成随机数: $count 个 (范围: $min-$max)")
    
    val random = new scala.util.Random()
    Source(1 to count)
      .map(_ => random.nextInt(max - min + 1) + min)
      .map(_.toString)
  }
  
  /**
   * 序列数据源
   */
  private def createSequenceSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = {
    val start = node.config.fields.get("start").map(_.convertTo[Int]).getOrElse(1)
    val end = node.config.fields.get("end").map(_.convertTo[Int]).getOrElse(100)
    val step = node.config.fields.get("step").map(_.convertTo[Int]).getOrElse(1)
    
    onLog(s"生成序列: $start 到 $end (步长: $step)")
    
    Source(start to end by step)
      .map(_.toString)
  }
  
  /**
   * SQL查询数据源（从 SqlTaskExecutor 迁移）
   * 
   * 支持执行SQL查询并流式返回结果
   * TODO: 集成真实SQL引擎（DataFusion/Calcite/DuckDB）
   */
  private def createSqlSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = {
    val sql = node.config.fields.get("sql").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("SQL source缺少sql配置"))
    val database = node.config.fields.get("database").map(_.convertTo[String]).getOrElse("default")
    
    onLog(s"执行SQL查询: $database - $sql")
    
    // TODO: 集成真实SQL引擎
    // 当前：模拟数据
    Source(List(
      "id,name,age",
      "1,Alice,25",
      "2,Bob,30",
      "3,Charlie,35"
    ))
  }
  
  /**
   * Kafka消费数据源（从 KafkaTaskExecutor 迁移）
   * 
   * 支持从Kafka topic消费消息
   */
  private def createKafkaSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = {
    val topic = node.config.fields.get("topic").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("Kafka source缺少topic配置"))
    val brokers = node.config.fields.get("brokers").map(_.convertTo[String]).getOrElse("localhost:9092")
    val groupId = node.config.fields.get("groupId").map(_.convertTo[String]).getOrElse("default-group")
    
    onLog(s"从Kafka消费: $topic @ $brokers (group: $groupId)")
    
    // TODO: 集成真实Kafka Consumer
    // 当前：模拟数据
    Source(List(
      "kafka-message-1",
      "kafka-message-2",
      "kafka-message-3"
    ))
  }
}
