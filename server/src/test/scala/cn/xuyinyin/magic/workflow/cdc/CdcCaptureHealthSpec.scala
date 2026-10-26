package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.testkit.STSpec

class CdcCaptureHealthSpec extends STSpec {
  "capture health observations" should {
    "expire a cached healthy observation without revoking the separately recorded ready boundary" in {
      val view=CdcCaptureView("capture","source","ADMISSION_READY","HEALTHY",None)
      CdcCaptureHealth.current(view,Some(10L),11L,5L).runtimeHealth shouldBe "HEALTHY"
      val expired=CdcCaptureHealth.current(view,Some(10L),16L,5L)
      expired.runtimeHealth shouldBe "STALE"
      expired.readiness shouldBe "ADMISSION_READY"
      expired.reason shouldBe Some("CAPTURE_OBSERVATION_STALE")
      CdcCaptureHealth.current(view,None,11L,5L).runtimeHealth should not be "HEALTHY"
      CdcCaptureHealth.current(view,Some(12L),11L,5L).runtimeHealth should not be "HEALTHY"
    }
  }
}
