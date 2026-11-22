package cn.xuyinyin.magic.cdc.guardian

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ManagedCdcJdbcSpec extends AnyWordSpec with Matchers {
  private val identity = SnapshotOwnerIdentity("boot-1", "deployment", 1L, "start")
  private val raw      = "jdbc:mysql://127.0.0.1:3306/cdc?useSSL=false"

  "managed JDBC URL tagging" should {
    "be idempotent for the same owner and role" in {
      val tagged = ManagedCdcJdbc.taggedUrl(raw, identity, "journal")
      ManagedCdcJdbc.taggedUrl(tagged, identity, "journal") shouldBe tagged
    }

    "reject an existing tag from another owner or role" in {
      val tagged = ManagedCdcJdbc.taggedUrl(raw, identity, "journal")
      intercept[IllegalArgumentException](ManagedCdcJdbc.taggedUrl(tagged, identity.copy(bootId = "other"), "journal"))
      intercept[IllegalArgumentException](ManagedCdcJdbc.taggedUrl(tagged, identity, "control"))
    }
  }
}
