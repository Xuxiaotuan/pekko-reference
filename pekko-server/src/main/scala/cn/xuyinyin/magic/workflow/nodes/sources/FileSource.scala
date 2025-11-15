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
 * 文件数据源节点
 * 
 * 支持多种文件格式：
 * - CSV
 * - Text
 * - JSON Lines
 * 
 * 配置：
 * - path: 文件路径（必需）
 * - format: 文件格式（csv/text/jsonl，默认text）
 * - delimiter: CSV分隔符（默认逗号）
 * - encoding: 文件编码（默认UTF-8）
 * - skipHeader: 是否跳过首行（默认false）
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-15
 */
class FileSource extends NodeSource {
  
  override def nodeType: String = "file.read"
  
  override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = {
    val path = node.config.fields.get("path").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("File source缺少path配置"))
    val format = node.config.fields.get("format").map(_.convertTo[String]).getOrElse("text")
    val delimiter = node.config.fields.get("delimiter").map(_.convertTo[String]).getOrElse(",")
    val skipHeader = node.config.fields.get("skipHeader").map(_.convertTo[Boolean]).getOrElse(false)
    
    onLog(s"读取文件: $path (格式: $format)")
    
    val baseSource = FileIO.fromPath(Paths.get(path))
      .mapMaterializedValue(_ => NotUsed)
      .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 8192, allowTruncation = true))
      .map(_.utf8String)
      .filter(_.nonEmpty)
    
    // 如果需要跳过首行
    if (skipHeader && format == "csv") {
      onLog("跳过CSV首行")
      baseSource.drop(1)
    } else {
      baseSource
    }
  }
}
