package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base.NodeSource
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import spray.json._
import spray.json.DefaultJsonProtocol._

/**
 * Kafka消费数据源节点
 * TODO: 集成真实Kafka Consumer
 */
class KafkaSource extends NodeSource {
  
  override def nodeType: String = "kafka.consumer"
  
  override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = {
    val topic = node.config.fields.get("topic").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("Kafka source缺少topic配置"))
    val brokers = node.config.fields.get("brokers").map(_.convertTo[String]).getOrElse("localhost:9092")
    val groupId = node.config.fields.get("groupId").map(_.convertTo[String]).getOrElse("default-group")
    
    onLog(s"从Kafka消费: $topic @ $brokers (group: $groupId)")
    
    // TODO: 集成真实Kafka Consumer
    // 当前：模拟数据
    Source(List(
      "kafka-message-1",
      "kafka-message-2",
      "kafka-message-3"
    ))
  }
}
