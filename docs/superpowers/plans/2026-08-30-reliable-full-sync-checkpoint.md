# Reliable Full Sync Checkpoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a resumable MySQL snapshot-sync path that survives workflow-node loss without losing rows or applying a committed batch twice.

**Architecture:** Persist the snapshot boundary and latest committed batch in the event-sourced workflow actor. Read MySQL by bounded numeric-primary-key chunks, commit each transformed batch and its idempotency ledger record in one target transaction, and advance the actor checkpoint only after that transaction commits. Preserve the existing `String` row-stream engine as the compatibility path.

**Tech Stack:** Scala 2.13, Apache Pekko Typed/Cluster Sharding/Persistence/Streams, Pekko Jackson CBOR, JDBC, HikariCP, MySQL 8, H2 MySQL mode, ScalaTest, sbt.

**Spec:** `docs/superpowers/specs/2026-08-30-reliable-full-sync-checkpoint-design.md`

## Global Constraints

- Keep the workflow shape strictly `Source -> Transform* -> Sink`.
- The reliable path is `mysql.snapshot` to checkpoint-aware `mysql.write`; preserve legacy `mysql.query` and row-stream behavior.
- Support exactly one partition named `pk-range-0` and one non-null immutable numeric primary key.
- Persist decimal cursor values as strings; do not narrow MySQL unsigned values to Scala `Long`.
- Persist the snapshot boundary before any target write.
- Commit target rows and `pekko_sync_batch_ledger` in one transaction before advancing the actor checkpoint.
- Reuse the original `executionId` on recovery.
- Process reliable batches sequentially; do not add adaptive batching or cross-batch concurrency.
- Never contact the existing Tailscale MySQL endpoints; tests using them remain `ExternalIntegration` and excluded.
- Do not modify, stage, or delete `.tasks/`.
- This phase is not authorized to commit, push, merge, publish, or run an external database. Replace commit steps with a scoped diff review and leave changes uncommitted.
- Every production behavior starts with a focused failing test whose failure is observed and recorded.

## File Ownership and Parallel Order

Task 1 freezes shared contracts and must complete first. Tasks 2, 3, and 4 then run in parallel with exclusive file ownership. Tasks 5 through 7 are integration-owner work and run sequentially.

| Track | Exclusive files | Shared files it must not edit |
|---|---|---|
| Task 2 actor | `WorkflowEvents.scala`, `EventSourcedWorkflowActor.scala`, actor specs | engine, source, sink, registry |
| Task 3 source | new `MySQLSnapshotSource*` files and source spec | actor, engine, sink, registry |
| Task 4 sink | `MySQLSinkNode.scala`, sink spec, ledger schema | actor, engine, source, registry |
| Integration owner | engine, registry, config, integration spec | reviews tracks before integrating |

---

### Task 1: Freeze the Reliable-Batch Contracts

**Files:**
- Create: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/checkpoint/CheckpointModels.scala`
- Create: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/ReliableRunContext.scala`
- Create: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/base/CheckpointedNodes.scala`
- Create: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/checkpoint/CheckpointModelsSpec.scala`

**Interfaces:**
- Produces: `SourceCursor`, `SnapshotBoundary`, `SourceBatch`, `BatchCheckpoint`, `BatchCommitResult`, `BatchId`.
- Produces: `ReliableRunContext` with immutable recovered state and two durability callbacks.
- Produces: self-typed `CheckpointedNodeSource` and `CheckpointedNodeSink` capabilities without changing `NodeSource` or `NodeSink`.

- [ ] **Step 1: Write serialization and deterministic-identity tests**

Create tests that instantiate the exact models below, round-trip `SnapshotBoundary` and `BatchCheckpoint` with `SerializationTestKit`, and assert that repeated calls to `BatchId.sha256("execution-1", "source-1", "pk-range-0", 7L)` return the same 64-character lowercase hexadecimal string while sequence `8L` returns a different value.

