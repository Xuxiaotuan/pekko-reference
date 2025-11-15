package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base.NodeSource
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import spray.json._

/**
 * 内存数据源节点
 */
class MemorySource extends NodeSource {
  
  override def nodeType: String = "memory.collection"
  
  override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = {
    val dataField = node.config.fields.get("data")
      .getOrElse(throw new IllegalArgumentException("Memory source缺少data配置"))
    
    val data = dataField match {
      case JsArray(elements) => elements.map(_.toString).toList
      case JsString(str) => List(str)
      case _ => throw new IllegalArgumentException("Memory source的data必须是数组或字符串")
    }
    
    onLog(s"使用内存数据: ${data.length}条记录")
    Source(data)
  }
}
