-- Additive account-lock intent. This is not an isolation or transaction-drain proof.
CREATE TABLE IF NOT EXISTS pekko_legacy_account_lock_intent (
  deployment_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  host_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  operation_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  server_uuid VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  legacy_user VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  legacy_host VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  protected_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  phase VARCHAR(32) NOT NULL,
  PRIMARY KEY (deployment_id,host_id)
) ENGINE=InnoDB;