```scala
val boundary = SnapshotBoundary("source-1", "pk-range-0", Some("18446744073709551615"))
val checkpoint = BatchCheckpoint(
  "source-1", "pk-range-0", 7L,
  BatchId.sha256("execution-1", "source-1", "pk-range-0", 7L),
  SourceCursor("mysql.numeric-pk", "1009", "18446744073709551615"),
  sourceRowsScanned = 10L,
  targetRowsWritten = 8L
)
```

- [ ] **Step 2: Run the model test and observe RED**

Run:

```bash
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.checkpoint.CheckpointModelsSpec'
```

Expected: compilation fails because the checkpoint package and model types do not exist.

- [ ] **Step 3: Implement the serializable data model and batch ID utility**

Implement the model fields exactly as specified. `SourceBatch` carries the pre-transform row vector and proposed cursor; `BatchCheckpoint` carries counts returned from the durable sink result. Define:

```scala
sealed trait BatchCommitResult
final case class Committed(checkpoint: BatchCheckpoint) extends BatchCommitResult
final case class AlreadyCommitted(checkpoint: BatchCheckpoint) extends BatchCommitResult

object BatchId {
  def sha256(executionId: String, sourceNodeId: String, partitionId: String, sequence: Long): String
}
```

Hash the UTF-8 bytes of the four values joined with `|`; render each digest byte as two lowercase hexadecimal characters.

- [ ] **Step 4: Implement the run context and connector capabilities**

Define:

```scala
final case class ReliableRunContext(
  executionId: String,
  workflowRevision: Long,
  boundary: Option[SnapshotBoundary],
  checkpoints: Vector[BatchCheckpoint],
  initializeBoundary: SnapshotBoundary => Future[Done],
  checkpointCommitted: BatchCheckpoint => Future[Done]
)
```

Define `CheckpointedNodeSource` as a self-type of `NodeSource` with:

```scala
def discoverBoundary(node: WorkflowDSL.Node, onLog: String => Unit)
  (implicit blockingEc: ExecutionContext): Future[SnapshotBoundary]

def createBatches(
  node: WorkflowDSL.Node,
  executionId: String,
  boundary: SnapshotBoundary,
  resumeFrom: Option[BatchCheckpoint],
  onLog: String => Unit
)(implicit blockingEc: ExecutionContext): Source[SourceBatch, NotUsed]
```

Define `CheckpointedNodeSink` as a self-type of `NodeSink` with the exact `validateReady` and `commitBatch` signatures from the spec and an implicit blocking execution context. `validateReady` completes only when the configured target ledger exists and has the required columns.

- [ ] **Step 5: Run focused tests and compile**

Run:

```bash
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.checkpoint.CheckpointModelsSpec' 'pekko-server/Test/compile'
```

Expected: model tests pass and test compilation succeeds.

- [ ] **Step 6: Review the scoped diff without committing**

Run:

```bash
git diff --check
git status --short
```

Confirm only the four Task 1 files plus the approved spec and plan are new or modified; `.tasks/` remains untracked and untouched.

---

### Task 2: Persist Snapshot Boundaries and Batch Checkpoints in the Workflow Actor

**Files:**
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/events/WorkflowEvents.scala`
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/actors/EventSourcedWorkflowActor.scala`
- Modify: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/actors/EventSourcedWorkflowActorSpec.scala`
- Modify: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/actors/EventSourcedWorkflowActorRecoverySpec.scala`

**Interfaces:**
- Consumes: checkpoint types from Task 1.
- Produces: `ResumableExecutionStarted`, `ExecutionSnapshotInitialized`, `ExecutionCheckpointAdvanced`.
- Produces: actor commands `InitializeSnapshot` and `AdvanceCheckpoint` and deterministic accepted/already-stored/rejected replies.
- Produces: resumable state that Task 5 reads when constructing `ReliableRunContext`.

- [ ] **Step 1: Add RED tests for event and command serialization**

Extend the actor spec to round-trip the three new events, both new commands, and their replies through `SerializationTestKit`. Use a typed test probe as `replyTo`; include an unsigned-range upper-bound string.

