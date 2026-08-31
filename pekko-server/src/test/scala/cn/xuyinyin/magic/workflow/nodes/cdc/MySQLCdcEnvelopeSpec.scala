package cn.xuyinyin.magic.workflow.nodes.cdc

import cn.xuyinyin.magic.testkit.STSpec
import io.debezium.data.{Json => DebeziumJson}
import io.debezium.data.geometry.Geometry
import io.debezium.time.{Date => DebeziumDate, MicroTime, MicroTimestamp, NanoTime, NanoTimestamp, Time => DebeziumTime, Timestamp => DebeziumTimestamp, ZonedTime, ZonedTimestamp}
import org.apache.kafka.connect.data.{Decimal, Schema, SchemaBuilder, Struct}
import org.apache.kafka.connect.source.SourceRecord
import org.scalatest.OptionValues
import spray.json._

import java.math.{BigDecimal => JBigDecimal}
import java.util.{Collections, Date, TimeZone}

class MySQLCdcEnvelopeSpec extends STSpec with OptionValues {
  private val connectorId = "orders-cdc-v1"

  private val keySchema = SchemaBuilder.struct()
    .name("pekko_workflow.orders.Key")
    .field("id", Schema.INT32_SCHEMA)
    .build()

  private val rowSchema = SchemaBuilder.struct()
    .name("pekko_workflow.orders.Value")
    .optional()
    .field("status", Schema.OPTIONAL_STRING_SCHEMA)
    .field("id", Schema.INT32_SCHEMA)
    .field("amount", Decimal.builder(2).optional().build())
    .field("note", Schema.OPTIONAL_STRING_SCHEMA)
    .build()

  private val sourceSchema = SchemaBuilder.struct()
    .name("io.debezium.connector.mysql.Source")
    .field("db", Schema.STRING_SCHEMA)
    .field("table", Schema.STRING_SCHEMA)
    .field("snapshot", Schema.OPTIONAL_STRING_SCHEMA)
    .field("file", Schema.OPTIONAL_STRING_SCHEMA)
    .field("pos", Schema.OPTIONAL_INT64_SCHEMA)
    .field("row", Schema.OPTIONAL_INT32_SCHEMA)
    .field("ts_ms", Schema.OPTIONAL_INT64_SCHEMA)
    .build()

  private val envelopeSchema = SchemaBuilder.struct()
    .name("pekko_workflow.orders.Envelope")
    .field("before", rowSchema)
    .field("after", rowSchema)
    .field("source", sourceSchema)
    .field("op", Schema.STRING_SCHEMA)
    .field("ts_ms", Schema.OPTIONAL_INT64_SCHEMA)
    .build()

