package cn.xuyinyin.magic.cdc.runtime

import java.sql.{Connection, PreparedStatement}
import java.util.UUID

/** Additive lease store used by the two-role runtime. Legacy owner and grant rows remain untouched. */
final class CdcRuntimeLeaseStore(open: () => Connection) {
  private def withConnection[A](f: Connection => A): A = {
    val connection = open()
    try f(connection) finally connection.close()
  }

  private def transaction[A](f: Connection => A): A = withConnection { connection =>
    val previous = connection.getAutoCommit
    connection.setAutoCommit(false)
    try {
      val result = f(connection)
      connection.commit()
      result
    } catch {
      case error: Throwable =>
        try connection.rollback() catch { case _: Throwable => () }
        throw error
    } finally connection.setAutoCommit(previous)
  }

  private def queryOne(statement: PreparedStatement): Option[Map[String, String]] = {
    val result = statement.executeQuery()
    try {
      if (!result.next()) None
      else {
        val metadata = result.getMetaData
        Some((1 to metadata.getColumnCount).map { index =>
          metadata.getColumnLabel(index) -> result.getString(index)
        }.toMap)
      }
    } finally result.close()
  }

  def acquireCapture(captureId: String, sourceLineage: String, incarnation: String): Option[CaptureLease] =
    transaction { connection =>
      val current = connection.prepareStatement(
        "SELECT owner_attempt,owner_epoch,state FROM pekko_cdc_capture_owner_v1 WHERE capture_id=? FOR UPDATE")
      current.setString(1, captureId)
      val row = try queryOne(current) finally current.close()
      val allowed = row.forall(r => Set("STOPPED", "RELEASED").contains(r("state")))
      if (!allowed) None
      else {
        val attempt = UUID.randomUUID().toString
        val epoch = row.map(_("owner_epoch").toLong + 1L).getOrElse(1L)
        val statement = connection.prepareStatement(
          "INSERT INTO pekko_cdc_capture_owner_v1(capture_id,source_lineage,owner_attempt,owner_epoch,incarnation,state,updated_at) VALUES(?,?,?,?,?,?,?) " +
            "ON DUPLICATE KEY UPDATE source_lineage=VALUES(source_lineage),owner_attempt=VALUES(owner_attempt),owner_epoch=VALUES(owner_epoch),incarnation=VALUES(incarnation),state=VALUES(state),updated_at=VALUES(updated_at)")
        try {
          statement.setString(1, captureId)
          statement.setString(2, sourceLineage)
          statement.setString(3, attempt)
          statement.setLong(4, epoch)
          statement.setString(5, incarnation)
          statement.setString(6, "ACQUIRING")
          statement.setLong(7, System.currentTimeMillis())
          statement.executeUpdate()
        } finally statement.close()
        Some(CaptureLease(captureId, sourceLineage, attempt, epoch, incarnation))
      }
    }

  def markCaptureActive(lease: CaptureLease): Boolean = updateCaptureState(lease, "ACQUIRING", "ACTIVE")

  def releaseCapture(lease: CaptureLease, state: String = "STOPPED"): Boolean = {
    require(Set("DRAINING", "STOPPED", "UNKNOWN").contains(state), "invalid capture release state")
    transaction { connection =>
      val statement = connection.prepareStatement(
        "UPDATE pekko_cdc_capture_owner_v1 SET state=?,updated_at=? WHERE capture_id=? AND owner_attempt=? AND owner_epoch=?")
      try {
        statement.setString(1, state)
        statement.setLong(2, System.currentTimeMillis())
        statement.setString(3, lease.captureId)
        statement.setString(4, lease.ownerAttempt)
        statement.setLong(5, lease.ownerEpoch)
        statement.executeUpdate() == 1
      } finally statement.close()
    }
  }

  private def updateCaptureState(lease: CaptureLease, expected: String, next: String): Boolean =
    transaction { connection =>
      val statement = connection.prepareStatement(
        "UPDATE pekko_cdc_capture_owner_v1 SET state=?,updated_at=? WHERE capture_id=? AND owner_attempt=? AND owner_epoch=? AND state=?")
      try {
        statement.setString(1, next)
        statement.setLong(2, System.currentTimeMillis())
        statement.setString(3, lease.captureId)
        statement.setString(4, lease.ownerAttempt)
        statement.setLong(5, lease.ownerEpoch)
        statement.setString(6, expected)
        statement.executeUpdate() == 1
      } finally statement.close()
    }

