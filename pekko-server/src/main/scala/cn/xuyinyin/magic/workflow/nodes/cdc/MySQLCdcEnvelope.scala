package cn.xuyinyin.magic.workflow.nodes.cdc

import org.apache.kafka.connect.data.{Decimal, Field, Schema, Struct}
import org.apache.kafka.connect.source.SourceRecord
import spray.json._

import java.math.{BigDecimal => JBigDecimal}
import java.time.format.{DateTimeFormatter, DateTimeFormatterBuilder}
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.time.{Instant, LocalDate, LocalDateTime, LocalTime, OffsetDateTime, OffsetTime, ZoneOffset}
import java.util.Date
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

sealed trait CdcOperation { def code: String }
object CdcOperation {
  case object Read extends CdcOperation { val code = "r" }
  case object Create extends CdcOperation { val code = "c" }
  case object Update extends CdcOperation { val code = "u" }
  case object Delete extends CdcOperation { val code = "d" }

  private[cdc] def parse(code: String): CdcOperation = code match {
    case "r" => Read
    case "c" => Create
    case "u" => Update
    case "d" => Delete
    case "t" => MySQLCdcEnvelope.fail("truncate operation is not supported")
    case other => MySQLCdcEnvelope.fail(s"unsupported CDC operation: $other")
  }
}

final case class MySQLCdcSourcePosition(
  connectorId: String,
  database: String,
  table: String,
  snapshot: Boolean,
  file: Option[String],
  position: Option[Long],
  row: Option[Int],
  eventTimestampMillis: Option[Long]
)

final case class CdcDecodeFailure(message: String)

final case class MySQLCdcEnvelope(
  version: Int,
  op: CdcOperation,
  key: JsObject,
  before: Option[JsObject],
  after: Option[JsObject],
  source: MySQLCdcSourcePosition
) {
  def canonicalJson: String = MySQLCdcEnvelope.canonicalJson(JsObject(
    "version" -> JsNumber(version),
    "op" -> JsString(op.code),
    "key" -> key,
    "before" -> before.getOrElse(JsNull),
    "after" -> after.getOrElse(JsNull),
    "source" -> JsObject(
      "connectorId" -> JsString(source.connectorId),
      "database" -> JsString(source.database),
      "table" -> JsString(source.table),
      "snapshot" -> JsBoolean(source.snapshot),
      "file" -> source.file.map(JsString(_)).getOrElse(JsNull),
      "position" -> source.position.map(JsNumber(_)).getOrElse(JsNull),
      "row" -> source.row.map(JsNumber(_)).getOrElse(JsNull),
      "eventTimestampMillis" -> source.eventTimestampMillis.map(JsNumber(_)).getOrElse(JsNull)
    )
  ))
}

object MySQLCdcEnvelope {
  private val Version = 1
  private val DecimalName = Decimal.LOGICAL_NAME
  private val ConnectDateName = org.apache.kafka.connect.data.Date.LOGICAL_NAME
  private val ConnectTimeName = org.apache.kafka.connect.data.Time.LOGICAL_NAME
  private val ConnectTimestampName = org.apache.kafka.connect.data.Timestamp.LOGICAL_NAME
  private val DbzDate = "io.debezium.time.Date"
  private val DbzTime = "io.debezium.time.Time"
  private val DbzMicroTime = "io.debezium.time.MicroTime"
  private val DbzNanoTime = "io.debezium.time.NanoTime"
  private val DbzTimestamp = "io.debezium.time.Timestamp"
  private val DbzMicroTimestamp = "io.debezium.time.MicroTimestamp"
  private val DbzNanoTimestamp = "io.debezium.time.NanoTimestamp"
  private val DbzZonedTime = "io.debezium.time.ZonedTime"
  private val DbzZonedTimestamp = "io.debezium.time.ZonedTimestamp"
  private val DbzJson = "io.debezium.data.Json"

  private val time3 = timeFormatter(3)
  private val time6 = timeFormatter(6)
  private val time9 = timeFormatter(9)
  private val timestamp3 = timestampFormatter(3)
  private val timestamp6 = timestampFormatter(6)
  private val timestamp9 = timestampFormatter(9)

