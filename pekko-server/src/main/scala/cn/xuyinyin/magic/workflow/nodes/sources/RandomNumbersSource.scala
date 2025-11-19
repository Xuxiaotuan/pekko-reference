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
    val count = node.config.fields.get("count") match {
      case Some(JsString(s)) => s.toInt  // 将字符串转换为整数
      case Some(JsNumber(n)) => n.toInt
      case _ => 100  // 默认值
    }

    val min = node.config.fields.get("min") match {
      case Some(JsString(s)) => s.toInt  // 将字符串转换为整数
      case Some(JsNumber(n)) => n.toInt
      case _ => 1  // 默认值
    }

    val max = node.config.fields.get("max") match {
      case Some(JsString(s)) => s.toInt  // 将字符串转换为整数
      case Some(JsNumber(n)) => n.toInt
      case _ => 100  // 默认值
    }

    onLog(s"生成随机数: $count 个 (范围: $min-$max)")

    val random = new scala.util.Random()
    Source(1 to count)
      .map(_ => random.nextInt(max - min + 1) + min)
      .map(_.toString)
  }
}