CREATE TABLE IF NOT EXISTS pekko_cdc_capture_owner_v1 (
  capture_id VARCHAR(64) PRIMARY KEY,
  source_lineage VARCHAR(255) NOT NULL,
  owner_attempt VARCHAR(128) NOT NULL,
  owner_epoch BIGINT NOT NULL,
  incarnation VARCHAR(128) NOT NULL,
  state VARCHAR(32) NOT NULL,
  reader_session VARCHAR(128) NULL,
  source_offset_digest CHAR(64) NULL,
  release_proof_id VARCHAR(128) NULL,
  updated_at BIGINT NOT NULL,
  UNIQUE KEY capture_owner_attempt (capture_id, owner_attempt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS pekko_cdc_worker_v1 (
  worker_id VARCHAR(128) PRIMARY KEY,
  incarnation VARCHAR(128) NOT NULL,
  worker_epoch BIGINT NOT NULL,
  state VARCHAR(32) NOT NULL,
  capacity_json LONGTEXT NOT NULL,
  active_graphs INT NOT NULL,
  heartbeat_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  UNIQUE KEY worker_instance (worker_id, incarnation)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS pekko_cdc_task_lease_v1 (
  task_id CHAR(64) PRIMARY KEY,
  capture_id VARCHAR(64) NOT NULL,
  worker_id VARCHAR(128) NULL,
  worker_incarnation VARCHAR(128) NULL,
  lease_epoch BIGINT NOT NULL,
  state VARCHAR(32) NOT NULL,
  last_l_seq BIGINT NOT NULL,
  last_journal_revision BIGINT NOT NULL,
  last_target_batch VARCHAR(128) NULL,
  release_proof LONGTEXT NULL,
  updated_at BIGINT NOT NULL,
  INDEX lease_worker (worker_id, state),
  INDEX lease_capture (capture_id, state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS pekko_cdc_task_handoff_v1 (
  handoff_id VARCHAR(128) PRIMARY KEY,
  task_id CHAR(64) NOT NULL,
  old_worker_id VARCHAR(128) NOT NULL,
  old_incarnation VARCHAR(128) NOT NULL,
  old_lease_epoch BIGINT NOT NULL,
  new_worker_id VARCHAR(128) NULL,
  new_incarnation VARCHAR(128) NULL,
  new_lease_epoch BIGINT NULL,
  state VARCHAR(32) NOT NULL,
  t_digest CHAR(64) NULL,
  journal_revision BIGINT NULL,
  d_seq BIGINT NULL,
  release_proof LONGTEXT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  INDEX handoff_task (task_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
