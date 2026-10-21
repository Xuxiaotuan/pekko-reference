package cn.xuyinyin.magic.workflow.actors

import EventSourcedWorkflowActor._
import cn.xuyinyin.magic.workflow.events.WorkflowEvents._
import cn.xuyinyin.magic.workflow.engine.ExecutionResult
import WorkflowDefinitionCodec.decodeWorkflow
import java.nio.charset.StandardCharsets

/** Bounded durable results and read-side history formatting; no execution ownership. */
private[actors] object WorkflowHistory {
  def statusResponse(workflowId: String, state: WorkflowState): StatusResponse =
    StatusResponse(workflowId, state.status.value, state.currentExecution.map(e => ExecutionInfo(e.executionId, e.startedAt, None, state.status.value, 0, 0)), state.recentExecutions.map(publicSummary).toList)

  def historyResponse(workflowId: String, state: WorkflowState, page: Int, pageSize: Int): ExecutionHistoryResponse = {
    val size = math.max(1, math.min(DefaultHistoryPageSize, pageSize))
    val selected = state.recentExecutions.reverse.slice(math.max(0, page) * size, (math.max(0, page) + 1) * size)
    val name = decodeWorkflow(state).toOption.map(_.name).getOrElse(workflowId)
    ExecutionHistoryResponse(workflowId, selected.map { summary =>
      val public = publicSummary(summary)
      ExecutionDetail(public.executionId, name, public.startTime, public.endTime, public.status, public.duration, historyNodes(summary))
    }.toList)
  }

  private def historyNodes(summary: StoredExecutionSummary): List[NodeExecutionDetail] = summary.result.toList.flatMap { result =>
    if (result.nodeResults.nonEmpty) {
      result.nodeResults.toList.map { node =>
        NodeExecutionDetail(
          node.nodeId,
          node.nodeType,
          None,
          None,
          Option.when(node.hasDuration)(node.duration),
          node.status,
          None,
          Option.when(node.hasMessage)(node.message)
        )
      }
    } else if (!result.success && result.message.nonEmpty) {
      List(NodeExecutionDetail("__workflow__", "workflow", Some(summary.startTime), summary.endTimeOption, summary.durationOption, result.status, Option.when(result.hasRowsProcessed)(result.rowsProcessed), Some(result.message)))
    } else Nil
  }

  def boundedPersistedResult(result: ExecutionResult): Either[String, PersistedExecutionResult] = {
    val oversized =
      result.nodeResults.size > MaxPersistedNodeResults ||
        utf8Bytes(result.message) > MaxPersistedMessageBytes ||
        result.nodeResults.exists(node => node.message.exists(message => utf8Bytes(message) > MaxPersistedMessageBytes))
    if (oversized) Left("engine result exceeded persistence bounds")
    else Right(PersistedExecutionResult(
      result.status,
      result.success,
      result.message,
      result.rowsProcessed.getOrElse(0),
      result.rowsProcessed.nonEmpty,
      result.duration.getOrElse(0L),
      result.duration.nonEmpty,
      result.nodeResults.map(n => PersistedNodeExecutionResult(n.nodeId, n.nodeType, n.status, n.message.getOrElse(""), n.message.nonEmpty, n.duration.getOrElse(0L), n.duration.nonEmpty))
    ))
  }
  def failedPersistedResult(message: String): PersistedExecutionResult =
    PersistedExecutionResult("failed", success = false, message, 0, hasRowsProcessed = false, 0L, hasDuration = false, Vector.empty)
  def publicSummary(summary: StoredExecutionSummary): ExecutionSummary =
    ExecutionSummary(summary.executionId, summary.startTime, if (summary.hasEndTime) Some(summary.endTime) else None, summary.status, if (summary.hasDuration) Some(summary.duration) else None)
  private implicit class StoredExecutionSummaryOps(private val summary: StoredExecutionSummary) extends AnyVal {
    def endTimeOption: Option[Long] = Option.when(summary.hasEndTime)(summary.endTime)
    def durationOption: Option[Long] = Option.when(summary.hasDuration)(summary.duration)
  }
  private def utf8Bytes(value: String): Int = value.getBytes(StandardCharsets.UTF_8).length
}
