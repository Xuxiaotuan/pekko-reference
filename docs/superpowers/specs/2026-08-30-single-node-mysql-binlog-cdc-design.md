# Single-Node MySQL Snapshot and Binlog CDC Design

**Date:** 2026-08-30

**Status:** Approved design; implementation not started

**Repository:** `/Users/xujiawei/magic/scala-workbench/pekko-reference`

## 1. Outcome

Deliver one real, long-running MySQL-to-MySQL mirror workflow that runs one
CDC reader in one Pekko process:

1. take a consistent initial snapshot of one source table;
2. transition to the exact MySQL binlog position associated with that
   snapshot without an application-managed gap;
3. apply snapshot reads and subsequent inserts, updates, and deletes to one
   target table;
4. commit target mutations and the Pekko batch ledger in one MySQL
   transaction;
5. acknowledge a Debezium batch only after the target transaction and the
   event-sourced workflow checkpoint are durable; and
6. resume after a JVM or pod restart from durable MySQL offset and schema
   history state.

The result is an at-least-once, final-state-idempotent single-node CDC MVP. It
does not claim cross-system exactly-once delivery or multi-node CDC failover.

## 2. Evidence and Constraints

The following facts were verified read-only on 2026-08-30:

| Area | Verified fact | Design consequence |
|---|---|---|
| MySQL runtime | MySQL 8.4.5 | Use a connector version that supports MySQL 8.4. |
| Binary logging | `log_bin=ON`, `binlog_format=ROW`, `binlog_row_image=FULL` | Row-level before/after data is available. |
| Source position | `gtid_mode=OFF`; current status exposes binlog file and position | Use file/position for this single-node MVP. Do not enable GTID in this change. |
| Retention | `binlog_expire_logs_seconds=2592000` | A stopped connector has a 30-day recovery window before a new snapshot may be required. |
| Existing account | `pekko_workflow` lacks replication privileges | Create a separate least-privilege CDC account. |
| Existing CDC code | `stream/cdc` generates random simulated events | Do not extend or present the simulator as real CDC. Add a workflow node backed by a real connector. |
| Existing workflow source contract | `CheckpointedNodeSource` emits finite `SourceBatch` values and has no post-commit acknowledgement hook | Add a backward-compatible acknowledgement hook and allow an unbounded source. |
| Existing sink | `mysql.write` supports insert/upsert/replace, but not CDC deletes | Add an explicit CDC apply sink rather than changing legacy row semantics. |
| Existing runtime | Docker uses Java 11 | Upgrade build/runtime baseline to Java 17 for the current stable Debezium connector. |

Debezium 3.6.1.Final is the selected connector line. The official release
matrix lists Java 17+ for current connectors and MySQL 8.4 support:

- <https://debezium.io/releases/>
- <https://debezium.io/documentation/reference/3.6/development/engine.html>
- <https://debezium.io/documentation/reference/stable/connectors/mysql.html>

## 3. Scope

### 3.1 Included

- New source node type `mysql.cdc`.
- New sink node type `mysql.cdc.apply`.
- Exactly one source table and one target table per workflow.
- Initial consistent snapshot followed by continuous binlog streaming.
- Debezium operations `r`, `c`, `u`, and `d` mapped to target changes.
- One ordered Debezium consumer and one in-flight source batch.
- Backpressure from the target commit to the Debezium callback.
- Durable Debezium offset storage and internal schema history in MySQL.
- Existing event-sourced execution checkpoint retained for workflow recovery
  and observability.
- Target mutation and batch-ledger claim committed atomically.
- Source and target metadata validation before streaming begins.
- Dedicated MySQL CDC account and Kubernetes Secret wiring.
- Java 17 build and Docker runtime.
- Unit, focused integration, real MySQL, and single-process restart tests.
- A separate one-replica acceptance deployment or equivalent isolated
  single-process run. The existing two-pod Pekko StatefulSet is not scaled
  down or used to claim CDC failover.

### 3.2 Excluded

- CDC leader election, leases, fencing, sharding ownership changes, or
  multi-node takeover.
- Kafka Connect, CDC Kafka topics, and downstream fan-out.
- More than one captured table per source node.
- Composite primary keys, tables without primary keys, and primary-key-free
  delete handling.
