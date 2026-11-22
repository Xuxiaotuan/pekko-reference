package cn.xuyinyin.magic.cdc.checkpoint

import com.fasterxml.jackson.databind.JsonNode
import org.apache.pekko.serialization.jackson.JacksonMigration

final class LegacySourceCursorMigration extends JacksonMigration {
  override def currentVersion: Int = 2
  override def transformClassName(fromVersion: Int, className: String): String = classOf[SourceCursor].getName
  override def transform(fromVersion: Int, json: JsonNode): JsonNode = json
}

final class LegacySnapshotBoundaryMigration extends JacksonMigration {
  override def currentVersion: Int = 2
  override def transformClassName(fromVersion: Int, className: String): String = classOf[SnapshotBoundary].getName
  override def transform(fromVersion: Int, json: JsonNode): JsonNode = json
}

final class LegacyBatchCheckpointMigration extends JacksonMigration {
  override def currentVersion: Int = 2
  override def transformClassName(fromVersion: Int, className: String): String = classOf[BatchCheckpoint].getName
  override def transform(fromVersion: Int, json: JsonNode): JsonNode = json
}