  def decode(record: SourceRecord, connectorId: String): Either[CdcDecodeFailure, Option[MySQLCdcEnvelope]] =
    attempt {
      nonEmpty(connectorId, "connectorId")
      if (record == null) fail("source record must not be null")
      else if (record.value() == null || isMetadata(record)) None
      else Some(decodeData(record, connectorId))
    }

  def parse(json: String): Either[CdcDecodeFailure, MySQLCdcEnvelope] =
    attempt(parseJson(json))

  private def attempt[A](body: => A): Either[CdcDecodeFailure, A] =
    try Right(body)
    catch {
      case error: DecodeException => Left(CdcDecodeFailure(error.getMessage))
      case NonFatal(error) => Left(CdcDecodeFailure(
        s"malformed CDC value: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}"))
    }

  private def decodeData(record: SourceRecord, connectorId: String): MySQLCdcEnvelope = {
    val valueSchema = schema(record.valueSchema(), "value")
    requireType(valueSchema, Schema.Type.STRUCT, "value")
    val value = struct(record.value(), "value")
    requireStructSchema(valueSchema, value, "value")
    val op = CdcOperation.parse(requiredString(value, "op", "value"))
    val key = convertStruct(schema(record.keySchema(), "key"), struct(record.key(), "key"), "key")
    if (key.fields.isEmpty) fail("key must contain at least one field")
    key.fields.collectFirst { case (name, JsNull) => name }.foreach(name => fail(s"key.$name is required"))
    val before = image(value, "before")
    val after = image(value, "after")
    requireImages(op, before, after)
    val sourceValue = requiredStruct(value, "source", "value")
    val source = MySQLCdcSourcePosition(
      connectorId,
      requiredString(sourceValue, "db", "source"),
      requiredString(sourceValue, "table", "source"),
      snapshot(sourceValue),
      optionalString(sourceValue, "file", "source"),
      optionalLong(sourceValue, "pos", "source"),
      optionalInt(sourceValue, "row", "source"),
      optionalLong(sourceValue, "ts_ms", "source")
    )
    MySQLCdcEnvelope(Version, op, key, before, after, source)
  }

  private def image(envelope: Struct, name: String): Option[JsObject] = {
    val imageField = field(envelope.schema(), name, "value")
    requireType(imageField.schema(), Schema.Type.STRUCT, s"value.$name")
    Option(envelope.getWithoutDefault(imageField.name())).map(value =>
      convertStruct(imageField.schema(), struct(value, s"value.$name"), s"value.$name"))
  }

  private def requireImages(op: CdcOperation, before: Option[JsObject], after: Option[JsObject]): Unit = op match {
    case CdcOperation.Read if after.isEmpty => fail("operation r requires after image")
    case CdcOperation.Create if after.isEmpty => fail("operation c requires after image")
    case CdcOperation.Update if before.isEmpty => fail("operation u requires before image")
    case CdcOperation.Update if after.isEmpty => fail("operation u requires after image")
    case CdcOperation.Delete if before.isEmpty => fail("operation d requires before image")
    case _ => ()
  }

  private def convertStruct(expected: Schema, value: Struct, path: String): JsObject = {
    requireType(expected, Schema.Type.STRUCT, path)
    requireStructSchema(expected, value, path)
    JsObject(expected.fields().asScala.map(f =>
      f.name() -> convert(f.schema(), value.getWithoutDefault(f.name()), s"$path.${f.name()}" )).toMap)
  }

  private def convert(schema: Schema, value: Any, path: String): JsValue = {
    if (value == null && schema.isOptional) JsNull
    else if (value == null) fail(s"$path is required")
    else Option(schema.name()) match {
      case Some(DecimalName) =>
        requireType(schema, Schema.Type.BYTES, path)
        value match {
          case decimal: JBigDecimal => JsString(decimal.toPlainString)
          case _ => fail(s"$path must be a BigDecimal")
        }
      case Some(DbzJson) => stringValue(schema, value, path)
      case Some(ConnectDateName) => connectDate(schema, value, path)
      case Some(ConnectTimeName) => connectTime(schema, value, path)
      case Some(ConnectTimestampName) => connectTimestamp(schema, value, path)
      case Some(DbzDate) => dbzDate(schema, value, path)
      case Some(DbzTime) => dbzTime(schema, value, path, 1000000L, time3)
      case Some(DbzMicroTime) => dbzTime(schema, value, path, 1000L, time6)
      case Some(DbzNanoTime) => dbzTime(schema, value, path, 1L, time9)
      case Some(DbzTimestamp) => dbzTimestamp(schema, value, path, 1000L, timestamp3)
      case Some(DbzMicroTimestamp) => dbzTimestamp(schema, value, path, 1000000L, timestamp6)
      case Some(DbzNanoTimestamp) => dbzTimestamp(schema, value, path, 1000000000L, timestamp9)
      case Some(DbzZonedTime) => dbzZonedTime(schema, value, path)
      case Some(DbzZonedTimestamp) => dbzZonedTimestamp(schema, value, path)
      case Some(name) => fail(s"unsupported logical schema at $path: $name")
      case None => primitive(schema, value, path)
    }
  }

