package cn.xuyinyin.magic.workflow.execution

import cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActor.WorkflowState
import cn.xuyinyin.magic.workflow.events.WorkflowEvents._
import cn.xuyinyin.magic.workflow.snapshot._
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.serialization.{SerializationExtension, Serializers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SnapshotSplitSerializationSpec extends AnyWordSpec with Matchers {
  "snapshot split persistence" should {
    "round-trip a nonempty entity snapshot and polymorphic split events through actual CBOR" in {
      val kit = ActorTestKit("split-cbor", ConfigFactory.parseString("pekko.actor.provider=local").withFallback(ConfigFactory.load("application-test")))
      try {
        val serialization = SerializationExtension(kit.system.classicSystem)
        val plan = SnapshotSplitPlanState("p", "s", "fingerprint", Some("9007199254740993"), 2, 4, 2, 4096)
        val split = SnapshotSplit("p:0", 0, None, "10")
        val state = SnapshotSplitState.advance(Some(SnapshotSplitState(plan)), RegisterSnapshotSplit(split)).toOption.get
        val updates: Vector[SnapshotSplitUpdate] = Vector(InitializeSnapshotSplits(plan), RegisterSnapshotSplit(split), StartSnapshotSplit(split.id),
          CompleteSnapshotSplit(split.id), RetrySnapshotSplit(split.id, 1), BlockSnapshotSplit(split.id, "protocol_conflict"),
          RetireSnapshotWindow, FinishSnapshotEnumeration)
        val samples: Vector[AnyRef] = Vector(WorkflowState(splitSnapshot = Some(state))) ++ updates.map(SnapshotSplitsUpdated("e", _, 1L))
        samples.foreach { value =>
          val serializer = serialization.findSerializerFor(value)
          val bytes = serialization.serialize(value).get
          serialization.deserialize(bytes, serializer.identifier, Serializers.manifestFor(serializer, value)).get shouldBe value
        }
      } finally kit.shutdownTestKit()
    }
  }
}