- [ ] **Step 2: Add RED behavior tests for checkpoint monotonicity**

Use a pending engine so the execution remains active. Register a test-only `NodeSource` with node type `mysql.snapshot`, define and execute that workflow, and verify:

```text
InitializeSnapshot(boundary)            -> SnapshotInitialized
InitializeSnapshot(same boundary)       -> SnapshotAlreadyInitialized
InitializeSnapshot(conflicting bound)   -> CheckpointRejected
AdvanceCheckpoint(sequence 0)           -> CheckpointAccepted
AdvanceCheckpoint(same checkpoint)      -> CheckpointAlreadyStored
AdvanceCheckpoint(sequence 2)           -> CheckpointRejected (gap)
AdvanceCheckpoint(sequence 0 conflict)  -> CheckpointRejected
```

Also assert `DefineWorkflow` is rejected while this execution is running.

- [ ] **Step 3: Run actor spec and observe RED**

Run:

```bash
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActorSpec'
```

Expected: compilation fails on the new event, command, reply, and state symbols.

- [ ] **Step 4: Add new event types without changing old event shapes**

Add:

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

Do not add fields to `ExecutionStarted`, `ExecutionCompleted`, or `ExecutionFailed`.

- [ ] **Step 5: Extend actor state with backward-compatible defaults**

Extend current execution state with:

```scala
workflowRevision: Long = 0L
resumable: Boolean = false
boundary: Option[SnapshotBoundary] = None
checkpoints: Vector[BatchCheckpoint] = Vector.empty
```

Handle `ExecutionStarted` as legacy/non-resumable. Handle `ResumableExecutionStarted` as resumable. Recognize a reliable workflow only when the source node type is `mysql.snapshot` and the sink node type is `mysql.write`.

- [ ] **Step 6: Implement idempotent boundary and checkpoint commands**

Persist a boundary only once. For checkpoints, find the latest checkpoint for `sourceNodeId + partitionId`; accept expected sequence `0` when absent, otherwise `latest.batchSequence + 1`. Validate every checkpoint's deterministic batch ID before sequence handling. Only a checkpoint exactly equal to the retained latest checkpoint returns already stored without persisting; reject older sequences because latest-only bounded state cannot verify their discarded cursor/count metadata. A gap, conflicting replay, wrong execution, or boundary mismatch returns `CheckpointRejected` without changing state.

- [ ] **Step 7: Expose and verify current reliable state before recovery integration**

Add a package-private `GetReliableRunState` query. Start a pending reliable execution, persist boundary and sequence zero, and observe the same execution ID, boundary, and checkpoint before stopping the actor. Keep the existing legacy interruption and JDBC recovery tests unchanged and passing. Automatic recovery of the new state is completed and tested in Task 5, where the reliable engine contract is available.

- [ ] **Step 8: Prove old snapshot compatibility**

Create and persist legacy-shaped execution state through the existing event path, force a snapshot, recover it with the new code, and assert defaults are `resumable = false`, `boundary = None`, and empty checkpoints. If Jackson cannot apply defaults to the old snapshot, stop this task and report `evidence_incomplete`; do not delete old snapshots or silently change serializer bindings.

- [ ] **Step 9: Run actor suites**

Run:

```bash
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActorSpec cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActorRecoverySpec'
```

Expected: all actor tests pass, including legacy recovery.

- [ ] **Step 10: Review only actor-owned changes**

Run `git diff --check` and record changed files and test counts in the task report. Do not commit.

---

### Task 3: Implement the Numeric-Primary-Key MySQL Snapshot Source

