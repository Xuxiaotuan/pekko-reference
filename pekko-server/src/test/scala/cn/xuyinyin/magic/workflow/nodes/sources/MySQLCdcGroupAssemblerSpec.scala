package cn.xuyinyin.magic.workflow.nodes.sources

import cn.xuyinyin.magic.testkit.STSpec
import cn.xuyinyin.magic.workflow.nodes.cdc.MySQLCdcGroupProtocol
import org.apache.kafka.connect.data.{Schema, SchemaBuilder, Struct}
import org.apache.kafka.connect.source.SourceRecord
import spray.json._
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

class MySQLCdcGroupAssemblerSpec extends STSpec {
  private val config = MySQLCdcSourceConfig("orders", "mysql", 3306, "db", "rows", "u", "p", 1, 16, 100,
    Some(MySQLCdcGroupProtocol("replication", "generation-1", "server-uuid")))
  private val keySchema = SchemaBuilder.struct().name("db.rows.Key").field("id", Schema.INT64_SCHEMA).build()
  private val image = SchemaBuilder.struct().name("db.rows.Value").optional().field("id", Schema.INT64_SCHEMA).build()
  private val source = SchemaBuilder.struct().name("io.debezium.connector.mysql.Source")
    .field("db", Schema.STRING_SCHEMA).field("table", Schema.STRING_SCHEMA).field("snapshot", Schema.STRING_SCHEMA)
    .field("file", Schema.STRING_SCHEMA).field("pos", Schema.INT64_SCHEMA).field("row", Schema.INT32_SCHEMA)
    .field("ts_ms", Schema.INT64_SCHEMA).build()
  private val envelope = SchemaBuilder.struct().name("db.rows.Envelope").field("op", Schema.STRING_SCHEMA)
    .field("source", source).field("before", image).field("after", image).build()
  private def record(op: String, id: Long, pos: Long): SourceRecord = {
    val value = new Struct(envelope).put("op", op).put("source", new Struct(source).put("db", "db").put("table", "rows")
      .put("snapshot", "false").put("file", "mysql-bin.000003").put("pos", pos).put("row", 0).put("ts_ms", 1700000000000L))
      .put("before", if (op == "c") null else new Struct(image).put("id", id))
      .put("after", if (op == "d") null else new Struct(image).put("id", id))
    new SourceRecord(new java.util.HashMap[String, AnyRef](Map[String, AnyRef]("server" -> "orders").asJava),
      new java.util.HashMap[String, AnyRef](Map[String, AnyRef]("file" -> "mysql-bin.000003", "pos" -> Long.box(pos)).asJava),
      "orders.db.rows", keySchema, new Struct(keySchema).put("id", id), envelope, value)
  }
  private def pair(oldId: Long, newId: Long, pos: Long): (SourceRecord, SourceRecord) = {
    val d = record("d", oldId, pos); val c = record("c", newId, pos)
    d.headers().add("__debezium.newkey", c.key(), keySchema); c.headers().add("__debezium.oldkey", d.key(), keySchema)
    d -> c
  }
  private def handle(effect: => Unit): CdcBatchCommitHandle = new CdcBatchCommitHandle {
    override def markProcessedAndFinished(): Unit = effect
  }

  "complete-group assembly" should {
    "cover ordinary prefixes and PK chains while acknowledging raw callbacks in order" in {
      val (d1,c1) = pair(1,11,200); val (d2,c2) = pair(2,12,300)
      val trace = ArrayBuffer.empty[String]
      val assembler = new MySQLCdcGroupAssembler(config, (rows, _, h) => {
        val count = rows.map(_.parseJson.asJsObject.fields("members").asInstanceOf[JsArray].elements.size).sum
        trace += s"covered:$count"; h.markProcessedAndFinished()
      }, _ => ())
      assembler.accept(Vector(record("u", 3,100),d1), handle { trace += "ack:A" })
      trace.toVector shouldBe Vector("covered:1")
      assembler.accept(Vector(c1,d2), handle { trace += "ack:B" })
      trace.toVector shouldBe Vector("covered:1","covered:2","ack:A")
      assembler.accept(Vector(c2,record("u", 3,400)), handle { trace += "ack:C" })
      trace.toVector shouldBe Vector("covered:1","covered:2","ack:A","covered:3","ack:B","ack:C")
      assembler.close()
    }

    "reject orphan components and mismatched restart offsets without confirming either callback" in {
      val (_,c) = pair(1,11,200)
      val ack = new AtomicInteger()
      val assembler = new MySQLCdcGroupAssembler(config, (_,_,_) => fail("invalid group was delivered"), _ => ())
      intercept[IllegalArgumentException](assembler.accept(Vector(c), handle { ack.incrementAndGet() }))
      ack.get() shouldBe 0
      assembler.close()
      val (d,c2) = pair(1,11,200)
      c2.sourceOffset().asInstanceOf[java.util.Map[String,AnyRef]].put("pos", Long.box(201))
      val other = new MySQLCdcGroupAssembler(config, (_,_,_) => fail("invalid pair was delivered"), _ => ())
      other.accept(Vector(d), handle { ack.incrementAndGet() })
      intercept[IllegalArgumentException](other.accept(Vector(c2), handle { ack.incrementAndGet() }))
      ack.get() shouldBe 0
      other.close()
    }

    "fail a retained-byte overflow before delivery or SDK acknowledgement" in {
      val ack = new AtomicInteger()
      val assembler = new MySQLCdcGroupAssembler(config, (_,_,_) => fail("over-budget delivery"), _ => (), maxRetainedBytes = 1)
      intercept[IllegalArgumentException](assembler.accept(Vector(record("u",1,100)), handle { ack.incrementAndGet() }))
      ack.get() shouldBe 0; assembler.close()
    }

    "cancel a blocked delivery without invoking retained SDK handles" in {
      val bridge = new DebeziumBatchBridge
      val ack = new AtomicInteger()
      val finished = new CountDownLatch(1)
      val assembler = new MySQLCdcGroupAssembler(config, (rows,cursor,h) => bridge.publish("delivery",rows,cursor,h), _ => ())
      val worker = new Thread(() => {
        try assembler.accept(Vector(record("u",1,100)), handle { ack.incrementAndGet() })
        catch { case _: IllegalStateException => () }
        finally finished.countDown()
      })
      worker.start()
      val batch = bridge.take()
      assembler.close(); bridge.close()
      finished.await(2,TimeUnit.SECONDS) shouldBe true
      intercept[IllegalStateException](bridge.acknowledge(batch.deliveryToken,batch.batchId))
      ack.get() shouldBe 0
    }

    "reject an unexpected source partition before it can acquire coverage" in {
      val r = record("u",1,100)
      r.sourcePartition().asInstanceOf[java.util.Map[String,AnyRef]].put("server","other-source")
      val ack = new AtomicInteger()
      val assembler = new MySQLCdcGroupAssembler(config, (_,_,_) => (), _ => ())
      intercept[IllegalArgumentException](assembler.accept(Vector(r), handle { ack.incrementAndGet() }))
      ack.get() shouldBe 0; assembler.close()
    }
  }
}
