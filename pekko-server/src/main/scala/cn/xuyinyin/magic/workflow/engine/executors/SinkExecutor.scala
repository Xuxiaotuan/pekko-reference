package cn.xuyinyin.magic.workflow.engine.executors

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.engine.registry.NodeRegistry
import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.Sink

import scala.concurrent.{ExecutionContext, Future}

/**
 * Sink节点执行器
 * 
 * 使用NodeRegistry查找和执行节点
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-15
 */
class SinkExecutor extends NodeExecutor {
  
  override def supportedTypes: Set[String] = NodeRegistry.supportedSinkTypes
  
  /**
   * 创建Sink (使用NodeRegistry)
   */
  def createSink(node: WorkflowDSL.Node, onLog: String => Unit)
                (implicit ec: ExecutionContext): Sink[String, Future[Done]] = {
    NodeRegistry.findSink(node.nodeType) match {
      case Some(nodeSink) =>
        nodeSink.createSink(node, onLog)
      case None =>
        throw new IllegalArgumentException(
          s"不支持的Sink类型: ${node.nodeType}\n" +
          s"支持的类型: ${supportedTypes.mkString(", ")}"
        )
    }
  }
}