- Automatic target table creation or source-to-target DDL propagation.
- `TRUNCATE` propagation.
- GTID enablement or MySQL server restart/reconfiguration.
- Binary, spatial, vector, and other unlisted complex data types.
- Arbitrary transforms between the CDC source and apply sink.
- Guaranteed exactly-once side effects outside the target MySQL transaction.
- Changing existing application tables or clearing non-acceptance data.

## 4. Chosen Architecture

```text
 source MySQL table
        |
        | initial snapshot + ordered ROW binlog
        v
 Debezium AsyncEmbeddedEngine (one connector, one ordered consumer)
        |
        | one callback batch, bounded blocking bridge
        v
 mysql.cdc CheckpointedNodeSource
        |
        | SourceBatch containing canonical CDC envelopes
        v
 WorkflowExecutionEngine (mapAsync(1), no transforms for CDC mirror)
        |
        v
 mysql.cdc.apply CheckpointedNodeSink
        |
        | one transaction
        +--> target row upsert/delete
        +--> pekko_sync_batch_ledger
        |
        v
 EventSourcedWorkflowActor checkpoint
        |
        v
 Debezium RecordCommitter markProcessed + markBatchFinished
        |
        v
 Debezium JDBC offset store + JDBC schema history
```

Debezium owns the consistent snapshot-to-binlog transition, MySQL schema
history interpretation, and source offset format. Pekko owns orchestration,
backpressure, target transactions, workflow state, and lifecycle reporting.

This split deliberately avoids implementing the MySQL replication protocol,
snapshot locking, DDL parser, or historic schema reconstruction in local
Scala code.

## 5. Node Contracts

### 5.1 `mysql.cdc` source

Example workflow configuration:

```json
{
  "connectorId": "orders-cdc-v1",
  "host": "mysql",
  "port": 3306,
  "database": "pekko_workflow",
  "table": "pekko_cdc_source_acceptance",
  "username": "pekko_cdc",
  "passwordEnv": "MYSQL_CDC_PASSWORD",
  "serverId": 54001,
  "maxBatchSize": 100,
  "pollIntervalMillis": 500
}
```

Rules:

- `connectorId` is required, stable across restarts, and matches
  `[A-Za-z0-9._-]+`.
- `host`, `database`, `table`, `username`, and `passwordEnv` are required.
- `port` defaults to `3306`.
- `serverId` is required and is an integer in MySQL's valid unsigned 32-bit
  range. It must not be reused by another active replication client.
- `maxBatchSize` is positive and bounded by a conservative application limit.
- `pollIntervalMillis` is positive and bounded.
- Only an environment-variable name is stored in workflow JSON. The password
  value must never appear in definitions, logs, events, snapshots, or API
  responses.
- The source forces a one-table include list and `snapshot.mode=initial`.
- The source forces ordered processing and does not allow user configuration
  to switch to unordered record handling.
- Connector offsets and schema history use application-level JDBC state
  configuration, not values embedded in the workflow definition.

### 5.2 `mysql.cdc.apply` sink

Example workflow configuration:

```json
{
  "host": "mysql",
  "port": 3306,
  "database": "pekko_workflow",
  "table": "pekko_cdc_target_acceptance",
  "username": "pekko_workflow",
  "passwordEnv": "DB_PASSWORD"
}
```

Rules:

- The sink accepts only canonical CDC envelopes produced by `mysql.cdc`.
- The target table must already exist.
- Source and target must have exactly one primary-key column with the same
  case-insensitive name.
- Every captured source column must exist in the target table.
- Extra target columns are permitted only when nullable or defaulted.
- The sink prepares typed JDBC statements from validated target metadata.
- It does not concatenate table or column names from events. Identifiers come
  only from validated node configuration and JDBC metadata.
- The sink fails before the stream begins if schema or ledger validation
  fails.

## 6. Canonical CDC Envelope

Each stream row is canonical compact JSON with stable field ordering:

```json
{
  "version": 1,
  "op": "u",
  "key": {"id": 42},
  "before": {"id": 42, "status": "new", "amount": "12.30"},
  "after": {"id": 42, "status": "paid", "amount": "12.30"},
  "source": {
    "connectorId": "orders-cdc-v1",
    "database": "pekko_workflow",
    "table": "pekko_cdc_source_acceptance",
    "snapshot": false,
    "file": "binlog.000012",
    "position": 2805470,
    "row": 0,
    "eventTimestampMillis": 1788100000000
  }
}
```

