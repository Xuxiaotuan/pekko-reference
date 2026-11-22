package cn.xuyinyin.magic.cdc.savepoint

import cn.xuyinyin.magic.testkit.STSpec

class CdcSavepointModelsSpec extends STSpec {
  private val digest="a"*64
  private val epoch=CaptureEpoch("capture",1L,"lineage",7L,Some("mysql-bin.000001:120"),digest,digest)
  private val ref=TableCheckpointRef("b"*64,"g1","STREAMING","c"*64,Some("j"),Some("t"),Some(digest),digest)

  "CDC savepoint models" should {
    "produce deterministic identity and page digests" in {
      epoch.digest shouldBe CaptureEpoch("capture",1L,"lineage",7L,Some("mysql-bin.000001:120"),digest,digest).digest
      CdcSavepointDigest.tableSet(Vector(ref)) shouldBe CdcSavepointDigest.tableSet(Vector(ref))
      CdcSavepointDigest.page("sp",0,Vector(ref)) shouldBe CdcSavepointDigest.page("sp",0,Vector(ref))
    }
    "reject a sealed header without a manifest digest" in {
      intercept[IllegalArgumentException] {
        CdcSavepointHeader("sp","capture","lineage",epoch,digest,1,"owner",1,"SEALED",None)
      }
    }
    "distinguish tombstones from active table references" in {
      ref.copy(inclusionState="TOMBSTONE").inclusionState shouldBe "TOMBSTONE"
      intercept[IllegalArgumentException](ref.copy(inclusionState="UNKNOWN"))
    }
  }
}
