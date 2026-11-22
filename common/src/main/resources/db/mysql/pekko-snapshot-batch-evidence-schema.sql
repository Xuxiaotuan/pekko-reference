-- Provision explicitly before enabling mysql.snapshot splitRows. Same InnoDB transaction as data + batch ledger.
CREATE TABLE IF NOT EXISTS pekko_snapshot_batch_evidence (
  batch_id VARCHAR(64) PRIMARY KEY,
  source_sha256 VARCHAR(64) NOT NULL,
  target_sha256 VARCHAR(64) NOT NULL
) ENGINE=InnoDB;
