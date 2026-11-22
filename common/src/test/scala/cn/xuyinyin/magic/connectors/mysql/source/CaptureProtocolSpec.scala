package cn.xuyinyin.magic.connectors.mysql.source

import cn.xuyinyin.magic.connectors.mysql.source.capture.CapturePoint
import cn.xuyinyin.magic.testkit.STSpec

class CaptureProtocolSpec extends STSpec {
  "capture positions" should {
    "order binlog rotation numerically and retain row identity" in {
      CapturePoint("mysql-bin.000010",4,0).after(CapturePoint("mysql-bin.000009",900,2)) shouldBe true
      CapturePoint("mysql-bin.000010",4,1).after(CapturePoint("mysql-bin.000010",4,0)) shouldBe true
      CapturePoint("mysql-bin.000010",4,0).after(CapturePoint("mysql-bin.000010",4,0)) shouldBe false
    }
    "reject incomparable namespaces rather than guessing a resume order" in {
      intercept[IllegalArgumentException] {
        CapturePoint("other.000001",4,0).after(CapturePoint("mysql-bin.000001",4,0))
      }
    }
    "round trip a durable B without runtime callback identity" in {
      val b=CapturePoint("mysql-bin.000001",234,2)
      CapturePoint.parse(b.json) shouldBe b
    }
  }
}
