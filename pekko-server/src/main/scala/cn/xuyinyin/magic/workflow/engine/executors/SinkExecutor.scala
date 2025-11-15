package cn.xuyinyin.magic.workflow.engine.executors

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.{FileIO, Flow, Keep, Sink}
import org.apache.pekko.util.ByteString
import spray.json.DefaultJsonProtocol._

import java.nio.file.Paths
import scala.concurrent.{ExecutionContext, Future}

/**
 * Sink节点执行器
 * 
 * 处理所有数据汇类型节点
 * 
 * @author : Xuxiaotuan
 * @since : 2024-11-15
 */
class SinkExecutor extends NodeExecutor {
  
  override def supportedTypes: Set[String] = Set(
    "file.text",
    "console.log",
    "aggregate.count",
    "kafka.producer",    // 从 KafkaTaskExecutor 迁移
    "file.transfer"      // 从 FileTransferTaskExecutor 迁移
  )
  
  /**
   * 创建Sink
   */
  def createSink(node: WorkflowDSL.Node, onLog: String => Unit)(implicit ec: ExecutionContext): Sink[String, Future[Done]] = {
    node.nodeType match {
      case "file.text" =>
        createFileSink(node, onLog)
      
      case "console.log" =>
        createConsoleSink(node, onLog)
      
      case "aggregate.count" =>
        createCountSink(node, onLog)
      
      case _ =>
        throw new IllegalArgumentException(s"不支持的Sink类型: ${node.nodeType}")
    }
  }
  
  /**
   * 文件输出Sink
   */
  private def createFileSink(node: WorkflowDSL.Node, onLog: String => Unit)(implicit ec: ExecutionContext): Sink[String, Future[Done]] = {
    val path = node.config.fields.get("path").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("File sink缺少path配置"))
    
    onLog(s"输出到文件: $path")
    
    Flow[String]
      .map(s => ByteString(s + "\n"))
      .toMat(FileIO.toPath(Paths.get(path)))(Keep.right)
      .mapMaterializedValue(_.map(_ => Done))
  }
  
  /**
   * 控制台输出Sink
   */
  private def createConsoleSink(node: WorkflowDSL.Node, onLog: String => Unit): Sink[String, Future[Done]] = {
    val limit = node.config.fields.get("limit").map(_.convertTo[Int]).getOrElse(100)
    
    onLog(s"输出到控制台 (最多${limit}行)")
    
    var count = 0
    Sink.foreach[String] { row =>
      if (count < limit) {
        onLog(s"输出: $row")
        count += 1
      }
    }
  }
  
  /**
   * 统计Sink
   */
  private def createCountSink(node: WorkflowDSL.Node, onLog: String => Unit)(implicit ec: ExecutionContext): Sink[String, Future[Done]] = {
    onLog("统计数据行数")
    
    Sink.fold[Int, String](0)((count, _) => count + 1)
      .mapMaterializedValue { futureCount =>
        futureCount.map { count =>
          val msg = s"总计: $count 行"
          onLog(msg)
          Done
        }
      }
  }
}