Operation requirements:

| Debezium op | Meaning | Required payload | Target action |
|---|---|---|---|
| `r` | snapshot read | `key`, `after` | upsert full `after` row |
| `c` | insert | `key`, `after` | upsert full `after` row |
| `u` | update | `key`, `before`, `after` | upsert full `after` row |
| `d` | delete | `key`, `before` | delete by validated primary key |

Debezium tombstone records are metadata for log-compacted Kafka consumers and
are not target-table mutations; the embedded mirror filters them. Heartbeat
and schema-change records update connector internals but do not enter the
target stream. A truncate event fails closed with a node-attributed error.

The MVP supports null, boolean, integral, floating-point, decimal, character,
JSON-as-text, date, time, datetime, and timestamp columns. Decimal values are
encoded as strings to avoid precision loss. Target JDBC metadata determines
binding. Unsupported source or target types fail readiness rather than being
silently stringified.

## 7. Boundary, Cursor, and Batch Identity

The existing `SnapshotBoundary` and `SourceCursor` serialized shapes remain
unchanged.

- `SnapshotBoundary.partitionId` is `mysql-cdc:<connectorId>`.
- `SnapshotBoundary.upperBound` is a versioned canonical stream identity that
  includes the connector ID, source database, and source table. It is stable
  for the life of the workflow definition and is not a finite upper bound.
- `SourceCursor.kind` is `mysql.binlog.v1`.
- `SourceCursor.value` contains versioned canonical Debezium source offset
  data sufficient for diagnostics, including snapshot state and binlog
  file/position when present.
- `SourceCursor.upperBound` repeats the stable stream identity so existing
  actor boundary validation continues to reject mismatched checkpoints.
- `batchSequence` remains strictly increasing within the active execution.
- `batchId` continues to use the existing execution/source/partition/sequence
  identity, preserving current actor validation and ledger behavior.

Debezium's JDBC offset store is authoritative for connector restart position.
The Pekko cursor is a durable workflow acknowledgement and observability
record. Neither one is discarded: they cover different sides of the
source-to-sink protocol.

## 8. Commit and Acknowledgement Protocol

`CheckpointedNodeSource` gains a default no-op post-commit method so existing
snapshot and Kafka nodes remain source compatible:

```scala
def acknowledgeCommittedBatch(
  node: WorkflowDSL.Node,
  batch: SourceBatch,
  onLog: String => Unit
)(implicit blockingEc: ExecutionContext): Future[Done]
```

The CDC source associates each emitted `SourceBatch` with its in-memory
Debezium records and `RecordCommitter`. The engine executes this sequence:

1. receive exactly one Debezium callback batch;
2. apply the batch in the CDC sink;
3. commit target mutations and ledger row in one transaction;
4. persist or confirm the actor checkpoint;
5. invoke `acknowledgeCommittedBatch`;
6. call `markProcessed` for every delivered Debezium record;
7. call `markBatchFinished` once; and
8. release the callback so Debezium may fetch the next batch.

There is only one in-flight batch. The callback-to-stream bridge is bounded
and blocks the connector callback while downstream work is incomplete. This
provides backpressure and removes acknowledgement reordering from the MVP.

The Debezium offset flush interval is zero for normal operation so the engine
attempts a flush after every completed connector batch. Debezium still
documents possible batch replay after a crash; the sink is designed for that
case rather than assuming it cannot happen.

### 8.1 Crash matrix

| Crash point | Durable state | Restart behavior |
|---|---|---|
| Before target commit | no target or ledger change | Debezium replays the batch. |
| After target commit, before actor checkpoint | target and ledger committed | Same execution and sequence produce the same batch ID; ledger returns already committed. |
| After actor checkpoint, before Debezium offset flush | target, ledger, and actor checkpoint committed | Debezium may replay; upserts and deletes reapply the same final state safely. |
| After offset flush | all states committed | Connector resumes after the batch. |

This ordering prevents source offset advancement before target durability. It
can produce duplicate processing but must not produce a missing committed
source change.

