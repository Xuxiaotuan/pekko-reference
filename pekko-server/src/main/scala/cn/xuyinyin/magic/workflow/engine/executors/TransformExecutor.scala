package cn.xuyinyin.magic.workflow.engine.executors

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.util.Try

/**
 * Transform节点执行器
 * 
 * 处理所有数据转换类型节点
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-15
 */
class TransformExecutor extends NodeExecutor {
  
  override def supportedTypes: Set[String] = Set(
    "filter",
    "map",
    "distinct",
    "batch",
    "data.clean",      // 从 DataProcessTaskExecutor 迁移
    "data.transform"   // 从 DataProcessTaskExecutor 迁移
  )
  
  /**
   * 创建Transform Flow
   */
  def createTransform(node: WorkflowDSL.Node, onLog: String => Unit): Flow[String, String, NotUsed] = {
    node.nodeType match {
      case "filter" =>
        createFilterFlow(node, onLog)
      
      case "map" =>
        createMapFlow(node, onLog)
      
      case "distinct" =>
        createDistinctFlow(node, onLog)
      
      case "batch" =>
        createBatchFlow(node, onLog)
      
      case _ =>
        throw new IllegalArgumentException(s"不支持的Transform类型: ${node.nodeType}")
    }
  }
  
  /**
   * 过滤Flow
   */
  private def createFilterFlow(node: WorkflowDSL.Node, onLog: String => Unit): Flow[String, String, NotUsed] = {
    val condition = node.config.fields.get("condition").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("Filter缺少condition配置"))
    
    onLog(s"过滤条件: $condition")
    
    Flow[String].filter { row =>
      evaluateCondition(row, condition)
    }
  }
  
  /**
   * 映射Flow
   */
  private def createMapFlow(node: WorkflowDSL.Node, onLog: String => Unit): Flow[String, String, NotUsed] = {
    val expression = node.config.fields.get("expression").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("Map缺少expression配置"))
    
    onLog(s"映射表达式: $expression")
    
    Flow[String].map { row =>
      applyExpression(row, expression)
    }
  }
  
  /**
   * 去重Flow
   */
  private def createDistinctFlow(node: WorkflowDSL.Node, onLog: String => Unit): Flow[String, String, NotUsed] = {
    onLog("去重处理")
    
    Flow[String].statefulMapConcat { () =>
      var seen = Set.empty[String]
      row => {
        if (seen.contains(row)) {
          Nil
        } else {
          seen += row
          List(row)
        }
      }
    }
  }
  
  /**
   * 批处理Flow
   */
  private def createBatchFlow(node: WorkflowDSL.Node, onLog: String => Unit): Flow[String, String, NotUsed] = {
    val batchSize = node.config.fields.get("batchSize").map(_.convertTo[Int])
      .getOrElse(throw new IllegalArgumentException("Batch缺少batchSize配置"))
    
    onLog(s"批处理大小: $batchSize")
    
    Flow[String]
      .grouped(batchSize)
      .mapConcat(batch => batch)
  }
  
  /**
   * 评估条件（简化版）
   */
  private def evaluateCondition(row: String, condition: String): Boolean = {
    Try {
      condition match {
        case c if c.contains(">") =>
          val parts = c.split(">")
          if (parts.length == 2) {
            val value = row.split(",").headOption.getOrElse("0").trim.toDouble
            val threshold = parts(1).trim.toDouble
            value > threshold
          } else false
        
        case c if c.contains("<") =>
          val parts = c.split("<")
          if (parts.length == 2) {
            val value = row.split(",").headOption.getOrElse("0").trim.toDouble
            val threshold = parts(1).trim.toDouble
            value < threshold
          } else false
        
        case c if c.contains("contains") =>
          val searchTerm = c.replace("contains", "").trim.replaceAll("[\"']", "")
          row.contains(searchTerm)
        
        case _ => true
      }
    }.getOrElse(true)
  }
  
  /**
   * 应用表达式（简化版）
   */
  private def applyExpression(row: String, expression: String): String = {
    expression match {
      case "toUpperCase" => row.toUpperCase
      case "toLowerCase" => row.toLowerCase
      case "trim" => row.trim
      case e if e.startsWith("prefix:") =>
        val prefix = e.substring(7)
        s"$prefix$row"
      case _ => row
    }
  }
}