  private def primitive(schema: Schema, value: Any, path: String): JsValue = schema.`type`() match {
    case Schema.Type.BOOLEAN => value match { case v: java.lang.Boolean => JsBoolean(v); case _ => wrong(path, schema, value) }
    case Schema.Type.INT8 => value match { case v: java.lang.Byte => JsNumber(v.longValue()); case _ => wrong(path, schema, value) }
    case Schema.Type.INT16 => value match { case v: java.lang.Short => JsNumber(v.longValue()); case _ => wrong(path, schema, value) }
    case Schema.Type.INT32 => value match { case v: java.lang.Integer => JsNumber(v.longValue()); case _ => wrong(path, schema, value) }
    case Schema.Type.INT64 => value match { case v: java.lang.Long => JsNumber(v.longValue()); case _ => wrong(path, schema, value) }
    case Schema.Type.FLOAT32 => value match {
      case v: java.lang.Float if java.lang.Float.isFinite(v) => JsNumber(BigDecimal.decimal(v.floatValue()))
      case _: java.lang.Float => fail(s"$path must be finite")
      case _ => wrong(path, schema, value)
    }
    case Schema.Type.FLOAT64 => value match {
      case v: java.lang.Double if java.lang.Double.isFinite(v) => JsNumber(BigDecimal.decimal(v.doubleValue()))
      case _: java.lang.Double => fail(s"$path must be finite")
      case _ => wrong(path, schema, value)
    }
    case Schema.Type.STRING => value match { case v: String => JsString(v); case _ => wrong(path, schema, value) }
    case other => fail(s"unsupported Connect schema type at $path: $other")
  }

  private def connectDate(s: Schema, value: Any, path: String): JsString = {
    requireType(s, Schema.Type.INT32, path)
    value match {
      case v: Date if Math.floorMod(v.getTime, 86400000L) == 0L =>
        JsString(v.toInstant.atZone(ZoneOffset.UTC).toLocalDate.toString)
      case _: Date => fail(s"$path must represent UTC midnight")
      case _ => wrong(path, s, value)
    }
  }

  private def connectTime(s: Schema, value: Any, path: String): JsString = {
    requireType(s, Schema.Type.INT32, path)
    value match {
      case v: Date =>
        val millis = v.getTime
        if (millis < 0L || millis >= 86400000L) fail(s"$path must be within a 24-hour day")
        JsString(time3.format(LocalTime.ofNanoOfDay(millis * 1000000L)))
      case _ => wrong(path, s, value)
    }
  }

  private def connectTimestamp(s: Schema, value: Any, path: String): JsString = {
    requireType(s, Schema.Type.INT64, path)
    value match {
      case v: Date => JsString(DateTimeFormatter.ISO_INSTANT.format(v.toInstant))
      case _ => wrong(path, s, value)
    }
  }

  private def dbzDate(s: Schema, value: Any, path: String): JsString = {
    requireType(s, Schema.Type.INT32, path)
    value match {
      case v: java.lang.Integer => JsString(LocalDate.ofEpochDay(v.longValue()).toString)
      case _ => wrong(path, s, value)
    }
  }