## 9. Target Transaction Semantics

For each `SourceBatch`, `mysql.cdc.apply` opens one target connection and one
transaction:

1. look up `batch_id` in `pekko_sync_batch_ledger`;
2. if the exact ledger identity exists, return `AlreadyCommitted` without
   mutating target rows;
3. otherwise claim the ledger row;
4. apply events in original order;
5. commit ledger and target changes together; and
6. roll back both on any failure.

Target actions are idempotent final-state operations:

- `r`, `c`, and `u` use `INSERT ... ON DUPLICATE KEY UPDATE` with the complete
  `after` image;
- `d` uses `DELETE ... WHERE <primary-key> = ?`;
- deleting an already absent row succeeds with zero affected rows;
- replaying an upsert writes the same full row state again.

The sink preserves source event order inside the batch. It does not group
operations by type because doing so could change the result of multiple
updates to the same primary key in one source transaction.

## 10. Lifecycle and Actor Semantics

A CDC workflow is intentionally unbounded:

- after manual start its public status remains `running`;
- it does not produce a successful terminal execution while the connector is
  healthy;
- scheduled execution is rejected during validation for a `mysql.cdc`
  source;
- another manual start returns the existing `already_running` response;
- stream cancellation or actor stop closes the Debezium engine and releases
  its executor;
- process recovery of an active resumable execution recreates the engine with
  the same connector ID and JDBC state configuration;
- a non-retriable connector or sink error fails the execution with the source
  or sink node attribution preserved;
- a new manual execution after failure resumes from Debezium's durable JDBC
  offset.

This change does not add pause, drain, or graceful-stop APIs. Those require a
separate lifecycle design because stopping an unbounded execution is a user-
visible persisted state transition.

## 11. Durable Connector State

Debezium state uses JDBC storage in the existing workflow MySQL database:

- offset store class: `JdbcOffsetBackingStore`;
- schema history class: `JdbcSchemaHistory`;
- per-connector state uses dedicated tables derived from stable `connectorId`;
- DDL creates the required offset and schema-history tables idempotently;
- configured table names are trusted prefixes; runtime appends the first 32
  lowercase hexadecimal characters of SHA-256(`connectorId`) after truncating
  the prefix to 31 characters, keeping MySQL identifiers within 64 characters;
- state connections use the existing workflow database account and secret;
- the CDC replication account is used only for snapshot and binlog reads.

Connector-scoped tables prevent one embedded engine from replacing another
connector's singleton JDBC offset/history rows. They are retained after a
workflow stops; automatic state-table garbage collection is outside this MVP.

The source table include list excludes the Debezium metadata, target, Pekko
journal, snapshot, and ledger tables, preventing feedback loops.

Deleting or resetting connector offsets is an administrative data-loss-risk
operation and is not exposed through the workflow API.

## 12. Security and Live Environment Changes

The `bigdata-lab` bootstrap adds a dedicated `pekko_cdc` account with:

```sql
GRANT SELECT, RELOAD, SHOW DATABASES,
      REPLICATION SLAVE, REPLICATION CLIENT
ON *.* TO 'pekko_cdc'@'%';
```

These are the connector permissions documented by Debezium. No `SUPER`,
`BINLOG_ADMIN`, DDL, or application-table write privileges are granted to the
CDC account.

The implementation also:

- creates or updates a Kubernetes Secret key for the CDC password without
  printing the value;
- injects the secret into the isolated acceptance process as
  `MYSQL_CDC_PASSWORD`;
- preserves the existing workflow database Secret and account;
- avoids credentials in command output, committed files, workflow JSON, and
  test reports; and
- logs host/database/table/connector identity but never passwords or complete
  Debezium configuration objects that contain passwords.

The approved live mutations are limited to the CDC account/grants, connector
metadata tables, dedicated acceptance tables, CDC secret wiring, and an
isolated one-replica acceptance workload. Existing business tables and the
two-pod Pekko StatefulSet are not modified.

## 13. Build and Packaging

Add the stable Debezium API, embedded engine, MySQL connector, and JDBC storage
modules at one pinned version. Dependency resolution must show one coherent
Debezium and Kafka Connect version set.

