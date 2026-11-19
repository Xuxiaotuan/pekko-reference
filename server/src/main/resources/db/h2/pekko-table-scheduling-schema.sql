-- Explicit control-plane provisioning, never a replacement for T, J or D.
CREATE TABLE IF NOT EXISTS pekko_table_admission_lock (id INT PRIMARY KEY, queue_sequence BIGINT NOT NULL, policy_hash VARCHAR(64));
INSERT INTO pekko_table_admission_lock (id,queue_sequence) SELECT 1,0 WHERE NOT EXISTS (SELECT 1 FROM pekko_table_admission_lock WHERE id=1);
CREATE TABLE IF NOT EXISTS pekko_table_tasks (
  workflow_id VARCHAR(255) PRIMARY KEY,
  revision BIGINT NOT NULL, workflow_json CLOB NOT NULL,
  source_instance VARCHAR(255) NOT NULL,
  target_instance VARCHAR(255) NOT NULL,
  parallelism INT NOT NULL, page_bytes INT NOT NULL,
  phase VARCHAR(24) NOT NULL, execution_id VARCHAR(64), request_key VARCHAR(255),
  queue_order BIGINT NOT NULL, not_before BIGINT NOT NULL,
  grant_id VARCHAR(64), owner_id VARCHAR(128)
);

CREATE INDEX IF NOT EXISTS pekko_table_ready ON pekko_table_tasks (phase,queue_order,workflow_id);
CREATE INDEX IF NOT EXISTS pekko_table_due ON pekko_table_tasks (phase,not_before,workflow_id);
CREATE INDEX IF NOT EXISTS pekko_table_owner ON pekko_table_tasks (owner_id,phase);
CREATE TABLE IF NOT EXISTS pekko_table_requests (
  request_id VARCHAR(64) PRIMARY KEY, workflow_id VARCHAR(255) NOT NULL, execution_id VARCHAR(64) NOT NULL
);