  "MySQLCdcEnvelope.decode" should {
    "map r to Read and require the after image" in {
      val decoded = success(record("r", None, Some(row(42, "new"))))

      decoded.op shouldBe CdcOperation.Read
      decoded.before shouldBe None
      decoded.after.value.fields("status") shouldBe JsString("new")
    }

    "treat Debezium's first snapshot marker as a snapshot record" in {
      val actual = record("r", None, Some(row(42, "new")))
      actual.value().asInstanceOf[Struct].getStruct("source").put("snapshot", "first")

      success(actual).source.snapshot shouldBe true
    }

    "map c to Create and preserve a null column as JSON null" in {
      val decoded = success(record("c", None, Some(row(42, "new", note = None))))

      decoded.op shouldBe CdcOperation.Create
      decoded.after.value.fields("note") shouldBe JsNull
    }

    "map u to Update rather than Create and retain both row images" in {
      val decoded = success(record("u", Some(row(42, "new")), Some(row(42, "paid"))))

      decoded.op shouldBe CdcOperation.Update
      decoded.key shouldBe JsObject("id" -> JsNumber(42))
      decoded.before.value.fields("status") shouldBe JsString("new")
      decoded.after.value.fields("status") shouldBe JsString("paid")
      decoded.source shouldBe MySQLCdcSourcePosition(
        connectorId = connectorId,
        database = "pekko_workflow",
        table = "orders",
        snapshot = false,
        file = Some("binlog.000012"),
        position = Some(2805470L),
        row = Some(0),
        eventTimestampMillis = Some(1788100000000L)
      )
    }

    "map d to Delete and retain the key and before image" in {
      val decoded = success(record("d", Some(row(42, "paid")), None))

      decoded.op shouldBe CdcOperation.Delete
      decoded.key shouldBe JsObject("id" -> JsNumber(42))
      decoded.before.value.fields("status") shouldBe JsString("paid")
      decoded.after shouldBe None
    }

    "preserve decimal scale and precision as a plain JSON string" in {
      val decoded = success(record("c", None, Some(row(42, "new", new JBigDecimal("12345678901234567890.10")))))

      decoded.after.value.fields("amount") shouldBe JsString("12345678901234567890.10")
    }

    "convert every supported primitive without stringifying integral or floating values" in {
      val schema = SchemaBuilder.struct().name("supported.primitives").optional()
        .field("bool", Schema.BOOLEAN_SCHEMA)
        .field("int8", Schema.INT8_SCHEMA)
        .field("int16", Schema.INT16_SCHEMA)
        .field("int32", Schema.INT32_SCHEMA)
        .field("int64", Schema.INT64_SCHEMA)
        .field("float32", Schema.FLOAT32_SCHEMA)
        .field("float64", Schema.FLOAT64_SCHEMA)
        .field("text", Schema.STRING_SCHEMA)
        .field("json", DebeziumJson.builder().build())
        .build()
      val value = new Struct(schema)
        .put("bool", true)
        .put("int8", 7.toByte)
        .put("int16", 300.toShort)
        .put("int32", 70000)
        .put("int64", 9007199254740993L)
        .put("float32", 1.5f)
        .put("float64", 2.25d)
        .put("text", "hello")
        .put("json", "{\"nested\":true}")

      success(recordWithImageSchema("c", schema, None, Some(value))).after.value shouldBe JsObject(
        "bool" -> JsBoolean(true),
        "int8" -> JsNumber(7),
        "int16" -> JsNumber(300),
        "int32" -> JsNumber(70000),
        "int64" -> JsNumber(9007199254740993L),
        "float32" -> JsNumber(BigDecimal("1.5")),
        "float64" -> JsNumber(BigDecimal("2.25")),
        "text" -> JsString("hello"),
        "json" -> JsString("{\"nested\":true}")
      )
    }

    "format actual Debezium logical date time and timestamp schemas deterministically" in {
      val schema = SchemaBuilder.struct().name("supported.temporal").optional()
        .field("date", DebeziumDate.builder().build())
        .field("time", DebeziumTime.builder().build())
        .field("microTime", MicroTime.builder().build())
        .field("nanoTime", NanoTime.builder().build())
        .field("timestamp", DebeziumTimestamp.builder().build())
        .field("microTimestamp", MicroTimestamp.builder().build())
        .field("nanoTimestamp", NanoTimestamp.builder().build())
        .field("zonedTime", ZonedTime.builder().build())
        .field("zonedTimestamp", ZonedTimestamp.builder().build())
        .build()
      val value = new Struct(schema)
        .put("date", 19724)
        .put("time", 3723456)
        .put("microTime", 3723456789L)
        .put("nanoTime", 3723456789123L)
        .put("timestamp", 1704164645123L)
        .put("microTimestamp", 1704164645123456L)
        .put("nanoTimestamp", 1704164645123456789L)
        .put("zonedTime", "01:02:03.456789+02:00")
        .put("zonedTimestamp", "2024-01-02T03:04:05.123456Z")

      success(recordWithImageSchema("c", schema, None, Some(value))).after.value shouldBe JsObject(
        "date" -> JsString("2024-01-02"),
        "time" -> JsString("01:02:03.456"),
        "microTime" -> JsString("01:02:03.456789"),
        "nanoTime" -> JsString("01:02:03.456789123"),
        "timestamp" -> JsString("2024-01-02T03:04:05.123"),
        "microTimestamp" -> JsString("2024-01-02T03:04:05.123456"),
        "nanoTimestamp" -> JsString("2024-01-02T03:04:05.123456789"),
        "zonedTime" -> JsString("01:02:03.456789+02:00"),
        "zonedTimestamp" -> JsString("2024-01-02T03:04:05.123456Z")
      )
    }

    "format Kafka Connect logical temporals identically under different JVM default time zones" in {
      val schema = SchemaBuilder.struct().name("supported.connect.temporal").optional()
        .field("date", org.apache.kafka.connect.data.Date.builder().build())
        .field("time", org.apache.kafka.connect.data.Time.builder().build())
        .field("timestamp", org.apache.kafka.connect.data.Timestamp.builder().build())
        .build()
      val value = new Struct(schema)
        .put("date", new Date(0L))
        .put("time", new Date(3723456L))
        .put("timestamp", new Date(1704164645123L))
      val original = TimeZone.getDefault
      try {
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
        val first = success(recordWithImageSchema("c", schema, None, Some(value))).after.value
        TimeZone.setDefault(TimeZone.getTimeZone("America/Adak"))
        val second = success(recordWithImageSchema("c", schema, None, Some(value))).after.value

        first shouldBe second
        first shouldBe JsObject(
          "date" -> JsString("1970-01-01"),
          "time" -> JsString("01:02:03.456"),
          "timestamp" -> JsString("2024-01-02T03:04:05.123Z")
        )
      } finally TimeZone.setDefault(original)
    }

    "reject a Kafka Connect Date that is not UTC midnight instead of truncating its time" in {
      val schema = SchemaBuilder.struct().name("invalid.connect.date").optional()
        .field("date", org.apache.kafka.connect.data.Date.builder().build()).build()
      val value = new Struct(schema).put("date", new Date(1L))

      failure(recordWithImageSchema("c", schema, None, Some(value))).message should include("midnight")
    }

    "reject a negative Kafka Connect Time instead of wrapping it into the previous day" in {
      val schema = SchemaBuilder.struct().name("invalid.connect.time.negative").optional()
        .field("time", org.apache.kafka.connect.data.Time.builder().build()).build()
      val value = new Struct(schema).put("time", new Date(-1L))

      failure(recordWithImageSchema("c", schema, None, Some(value))).message should include("24-hour day")
    }

    "reject a Kafka Connect Time at the next-day boundary instead of wrapping it to midnight" in {
      val schema = SchemaBuilder.struct().name("invalid.connect.time.next.day").optional()
        .field("time", org.apache.kafka.connect.data.Time.builder().build()).build()
      val value = new Struct(schema).put("time", new Date(86400000L))

      failure(recordWithImageSchema("c", schema, None, Some(value))).message should include("24-hour day")
    }

    "reject an invalid Debezium ZonedTime string instead of passing it through" in {
      val schema = SchemaBuilder.struct().name("invalid.zoned.time").optional()
        .field("time", ZonedTime.builder().build()).build()
      val value = new Struct(schema).put("time", "25:00:00+02:00")

      failure(recordWithImageSchema("c", schema, None, Some(value))).message should include("ZonedTime")
    }

    "reject an invalid Debezium ZonedTimestamp string instead of passing it through" in {
      val schema = SchemaBuilder.struct().name("invalid.zoned.timestamp").optional()
        .field("timestamp", ZonedTimestamp.builder().build()).build()
      val value = new Struct(schema).put("timestamp", "2024-02-30T03:04:05Z")

      failure(recordWithImageSchema("c", schema, None, Some(value))).message should include("ZonedTimestamp")
    }

    "sort every object level in canonical JSON instead of retaining schema field order" in {
      val decoded = success(record("u", Some(row(42, "new")), Some(row(42, "paid"))))

      decoded.canonicalJson shouldBe
        "{\"after\":{\"amount\":\"12.30\",\"id\":42,\"note\":null,\"status\":\"paid\"},\"before\":{\"amount\":\"12.30\",\"id\":42,\"note\":null,\"status\":\"new\"},\"key\":{\"id\":42},\"op\":\"u\",\"source\":{\"connectorId\":\"orders-cdc-v1\",\"database\":\"pekko_workflow\",\"eventTimestampMillis\":1788100000000,\"file\":\"binlog.000012\",\"position\":2805470,\"row\":0,\"snapshot\":false,\"table\":\"orders\"},\"version\":1}"
    }

    "filter a tombstone instead of leaking it into the mutation stream" in {
      MySQLCdcEnvelope.decode(tombstoneRecord, connectorId) shouldBe Right(None)
    }

    "filter a heartbeat instead of treating its Struct as a malformed data event" in {
      val schema = SchemaBuilder.struct().name("io.debezium.connector.common.Heartbeat")
        .version(1)
        .field("ts_ms", Schema.INT64_SCHEMA).build()
      val heartbeat = sourceRecord("server.heartbeat", keySchema, key(42), schema, new Struct(schema).put("ts_ms", 1L))

      MySQLCdcEnvelope.decode(heartbeat, connectorId) shouldBe Right(None)
    }

    "filter a schema-change record instead of treating it as a row mutation" in {
      val tableChangeSchema = SchemaBuilder.struct().name("io.debezium.connector.schema.Change")
        .field("type", Schema.STRING_SCHEMA).build()
      val schema = SchemaBuilder.struct().name("io.debezium.connector.mysql.SchemaChangeValue")
        .version(1)
        .field("source", sourceSchema)
        .field("ts_ms", Schema.INT64_SCHEMA)
        .field("databaseName", Schema.OPTIONAL_STRING_SCHEMA)
        .field("schemaName", Schema.OPTIONAL_STRING_SCHEMA)
        .field("ddl", Schema.OPTIONAL_STRING_SCHEMA)
        .field("tableChanges", SchemaBuilder.array(tableChangeSchema).build())
        .build()
      val schemaChange = sourceRecord("server.schema-changes", keySchema, key(42), schema, new Struct(schema)
        .put("source", source)
        .put("ts_ms", 1788100000000L)
        .put("databaseName", "pekko_workflow")
        .put("schemaName", null)
        .put("ddl", "alter table orders")
        .put("tableChanges", Collections.emptyList[Struct]()))

      MySQLCdcEnvelope.decode(schemaChange, connectorId) shouldBe Right(None)
    }

    "decode a valid row on a topic ending in heartbeat instead of applying a broad suffix filter" in {
      val decoded = success(onTopic(record("c", None, Some(row(42, "new"))), "server.orders.heartbeat"))

      decoded.op shouldBe CdcOperation.Create
      decoded.key shouldBe JsObject("id" -> JsNumber(42))
    }

    "decode a valid row on a topic containing schema-changes instead of applying a broad substring filter" in {
      val decoded = success(onTopic(record("c", None, Some(row(42, "new"))), "server.schema-changes.orders"))

      decoded.op shouldBe CdcOperation.Create
      decoded.after.value.fields("status") shouldBe JsString("new")
    }

    "reject truncate instead of accepting a mutation with no row images" in {
      failure(record("t", None, None)).message should include("truncate")
    }

    "reject an unknown operation instead of falling through to a default mapping" in {
      failure(record("x", None, Some(row(42, "new")))).message should include("operation")
    }

    "reject each operation when its required image is absent" in {
      val failures = Vector(
        failure(record("r", None, None)).message,
        failure(record("c", None, None)).message,
        failure(record("u", None, Some(row(42, "paid")))).message,
        failure(record("u", Some(row(42, "new")), None)).message,
        failure(record("d", None, None)).message
      )

      failures should contain theSameElementsAs Vector(
        "operation r requires after image",
        "operation c requires after image",
        "operation u requires before image",
        "operation u requires after image",
        "operation d requires before image"
      )
    }

    "reject raw bytes instead of using an unknown toString fallback" in {
      val schema = SchemaBuilder.struct().name("unsupported.bytes").optional()
        .field("payload", Schema.BYTES_SCHEMA).build()
      val value = new Struct(schema).put("payload", Array[Byte](1, 2, 3))

      failure(recordWithImageSchema("c", schema, None, Some(value))).message should include("unsupported")
    }

    "reject a Debezium spatial schema instead of flattening its Struct" in {
      val geometrySchema = Geometry.schema()
      val schema = SchemaBuilder.struct().name("unsupported.geometry").optional()
        .field("location", geometrySchema).build()
      val value = new Struct(schema).put("location", Geometry.createValue(geometrySchema, Array[Byte](1, 2), 4326))

      failure(recordWithImageSchema("c", schema, None, Some(value))).message should include("unsupported")
    }

    "reject a non-Struct value instead of trusting an unrelated schema" in {
      val malformed = sourceRecord("server.pekko_workflow.orders", keySchema, key(42), Schema.STRING_SCHEMA, "not-an-envelope")

      failure(malformed).message should include("STRUCT")
    }

    "reject a SourceRecord value schema that differs from its runtime Struct schema" in {
      val actual = record("c", None, Some(row(42, "new")))
      val declared = SchemaBuilder.struct().name("different.declared.Envelope")
        .field("ignored", Schema.STRING_SCHEMA).build()
      val mismatched = sourceRecord(actual.topic(), actual.keySchema(), actual.key().asInstanceOf[AnyRef], declared, actual.value().asInstanceOf[AnyRef])

      failure(mismatched).message should include("value Struct schema")
    }

    "reject a nested source Struct whose schema differs from the envelope source field schema" in {
      val otherSourceSchema = SchemaBuilder.struct().name("different.mysql.Source")
        .field("db", Schema.STRING_SCHEMA)
        .field("table", Schema.STRING_SCHEMA)
        .field("snapshot", Schema.OPTIONAL_STRING_SCHEMA)
        .field("file", Schema.OPTIONAL_STRING_SCHEMA)
        .field("pos", Schema.OPTIONAL_INT64_SCHEMA)
        .field("row", Schema.OPTIONAL_INT32_SCHEMA)
        .field("ts_ms", Schema.OPTIONAL_INT64_SCHEMA)
        .build()
      val otherSource = new Struct(otherSourceSchema)
        .put("db", "pekko_workflow")
        .put("table", "orders")
        .put("snapshot", "false")
        .put("file", "binlog.000012")
        .put("pos", 2805470L)
        .put("row", 0)
        .put("ts_ms", 1788100000000L)
      val actual = record("c", None, Some(row(42, "new")))
      putUnchecked(actual.value().asInstanceOf[Struct], "source", otherSource)

      failure(actual).message should include("source Struct schema")
    }

    "reject null in a required Connect row field instead of converting it to JSON null" in {
      val invalidRow = new Struct(rowSchema)
        .put("status", "new")
        .put("id", 42)
        .put("amount", new JBigDecimal("12.30"))
        .put("note", null)
      putUnchecked(invalidRow, "id", null)
      val actual = record("c", None, Some(row(42, "new")))
      putUnchecked(actual.value().asInstanceOf[Struct], "after", invalidRow)

      failure(actual).message should include("value.after.id is required")
    }

    "reject a null required primary-key value instead of accepting a non-actionable key" in {
      val actual = record("c", None, Some(row(42, "new")))
      val nullKey = new Struct(keySchema).put("id", 42)
      putUnchecked(nullKey, "id", null)
      val invalid = sourceRecord(actual.topic(), keySchema, nullKey, actual.valueSchema(), actual.value().asInstanceOf[AnyRef])

      failure(invalid).message should include("key.id is required")
    }
  }

