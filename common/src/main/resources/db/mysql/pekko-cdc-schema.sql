CREATE TABLE IF NOT EXISTS debezium_offset_storage (
  id VARCHAR(36) NOT NULL,
  offset_key VARCHAR(1255),
  offset_val VARCHAR(1255),
  record_insert_ts TIMESTAMP(6) NOT NULL,
  record_insert_seq INT NOT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS debezium_database_history (
  id VARCHAR(36) NOT NULL,
  history_data LONGTEXT,
  history_data_seq INT,
  record_insert_ts TIMESTAMP(6) NOT NULL,
  record_insert_seq INT NOT NULL,
  PRIMARY KEY (id, history_data_seq)
);

CREATE TABLE IF NOT EXISTS pekko_cdc_source_acceptance (
  id BIGINT NOT NULL,
  run_id VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  note VARCHAR(255) NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS pekko_cdc_target_acceptance LIKE pekko_cdc_source_acceptance;
