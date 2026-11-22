package cn.xuyinyin.magic.cdc.savepoint

import cn.xuyinyin.magic.testkit.STSpec
import cn.xuyinyin.magic.cdc.platform.{CdcPlatformLimits, CdcTaskRequest, CdcTaskStore}
import java.sql.DriverManager
import java.util.UUID

class CdcSavepointStoreSpec extends STSpec {
  Class.forName("org.h2.Driver")
  private def db() = "jdbc:h2:mem:savepoint_" + UUID.randomUUID().toString.replace('-', '_') + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
  private val digest = "a" * 64
  private val ref    = TableCheckpointRef("b" * 64, "g1", "STREAMING", "c" * 64, Some("j"), Some("t"), Some(digest), digest)
  "paged savepoint storage" should {
    "seal and restore an immutable manifest" in {
      val url   = db(); val store = new CdcSavepointStore(() => DriverManager.getConnection(url)); store.initialize()
      val epoch = CaptureEpoch("cap", 1, "lineage", 7, Some("offset"), digest, digest)
      store.create(CdcSavepointHeader("sp", "cap", "lineage", epoch, CdcSavepointDigest.tableSet(Vector(ref)), 1, "owner", 1, "CREATING", None))
      val page = CdcSavepointPage("sp", 0, Vector(ref), CdcSavepointDigest.page("sp", 0, Vector(ref)))
      store.appendPage(page); store.appendPage(page); store.seal("sp").status shouldBe "SEALED"
      store.readSealed("sp").get._2 shouldBe Vector(ref)
      intercept[IllegalArgumentException](store.appendPage(page.copy(pageDigest = "d" * 64)))
    }
    "reject a missing page before sealing" in {
      val url   = db(); val store = new CdcSavepointStore(() => DriverManager.getConnection(url)); store.initialize()
      val epoch = CaptureEpoch("cap", 1, "lineage", 7, None, digest, digest)
      store.create(CdcSavepointHeader("sp", "cap", "lineage", epoch, digest, 2, "owner", 1, "CREATING", None))
      intercept[IllegalArgumentException](store.seal("sp"))
    }
    "build stable keyset pages from one capture without creating execution resources" in {
      val url = db(); val tasks = new CdcTaskStore(() => DriverManager.getConnection(url), CdcPlatformLimits(snapshotSteps = 1, tailSteps = 1));
      tasks.initialize()
      tasks.submit(CdcTaskRequest("r1", "cap", "orders", "g1", "target_orders"), 1L)
      tasks.submit(CdcTaskRequest("r2", "other", "products", "g1", "target_products"), 2L)
      val first = tasks.savepointTaskPage("cap", None, 16)
      first.items.size shouldBe 1
      first.items.head.inclusionState shouldBe "ACTIVE"
      first.nextAfter shouldBe None
      tasks.savepointTableSetDigest("cap") shouldBe CdcSavepointDigest.tableSet(first.items)
    }
  }
}
