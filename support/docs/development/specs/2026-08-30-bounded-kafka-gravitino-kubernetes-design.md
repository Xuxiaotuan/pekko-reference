# Bounded Kafka, Gravitino, and Kubernetes Design

**Date:** 2026-08-30
**Status:** Complete for the scoped reliable multi-node bounded Kafka-to-MySQL MVP path; corrective Cluster Bootstrap and deterministic takeover verified
**Repository:** `/Users/xujiawei/magic/scala-workbench/pekko-reference`

## 1. Outcome

Deliver one reliable, bounded Kafka-to-MySQL workflow vertical slice on the
existing `bigdata-lab` Kubernetes namespace:

1. resolve Kafka topic metadata from Gravitino or direct configuration;
2. freeze a finite Kafka offset boundary for each workflow execution;
3. consume deterministic batches through Pekko Streams;
4. commit target rows and the batch ledger in one MySQL transaction;
5. persist the aggregate Kafka cursor through the event-sourced workflow
   entity only after the sink transaction commits;
6. recover the same execution after a Pekko process or node failure; and
7. carry the last committed Kafka cursor into later scheduled executions.

The result remains an orchestrated, finite workflow run. It is not a permanent
streaming job and it does not add MySQL Binlog CDC.

## 2. Verified Environment

The following facts were verified read-only on 2026-08-30:

| Component | Version/state | In-cluster address | External diagnostic port |
|---|---|---|---|
| Kafka | Apache Kafka 4.0.0, one running broker | `kafka:9092` | NodePort `30226` |
| MySQL | MySQL 8, one running pod | `mysql:3306` | NodePort `32372` |
| Gravitino | Apache Gravitino 1.1.0, ready, zero restarts | `gravitino:8090` | NodePort `32343` |
| Pekko Workflow | not deployed | none | none |

Kafka currently advertises `PLAINTEXT://kafka:9092`. This is correct for a
consumer deployed in `bigdata-lab`; a client using the NodePort from outside
the cluster cannot use broker metadata without an additional external
listener. The deployment therefore runs Pekko inside the namespace.

Gravitino currently returns an empty metalake list. The design creates only
the dedicated metadata objects named in this document.

## 3. Scope

### 3.1 Included

- Replace the static `KafkaSource` messages with a real Kafka consumer.
- Use Apache Pekko Connectors Kafka 1.1.0, matching the existing
  `pekkoConnectorsVer` in `build.sbt`.
- Support every partition of one Kafka topic per source node.
- Support direct broker configuration and Gravitino topic resolution.
- Store Kafka offsets outside Kafka in the event-sourced workflow state.
- Preserve the existing checkpoint-aware MySQL sink transaction and ledger.
- Persist workflow-level Kafka progress across completed or failed runs.
- Deploy two Pekko pods to `bigdata-lab` with stable seed-node DNS.
- Bootstrap one Gravitino metalake and Kafka catalog without overwriting
  pre-existing metadata.
- Run real Kafka, MySQL, Gravitino, and Pekko process-failure acceptance tests.
- Prevent database passwords from being persisted in workflow JSON by adding
  environment-variable password references to MySQL node configuration.

### 3.2 Excluded

- Permanent, unbounded Kafka stream executions.
- Kafka producer nodes.
- Kafka broker failover or replication changes.
- MySQL pod failover or storage redesign.
- MySQL Binlog CDC, Debezium, Kafka Connect, schema evolution, or DDL capture.
- Cross-database exactly-once guarantees.
- Exactly-once behavior for sinks that do not implement
  `CheckpointedNodeSink`.
- Gravitino as a credential store or runtime offset store.
- Changes to existing Flink, Spark, DolphinScheduler, SeaTunnel, StreamPark,
  Kafka, MySQL, or Gravitino deployments.

## 4. Architecture

```text
                    control plane
  Workflow definition ────────> Gravitino 1.1.0
          │                    pekko / bigdata-kafka / topic
          │                                  │
          │ resolve once before boundary     │
          └──────────────────────────────────┘
                          │
                          v
  Scheduler Singleton -> Sharded EventSourcedWorkflowActor
                          │
                          │ reliable run context
                          v
               BoundedKafkaSourceNode
               - manual assignment
               - frozen end offsets
               - deterministic partition order
                          │ SourceBatch
                          v
                    Transform*
                          │
                          v
           MySQLSinkNode + pekko_sync_batch_ledger
                          │ one MySQL transaction
                          v
              Actor checkpoint acknowledgement

  Durable state: MySQL journal + MySQL snapshots + workflow source progress
```

