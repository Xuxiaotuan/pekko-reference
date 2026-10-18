package cn.xuyinyin.magic.workflow.nodes.sources

import spray.json._
import java.io.{FileOutputStream, OutputStreamWriter, PrintWriter}
import java.nio.file.{Files, Path}
import java.util.concurrent.{ConcurrentHashMap, CountDownLatch}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global

/** TEST ONLY: raw MySQL records -> complete groups -> real production Bridge.
  * A manual coverage gate replaces T/J; no Sink/Actor or new production protocol is exercised.
  * Only the ordered callback caller invokes original Debezium commit handles.
  */
object RealMySQLDeferredAckProbe {
  def main(args: Array[String]): Unit = {
    require(sys.env.get("RUN_MYSQL_DEFERRED_ACK_PROBE").contains("1"))
    require(args.length == 6, "schema connector port batchSize bootstrap output")
    val schema = args(0); val connector = args(1); val port = args(2).toInt
    val size = args(3).toInt; val bootstrap = args(4).toBoolean; val output = Path.of(args(5))
    require(schema.matches("pekko_test_ack_[a-f0-9]{12}"))
    val password = sys.env("ACK_PROBE_MYSQL_PASSWORD")
    Files.createDirectories(output)
    val rawStream = new FileOutputStream(output.resolve("callbacks.jsonl").toFile)
    val raw = new PrintWriter(new OutputStreamWriter(rawStream, java.nio.charset.StandardCharsets.UTF_8))
    val traceStream = new FileOutputStream(output.resolve("trace.jsonl").toFile)
    val trace = new PrintWriter(new OutputStreamWriter(traceStream, java.nio.charset.StandardCharsets.UTF_8))
    val traceSequence = new AtomicLong(0)
    def emit(kind: String, fields: (String, JsValue)*): Unit = trace.synchronized {
      val line = JsObject(fields.toMap ++ Map("kind" -> JsString(kind), "seq" -> JsNumber(traceSequence.getAndIncrement()),
        "pid" -> JsNumber(ProcessHandle.current().pid()), "thread" -> JsString(Thread.currentThread().getName)))
      trace.println(line.compactPrint); trace.flush(); traceStream.getFD.sync()
      println("ACKPROBE " + line.compactPrint); System.out.flush()
    }
    def string(o: JsObject, k: String): String = o.fields(k).asInstanceOf[JsString].value
    def groupId(r: JsObject): String = {
      val s = r.fields("value").asJsObject.fields("source").asJsObject
      JsArray(Vector("db", "table", "file", "pos", "row").map(s.fields)).compactPrint
    }
    def header(r: JsObject, name: String): Option[JsValue] = r.fields("headers").asInstanceOf[JsArray].elements
      .map(_.asJsObject).find(h => h.fields("key") == JsString(name)).map(_.fields("value"))
    def op(r: JsObject): String = string(r.fields("value").asJsObject, "op")
    val inWindow = new AtomicBoolean(!bootstrap)
    val bridge = new DebeziumBatchBridge
    val activeDelivery = new AtomicReference[BridgedCdcBatch]()
    val covered = ConcurrentHashMap.newKeySet[String]()
    val firstDelayedAck = new AtomicBoolean(true)
    val afterAckGate = new CountDownLatch(1)
    val insideCallback = new AtomicBoolean(false)
    val marking = new AtomicBoolean(false)
    val callbacks = new AtomicLong(0)
    var deliverySequence = 0L
    var half = Option.empty[JsObject]
    var complete = Set.empty[String]
    case class Entry(id: Long, groups: Set[String], handle: CdcBatchCommitHandle)
    val pending = mutable.Queue.empty[Entry]

    val consumerThread = new Thread(() => {
      try while (true) {
        val delivery = bridge.take()
        require(activeDelivery.compareAndSet(null, delivery))
        emit("DELIVERY_READY", "delivery" -> JsString(delivery.batchId),
          "groups" -> JsArray(delivery.rows.map(_.parseJson)), "cursor" -> delivery.cursorValue.parseJson)
        // take() itself waits for the existing active delivery to be acknowledged.
      } catch { case e: IllegalStateException => emit("BRIDGE_READER_STOPPED", "reason" -> JsString(e.toString)) }
    }, "ack-probe-bridge-reader")
    consumerThread.setDaemon(true); consumerThread.start()

    val source = MySQLCdcSourceConfig(connector, "127.0.0.1", port, schema, "source_rows", "root", password, 186020L, size, 1000)
    val state = MySQLCdcStateConfig(s"jdbc:mysql://127.0.0.1:$port/$schema?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC",
      "root", password, "ack_offset", "ack_history", 0)
    val engine = DebeziumEngineFactory.real.create(MySQLCdcSourceNode.connectorProperties(source, state))
    engine.start(new DebeziumBatchConsumer {
      override def handleBatch(records: Vector[org.apache.kafka.connect.source.SourceRecord], handle: CdcBatchCommitHandle): Unit = {
        require(insideCallback.compareAndSet(false, true), "concurrent callbacks violate probe contract")
        val id = callbacks.getAndIncrement()
        val window = inWindow.get()
        try {
          val encoded = records.map(r => RealMySQLSourceRecordIdentityProbe.encode(r, connector).asJsObject)
          raw.println(JsObject("callback" -> JsNumber(id), "window" -> JsBoolean(window),
            "records" -> JsArray(encoded)).compactPrint); raw.flush(); rawStream.getFD.sync()
          emit("CALLBACK_ENTER", "callback" -> JsNumber(id), "window" -> JsBoolean(window), "records" -> JsNumber(records.size))
          if (!window) {
            handle.markProcessedAndFinished()
            emit("BOOTSTRAP_ACK", "callback" -> JsNumber(id))
          } else {
            require(records.nonEmpty && records.size <= size)
            pending.enqueue(Entry(id, encoded.map(groupId).toSet, handle))
            require(pending.size <= 2, "more than two retained callbacks")
            emit("PENDING_CALLBACKS", "count" -> JsNumber(pending.size))
            val groups = Vector.newBuilder[JsObject]
            encoded.foreach { record =>
              require(record.fields("normalizedEnvelope") != JsNull &&
                !record.fields("normalizedEnvelope").asJsObject.fields.contains("decodeFailure"))
              val gid = groupId(record)
              half match {
                case Some(old) =>
                  require(op(record) == "c" && gid == groupId(old), "unmatched PK component")
                  require(header(old, "__debezium.newkey").contains(record.fields("key")))
                  require(header(record, "__debezium.oldkey").contains(old.fields("key")))
                  require(old.fields("sourceOffset") == record.fields("sourceOffset"))
                  groups += JsObject("groupId" -> JsString(gid), "members" -> JsArray(Vector(old, record)))
                  complete += gid; half = None
                  emit("PAIR_ASSEMBLED", "groupId" -> JsString(gid), "callback" -> JsNumber(id))
                case None if op(record) == "d" && header(record, "__debezium.newkey").nonEmpty =>
                  half = Some(record)
                  emit("HALF_BUFFERED", "groupId" -> JsString(gid), "callback" -> JsNumber(id))
                case None =>
                  require(header(record, "__debezium.oldkey").isEmpty, "orphan PK create")
                  groups += JsObject("groupId" -> JsString(gid), "members" -> JsArray(Vector(record)))
                  complete += gid
              }
            }
            val ready = groups.result()
            if (ready.nonEmpty) {
              val delivery = "delivery-" + deliverySequence; deliverySequence += 1
              val ids = ready.map(g => string(g, "groupId"))
              val last = ready.last.fields("members").asInstanceOf[JsArray].elements.last.asJsObject
              emit("DELIVERY_PUBLISH", "delivery" -> JsString(delivery), "groupIds" -> JsArray(ids.map(JsString(_))))
              bridge.publish(delivery, ready.map(_.compactPrint), last.fields("sourceOffset").compactPrint,
                new CdcBatchCommitHandle {
                  override def markProcessedAndFinished(): Unit = {
                    ids.foreach(covered.add)
                    emit("TEST_COVERAGE_CONFIRMED", "delivery" -> JsString(delivery), "groupIds" -> JsArray(ids.map(JsString(_))))
                  }
                })
            }
            while (pending.headOption.exists(e => e.groups.forall(g => complete(g) && covered.contains(g)))) {
              val entry = pending.dequeue()
              require(marking.compareAndSet(false, true), "concurrent committer use")
              emit("RAW_ACK_BEGIN", "callback" -> JsNumber(entry.id), "currentCallback" -> JsNumber(id))
              try entry.handle.markProcessedAndFinished() finally marking.set(false)
              emit("RAW_ACK_RETURNED", "callback" -> JsNumber(entry.id), "currentCallback" -> JsNumber(id))
              if (entry.id < id && firstDelayedAck.compareAndSet(true, false)) {
                emit("AFTER_DELAYED_ACK_GATE", "callback" -> JsNumber(entry.id), "currentCallback" -> JsNumber(id))
                afterAckGate.await()
              }
            }
          }
        } finally {
          emit("CALLBACK_RETURNING", "callback" -> JsNumber(id), "window" -> JsBoolean(window),
            "pendingCallbacks" -> JsNumber(pending.size), "halfBuffered" -> JsBoolean(half.nonEmpty))
          insideCallback.set(false)
        }
      }
    }).onComplete(r => emit("ENGINE_DONE", "result" -> JsString(r.toString)))
    emit("READY", "batchSize" -> JsNumber(size), "bootstrap" -> JsBoolean(bootstrap))
    scala.io.Source.stdin.getLines().foreach {
      case "ARM" => inWindow.set(true); emit("ARMED")
      case "CONFIRM" =>
        val delivery = activeDelivery.getAndSet(null)
        require(delivery != null, "no active delivery")
        bridge.acknowledge(delivery.deliveryToken, delivery.batchId)
        emit("DELIVERY_CONFIRMED", "delivery" -> JsString(delivery.batchId))
      case "RELEASE_ACK" => afterAckGate.countDown(); emit("ACK_GATE_RELEASED")
      case "CRASH" =>
        raw.flush(); rawStream.getFD.sync(); trace.flush(); traceStream.getFD.sync()
        System.out.flush(); Runtime.getRuntime.halt(86)
      case x => throw new IllegalArgumentException(s"unexpected command: $x")
    }
    afterAckGate.countDown(); bridge.close(); engine.close(); raw.close(); trace.close()
  }
}
