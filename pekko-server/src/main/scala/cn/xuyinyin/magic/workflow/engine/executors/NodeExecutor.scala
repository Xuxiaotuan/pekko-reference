package cn.xuyinyin.magic.workflow.engine.executors

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import org.apache.pekko.Done
import scala.concurrent.Future

/**
 * 节点执行器基础接口
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-15
 */
trait NodeExecutor {
  /**
   * 支持的节点类型
   */
  def supportedTypes: Set[String]
  
  /**
   * 检查是否支持该节点类型
   */
  def supports(nodeType: String): Boolean = supportedTypes.contains(nodeType)
}
