package cn.xuyinyin.magic.workflow.nodes.base

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.Sink

import scala.concurrent.{ExecutionContext, Future}

/**
 * Sink节点基类
 * 
 * 所有数据汇节点都继承此类
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-15
 */
trait NodeSink {
  
  /**
   * 节点类型标识
   */
  def nodeType: String
  
  /**
   * 创建Pekko Stream Sink
   * 
   * @param node 节点配置
   * @param onLog 日志回调
   * @param ec ExecutionContext
   * @return Sink
   */
  def createSink(node: WorkflowDSL.Node, onLog: String => Unit)
                (implicit ec: ExecutionContext): Sink[String, Future[Done]]
}
