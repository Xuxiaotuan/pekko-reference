package cn.xuyinyin.magic.cdc.snapshot

import cn.xuyinyin.magic.cdc.checkpoint.{BatchCheckpoint, SourceCursor}
import cn.xuyinyin.magic.cdc.compat.WorkflowEvents
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.serialization.{SerializationExtension, Serializers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.io.Source

class LegacySplitMigrationSpec extends AnyWordSpec with Matchers {
  "split migration" should {
    "round trip a newly written event after the schema version change" in {
      val kit = ActorTestKit("current-split-cbor")
      try {
        val extension = SerializationExtension(kit.system.toClassic)
        val event = WorkflowEvents.SnapshotSplitsUpdated("exec", RetireSnapshotWindow, 123L)
        val serializer = extension.findSerializerFor(event)
        val manifest = Serializers.manifestFor(serializer, event)
        extension.deserialize(serializer.toBinary(event), serializer.identifier, manifest).get shouldBe event
      } finally kit.shutdownTestKit()
    }
    "read old states, updates and events with embedded polymorphic type names" in {
      val plan = SnapshotSplitPlanState("p", "s", "f", Some("100"), 10, 20, 1, 4096)
      val split = SnapshotSplit("p:0", 0, None, "20")
      val checkpoint = BatchCheckpoint("s", "p:0", 0, "b", SourceCursor("mysql.snapshot-split.v1", "1", "20"), 1, 1)
      val state = SnapshotSplitState(plan, Vector(SnapshotSplitProgress(split, Some(checkpoint))))
      val updates = Vector(InitializeSnapshotSplits(plan), RegisterSnapshotSplit(split), StartSnapshotSplit("p:0"),
        CommitSnapshotPage(checkpoint), CompleteSnapshotSplit("p:0"), RetrySnapshotSplit("p:0", 1),
        BlockSnapshotSplit("p:0", "reason"), RetireSnapshotWindow, FinishSnapshotEnumeration)
      val expected = Vector[AnyRef](plan, split, state.splits.head, state) ++ updates ++
        updates.map(u => WorkflowEvents.SnapshotSplitsUpdated("exec", u, 123L))
      val source = Source.fromResource("cdc/legacy-split-fixtures.tsv")
      val rows = try source.getLines().toVector finally source.close()
      rows.size shouldBe expected.size
      val kit = ActorTestKit("legacy-split-cbor")
      try {
        val serializer = SerializationExtension(kit.system.toClassic)
        rows.zip(expected).foreach { case (row, value) =>
          val columns = row.split("\t", 3)
          withClue(columns(1)) {
            serializer.deserialize(java.util.HexFormat.of.parseHex(columns(2)), columns(0).toInt, columns(1)).get shouldBe value
          }
        }
      } finally kit.shutdownTestKit()
    }
  }
}
