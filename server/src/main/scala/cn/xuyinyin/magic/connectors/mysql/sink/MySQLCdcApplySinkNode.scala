package cn.xuyinyin.magic.connectors.mysql.sink

import cn.xuyinyin.magic.cdc.compat.nodes.WorkflowDSL
import cn.xuyinyin.magic.cdc.checkpoint.{BatchCommitResult, SnapshotBoundary, SourceBatch}
import cn.xuyinyin.magic.cdc.compat.nodes.{CheckpointedNodeSink, NodeSink}
import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.Sink
import scala.concurrent.{ExecutionContext, Future}

/** Compatibility adapter for legacy Workflow nodes. CDC runtime uses MySQLCdcApplyEngine. */
class MySQLCdcApplySinkNode extends MySQLCdcApplyEngine with NodeSink with CheckpointedNodeSink {
  override val nodeType: String = "mysql.cdc.apply"
  protected[sink] def getenv(name: String): Option[String] = sys.env.get(name)
  override def createSink(node: WorkflowDSL.Node, onLog: String => Unit)
    (implicit ec: ExecutionContext): Sink[String, Future[Done]] =
    throw new UnsupportedOperationException("mysql.cdc.apply requires checkpoint-aware execution")
  private def parseConfig(node: WorkflowDSL.Node): MySQLCdcApplyConfig =
    if (node.config.fields.contains("password")) MySQLCdcApplyNodeConfig.parseTrustedRuntime(node)
    else MySQLCdcApplyNodeConfig.parse(node, getenv)
  override def validateReady(node: WorkflowDSL.Node, onLog: String => Unit)
    (implicit ec: ExecutionContext): Future[Done] = validateReadyConfigured(parseConfig(node), onLog)
  override def validateSourceBoundary(node: WorkflowDSL.Node, boundary: SnapshotBoundary, onLog: String => Unit)
    (implicit ec: ExecutionContext): Future[Done] = validateSourceBoundaryConfigured(parseConfig(node), boundary, onLog)
  override def commitBatch(node: WorkflowDSL.Node, workflowId: String, executionId: String, batch: SourceBatch,
    transformedRows: Vector[String], onLog: String => Unit)(implicit ec: ExecutionContext): Future[BatchCommitResult] =
    commitBatchConfigured(parseConfig(node), workflowId, executionId, batch, transformedRows, onLog)
}

object MySQLCdcApplySinkNode {
  private[sink] def jdbcUrl(config: MySQLCdcApplyConfig): String = MySQLCdcApplyEngine.jdbcUrl(config)
}
