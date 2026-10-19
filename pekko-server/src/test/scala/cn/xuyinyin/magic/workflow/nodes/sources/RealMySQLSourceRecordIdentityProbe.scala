package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.workflow.nodes.cdc.MySQLCdcEnvelope
import org.apache.kafka.connect.data.{Schema, Struct}
import org.apache.kafka.connect.source.SourceRecord
import spray.json._

import java.io.{FileOutputStream, OutputStreamWriter, PrintWriter}
import java.nio.file.{Files, Path}
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.jdk.CollectionConverters._

/** SourceRecord identity observation only. No Actor, Sink or new recovery protocol.
  * Withholding markProcessedAndFinished keeps the REAL JDBC offset at a controlled prefix.
  * Callback contents are never split, combined or rewritten by the probe.
  */
object RealMySQLSourceRecordIdentityProbe {
  def main(args: Array[String]): Unit = {
    require(sys.env.get("RUN_MYSQL_IDENTITY_PROBE").contains("1"), "explicit isolated-fixture opt-in required")
    require(args.length == 7, "expected schema connector port batchSize ackLimit bootstrap output")
    val schema = args(0); val connector = args(1); val port = args(2); val batchSize = args(3)
    val ackLimitText = args(4); val bootstrapText = args(5); val output = args(6)
    require(schema.matches("pekko_test_identity_[a-f0-9]{12}"))
    val password = sys.env("IDENTITY_MYSQL_PASSWORD")
    val source = MySQLCdcSourceConfig(connector, "127.0.0.1", port.toInt, schema, "source_rows",
      "root", password, 186019L, batchSize.toInt, 1000)
    val state = MySQLCdcStateConfig(s"jdbc:mysql://127.0.0.1:$port/$schema?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC",
      "root", password, "identity_offset", "identity_history", 0)
    val properties = MySQLCdcSourceNode.connectorProperties(source, state)
    val window = new AtomicBoolean(!bootstrapText.toBoolean)
    val firstWindow = new AtomicBoolean(true)
    val gate = new CountDownLatch(1)
    val batchSequence = new AtomicLong(0)
    val windowCount = new AtomicLong(0)
    val ackLimit = ackLimitText.toLong
    Files.createDirectories(Path.of(output))
    val stream = new FileOutputStream(Path.of(output, "records.jsonl").toFile)
    val writer = new PrintWriter(new OutputStreamWriter(stream, java.nio.charset.StandardCharsets.UTF_8))
    val engine = DebeziumEngineFactory.real.create(properties)
    emit("READY", "pid" -> JsNumber(ProcessHandle.current().pid()), "batchSize" -> JsNumber(batchSize.toInt),
      "ackLimit" -> JsNumber(ackLimit), "bootstrap" -> JsBoolean(bootstrapText.toBoolean))
    val done = engine.start(new DebeziumBatchConsumer {
      override def handleBatch(records: Vector[SourceRecord], handle: CdcBatchCommitHandle): Unit = {
        val inWindow = window.get()
        val number = batchSequence.getAndIncrement()
        val start = windowCount.get()
        val encoded = records.map(record => encode(record, source.connectorId))
        val count = if (inWindow) windowCount.addAndGet(records.size) else 0L
        val shouldAck = !inWindow || (ackLimit > 0 && count <= ackLimit)
        require(!inWindow || ackLimit <= start || count <= ackLimit,
          "ack prefix falls inside a callback; choose batchSize=1 for exact prefix")
        val batch = JsObject("batch" -> JsNumber(number), "window" -> JsBoolean(inWindow),
          "pid" -> JsNumber(ProcessHandle.current().pid()), "ackRequested" -> JsBoolean(shouldAck),
          "records" -> JsArray(encoded))
        writer.println(batch.compactPrint); writer.flush(); stream.getFD.sync()
        if (inWindow && firstWindow.compareAndSet(true, false)) {
          emit("FIRST_WINDOW_GATE", "batch" -> JsNumber(number), "records" -> JsNumber(records.size))
          gate.await()
        }
        if (shouldAck) {
          handle.markProcessedAndFinished()
          emit("ACK_RETURNED", "batch" -> JsNumber(number))
        }
        emit("BATCH_OBSERVED", "batch" -> JsNumber(number), "window" -> JsBoolean(inWindow), "count" -> JsNumber(count))
        if (records.exists(isSentinel)) emit("SENTINEL", "count" -> JsNumber(count))
      }
    })
    done.onComplete { result => emit("ENGINE_DONE", "result" -> JsString(result.toString)) }
    scala.io.Source.stdin.getLines().foreach {
      case "ARM" => window.set(true); emit("ARMED")
      case "RELEASE" => gate.countDown(); emit("RELEASED")
      case "CRASH" => writer.flush(); stream.getFD.sync(); System.out.flush(); Runtime.getRuntime.halt(86)
      case other => throw new IllegalArgumentException(s"unknown command: $other")
    }
    gate.countDown(); engine.close(); writer.close()
  }

