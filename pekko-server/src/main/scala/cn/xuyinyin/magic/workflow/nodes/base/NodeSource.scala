package cn.xuyinyin.magic.workflow.nodes.base

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

/**
 * Source节点基类
 * 
 * 所有数据源节点都继承此类
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-15
 */
trait NodeSource {
  
  /**
   * 节点类型标识
   */
  def nodeType: String
  
  /**
   * 创建Pekko Stream Source
   * 
   * @param node 节点配置
   * @param onLog 日志回调
   * @return Source流
   */
  def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed]
}
