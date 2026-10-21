CREATE TABLE IF NOT EXISTS pekko_sync_batch_ledger (
  batch_id VARCHAR(64) PRIMARY KEY,
  workflow_id VARCHAR(255) NOT NULL,
  execution_id VARCHAR(255) NOT NULL,
  source_node_id VARCHAR(255) NOT NULL,
  partition_id VARCHAR(128) NOT NULL,
  batch_sequence BIGINT NOT NULL,
  cursor_kind VARCHAR(64) NOT NULL DEFAULT 'mysql.numeric-pk',
  cursor_value CHARACTER LARGE OBJECT NOT NULL,
  upper_bound CHARACTER LARGE OBJECT NOT NULL,
  source_rows BIGINT NOT NULL,
  target_rows BIGINT NOT NULL,
  committed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_execution_partition_sequence UNIQUE
    (execution_id, source_node_id, partition_id, batch_sequence)
);

ALTER TABLE pekko_sync_batch_ledger
  ADD COLUMN IF NOT EXISTS cursor_kind VARCHAR(64) DEFAULT 'mysql.numeric-pk';

UPDATE pekko_sync_batch_ledger
SET cursor_kind = 'mysql.numeric-pk'
WHERE cursor_kind IS NULL OR cursor_kind = '';

ALTER TABLE pekko_sync_batch_ledger
  ALTER COLUMN cursor_kind SET NOT NULL;
ALTER TABLE pekko_sync_batch_ledger
  ALTER COLUMN cursor_value SET DATA TYPE CHARACTER LARGE OBJECT;
ALTER TABLE pekko_sync_batch_ledger
  ALTER COLUMN upper_bound SET DATA TYPE CHARACTER LARGE OBJECT;
