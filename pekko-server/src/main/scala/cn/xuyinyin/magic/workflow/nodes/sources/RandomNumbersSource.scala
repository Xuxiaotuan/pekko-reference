package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base.NodeSource
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import spray.json._
import spray.json.DefaultJsonProtocol._

/**
 * 随机数生成器节点
 */
class RandomNumbersSource extends NodeSource {
  
  override def nodeType: String = "random.numbers"
  
  override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = {
    val count = node.config.fields.get("count").map(_.convertTo[Int]).getOrElse(100)
    val min = node.config.fields.get("min").map(_.convertTo[Int]).getOrElse(1)
    val max = node.config.fields.get("max").map(_.convertTo[Int]).getOrElse(100)
    
    onLog(s"生成随机数: $count 个 (范围: $min-$max)")
    
    val random = new scala.util.Random()
    Source(1 to count)
      .map(_ => random.nextInt(max - min + 1) + min)
      .map(_.toString)
  }
}