**Files:**
- Create: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLSnapshotSourceConfig.scala`
- Create: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLSnapshotSourceNode.scala`
- Create: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLSnapshotSourceNodeSpec.scala`

**Interfaces:**
- Consumes: `CheckpointedNodeSource`, `SnapshotBoundary`, `SourceBatch`, `BatchCheckpoint`, and `BatchId` from Task 1.
- Produces: node type `mysql.snapshot` with boundary discovery and sequential keyset batches.
- Does not modify or replace `MySQLSourceNode`.

- [ ] **Step 1: Write RED config-validation tests**

Cover missing table/columns/primary key, `chunkSize <= 0`, identifiers containing whitespace/backticks/SQL punctuation, an empty column list, and a valid configuration. The accepted identifier regex is exactly `[A-Za-z_][A-Za-z0-9_]*`.

- [ ] **Step 2: Write RED H2 keyset tests**

Use an isolated H2 database in MySQL mode with numeric primary keys `1, 2, 5, 9, 12`. Assert `chunkSize = 2` yields cursors `2`, `9`, `12`, batch sequences `0, 1, 2`, stable deterministic batch IDs, and rows ordered by primary key. Assert resume after cursor `9` yields only key `12` at sequence `3`.

- [ ] **Step 3: Add RED boundary tests**

Assert a non-empty table produces `Some("12")`; an empty table produces `None`; inserting key `13` after discovering `Some("12")` does not include key `13` in that run.

- [ ] **Step 4: Add RED resource and metadata tests**

Assert cancellation and injected SQL failure close the data source and leave zero active connections. Reject a composite key, non-primary configured column, nullable/non-numeric key, and primary-key metadata that does not match configuration. Verify JSON uses `ResultSetMetaData.getColumnLabel` so selected aliases are preserved.

- [ ] **Step 5: Run source spec and observe RED**

Run:

```bash
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.MySQLSnapshotSourceNodeSpec'
```

Expected: compilation fails because the snapshot source and config parser do not exist.

- [ ] **Step 6: Implement strict configuration parsing**

Parse the existing connection fields plus `table`, `columns`, `primaryKey`, and `chunkSize`. Validate identifiers before constructing SQL. Keep password values out of logs and exceptions. Use an overridable `createDataSource` method for hermetic H2 tests.

- [ ] **Step 7: Implement boundary discovery and metadata validation**

Open a short-lived connection, validate one matching numeric non-null primary-key column through JDBC metadata, and execute `SELECT MAX(quotedPrimaryKey) FROM quotedTable`. Convert the result using `BigDecimal(value.toString).bigDecimal.toPlainString`; return `None` for SQL `NULL`. Close result set, statement, connection, and data source on every path.

- [ ] **Step 8: Implement one-at-a-time keyset batch reads**

Use the persisted boundary and the last checkpoint cursor. Query strictly `> cursor` and `<= upperBound`, ordered ascending, limited by `chunkSize`. Materialize no more than one `Vector[String]` of size `chunkSize`. Derive the next cursor from the final source row's primary key and sequence from the recovered checkpoint. Use `getColumnLabel` for JSON keys.

- [ ] **Step 9: Run source tests twice**

Run the focused source spec once for green, then repeat it to expose leaked H2 resources or order dependence:

```bash
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.MySQLSnapshotSourceNodeSpec'
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.MySQLSnapshotSourceNodeSpec'
```

Expected: both runs pass with no active connections.

- [ ] **Step 10: Review only source-owned changes**

Run `git diff --check`; confirm `MySQLSourceNode.scala`, actor, engine, sink, and registry are unchanged by this track. Do not commit.

---

### Task 4: Add Transactional Batch Idempotency to the MySQL Sink

**Files:**
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sinks/MySQLSinkNode.scala`
- Modify: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sinks/MySQLSinkNodeSpec.scala`
- Create: `pekko-server/src/main/resources/db/mysql/pekko-sync-ledger-schema.sql`
- Create: `pekko-server/src/test/resources/schema/h2/pekko-sync-ledger-schema.sql`

**Interfaces:**
- Consumes: `CheckpointedNodeSink`, `SourceBatch`, `BatchCheckpoint`, `Committed`, and `AlreadyCommitted` from Task 1.
- Produces: `MySQLSinkNode.commitBatch` that atomically commits target rows and ledger metadata.
- Preserves: existing `createSink` behavior and its current tests.

- [ ] **Step 1: Add RED ledger-schema assertions**

Load the H2 schema into the existing isolated sink fixture and assert the ledger table has primary key `batch_id` and a unique constraint over execution/source/partition/sequence.

- [ ] **Step 2: Add RED first-commit and replay tests**

Commit a two-row batch and assert two target rows plus one ledger row. Commit the identical batch again and assert target count and ledger count remain unchanged and the result is `AlreadyCommitted` with checkpoint metadata equal to the first `Committed` result.

- [ ] **Step 3: Add RED rollback and conflict tests**

Assert a duplicate target key rolls back target rows and the ledger. Insert a ledger row for the same batch identity with a different cursor or row count and assert `commitBatch` fails without target changes. Commit an empty transformed batch and assert zero target rows plus one ledger row.

- [ ] **Step 4: Add a RED readiness test**

Call `validateReady` before creating the ledger table and assert a focused missing-ledger failure. Create the approved schema and assert validation succeeds without changing target or ledger row counts.

- [ ] **Step 5: Run sink spec and observe RED**

Run:

```bash
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sinks.MySQLSinkNodeSpec'
```

Expected: compilation fails because `MySQLSinkNode` does not implement the checkpoint-aware capability or `commitBatch`.

- [ ] **Step 6: Add versioned MySQL and H2 ledger schemas**

Use the exact column and index names from the design. The MySQL script uses `CREATE TABLE IF NOT EXISTS pekko_sync_batch_ledger`; the H2 fixture uses compatible MySQL-mode types and constraints. Do not add automatic production table creation.

- [ ] **Step 7: Implement readiness, ledger lookup, and conflict validation**

Implement `validateReady` with a read-only metadata/query check for every required ledger column. Within a single connection, query by `batch_id` before target writes. Return `AlreadyCommitted` only if workflow ID, execution ID, source node, partition, sequence, cursor, upper bound, source-row count, and target-row count match the proposed batch. Treat any difference as an `IllegalStateException` with a non-secret diagnostic.

- [ ] **Step 8: Implement atomic target and ledger commit**

With `autoCommit = false`, parse and validate all transformed rows, insert the ledger record to claim the deterministic identity, execute the target batch, then commit. Construct the returned `BatchCheckpoint` using `batch.rows.size` and the actual transformed row count. If a concurrent unique-key race occurs, roll back, re-read the ledger, and return `AlreadyCommitted` only when all metadata matches. Roll back on every other non-fatal failure and close statement and connection in `finally`.

- [ ] **Step 9: Keep legacy sink regression tests green**

Run:

```bash
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sinks.MySQLSinkNodeSpec'
```

Expected: old batching, rollback, lazy allocation, cancellation, and malformed-JSON tests plus new idempotency tests all pass.

- [ ] **Step 10: Review only sink-owned changes**

Run `git diff --check`; confirm actor, source, engine, and registry files are unchanged by this track. Do not commit.

---

### Task 5: Integrate Reliable Batch Execution and Actor Recovery

**Files:**
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/WorkflowExecutionEngine.scala`
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/registry/NodeRegistry.scala`
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/actors/EventSourcedWorkflowActor.scala`
- Modify: `pekko-server/src/main/resources/application.conf`
- Modify: `pekko-server/src/main/resources/application-dev.conf`
- Modify: `pekko-server/src/main/resources/application-prod.conf`
- Modify: `pekko-server/src/test/resources/application-test.conf`
- Modify: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/engine/WorkflowExecutionEngineSpec.scala`
- Modify: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/actors/EventSourcedWorkflowActorRecoverySpec.scala`

