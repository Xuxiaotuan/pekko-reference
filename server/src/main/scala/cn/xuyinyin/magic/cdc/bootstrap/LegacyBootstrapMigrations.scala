package cn.xuyinyin.magic.cdc.bootstrap

import com.fasterxml.jackson.databind.JsonNode
import org.apache.pekko.serialization.jackson.JacksonMigration

/** Reads the original workflow journal manifest after moving bootstrap to CDC. */
final class LegacyBootstrapAdvancedMigration extends JacksonMigration {
  override def currentVersion: Int = 2
  override def transformClassName(fromVersion: Int, className: String): String =
    classOf[BootstrapJournal.Advanced].getName
  override def transform(fromVersion: Int, json: JsonNode): JsonNode = json
}

/** Reads snapshots whose manifest still names the original workflow state. */
final class LegacyBootstrapProgressMigration extends JacksonMigration {
  override def currentVersion: Int = 2
  override def transformClassName(fromVersion: Int, className: String): String =
    classOf[BootstrapProgress].getName
  override def transform(fromVersion: Int, json: JsonNode): JsonNode = json
}
