package cn.xuyinyin.magic.cdc.recovery

import com.fasterxml.jackson.databind.JsonNode
import org.apache.pekko.serialization.jackson.JacksonMigration

/** Reads failure state serialized with the former Workflow class name. */
final class LegacyExecutionFailureMigration extends JacksonMigration {
  override def currentVersion: Int = 2
  override def transformClassName(fromVersion: Int, className: String): String = classOf[ExecutionFailure].getName
  override def transform(fromVersion: Int, json: JsonNode): JsonNode = json
}
