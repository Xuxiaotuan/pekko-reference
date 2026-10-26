package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.testkit.STSpec

class CdcJdbcSpec extends STSpec {
  "platform JDBC URLs" should {
    "bound missing or zero timeouts while preserving positive values and TLS options" in {
      CdcJdbc.withTimeouts("jdbc:mysql://localhost/state") shouldBe "jdbc:mysql://localhost/state?connectTimeout=5000&socketTimeout=5000"
      CdcJdbc.withTimeouts("jdbc:mysql://localhost/state?sslMode=VERIFY_IDENTITY&connectTimeout=1200&socketTimeout=0") shouldBe
        "jdbc:mysql://localhost/state?sslMode=VERIFY_IDENTITY&connectTimeout=1200&socketTimeout=5000"
    }
    "reject ambiguous or malformed timeout parameters" in {
      Vector("connectTimeout=1&connectTimeout=2","socketTimeout=-1","connectTimeout=no","ConnectTimeout=20").foreach {params=>
        intercept[CdcPlatformException](CdcJdbc.withTimeouts("jdbc:mysql://localhost/state?"+params)).code shouldBe "INVALID_STATE_JDBC"
      }
    }
  }
}
