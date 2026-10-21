package cn.xuyinyin.magic.connectors.mysql.source

import cn.xuyinyin.magic.cdc.{CdcOperation, MySQLCdcEnvelope, MySQLCdcGroup, MySQLCdcGroupProtocol}
import org.apache.kafka.connect.data.Struct
import org.apache.kafka.connect.source.SourceRecord
import spray.json._

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** One ORDERED callback caller owns assembly and all original SDK handles.
  * publish blocks for T -> J coverage; its proxy handle never calls the SDK.
  */
private[source] final class MySQLCdcGroupAssembler(
  config: MySQLCdcSourceConfig,
  publish: (Vector[String], String, CdcBatchCommitHandle) => Unit,
  onLog: String => Unit,
  maxRetainedBytes: Long = 16L * 1024 * 1024
) extends AutoCloseable {
  private val protocol = config.groupProtocol.get
  private case class Member(record: SourceRecord, envelope: MySQLCdcEnvelope, id: String,
                            newKey: Option[JsObject], oldKey: Option[JsObject])
  private case class Callback(id: Long, groups: Set[String], handle: CdcBatchCommitHandle, bytes: Long)
  private val pending = mutable.Queue.empty[Callback]
  private val covered = mutable.Set.empty[String] // callback thread only; publish completion establishes visibility
  private var half = Option.empty[Member]
  private var callbackSequence = 0L
  private val closed = new AtomicBoolean(false)
  private val inside = new AtomicBoolean(false)
  private val ackLock = new AnyRef

  def accept(records: Vector[SourceRecord], handle: CdcBatchCommitHandle): Unit = {
    require(inside.compareAndSet(false, true), "concurrent CDC callbacks are unsupported")
    try {
      ensureOpen()
      require(records.nonEmpty && records.size <= config.maxBatchSize, "invalid CDC callback size")
      val id = callbackSequence; callbackSequence += 1
      val decoded = records.map { record =>
        MySQLCdcEnvelope.decode(record, config.connectorId) match {
          case Right(Some(e)) =>
            require(record.sourcePartition().asScala.toMap == Map("server" -> config.connectorId),
              "CDC source partition does not match configured connector")
            require(e.source.database == config.database && e.source.table == config.table,
              "CDC source does not match configured table")
            Some(Member(record, e, MySQLCdcGroup.id(protocol, e), keyHeader(record, "__debezium.newkey"),
              keyHeader(record, "__debezium.oldkey")))
          case Right(None) => None // explicitly ignored metadata, still part of the original callback ACK
          case Left(error) => throw new IllegalArgumentException(error.message)
        }
      }
      val bytes = decoded.flatten.map { m =>
        m.envelope.canonicalJson.getBytes(UTF_8).length.toLong + m.record.valueSchema().toString.getBytes(UTF_8).length +
          m.record.sourceOffset().toString.getBytes(UTF_8).length
      }.sum
      require(pending.size < 2 && pending.map(_.bytes).sum + bytes <= maxRetainedBytes,
        "CDC retained callback budget exceeded")
      pending.enqueue(Callback(id, decoded.flatten.map(_.id).toSet, handle, bytes))
      onLog(s"CDC raw callback retained callback=$id records=${records.size} pending=${pending.size}")
      val ready = Vector.newBuilder[MySQLCdcGroup]
      var lastComplete = Option.empty[SourceRecord]
      decoded.foreach {
        case None => require(half.isEmpty, "metadata interrupted primary-key CDC group")
        case Some(m) => half match {
          case Some(d) =>
            require(m.envelope.op == CdcOperation.Create && m.id == d.id &&
              d.newKey.contains(m.envelope.key) && m.oldKey.contains(d.envelope.key) &&
              m.newKey.isEmpty && d.oldKey.isEmpty, "unmatched primary-key CDC group")
            require(d.record.sourceOffset() == m.record.sourceOffset() &&
              d.record.sourcePartition() == m.record.sourcePartition() &&
              d.record.valueSchema() == m.record.valueSchema(), "primary-key CDC group restart context mismatch")
            val group = MySQLCdcGroup(m.id, Vector("pk.d" -> d.envelope, "pk.c" -> m.envelope))
            MySQLCdcGroup.parse(group.canonicalJson, protocol) // same structural contract as the Sink
            ready += group; lastComplete = Some(m.record); half = None
            onLog(s"CDC primary-key group assembled groupId=${m.id}")
          case None if m.envelope.op == CdcOperation.Delete && m.newKey.nonEmpty =>
            require(m.oldKey.isEmpty, "ambiguous primary-key CDC headers")
            half = Some(m)
            onLog(s"CDC primary-key half retained groupId=${m.id} callback=$id")
          case None =>
            require(m.newKey.isEmpty && m.oldKey.isEmpty, "orphan primary-key CDC component")
            ready += MySQLCdcGroup(m.id, Vector(("single." + m.envelope.op.code) -> m.envelope))
            lastComplete = Some(m.record)
        }
      }
      val groups = ready.result()
      if (groups.nonEmpty) {
        // Only complete groups enter Bridge. This returns after the Engine has persisted J.
        publish(groups.map(_.canonicalJson), MySQLCdcSourceNode.cursorValue(lastComplete.get),
          new CdcBatchCommitHandle { override def markProcessedAndFinished(): Unit = () })
        ensureOpen()
        covered ++= groups.map(_.groupId)
      }
      while (pending.headOption.exists(_.groups.subsetOf(covered.toSet))) {
        val entry = pending.head
        ackLock.synchronized {
          ensureOpen()
          entry.handle.markProcessedAndFinished()
        }
        pending.dequeue()
        onLog(s"CDC raw callback acknowledged callback=${entry.id}")
        // A PK group may still be referenced by the next raw callback. Keep only live references.
        val referenced = pending.iterator.flatMap(_.groups).toSet
        covered.retain(referenced.contains)
      }
    } finally {
      inside.set(false)
      if (closed.get()) clearRetained()
    }
  }

  private def keyHeader(record: SourceRecord, name: String): Option[JsObject] = {
    val headers = record.headers().allWithName(name).asScala.toVector
    require(headers.size <= 1, "duplicate primary-key CDC header")
    headers.headOption.map { h =>
      val struct = h.value() match {
        case s: Struct if s.schema() == record.keySchema() => s
        case _ => throw new IllegalArgumentException("invalid primary-key CDC header schema")
      }
      require(struct.schema().fields().size() == 1, "CDC group requires a single primary key")
      val key = record.key() match {
        case s: Struct => s
        case _ => throw new IllegalArgumentException("invalid CDC key")
      }
      // Decode the header using exactly the production key conversion, preserving numeric types.
      val replacement = new SourceRecord(record.sourcePartition(), record.sourceOffset(), record.topic(),
        record.keySchema(), struct, record.valueSchema(), record.value())
      val decoded = MySQLCdcEnvelope.decode(replacement, config.connectorId).toOption.flatten.get
      require(key.schema() == struct.schema(), "primary-key CDC key schema mismatch")
      decoded.key
    }
  }
  private def ensureOpen(): Unit = require(!closed.get(), "CDC group assembler is closed")
  private def clearRetained(): Unit = ackLock.synchronized { pending.clear(); covered.clear(); half = None }
  override def close(): Unit = ackLock.synchronized {
    closed.set(true)
    if (!inside.get()) clearRetained()
  }
}