  "MySQLCdcEnvelope.parse" should {
    "parse a hand-written authoritative canonical envelope literal" in {
      val literal =
        "{\"version\":1,\"op\":\"c\",\"key\":{\"id\":42},\"before\":null,\"after\":{\"amount\":\"12.30\",\"id\":42,\"status\":\"new\"},\"source\":{\"connectorId\":\"orders-cdc-v1\",\"database\":\"pekko_workflow\",\"table\":\"orders\",\"snapshot\":false,\"file\":\"binlog.000012\",\"position\":2805470,\"row\":0,\"eventTimestampMillis\":1788100000000}}"

      MySQLCdcEnvelope.parse(literal) shouldBe Right(MySQLCdcEnvelope(
        version = 1,
        op = CdcOperation.Create,
        key = JsObject("id" -> JsNumber(42)),
        before = None,
        after = Some(JsObject("amount" -> JsString("12.30"), "id" -> JsNumber(42), "status" -> JsString("new"))),
        source = MySQLCdcSourcePosition(
          connectorId = "orders-cdc-v1",
          database = "pekko_workflow",
          table = "orders",
          snapshot = false,
          file = Some("binlog.000012"),
          position = Some(2805470L),
          row = Some(0),
          eventTimestampMillis = Some(1788100000000L)
        )
      ))
    }

    "reject a null key value because parsed JSON has no Connect schema to prove it optional" in {
      val literal =
        "{\"version\":1,\"op\":\"c\",\"key\":{\"id\":null},\"before\":null,\"after\":{\"id\":42},\"source\":{\"connectorId\":\"orders-cdc-v1\",\"database\":\"pekko_workflow\",\"table\":\"orders\",\"snapshot\":false,\"file\":null,\"position\":null,\"row\":null,\"eventTimestampMillis\":null}}"

      MySQLCdcEnvelope.parse(literal) match {
        case Left(error) => error.message should include("envelope.key.id")
        case Right(value) => fail(s"null key unexpectedly parsed: $value")
      }
    }

    "round-trip the canonical boundary and reject an unsupported version" in {
      val decoded = success(record("u", Some(row(42, "new")), Some(row(42, "paid"))))

      MySQLCdcEnvelope.parse(decoded.canonicalJson) shouldBe Right(decoded)
      MySQLCdcEnvelope.parse(decoded.canonicalJson.replace("\"version\":1", "\"version\":2")) match {
        case Left(error) => error.message should include("version")
        case Right(value) => fail(s"unsupported version unexpectedly parsed: $value")
      }
    }
  }

