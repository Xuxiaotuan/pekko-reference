package cn.xuyinyin.magic.workflow.engine.registry

import cn.xuyinyin.magic.workflow.nodes.base.{NodeSource, NodeSink}
import cn.xuyinyin.magic.workflow.nodes.sources._
import cn.xuyinyin.magic.workflow.nodes.sinks._

import scala.collection.mutable

/**
 * 节点注册中心
 * 
 * 管理所有可用的节点实现
 * 支持动态注册和加载连接器
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-15
 */
object NodeRegistry {
  
  // 可变的注册表，支持运行时动态注册
  private val dynamicSources = mutable.Map[String, NodeSource]()
  private val dynamicSinks = mutable.Map[String, NodeSink]()
  
  // 内置Source节点实例（懒加载）
  private lazy val builtinSourceInstances: Map[String, NodeSource] = {
    val sources = List(
      new RandomNumbersSource(),
      new SequenceSource(),
      new MemorySource(),
      new KafkaSource(),
      new MySQLSnapshotSourceNode(),
      new MySQLSourceNode()  // MySQL连接器（真实JDBC实现）
      // SqlSource已移除（DataFusion依赖）
    )
    sources.map(s => s.nodeType -> s).toMap
  }
  
  // 内置Sink节点实例（懒加载）
  private lazy val builtinSinkInstances: Map[String, NodeSink] = {
    val sinks = List(
      new ConsoleLogSink(),
      new FileTextSink(),
      new MySQLSinkNode()  // MySQL连接器（真实JDBC实现）
    )
    sinks.map(s => s.nodeType -> s).toMap
  }
  
  /**
   * 动态注册Source节点
   * 用于在运行时注册pekko-connectors中的连接器
   */
  def registerSource(source: NodeSource): Unit = {
    dynamicSources.put(source.nodeType, source)
  }

  /**
   * 仅在当前注册实例与期望实例匹配时移除运行时数据源。
   */
  private[engine] def unregisterSource(nodeType: String, expected: NodeSource): Unit = {
    dynamicSources.get(nodeType)
      .filter(current => current eq expected)
      .foreach(_ => dynamicSources.remove(nodeType))
  }
  
  /**
   * 动态注册Sink节点
   * 用于在运行时注册pekko-connectors中的连接器
   */
  def registerSink(sink: NodeSink): Unit = {
    dynamicSinks.put(sink.nodeType, sink)
  }

  /**
   * 仅在当前注册实例与期望实例匹配时移除运行时 Sink。
   */
  private[engine] def unregisterSink(nodeType: String, expected: NodeSink): Unit = {
    dynamicSinks.get(nodeType)
      .filter(current => current eq expected)
      .foreach(_ => dynamicSinks.remove(nodeType))
  }
  
  // 合并内置和动态注册的节点（动态注册的优先级更高）
  private def sourceInstances: Map[String, NodeSource] = {
    builtinSourceInstances ++ dynamicSources
  }
  
  private def sinkInstances: Map[String, NodeSink] = {
    builtinSinkInstances ++ dynamicSinks
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
