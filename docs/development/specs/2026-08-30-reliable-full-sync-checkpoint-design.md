# Reliable Full Sync Checkpoint Design

Date: 2026-08-30

## 1. Objective

Extend the reliable multi-node MVP with a resumable MySQL full-sync path. A
workflow must continue from its last durably committed source batch after the
hosting node stops, without losing target rows and without applying the same
batch twice.

This phase covers the first delivery batch from the product roadmap:

- SYNC-101: checkpoint data model;
- SYNC-102: checkpoint persistence;
- SYNC-103: MySQL primary-key chunk reading;
- SYNC-105: sink batch idempotency;
- SYNC-108: node interruption and recovery end-to-end evidence.

SYNC-108 requires the minimum recovery runner from SYNC-104 and the commit
ordering rule from SYNC-106. Those two pieces are included only to the extent
needed to make the recovery test real; adaptive batching, throttling, CDC, and
reconciliation remain outside this phase.

## 2. Existing Constraints

The existing runtime executes a linear `Source[String] -> Transform* ->
Sink[String]` stream. The source exposes neither a cursor nor a batch boundary,
the sink returns only a final `Done`, and the workflow actor persists only
execution start and terminal events. On recovery, an interrupted execution is
currently marked failed.

A checkpoint cannot safely represent rows merely emitted by the source. It may
advance only after the corresponding target effects are durable. The design
therefore introduces an explicit reliable-batch path while retaining the
existing row-stream path for compatibility.

## 3. Reliability Contract

For the new `mysql.snapshot` to checkpoint-aware MySQL sink path:

1. No source row at or below the captured snapshot upper bound is skipped
   because of a process or node restart.
2. A committed batch may be retried, but its target effects are applied at most
   once by the checkpoint-aware sink.
3. The actor checkpoint never moves ahead of the target transaction.
4. Recovery uses the original execution ID, workflow revision, source upper
   bound, and latest committed cursor.
5. Batches and partitions advance monotonically; gaps are rejected.

The system does not claim a distributed transaction across Pekko Persistence
and an arbitrary target database. It closes the unavoidable commit gap through
deterministic replay and a target-side idempotency ledger.

## 4. Chosen Architecture

Use an actor-owned durable checkpoint plus a target-database idempotency ledger.

```text
EventSourcedWorkflowActor
  |  executionId + workflowRevision + durable checkpoints
  v
WorkflowExecutionEngine (reliable batch mode)
  |  discover and persist snapshot upper bound before reading rows
  |  resume cursor
  v
MySQLSnapshotSourceNode -- SourceBatch(rows, next checkpoint)
  |  sequential batch transform
  v
Checkpointed MySQL Sink
  |  one DB transaction:
  |    target row changes + batch ledger record
  v
Actor persists ExecutionCheckpointAdvanced
  |
  `-- ACK permits the next source batch
```

Alternatives rejected for this phase:

- Actor checkpoint only: a crash after a target commit and before checkpoint
  persistence repeats external writes.
- One central transaction for workflow metadata and all target effects: this
  couples the runtime to one target database and prevents general connectors.

## 5. Batch and Checkpoint Model

The common model is independent of Spray JSON and is Jackson-CBOR serializable.

```scala
final case class SourceCursor(
  kind: String,
  value: String,
  upperBound: String
) extends CborSerializable

final case class SnapshotBoundary(
  sourceNodeId: String,
  partitionId: String,
  upperBound: Option[String]
) extends CborSerializable

final case class SourceBatch(
  sourceNodeId: String,
  partitionId: String,
  batchSequence: Long,
  batchId: String,
  cursor: SourceCursor,
  rows: Vector[String]
)

