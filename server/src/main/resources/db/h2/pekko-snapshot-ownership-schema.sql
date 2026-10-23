-- Additive control metadata. Existing business Journal/Snapshot and batch identities are unchanged.
CREATE TABLE IF NOT EXISTS pekko_snapshot_owners (
  owner_id VARCHAR(128) PRIMARY KEY, deployment_id VARCHAR(255) NOT NULL, process_id BIGINT NOT NULL,
  start_identity VARCHAR(128) NOT NULL, phase VARCHAR(24) NOT NULL, operation_id VARCHAR(64),
  manifest_version BIGINT NOT NULL, sealed_version BIGINT, isolation_proof TEXT,
  cluster_address VARCHAR(255), updated_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS pekko_snapshot_owner_instances (
  owner_id VARCHAR(128) NOT NULL, instance_id VARCHAR(128) NOT NULL, server_uuid VARCHAR(128) NOT NULL,
  server_started BIGINT NOT NULL, database_users TEXT NOT NULL, empty_since BIGINT, empty_rounds INT NOT NULL,
  complete BOOLEAN NOT NULL, observation TEXT,
  PRIMARY KEY(owner_id,instance_id)
);
CREATE TABLE IF NOT EXISTS pekko_snapshot_owner_sessions (
  owner_id VARCHAR(128) NOT NULL, instance_id VARCHAR(128) NOT NULL, connection_id BIGINT NOT NULL,
  role_name VARCHAR(24) NOT NULL, registered_at BIGINT NOT NULL,
  PRIMARY KEY(owner_id,instance_id,connection_id)
);
CREATE TABLE IF NOT EXISTS pekko_snapshot_writer_permissions (
  workflow_id VARCHAR(255) PRIMARY KEY, owner_id VARCHAR(128) NOT NULL, generation BIGINT NOT NULL,
  permit_id VARCHAR(64) NOT NULL, grant_id VARCHAR(64) NOT NULL, execution_id VARCHAR(64) NOT NULL,
  revision BIGINT NOT NULL, definition_hash VARCHAR(64) NOT NULL, phase VARCHAR(24) NOT NULL,
  predecessor_owner VARCHAR(128)
);
CREATE TABLE IF NOT EXISTS pekko_snapshot_ownership_audit (
  event_id VARCHAR(64) PRIMARY KEY, owner_id VARCHAR(128) NOT NULL, workflow_id VARCHAR(255),
  event_type VARCHAR(40) NOT NULL, details TEXT NOT NULL, recorded_at BIGINT NOT NULL, event_sequence BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS pekko_snapshot_instance_id ON pekko_snapshot_owner_instances(instance_id);

CREATE TABLE IF NOT EXISTS pekko_snapshot_schedule_triggers (
  workflow_id VARCHAR(255) NOT NULL, schedule_id VARCHAR(255) NOT NULL, scheduled_at BIGINT NOT NULL,
  trigger_id VARCHAR(255) NOT NULL, execution_id VARCHAR(64) NOT NULL, disposition VARCHAR(24) NOT NULL,
  recorded_at BIGINT NOT NULL, PRIMARY KEY(workflow_id,trigger_id)
);
CREATE INDEX IF NOT EXISTS pekko_snapshot_schedule_watermark ON pekko_snapshot_schedule_triggers(workflow_id,schedule_id,scheduled_at);
CREATE INDEX IF NOT EXISTS pekko_snapshot_schedule_execution ON pekko_snapshot_schedule_triggers(execution_id,disposition);
