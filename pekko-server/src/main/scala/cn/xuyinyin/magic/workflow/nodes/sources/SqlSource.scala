package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.workflow.model.WorkflowDSL
import cn.xuyinyin.magic.workflow.nodes.base.NodeSource
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import spray.json._
import spray.json.DefaultJsonProtocol._

/**
 * SQL查询数据源节点
 * TODO: 集成真实SQL引擎（DataFusion/Calcite/DuckDB）
 */
class SqlSource extends NodeSource {
  
  override def nodeType: String = "sql.query"
  
  override def createSource(node: WorkflowDSL.Node, onLog: String => Unit): Source[String, NotUsed] = {
    val sql = node.config.fields.get("sql").map(_.convertTo[String])
      .getOrElse(throw new IllegalArgumentException("SQL source缺少sql配置"))
    val database = node.config.fields.get("database").map(_.convertTo[String]).getOrElse("default")
    
    onLog(s"执行SQL查询: $database - $sql")
    
    // TODO: 集成真实SQL引擎
    // 当前：模拟数据
    Source(List(
      "id,name,age",
      "1,Alice,25",
      "2,Bob,30",
      "3,Charlie,35"
    ))
  }
}