final case class BatchCheckpoint(
  sourceNodeId: String,
  partitionId: String,
  batchSequence: Long,
  batchId: String,
  cursor: SourceCursor,
  sourceRowsScanned: Long,
  targetRowsWritten: Long
) extends CborSerializable
```

MVP rules:

- `kind` is exactly `mysql.numeric-pk`.
- `partitionId` is `pk-range-0`; parallel range partitions are deferred.
- `batchSequence` starts at zero and increases by one.
- `batchId` is deterministic for the life of the execution:
  `SHA-256(executionId | sourceNodeId | partitionId | batchSequence)`.
- Cursor values use canonical decimal strings so unsigned MySQL `BIGINT`
  values are not restricted by Scala `Long`.
- `SourceBatch` contains the pre-transform rows and proposed next cursor.
- `BatchCheckpoint` is created from the sink's committed result, so its target
  row count is never guessed by the source.
- `sourceRowsScanned` describes the source batch before transforms. A batch
  whose rows are all filtered out is still committed and advances the cursor.

Checkpoint state is bounded to the latest checkpoint per partition. This phase
has one partition, so actor snapshot growth is constant per running execution.

## 6. Source Contract and MySQL Snapshot Semantics

The current `NodeSource` remains unchanged. A compatible extension provides
the reliable-batch capability:

```scala
trait CheckpointedNodeSource extends NodeSource {
  def discoverBoundary(
    node: WorkflowDSL.Node,
    onLog: String => Unit
  ): Future[SnapshotBoundary]

  def createBatches(
    node: WorkflowDSL.Node,
    executionId: String,
    boundary: SnapshotBoundary,
    resumeFrom: Option[BatchCheckpoint],
    onLog: String => Unit
  ): Source[SourceBatch, NotUsed]
}
```

The new node type is `mysql.snapshot`; existing `mysql.query` keeps its current
non-resumable arbitrary-query behavior.

`mysql.snapshot` accepts connection fields plus:

- `table`;
- `columns`;
- `primaryKey`;
- `chunkSize`.

The MVP accepts only a single, non-null, immutable numeric primary key. It does
not accept free-form SQL, free-form predicates, composite keys, UUID keys, or
collation-dependent text keys. Table and column identifiers must match a narrow
identifier grammar and are quoted. Cursor values are bound parameters.

Before reading the first batch, the source discovers the maximum primary-key
value and the actor persists it as the execution's `SnapshotBoundary`. No target
write may start until that event is acknowledged. An empty table stores
`upperBound = None` and can therefore complete deterministically without later
including newly inserted rows in the same execution. Each subsequent query for
a non-empty boundary uses keyset pagination:

```sql
SELECT <columns>
FROM <table>
WHERE <primaryKey> > ? AND <primaryKey> <= ?
ORDER BY <primaryKey> ASC
LIMIT ?
```

Rows inserted above the frozen upper bound belong to a later CDC or full-sync
run. Gaps in primary-key values are valid. Updating a primary key during a run
is outside the guarantee.

JDBC reads execute on the configured blocking dispatcher. At most one chunk is
in flight. A data source is allocated per materialized run and is closed after
completion, cancellation, or failure.

## 7. Transform and Engine Behavior

The engine selects reliable-batch mode only when the source and sink implement
their checkpoint-aware capabilities. Other workflows continue through the
existing row-stream engine.

For each `SourceBatch`, the engine materializes the existing ordered transform
pipeline over that batch's bounded row vector, collects the transformed rows,
and passes them with the unchanged source checkpoint to the sink. Batches are
processed sequentially with `mapAsync(1)`. This intentionally favors clear
recovery semantics over cross-batch concurrency in the first version.

An empty transformed batch is not dropped. The sink records its ledger entry
with zero target rows, after which the actor advances the source cursor.

The engine receives a run context containing the recovered snapshot boundary,
checkpoints, and callbacks that complete only after the actor persists the
corresponding events. For a new run, it discovers and persists the boundary
before creating the batch source. It does not pull the next batch until the
current checkpoint callback succeeds.

The sink capability is explicit rather than inferred from the legacy row sink:

```scala
trait CheckpointedNodeSink extends NodeSink {
  def validateReady(
    node: WorkflowDSL.Node,
    onLog: String => Unit
  )(implicit blockingEc: ExecutionContext): Future[Done]