  private def row(id: Int, status: String, amount: JBigDecimal = new JBigDecimal("12.30"), note: Option[String] = None): Struct =
    new Struct(rowSchema)
      .put("status", status)
      .put("id", id)
      .put("amount", amount)
      .put("note", note.orNull)

  private def key(id: Int): Struct = new Struct(keySchema).put("id", id)

  private def source: Struct = new Struct(sourceSchema)
    .put("db", "pekko_workflow")
    .put("table", "orders")
    .put("snapshot", "false")
    .put("file", "binlog.000012")
    .put("pos", 2805470L)
    .put("row", 0)
    .put("ts_ms", 1788100000000L)

  private def record(op: String, before: Option[Struct], after: Option[Struct]): SourceRecord = {
    val value = new Struct(envelopeSchema)
      .put("before", before.orNull)
      .put("after", after.orNull)
      .put("source", source)
      .put("op", op)
      .put("ts_ms", 1788100000100L)
    sourceRecord("server.pekko_workflow.orders", keySchema, key(42), envelopeSchema, value)
  }

  private def recordWithImageSchema(op: String, imageSchema: Schema, before: Option[Struct], after: Option[Struct]): SourceRecord = {
    val schema = SchemaBuilder.struct().name(s"fixture.$op.Envelope")
      .field("before", imageSchema)
      .field("after", imageSchema)
      .field("source", sourceSchema)
      .field("op", Schema.STRING_SCHEMA)
      .build()
    val value = new Struct(schema)
      .put("before", before.orNull)
      .put("after", after.orNull)
      .put("source", source)
      .put("op", op)
    sourceRecord("server.pekko_workflow.orders", keySchema, key(42), schema, value)
  }

