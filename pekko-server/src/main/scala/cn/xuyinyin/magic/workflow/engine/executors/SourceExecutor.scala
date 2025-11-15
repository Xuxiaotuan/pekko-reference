package cn.xuyinyin.magic.workflow.engine.executors

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

/**
 * Source节点执行器
 * 
 * 使用NodeRegistry查找和执行节点
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-15
 */
class SourceExecutor extends NodeExecutor {
  
  override def supportedTypes: Set[String] = NodeRegistry.supportedSourceTypes
  
  /**
   * 创建Source (使用NodeRegistry)
   */
  def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = {
    NodeRegistry.findSource(node.nodeType) match {
      case Some(nodeSource) =>
        nodeSource.createSource(node, onLog)
      case None =>
        throw new IllegalArgumentException(
          s"不支持的Source类型: ${node.nodeType}\n" +
          s"支持的类型: ${supportedTypes.mkString(", ")}"
        )
    }
  }
}
