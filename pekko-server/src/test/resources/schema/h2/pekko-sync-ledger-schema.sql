CREATE TABLE IF NOT EXISTS pekko_sync_batch_ledger (
  batch_id VARCHAR(64) PRIMARY KEY,
  workflow_id VARCHAR(255) NOT NULL,
  execution_id VARCHAR(255) NOT NULL,
  source_node_id VARCHAR(255) NOT NULL,
  partition_id VARCHAR(128) NOT NULL,
  batch_sequence BIGINT NOT NULL,
  cursor_value VARCHAR(128) NOT NULL,
  upper_bound VARCHAR(128) NOT NULL,
  source_rows BIGINT NOT NULL,
  target_rows BIGINT NOT NULL,
  committed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_execution_partition_sequence UNIQUE
    (execution_id, source_node_id, partition_id, batch_sequence)
);
