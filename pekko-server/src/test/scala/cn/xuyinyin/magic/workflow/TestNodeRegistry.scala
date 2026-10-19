package cn.xuyinyin.magic.workflow

import cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry
import cn.xuyinyin.magic.workflow.nodes.base.{NodeSource, NodeSink}

/** Suite-local fixture builder; Engines receive immutable snapshots, never this mutable builder. */
final class TestNodeRegistry {
  private val builtins = NodeRegistry.builtins()
  private var sources = Map.empty[String, NodeSource]
  private var sinks = Map.empty[String, NodeSink]
  def snapshot: NodeRegistry = sinks.values.foldLeft(sources.values.foldLeft(builtins)(_.withSource(_)))(_.withSink(_))
  def registerSource(s: NodeSource): Unit = { sources += s.nodeType -> s }
  def registerSink(s: NodeSink): Unit = { sinks += s.nodeType -> s }
  def unregisterSource(t: String, expected: NodeSource): Unit = if (sources.get(t).exists(_ eq expected)) sources -= t
  def unregisterSink(t: String, expected: NodeSink): Unit = if (sinks.get(t).exists(_ eq expected)) sinks -= t
  def unregister(s: NodeSource): Unit = unregisterSource(s.nodeType, s)
  def unregister(s: NodeSink): Unit = unregisterSink(s.nodeType, s)
  def findSource(t: String): Option[NodeSource] = snapshot.findSource(t)
  def findSink(t: String): Option[NodeSink] = snapshot.findSink(t)
}
