package cn.xuyinyin.magic.connectors.mysql.source

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Properties
import org.apache.kafka.connect.source.SourceRecord
import scala.jdk.CollectionConverters._
import spray.json._

/** Debezium configuration and deterministic offset representation shared by CDC capture. */
private[magic] object MySQLDebeziumConnector {
  private val OffsetTableDdl =
    "CREATE TABLE %s (id VARCHAR(36) NOT NULL, offset_key VARCHAR(1255), offset_val VARCHAR(1255), " +
      "record_insert_ts TIMESTAMP(6) NOT NULL, record_insert_seq INT NOT NULL, PRIMARY KEY (id))"
  private val HistoryTableDdl =
    "CREATE TABLE %s (id VARCHAR(36) NOT NULL, history_data LONGTEXT, history_data_seq INT, " +
      "record_insert_ts TIMESTAMP(6) NOT NULL, record_insert_seq INT NOT NULL, " +
      "PRIMARY KEY (id, history_data_seq))"
  private[source] def connectorProperties(
    source: MySQLCdcSourceConfig,
    state: MySQLCdcStateConfig
  ): Properties = {
    val properties = new Properties()
    val (offsetTable, historyTable) = connectorStateTables(state, source.connectorId)
    Vector(
      "name" -> source.connectorId,
      "connector.class" -> "io.debezium.connector.mysql.MySqlConnector",
      "database.hostname" -> source.host,
      "database.port" -> source.port.toString,
      "database.user" -> source.username,
      "database.password" -> source.password,
      "driver.allowPublicKeyRetrieval" -> "true",
      "driver.forceConnectionTimeZoneToSession" -> "true",
      "database.connectionTimeZone" -> "UTC",
      "database.server.id" -> source.serverId.toString,
      "topic.prefix" -> source.connectorId,
      "database.include.list" -> source.database,
      "table.include.list" -> s"${source.database}.${source.table}",
      "snapshot.mode" -> (if (source.groupProtocol.nonEmpty) "no_data" else "initial"),
      "snapshot.locking.mode" -> "none",
      "record.processing.order" -> "ORDERED",
      "record.processing.threads" -> "1",
      "offset.flush.interval.ms" -> state.offsetFlushIntervalMillis.toString,
      "decimal.handling.mode" -> "string",
      "include.schema.changes" -> "false",
      "tombstones.on.delete" -> "false",
      "max.batch.size" -> source.maxBatchSize.toString,
      "poll.interval.ms" -> source.pollIntervalMillis.toString,
      "offset.storage" -> "io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore",
      "offset.storage.jdbc.connection.url" -> state.jdbcUrl,
      "offset.storage.jdbc.connection.user" -> state.username,
      "offset.storage.jdbc.connection.password" -> state.password,
      "offset.storage.jdbc.table.name" -> offsetTable,
      "offset.storage.jdbc.table.ddl" -> OffsetTableDdl,
      "schema.history.internal" -> "io.debezium.storage.jdbc.history.JdbcSchemaHistory",
      "schema.history.internal.jdbc.connection.url" -> state.jdbcUrl,
      "schema.history.internal.jdbc.connection.user" -> state.username,
      "schema.history.internal.jdbc.connection.password" -> state.password,
      "schema.history.internal.jdbc.table.name" -> historyTable,
      "schema.history.internal.jdbc.table.ddl" -> HistoryTableDdl
    ).foreach { case (key, value) => properties.setProperty(key, value) }
    properties
  }

  private[magic] def connectorStateTables(
    state: MySQLCdcStateConfig,
    connectorId: String
  ): (String, String) = {
    val suffix = sha256(connectorId).take(32)
    def scoped(base: String): String = s"${base.take(31)}_$suffix"
    scoped(state.offsetTable) -> scoped(state.historyTable)
  }

  private[source] def cursorValue(record: SourceRecord): String = {
    if (record == null || record.sourceOffset() == null) {
      throw new IllegalArgumentException("Debezium source offset is missing")
    }
    val offset = record.sourceOffset().asScala.toVector.map { case (key, value) =>
      key -> offsetValue(value, s"sourceOffset.$key")
    }.toMap
    canonicalJson(JsObject(
      "version" -> JsNumber(1),
      "offset" -> JsObject(offset)
    ))
  }

  private def offsetValue(value: Any, path: String): JsValue = value match {
    case null => JsNull
    case v: java.lang.Boolean => JsBoolean(v.booleanValue())
    case v: java.lang.Byte => JsNumber(v.longValue())
    case v: java.lang.Short => JsNumber(v.longValue())
    case v: java.lang.Integer => JsNumber(v.longValue())
    case v: java.lang.Long => JsNumber(v.longValue())
    case v: java.math.BigInteger => JsNumber(BigDecimal(v))
    case v: java.math.BigDecimal => JsNumber(BigDecimal(v))
    case v: java.lang.Float if java.lang.Float.isFinite(v) => JsNumber(BigDecimal.decimal(v.floatValue()))
    case v: java.lang.Double if java.lang.Double.isFinite(v) => JsNumber(BigDecimal.decimal(v.doubleValue()))
    case v: String => JsString(v)
    case other => throw new IllegalArgumentException(s"unsupported Debezium offset value at $path: ${other.getClass.getSimpleName}")
  }

  private[source] def canonicalJson(value: JsValue): String = value match {
    case JsObject(fields) => fields.toVector.sortBy(_._1).map { case (key, field) =>
      s"${JsString(key).compactPrint}:${canonicalJson(field)}"
    }.mkString("{", ",", "}")
    case JsArray(elements) => elements.map(canonicalJson).mkString("[", ",", "]")
    case other => other.compactPrint
  }
  private[source] def sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x").mkString
}
