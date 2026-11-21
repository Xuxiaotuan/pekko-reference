package cn.xuyinyin.magic.cdc.runtime

import java.sql.{Connection, PreparedStatement}
import java.util.UUID

/** Records the schema contract actually applied by each runtime initializer. */
object CdcRuntimeSchemaVersion {
  // Versions 2-4 are already occupied by the historical shared schema table.
  val leaseVersion = 7
  val taskVersion = 4
  val leaseChecksum = "cdc-runtime-lease-v5-two-role-lease-id-progress-id"
  val taskChecksum = "cdc-task-store-v4-capture-readiness-projection"

  def record(connection: Connection, version: Int, checksum: String): Unit = {
    val select = connection.prepareStatement("SELECT checksum FROM pekko_cdc_runtime_schema_version WHERE version=? FOR UPDATE")
    try {
      select.setInt(1, version)
      val rows = select.executeQuery()
      try {
        if (rows.next()) require(rows.getString(1) == checksum, s"runtime schema checksum conflict for version $version")
        else {
          val insert = connection.prepareStatement("INSERT INTO pekko_cdc_runtime_schema_version(version,checksum,applied_at) VALUES(?,?,?)")
          try {
            insert.setInt(1, version)
            insert.setString(2, checksum)
            insert.setLong(3, System.currentTimeMillis())
            insert.executeUpdate()
          } finally insert.close()
        }
      } finally rows.close()
    } finally select.close()
  }
}

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
      "CREATE TABLE IF NOT EXISTS pekko_cdc_capture_subscription_v1(task_id CHAR(64) PRIMARY KEY,capture_id VARCHAR(64) NOT NULL,request_id VARCHAR(128) NOT NULL,table_name VARCHAR(128) NOT NULL,generation VARCHAR(128) NOT NULL,desired_state VARCHAR(32) NOT NULL,state VARCHAR(32) NOT NULL,control_revision BIGINT NOT NULL,capture_readiness VARCHAR(32) NOT NULL,initialization_evidence LONGTEXT NULL,b_offset LONGTEXT NULL,high_offset LONGTEXT NULL,f_offset LONGTEXT NULL,k_offset LONGTEXT NULL,owner_attempt VARCHAR(128) NULL,capture_owner_epoch BIGINT NULL,capture_lease_id VARCHAR(128) NULL,readiness_digest CHAR(64) NULL,updated_at BIGINT NOT NULL,UNIQUE(capture_id,table_name,generation),INDEX subscription_reconcile(capture_id,desired_state,state))",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_capture_owner_v1(capture_id VARCHAR(64) PRIMARY KEY,source_lineage VARCHAR(255) NOT NULL,owner_attempt VARCHAR(128) NOT NULL,owner_epoch BIGINT NOT NULL,incarnation VARCHAR(128) NOT NULL,state VARCHAR(32) NOT NULL,reader_session VARCHAR(128) NULL,source_offset_digest CHAR(64) NULL,release_proof_id VARCHAR(128) NULL,lease_expires_at BIGINT NULL,last_heartbeat_at BIGINT NULL,control_revision BIGINT NOT NULL DEFAULT 0,fence_token_hash CHAR(64) NULL,lease_id VARCHAR(128) NULL,drain_started_at BIGINT NULL,drain_deadline_at BIGINT NULL,failure_code VARCHAR(64) NULL,last_l_seq BIGINT NULL,last_source_epoch BIGINT NULL,updated_at BIGINT NOT NULL,UNIQUE(capture_id,owner_attempt))",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_worker_v1(worker_id VARCHAR(128) PRIMARY KEY,incarnation VARCHAR(128) NOT NULL,worker_epoch BIGINT NOT NULL,state VARCHAR(32) NOT NULL,capacity_json LONGTEXT NOT NULL,active_graphs INT NOT NULL,heartbeat_at BIGINT NOT NULL,lease_expires_at BIGINT NULL,last_heartbeat_at BIGINT NULL,drain_started_at BIGINT NULL,drain_deadline_at BIGINT NULL,max_active_graphs INT NULL,failure_code VARCHAR(64) NULL,lease_id VARCHAR(128) NULL,control_revision BIGINT NOT NULL DEFAULT 0,updated_at BIGINT NOT NULL,UNIQUE(worker_id,incarnation))",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_task_lease_v1(task_id CHAR(64) PRIMARY KEY,capture_id VARCHAR(64) NOT NULL,worker_id VARCHAR(128) NULL,worker_incarnation VARCHAR(128) NULL,worker_lease_id VARCHAR(128) NULL,lease_epoch BIGINT NOT NULL,state VARCHAR(32) NOT NULL,last_l_seq BIGINT NOT NULL,last_journal_revision BIGINT NOT NULL,last_target_batch VARCHAR(128) NULL,release_proof LONGTEXT NULL,lease_expires_at BIGINT NULL,renewed_at BIGINT NULL,handoff_id VARCHAR(128) NULL,fence_token_hash CHAR(64) NULL,lease_id VARCHAR(128) NULL,last_ack_kind VARCHAR(16) NULL,last_ack_digest CHAR(64) NULL,last_progress_id CHAR(64) NULL,last_progress_digest CHAR(64) NULL,unknown_since BIGINT NULL,release_state VARCHAR(32) NULL,failure_code VARCHAR(64) NULL,control_revision BIGINT NOT NULL DEFAULT 0,capture_owner_epoch BIGINT NULL,worker_epoch BIGINT NULL,updated_at BIGINT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS pekko_cdc_unknown_recovery_v1(grant_id VARCHAR(64) PRIMARY KEY,task_id CHAR(64) NOT NULL,old_owner_attempt VARCHAR(128) NOT NULL,decision VARCHAR(64) NOT NULL,proof_json LONGTEXT NOT NULL,created_at BIGINT NOT NULL)",
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
      ("pekko_cdc_task_lease_v1","worker_lease_id","VARCHAR(128) NULL"),
      ("pekko_cdc_task_lease_v1","last_progress_id","CHAR(64) NULL"),("pekko_cdc_task_lease_v1","last_progress_digest","CHAR(64) NULL"),
      ("pekko_cdc_task_lease_v1","release_state","VARCHAR(32) NULL"),("pekko_cdc_task_lease_v1","failure_code","VARCHAR(64) NULL"),
      ("pekko_cdc_task_lease_v1","control_revision","BIGINT NOT NULL DEFAULT 0"),
      ("pekko_cdc_task_lease_v1","capture_owner_epoch","BIGINT NULL"),
      ("pekko_cdc_task_lease_v1","worker_epoch","BIGINT NULL"),
      ("pekko_cdc_capture_subscription_v1","capture_owner_epoch","BIGINT NULL"),
      ("pekko_cdc_capture_subscription_v1","capture_lease_id","VARCHAR(128) NULL"),
      ("pekko_cdc_capture_subscription_v1","readiness_digest","CHAR(64) NULL"),
      ("pekko_cdc_task_handoff_v1","old_release_state","VARCHAR(32) NULL"),("pekko_cdc_task_handoff_v1","proof_digest","CHAR(64) NULL"),
      ("pekko_cdc_task_handoff_v1","new_claimed_at","BIGINT NULL"),("pekko_cdc_task_handoff_v1","failure_code","VARCHAR(64) NULL"))
    columns.foreach { case (table,column,definition) =>
      val metadata=connection.getMetaData.getColumns(connection.getCatalog,null,table,"%")
      var found=false
      try while(metadata.next())if(metadata.getString("TABLE_NAME").equalsIgnoreCase(table) && metadata.getString("COLUMN_NAME").equalsIgnoreCase(column))found=true
      finally metadata.close()
      if(!found){val statement=connection.createStatement();try statement.executeUpdate(s"ALTER TABLE $table ADD COLUMN $column $definition") finally statement.close()}
    }
    CdcRuntimeSchemaVersion.record(connection,CdcRuntimeSchemaVersion.leaseVersion,CdcRuntimeSchemaVersion.leaseChecksum)
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

  private def queryRows(statement: PreparedStatement): Vector[Map[String, String]] = {
    val result = statement.executeQuery()
    try {
      val metadata = result.getMetaData
      val rows = Vector.newBuilder[Map[String, String]]
      while (result.next()) rows += (1 to metadata.getColumnCount).map { index =>
        metadata.getColumnLabel(index) -> result.getString(index)
      }.toMap
      rows.result()
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
        val leaseId = UUID.randomUUID().toString
        val epoch = row.map(_("owner_epoch").toLong + 1L).getOrElse(1L)
        val statement = connection.prepareStatement(
          "INSERT INTO pekko_cdc_capture_owner_v1(capture_id,source_lineage,owner_attempt,owner_epoch,incarnation,lease_id,state,updated_at) VALUES(?,?,?,?,?,?,?,?) " +
            "ON DUPLICATE KEY UPDATE source_lineage=VALUES(source_lineage),owner_attempt=VALUES(owner_attempt),owner_epoch=VALUES(owner_epoch),incarnation=VALUES(incarnation),lease_id=VALUES(lease_id),state=VALUES(state),updated_at=VALUES(updated_at)")
        try {
          statement.setString(1, captureId)
          statement.setString(2, sourceLineage)
          statement.setString(3, attempt)
          statement.setLong(4, epoch)
          statement.setString(5, incarnation)
          statement.setString(6, leaseId)
          statement.setString(7, "ACQUIRING")
          statement.setLong(8, System.currentTimeMillis())
          statement.executeUpdate()
        } finally statement.close()
        Some(CaptureLease(captureId, sourceLineage, attempt, epoch, incarnation, Some(leaseId)))
      }
    }

  def currentCapture(captureId: String): Option[CaptureOwnerSnapshot] = withConnection { connection =>
    val statement = connection.prepareStatement(
        "SELECT source_lineage,owner_attempt,owner_epoch,incarnation,lease_id,state,updated_at,lease_expires_at " +
        "FROM pekko_cdc_capture_owner_v1 WHERE capture_id=?")
    try {
      statement.setString(1, captureId)
      queryOne(statement).map { row =>
        CaptureOwnerSnapshot(
          CaptureLease(captureId, row("source_lineage"), row("owner_attempt"), row("owner_epoch").toLong, row("incarnation"), Option(row("lease_id"))),
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
        "AND (lease_expires_at IS NULL OR lease_expires_at>?) AND (lease_id IS NULL OR lease_id=?)")
    try {
      statement.setLong(1, now); statement.setLong(2, now + ttlMillis); statement.setLong(3, now)
      statement.setString(4, lease.captureId); statement.setString(5, lease.ownerAttempt)
      statement.setLong(6, lease.ownerEpoch); statement.setString(7, lease.incarnation); statement.setLong(8, now)
      lease.leaseId match { case Some(value) => statement.setString(9, value); case None => statement.setObject(9, null) }
      statement.executeUpdate() == 1
    } finally statement.close()
  }

  def releaseCapture(lease: CaptureLease, state: String = "STOPPED"): Boolean = {
    require(Set("DRAINING", "STOPPED", "UNKNOWN").contains(state), "invalid capture release state")
    transaction { connection =>
      val statement = connection.prepareStatement(
        "UPDATE pekko_cdc_capture_owner_v1 SET state=?,lease_expires_at=NULL,updated_at=? WHERE capture_id=? AND owner_attempt=? AND owner_epoch=? AND incarnation=? AND (lease_id IS NULL OR lease_id=?)")
      try {
        statement.setString(1, state)
        statement.setLong(2, System.currentTimeMillis())
        statement.setString(3, lease.captureId)
        statement.setString(4, lease.ownerAttempt)
        statement.setLong(5, lease.ownerEpoch)
        statement.setString(6, lease.incarnation)
        lease.leaseId match { case Some(value) => statement.setString(7, value); case None => statement.setObject(7, null) }
        statement.executeUpdate() == 1
      } finally statement.close()
    }
  }

  private def updateCaptureState(lease: CaptureLease, expected: String, next: String): Boolean =
    transaction { connection =>
      val statement = connection.prepareStatement(
        "UPDATE pekko_cdc_capture_owner_v1 SET state=?,lease_expires_at=NULL,updated_at=? WHERE capture_id=? AND owner_attempt=? AND owner_epoch=? AND state=? AND incarnation=? AND (lease_id IS NULL OR lease_id=?)")
      try {
        statement.setString(1, next)
        statement.setLong(2, System.currentTimeMillis())
        statement.setString(3, lease.captureId)
        statement.setString(4, lease.ownerAttempt)
        statement.setLong(5, lease.ownerEpoch)
        statement.setString(6, expected)
        statement.setString(7, lease.incarnation)
        lease.leaseId match { case Some(value) => statement.setString(8, value); case None => statement.setObject(8, null) }
        statement.executeUpdate() == 1
      } finally statement.close()
    }

  def registerWorker(workerId: String, incarnation: String, capacityJson: String): WorkerLease = transaction { connection =>
    val existing = connection.prepareStatement("SELECT worker_epoch,state,incarnation FROM pekko_cdc_worker_v1 WHERE worker_id=? FOR UPDATE")
    val nextEpoch = try {
      existing.setString(1, workerId)
      queryOne(existing) match {
        case None => 1L
        case Some(row) =>
          val state = row("state")
          require(state == "STOPPED", s"worker identity is fenced: state=$state")
          row("worker_epoch").toLong + 1L
      }
    } finally existing.close()
    val leaseId = UUID.randomUUID().toString
    val statement = connection.prepareStatement(
      "INSERT INTO pekko_cdc_worker_v1(worker_id,incarnation,worker_epoch,state,capacity_json,active_graphs,heartbeat_at,lease_expires_at,last_heartbeat_at,lease_id,updated_at) VALUES(?,?,?, ?,?,0,?,?,?,?,?) " +
        "ON DUPLICATE KEY UPDATE incarnation=VALUES(incarnation),worker_epoch=VALUES(worker_epoch),state='READY',capacity_json=VALUES(capacity_json),heartbeat_at=VALUES(heartbeat_at),lease_expires_at=VALUES(lease_expires_at),last_heartbeat_at=VALUES(last_heartbeat_at),lease_id=VALUES(lease_id),control_revision=control_revision+1,updated_at=VALUES(updated_at)")
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
      statement.setString(9, leaseId)
      statement.setLong(10, now)
      statement.executeUpdate()
    } finally statement.close()
    val read = connection.prepareStatement("SELECT COUNT(*) AS n FROM pekko_cdc_task_lease_v1 WHERE worker_id=? AND state='ACTIVE'")
    try { read.setString(1, workerId); queryOne(read) } finally read.close()
    WorkerLease(workerId, incarnation, nextEpoch, Some(leaseId))
  }

  /**
    * Records the result of a worker shutdown without changing task evidence.
    * UNKNOWN is deliberately retained when the process or its tasks cannot be
    * proven drained. A later registration is allowed only from STOPPED.
    */
  def releaseWorker(worker: WorkerLease, state: String): Boolean = {
    require(Set("STOPPED", "UNKNOWN").contains(state), "invalid worker release state")
    transaction { connection =>
      val statement = connection.prepareStatement(
        "UPDATE pekko_cdc_worker_v1 SET state=?,lease_expires_at=NULL,last_heartbeat_at=?,updated_at=?,control_revision=control_revision+1 " +
          "WHERE worker_id=? AND incarnation=? AND worker_epoch=? AND state IN ('READY','ACTIVE','DRAINING') AND (lease_id IS NULL OR lease_id=?)")
      try {
        val now = System.currentTimeMillis()
        statement.setString(1, state)
        statement.setLong(2, now)
        statement.setLong(3, now)
        statement.setString(4, worker.workerId)
        statement.setString(5, worker.incarnation)
        statement.setLong(6, worker.leaseEpoch)
        worker.leaseId match { case Some(value) => statement.setString(7, value); case None => statement.setObject(7, null) }
        statement.executeUpdate() == 1
      } finally statement.close()
    }
  }

  /**
    * Converges an abandoned legacy platform owner only when its worker lease
    * has expired and no durable work can still be executing. Lease expiry is
    * one input to the proof, never the proof by itself.
    */
  def recoverExpiredOwner(now: Long, workerLeaseGraceMillis: Long = defaultLeaseMillis): Boolean = transaction { connection =>
    require(workerLeaseGraceMillis > 0L, "invalid worker lease grace")
    val owner = connection.prepareStatement(
      "SELECT owner_attempt,status FROM pekko_cdc_owner_v1 WHERE platform_id='default' FOR UPDATE")
    val ownerRow = try queryOne(owner) finally owner.close()
    ownerRow match {
      case Some(row) if Set("ACTIVE", "UNKNOWN")(row("status")) && row.get("owner_attempt").exists(_.nonEmpty) =>
        val attempt = row("owner_attempt")
        val worker = connection.prepareStatement(
          "SELECT state,lease_expires_at FROM pekko_cdc_worker_v1 WHERE worker_id=? FOR UPDATE")
        val workerRow = try { worker.setString(1, attempt); queryOne(worker) } finally worker.close()
        val leaseExpired = workerRow.exists(r =>
          Set("READY", "ACTIVE", "DRAINING", "UNKNOWN")(r("state")) &&
            Option(r("lease_expires_at")).exists(_.toLong <= now - workerLeaseGraceMillis))
        val taskCheck = connection.prepareStatement(
          "SELECT 1 AS n FROM pekko_cdc_task_lease_v1 WHERE state IN ('ACTIVE','DRAINING') AND (lease_expires_at IS NULL OR lease_expires_at>?) LIMIT 1")
        val hasActiveTask = try { taskCheck.setLong(1, now); queryOne(taskCheck).nonEmpty } finally taskCheck.close()
        val grantCheck = connection.prepareStatement(
          "SELECT g.grant_id,g.task_id,g.owner_attempt,g.status,t.phase,t.release_status,t.runtime_health," +
            "l.state AS lease_state,l.lease_expires_at,l.last_target_batch " +
            "FROM pekko_cdc_grants_v1 g JOIN pekko_cdc_tasks_v1 t ON t.task_id=g.task_id " +
            "LEFT JOIN pekko_cdc_task_lease_v1 l ON l.task_id=g.task_id " +
            "WHERE g.status IN ('ACTIVE','UNKNOWN') ORDER BY g.grant_id FOR UPDATE")
        val grants = try queryRows(grantCheck) finally grantCheck.close()
        val activeGrant = grants.exists(_("status") == "ACTIVE")
        val replayableUnknown = grants.nonEmpty && !activeGrant && grants.forall { grant =>
          grant("owner_attempt") == attempt && grant("status") == "UNKNOWN" &&
            grant("phase") == "PAUSED" && grant("release_status") == "UNKNOWN" &&
            grant("runtime_health") == "UNKNOWN" && Option(grant("last_target_batch")).forall(_.isEmpty) &&
            (Option(grant("lease_state")).forall(state => state != "ACTIVE" && state != "DRAINING") ||
              Option(grant("lease_expires_at")).exists(_.toLong <= now))
        }
        val pendingCheck = connection.prepareStatement(
          "SELECT 1 AS n FROM pekko_cdc_b_recovery_v1 WHERE consumed=FALSE LIMIT 1")
        val hasPendingRecovery = try queryOne(pendingCheck).nonEmpty finally pendingCheck.close()
        if (leaseExpired && !hasActiveTask && !hasPendingRecovery && (!activeGrant && (grants.isEmpty || replayableUnknown))) {
          grants.filter(_("status") == "UNKNOWN").foreach { grant =>
            val proof = s"{\"decision\":\"REPLAY_WITHOUT_TARGET_BATCH\",\"grantId\":\"${grant("grant_id")}\",\"taskId\":\"${grant("task_id")}\",\"oldOwner\":\"$attempt\"}"
            val audit = connection.prepareStatement(
              "INSERT INTO pekko_cdc_unknown_recovery_v1(grant_id,task_id,old_owner_attempt,decision,proof_json,created_at) VALUES(?,?,?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE decision=VALUES(decision),proof_json=VALUES(proof_json)")
            try {
              audit.setString(1, grant("grant_id")); audit.setString(2, grant("task_id")); audit.setString(3, attempt)
              audit.setString(4, "REPLAY_WITHOUT_TARGET_BATCH"); audit.setString(5, proof); audit.setLong(6, now); audit.executeUpdate()
            } finally audit.close()
            val releaseGrant = connection.prepareStatement("UPDATE pekko_cdc_grants_v1 SET status='RELEASED',finished_at=? WHERE grant_id=? AND status='UNKNOWN'")
            try { releaseGrant.setLong(1, now); releaseGrant.setString(2, grant("grant_id")); releaseGrant.executeUpdate() } finally releaseGrant.close()
            val resetTask = connection.prepareStatement(
              "UPDATE pekko_cdc_tasks_v1 SET phase='QUEUED',runtime_health='WAITING',release_status='RELEASED',failure_code='UNKNOWN_REPLAY_REQUEUED',grant_id=NULL,grant_owner=NULL,schedulable=TRUE,due_at=?,observed_at=? WHERE task_id=? AND phase='PAUSED' AND release_status IN ('UNKNOWN','RELEASED') AND (failure_code='local_release_unconfirmed' OR failure_code IS NULL)")
            try { resetTask.setLong(1, now); resetTask.setLong(2, now); resetTask.setString(3, grant("task_id")); resetTask.executeUpdate() } finally resetTask.close()
            val releaseLease = connection.prepareStatement(
              "UPDATE pekko_cdc_task_lease_v1 SET state='RELEASED',release_state='RELEASED',release_proof=?,lease_expires_at=NULL,updated_at=?,control_revision=control_revision+1 WHERE task_id=? AND state IN ('UNKNOWN','ACTIVE','DRAINING') AND (lease_expires_at IS NULL OR lease_expires_at<=?)")
            try { releaseLease.setString(1, proof); releaseLease.setLong(2, now); releaseLease.setString(3, grant("task_id")); releaseLease.setLong(4, now); releaseLease.executeUpdate() } finally releaseLease.close()
          }
          val stopWorker = connection.prepareStatement(
            "UPDATE pekko_cdc_worker_v1 SET state='STOPPED',lease_expires_at=NULL,updated_at=?,control_revision=control_revision+1 WHERE worker_id=? AND state<>'STOPPED'")
          try { stopWorker.setLong(1, now); stopWorker.setString(2, attempt); stopWorker.executeUpdate() } finally stopWorker.close()
          val stopOwner = connection.prepareStatement(
            "UPDATE pekko_cdc_owner_v1 SET status='STOPPED',updated_at=? WHERE platform_id='default' AND owner_attempt=? AND status=?")
          try { stopOwner.setLong(1, now); stopOwner.setString(2, attempt); stopOwner.setString(3, row("status")); stopOwner.executeUpdate() == 1 }
          finally stopOwner.close()
        } else false
      case _ => false
    }
  }

  /** Re-applies a durable UNKNOWN replay decision after a later owner has already started. */
  def requeueAuditedUnknownTasks(now: Long): Int = transaction { connection =>
    val update = connection.prepareStatement(
      "UPDATE pekko_cdc_tasks_v1 SET phase='QUEUED',runtime_health='WAITING',release_status='RELEASED',failure_code='UNKNOWN_REPLAY_REQUEUED'," +
        "grant_id=NULL,grant_owner=NULL,schedulable=TRUE,due_at=?,observed_at=? " +
        "WHERE task_id IN (SELECT task_id FROM pekko_cdc_unknown_recovery_v1 WHERE decision='REPLAY_WITHOUT_TARGET_BATCH') " +
        "AND phase='PAUSED' AND release_status='RELEASED' AND (failure_code='local_release_unconfirmed' OR failure_code IS NULL)")
    try { update.setLong(1, now); update.setLong(2, now); update.executeUpdate() }
    finally update.close()
  }

  def heartbeat(worker: WorkerLease, activeGraphs: Int): Boolean = heartbeatWorker(worker, activeGraphs, defaultLeaseMillis)

  def heartbeatWorker(worker: WorkerLease, activeGraphs: Int, ttlMillis: Long = defaultLeaseMillis): Boolean = transaction { connection =>
    require(ttlMillis > 0L, "invalid worker lease ttl")
    val statement = connection.prepareStatement(
      "UPDATE pekko_cdc_worker_v1 SET heartbeat_at=?,lease_expires_at=?,last_heartbeat_at=?,active_graphs=?,control_revision=control_revision+1,updated_at=? " +
        "WHERE worker_id=? AND incarnation=? AND worker_epoch=? AND state IN ('READY','ACTIVE','DRAINING') " +
        "AND (lease_expires_at IS NULL OR lease_expires_at>?) AND (lease_id IS NULL OR lease_id=?)")
    try {
      val now = System.currentTimeMillis()
      statement.setLong(1, now); statement.setLong(2, now + ttlMillis); statement.setLong(3, now)
      statement.setInt(4, activeGraphs); statement.setLong(5, now)
      statement.setString(6, worker.workerId); statement.setString(7, worker.incarnation); statement.setLong(8, worker.leaseEpoch); statement.setLong(9, now)
      worker.leaseId match { case Some(value) => statement.setString(10, value); case None => statement.setObject(10, null) }
      statement.executeUpdate() == 1
    } finally statement.close()
  }

  private def requireCurrentWorker(connection: Connection, worker: WorkerLease): Unit = {
    val statement = connection.prepareStatement(
      "SELECT incarnation,worker_epoch,state,lease_expires_at,lease_id FROM pekko_cdc_worker_v1 WHERE worker_id=? FOR UPDATE")
    val current = try {
      statement.setString(1, worker.workerId)
      queryOne(statement)
    } finally statement.close()
    val now = System.currentTimeMillis()
    require(current.exists(row => row("incarnation") == worker.incarnation &&
      row("worker_epoch").toLong == worker.leaseEpoch && Set("READY", "ACTIVE").contains(row("state")) &&
      (worker.leaseId.isEmpty || Option(row("lease_id")).contains(worker.leaseId.get)) &&
      Option(row("lease_expires_at")).forall(_.toLong > now)),
      "worker lease is stale")
  }

  private def workerEpoch(connection: Connection, worker: WorkerLease): Long = {
    val statement = connection.prepareStatement(
      "SELECT worker_epoch FROM pekko_cdc_worker_v1 WHERE worker_id=? AND incarnation=? FOR UPDATE")
    try {
      statement.setString(1, worker.workerId)
      statement.setString(2, worker.incarnation)
      queryOne(statement).map(_("worker_epoch").toLong).getOrElse(
        throw new IllegalArgumentException("worker lease is stale"))
    } finally statement.close()
  }

  /** A task lease may outlive neither the Capture owner epoch nor its state. */
  private def captureOwnerEpoch(connection: Connection, captureId: String): Option[Long] = {
    val statement = connection.prepareStatement(
      "SELECT owner_epoch,state FROM pekko_cdc_capture_owner_v1 WHERE capture_id=? FOR UPDATE")
    try {
      statement.setString(1, captureId)
      queryOne(statement) match {
        case None => None
        case Some(row) =>
          require(row("state") == "ACTIVE", "capture owner is not active")
          Some(row("owner_epoch").toLong)
      }
    } finally statement.close()
  }

  private def requireLeaseContext(connection: Connection, lease: TaskLease): Unit = {
    lease.workerEpoch.foreach { epoch =>
      val statement = connection.prepareStatement(
        "SELECT worker_epoch,incarnation,state,lease_expires_at,lease_id FROM pekko_cdc_worker_v1 WHERE worker_id=? FOR UPDATE")
      try {
        statement.setString(1, lease.workerId)
        val row = queryOne(statement)
        val now = System.currentTimeMillis()
        require(row.exists(r => r("incarnation") == lease.workerIncarnation && r("worker_epoch").toLong == epoch &&
          Set("READY", "ACTIVE", "DRAINING")(r("state")) &&
          (lease.workerLeaseId.isEmpty || Option(r("lease_id")).contains(lease.workerLeaseId.get)) &&
          Option(r("lease_expires_at")).forall(_.toLong > now)),
          "worker lease context changed")
      } finally statement.close()
    }
    lease.captureOwnerEpoch.foreach { epoch =>
      val statement = connection.prepareStatement(
        "SELECT owner_epoch,state FROM pekko_cdc_capture_owner_v1 WHERE capture_id=? FOR UPDATE")
      try {
        statement.setString(1, lease.captureId)
        val row = queryOne(statement)
        require(row.exists(r => r("owner_epoch").toLong == epoch && r("state") == "ACTIVE"),
          "capture owner epoch changed")
      } finally statement.close()
    }
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
    val captureEpoch = captureOwnerEpoch(connection, captureId)
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
        "INSERT INTO pekko_cdc_task_lease_v1(task_id,capture_id,worker_id,worker_incarnation,worker_lease_id,lease_epoch,state,last_l_seq,last_journal_revision,updated_at,lease_expires_at,renewed_at,capture_owner_epoch,worker_epoch,lease_id) VALUES(?,?,?,?,?,?,'ACTIVE',0,0,?,?,?, ?,?,?) " +
          "ON DUPLICATE KEY UPDATE capture_id=VALUES(capture_id),worker_id=VALUES(worker_id),worker_incarnation=VALUES(worker_incarnation),worker_lease_id=VALUES(worker_lease_id),lease_epoch=VALUES(lease_epoch),state='ACTIVE',release_proof=NULL,lease_expires_at=VALUES(lease_expires_at),renewed_at=VALUES(renewed_at),capture_owner_epoch=VALUES(capture_owner_epoch),worker_epoch=VALUES(worker_epoch),lease_id=VALUES(lease_id),control_revision=control_revision+1,updated_at=VALUES(updated_at)")
      try {
        val now = System.currentTimeMillis()
        val leaseId = UUID.randomUUID().toString
        insert.setString(1, taskId); insert.setString(2, captureId); insert.setString(3, worker.workerId)
        insert.setString(4, worker.incarnation)
        worker.leaseId match { case Some(value) => insert.setString(5, value); case None => insert.setObject(5, null) }
        insert.setLong(6, epoch); insert.setLong(7, now); insert.setLong(8, now + defaultLeaseMillis); insert.setLong(9, now)
        captureEpoch match { case Some(value) => insert.setLong(10, value); case None => insert.setObject(10, null) }
        insert.setLong(11, workerEpoch(connection, worker)); insert.setString(12, leaseId)
        insert.executeUpdate()
        Some(TaskLease(taskId, captureId, worker.workerId, worker.incarnation, epoch, 0L, 0L, None, captureEpoch, Some(worker.leaseEpoch), Some(leaseId), workerLeaseId = worker.leaseId))
      } finally insert.close()
    }
  }

  def renewTask(lease: TaskLease): Boolean = renewTask(lease, defaultLeaseMillis)

  def renewTask(lease: TaskLease, ttlMillis: Long): Boolean = transaction { connection =>
    require(ttlMillis > 0L, "invalid task lease ttl")
    requireLeaseContext(connection, lease)
    val statement = connection.prepareStatement(
      "UPDATE pekko_cdc_task_lease_v1 SET renewed_at=?,lease_expires_at=?,control_revision=control_revision+1,updated_at=? " +
      "WHERE task_id=? AND worker_id=? AND worker_incarnation=? AND lease_epoch=? AND state='ACTIVE' " +
        "AND (lease_expires_at IS NULL OR lease_expires_at>?) AND (lease_id IS NULL OR lease_id=?)")
    try {
      val now = System.currentTimeMillis()
      statement.setLong(1, now); statement.setLong(2, now + ttlMillis); statement.setLong(3, now)
      statement.setString(4, lease.taskId); statement.setString(5, lease.workerId); statement.setString(6, lease.workerIncarnation); statement.setLong(7, lease.leaseEpoch); statement.setLong(8, now)
      lease.leaseId match { case Some(value) => statement.setString(9, value); case None => statement.setObject(9, null) }
      statement.executeUpdate() == 1
    } finally statement.close()
  }

  /** Commits T/J/D progress and extends the lease in one database transaction. */
  def recordProgress(commit: TaskProgressCommit, ttlMillis: Long = defaultLeaseMillis): TaskLease = transaction { connection =>
    require(ttlMillis > 0L, "invalid task lease ttl")
    require(commit.progressId.forall(_ => commit.progressDigest.nonEmpty), "progress id requires a digest")
    requireLeaseContext(connection, commit.lease)
    val currentStatement = connection.prepareStatement(
      "SELECT last_l_seq,last_journal_revision,last_target_batch,last_progress_id,last_progress_digest " +
        "FROM pekko_cdc_task_lease_v1 WHERE task_id=? AND worker_id=? AND worker_incarnation=? AND lease_epoch=? " +
        "AND state='ACTIVE' AND (lease_expires_at IS NULL OR lease_expires_at>?) AND (lease_id IS NULL OR lease_id=?) FOR UPDATE")
    val current = try {
      val now = System.currentTimeMillis()
      currentStatement.setString(1, commit.lease.taskId)
      currentStatement.setString(2, commit.lease.workerId)
      currentStatement.setString(3, commit.lease.workerIncarnation)
      currentStatement.setLong(4, commit.lease.leaseEpoch)
      currentStatement.setLong(5, now)
      commit.lease.leaseId match { case Some(value) => currentStatement.setString(6, value); case None => currentStatement.setObject(6, null) }
      queryOne(currentStatement)
    } finally currentStatement.close()
    require(current.nonEmpty, "task lease is stale while committing progress")
    val storedProgressId = Option(current.get("last_progress_id")).filter(_.nonEmpty)
    val duplicate = commit.progressId.filter(storedProgressId.contains).map { _ =>
      require(Option(current.get("last_progress_digest")).contains(commit.progressDigest.orNull), "progress id reused with a different digest")
      commit.lease.copy(
        lastLSeq = current.get("last_l_seq").toLong,
        lastJournalRevision = current.get("last_journal_revision").toLong,
        lastTargetBatch = Option(current.get("last_target_batch")),
        lastProgressId = storedProgressId,
        lastProgressDigest = Option(current.get("last_progress_digest")).filter(_.nonEmpty))
    }
    if (duplicate.nonEmpty) duplicate.get
    else {
    val now = System.currentTimeMillis()
    val statement = connection.prepareStatement(
      "UPDATE pekko_cdc_task_lease_v1 SET last_l_seq=CASE WHEN last_l_seq<? THEN ? ELSE last_l_seq END," +
        "last_journal_revision=CASE WHEN last_journal_revision<? THEN ? ELSE last_journal_revision END," +
        "last_target_batch=COALESCE(?,last_target_batch),last_ack_kind=COALESCE(?,last_ack_kind)," +
        "last_ack_digest=COALESCE(?,last_ack_digest),last_progress_id=COALESCE(?,last_progress_id)," +
        "last_progress_digest=COALESCE(?,last_progress_digest),renewed_at=?,lease_expires_at=?," +
        "control_revision=control_revision+1,updated_at=? WHERE task_id=? AND worker_id=? AND " +
        "worker_incarnation=? AND lease_epoch=? AND state='ACTIVE' AND (lease_expires_at IS NULL OR lease_expires_at>?) AND (lease_id IS NULL OR lease_id=?)")
    try {
      val p=commit.progress
      statement.setLong(1,p.lastLSeq);statement.setLong(2,p.lastLSeq)
      statement.setLong(3,p.lastJournalRevision);statement.setLong(4,p.lastJournalRevision)
      p.lastTargetBatch match {case Some(value)=>statement.setString(5,value);case None=>statement.setObject(5,null)}
      commit.ackKind match {case Some(value)=>statement.setString(6,value);case None=>statement.setObject(6,null)}
      commit.ackDigest match {case Some(value)=>statement.setString(7,value);case None=>statement.setObject(7,null)}
      commit.progressId match {case Some(value)=>statement.setString(8,value);case None=>statement.setObject(8,null)}
      commit.progressDigest match {case Some(value)=>statement.setString(9,value);case None=>statement.setObject(9,null)}
      statement.setLong(10,now);statement.setLong(11,now+ttlMillis);statement.setLong(12,now)
      statement.setString(13,commit.lease.taskId);statement.setString(14,commit.lease.workerId)
      statement.setString(15,commit.lease.workerIncarnation);statement.setLong(16,commit.lease.leaseEpoch);statement.setLong(17,now)
      commit.lease.leaseId match { case Some(value) => statement.setString(18, value); case None => statement.setObject(18, null) }
      require(statement.executeUpdate()==1,"task lease is stale while committing progress")
      commit.lease.copy(lastLSeq=math.max(commit.lease.lastLSeq,p.lastLSeq),
        lastJournalRevision=math.max(commit.lease.lastJournalRevision,p.lastJournalRevision),
        lastTargetBatch=p.lastTargetBatch.orElse(commit.lease.lastTargetBatch),
        lastProgressId=commit.progressId.orElse(commit.lease.lastProgressId),
        lastProgressDigest=commit.progressDigest.orElse(commit.lease.lastProgressDigest))
    } finally statement.close()
    }
  }

  def releaseTask(lease: TaskLease, proof: String, state: String = "RELEASED"): Boolean = transaction { connection =>
    require(Set("RELEASED", "UNKNOWN").contains(state), "invalid task release state")
    requireLeaseContext(connection, lease)
    val statement = connection.prepareStatement(
      "UPDATE pekko_cdc_task_lease_v1 SET state=?,release_proof=?,lease_expires_at=NULL,control_revision=control_revision+1,updated_at=? WHERE task_id=? AND worker_id=? AND worker_incarnation=? AND lease_epoch=? AND state='ACTIVE' AND (lease_id IS NULL OR lease_id=?)")
    try {
      statement.setString(1, state); statement.setString(2, proof); statement.setLong(3, System.currentTimeMillis())
      statement.setString(4, lease.taskId); statement.setString(5, lease.workerId); statement.setString(6, lease.workerIncarnation); statement.setLong(7, lease.leaseEpoch)
      lease.leaseId match { case Some(value) => statement.setString(8, value); case None => statement.setObject(8, null) }
      statement.executeUpdate() == 1
    } finally statement.close()
  }

  def beginHandoff(lease: TaskLease): Option[String] = transaction { connection =>
    requireLeaseContext(connection, lease)
    val handoffId = UUID.randomUUID().toString
    val update = connection.prepareStatement(
      "UPDATE pekko_cdc_task_lease_v1 SET state='DRAINING',updated_at=? WHERE task_id=? AND worker_id=? AND worker_incarnation=? AND lease_epoch=? AND state='ACTIVE' AND (lease_id IS NULL OR lease_id=?)")
    val changed = try {
      update.setLong(1, System.currentTimeMillis()); update.setString(2, lease.taskId)
      update.setString(3, lease.workerId); update.setString(4, lease.workerIncarnation); update.setLong(5, lease.leaseEpoch)
      lease.leaseId match { case Some(value) => update.setString(6, value); case None => update.setObject(6, null) }
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