  private def tombstoneRecord: SourceRecord =
    sourceRecord("server.pekko_workflow.orders", keySchema, key(42), null, null)

  private def onTopic(record: SourceRecord, topic: String): SourceRecord =
    sourceRecord(topic, record.keySchema(), record.key().asInstanceOf[AnyRef], record.valueSchema(), record.value().asInstanceOf[AnyRef])

  private def putUnchecked(struct: Struct, fieldName: String, value: AnyRef): Unit = {
    val valuesField = classOf[Struct].getDeclaredField("values")
    valuesField.setAccessible(true)
    val values = valuesField.get(struct).asInstanceOf[Array[AnyRef]]
    values(struct.schema().field(fieldName).index()) = value
  }

  private def sourceRecord(topic: String, actualKeySchema: Schema, actualKey: AnyRef, valueSchema: Schema, value: AnyRef): SourceRecord =
    new SourceRecord(
      Collections.singletonMap[String, String]("server", "server"),
      Collections.singletonMap[String, String]("file", "binlog.000012"),
      topic,
      actualKeySchema,
      actualKey,
      valueSchema,
      value
    )

  private def success(record: SourceRecord): MySQLCdcEnvelope =
    MySQLCdcEnvelope.decode(record, connectorId) match {
      case Right(Some(value)) => value
      case other => fail(s"expected decoded envelope, got $other")
    }

  private def failure(record: SourceRecord): CdcDecodeFailure =
    MySQLCdcEnvelope.decode(record, connectorId) match {
      case Left(error) => error
      case other => fail(s"expected decode failure, got $other")
    }
}
