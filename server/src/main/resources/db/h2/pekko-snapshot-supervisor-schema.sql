CREATE TABLE IF NOT EXISTS pekko_snapshot_host_control (
  deployment_id VARCHAR(255) NOT NULL, host_id VARCHAR(128) NOT NULL,
  guardian_boot VARCHAR(128) NOT NULL, public_key CLOB NOT NULL, max_workers INT NOT NULL,
  guardian_pid BIGINT, guardian_start VARCHAR(128), guardian_capability VARCHAR(64),
  controller_boot VARCHAR(128), controller_epoch BIGINT NOT NULL, pause_reason VARCHAR(255),
  PRIMARY KEY(deployment_id,host_id)
);
CREATE TABLE IF NOT EXISTS pekko_snapshot_worker_launches (
  boot_id VARCHAR(128) PRIMARY KEY, deployment_id VARCHAR(255) NOT NULL, host_id VARCHAR(128) NOT NULL,
  guardian_boot VARCHAR(128) NOT NULL, profile_hash VARCHAR(64) NOT NULL, phase VARCHAR(24) NOT NULL,
  process_id BIGINT, start_identity VARCHAR(128), operation_id VARCHAR(64), signed_proof CLOB
);
CREATE INDEX IF NOT EXISTS pekko_worker_launch_host ON pekko_snapshot_worker_launches(deployment_id,host_id,phase,boot_id);
