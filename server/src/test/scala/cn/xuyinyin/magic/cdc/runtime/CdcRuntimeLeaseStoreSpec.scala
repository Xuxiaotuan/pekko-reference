package cn.xuyinyin.magic.cdc.runtime

import cn.xuyinyin.magic.cdc.platform.{CdcPlatformLimits, CdcTaskRequest, CdcTaskStore}
import cn.xuyinyin.magic.testkit.STSpec
import java.sql.DriverManager
import java.util.UUID

class CdcRuntimeLeaseStoreSpec extends STSpec {
  private def database(): String =
    "jdbc:h2:mem:cdc_runtime_lease_" + UUID.randomUUID().toString.replace('-', '_') +
      ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"

  "two-role runtime leases" should {
    "fence capture owners and task leases by epoch" in {
      val url = database()
      val taskStore = new CdcTaskStore(() => DriverManager.getConnection(url), CdcPlatformLimits())
      taskStore.initialize()
      taskStore.submit(CdcTaskRequest("request", "capture", "orders", "g1", "orders_target"), 1L)
      val leases = new CdcRuntimeLeaseStore(() => DriverManager.getConnection(url))

      val capture = leases.acquireCapture("capture", "source-1", "capture-incarnation").get
      leases.markCaptureActive(capture) shouldBe true
      leases.acquireCapture("capture", "source-1", "other-incarnation") shouldBe None
      leases.releaseCapture(capture) shouldBe true

      val worker = leases.registerWorker("worker-1", "worker-incarnation", "{\"tail\":1}")
      val task = leases.claimTasks(worker, 1).head
      leases.renewTask(task) shouldBe true
      val stale = task.copy(leaseEpoch = task.leaseEpoch - 1L)
      leases.renewTask(stale) shouldBe false
      leases.releaseTask(task, "proof") shouldBe true
    }
  }
}
