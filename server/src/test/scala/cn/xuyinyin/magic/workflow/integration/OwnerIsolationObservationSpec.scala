package cn.xuyinyin.magic.workflow.integration

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import OwnerIsolationProbeSupport.ReleaseObservation

/** Protects the TEST operator gate against treating missing observations as successful release. */
class OwnerIsolationObservationSpec extends AnyWordSpec with Matchers {
  "the laboratory requeue decision" should {
    "refuse a live process even with no currently observed SQL work" in {
      ReleaseObservation(false,true,true,0,0,0).permitsTestRequeue shouldBe false
    }
    "refuse unknown attribution and failed observations rather than interpreting them as empty" in {
      ReleaseObservation(true,false,true,0,0,0).permitsTestRequeue shouldBe false
      ReleaseObservation(true,true,false,0,0,0).permitsTestRequeue shouldBe false
      ReleaseObservation(true,true,true,-1,-1,-1).permitsTestRequeue shouldBe false
    }
    "retain the fence for a server-side session, transaction or untagged connection" in {
      ReleaseObservation(true,true,true,1,0,0).permitsTestRequeue shouldBe false
      ReleaseObservation(true,true,true,0,1,0).permitsTestRequeue shouldBe false
      ReleaseObservation(true,true,true,0,0,1).permitsTestRequeue shouldBe false
    }
    "permit only a confirmed dead process with complete empty database observations" in {
      ReleaseObservation(true,true,true,0,0,0).permitsTestRequeue shouldBe true
    }
  }
}
