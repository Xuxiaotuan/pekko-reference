CREATE TABLE IF NOT EXISTS pekko_sync_batch_ledger (
  batch_id VARCHAR(64) PRIMARY KEY,
  workflow_id VARCHAR(255) NOT NULL,
  execution_id VARCHAR(255) NOT NULL,
  source_node_id VARCHAR(255) NOT NULL,
  partition_id VARCHAR(128) NOT NULL,
  batch_sequence BIGINT NOT NULL,
  cursor_kind VARCHAR(64) NOT NULL DEFAULT 'mysql.numeric-pk',
  cursor_value LONGTEXT NOT NULL,
  upper_bound LONGTEXT NOT NULL,
  source_rows BIGINT NOT NULL,
  target_rows BIGINT NOT NULL,
  committed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_execution_partition_sequence
    (execution_id, source_node_id, partition_id, batch_sequence)
);

SET @pekko_cursor_kind_exists = (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'pekko_sync_batch_ledger'
    AND column_name = 'cursor_kind'
);
SET @pekko_add_cursor_kind_sql = IF(
  @pekko_cursor_kind_exists = 0,
  'ALTER TABLE pekko_sync_batch_ledger ADD COLUMN cursor_kind VARCHAR(64) NULL DEFAULT ''mysql.numeric-pk'' AFTER batch_sequence',
  'SELECT 1'
);
PREPARE pekko_add_cursor_kind FROM @pekko_add_cursor_kind_sql;
EXECUTE pekko_add_cursor_kind;
DEALLOCATE PREPARE pekko_add_cursor_kind;

UPDATE pekko_sync_batch_ledger
SET cursor_kind = 'mysql.numeric-pk'
WHERE cursor_kind IS NULL OR cursor_kind = '';

ALTER TABLE pekko_sync_batch_ledger
  MODIFY COLUMN cursor_kind VARCHAR(64) NOT NULL DEFAULT 'mysql.numeric-pk',
  MODIFY COLUMN cursor_value LONGTEXT NOT NULL,
  MODIFY COLUMN upper_bound LONGTEXT NOT NULL;
