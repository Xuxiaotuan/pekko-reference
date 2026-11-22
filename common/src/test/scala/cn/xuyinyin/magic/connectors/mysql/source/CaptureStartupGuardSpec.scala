package cn.xuyinyin.magic.connectors.mysql.source

import cn.xuyinyin.magic.connectors.mysql.source.capture._
import cn.xuyinyin.magic.testkit.STSpec

class CaptureStartupGuardSpec extends STSpec {
  private val checkpointKey = """["capture",{"server":"capture"}]"""
  private def checkpoint(snapshot: String = "") = CaptureCheckpoint.decode("capture", Vector(checkpointKey ->
    ("""{"file":"binlog.000003","pos":120""" + snapshot + "}")))
  private def facts = CaptureRecoveryFacts(existingDomain = false, hasEvidence = false, hasBoundaries = false,
    hasUnboundMarkers = false, controlPresent = false, historyPresent = false,
    checkpoint = CaptureCheckpoint.missing, coverageVerified = false)

  "startup recovery" should {
    "allow a new domain while rejecting an uncertain legacy marker" in {
      CaptureStartupGuard.classify(facts) shouldBe None
      CaptureStartupGuard.classify(facts.copy(existingDomain=true,controlPresent=true,hasUnboundMarkers=true)) shouldBe Some("UNBOUND_MARKER_UNPROVEN")
    }
    "pause an existing B with INITIAL before any SDK restart" in {
      CaptureStartupGuard.classify(facts.copy(existingDomain=true,controlPresent=true,hasBoundaries=true,
        hasEvidence=true,historyPresent=true,checkpoint=CaptureCheckpoint.decode("capture",Vector(checkpointKey -> """{"file":"binlog.000003","pos":120,"snapshot":"INITIAL"}""")),coverageVerified=true)) shouldBe Some("INITIAL_WITH_COMMITMENT")
    }
    "never rebuild an old missing control table or silently replace missing D" in {
      CaptureStartupGuard.classify(facts.copy(existingDomain=true)) shouldBe Some("CONTROL_TABLE_MISSING")
      CaptureStartupGuard.classify(facts.copy(existingDomain=true,controlPresent=true,hasEvidence=true,historyPresent=true)) shouldBe Some("CHECKPOINT_MISSING")
    }
    "require history and L coverage for a completed checkpoint" in {
      val old=facts.copy(existingDomain=true,controlPresent=true,hasEvidence=true,hasBoundaries=true,checkpoint=checkpoint())
      CaptureStartupGuard.classify(old) shouldBe Some("SCHEMA_HISTORY_MISSING")
      CaptureStartupGuard.classify(old.copy(historyPresent=true)) shouldBe Some("CHECKPOINT_UNCOVERED")
      CaptureStartupGuard.classify(old.copy(historyPresent=true,coverageVerified=true)) shouldBe None
    }
    "allow an incomplete schema-only start without an accepted boundary" in {
      val initial=CaptureCheckpoint.decode("capture",Vector(checkpointKey -> """{"file":"binlog.000003","pos":120,"snapshot":"INITIAL"}"""))
      CaptureStartupGuard.classify(facts.copy(existingDomain=true,controlPresent=true,hasEvidence=true,
        historyPresent=true,checkpoint=initial,coverageVerified=true)) shouldBe None
    }
  }
  "checkpoint decoding" should {
    "reject ambiguous keys, wrong partitions and unknown snapshot states" in {
      CaptureCheckpoint.decode("capture",Vector(checkpointKey -> "{}",checkpointKey -> "{}")).status shouldBe "INVALID"
      CaptureCheckpoint.decode("capture",Vector("""["capture",{"server":"other"}]""" -> """{"file":"binlog.000003","pos":120}""")).status shouldBe "INVALID"
      for(value<-Vector("true","\"last\"","0","\"UNKNOWN\""))
        CaptureCheckpoint.decode("capture",Vector(checkpointKey -> s"""{"file":"binlog.000003","pos":120,"snapshot":$value}""")).status shouldBe "INVALID"
      checkpoint().status shouldBe "COMPLETE"
    }
  }
  "admission evidence" should {
    "require the current ACK, actual D, completed lifecycle and matching attempt" in {
      CaptureStartupGuard.ready("COMPLETED","MISSING",checkpoint(),10,11,11) shouldBe true
      CaptureStartupGuard.ready("COMPLETED","MISSING",checkpoint(),10,10,11) shouldBe false
      CaptureStartupGuard.ready("COMPLETED","MISSING",checkpoint(),10,12,11) shouldBe false
      CaptureStartupGuard.ready("RUNNING","MISSING",checkpoint(),10,11,11) shouldBe false
      CaptureStartupGuard.ready("SKIPPED","INITIAL",checkpoint(),10,11,11) shouldBe false
      CaptureStartupGuard.ready("SKIPPED","COMPLETE",checkpoint(),10,11,11) shouldBe true
    }
    "never turn a stopping attempt back into ready" in {
      val state=new CaptureStartupState("attempt")
      state.starting("MISSING",10)
      state.stop()
      state.observed("COMPLETED",checkpoint(),11,11)
      state.evidence.phase shouldBe "STOPPING"
    }
  }
}
