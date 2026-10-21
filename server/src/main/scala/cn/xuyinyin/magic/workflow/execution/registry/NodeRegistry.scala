package cn.xuyinyin.magic.workflow.execution.registry

import cn.xuyinyin.magic.workflow.spi.{NodeSource, NodeSink}
import cn.xuyinyin.magic.connectors.builtin._
import cn.xuyinyin.magic.connectors.file._
import cn.xuyinyin.magic.connectors.kafka._
import cn.xuyinyin.magic.connectors.mysql.source._
import cn.xuyinyin.magic.connectors.mysql.sink._

/** Immutable runtime catalog. Adapters are stable; their execution resources are session scoped. */
final class NodeRegistry private (
  val getSources: Map[String, NodeSource],
  val getSinks: Map[String, NodeSink]
) {
  def findSource(nodeType: String): Option[NodeSource] = getSources.get(nodeType)
  def findSink(nodeType: String): Option[NodeSink] = getSinks.get(nodeType)
  def supportedSourceTypes: Set[String] = getSources.keySet
  def supportedSinkTypes: Set[String] = getSinks.keySet
  def withSource(source: NodeSource): NodeRegistry = new NodeRegistry(getSources.updated(source.nodeType, source), getSinks)
  def withSink(sink: NodeSink): NodeRegistry = new NodeRegistry(getSources, getSinks.updated(sink.nodeType, sink))
}

object NodeRegistry {
  def builtins(): NodeRegistry = {
    val sources: List[NodeSource] = List(new RandomNumbersSource(), new SequenceSource(), new MemorySource(),
      new KafkaSource(), new MySQLCdcSourceNode(), new MySQLSnapshotSourceNode(), new MySQLSourceNode())
    val sinks: List[NodeSink] = List(new ConsoleLogSink(), new FileTextSink(), new MySQLCdcApplySinkNode(), new MySQLSinkNode())
    new NodeRegistry(sources.map(s => s.nodeType -> s).toMap, sinks.map(s => s.nodeType -> s).toMap)
  }
}
