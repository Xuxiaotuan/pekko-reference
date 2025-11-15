package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base.NodeSource
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{FileIO, Framing, Source}
import org.apache.pekko.util.ByteString
import spray.json._
import spray.json.DefaultJsonProtocol._

import java.nio.file.Paths

/**
 * CSV文件数据源节点
 */
class CsvSource extends NodeSource {
  
  override def nodeType: String = "file.csv"
  
  override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = {
    val path = node.config.fields.get("path").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("CSV source缺少path配置"))
    val delimiter = node.config.fields.get("delimiter").map(_.convertTo[String]).getOrElse(",")
    
    onLog(s"读取CSV文件: $path (分隔符: $delimiter)")
    
    FileIO.fromPath(Paths.get(path))
      .mapMaterializedValue(_ => NotUsed)
      .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 8192, allowTruncation = true))
      .map(_.utf8String)
      .filter(_.nonEmpty)
  }
}

/**
 * 文本文件数据源节点
 */
class TextSource extends NodeSource {
  
  override def nodeType: String = "file.text"
  
  override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = {
    val path = node.config.fields.get("path").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("Text source缺少path配置"))
    
    onLog(s"读取文本文件: $path")
    
    FileIO.fromPath(Paths.get(path))
      .mapMaterializedValue(_ => NotUsed)
      .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 8192, allowTruncation = true))
      .map(_.utf8String)
  }
}
