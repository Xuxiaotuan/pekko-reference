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
  
  // 所有Source节点实例（懒加载）
  private lazy val sourceInstances: Map[String, NodeSource] = {
    val sources = List(
      new RandomNumbersSource(),
      new SequenceSource(),
      new CsvSource(),
      new TextSource(),
      new MemorySource(),
      new SqlSource(),
      new KafkaSource(),
      new MySQLSource()
    )
    sources.map(s => s.nodeType -> s).toMap
  }
  
  // 所有Sink节点实例（懒加载）
  private lazy val sinkInstances: Map[String, NodeSink] = {
    val sinks = List(
      new ConsoleLogSink(),
      new FileTextSink(),
      new MySQLSink()
    )
    sinks.map(s => s.nodeType -> s).toMap
  }
  
  /**
   * 获取所有Source节点
   */
  def getSources: Map[String, NodeSource] = sourceInstances
  
  /**
   * 获取所有Sink节点
   */
  def getSinks: Map[String, NodeSink] = sinkInstances
  
  /**
   * 根据类型查找Source
   */
  def findSource(nodeType: String): Option[NodeSource] = {
    sourceInstances.get(nodeType)
  }
  
  /**
   * 根据类型查找Sink
   */
  def findSink(nodeType: String): Option[NodeSink] = {
    sinkInstances.get(nodeType)
  }
  
  /**
   * 获取所有支持的Source类型
   */
  def supportedSourceTypes: Set[String] = sourceInstances.keySet
  
  /**
   * 获取所有支持的Sink类型
   */
  def supportedSinkTypes: Set[String] = sinkInstances.keySet
}
