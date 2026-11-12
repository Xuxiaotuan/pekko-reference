package cn.xuyinyin.magic.cdc.platform

import cn.xuyinyin.magic.testkit.STSpec
import com.typesafe.config.ConfigFactory

class CdcGcSettingsSpec extends STSpec {
  "GC settings" should {
    "require explicit opt-in and keep credentials out of diagnostics" in {
      CdcGcSettings.load(ConfigFactory.empty()) shouldBe None
      val root=ConfigFactory.parseString("""gc {
        enabled=true, username="gc_writer",password="secret-should-stay-private",account-host="127.0.0.1",
        ledger-definer="control@localhost",trusted-admin-accounts=["control@localhost"],producer-accounts=["sdk@127.0.0.1"]
      }""")
      val value=CdcGcSettings.load(root).get
      value.enableLegacy shouldBe false
      value.toString should not include "secret-should-stay-private"
      intercept[IllegalArgumentException](value.copy(accountHost="%"))
      intercept[IllegalArgumentException](value.copy(batchFrames=129))
      intercept[IllegalArgumentException](value.copy(trustedAdmins=value.trustedAdmins+value.account))
    }
  }
}
