package cn.xuyinyin.magic.cdc.checkpoint

import cn.xuyinyin.magic.cdc.compat.WorkflowEvents
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.serialization.SerializationExtension
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.io.Source
import spray.json._
import spray.json.DefaultJsonProtocol._

class LegacyCheckpointMigrationSpec extends AnyWordSpec with Matchers {
  "checkpoint migration" should {
    "read an actual checkpoint Journal event captured from an older MySQL experiment" in {
      val path=java.nio.file.Path.of("docs/experiments/2026-10-25-capture-startup/R02-original-trigger-eaa005be087a/final.json")
      val root=java.nio.file.Files.readString(path).parseJson.asJsObject.fields
      val records=root("worker").asJsObject.fields("J").asInstanceOf[JsArray].elements
      val event=records.map(_.asJsObject.fields).find(_.get("decodedType").contains(JsString("ExecutionCheckpointAdvanced"))).get
      val expected=event("checkpoint").asJsObject.fields
      val kit=ActorTestKit("actual-legacy-checkpoint")
      try {
        val serialization=SerializationExtension(kit.system.toClassic)
        val bytes=java.util.HexFormat.of.parseHex(event("payload_hex").convertTo[String])
        val decoded=serialization.deserialize(bytes,event("event_ser_id").convertTo[String].toInt,
          event("event_ser_manifest").convertTo[String]).get.asInstanceOf[WorkflowEvents.ExecutionCheckpointAdvanced]
        decoded.checkpoint.batchId shouldBe expected("batchId").convertTo[String]
        decoded.checkpoint.batchSequence shouldBe expected("sequence").convertTo[Long]
        decoded.checkpoint.sourceRowsScanned shouldBe expected("sourceRows").convertTo[Long]
        decoded.checkpoint.targetRowsWritten shouldBe expected("targetRows").convertTo[Long]
        decoded.checkpoint.cursor.value.parseJson shouldBe JsObject(expected("cursor").asJsObject.fields)
      } finally kit.shutdownTestKit()
    }
    "read pre-move CBOR for standalone values and their nested Journal events" in {
      val cursor = SourceCursor("mysql", "123", "999")
      val boundary = SnapshotBoundary("source", "part", Some("999"))
      val checkpoint = BatchCheckpoint("source", "part", 7L, "batch", cursor, 10L, 8L)
      val expected = Vector(cursor, boundary, checkpoint,
        WorkflowEvents.ExecutionCheckpointAdvanced("exec", checkpoint, 123L),
        WorkflowEvents.ExecutionSnapshotInitialized("exec", boundary, 123L))
      val fixture = Source.fromResource("cdc/legacy-checkpoint-fixtures.tsv")
      val rows = try fixture.getLines().toVector finally fixture.close()
      rows.size shouldBe expected.size
      val kit = ActorTestKit("legacy-checkpoint-cbor")
      try {
        val serialization = SerializationExtension(kit.system.toClassic)
        rows.zip(expected).foreach { case (row, value) =>
          val columns = row.split("\t", 3)
          val decoded = serialization.deserialize(java.util.HexFormat.of.parseHex(columns(2)), columns(0).toInt, columns(1)).get
          decoded shouldBe value
        }
      } finally kit.shutdownTestKit()
    }
  }
}