  def registerWorker(workerId: String, incarnation: String, capacityJson: String): WorkerLease = transaction { connection =>
    val existing = connection.prepareStatement("SELECT worker_epoch FROM pekko_cdc_worker_v1 WHERE worker_id=? FOR UPDATE")
    val nextEpoch = try {
      existing.setString(1, workerId)
      queryOne(existing).map(_("worker_epoch").toLong + 1L).getOrElse(1L)
    } finally existing.close()
    val statement = connection.prepareStatement(
      "INSERT INTO pekko_cdc_worker_v1(worker_id,incarnation,worker_epoch,state,capacity_json,active_graphs,heartbeat_at,updated_at) VALUES(?,?,?, ?,?,0,?,?) " +
        "ON DUPLICATE KEY UPDATE incarnation=VALUES(incarnation),worker_epoch=VALUES(worker_epoch),state='READY',capacity_json=VALUES(capacity_json),heartbeat_at=VALUES(heartbeat_at),updated_at=VALUES(updated_at)")
    try {
      val now = System.currentTimeMillis()
      statement.setString(1, workerId)
      statement.setString(2, incarnation)
      statement.setLong(3, nextEpoch)
      statement.setString(4, "READY")
      statement.setString(5, capacityJson)
      statement.setLong(6, now)
      statement.setLong(7, now)
      statement.executeUpdate()
    } finally statement.close()
    val read = connection.prepareStatement("SELECT COUNT(*) AS n FROM pekko_cdc_task_lease_v1 WHERE worker_id=? AND state='ACTIVE'")
    try { read.setString(1, workerId); queryOne(read) } finally read.close()
    WorkerLease(workerId, incarnation, nextEpoch)
  }

  def heartbeat(worker: WorkerLease, activeGraphs: Int): Boolean = transaction { connection =>
    val statement = connection.prepareStatement(
      "UPDATE pekko_cdc_worker_v1 SET heartbeat_at=?,active_graphs=?,updated_at=? WHERE worker_id=? AND incarnation=? AND state IN ('READY','ACTIVE','DRAINING')")
    try {
      val now = System.currentTimeMillis()
      statement.setLong(1, now)
      statement.setInt(2, activeGraphs)
      statement.setLong(3, now)
      statement.setString(4, worker.workerId)
      statement.setString(5, worker.incarnation)
      statement.executeUpdate() == 1
    } finally statement.close()
  }

  def claimTasks(worker: WorkerLease, limit: Int): Vector[TaskLease] = transaction { connection =>
    require(limit > 0 && limit <= 128, "invalid task lease page")
    val tasks = connection.prepareStatement(
      "SELECT task_id,capture_id FROM pekko_cdc_tasks_v1 WHERE desired_state='RUNNING' AND phase NOT IN ('PAUSED','CANCELED') " +
        "AND NOT EXISTS (SELECT 1 FROM pekko_cdc_task_lease_v1 l WHERE l.task_id=pekko_cdc_tasks_v1.task_id AND l.state IN ('ACTIVE','DRAINING','UNKNOWN')) " +
        "ORDER BY task_id LIMIT ? FOR UPDATE")
    try {
      tasks.setInt(1, limit)
      val result = tasks.executeQuery()
      try {
        val leases = Vector.newBuilder[TaskLease]
        while (result.next()) {
          val taskId = result.getString("task_id")
          val captureId = result.getString("capture_id")
          val next = connection.prepareStatement(
            "SELECT COALESCE(MAX(lease_epoch),0)+1 AS next_epoch FROM pekko_cdc_task_lease_v1 WHERE task_id=?")
          val epoch = try { next.setString(1, taskId); queryOne(next).map(_("next_epoch").toLong).getOrElse(1L) } finally next.close()
          val insert = connection.prepareStatement(
            "INSERT INTO pekko_cdc_task_lease_v1(task_id,capture_id,worker_id,worker_incarnation,lease_epoch,state,last_l_seq,last_journal_revision,updated_at) VALUES(?,?,?,?,?,'ACTIVE',0,0,?)")
          try {
            insert.setString(1, taskId); insert.setString(2, captureId); insert.setString(3, worker.workerId)
            insert.setString(4, worker.incarnation); insert.setLong(5, epoch); insert.setLong(6, System.currentTimeMillis()); insert.executeUpdate()
          } finally insert.close()
          leases += TaskLease(taskId, captureId, worker.workerId, worker.incarnation, epoch, 0L, 0L, None)
        }
        leases.result()
      } finally result.close()
    } finally tasks.close()
  }

