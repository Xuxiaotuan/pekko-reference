package cn.xuyinyin.magic.connectors.mysql.source.capture

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CaptureFenceGrantSpec extends AnyWordSpec with Matchers {
  "source fence writer grant admission" should {
    "reject roles, DDL, schema-wide writes, guard writes and grant delegation" in {
      val usage="GRANT USAGE ON *.* TO `cdc`@`%`"
      val read="GRANT SELECT ON `source`.* TO `cdc`@`%`"
      val control="GRANT EXECUTE ON PROCEDURE `source`.`pekko_control_write_open_v1` TO `cdc`@`%`"
      CaptureControlFence.safeWriterGrants(Vector(usage,read,control),"source") shouldBe true
      Vector(
        "GRANT ALL PRIVILEGES ON `source`.* TO `cdc`@`%`",
        "GRANT INSERT ON `source`.`pekko_capture_control` TO `cdc`@`%`",
        "GRANT TRIGGER ON `source`.`pekko_capture_control` TO `cdc`@`%`",
        "GRANT UPDATE ON `source`.`pekko_capture_control_fence_v1` TO `cdc`@`%`",
        "GRANT SELECT ON `source`.* TO `cdc`@`%` WITH GRANT OPTION",
        "GRANT `some_role`@`%` TO `cdc`@`%`",
        "GRANT EXECUTE ON *.* TO `cdc`@`%`"
      ).foreach(g=>CaptureControlFence.safeWriterGrants(Vector(usage,g),"source") shouldBe false)
    }
  }
}