Upgrade the application build and runtime baseline from Java 11 to Java 17:

- Docker base image becomes a Temurin 17 JRE image;
- compile and test run under JDK 17 or later;
- application bytecode target is documented and made consistent with the
  selected dependency baseline; and
- the Universal and Docker packages must include the Debezium ServiceLoader
  metadata required by the embedded implementation.

No preview Debezium 3.7 build is used.

## 14. Validation Strategy

Implementation follows test-first red/green/refactor cycles.

### 14.1 Focused unit tests

- valid and invalid CDC source configuration;
- password environment resolution without value leakage;
- canonical event-envelope conversion for `r`, `c`, `u`, and `d`;
- tombstone filtering and truncate rejection;
- supported and unsupported JDBC type validation;
- source boundary and cursor identity validation;
- one-batch-at-a-time bridge backpressure;
- acknowledgement happens only after sink and actor completion;
- acknowledgement failure fails the workflow;
- CDC workflow scheduling rejection;
- legacy snapshot and Kafka sources retain default no-op acknowledgement;
- `mysql.write` behavior remains unchanged.

### 14.2 MySQL integration tests

Against an isolated real MySQL schema:

1. create compatible source and target tables;
2. insert baseline source rows;
3. start `mysql.cdc` with no stored offset;
4. verify snapshot rows appear in the target;
5. commit source insert, update, and delete operations;
6. verify the exact target final state;
7. verify target rows and ledger rows commit or roll back together;
8. stop and restart the JVM with the same connector ID;
9. verify the connector resumes without a new snapshot;
10. force a replay window by transactionally locking only this connector's
    offset row, prove the target and actor ledger advanced without a durable
    offset change, kill the process, release the lock, and restart; and
11. verify no missing row, no resurrected delete, and the correct final state.

### 14.3 `bigdata-lab` acceptance

Use only dedicated tables:

- `pekko_cdc_source_acceptance`;
- `pekko_cdc_target_acceptance`.

Acceptance runs one Pekko process. It does not scale down the existing
StatefulSet. The test records:

- MySQL version and binlog variables;
- effective CDC grants without credential values;
- source and target row states before and after each operation;
- workflow execution ID and checkpoint cursor;
- connector offset row existence;
- process identity before and after restart; and
- final target equality.

Run-scoped rows in the dedicated acceptance tables may be deleted by the
acceptance harness. It must not truncate or delete from shared application
tables.

### 14.4 Regression verification

At minimum:

- compile the `pekko-server` test sources;
- run all new CDC-focused suites;
- run existing MySQL snapshot, Kafka source, MySQL sink, engine, and actor
  recovery suites;
- run the full non-external test suite;
- build the Universal package;
- build the Docker image; and
- inspect the final dependency tree and Git diff.

## 15. Acceptance Criteria

The MVP is complete only when all of the following have current evidence:

1. no simulator is used by the `mysql.cdc` workflow node;
2. the first run mirrors pre-existing source rows;
3. committed inserts, updates, and deletes appear in the target in order;
4. target mutation and batch ledger are transactionally atomic;
5. the Debezium batch is not acknowledged before target and actor durability;
6. a real process restart resumes from durable MySQL connector state;
7. a forced replay produces the correct target final state;
8. the CDC password is absent from definitions, logs, reports, and Git diff;
9. the full non-external test suite passes;
10. a Java 17 Universal package and Docker image build successfully; and
11. the real `bigdata-lab` single-process acceptance passes.

Local unit tests, compilation, or simulated CDC events cannot satisfy items
2 through 7 or item 11. If the live environment cannot be reached or mutated,
those items remain `external_blocked`, not complete.

## 16. Known Boundaries After Completion

After this MVP, the system will have a credible single-reader MySQL mirror,
but it will still need separate designs for:

- fenced ownership and takeover across Pekko nodes;
- connector ID and MySQL replication server ID allocation across workflows;
- pause, drain, stop, restart, and operator reset semantics;
- multiple tables and schema evolution;
- GTID migration and MySQL failover;
- retention and compaction of long-running execution checkpoints and ledgers;
- metrics for lag, snapshot progress, replay count, and offset age; and
- broader type compatibility and throughput tuning.

Those boundaries must not be described as implemented by this work.
