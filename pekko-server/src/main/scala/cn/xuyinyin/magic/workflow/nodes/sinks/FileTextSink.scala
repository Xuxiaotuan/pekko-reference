package cn.xuyinyin.magic.workflow.nodes.sinks

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base.NodeSink
import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.{FileIO, Sink}
import org.apache.pekko.util.ByteString
import spray.json._
import spray.json.DefaultJsonProtocol._

import java.nio.file.Paths
import scala.concurrent.{ExecutionContext, Future}

/**
 * 文件输出Sink节点
 */
class FileTextSink extends NodeSink {
  
  override def nodeType: String = "file.text"
  
  override def createSink(node: WorkflowDSL.Node, onLog: String => Unit)
                        (implicit ec: ExecutionContext): Sink[String, Future[Done]] = {
    val path = node.config.fields.get("path").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("File sink缺少path配置"))
    
    onLog(s"输出到文件: $path")
    
    FileIO.toPath(Paths.get(path))
      .contramap[String](line => ByteString(line + "\n"))
      .mapMaterializedValue(_.map(_ => Done))
  }
}