  def commitBatch(
    node: WorkflowDSL.Node,
    workflowId: String,
    executionId: String,
    batch: SourceBatch,
    transformedRows: Vector[String],
    onLog: String => Unit
  ): Future[BatchCommitResult]
}
```

`BatchCommitResult` reports either `Committed(checkpoint)` or
`AlreadyCommitted(checkpoint)`. In both cases the checkpoint values come from
the durable ledger metadata and are passed unchanged to the actor.

Reliable mode is selected by a checkpoint-aware source. Such a source requires
a checkpoint-aware sink and fails validation before reading rows otherwise. A
legacy source continues through the legacy row-stream path even when its sink
also offers the checkpoint-aware capability; this preserves existing
`mysql.query -> mysql.write` workflows.

## 8. Sink Idempotency Ledger

A checkpoint-aware MySQL sink commits target effects and an idempotency record
using one database connection and one transaction.

The target database contains a runtime-owned table equivalent to:

```sql
CREATE TABLE pekko_sync_batch_ledger (
  batch_id VARCHAR(64) PRIMARY KEY,
  workflow_id VARCHAR(255) NOT NULL,
  execution_id VARCHAR(255) NOT NULL,
  source_node_id VARCHAR(255) NOT NULL,
  partition_id VARCHAR(128) NOT NULL,
  batch_sequence BIGINT NOT NULL,
  cursor_value VARCHAR(128) NOT NULL,
  upper_bound VARCHAR(128) NOT NULL,
  source_rows BIGINT NOT NULL,
  target_rows BIGINT NOT NULL,
  committed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_execution_partition_sequence
    (execution_id, source_node_id, partition_id, batch_sequence)
)
```

Production startup does not silently create this table. A versioned schema
script is supplied, and validation reports a missing ledger table before a
reliable workflow starts. Hermetic tests create it explicitly.

Commit behavior:

- If `batch_id` does not exist, write target rows and insert the ledger record
  in one transaction, then commit.
- If the same `batch_id` exists with identical metadata, return
  `AlreadyCommitted` without applying target rows again.
- If the same identity exists with conflicting cursor or counts, fail the
  execution; it indicates nondeterminism or corrupt state.
- On any row or ledger failure, roll back the complete transaction.

The first version supports the existing `insert`, `upsert`, and `replace`
modes, but reliable recovery of plain `insert` depends on the ledger transaction
being used. The legacy MySQL sink path retains its current behavior.

## 9. Actor Events, State, and Recovery

Existing event shapes remain unchanged so old journal rows can still be read.
New executions use additional event types:

```scala
final case class ResumableExecutionStarted(
  executionId: String,
  trigger: ExecutionTrigger,
  workflowRevision: Long,
  timestamp: Long
) extends WorkflowEvent

final case class ExecutionSnapshotInitialized(
  executionId: String,
  boundary: SnapshotBoundary,
  timestamp: Long
) extends WorkflowEvent