Gravitino is used only before a new boundary is frozen. The resolved topic and
bootstrap servers are copied into the durable boundary. Recovery of an
already-started execution does not require Gravitino.

## 5. Kafka Node Contract

The node type remains `kafka.consumer` so stored workflow definitions do not
need a type migration. The static demo implementation is replaced in place.

### 5.1 Direct mode

```json
{
  "topic": "pekko-workflow-e2e",
  "brokers": "kafka:9092",
  "offsetReset": "earliest",
  "chunkSize": 10,
  "maxRecords": 50,
  "maxDurationSeconds": 120
}
```

### 5.2 Gravitino mode

```json
{
  "topic": "pekko-workflow-e2e",
  "gravitino": {
    "uri": "http://gravitino:8090",
    "metalake": "pekko",
    "catalog": "bigdata-kafka"
  },
  "offsetReset": "earliest",
  "chunkSize": 10,
  "maxRecords": 50,
  "maxDurationSeconds": 120
}
```

Configuration rules:

- `topic`, `chunkSize`, `maxRecords`, and `maxDurationSeconds` are required.
- Exactly one of `brokers` or `gravitino` is required.
- `offsetReset` is `earliest` or `latest`; the default is `earliest`.
- All counts and durations must be positive integers.
- Kafka auto commit is always disabled and cannot be enabled per workflow.
- The consumer group identifier is derived from the workflow source node ID
  for client metrics only. Group coordination and Kafka committed offsets are
  not used for recovery.
- The emitted stream element is the Kafka record value as a UTF-8 string.
- Null Kafka values fail the batch with a node-attributed error; tombstone/CDC
  semantics are deferred to the CDC design.

## 6. Gravitino Resolution

`GravitinoTopicResolver` performs two read-only calls:

1. load catalog
   `/api/metalakes/{metalake}/catalogs/{catalog}`;
2. load topic
   `/api/metalakes/{metalake}/catalogs/{catalog}/schemas/default/topics/{topic}`.

The resolver verifies that:

- the catalog type is `messaging`;
- the provider is `kafka`;
- `bootstrap.servers` is present and non-empty; and
- the requested topic exists.

The resolver returns `ResolvedKafkaTopic(topic, bootstrapServers)`. It never
returns or stores Kafka credentials. A Gravitino timeout, malformed response,
wrong provider, missing broker property, or missing topic fails boundary
discovery before any sink write occurs.

Direct mode bypasses Gravitino and is retained for isolated tests and recovery
operations. The `bigdata-lab` acceptance workflow uses Gravitino mode.

## 7. Boundary and Cursor Encoding

The existing `SnapshotBoundary` and `SourceCursor` types remain binary and
source compatible. Kafka stores versioned canonical JSON strings inside their
existing string fields.

`SnapshotBoundary.partitionId` is the prefix `kafka:` followed by the exact
topic name (for example, `kafka:pekko-workflow-e2e`).

The boundary `upperBound` JSON contains:

```json
{
  "version": 1,
  "topic": "pekko-workflow-e2e",
  "bootstrapServers": "kafka:9092",
  "deadlineEpochMillis": 1788076800000,
  "partitions": [
    {"partition": 0, "startOffset": 0, "endOffset": 20},
    {"partition": 1, "startOffset": 0, "endOffset": 20},
    {"partition": 2, "startOffset": 0, "endOffset": 10}
  ]
}
```

Partitions are sorted numerically. `startOffset` is selected from previous
workflow progress when present; otherwise it is the broker beginning or end
offset according to `offsetReset`. `endOffset` is frozen once for the
execution. Records appended after the boundary belong to the next run.

The cursor `value` JSON contains:

```json
{
  "version": 1,
  "nextOffsets": {"0": 10, "1": 0, "2": 0},
  "recordsConsumed": 10
}
```

`SourceCursor.upperBound` contains the exact canonical boundary string.
Unknown versions, missing partitions, negative offsets, offsets beyond the
frozen end, or non-canonical duplicate partition entries are rejected.

Recovery inside the same execution reuses the persisted boundary and retains
`recordsConsumed`. A later execution copies only the previous `nextOffsets`
into its new boundary, resets `recordsConsumed` to zero, and continues with
the next globally valid batch sequence. This makes `maxRecords` a per-run
limit while preserving Actor sequence validation across scheduled runs.

## 8. Deterministic Batching

The source uses `Consumer.plainSource` with manual partition assignment and
explicit starting offsets. Auto commit is disabled.

