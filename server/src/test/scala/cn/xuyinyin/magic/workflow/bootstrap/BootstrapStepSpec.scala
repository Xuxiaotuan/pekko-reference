package cn.xuyinyin.magic.workflow.bootstrap

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class BootstrapStepSpec extends AnyWordSpec with Matchers {
  private val clean = BootstrapProgress(revision=17,phase="STREAMING",definition="definition",executionId="execution",
    planned=true,upper=Some("64"),nextLower=Some("64"),index=2,deltaAfter=190,batchSequence=8,
    completionRoot="root",recoveryAttempts=2)

  "a clean bootstrap step continuation" should {
    "accept the exact Journal state without spending another recovery attempt" in {
      BootstrapStepContinuation.matches(clean,clean) shouldBe true
      clean.recoveryAttempts shouldBe 2
    }
    "allow only the Journal's sticky cancel transition after a clean step" in {
      BootstrapStepContinuation.matches(clean,clean.copy(revision=18,phase="CANCELING",cancelRequested=true)) shouldBe true
      BootstrapStepContinuation.matches(clean,clean.copy(revision=18,cancelRequested=true)) shouldBe false
      BootstrapStepContinuation.matches(clean,clean.copy(revision=16,phase="CANCELING",cancelRequested=true)) shouldBe false
    }
    "reject a changed execution, definition, accepted page or late checkpoint" in {
      Vector(clean.copy(executionId="other"),clean.copy(definition="other"),clean.copy(appliedPage=1),
        clean.copy(revision=18),clean.copy(deltaAfter=191),clean.copy(recoveryAttempts=3))
        .foreach(next=>BootstrapStepContinuation.matches(clean,next) shouldBe false)
    }
    "discard an unsealed read view even when the scheduling grant was clean" in {
      val retry=clean.copy(phase="SCANNING",window=Some("range_0_0"),manifest=None,
        upper=Some("64"),nextLower=Some("32"),recoveryAttempts=1)
      // A retryable source failure can close all JDBC successfully while its RAW
      // pages still belong to the ended RR transaction. Clean release is not a seal.
      BootstrapStepContinuation.matches(retry,retry) shouldBe true
      BootstrapStepContinuation.needsReadViewRecovery(retry,windowSealed=false) shouldBe true
      BootstrapStepContinuation.needsReadViewRecovery(retry,windowSealed=true) shouldBe false
      BootstrapStepContinuation.needsReadViewRecovery(retry.copy(window=None),windowSealed=false) shouldBe false
      retry.recoveryAttempts shouldBe 1
    }
    "reject a missing seal once the Journal accepted its manifest or target pages" in {
      val window=clean.copy(phase="SCANNING",window=Some("range_0_0"),manifest=None,appliedPage=0)
      intercept[IllegalArgumentException] {
        BootstrapStepContinuation.needsReadViewRecovery(window.copy(manifest=Some("sealed-output-hash")),windowSealed=false)
      }
      intercept[IllegalArgumentException] {
        BootstrapStepContinuation.needsReadViewRecovery(window.copy(appliedPage=1),windowSealed=false)
      }
      BootstrapStepContinuation.needsReadViewRecovery(window.copy(manifest=Some("sealed-output-hash"),appliedPage=1),windowSealed=true) shouldBe false
    }
  }

  "a bounded bootstrap step result" should {
    "keep no-work tail distinct from terminal and unknown release" in {
      val idle=BootstrapStepResult(clean,didWork=false,releaseConfirmed=true,nextDelayMillis=250)
      idle.phase shouldBe "STREAMING"
      idle.terminal shouldBe false
      idle.paused shouldBe false
      idle.didWork shouldBe false
      val unknown=BootstrapStepResult(clean.copy(phase="PAUSED"),didWork=false,releaseConfirmed=false,nextDelayMillis=0)
      unknown.terminal shouldBe true
      unknown.paused shouldBe true
      unknown.releaseConfirmed shouldBe false
    }
  }
}