final case class ExecutionCheckpointAdvanced(
  executionId: String,
  checkpoint: BatchCheckpoint,
  timestamp: Long
) extends WorkflowEvent
```

The current execution state gains resumable metadata with defaults compatible
with older snapshots. A focused serialization and snapshot-recovery test must
prove that state written before these fields existed remains readable. If that
test disproves default-field compatibility, an explicit Jackson migration is
required before implementation can continue.

Checkpoint command handling enforces:

- matching current execution ID;
- one immutable snapshot boundary per source and partition;
- matching source and partition;
- `sequence == latest + 1`: persist and acknowledge accepted;
- `sequence == latest` with full checkpoint equality: acknowledge as already
  stored without another event;
- `sequence < latest`: reject because bounded latest-only state cannot verify
  the older cursor and row-count metadata;
- a sequence gap or conflicting replay: reject and fail the run.

Workflow definition updates are rejected while an execution is active. This
freezes the workflow revision used by recovery without persisting another full
workflow copy in every execution event.

After actor recovery:

- a resumable execution restarts the engine with the original execution ID and
  latest checkpoints;
- a legacy or non-checkpoint-aware interrupted execution keeps the current
  `interrupted/recovered` failure behavior;
- externally queued commands remain stashed until recovery has either resumed
  the current run or made it terminal.

If recovery finds a resumable run without a snapshot boundary, the engine may
discover one because no target batch was allowed to commit before boundary
persistence. Once a boundary exists, every restart must reuse it.

## 10. Commit Ordering and Crash Windows

The only valid success order is:

```text
1. Actor persists `ExecutionSnapshotInitialized` before the first target write.
2. Target rows and batch ledger commit atomically.
3. Actor persists `ExecutionCheckpointAdvanced`.
4. Engine receives checkpoint ACK.
5. Engine requests the next source batch.
```

Failure matrix:

| Crash point | Durable state | Recovery action |
|---|---|---|
| Before snapshot-boundary event | Execution start only, no target effects | Rediscover and persist a boundary, then start reading |
| After snapshot-boundary event, before first target commit | Frozen boundary only | Reuse boundary and run first batch |
| Before target commit | Neither target batch nor checkpoint | Re-run batch and commit normally |
| After target commit, before actor event | Target batch and ledger only | Sink returns AlreadyCommitted; actor persists checkpoint |
| After actor event, before engine observes ACK | Target batch, ledger, and checkpoint | Actor returns AlreadyStored; continue with next batch |
| During next source read | Previous checkpoint is durable | Resume keyset query after previous cursor |
| After all batches, before terminal event | Final checkpoint is durable | Resume, observe no remaining rows, persist terminal event |

Persisting the actor checkpoint before the target transaction is prohibited
because that ordering can deterministically lose data.

## 11. Testing and Acceptance Evidence

All behavior changes follow red-green-refactor. The minimum hermetic suite must
cover:

### Checkpoint model and actor

- checkpoint event updates the current execution and survives actor restart;
- snapshot boundary is immutable and survives actor restart;
- duplicate checkpoint is acknowledged without another state advance;
- sequence gaps and conflicting replays are rejected;
- legacy interrupted runs still become failed;
- resumable interrupted runs restart with the same execution ID and revision;
- workflow updates are rejected while a run is active;
- old serialized events and snapshots remain readable.

### MySQL snapshot source

- rows are emitted in numeric primary-key chunks;
- resume excludes keys at or below the stored cursor;
- key gaps and a partial final batch work;
- rows inserted above the captured upper bound are excluded;
- an empty-table boundary remains empty after recovery;
- invalid identifiers, chunk sizes, and unsupported key shapes are rejected;
- cancellation and failures close all JDBC resources;
- a filtered-to-empty batch still carries a checkpoint boundary.

### Idempotent sink

- target rows and ledger entry commit together;
- replaying an identical batch does not write target rows twice;
- conflicting batch metadata fails;
- a target row failure rolls back both target rows and ledger;
- an empty batch writes only its ledger entry;
- data source resources close on success, failure, and cancellation.

### Recovery integration

A hermetic two-node test using shared JDBC persistence and an isolated H2 target
must stop the node hosting a workflow after at least one target batch commits,
recover the sharded entity on the surviving node, and prove:

- the original execution ID is retained;
- execution resumes after the last durable source cursor;
- every expected target row exists exactly once;
- every batch identity appears once in the ledger;
- the execution reaches `completed` rather than `interrupted/recovered`.

Real MySQL integration tests are tagged external and are not run automatically.
After explicit authorization, isolated random `pekko_test_*` schemas on MySQL
8.4.5 verified connector replay, direct actor recovery across two JVMs, and full
two-worker Cluster Sharding failover after `Runtime.halt(23)`. The sharding
harness waits for both regions before the crash and for the survivor's new
coordinator after member removal; two consecutive post-fix runs passed. Every
test drops its schema in `finally`; H2 and real-MySQL evidence remain reported
separately.

## 12. Parallel Delivery Boundaries

The public batch protocol is implemented first and frozen before parallel
production edits. Work then splits into three non-overlapping tracks:

1. Checkpoint actor track: events, actor state/commands, serialization and
   recovery tests.
2. MySQL snapshot source track: new source node, config parser, and source
   tests. It does not modify the legacy source.
3. MySQL sink idempotency track: checkpoint-aware sink transaction, schema,
   and sink tests.

The integration owner alone modifies shared engine contracts, registry wiring,
run-context construction, and the two-node recovery test. After each track is
reviewed, the integration owner runs focused suites followed by the existing
reliable multi-node regression suites.

Agents must not modify or stage the user-owned `.tasks/` directory. Explicit
authorization covered isolated external MySQL verification, but this phase does
not authorize a Git commit, push, or merge.

## 13. Out of Scope

- Parallel primary-key range partitions.
- Composite, textual, UUID, nullable, or mutable cursor keys.
- Arbitrary SQL snapshot queries or user-provided predicates.
- Cross-database exactly-once guarantees.
- Kafka or MySQL Binlog CDC.
- Reconciliation and automatic repair.
- Adaptive batch sizing, throttling, and workload placement.
- Changes to the public HTTP checkpoint API or frontend.