Partitions are concatenated in ascending partition order. Each partition is
read only until its frozen end offset, after which its consumer source is
closed before the next partition begins. The combined record stream is then:

1. limited by the persisted execution deadline;
2. limited by the remaining `maxRecords` count; and
3. grouped into `chunkSize` batches.

For each batch, the source advances an aggregate `nextOffsets` map and emits a
single `SourceBatch`. Batch sequence numbers are global for the source and
strictly increase. This produces the same records, ordering, cursor, and batch
identity when the same execution restarts before its checkpoint is accepted.

The source closes the Kafka consumer control on normal completion,
cancellation, timeout, and failure. Tests must prove consumer materialization
does not remain alive after the source terminates.

## 9. Commit and Recovery Ordering

The existing reliable engine ordering remains mandatory:

1. transform all rows in one `SourceBatch`;
2. call `MySQLSinkNode.commitBatch`;
3. commit target rows and `pekko_sync_batch_ledger` in one transaction;
4. return `Committed` or matching `AlreadyCommitted`;
5. persist `ExecutionCheckpointAdvanced` in the workflow actor; and
6. request the next Kafka batch.

Failure cases:

- Before the MySQL transaction commits: the batch is retried.
- After the MySQL transaction commits but before the Actor checkpoint: the
  same execution recreates the batch; the ledger returns `AlreadyCommitted`.
- After the Actor checkpoint: recovery starts at the persisted next offsets.
- Gravitino fails after boundary persistence: recovery uses the boundary and
  does not call Gravitino.
- Kafka is unavailable: the execution fails with a source-attributed error;
  no checkpoint advances.
- The frozen offset has expired from Kafka retention: the source fails rather
  than silently jumping to a newer offset.

## 10. Workflow-Level Kafka Progress

Execution-only checkpoints are insufficient for scheduled micro-batches
because the current actor clears `currentExecution` at a terminal event.

Add a versioned `workflowSourceProgress` field to `WorkflowState` containing:

- workflow revision;
- source node ID;
- source node type;
- latest `BatchCheckpoint`; and
- progress scope `workflow`.

No new journal event type is introduced. On the existing
`ResumableExecutionStarted` event, the event handler identifies a
`kafka.consumer` source from the persisted workflow definition and seeds the
new `ExecutionState` from compatible progress. Progress is compatible only
when workflow revision, source node ID, and source node type match.

On the existing `ExecutionCheckpointAdvanced` event, a checkpoint whose
cursor kind is the versioned Kafka-offset kind updates both the current
execution and `workflowSourceProgress`. This is deliberate: already committed
MySQL batches must not be replayed under a new execution ID merely because a
later Kafka batch failed. A later execution starts from the latest accepted
checkpoint whether the previous run completed or failed.

Defining a new workflow revision clears workflow progress. The new revision
therefore applies its configured `offsetReset` policy unless the user restores
progress through a future explicit migration feature. No automatic cross-topic
or cross-revision cursor reuse is allowed.

Existing MySQL Snapshot executions continue using execution-only checkpoints
and full snapshot boundaries exactly as before.

## 11. Secret Handling

The current MySQL node configuration accepts an inline password, which would
be persisted with workflow JSON. The new deployment must not use that path.

MySQL source and sink configuration add `passwordEnv`. Exactly one of
`password` and `passwordEnv` is accepted. `passwordEnv` names an environment
variable and resolves it at node setup time. Missing or empty variables fail
before a JDBC connection is opened. Error messages include the variable name
but never the secret value.

The `bigdata-lab` workflow uses:

```json
{"passwordEnv": "WORKFLOW_DB_PASSWORD"}
```

Kubernetes injects that variable from Secret `pekko-workflow-db`. The Secret
also supplies persistence `DB_PASSWORD`. No password is stored in Git,
ConfigMap, Gravitino, workflow JSON, test output, or application logs.

Kafka and Gravitino use namespace-local plaintext endpoints in this MVP. SASL,
TLS, and external ingress are outside this phase.

## 12. Kubernetes Deployment

All new resources use the `bigdata-lab` namespace and the `pekko-workflow`
prefix:

| Resource | Name | Purpose |
|---|---|---|
| Headless Service | `pekko-workflow-headless` | stable Artery DNS |
| ClusterIP Service | `pekko-workflow-api` | HTTP API and probes |
| ConfigMap | `pekko-workflow-config` | `application-k8s.conf` |
| Secret reference | `pekko-workflow-db` | database user/password |
| StatefulSet | `pekko-workflow` | two identical Pekko nodes |
| PodDisruptionBudget | `pekko-workflow` | keep at least one pod available |
| ServiceAccount | `pekko-workflow` | identity for Kubernetes discovery |
| Role/RoleBinding | `pekko-workflow-pod-reader` | namespace-scoped Pod discovery |

