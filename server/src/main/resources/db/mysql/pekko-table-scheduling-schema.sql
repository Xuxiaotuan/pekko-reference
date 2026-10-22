-- Explicit control-plane provisioning, never a replacement for T, J or D.
CREATE TABLE IF NOT EXISTS pekko_table_admission_lock (id INT PRIMARY KEY, queue_sequence BIGINT NOT NULL, policy_hash VARCHAR(64)) ENGINE=InnoDB;
INSERT INTO pekko_table_admission_lock (id,queue_sequence) SELECT 1,0 WHERE NOT EXISTS (SELECT 1 FROM pekko_table_admission_lock WHERE id=1);
CREATE TABLE IF NOT EXISTS pekko_table_tasks (
  workflow_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY,
  revision BIGINT NOT NULL, workflow_json MEDIUMTEXT NOT NULL,
  source_instance VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  target_instance VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  parallelism INT NOT NULL, page_bytes INT NOT NULL,
  phase VARCHAR(24) NOT NULL, execution_id VARCHAR(64), request_key VARCHAR(255),
  queue_order BIGINT NOT NULL, not_before BIGINT NOT NULL,
  grant_id VARCHAR(64), owner_id VARCHAR(128),
  INDEX pekko_table_ready (phase,queue_order,workflow_id),
  INDEX pekko_table_due (phase,not_before,workflow_id),
  INDEX pekko_table_owner (owner_id,phase)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS pekko_table_requests (
  request_id VARCHAR(64) PRIMARY KEY,
  workflow_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  execution_id VARCHAR(64) NOT NULL
) ENGINE=InnoDB;
