-- Additive, opt-in control metadata. No Journal/Snapshot payload migration.
CREATE TABLE IF NOT EXISTS pekko_snapshot_host_control (
  deployment_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  host_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  guardian_boot VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  public_key TEXT NOT NULL, max_workers INT NOT NULL,
  guardian_pid BIGINT, guardian_start VARCHAR(128), guardian_capability VARCHAR(64),
  controller_boot VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin,
  controller_epoch BIGINT NOT NULL, pause_reason VARCHAR(255),
  PRIMARY KEY(deployment_id,host_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS pekko_snapshot_worker_launches (
  boot_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY,
  deployment_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  host_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  guardian_boot VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  profile_hash VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  phase VARCHAR(24) NOT NULL, process_id BIGINT, start_identity VARCHAR(128),
  operation_id VARCHAR(64), signed_proof TEXT,
  INDEX pekko_worker_launch_host(deployment_id,host_id,phase,boot_id)
) ENGINE=InnoDB;
