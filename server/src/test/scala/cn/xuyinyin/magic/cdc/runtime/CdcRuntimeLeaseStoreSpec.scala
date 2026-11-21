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

    "must not create a Grant when the task lease is already fenced by another worker" in {
      val url = database()
      val taskStore = new CdcTaskStore(() => DriverManager.getConnection(url), CdcPlatformLimits(snapshotSteps = 1, tailSteps = 1))
      taskStore.initialize()
      taskStore.beginOwner("owner-attempt", 1L) shouldBe true
      taskStore.submit(CdcTaskRequest("request", "capture", "orders", "g1", "orders_target"), 1L)
      val leases = new CdcRuntimeLeaseStore(() => DriverManager.getConnection(url))
      val incumbent = leases.registerWorker("worker-incumbent", "incumbent-incarnation", "{\"snapshotSteps\":1}")
      leases.claimTasks(incumbent, 1).size shouldBe 1
      val candidate = leases.registerWorker("worker-candidate", "candidate-incarnation", "{\"snapshotSteps\":1}")

      taskStore.claimWithLease("owner-attempt", "SNAPSHOT", Set("capture"), 2L, None, candidate, leases) shouldBe None
      taskStore.grants shouldBe empty
    }

    "must renew capture and worker leases only for the current fencing identity" in {
      val url = database()
      val taskStore = new CdcTaskStore(() => DriverManager.getConnection(url), CdcPlatformLimits())
      taskStore.initialize()
      val leases = new CdcRuntimeLeaseStore(() => DriverManager.getConnection(url))
      val capture = leases.acquireCapture("capture", "source-1", "capture-incarnation").get
      leases.markCaptureActive(capture) shouldBe true
      leases.renewCapture(capture, 5000L) shouldBe true
      leases.renewCapture(capture.copy(ownerEpoch = capture.ownerEpoch - 1L), 5000L) shouldBe false
      val worker = leases.registerWorker("worker-1", "worker-incarnation", "{\"snapshotSteps\":1}")
      leases.heartbeatWorker(worker, 0, 5000L) shouldBe true
      leases.heartbeatWorker(worker.copy(leaseEpoch = worker.leaseEpoch - 1L), 0, 5000L) shouldBe false
    }

    "must reject an expired worker lease before claiming or renewing work" in {
      val url = database()
      val taskStore = new CdcTaskStore(() => DriverManager.getConnection(url), CdcPlatformLimits())
      taskStore.initialize()
      taskStore.submit(CdcTaskRequest("request", "capture", "orders", "g1", "orders_target"), 1L)
      val leases = new CdcRuntimeLeaseStore(() => DriverManager.getConnection(url))
      val worker = leases.registerWorker("worker-expired", "incarnation", "{\"snapshotSteps\":1}")
      val connection = DriverManager.getConnection(url)
      try {
        val update = connection.prepareStatement("UPDATE pekko_cdc_worker_v1 SET lease_expires_at=0 WHERE worker_id=?")
        try { update.setString(1, worker.workerId); update.executeUpdate() }
        finally update.close()
      } finally connection.close()

      intercept[IllegalArgumentException](leases.claimTasks(worker, 1)).getMessage should include("worker lease is stale")
      leases.heartbeatWorker(worker, 0, 5000L) shouldBe false
    }

    "must validate a repeated capture acquisition against the requested incarnation" in {
      val current = CaptureLease("capture", "source-1", "attempt", 3L, "incarnation-current")
      CdcCaptureRuntime.requireIncarnation(current, "incarnation-current") shouldBe current
      intercept[IllegalArgumentException](CdcCaptureRuntime.requireIncarnation(current, "incarnation-old")).getMessage should include("capture incarnation is stale")
    }

    "must initialize the runtime lease schema without the worker task store" in {
      val url = database()
      val leases = new CdcRuntimeLeaseStore(() => DriverManager.getConnection(url))
      leases.initialize()
      leases.acquireCapture("capture", "source-1", "capture-incarnation").nonEmpty shouldBe true
    }
  }
}
