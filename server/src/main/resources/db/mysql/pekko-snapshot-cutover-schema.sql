-- Additive cutover authorization metadata. No credentials or Journal payloads are stored.
CREATE TABLE IF NOT EXISTS pekko_snapshot_cutover_authorizations (
  deployment_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  host_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  operation_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  old_owner_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  old_manifest BIGINT NOT NULL,
  policy_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  isolation_proof_digest VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  account_drain_digest VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  phase VARCHAR(24) NOT NULL,
  recorded_at BIGINT NOT NULL,
  PRIMARY KEY (deployment_id,host_id,operation_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS pekko_snapshot_credential_bindings (
  workflow_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  execution_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  node_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  node_role VARCHAR(24) NOT NULL,
  permit_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  owner_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  operation_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  endpoint VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  database_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  legacy_user VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  runtime_user VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  password_env VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  definition_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  phase VARCHAR(24) NOT NULL,
  recorded_at BIGINT NOT NULL,
  PRIMARY KEY (workflow_id,execution_id,node_id,node_role),
  UNIQUE KEY pekko_snapshot_binding_permit (permit_id,node_id,node_role)
) ENGINE=InnoDB;