**Interfaces:**
- Consumes: all Task 1 contracts, Task 2 actor state/protocol, Task 3 source, and Task 4 sink.
- Produces: reliable-mode engine selection, durability backpressure, and automatic same-execution recovery.

- [ ] **Step 1: Add RED engine-mode tests with in-memory checkpoint connectors**

Register test source and sink implementations with the checkpoint-aware self-types. Assert reliable mode:

- reuses a supplied boundary without rediscovery;
- validates sink readiness before boundary discovery or source reads;
- initializes a missing boundary before the first sink call;
- resumes source creation from the latest checkpoint;
- transforms each batch in order;
- commits an empty transformed batch;
- invokes `checkpointCommitted` before requesting the next batch;
- returns rows processed as the sum of committed target rows.

Keep the existing legacy source/sink tests unchanged.

- [ ] **Step 2: Run engine spec and observe RED**

Run:

```bash
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngineSpec'
```

Expected: new reliable-mode expectations fail because the engine still constructs one row stream.

- [ ] **Step 3: Register `mysql.snapshot` without altering `mysql.query`**

Add one `MySQLSnapshotSourceNode` to built-in sources. Assert registry lookup returns separate instances for node types `mysql.snapshot` and `mysql.query`.

- [ ] **Step 4: Add a configured JDBC blocking dispatcher**

