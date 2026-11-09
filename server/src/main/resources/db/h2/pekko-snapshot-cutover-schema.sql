CREATE TABLE IF NOT EXISTS pekko_snapshot_cutover_authorizations (
  deployment_id VARCHAR(255) NOT NULL, host_id VARCHAR(128) NOT NULL, operation_id VARCHAR(128) NOT NULL,
  old_owner_id VARCHAR(128) NOT NULL, old_manifest BIGINT NOT NULL, policy_hash VARCHAR(64) NOT NULL,
  isolation_proof_digest VARCHAR(64) NOT NULL, account_drain_digest VARCHAR(64) NOT NULL,
  phase VARCHAR(24) NOT NULL, recorded_at BIGINT NOT NULL,
  PRIMARY KEY (deployment_id,host_id,operation_id)
);
CREATE TABLE IF NOT EXISTS pekko_snapshot_credential_bindings (
  workflow_id VARCHAR(255) NOT NULL, execution_id VARCHAR(64) NOT NULL, node_id VARCHAR(128) NOT NULL,
  node_role VARCHAR(24) NOT NULL, permit_id VARCHAR(64) NOT NULL, owner_id VARCHAR(128) NOT NULL,
  operation_id VARCHAR(128) NOT NULL, endpoint VARCHAR(255) NOT NULL, database_name VARCHAR(255) NOT NULL,
  legacy_user VARCHAR(128) NOT NULL, runtime_user VARCHAR(128) NOT NULL, password_env VARCHAR(128) NOT NULL,
  definition_hash VARCHAR(64) NOT NULL, phase VARCHAR(24) NOT NULL, recorded_at BIGINT NOT NULL,
  PRIMARY KEY (workflow_id,execution_id,node_id,node_role), UNIQUE (permit_id,node_id,node_role)
);