  private def dbzTime(s: Schema, value: Any, path: String, nanosPerUnit: Long, formatter: DateTimeFormatter): JsString = {
    val units = (s.`type`(), value) match {
      case (Schema.Type.INT32, v: java.lang.Integer) => v.longValue()
      case (Schema.Type.INT64, v: java.lang.Long) => v.longValue()
      case (Schema.Type.INT32 | Schema.Type.INT64, _) => wrong(path, s, value)
      case (other, _) => fail(s"$path temporal schema must be INT32 or INT64, got $other")
    }
    val nanos = try Math.multiplyExact(units, nanosPerUnit)
    catch { case _: ArithmeticException => fail(s"$path temporal value is out of range") }
    if (nanos < 0L || nanos >= 86400000000000L) fail(s"$path is outside a 24-hour day")
    JsString(formatter.format(LocalTime.ofNanoOfDay(nanos)))
  }

  private def dbzTimestamp(s: Schema, value: Any, path: String, unitsPerSecond: Long, formatter: DateTimeFormatter): JsString = {
    requireType(s, Schema.Type.INT64, path)
    val units = value match {
      case v: java.lang.Long => v.longValue()
      case _ => wrong(path, s, value)
    }
    val seconds = Math.floorDiv(units, unitsPerSecond)
    val remainder = Math.floorMod(units, unitsPerSecond)
    val instant = Instant.ofEpochSecond(seconds, remainder * (1000000000L / unitsPerSecond))
    JsString(formatter.format(LocalDateTime.ofInstant(instant, ZoneOffset.UTC)))
  }

  private def stringValue(s: Schema, value: Any, path: String): JsString = {
    requireType(s, Schema.Type.STRING, path)
    value match { case v: String => JsString(v); case _ => wrong(path, s, value) }
  }

  private def dbzZonedTime(s: Schema, value: Any, path: String): JsString = {
    val result = stringValue(s, value, path)
    try OffsetTime.parse(result.value, DateTimeFormatter.ISO_OFFSET_TIME)
    catch { case _: DateTimeParseException => fail(s"$path must be a valid Debezium ZonedTime") }
    result
  }