Define `pekko.workflow.jdbc-dispatcher` as a fixed thread-pool dispatcher in default, development, production, and test configurations. The engine resolves it with `DispatcherSelector.fromConfig("pekko.workflow.jdbc-dispatcher")` and supplies it only to blocking source/sink calls.

- [ ] **Step 5: Implement reliable-mode selection and sequential batches**

After workflow validation, select reliable mode when the source exposes the checkpoint-aware capability. Require the sink to expose its checkpoint-aware capability and otherwise return a failed `ExecutionResult` naming both node types. A legacy source always remains on the legacy row-stream path, even when its sink also has checkpoint-aware methods. For a complete reliable pairing:

```text
resolve/persist boundary
create Source[SourceBatch]
mapAsync(1): transform bounded rows -> commitBatch -> persist checkpoint
fold targetRowsWritten
return completed ExecutionResult
```

Preserve node failure attribution by wrapping boundary, source, transform, sink, and checkpoint callback failures with the correct workflow node or workflow-level diagnostic.

- [ ] **Step 6: Construct actor durability callbacks with typed asks**

When starting a resumable workflow, build `ReliableRunContext` from current actor state. `initializeBoundary` asks `context.self` to handle `InitializeSnapshot`; `checkpointCommitted` asks it to handle `AdvanceCheckpoint`. Convert accepted and duplicate replies to `Done`; convert rejection and timeout to failed futures so the stream stops.

- [ ] **Step 7: Replace resumable interruption failure with same-ID restart**

On `RecoveryCompleted`, keep the recovery gate closed, decode the frozen workflow definition, and restart the reliable engine with the recovered execution ID, revision, boundary, and checkpoints. Permit only internal `InitializeSnapshot`, `AdvanceCheckpoint`, and engine terminal commands through the gate while resume startup is in progress; otherwise the actor would deadlock waiting on an ask to itself. Open the gate and unstash queued external commands only after the engine is successfully launched. Preserve the current terminal-failure path for legacy/non-resumable executions.

Add the JDBC recovery test deferred from Task 2: persist a boundary and sequence-zero checkpoint, stop the actor, recover it with the same persistence ID, and assert the restarted engine receives the original execution ID, revision, boundary, and checkpoint.

- [ ] **Step 8: Run engine and actor recovery suites**

Run:

```bash
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngineSpec cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActorSpec cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActorRecoverySpec'
```

Expected: reliable and legacy paths pass.

- [ ] **Step 9: Inspect integrated diff without committing**

Run `git diff --check` and inspect `git diff --stat`. Confirm integration did not rewrite unrelated workflow, scheduler, API, or connector code.

---

### Task 6: Prove Two-Node Batch Recovery End to End

