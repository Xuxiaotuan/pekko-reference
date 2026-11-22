package cn.xuyinyin.magic.cdc.compat

import cn.xuyinyin.magic.cdc.snapshot.LegacySplitMigration
import com.fasterxml.jackson.databind.JsonNode
import org.apache.pekko.serialization.jackson.JacksonMigration

/** Read historical Workflow Journal manifests using the CDC compatibility model. */
final class LegacyWorkflowEventMigration extends JacksonMigration {
  override def currentVersion: Int = 3
  private val oldPrefix = "cn.xuyinyin.magic.workflow.events.WorkflowEvents$"
  private val newPrefix = "cn.xuyinyin.magic.cdc.compat.WorkflowEvents$"
  private val types = Set("ExecutionTrigger", "PersistedNodeExecutionResult", "PersistedExecutionResult",
    "WorkflowDefined", "ExecutionStarted", "ResumableExecutionStarted", "ExecutionSnapshotInitialized",
    "ExecutionCheckpointAdvanced", "ExecutionRecoveryState", "ExecutionRecoveryScheduled",
    "ExecutionRecoveryAttemptStarted", "ExecutionRecoveryBlocked", "ExecutionCompleted", "ExecutionFailed",
    "SnapshotSplitsUpdated", "SnapshotExecutionPaused", "SnapshotSchedulingChanged", "ExecutionSkipped")

  override def transformClassName(fromVersion: Int, className: String): String = {
    require(className.startsWith(oldPrefix) && types.contains(className.substring(oldPrefix.length)),
      "unsupported legacy Workflow event manifest")
    newPrefix + className.substring(oldPrefix.length)
  }

  override def transform(fromVersion: Int, json: JsonNode): JsonNode =
    // Old Split updates also contain a class name inside their polymorphic `update` field.
    new LegacySplitMigration().transform(math.min(fromVersion, 1), json)
}
