package cn.xuyinyin.magic.cdc.recovery

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.serialization.SerializationExtension
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class LegacyExecutionFailureMigrationSpec extends AnyWordSpec with Matchers {
  "failure migration" should {
    "read a payload captured with the pre-move serializer" in {
      val kit = ActorTestKit("old-failure-compatibility")
      try {
        // Captured with the old class and this project's Jackson CBOR serializer;
        // this fixture was not read from an existing production Journal.
        val hex = "bf64636f646576636f6d6d69745f6f7574636f6d655f756e6b6e6f776e65737461676566746172676574676261746368496463622d317072656c65617365436f6e6669726d6564f4ff"
        val bytes = java.util.HexFormat.of.parseHex(hex)
        val actual = SerializationExtension(kit.system.toClassic).deserialize(
          bytes, 33, "cn.xuyinyin.magic.workflow.engine.ExecutionFailure").get
        actual shouldBe ExecutionFailure("commit_outcome_unknown", "target", Some("b-1"), false)
      } finally kit.shutdownTestKit()
    }
  }
}