  private def emit(kind: String, fields: (String, JsValue)*): Unit = synchronized {
    println("IDENTITY " + JsObject(fields.toMap + ("kind" -> JsString(kind))).compactPrint)
    System.out.flush()
  }

  private def isSentinel(record: SourceRecord): Boolean = record.value() match {
    case value: Struct if value.schema().field("after") != null =>
      Option(value.getStruct("after")).exists(row => row.schema().field("revision") != null && row.getInt32("revision") == 999)
    case _ => false
  }

  private[workflow] def encode(record: SourceRecord, connectorId: String): JsValue = {
    val normalized = MySQLCdcEnvelope.decode(record, connectorId) match {
      case Right(Some(envelope)) => envelope.canonicalJson.parseJson
      case Right(None) => JsNull
      case Left(error) => JsObject("decodeFailure" -> JsString(error.message))
    }
    JsObject("topic" -> JsString(record.topic()), "kafkaPartition" -> value(record.kafkaPartition()),
      "timestamp" -> value(record.timestamp()), "sourcePartition" -> value(record.sourcePartition()),
      "sourceOffset" -> value(record.sourceOffset()), "keySchema" -> schema(record.keySchema()),
      "valueSchema" -> schema(record.valueSchema()), "key" -> value(record.key()), "value" -> value(record.value()),
      "headers" -> JsArray(record.headers().iterator().asScala.map(h => JsObject(
        "key" -> JsString(h.key()), "schema" -> schema(h.schema()), "value" -> value(h.value()))).toVector),
      "normalizedEnvelope" -> normalized)
  }

  private def schema(s: Schema): JsValue = if (s == null) JsNull else JsObject(
    "type" -> JsString(s.`type`().toString), "name" -> value(s.name()), "version" -> value(s.version()),
    "optional" -> JsBoolean(s.isOptional), "parameters" -> value(s.parameters()),
    "fields" -> (if (s.`type`() == Schema.Type.STRUCT) JsArray(s.fields().asScala.map(f =>
      JsObject("name" -> JsString(f.name()), "schema" -> schema(f.schema()))).toVector) else JsArray()),
    "keySchema" -> (if (s.`type`() == Schema.Type.MAP) schema(s.keySchema()) else JsNull),
    "valueSchema" -> (if (Set(Schema.Type.ARRAY, Schema.Type.MAP).contains(s.`type`())) schema(s.valueSchema()) else JsNull))

  private def value(v: Any): JsValue = v match {
    case null => JsNull
    case s: Struct => JsObject(s.schema().fields().asScala.map(f => f.name() -> value(s.getWithoutDefault(f.name()))).toMap)
    case m: java.util.Map[_, _] => JsObject(m.asScala.map { case (k, x) => k.toString -> value(x) }.toMap)
    case xs: java.util.List[_] => JsArray(xs.asScala.map(value).toVector)
    case b: java.lang.Boolean => JsBoolean(b)
    case n: java.lang.Number => JsNumber(BigDecimal(n.toString))
    case x: String => JsString(x)
    case d: java.util.Date => JsObject("javaDateMillis" -> JsNumber(d.getTime))
    case bytes: Array[Byte] => JsObject("base64" -> JsString(java.util.Base64.getEncoder.encodeToString(bytes)))
    case x => throw new IllegalArgumentException("unhandled SourceRecord value type: " + x.getClass.getName)
  }
}
