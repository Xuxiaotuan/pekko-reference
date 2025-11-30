package cn.xuyinyin.magic.workflow.nodes.base

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.ExecutionContext

/**
 * Transform节点基类
 * 
 * 所有数据转换节点都继承此类
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-28
 */
trait NodeTransform {
  
  /**
   * 节点类型标识
   */
  def nodeType: String
  
  /**
   * 创建Pekko Stream Flow
   * 
   * @param node 节点配置
   * @param onLog 日志回调
   * @param ec ExecutionContext
   * @return Flow转换
   */
  def createTransform(
    node: WorkflowDSL.Node,
    onLog: String => Unit
  )(implicit ec: ExecutionContext): Flow[String, String, NotUsed]
}
