package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base.NodeSource
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import spray.json._
import spray.json.DefaultJsonProtocol._

/**
 * 序列生成器节点
 */
class SequenceSource extends NodeSource {
  
  override def nodeType: String = "sequence.numbers"
  
  override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = {
    val start = node.config.fields.get("start").map(_.convertTo[Int]).getOrElse(1)
    val end = node.config.fields.get("end").map(_.convertTo[Int]).getOrElse(100)
    val step = node.config.fields.get("step").map(_.convertTo[Int]).getOrElse(1)
    
    onLog(s"生成序列: $start 到 $end (步长: $step)")
    
    Source(start to end by step)
      .map(_.toString)
  }
}
