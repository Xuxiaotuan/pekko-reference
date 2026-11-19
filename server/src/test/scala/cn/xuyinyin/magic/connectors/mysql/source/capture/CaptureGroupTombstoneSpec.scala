package cn.xuyinyin.magic.connectors.mysql.source.capture

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CaptureGroupTombstoneSpec extends AnyWordSpec with Matchers {
  "tombstone compaction" should {
    "count the stored prefix and UTF-8 bytes when enforcing minimum savings" in {
      val tombstone = "数据"
      val stored = CaptureGroupTombstone.Prefix + tombstone
      val storedBytes = stored.getBytes("UTF-8").length
      val threshold = 128

      CaptureGroupTombstone.worthwhile("x" * (storedBytes + threshold - 1), tombstone, threshold) shouldBe false
      CaptureGroupTombstone.worthwhile("x" * (storedBytes + threshold), tombstone, threshold) shouldBe true
      intercept[IllegalArgumentException](CaptureGroupTombstone.worthwhile("x", tombstone, threshold - 1))
    }
  }
}