The StatefulSet has two replicas. Both pods carry `coordinator`, `worker`, and
`api-gateway` roles. Kubernetes does not use static seed nodes. Every pod
starts Pekko Management 1.1.1 and Cluster Bootstrap, discovers
`app=pekko-workflow` pods through the Kubernetes API, and probes the named
`management` port on 7626. The application uses Pekko Core 1.1.3 and Pekko
HTTP 1.1.0, matching Management 1.1.1's published dependency line.

Bootstrap requires two stable contact points before initial formation and
retains the default 5-second stable margin. Static `seed-nodes` resolve to an
empty list in `application-k8s.conf`; production and development profiles
outside Kubernetes retain their static-seed behavior. A dedicated
ServiceAccount receives only `get`, `list`, and `watch` on Pods in
`bigdata-lab`. Management port 7626 is internal and is not exposed through an
Ingress or public Service.

A fresh read-only authorization check at `2026-08-30 21:46:19 +08` verified
that `system:serviceaccount:bigdata-lab:pekko-workflow` can `get=yes`,
`list=yes`, and `watch=yes` Pods in namespace `bigdata-lab`.

`pekko.cluster.min-nr-of-members` is 2. Pod anti-affinity places the replicas
on different Kubernetes nodes when both `xjw` and `xxt` are schedulable.
Liveness uses `/health/live`; readiness uses `/health/ready`.

This two-node topology is a functional multi-node MVP, not symmetric HA.
`keep-majority` cannot preserve service on both sides of a 1-1 partition, and
`required-contact-point-nr = 2` intentionally prevents a one-pod cold start.
No network-partition-safety claim is made.

The API Service remains ClusterIP. Acceptance uses `kubectl port-forward`, so
the implementation does not allocate another public NodePort.

The final image tag is `pekko-reference:cluster-bootstrap-20260830` with
`imagePullPolicy: Never`. The image is built for Linux AMD64 and imported into
the Kubernetes container runtime on both `xjw` and `xxt`. The earlier
`pekko-reference:bounded-kafka-mvp` tag is only the historical migration source
for the replaced static-seed pods, not the final accepted image. No public
registry, repository push, or external publication is part of this work.

Both accepted pods carry controller revision hash
`pekko-workflow-6c546f757b`. With the `OnDelete` strategy, the StatefulSet
status at final evidence collection was
`currentRevision=pekko-workflow-6548d67c4b`,
`updateRevision=pekko-workflow-6c546f757b`, and `readyReplicas=2`; therefore the
pod hash is the accepted revision evidence, without incorrectly claiming that
`status.currentRevision` had already converged.

No persistent volume is attached to Pekko pods. Durable state remains in
MySQL.

## 13. Database Bootstrap

Create the dedicated database and application account:

- database: `pekko_workflow`;
- user: `pekko_workflow` with access only to that database;
- schema: existing Pekko persistence schema plus
  `pekko_sync_batch_ledger`; and
- acceptance table: `pekko_kafka_e2e_sink`.

Bootstrap is idempotent and does not alter other databases or users. Root
credentials are consumed only inside the existing MySQL pod through its
environment and are never printed or copied to the repository.

The application account receives only the DDL/DML privileges required for its
database. The acceptance table uses a message identifier as its primary key so
the final duplicate check is independent of row ordering.

## 14. Gravitino Bootstrap

Create only the following metadata when absent:

- metalake `pekko`;
- messaging catalog `bigdata-kafka`;
- provider `kafka`;
- property `bootstrap.servers=kafka:9092`.

Bootstrap performs GET-before-create. If an object with the expected name
already exists but its type, provider, or bootstrap servers differ, bootstrap
fails without updating or deleting it.

The application never creates or deletes topics through Gravitino. The
acceptance topic is created through Kafka administration, after which
Gravitino must list and load it through the catalog.

## 15. Acceptance Resources and Data

The real-environment acceptance test creates:

- Kafka topic `pekko-workflow-e2e`;
- 3 partitions;
- replication factor 1, matching the single broker;
- 50 JSON messages with unique IDs `event-0001` through `event-0050`;
- workflow ID `kafka-gravitino-e2e`; and
- MySQL table `pekko_kafka_e2e_sink`.

The workflow uses Gravitino mode, `chunkSize=10`, `maxRecords=50`, and
`maxDurationSeconds=120`.

