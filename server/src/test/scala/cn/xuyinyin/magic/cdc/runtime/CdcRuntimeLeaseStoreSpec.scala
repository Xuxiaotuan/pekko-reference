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
      capture.leaseId.nonEmpty shouldBe true
      leases.markCaptureActive(capture) shouldBe true
      leases.acquireCapture("capture", "source-1", "other-incarnation") shouldBe None

      val worker = leases.registerWorker("worker-1", "worker-incarnation", "{\"tail\":1}")
      worker.leaseId.nonEmpty shouldBe true
      leases.heartbeatWorker(worker.copy(leaseId = Some("stale-worker-lease")), 0) shouldBe false
      val task = leases.claimTasks(worker, 1).head
      leases.renewTask(task) shouldBe true
      val stale = task.copy(leaseEpoch = task.leaseEpoch - 1L)
      leases.renewTask(stale) shouldBe false
      leases.releaseTask(task, "proof") shouldBe true
      leases.taskLeaseReleased(task) shouldBe true
      leases.releaseCapture(capture) shouldBe true
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

    "must require the Capture State readiness proof when the roles use separate databases" in {
      val controlUrl = database()
      def openControl() = DriverManager.getConnection(controlUrl)
      val taskStore = new CdcTaskStore(() => openControl(), CdcPlatformLimits(snapshotSteps = 1, tailSteps = 1), true)
      taskStore.initialize()
      taskStore.beginOwner("owner", 1L) shouldBe true
      val task = taskStore.submit(CdcTaskRequest("request", "capture", "orders", "g1", "orders_target"), 2L).task
      val leases = new CdcRuntimeLeaseStore(() => openControl())
      val worker = leases.registerWorker("worker", "incarnation", "{\"snapshotSteps\":1}")

      taskStore.claimWithLease("owner", "SNAPSHOT", Set("capture"), 3L, None, worker, leases) shouldBe None

      val pending = openControl()
      try {
        val insert = pending.prepareStatement("UPDATE pekko_cdc_capture_subscription_v1 SET state='B_PENDING',capture_readiness='B_PENDING' WHERE task_id=?")
        try {
          insert.setString(1, task.taskId)
          insert.executeUpdate()
        } finally insert.close()
      } finally pending.close()
      taskStore.claimWithLease("owner", "SNAPSHOT", Set("capture"), 4L, None, worker, leases) shouldBe None

      val ready = openControl()
      try {
        val update = ready.prepareStatement("UPDATE pekko_cdc_capture_subscription_v1 SET state='CAPTURE_READY',capture_readiness='CAPTURE_READY' WHERE task_id=?")
        try { update.setString(1, task.taskId); update.executeUpdate() }
        finally update.close()
      } finally ready.close()
      taskStore.claimWithLease("owner", "SNAPSHOT", Set("capture"), 5L, None, worker, leases).nonEmpty shouldBe true
    }

    "must not claim a paused task even when its scheduling flag is stale" in {
      val url = database()
      val taskStore = new CdcTaskStore(() => DriverManager.getConnection(url), CdcPlatformLimits(snapshotSteps = 1, tailSteps = 1), true)
      taskStore.initialize()
      taskStore.beginOwner("owner", 1L) shouldBe true
      val task = taskStore.submit(CdcTaskRequest("request", "capture", "orders", "g1", "orders_target"), 2L).task
      val connection = DriverManager.getConnection(url)
      try {
        val update = connection.prepareStatement("UPDATE pekko_cdc_tasks_v1 SET phase='PAUSED',schedulable=TRUE WHERE task_id=?")
        try { update.setString(1, task.taskId); update.executeUpdate() }
        finally update.close()
        val ready = connection.prepareStatement("UPDATE pekko_cdc_capture_subscription_v1 SET state='CAPTURE_READY',capture_readiness='CAPTURE_READY' WHERE task_id=?")
        try { ready.setString(1, task.taskId); ready.executeUpdate() }
        finally ready.close()
      } finally connection.close()
      val leases = new CdcRuntimeLeaseStore(() => DriverManager.getConnection(url))
      val worker = leases.registerWorker("worker", "incarnation", "{\"snapshotSteps\":1}")

      taskStore.claimWithLease("owner", "SNAPSHOT", Set("capture"), 3L, None, worker, leases) shouldBe None
    }

    "bind task progress to both owner epochs and commit T/J/D with the lease renewal" in {
      val url = database()
      val taskStore = new CdcTaskStore(() => DriverManager.getConnection(url), CdcPlatformLimits())
      taskStore.initialize()
      taskStore.submit(CdcTaskRequest("request", "capture", "orders", "g1", "orders_target"), 1L)
      val leases = new CdcRuntimeLeaseStore(() => DriverManager.getConnection(url))
      val capture = leases.acquireCapture("capture", "source-1", "capture-incarnation").get
      leases.markCaptureActive(capture) shouldBe true
      val worker = leases.registerWorker("worker", "incarnation", "{\"snapshotSteps\":1}")
      val task = leases.claimTasks(worker, 1).head

      task.captureOwnerEpoch shouldBe Some(capture.ownerEpoch)
      task.workerEpoch shouldBe Some(worker.leaseEpoch)
      task.leaseId.nonEmpty shouldBe true

      val progressed = leases.recordProgress(TaskProgressCommit(task,
        TaskProgressSnapshot(7L, 3L, Some("target-batch-1")), Some("COMMIT"), Some("ack-digest"), Some("progress-1"), Some("progress-digest-1")))
      progressed.lastLSeq shouldBe 7L
      progressed.lastJournalRevision shouldBe 3L
      progressed.lastTargetBatch shouldBe Some("target-batch-1")
      leases.recordProgress(TaskProgressCommit(progressed,
        TaskProgressSnapshot(7L, 3L, Some("target-batch-1")), Some("COMMIT"), Some("ack-digest"), Some("progress-1"), Some("progress-digest-1"))).lastProgressId shouldBe Some("progress-1")
      intercept[IllegalArgumentException](leases.recordProgress(TaskProgressCommit(progressed,
        TaskProgressSnapshot(8L, 4L, Some("target-batch-2")), Some("COMMIT"), Some("ack-digest-2"), Some("progress-1"), Some("different-digest")))).getMessage should include("progress id reused")

      val nextCapture = {
        leases.releaseCapture(capture) shouldBe true
        val value = leases.acquireCapture("capture", "source-1", "capture-incarnation-2").get
        leases.markCaptureActive(value) shouldBe true
        value
      }
      nextCapture.ownerEpoch shouldBe capture.ownerEpoch + 1L
      intercept[IllegalArgumentException](leases.renewTask(progressed)).getMessage should include("capture owner epoch changed")
    }

    "must not let an older unready task block a later ready task in the same capture" in {
      val url = database()
      val taskStore = new CdcTaskStore(() => DriverManager.getConnection(url), CdcPlatformLimits(snapshotSteps = 1, tailSteps = 1), true)
      taskStore.initialize(); taskStore.beginOwner("owner", 1L) shouldBe true
      val old = taskStore.submit(CdcTaskRequest("old", "capture", "old_orders", "g1", "old_target"), 2L).task
      val ready = taskStore.submit(CdcTaskRequest("ready", "capture", "ready_orders", "g1", "ready_target"), 3L).task
      val connection = DriverManager.getConnection(url)
      try {
        val update = connection.prepareStatement("UPDATE pekko_cdc_capture_subscription_v1 SET state='CAPTURE_READY',capture_readiness='CAPTURE_READY' WHERE task_id=?")
        try { update.setString(1, ready.taskId); update.executeUpdate() }
        finally update.close()
      } finally connection.close()
      val leases = new CdcRuntimeLeaseStore(() => DriverManager.getConnection(url))
      val worker = leases.registerWorker("worker", "incarnation", "{\"snapshotSteps\":1}")
      val claimed = taskStore.claimWithLease("owner", "SNAPSHOT", Set("capture"), 4L, None, worker, leases)
      claimed.map(_._1.task.view.taskId) shouldBe Some(ready.taskId)
      old.taskId should not be ready.taskId
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

    "must allow a new worker to claim only after the old task lease expires" in {
      val url = database()
      val taskStore = new CdcTaskStore(() => DriverManager.getConnection(url), CdcPlatformLimits())
      taskStore.initialize()
      taskStore.submit(CdcTaskRequest("request", "capture", "orders", "g1", "orders_target"), 1L)
      val leases = new CdcRuntimeLeaseStore(() => DriverManager.getConnection(url))
      val oldWorker = leases.registerWorker("worker-old", "old-incarnation", "{\"snapshotSteps\":1}")
      val oldTask = leases.claimTasks(oldWorker, 1).head
      val connection = DriverManager.getConnection(url)
      try {
        val update = connection.prepareStatement("UPDATE pekko_cdc_task_lease_v1 SET lease_expires_at=0 WHERE task_id=?")
        try { update.setString(1, oldTask.taskId); update.executeUpdate() }
        finally update.close()
      } finally connection.close()

      val newWorker = leases.registerWorker("worker-new", "new-incarnation", "{\"snapshotSteps\":1}")
      val replacement = leases.claimTasks(newWorker, 1)
      replacement.size shouldBe 1
      replacement.head.workerId shouldBe newWorker.workerId
      replacement.head.leaseEpoch should be > oldTask.leaseEpoch
    }

    "must retain an UNKNOWN task lease until an explicit recovery path clears it" in {
      val url = database()
      val taskStore = new CdcTaskStore(() => DriverManager.getConnection(url), CdcPlatformLimits())
      taskStore.initialize()
      taskStore.submit(CdcTaskRequest("request", "capture", "orders", "g1", "orders_target"), 1L)
      val leases = new CdcRuntimeLeaseStore(() => DriverManager.getConnection(url))
      val oldWorker = leases.registerWorker("worker-old", "old-incarnation", "{\"snapshotSteps\":1}")
      val oldTask = leases.claimTasks(oldWorker, 1).head
      leases.releaseTask(oldTask, "release-unknown", "UNKNOWN") shouldBe true
      val newWorker = leases.registerWorker("worker-new", "new-incarnation", "{\"snapshotSteps\":1}")

      leases.claimTasks(newWorker, 1) shouldBe empty
    }

    "must fence an UNKNOWN worker from same-id re-registration until it is stopped" in {
      val url = database()
      val leases = new CdcRuntimeLeaseStore(() => DriverManager.getConnection(url))
      leases.initialize()
      val oldWorker = leases.registerWorker("worker-reused", "old-incarnation", "{\"snapshotSteps\":1}")
      leases.releaseWorker(oldWorker, "UNKNOWN") shouldBe true

      val error = intercept[IllegalArgumentException] {
        leases.registerWorker("worker-reused", "new-incarnation", "{\"snapshotSteps\":1}")
      }
      error.getMessage should include("worker identity is fenced")

      val connection = DriverManager.getConnection(url)
      try {
        val update = connection.prepareStatement("UPDATE pekko_cdc_worker_v1 SET state='STOPPED' WHERE worker_id=? AND incarnation=? AND worker_epoch=?")
        try {
          update.setString(1, oldWorker.workerId)
          update.setString(2, oldWorker.incarnation)
          update.setLong(3, oldWorker.leaseEpoch)
          update.executeUpdate() shouldBe 1
        } finally update.close()
      } finally connection.close()
      val replacement = leases.registerWorker("worker-reused", "new-incarnation", "{\"snapshotSteps\":1}")
      replacement.leaseEpoch shouldBe oldWorker.leaseEpoch + 1L
      replacement.incarnation shouldBe "new-incarnation"
    }

    "must converge an expired legacy owner only when no executable work remains" in {
      val url = database()
      val taskStore = new CdcTaskStore(() => DriverManager.getConnection(url), CdcPlatformLimits())
      taskStore.initialize()
      taskStore.beginOwner("legacy-worker", 1L) shouldBe true
      val leases = new CdcRuntimeLeaseStore(() => DriverManager.getConnection(url))
      leases.registerWorker("legacy-worker", "legacy-incarnation", "{}")
      val connection = DriverManager.getConnection(url)
      try {
        val update = connection.prepareStatement("UPDATE pekko_cdc_worker_v1 SET lease_expires_at=0 WHERE worker_id=?")
        try { update.setString(1, "legacy-worker"); update.executeUpdate() }
        finally update.close()
      } finally connection.close()
      leases.recoverExpiredOwner(System.currentTimeMillis()) shouldBe true
      taskStore.ownerStatus shouldBe "STOPPED"
    }

    "must requeue an UNKNOWN grant only when it has no target batch and its task is paused" in {
      val url = database()
      val taskStore = new CdcTaskStore(() => DriverManager.getConnection(url), CdcPlatformLimits())
      taskStore.initialize()
      taskStore.beginOwner("legacy-worker", 1L) shouldBe true
      val accepted = taskStore.submit(CdcTaskRequest("request", "capture", "orders", "g1", "orders_target"), 1L)
      val leases = new CdcRuntimeLeaseStore(() => DriverManager.getConnection(url))
      val worker = leases.registerWorker("legacy-worker", "legacy-incarnation", "{}"); val task = leases.claimTasks(worker, 1).head
      leases.releaseTask(task, "release-unknown", "UNKNOWN") shouldBe true
      val connection = DriverManager.getConnection(url)
      try {
        val grant = connection.prepareStatement("INSERT INTO pekko_cdc_grants_v1 VALUES(?,?,?,?,?, ?,?)")
        try { grant.setString(1, "unknown-grant"); grant.setString(2, accepted.task.taskId); grant.setString(3, "legacy-worker"); grant.setString(4, "SNAPSHOT"); grant.setString(5, "UNKNOWN"); grant.setLong(6, 1L); grant.setObject(7, null); grant.executeUpdate() } finally grant.close()
        val taskState = connection.prepareStatement("UPDATE pekko_cdc_tasks_v1 SET phase='PAUSED',runtime_health='UNKNOWN',release_status='UNKNOWN',schedulable=FALSE,grant_id=? WHERE task_id=?")
        try { taskState.setString(1, "unknown-grant"); taskState.setString(2, accepted.task.taskId); taskState.executeUpdate() } finally taskState.close()
        val expire = connection.prepareStatement("UPDATE pekko_cdc_worker_v1 SET lease_expires_at=0 WHERE worker_id=?")
        try { expire.setString(1, worker.workerId); expire.executeUpdate() } finally expire.close()
      } finally connection.close()

      leases.recoverExpiredOwner(System.currentTimeMillis()) shouldBe true
      val check = DriverManager.getConnection(url)
      try {
        val query = check.prepareStatement("SELECT status FROM pekko_cdc_grants_v1 WHERE grant_id='unknown-grant'")
        try { val rows=query.executeQuery(); try { rows.next() shouldBe true; rows.getString(1) shouldBe "RELEASED" } finally rows.close() } finally query.close()
        val audit = check.prepareStatement("SELECT decision FROM pekko_cdc_unknown_recovery_v1 WHERE grant_id='unknown-grant'")
        try { val rows=audit.executeQuery(); try { rows.next() shouldBe true; rows.getString(1) shouldBe "REPLAY_WITHOUT_TARGET_BATCH" } finally rows.close() } finally audit.close()
      } finally check.close()
      taskStore.ownerStatus shouldBe "STOPPED"
    }

    "must requeue a released task left in SCANNING after an expired worker owner" in {
      val url = database()
      val taskStore = new CdcTaskStore(() => DriverManager.getConnection(url), CdcPlatformLimits())
      taskStore.initialize()
      taskStore.beginOwner("worker-abandoned", 1L) shouldBe true
      val accepted = taskStore.submit(CdcTaskRequest("request-abandoned", "capture", "orders", "g1", "orders_target"), 1L)
      val leases = new CdcRuntimeLeaseStore(() => DriverManager.getConnection(url))
      val worker = leases.registerWorker("worker-abandoned", "incarnation", "{\"snapshotSteps\":1}")
      val task = leases.claimTasks(worker, 1).head
      leases.releaseTask(task, "step-released", "RELEASED") shouldBe true
      val connection = DriverManager.getConnection(url)
      try {
        val taskState = connection.prepareStatement("UPDATE pekko_cdc_tasks_v1 SET phase='SCANNING',runtime_health='UNKNOWN',release_status='RELEASED',schedulable=FALSE WHERE task_id=?")
        try { taskState.setString(1, accepted.task.taskId); taskState.executeUpdate() shouldBe 1 } finally taskState.close()
        val workerState = connection.prepareStatement("UPDATE pekko_cdc_worker_v1 SET lease_expires_at=0 WHERE worker_id=?")
        try { workerState.setString(1, worker.workerId); workerState.executeUpdate() shouldBe 1 } finally workerState.close()
      } finally connection.close()

      leases.recoverExpiredOwner(System.currentTimeMillis()) shouldBe true
      val replacement = leases.registerWorker("worker-replacement", "incarnation", "{\"snapshotSteps\":1}")
      leases.claimTasks(replacement, 1).map(_.taskId) shouldBe Vector(accepted.task.taskId)
    }

    "must project task lifecycle changes into the capture subscription view" in {
      val url = database()
      val taskStore = new CdcTaskStore(() => DriverManager.getConnection(url), CdcPlatformLimits())
      taskStore.initialize()
      val accepted = taskStore.submit(CdcTaskRequest("request", "capture", "orders", "g1", "orders_target"), 1L)
      def projection(): (String,String) = {
        val connection = DriverManager.getConnection(url)
        try {
          val query = connection.prepareStatement("SELECT desired_state,state FROM pekko_cdc_capture_subscription_v1 WHERE task_id=?")
          try {
            query.setString(1, accepted.task.taskId)
            val rows = query.executeQuery()
            try { rows.next() shouldBe true; rows.getString(1) -> rows.getString(2) }
            finally rows.close()
          } finally query.close()
        } finally connection.close()
      }
      projection() shouldBe "RUNNING" -> "QUEUED"
      taskStore.cancel(accepted.task.taskId, 2L).phase shouldBe "CANCELED"
      projection() shouldBe "CANCELED" -> "CANCELED"
    }

    "must initialize the runtime lease schema without the worker task store" in {
      val url = database()
      val leases = new CdcRuntimeLeaseStore(() => DriverManager.getConnection(url))
      leases.initialize()
      leases.acquireCapture("capture", "source-1", "capture-incarnation").nonEmpty shouldBe true
      val connection = DriverManager.getConnection(url)
      try {
        val query = connection.prepareStatement("SELECT checksum FROM pekko_cdc_runtime_schema_version WHERE version=?")
        try {
          query.setInt(1, CdcRuntimeSchemaVersion.leaseVersion)
          val rows = query.executeQuery()
          try { rows.next() shouldBe true; rows.getString(1) shouldBe CdcRuntimeSchemaVersion.leaseChecksum }
          finally rows.close()
        } finally query.close()
        val columns = connection.prepareStatement("SELECT capture_owner_epoch,capture_lease_id,readiness_digest FROM pekko_cdc_capture_subscription_v1 WHERE 1=0")
        try columns.executeQuery().close()
        finally columns.close()
      } finally connection.close()
    }
  }
}
