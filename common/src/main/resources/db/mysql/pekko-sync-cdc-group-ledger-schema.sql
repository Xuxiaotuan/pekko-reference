-- Provision separately before opting into streaming group protocol. Never expire evidence
-- while its source history can be replayed. One active writer, fixed schema, InnoDB only.
CREATE TABLE IF NOT EXISTS pekko_sync_cdc_group_ledger (
  group_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  evidence LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  committed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