  def claimTask(worker: WorkerLease, taskId: String, captureId: String): Option[TaskLease] = transaction { connection =>
    val existing = connection.prepareStatement("SELECT state FROM pekko_cdc_task_lease_v1 WHERE task_id=? FOR UPDATE")
    val state = try { existing.setString(1, taskId); queryOne(existing).map(_("state")) } finally existing.close()
    if (state.exists(Set("ACTIVE", "DRAINING", "UNKNOWN"))) None
    else {
      val next = connection.prepareStatement("SELECT COALESCE(MAX(lease_epoch),0)+1 AS next_epoch FROM pekko_cdc_task_lease_v1 WHERE task_id=?")
      val epoch = try { next.setString(1, taskId); queryOne(next).map(_("next_epoch").toLong).getOrElse(1L) } finally next.close()
      val insert = connection.prepareStatement(
        "INSERT INTO pekko_cdc_task_lease_v1(task_id,capture_id,worker_id,worker_incarnation,lease_epoch,state,last_l_seq,last_journal_revision,updated_at) VALUES(?,?,?,?,?,'ACTIVE',0,0,?) " +
          "ON DUPLICATE KEY UPDATE capture_id=VALUES(capture_id),worker_id=VALUES(worker_id),worker_incarnation=VALUES(worker_incarnation),lease_epoch=VALUES(lease_epoch),state='ACTIVE',release_proof=NULL,updated_at=VALUES(updated_at)")
      try {
        val now = System.currentTimeMillis()
        insert.setString(1, taskId); insert.setString(2, captureId); insert.setString(3, worker.workerId)
        insert.setString(4, worker.incarnation); insert.setLong(5, epoch); insert.setLong(6, now); insert.executeUpdate()
      } finally insert.close()
      Some(TaskLease(taskId, captureId, worker.workerId, worker.incarnation, epoch, 0L, 0L, None))
    }
  }

  def renewTask(lease: TaskLease): Boolean = transaction { connection =>
    val statement = connection.prepareStatement(
      "UPDATE pekko_cdc_task_lease_v1 SET updated_at=? WHERE task_id=? AND worker_id=? AND worker_incarnation=? AND lease_epoch=? AND state='ACTIVE'")
    try {
      statement.setLong(1, System.currentTimeMillis()); statement.setString(2, lease.taskId)
      statement.setString(3, lease.workerId); statement.setString(4, lease.workerIncarnation); statement.setLong(5, lease.leaseEpoch)
      statement.executeUpdate() == 1
    } finally statement.close()
  }

  def releaseTask(lease: TaskLease, proof: String, state: String = "RELEASED"): Boolean = transaction { connection =>
    require(Set("RELEASED", "UNKNOWN").contains(state), "invalid task release state")
    val statement = connection.prepareStatement(
      "UPDATE pekko_cdc_task_lease_v1 SET state=?,release_proof=?,updated_at=? WHERE task_id=? AND worker_id=? AND worker_incarnation=? AND lease_epoch=? AND state='ACTIVE'")
    try {
      statement.setString(1, state); statement.setString(2, proof); statement.setLong(3, System.currentTimeMillis())
      statement.setString(4, lease.taskId); statement.setString(5, lease.workerId); statement.setString(6, lease.workerIncarnation); statement.setLong(7, lease.leaseEpoch)
      statement.executeUpdate() == 1
    } finally statement.close()
  }

  def beginHandoff(lease: TaskLease): Option[String] = transaction { connection =>
    val handoffId = UUID.randomUUID().toString
    val update = connection.prepareStatement(
      "UPDATE pekko_cdc_task_lease_v1 SET state='DRAINING',updated_at=? WHERE task_id=? AND worker_id=? AND worker_incarnation=? AND lease_epoch=? AND state='ACTIVE'")
    val changed = try {
      update.setLong(1, System.currentTimeMillis()); update.setString(2, lease.taskId)
      update.setString(3, lease.workerId); update.setString(4, lease.workerIncarnation); update.setLong(5, lease.leaseEpoch)
      update.executeUpdate() == 1
    } finally update.close()
    if (!changed) None
    else {
      val insert = connection.prepareStatement(
        "INSERT INTO pekko_cdc_task_handoff_v1(handoff_id,task_id,old_worker_id,old_incarnation,old_lease_epoch,state,created_at,updated_at) VALUES(?,?,?,?,?,'DRAINING',?,?)")
      try {
        val now = System.currentTimeMillis()
        insert.setString(1, handoffId); insert.setString(2, lease.taskId); insert.setString(3, lease.workerId)
        insert.setString(4, lease.workerIncarnation); insert.setLong(5, lease.leaseEpoch); insert.setLong(6, now); insert.setLong(7, now)
        insert.executeUpdate()
      } finally insert.close()
      Some(handoffId)
    }
  }
}