**Files:**
- Create: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/integration/ResumableFullSyncRecoverySpec.scala`
- Modify: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/integration/TwoNodeWorkflowRecoverySpec.scala` only if a reusable helper must be made package-visible.
- Modify: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/WorkflowFixtures.scala`

**Interfaces:**
- Consumes: shared JDBC multi-node fixture, `mysql.snapshot`, idempotent MySQL sink, reliable engine, and actor recovery.
- Produces: hermetic evidence for the Sink-commit/Actor-checkpoint crash window.

- [ ] **Step 1: Write a RED controlled-crash integration test**

Create isolated shared JDBC persistence and target H2 databases. Insert at least five source rows with `chunkSize = 2`. Instrument the sink or actor checkpoint callback with a test probe/latch so node 1 is terminated after sequence zero commits to target and ledger but before its checkpoint ACK completes.

- [ ] **Step 2: Assert final recovery invariants**

Join node 2, down node 1, initialize sharding on node 2, and eventually assert:

```text
workflow status                 = completed
execution ID after recovery     = original execution ID
target IDs                      = exactly all source IDs
target row count                = source row count
ledger batch IDs                = unique
ledger sequence values          = 0, 1, 2
latest actor checkpoint cursor  = final source primary key
```

- [ ] **Step 3: Run the test and observe RED**

Run:

```bash
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.integration.ResumableFullSyncRecoverySpec'
```

Expected: the test fails at the injected crash/recovery invariant until the integration path fully handles the commit gap.

- [ ] **Step 4: Make the smallest integration corrections**

Correct only ordering, recovery startup, or test hooks proven wrong by the RED result. Do not add retries beyond deterministic ledger replay and actor ask timeout behavior. Do not weaken exact row, ledger, execution-ID, or terminal-status assertions.

- [ ] **Step 5: Run the recovery test twice**

Run:

```bash
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.integration.ResumableFullSyncRecoverySpec'
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.integration.ResumableFullSyncRecoverySpec'
```

Expected: both runs pass without port, database, or actor-system leakage.

- [ ] **Step 6: Review integration-only changes**

Run `git diff --check`; confirm the test never resolves or connects to Tailscale endpoints. Do not commit.

---

### Task 7: Regression Verification and Final Review

**Files:**
- Modify only files required by a failure that is directly caused by Tasks 1 through 6.

**Interfaces:**
- Consumes: completed implementation and all focused test evidence.
- Produces: final evidence report with explicit limitations.

- [ ] **Step 1: Compile production and test code**

Run:

```bash
sbt 'pekko-server/compile' 'pekko-server/Test/compile'
```

Expected: both commands succeed.

- [ ] **Step 2: Run the new focused suite**

Run:

```bash
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.checkpoint.CheckpointModelsSpec cn.xuyinyin.magic.workflow.nodes.sources.MySQLSnapshotSourceNodeSpec cn.xuyinyin.magic.workflow.nodes.sinks.MySQLSinkNodeSpec cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngineSpec cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActorSpec cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActorRecoverySpec cn.xuyinyin.magic.workflow.integration.ResumableFullSyncRecoverySpec'
```

Expected: every listed test passes.

- [ ] **Step 3: Run reliable multi-node regression suites**

Run:

```bash
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.integration.TwoNodeWorkflowRecoverySpec cn.xuyinyin.magic.workflow.integration.SchedulerFailoverSpec cn.xuyinyin.magic.workflow.scheduler.SchedulerCoordinatorRecoverySpec cn.xuyinyin.magic.config.JdbcPersistenceConfigSpec'
```

Expected: every listed suite passes. Do not claim the repository-wide ordinary suite passes unless it is separately run and green; known legacy cluster/performance failures remain outside this phase.

- [ ] **Step 4: Verify schemas and forbidden external access**

Inspect the schema resources and test tags. Confirm no test command removed the `ExternalIntegration` exclusion and no connection was attempted to the existing external MySQL host.

- [ ] **Step 5: Review the final diff**

Run:

```bash
git diff --check
git diff --stat
git status --short
```

Review every changed file against the spec. Preserve `.tasks/` and unrelated user changes. Do not stage or commit.

- [ ] **Step 6: Report evidence and remaining risk**

Report exact commands, exit status, suite/test counts, and any unverified item. Mark real MySQL failover as `evidence_incomplete` until the real MySQL tests are explicitly run, even when Tailscale connectivity is available. Mark any incomplete serializer compatibility or multi-node crash-window evidence as `evidence_incomplete`; do not promote unit or H2 evidence to production MySQL evidence.
