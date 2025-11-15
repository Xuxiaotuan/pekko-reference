package cn.xuyinyin.magic.workflow.nodes.sinks

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base.NodeSink
import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.Sink
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}

/**
 * 控制台输出Sink节点
 */
class ConsoleLogSink extends NodeSink {
  
  override def nodeType: String = "console.log"
  
  override def createSink(node: WorkflowDSL.Node, onLog: String => Unit)
                        (implicit ec: ExecutionContext): Sink[String, Future[Done]] = {
    val limit = node.config.fields.get("limit").map(_.convertTo[Int]).getOrElse(Int.MaxValue)
    
    onLog(s"输出到控制台 (最多${if (limit == Int.MaxValue) "无限" else limit}行)")
    
    Sink.foreach[String] { line =>
      onLog(line)
    }.mapMaterializedValue(_.map(_ => Done))
  }
}
