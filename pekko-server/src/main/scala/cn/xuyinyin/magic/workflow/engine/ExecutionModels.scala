package cn.xuyinyin.magic.workflow.engine

final case class NodeExecutionResult(
  nodeId: String,
  nodeType: String,
  status: String,
  message: Option[String] = None,
  duration: Option[Long] = None
)

final case class ExecutionResult(
  status: String,
  success: Boolean,
  message: String,
  rowsProcessed: Option[Int],
  duration: Option[Long] = None,
  nodeResults: Vector[NodeExecutionResult] = Vector.empty
)