## 16. Verification Matrix

### 16.1 Unit and focused integration

- Configuration accepts exactly one connection mode.
- Invalid counts, reset policy, and Gravitino identifiers fail precisely.
- Boundary/cursor canonical JSON round-trips.
- Duplicate, missing, negative, and out-of-bound offsets fail.
- Multi-partition batching is deterministic.
- `maxRecords`, deadline, and frozen offsets stop the source.
- Consumer control terminates on completion and cancellation.
- Workflow progress advances on every accepted Kafka checkpoint.
- Failed and completed executions both seed the next Kafka run.
- Workflow revision changes clear progress.
- MySQL Snapshot does not acquire cross-execution progress.
- `passwordEnv` resolves without logging or serializing the value.
- Gravitino is not called when a persisted boundary is reused.

### 16.2 Real services

1. Verify Gravitino loads `bigdata-kafka` and lists
   `pekko-workflow-e2e`.
2. Verify both Pekko pods form one cluster and pass readiness.
3. Submit and run `kafka-gravitino-e2e`.
4. Verify 50 target rows and five matching ledger batches.
5. Append 12 new messages and run again.
6. Verify exactly 12 new target rows and no old-row duplication.

### 16.3 Cross-process failure

1. Refill an isolated execution with enough messages to span multiple
   batches.
2. Identify the Pekko pod hosting the workflow entity from node-attributed
   execution logs.
3. Wait until at least two batch checkpoints exist.
4. Delete only that `pekko-workflow-*` pod.
5. Verify the remaining pod recovers the entity from MySQL and continues from
   the accepted Kafka cursor.
6. Verify target IDs are complete and unique and ledger batches have no
   conflicting checkpoint.
7. Verify the StatefulSet recreates the deleted pod and the cluster returns to
   two ready members.

The test never deletes or restarts Kafka, MySQL, Gravitino, or unrelated pods.
If the first Pekko recovery attempt fails, the test stops and preserves logs;
it does not delete the second Pekko pod.

## 17. Evidence Levels

The overall reliable multi-node MVP status is complete for the scoped bounded
Kafka-to-MySQL path. Historical intermediate `evidence_incomplete` results are
preserved in the Task 10/11 reports as stop and handoff evidence, not as the
final status.

Completion claims must remain separated:

- compile evidence: production and test code compile;
- focused test evidence: codec, source, actor progress, sink, and resolver
  tests pass;
- local integration evidence: connector behavior against controlled services;
- real-service evidence: actual `bigdata-lab` Kafka, MySQL, and Gravitino;
- fault-recovery evidence: deletion of one new Pekko pod and recovery on the
  remaining pod; and
- excluded evidence: no claim of Kafka broker HA, MySQL HA, network-partition
  safety, or CDC.

Any missing required layer is reported as `evidence_incomplete`. An external
service or permission preventing a required check is `external_blocked`.

## 18. Rollback and Safety

The design adds no journal event type, so journal replay does not acquire an
unknown event manifest. The new `WorkflowState` field has a default so the new
binary reads existing snapshots. Before claiming that an older binary can
read snapshots written by the new binary, an explicit backward snapshot test
must pass. Until that evidence exists, operational recovery uses a forward
fix or the new binary with Kafka workflows paused; automatic rollback to the
old image after Kafka state has been snapshotted is not claimed safe.

Cluster rollback removes only resources with the `pekko-workflow` prefix.
Gravitino metadata and the acceptance topic are retained unless deletion is
separately authorized. Database and user deletion are also separate,
destructive actions and are not part of automatic rollback.

The implementation must inspect the final Git diff and must not stage or
modify the existing untracked `.tasks/` directory.

## 19. Delivery Sequence

1. Add failing tests for configuration, codec, deterministic batching, and
   workflow-scope progress.
2. Implement the bounded Kafka source and Gravitino resolver.
3. Add password environment references and their tests.
4. Add Kubernetes manifests and idempotent bootstrap scripts.
5. Run compile, focused tests, and the broader test suite.
6. Build and import the Linux AMD64 image on both Kubernetes nodes.
7. Bootstrap the dedicated MySQL database and Gravitino metadata.
8. Deploy two Pekko pods and run real-service acceptance.
9. Run the scoped single-Pekko-pod failure test.
10. Report observed evidence and remaining limits without upgrading claims.

MySQL Binlog CDC begins only after this sequence passes. It receives a
separate design because its source boundary is a snapshot/binlog handoff and
its event semantics include updates, deletes, transaction ordering, schema
change, and retention gaps.
