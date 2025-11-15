package cn.xuyinyin.magic.workflow.engine.registry

import cn.xuyinyin.magic.workflow.nodes.base.{NodeSource, NodeSink}
import cn.xuyinyin.magic.workflow.nodes.sources._
import cn.xuyinyin.magic.workflow.nodes.sinks._

import scala.concurrent.ExecutionContext

/**
 * 节点注册中心
 * 
 * 管理所有可用的节点实现
 * 方便添加和查找节点
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-15
 */
object NodeRegistry {
  
  /**
   * 获取所有Source节点
   */
  def getSources: Map[String, NodeSource] = Map(
    "mysql.query" -> new MySQLSource(),
    "file.read" -> new FileSource(),
    // 添加更多...
  )
  
  /**
   * 获取所有Sink节点
   */
  def getSinks(implicit ec: ExecutionContext): Map[String, NodeSink] = Map(
    "mysql.write" -> new MySQLSink(),
    // 添加更多...
  )
  
  /**
   * 根据类型查找Source
   */
  def findSource(nodeType: String): Option[NodeSource] = {
    getSources.get(nodeType)
  }
  
  /**
   * 根据类型查找Sink
   */
  def findSink(nodeType: String)(implicit ec: ExecutionContext): Option[NodeSink] = {
    getSinks.get(nodeType)
  }
  
  /**
   * 获取所有支持的节点类型
   */
  def supportedTypes: Map[String, String] = {
    val sources = getSources.keys.map(_ -> "source")
    val sinks = getSinks(scala.concurrent.ExecutionContext.global).keys.map(_ -> "sink")
    (sources ++ sinks).toMap
  }
}