  private def dbzZonedTimestamp(s: Schema, value: Any, path: String): JsString = {
    val result = stringValue(s, value, path)
    try OffsetDateTime.parse(result.value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    catch { case _: DateTimeParseException => fail(s"$path must be a valid Debezium ZonedTimestamp") }
    result
  }

  private def parseJson(json: String): MySQLCdcEnvelope = {
    val root = json.parseJson match { case v: JsObject => v; case _ => fail("envelope must be an object") }
    exact(root, Set("version", "op", "key", "before", "after", "source"), "envelope")
    val version = int(root, "version", "envelope")
    if (version != Version) fail(s"unsupported CDC envelope version: $version")
    val op = CdcOperation.parse(jsonString(root, "op", "envelope"))
    val key = jsonObject(root, "key", "envelope")
    if (key.fields.isEmpty) fail("envelope.key must contain at least one field")
    scalars(key, "envelope.key")
    key.fields.collectFirst { case (name, JsNull) => name }.foreach(name => fail(s"envelope.key.$name is required"))
    val before = optionalObject(root, "before", "envelope")
    val after = optionalObject(root, "after", "envelope")
    before.foreach(scalars(_, "envelope.before"))
    after.foreach(scalars(_, "envelope.after"))
    requireImages(op, before, after)
    val source = jsonObject(root, "source", "envelope")
    exact(source, Set("connectorId", "database", "table", "snapshot", "file", "position", "row", "eventTimestampMillis"), "envelope.source")
    val result = MySQLCdcSourcePosition(
      jsonString(source, "connectorId", "envelope.source"),
      jsonString(source, "database", "envelope.source"),
      jsonString(source, "table", "envelope.source"),
      boolean(source, "snapshot", "envelope.source"),
      optionalJsonString(source, "file", "envelope.source"),
      optionalJsonLong(source, "position", "envelope.source"),
      optionalJsonInt(source, "row", "envelope.source"),
      optionalJsonLong(source, "eventTimestampMillis", "envelope.source")
    )
    MySQLCdcEnvelope(version, op, key, before, after, result)
  }

  private def isMetadata(record: SourceRecord): Boolean = {
    val valueSchema = record.valueSchema()
    record.value() match {
      case value: Struct if valueSchema != null && value.schema() == valueSchema =>
        isHeartbeatSchema(valueSchema) || isMySqlSchemaChangeSchema(valueSchema)
      case _ => false
    }
  }

  private def isHeartbeatSchema(s: Schema): Boolean =
    s.`type`() == Schema.Type.STRUCT && s.name() == "io.debezium.connector.common.Heartbeat" &&
      s.version() == Integer.valueOf(1) && exactSchemaFields(s, Map("ts_ms" -> Schema.Type.INT64))

  private def isMySqlSchemaChangeSchema(s: Schema): Boolean =
    s.`type`() == Schema.Type.STRUCT && s.name() == "io.debezium.connector.mysql.SchemaChangeValue" &&
      s.version() == Integer.valueOf(1) && exactSchemaFields(s, Map(
        "source" -> Schema.Type.STRUCT,
        "ts_ms" -> Schema.Type.INT64,
        "databaseName" -> Schema.Type.STRING,
        "schemaName" -> Schema.Type.STRING,
        "ddl" -> Schema.Type.STRING,
        "tableChanges" -> Schema.Type.ARRAY
      ))

  private def exactSchemaFields(s: Schema, expected: Map[String, Schema.Type]): Boolean = {
    val actual = s.fields().asScala.map(field => field.name() -> field.schema().`type`()).toMap
    actual == expected
  }

  private def snapshot(value: Struct): Boolean = {
    val f = field(value.schema(), "snapshot", "source")
    (f.schema().`type`(), Option(value.getWithoutDefault(f.name()))) match {
      case (Schema.Type.BOOLEAN, Some(v: java.lang.Boolean)) => v
      case (Schema.Type.STRING, Some("false")) => false
      case (Schema.Type.STRING, Some("true" | "first" | "last" | "incremental")) => true
      case (Schema.Type.STRING, Some(v)) => fail(s"source.snapshot has unsupported value: $v")
      case (_, None) => false
      case _ => wrong("source.snapshot", f.schema(), value.getWithoutDefault(f.name()))
    }
  }

  private def requiredString(value: Struct, name: String, path: String): String = {
    val f = field(value.schema(), name, path)
    requireType(f.schema(), Schema.Type.STRING, s"$path.$name")
    value.getWithoutDefault(f.name()) match {
      case v: String => nonEmpty(v, s"$path.$name")
      case _ => fail(s"$path.$name must be a non-empty string")
    }
  }

  private def optionalString(value: Struct, name: String, path: String): Option[String] = {
    val f = field(value.schema(), name, path)
    requireType(f.schema(), Schema.Type.STRING, s"$path.$name")
    Option(value.getWithoutDefault(f.name())).map {
      case v: String => v
      case v => wrong(s"$path.$name", f.schema(), v)
    }
  }

  private def optionalLong(value: Struct, name: String, path: String): Option[Long] = {
    val f = field(value.schema(), name, path)
    requireType(f.schema(), Schema.Type.INT64, s"$path.$name")
    Option(value.getWithoutDefault(f.name())).map {
      case v: java.lang.Long => v.longValue()
      case v => wrong(s"$path.$name", f.schema(), v)
    }
  }

  private def optionalInt(value: Struct, name: String, path: String): Option[Int] = {
    val f = field(value.schema(), name, path)
    requireType(f.schema(), Schema.Type.INT32, s"$path.$name")
    Option(value.getWithoutDefault(f.name())).map {
      case v: java.lang.Integer => v.intValue()
      case v => wrong(s"$path.$name", f.schema(), v)
    }
  }

  private def requiredStruct(value: Struct, name: String, path: String): Struct = {
    val f = field(value.schema(), name, path)
    requireType(f.schema(), Schema.Type.STRUCT, s"$path.$name")
    val result = Option(value.getWithoutDefault(f.name())).map(struct(_, s"$path.$name")).getOrElse(fail(s"$path.$name is required"))
    requireStructSchema(f.schema(), result, s"$path.$name")
    result
  }

  private def schema(value: Schema, path: String): Schema = Option(value).getOrElse(fail(s"$path schema is required"))
  private def field(s: Schema, name: String, path: String): Field = Option(s.field(name)).getOrElse(fail(s"$path.$name schema field is required"))
  private def struct(value: Any, path: String): Struct = value match { case v: Struct => v; case _ => fail(s"$path must be a Struct") }
  private def requireStructSchema(expected: Schema, value: Struct, path: String): Unit =
    if (value.schema() != expected) fail(s"$path Struct schema does not match declared schema")
  private def requireType(s: Schema, expected: Schema.Type, path: String): Unit =
    if (s.`type`() != expected) fail(s"$path schema must be $expected, got ${s.`type`()}")
  private def nonEmpty(value: String, path: String): String = { if (value == null || value.isEmpty) fail(s"$path must not be empty"); value }
  private def wrong(path: String, s: Schema, value: Any): Nothing =
    fail(s"$path value ${Option(value).map(_.getClass.getName).getOrElse("null")} does not match Connect schema ${s.`type`()}")

  private def exact(v: JsObject, expected: Set[String], path: String): Unit =
    if (v.fields.keySet != expected) fail(s"$path fields must be exactly ${expected.toVector.sorted.mkString(", ")}")
  private def jsonObject(v: JsObject, name: String, path: String): JsObject = v.fields.get(name) match {
    case Some(result: JsObject) => result; case _ => fail(s"$path.$name must be an object")
  }
  private def optionalObject(v: JsObject, name: String, path: String): Option[JsObject] = v.fields.get(name) match {
    case Some(JsNull) => None; case Some(result: JsObject) => Some(result); case _ => fail(s"$path.$name must be an object or null")
  }
  private def jsonString(v: JsObject, name: String, path: String): String = v.fields.get(name) match {
    case Some(JsString(result)) => nonEmpty(result, s"$path.$name"); case _ => fail(s"$path.$name must be a non-empty string")
  }
  private def optionalJsonString(v: JsObject, name: String, path: String): Option[String] = v.fields.get(name) match {
    case Some(JsNull) => None; case Some(JsString(result)) => Some(result); case _ => fail(s"$path.$name must be a string or null")
  }
  private def boolean(v: JsObject, name: String, path: String): Boolean = v.fields.get(name) match {
    case Some(JsBoolean(result)) => result; case _ => fail(s"$path.$name must be a boolean")
  }
  private def int(v: JsObject, name: String, path: String): Int = v.fields.get(name) match {
    case Some(JsNumber(n)) => try n.toIntExact catch { case NonFatal(_) => fail(s"$path.$name must be an Int") }
    case _ => fail(s"$path.$name must be an Int")
  }
  private def optionalJsonLong(v: JsObject, name: String, path: String): Option[Long] = v.fields.get(name) match {
    case Some(JsNull) => None
    case Some(JsNumber(n)) => try Some(n.toLongExact) catch { case NonFatal(_) => fail(s"$path.$name must be a Long or null") }
    case _ => fail(s"$path.$name must be a Long or null")
  }
  private def optionalJsonInt(v: JsObject, name: String, path: String): Option[Int] =
    optionalJsonLong(v, name, path).map(n => try Math.toIntExact(n) catch { case _: ArithmeticException => fail(s"$path.$name must be an Int or null") })
  private def scalars(v: JsObject, path: String): Unit = v.fields.foreach {
    case (_, JsNull | _: JsBoolean | _: JsNumber | _: JsString) => ()
    case (name, _) => fail(s"$path.$name must be a scalar JSON value")
  }

  private def timeFormatter(digits: Int): DateTimeFormatter = new DateTimeFormatterBuilder()
    .appendPattern("HH:mm:ss").appendFraction(ChronoField.NANO_OF_SECOND, digits, digits, true).toFormatter
  private def timestampFormatter(digits: Int): DateTimeFormatter = new DateTimeFormatterBuilder()
    .appendPattern("yyyy-MM-dd'T'HH:mm:ss").appendFraction(ChronoField.NANO_OF_SECOND, digits, digits, true).toFormatter

  private[cdc] def canonicalJson(value: JsValue): String = value match {
    case JsObject(fields) => fields.toVector.sortBy(_._1).map { case (key, child) =>
      JsString(key).compactPrint + ":" + canonicalJson(child)
    }.mkString("{", ",", "}")
    case JsArray(elements) => elements.map(canonicalJson).mkString("[", ",", "]")
    case scalar => scalar.compactPrint
  }

  private[cdc] def fail(message: String): Nothing = throw new DecodeException(message)
  private final class DecodeException(message: String) extends RuntimeException(message)
}
