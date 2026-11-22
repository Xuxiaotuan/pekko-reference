CREATE TABLE IF NOT EXISTS pekko_legacy_account_lock_intent (
  deployment_id VARCHAR(255) NOT NULL,
  host_id VARCHAR(128) NOT NULL,
  operation_id VARCHAR(128) NOT NULL,
  server_uuid VARCHAR(128) NOT NULL,
  legacy_user VARCHAR(32) NOT NULL,
  legacy_host VARCHAR(255) NOT NULL,
  protected_digest CHAR(64) NOT NULL,
  phase VARCHAR(32) NOT NULL,
  PRIMARY KEY (deployment_id,host_id)
);
