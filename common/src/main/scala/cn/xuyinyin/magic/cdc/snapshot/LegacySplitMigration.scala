package cn.xuyinyin.magic.cdc.snapshot

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.apache.pekko.serialization.jackson.JacksonMigration

/** Maps old manifest names and the polymorphic update type embedded in Journal events. */
final class LegacySplitMigration extends JacksonMigration {
  override def currentVersion: Int = 2
  private val oldPrefix = "cn.xuyinyin.magic.workflow.snapshot."
  private val newPrefix = "cn.xuyinyin.magic.cdc.snapshot."
  private val names = Set("SnapshotSplitPlanState", "SnapshotSplit", "SnapshotSplitProgress", "SnapshotSplitState",
    "InitializeSnapshotSplits", "RegisterSnapshotSplit", "StartSnapshotSplit", "CommitSnapshotPage",
    "CompleteSnapshotSplit", "RetrySnapshotSplit", "BlockSnapshotSplit", "RetireSnapshotWindow$", "FinishSnapshotEnumeration$")

  private def migrated(name: String): String =
    if (name.startsWith(oldPrefix) && names.contains(name.substring(oldPrefix.length))) newPrefix + name.substring(oldPrefix.length)
    else name

  override def transformClassName(fromVersion: Int, className: String): String = migrated(className)
  override def transform(fromVersion: Int, json: JsonNode): JsonNode = {
    if (fromVersion <= 1 && json.isObject) {
      def rewrite(node: JsonNode): Unit = node match {
        case obj: ObjectNode if obj.hasNonNull("_type") && obj.get("_type").isTextual =>
          val old = obj.get("_type").asText()
          val next = migrated(old)
          if (next != old) obj.put("_type", next)
        case _ => ()
      }
      rewrite(json)
      if (json.has("update")) rewrite(json.get("update"))
    }
    json
  }
}
