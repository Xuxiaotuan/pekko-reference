package cn.xuyinyin.magic.cdc.runtime

import java.sql.{Connection, PreparedStatement}
import java.util.UUID

/** Additive lease store used by the two-role runtime. Legacy owner and grant rows remain untouched. */
final class CdcRuntimeLeaseStore(open: () => Connection) {
  private val defaultLeaseMillis = 5000L
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

  /** Initializes only the two-role runtime tables. Existing task and Journal tables are untouched. */
  def initialize(): Unit = transaction { connection =>
    val ddl = Vector(
      "CREATE TABLE IF NOT EXISTS pekko_cdc_runtime_schema_version(version INT PRIMARY KEY,checksum CHAR(64) NOT NULL,applied_at BIGINT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_capture_subscription_v1(task_id CHAR(64) PRIMARY KEY,capture_id VARCHAR(64) NOT NULL,request_id VARCHAR(128) NOT NULL,table_name VARCHAR(128) NOT NULL,generation VARCHAR(128) NOT NULL,desired_state VARCHAR(32) NOT NULL,state VARCHAR(32) NOT NULL,control_revision BIGINT NOT NULL,capture_readiness VARCHAR(32) NOT NULL,initialization_evidence LONGTEXT NULL,b_offset LONGTEXT NULL,high_offset LONGTEXT NULL,f_offset LONGTEXT NULL,k_offset LONGTEXT NULL,owner_attempt VARCHAR(128) NULL,updated_at BIGINT NOT NULL,UNIQUE(capture_id,table_name,generation),INDEX subscription_reconcile(capture_id,desired_state,state))",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_capture_owner_v1(capture_id VARCHAR(64) PRIMARY KEY,source_lineage VARCHAR(255) NOT NULL,owner_attempt VARCHAR(128) NOT NULL,owner_epoch BIGINT NOT NULL,incarnation VARCHAR(128) NOT NULL,state VARCHAR(32) NOT NULL,reader_session VARCHAR(128) NULL,source_offset_digest CHAR(64) NULL,release_proof_id VARCHAR(128) NULL,lease_expires_at BIGINT NULL,last_heartbeat_at BIGINT NULL,control_revision BIGINT NOT NULL DEFAULT 0,fence_token_hash CHAR(64) NULL,lease_id VARCHAR(128) NULL,drain_started_at BIGINT NULL,drain_deadline_at BIGINT NULL,failure_code VARCHAR(64) NULL,last_l_seq BIGINT NULL,last_source_epoch BIGINT NULL,updated_at BIGINT NOT NULL,UNIQUE(capture_id,owner_attempt))",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_worker_v1(worker_id VARCHAR(128) PRIMARY KEY,incarnation VARCHAR(128) NOT NULL,worker_epoch BIGINT NOT NULL,state VARCHAR(32) NOT NULL,capacity_json LONGTEXT NOT NULL,active_graphs INT NOT NULL,heartbeat_at BIGINT NOT NULL,lease_expires_at BIGINT NULL,last_heartbeat_at BIGINT NULL,drain_started_at BIGINT NULL,drain_deadline_at BIGINT NULL,max_active_graphs INT NULL,failure_code VARCHAR(64) NULL,lease_id VARCHAR(128) NULL,control_revision BIGINT NOT NULL DEFAULT 0,updated_at BIGINT NOT NULL,UNIQUE(worker_id,incarnation))",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_task_lease_v1(task_id CHAR(64) PRIMARY KEY,capture_id VARCHAR(64) NOT NULL,worker_id VARCHAR(128) NULL,worker_incarnation VARCHAR(128) NULL,lease_epoch BIGINT NOT NULL,state VARCHAR(32) NOT NULL,last_l_seq BIGINT NOT NULL,last_journal_revision BIGINT NOT NULL,last_target_batch VARCHAR(128) NULL,release_proof LONGTEXT NULL,lease_expires_at BIGINT NULL,renewed_at BIGINT NULL,handoff_id VARCHAR(128) NULL,fence_token_hash CHAR(64) NULL,lease_id VARCHAR(128) NULL,last_ack_kind VARCHAR(16) NULL,last_ack_digest CHAR(64) NULL,unknown_since BIGINT NULL,release_state VARCHAR(32) NULL,failure_code VARCHAR(64) NULL,control_revision BIGINT NOT NULL DEFAULT 0,updated_at BIGINT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_task_handoff_v1(handoff_id VARCHAR(128) PRIMARY KEY,task_id CHAR(64) NOT NULL,old_worker_id VARCHAR(128) NOT NULL,old_incarnation VARCHAR(128) NOT NULL,old_lease_epoch BIGINT NOT NULL,new_worker_id VARCHAR(128) NULL,new_incarnation VARCHAR(128) NULL,new_lease_epoch BIGINT NULL,state VARCHAR(32) NOT NULL,t_digest CHAR(64) NULL,journal_revision BIGINT NULL,d_seq BIGINT NULL,release_proof LONGTEXT NULL,old_release_state VARCHAR(32) NULL,proof_digest CHAR(64) NULL,new_claimed_at BIGINT NULL,failure_code VARCHAR(64) NULL,created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL)"
    )
    ddl.foreach { sql =>
      val statement=connection.createStatement()
      try statement.executeUpdate(sql) finally statement.close()
    }
    val columns=Vector(
      ("pekko_cdc_capture_owner_v1","lease_expires_at","BIGINT NULL"),("pekko_cdc_capture_owner_v1","last_heartbeat_at","BIGINT NULL"),
      ("pekko_cdc_capture_owner_v1","control_revision","BIGINT NOT NULL DEFAULT 0"),("pekko_cdc_capture_owner_v1","fence_token_hash","CHAR(64) NULL"),
      ("pekko_cdc_capture_owner_v1","lease_id","VARCHAR(128) NULL"),("pekko_cdc_capture_owner_v1","drain_started_at","BIGINT NULL"),
      ("pekko_cdc_capture_owner_v1","drain_deadline_at","BIGINT NULL"),("pekko_cdc_capture_owner_v1","failure_code","VARCHAR(64) NULL"),
      ("pekko_cdc_capture_owner_v1","last_l_seq","BIGINT NULL"),("pekko_cdc_capture_owner_v1","last_source_epoch","BIGINT NULL"),
      ("pekko_cdc_worker_v1","lease_expires_at","BIGINT NULL"),("pekko_cdc_worker_v1","last_heartbeat_at","BIGINT NULL"),
      ("pekko_cdc_worker_v1","drain_started_at","BIGINT NULL"),("pekko_cdc_worker_v1","drain_deadline_at","BIGINT NULL"),
      ("pekko_cdc_worker_v1","max_active_graphs","INT NULL"),("pekko_cdc_worker_v1","failure_code","VARCHAR(64) NULL"),
      ("pekko_cdc_worker_v1","lease_id","VARCHAR(128) NULL"),("pekko_cdc_worker_v1","control_revision","BIGINT NOT NULL DEFAULT 0"),
      ("pekko_cdc_task_lease_v1","lease_expires_at","BIGINT NULL"),("pekko_cdc_task_lease_v1","renewed_at","BIGINT NULL"),
      ("pekko_cdc_task_lease_v1","handoff_id","VARCHAR(128) NULL"),("pekko_cdc_task_lease_v1","fence_token_hash","CHAR(64) NULL"),
      ("pekko_cdc_task_lease_v1","lease_id","VARCHAR(128) NULL"),("pekko_cdc_task_lease_v1","last_ack_kind","VARCHAR(16) NULL"),
      ("pekko_cdc_task_lease_v1","last_ack_digest","CHAR(64) NULL"),("pekko_cdc_task_lease_v1","unknown_since","BIGINT NULL"),
      ("pekko_cdc_task_lease_v1","release_state","VARCHAR(32) NULL"),("pekko_cdc_task_lease_v1","failure_code","VARCHAR(64) NULL"),
      ("pekko_cdc_task_lease_v1","control_revision","BIGINT NOT NULL DEFAULT 0"),
      ("pekko_cdc_task_handoff_v1","old_release_state","VARCHAR(32) NULL"),("pekko_cdc_task_handoff_v1","proof_digest","CHAR(64) NULL"),
      ("pekko_cdc_task_handoff_v1","new_claimed_at","BIGINT NULL"),("pekko_cdc_task_handoff_v1","failure_code","VARCHAR(64) NULL"))
    columns.foreach { case (table,column,definition) =>
      val metadata=connection.getMetaData.getColumns(connection.getCatalog,null,table,"%")
      var found=false
      try while(metadata.next())if(metadata.getString("TABLE_NAME").equalsIgnoreCase(table) && metadata.getString("COLUMN_NAME").equalsIgnoreCase(column))found=true
      finally metadata.close()
      if(!found){val statement=connection.createStatement();try statement.executeUpdate(s"ALTER TABLE $table ADD COLUMN $column $definition") finally statement.close()}
    }
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

  def currentCapture(captureId: String): Option[CaptureOwnerSnapshot] = withConnection { connection =>
    val statement = connection.prepareStatement(
      "SELECT source_lineage,owner_attempt,owner_epoch,incarnation,state,updated_at,lease_expires_at " +
        "FROM pekko_cdc_capture_owner_v1 WHERE capture_id=?")
    try {
      statement.setString(1, captureId)
      queryOne(statement).map { row =>
        CaptureOwnerSnapshot(
          CaptureLease(captureId, row("source_lineage"), row("owner_attempt"), row("owner_epoch").toLong, row("incarnation")),
          row("state"),
          row("updated_at").toLong,
          Option(row("lease_expires_at")).map(_.toLong))
      }
    } finally statement.close()
  }

  def markCaptureActive(lease: CaptureLease): Boolean = updateCaptureState(lease, "ACQUIRING", "ACTIVE")

  def renewCapture(lease: CaptureLease, ttlMillis: Long = defaultLeaseMillis): Boolean = transaction { connection =>
    require(ttlMillis > 0L, "invalid capture lease ttl")
    val now = System.currentTimeMillis()
    val statement = connection.prepareStatement(
      "UPDATE pekko_cdc_capture_owner_v1 SET last_heartbeat_at=?,lease_expires_at=?,control_revision=control_revision+1,updated_at=? " +
        "WHERE capture_id=? AND owner_attempt=? AND owner_epoch=? AND incarnation=? AND state='ACTIVE' " +
        "AND (lease_expires_at IS NULL OR lease_expires_at>?)")
    try {
      statement.setLong(1, now); statement.setLong(2, now + ttlMillis); statement.setLong(3, now)
      statement.setString(4, lease.captureId); statement.setString(5, lease.ownerAttempt)
      statement.setLong(6, lease.ownerEpoch); statement.setString(7, lease.incarnation); statement.setLong(8, now)
      statement.executeUpdate() == 1
    } finally statement.close()
  }

  def releaseCapture(lease: CaptureLease, state: String = "STOPPED"): Boolean = {
    require(Set("DRAINING", "STOPPED", "UNKNOWN").contains(state), "invalid capture release state")
    transaction { connection =>
      val statement = connection.prepareStatement(
        "UPDATE pekko_cdc_capture_owner_v1 SET state=?,lease_expires_at=NULL,updated_at=? WHERE capture_id=? AND owner_attempt=? AND owner_epoch=? AND incarnation=?")
      try {
        statement.setString(1, state)
        statement.setLong(2, System.currentTimeMillis())
        statement.setString(3, lease.captureId)
        statement.setString(4, lease.ownerAttempt)
        statement.setLong(5, lease.ownerEpoch)
        statement.setString(6, lease.incarnation)
        statement.executeUpdate() == 1
      } finally statement.close()
    }
  }

  private def updateCaptureState(lease: CaptureLease, expected: String, next: String): Boolean =
    transaction { connection =>
      val statement = connection.prepareStatement(
        "UPDATE pekko_cdc_capture_owner_v1 SET state=?,lease_expires_at=NULL,updated_at=? WHERE capture_id=? AND owner_attempt=? AND owner_epoch=? AND state=? AND incarnation=?")
      try {
        statement.setString(1, next)
        statement.setLong(2, System.currentTimeMillis())
        statement.setString(3, lease.captureId)
        statement.setString(4, lease.ownerAttempt)
        statement.setLong(5, lease.ownerEpoch)
        statement.setString(6, expected)
        statement.setString(7, lease.incarnation)
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
      "INSERT INTO pekko_cdc_worker_v1(worker_id,incarnation,worker_epoch,state,capacity_json,active_graphs,heartbeat_at,lease_expires_at,last_heartbeat_at,updated_at) VALUES(?,?,?, ?,?,0,?,?,?,?) " +
        "ON DUPLICATE KEY UPDATE incarnation=VALUES(incarnation),worker_epoch=VALUES(worker_epoch),state='READY',capacity_json=VALUES(capacity_json),heartbeat_at=VALUES(heartbeat_at),lease_expires_at=VALUES(lease_expires_at),last_heartbeat_at=VALUES(last_heartbeat_at),control_revision=control_revision+1,updated_at=VALUES(updated_at)")
    try {
      val now = System.currentTimeMillis()
      statement.setString(1, workerId)
      statement.setString(2, incarnation)
      statement.setLong(3, nextEpoch)
      statement.setString(4, "READY")
      statement.setString(5, capacityJson)
      statement.setLong(6, now)
      statement.setLong(7, now + defaultLeaseMillis)
      statement.setLong(8, now)
      statement.setLong(9, now)
      statement.executeUpdate()
    } finally statement.close()
    val read = connection.prepareStatement("SELECT COUNT(*) AS n FROM pekko_cdc_task_lease_v1 WHERE worker_id=? AND state='ACTIVE'")
    try { read.setString(1, workerId); queryOne(read) } finally read.close()
    WorkerLease(workerId, incarnation, nextEpoch)
  }

  def heartbeat(worker: WorkerLease, activeGraphs: Int): Boolean = heartbeatWorker(worker, activeGraphs, defaultLeaseMillis)

  def heartbeatWorker(worker: WorkerLease, activeGraphs: Int, ttlMillis: Long = defaultLeaseMillis): Boolean = transaction { connection =>
    require(ttlMillis > 0L, "invalid worker lease ttl")
    val statement = connection.prepareStatement(
      "UPDATE pekko_cdc_worker_v1 SET heartbeat_at=?,lease_expires_at=?,last_heartbeat_at=?,active_graphs=?,control_revision=control_revision+1,updated_at=? " +
        "WHERE worker_id=? AND incarnation=? AND worker_epoch=? AND state IN ('READY','ACTIVE','DRAINING') " +
        "AND (lease_expires_at IS NULL OR lease_expires_at>?)")
    try {
      val now = System.currentTimeMillis()
      statement.setLong(1, now); statement.setLong(2, now + ttlMillis); statement.setLong(3, now)
      statement.setInt(4, activeGraphs); statement.setLong(5, now)
      statement.setString(6, worker.workerId); statement.setString(7, worker.incarnation); statement.setLong(8, worker.leaseEpoch); statement.setLong(9, now)
      statement.executeUpdate() == 1
    } finally statement.close()
  }

  private def requireCurrentWorker(connection: Connection, worker: WorkerLease): Unit = {
    val statement = connection.prepareStatement(
      "SELECT incarnation,worker_epoch,state,lease_expires_at FROM pekko_cdc_worker_v1 WHERE worker_id=? FOR UPDATE")
    val current = try {
      statement.setString(1, worker.workerId)
      queryOne(statement)
    } finally statement.close()
    val now = System.currentTimeMillis()
    require(current.exists(row => row("incarnation") == worker.incarnation &&
      row("worker_epoch").toLong == worker.leaseEpoch && Set("READY", "ACTIVE").contains(row("state")) &&
      Option(row("lease_expires_at")).forall(_.toLong > now)),
      "worker lease is stale")
  }

  def claimTasks(worker: WorkerLease, limit: Int): Vector[TaskLease] = transaction { connection =>
    requireCurrentWorker(connection, worker)
    require(limit > 0 && limit <= 128, "invalid task lease page")
    val now = System.currentTimeMillis()
    val tasks = connection.prepareStatement(
      "SELECT task_id,capture_id FROM pekko_cdc_tasks_v1 WHERE desired_state='RUNNING' AND phase NOT IN ('PAUSED','CANCELED') " +
        "AND NOT EXISTS (SELECT 1 FROM pekko_cdc_task_lease_v1 l WHERE l.task_id=pekko_cdc_tasks_v1.task_id " +
        "AND (l.state IN ('DRAINING','UNKNOWN') OR (l.state='ACTIVE' AND (l.lease_expires_at IS NULL OR l.lease_expires_at>?)))) " +
        "ORDER BY task_id LIMIT ? FOR UPDATE")
    try {
      tasks.setLong(1, now)
      tasks.setInt(2, limit)
      val result = tasks.executeQuery()
      try {
        val leases = Vector.newBuilder[TaskLease]
        while (result.next()) {
          val taskId = result.getString("task_id")
          val captureId = result.getString("capture_id")
          claimTaskOn(connection, worker, taskId, captureId).foreach(leases += _)
        }
        leases.result()
      } finally result.close()
    } finally tasks.close()
  }

  def claimTask(worker: WorkerLease, taskId: String, captureId: String): Option[TaskLease] = transaction { connection =>
    claimTaskOn(connection, worker, taskId, captureId)
  }

  /**
    * Claims a task lease on a caller-owned transaction. The caller must commit
    * or roll back the same connection together with its execution admission.
    */
  private[magic] def claimTaskOn(connection: Connection, worker: WorkerLease, taskId: String, captureId: String): Option[TaskLease] = {
    requireCurrentWorker(connection, worker)
    val existing = connection.prepareStatement("SELECT state,lease_expires_at FROM pekko_cdc_task_lease_v1 WHERE task_id=? FOR UPDATE")
    val current = try { existing.setString(1, taskId); queryOne(existing) } finally existing.close()
    val now = System.currentTimeMillis()
    val occupied = current.exists(row => Set("DRAINING", "UNKNOWN")(row("state")) ||
      (row("state") == "ACTIVE" && Option(row("lease_expires_at")).forall(_.toLong > now)))
    if (occupied) None
    else {
      val next = connection.prepareStatement("SELECT COALESCE(MAX(lease_epoch),0)+1 AS next_epoch FROM pekko_cdc_task_lease_v1 WHERE task_id=?")
      val epoch = try { next.setString(1, taskId); queryOne(next).map(_("next_epoch").toLong).getOrElse(1L) } finally next.close()
      val insert = connection.prepareStatement(
        "INSERT INTO pekko_cdc_task_lease_v1(task_id,capture_id,worker_id,worker_incarnation,lease_epoch,state,last_l_seq,last_journal_revision,updated_at,lease_expires_at,renewed_at) VALUES(?,?,?,?,?,'ACTIVE',0,0,?,?,?) " +
          "ON DUPLICATE KEY UPDATE capture_id=VALUES(capture_id),worker_id=VALUES(worker_id),worker_incarnation=VALUES(worker_incarnation),lease_epoch=VALUES(lease_epoch),state='ACTIVE',release_proof=NULL,lease_expires_at=VALUES(lease_expires_at),renewed_at=VALUES(renewed_at),control_revision=control_revision+1,updated_at=VALUES(updated_at)")
      try {
        val now = System.currentTimeMillis()
        insert.setString(1, taskId); insert.setString(2, captureId); insert.setString(3, worker.workerId)
        insert.setString(4, worker.incarnation); insert.setLong(5, epoch); insert.setLong(6, now); insert.setLong(7, now + defaultLeaseMillis); insert.setLong(8, now); insert.executeUpdate()
      } finally insert.close()
      Some(TaskLease(taskId, captureId, worker.workerId, worker.incarnation, epoch, 0L, 0L, None))
    }
  }

  def renewTask(lease: TaskLease): Boolean = renewTask(lease, defaultLeaseMillis)

  def renewTask(lease: TaskLease, ttlMillis: Long): Boolean = transaction { connection =>
    require(ttlMillis > 0L, "invalid task lease ttl")
    val statement = connection.prepareStatement(
      "UPDATE pekko_cdc_task_lease_v1 SET renewed_at=?,lease_expires_at=?,control_revision=control_revision+1,updated_at=? " +
        "WHERE task_id=? AND worker_id=? AND worker_incarnation=? AND lease_epoch=? AND state='ACTIVE' " +
        "AND (lease_expires_at IS NULL OR lease_expires_at>?)")
    try {
      val now = System.currentTimeMillis()
      statement.setLong(1, now); statement.setLong(2, now + ttlMillis); statement.setLong(3, now)
      statement.setString(4, lease.taskId); statement.setString(5, lease.workerId); statement.setString(6, lease.workerIncarnation); statement.setLong(7, lease.leaseEpoch); statement.setLong(8, now)
      statement.executeUpdate() == 1
    } finally statement.close()
  }

  def releaseTask(lease: TaskLease, proof: String, state: String = "RELEASED"): Boolean = transaction { connection =>
    require(Set("RELEASED", "UNKNOWN").contains(state), "invalid task release state")
    val statement = connection.prepareStatement(
      "UPDATE pekko_cdc_task_lease_v1 SET state=?,release_proof=?,lease_expires_at=NULL,control_revision=control_revision+1,updated_at=? WHERE task_id=? AND worker_id=? AND worker_incarnation=? AND lease_epoch=? AND state='ACTIVE'")
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
