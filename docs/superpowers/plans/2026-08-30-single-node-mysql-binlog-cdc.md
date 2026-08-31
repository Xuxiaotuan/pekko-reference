# Single-Node MySQL Snapshot and Binlog CDC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a real single-reader MySQL-to-MySQL mirror that performs an initial consistent snapshot, continues from MySQL ROW binlog, applies insert/update/delete events idempotently, and resumes from durable MySQL state.

**Architecture:** Embed Debezium 3.6.1.Final inside a new checkpoint-aware `mysql.cdc` source. Bridge each ordered Debezium callback batch into the existing reliable Pekko pipeline with one in-flight batch, commit target changes and the ledger transaction first, persist the actor checkpoint second, and acknowledge Debezium last. A dedicated `mysql.cdc.apply` sink interprets canonical CDC envelopes and makes replay safe through full-row upsert and primary-key delete operations.

**Tech Stack:** Scala 2.13.12, Java 17, Apache Pekko 1.1.3, Pekko Streams, Pekko Persistence JDBC, Debezium Engine/MySQL/JDBC Storage 3.6.1.Final, MySQL 8.4, ScalaTest 3.2.19, SBT Native Packager, Kubernetes 1.23.

**Spec:** `docs/superpowers/specs/2026-08-30-single-node-mysql-binlog-cdc-design.md`

## Global Constraints

- Keep Debezium pinned to `3.6.1.Final`; do not use a 3.7 preview build.
- Upgrade build and runtime to Java 17; run every SBT command with `JAVA_HOME=$(/usr/libexec/java_home -v 17)` on macOS.
- Implement exactly one source table and one target table with exactly one matching primary-key column.
- Preserve ordered processing with one in-flight Debezium callback batch.
- Commit target mutations and `pekko_sync_batch_ledger` atomically before actor checkpoint persistence.
- Acknowledge Debezium only after sink commit and actor checkpoint success.
- Treat delivery as at-least-once; prove final-state idempotency and do not claim exactly-once.
- Keep `mysql.write`, `mysql.snapshot`, and bounded Kafka behavior backward compatible.
- Store only `passwordEnv` names in workflow JSON. Never print or persist credential values.
- Do not enable GTID, restart MySQL, modify shared business tables, or scale down the existing two-pod Pekko StatefulSet.
- Keep `.tasks/` and unrelated dirty files untouched.
- Do not stage or commit unless the user grants a separate explicit commit authorization. Conditional commit checkpoints below are documentation, not current authorization.

## File Structure

### New production files

- `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/cdc/MySQLCdcEnvelope.scala` — versioned canonical CDC event model and codec.
- `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLCdcSourceConfig.scala` — workflow-node configuration parser and secret resolution.
- `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLCdcStateConfig.scala` — application-level JDBC offset/schema-history configuration.
- `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/DebeziumBatchBridge.scala` — bounded one-batch callback-to-stream bridge.
- `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/DebeziumEngineAccess.scala` — narrow engine lifecycle/committer boundary and real Debezium adapter.
- `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLCdcSourceNode.scala` — `mysql.cdc` checkpointed source.
- `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sinks/MySQLCdcApplyConfig.scala` — CDC apply sink configuration.
- `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sinks/MySQLCdcApplySinkNode.scala` — ordered transactional upsert/delete sink.
- `pekko-server/src/main/resources/db/mysql/pekko-cdc-schema.sql` — Debezium JDBC state and isolated acceptance tables.

### New test files

- `pekko-server/src/test/scala/cn/xuyinyin/magic/config/ConfigValidatorSpec.scala`
- `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/cdc/MySQLCdcEnvelopeSpec.scala`
- `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLCdcSourceConfigSpec.scala`
- `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sources/DebeziumBatchBridgeSpec.scala`
- `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLCdcSourceNodeSpec.scala`
- `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sinks/MySQLCdcApplySinkNodeSpec.scala`
- `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/integration/RealMySQLCdcProcess.scala`
- `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/integration/RealMySQLCdcRecoverySpec.scala`

### Modified production/configuration files

- `build.sbt` — Java 17 target, Debezium modules, Docker runtime.
- `pekko-server/src/main/resources/application.conf` — local CDC JDBC state defaults.
- `pekko-server/src/main/resources/application-prod.conf` — production CDC JDBC state environment overrides.
- `pekko-server/src/main/scala/cn/xuyinyin/magic/config/ConfigValidator.scala` — validate CDC JDBC state configuration.
- `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/base/CheckpointedNodes.scala` — default post-commit source acknowledgement.
- `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/WorkflowExecutionEngine.scala` — enforce sink/checkpoint/source-ack ordering and resolve CDC secrets.
- `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/WorkflowValidator.scala` — enforce direct CDC source-to-apply-sink and no schedule.
- `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/registry/NodeRegistry.scala` — register source and sink.
- `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/WorkflowFixtures.scala` — reusable CDC workflows.
- `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/engine/WorkflowExecutionEngineSpec.scala` — acknowledgement ordering and failure attribution.
- `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/engine/WorkflowValidatorSpec.scala` — CDC topology/lifecycle validation.
- `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/actors/EventSourcedWorkflowActorRecoverySpec.scala` — unbounded reliable execution recovery selection.
- `deploy/k8s/bigdata-lab/bootstrap-mysql.sh` — idempotent CDC schema/account/Secret bootstrap.
- `deploy/k8s/bigdata-lab/README.md` — CDC bootstrap and acceptance commands.

### New deployment/acceptance files

- `deploy/k8s/bigdata-lab/cdc-single/application-cdc-single.conf`
- `deploy/k8s/bigdata-lab/cdc-single/headless-service.yaml`
- `deploy/k8s/bigdata-lab/cdc-single/api-service.yaml`
- `deploy/k8s/bigdata-lab/cdc-single/statefulset.yaml`
- `deploy/k8s/bigdata-lab/cdc-single/kustomization.yaml`
- `deploy/k8s/bigdata-lab/run-cdc-e2e.sh`

---

### Task 1: Java 17 and Debezium Dependency Baseline

**Files:**
- Modify: `build.sbt:18-19, 28-50, 151-175, 180-201, 252-257`
- Create: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sources/DebeziumDependencySpec.scala`

**Interfaces:**
- Consumes: existing SBT `pekko-server` project and Docker packaging.
- Produces: `debeziumVersion = "3.6.1.Final"`; loadable `DebeziumEngine`, `MySqlConnector`, `JdbcOffsetBackingStore`, and `JdbcSchemaHistory` classes under Java 17.

- [ ] **Step 1: Record the current Java 11 failure boundary**

Run:

```bash
java -version
JAVA_HOME=$(/usr/libexec/java_home -v 17) sbt 'show pekko-server/Compile/javaHome' 'pekko-server/Test/compile'
```

Expected: the first command reports the current Java 11 default; the explicit Java 17 compile establishes the pre-change baseline without modifying files.

- [ ] **Step 2: Write the dependency smoke test before adding dependencies**

Create a ScalaTest that references the required classes:

```scala
class DebeziumDependencySpec extends STSpec {
  "the CDC runtime" should {
    "load the embedded engine, MySQL connector, and JDBC stores" in {
      Class.forName("io.debezium.engine.DebeziumEngine") should not be null
      Class.forName("io.debezium.connector.mysql.MySqlConnector") should not be null
      Class.forName("io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore") should not be null
      Class.forName("io.debezium.storage.jdbc.history.JdbcSchemaHistory") should not be null
    }
  }
}
```

- [ ] **Step 3: Run the test and verify RED**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.DebeziumDependencySpec'
```

Expected: test compilation fails because Debezium classes are not on the classpath.

- [ ] **Step 4: Add the pinned dependencies and Java 17 target**

Add:

```scala
val debeziumVersion = "3.6.1.Final"

Compile / javacOptions ++= Seq("-source", "17", "-target", "17")
Compile / scalacOptions += "-release:17"

"io.debezium" % "debezium-api"             % debeziumVersion,
"io.debezium" % "debezium-embedded"        % debeziumVersion,
"io.debezium" % "debezium-connector-mysql" % debeziumVersion,
"io.debezium" % "debezium-storage-jdbc"    % debeziumVersion,
```

Change the Docker base image to:

```scala
dockerBaseImage := "eclipse-temurin:17-jre-alpine"
```

Remove the old Java 11 `-source` and `-target` pair rather than leaving conflicting compiler options.

- [ ] **Step 5: Run GREEN checks and inspect convergence**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) sbt \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.DebeziumDependencySpec' \
  'pekko-server/dependencyTree'
```

Expected: the smoke test passes; the tree contains one `3.6.1.Final` Debezium line and no evicted Debezium version. Capture any Kafka Connect/Jackson eviction before proceeding; do not add blind exclusions.

- [ ] **Step 6: Conditional commit checkpoint**

Only if explicit commit authorization is granted:

```bash
git add build.sbt pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sources/DebeziumDependencySpec.scala
git commit -m "build: add Java 17 Debezium CDC runtime"
```

Otherwise leave the files unstaged and record the completed checks in the working report.

### Task 2: Durable CDC State and Node Configuration

**Files:**
- Create: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLCdcSourceConfig.scala`
- Create: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLCdcStateConfig.scala`
- Create: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLCdcSourceConfigSpec.scala`
- Create: `pekko-server/src/test/scala/cn/xuyinyin/magic/config/ConfigValidatorSpec.scala`
- Modify: `pekko-server/src/main/resources/application.conf:99-114`
- Modify: `pekko-server/src/main/resources/application-prod.conf:99-117,134-148`
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/config/ConfigValidator.scala:24-49,200-222`

**Interfaces:**
- Consumes: `WorkflowDSL.Node`, `JdbcPasswordResolver`, Typesafe `Config`.
- Produces: `MySQLCdcSourceConfig.parse(node, getenv)`, `MySQLCdcStateConfig.load(config)`, and validated `pekko.workflow.mysql-cdc.state-jdbc` settings.

- [ ] **Step 1: Write failing source-config tests**

Cover the exact valid value:

```scala
MySQLCdcSourceConfig.parse(validNode, name => Option.when(name == "MYSQL_CDC_PASSWORD")("secret")) shouldBe
  MySQLCdcSourceConfig(
    connectorId = "orders-cdc-v1",
    host = "mysql",
    port = 3306,
    database = "pekko_workflow",
    table = "source_orders",
    username = "pekko_cdc",
    password = "secret",
    serverId = 54001L,
    maxBatchSize = 100,
    pollIntervalMillis = 500
  )
```

Add independent cases for missing `connectorId`, unsafe identifiers, invalid connector ID, absent environment value, simultaneous `password` and `passwordEnv`, server ID `0`, server ID above `4294967295`, non-positive batch size, and non-positive poll interval. Assert exception messages never contain the resolved password.

- [ ] **Step 2: Run source-config tests and verify RED**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.MySQLCdcSourceConfigSpec'
```

Expected: compilation fails because `MySQLCdcSourceConfig` does not exist.

- [ ] **Step 3: Implement the minimal immutable source config parser**

Define:

```scala
final case class MySQLCdcSourceConfig(
  connectorId: String,
  host: String,
  port: Int,
  database: String,
  table: String,
  username: String,
  password: String,
  serverId: Long,
  maxBatchSize: Int,
  pollIntervalMillis: Int
)

object MySQLCdcSourceConfig {
  def parse(
    node: WorkflowDSL.Node,
    getenv: String => Option[String] = sys.env.get
  ): MySQLCdcSourceConfig
}
```

Reuse `JdbcPasswordResolver`; validate SQL identifiers with the same conservative identifier pattern used by `MySQLSnapshotSourceConfig`. Do not log the case class because it contains the resolved password.

- [ ] **Step 4: Write failing application-state tests**

The valid config must load as:

```scala
MySQLCdcStateConfig(
  jdbcUrl = "jdbc:mysql://mysql:3306/pekko_workflow",
  username = "pekko_workflow",
  password = "workflow-secret",
  offsetTable = "debezium_offset_storage",
  historyTable = "debezium_database_history",
  offsetFlushIntervalMillis = 0
)
```

Add failures for missing/blank URL, non-JDBC URL, missing username/password, unsafe table names, a negative offset flush interval, and an H2 URL in production CDC state. Verify the error list excludes password values.

- [ ] **Step 5: Run application-state tests and verify RED**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) sbt 'pekko-server/testOnly cn.xuyinyin.magic.config.ConfigValidatorSpec'
```

Expected: compilation fails because the CDC state model and validation path do not exist.

- [ ] **Step 6: Add state configuration and validation**

Add to production configuration:

```hocon
pekko.workflow.mysql-cdc.state-jdbc {
  url = "jdbc:mysql://"${DB_HOST}":"${DB_PORT}"/"${DB_NAME}
  username = ${DB_USER}
  password = ${DB_PASSWORD}
  offset-table = "debezium_offset_storage"
  history-table = "debezium_database_history"
  offset-flush-interval-ms = 0
  offset-flush-interval-ms = ${?CDC_OFFSET_FLUSH_INTERVAL_MS}
}
```

Local `application.conf` uses a file-backed H2 value only for non-external unit tests; real CDC startup must reject it. `ConfigValidator` validates the block only when `pekko.workflow.mysql-cdc.enabled=true`, so legacy test configurations do not become invalid merely by lacking CDC state.

- [ ] **Step 7: Run GREEN tests**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) sbt \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.MySQLCdcSourceConfigSpec' \
  'pekko-server/testOnly cn.xuyinyin.magic.config.ConfigValidatorSpec'
```

Expected: all cases pass and no output contains the fixture secret.

- [ ] **Step 8: Conditional commit checkpoint**

Only with explicit commit authorization:

```bash
git add pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLCdcSourceConfig.scala \
  pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLCdcStateConfig.scala \
  pekko-server/src/main/scala/cn/xuyinyin/magic/config/ConfigValidator.scala \
  pekko-server/src/main/resources/application.conf \
  pekko-server/src/main/resources/application-prod.conf \
  pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLCdcSourceConfigSpec.scala \
  pekko-server/src/test/scala/cn/xuyinyin/magic/config/ConfigValidatorSpec.scala
git commit -m "feat: validate durable MySQL CDC configuration"
```

### Task 3: Canonical CDC Envelope and Type Boundary

**Files:**
- Create: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/cdc/MySQLCdcEnvelope.scala`
- Create: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/cdc/MySQLCdcEnvelopeSpec.scala`

**Interfaces:**
- Consumes: Debezium/Kafka Connect `SourceRecord`, `Struct`, and schemas.
- Produces: `MySQLCdcEnvelope.decode(record, connectorId): Either[CdcDecodeFailure, Option[MySQLCdcEnvelope]]` and `MySQLCdcEnvelope.parse(json)` with canonical compact JSON output. `None` represents an intentionally filtered tombstone or heartbeat.

- [ ] **Step 1: Write failing operation-envelope tests with real Connect structures**

Build literal Connect schemas and `SourceRecord` values rather than mocking Debezium. Assert exact parsed behavior for `r`, `c`, `u`, and `d`:

```scala
val envelope = MySQLCdcEnvelope.decode(updateRecord, "orders-cdc-v1").value.value
envelope.op shouldBe CdcOperation.Update
envelope.key shouldBe JsObject("id" -> JsNumber(42))
envelope.before.value.fields("status") shouldBe JsString("new")
envelope.after.value.fields("status") shouldBe JsString("paid")
envelope.source.file shouldBe Some("binlog.000012")
envelope.source.position shouldBe Some(2805470L)
```

Name the break each case catches: wrong operation mapping, missing delete key, lost decimal precision, null corruption, reordered fields, tombstone leakage, heartbeat leakage, and truncate acceptance.

- [ ] **Step 2: Run and verify RED**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.cdc.MySQLCdcEnvelopeSpec'
```

Expected: compilation fails because the envelope API is missing.

- [ ] **Step 3: Implement the versioned envelope**

Define the public model:

```scala
sealed trait CdcOperation { def code: String }
object CdcOperation {
  case object Read extends CdcOperation { val code = "r" }
  case object Create extends CdcOperation { val code = "c" }
  case object Update extends CdcOperation { val code = "u" }
  case object Delete extends CdcOperation { val code = "d" }
}

final case class MySQLCdcSourcePosition(
  connectorId: String,
  database: String,
  table: String,
  snapshot: Boolean,
  file: Option[String],
  position: Option[Long],
  row: Option[Int],
  eventTimestampMillis: Option[Long]
)

final case class MySQLCdcEnvelope(
  version: Int,
  op: CdcOperation,
  key: JsObject,
  before: Option[JsObject],
  after: Option[JsObject],
  source: MySQLCdcSourcePosition
) {
  def canonicalJson: String
}
```

Return `None` for tombstones, heartbeats, and schema records through a separate `decode(record): Either[CdcDecodeFailure, Option[MySQLCdcEnvelope]]`. Return `Left` for truncate, malformed operations, absent required images, or unsupported value schemas.

- [ ] **Step 4: Implement lossless supported-value conversion**

Map Connect values to JSON using explicit schema types. Preserve `Decimal` as a plain string, preserve integral values as JSON numbers, encode date/time/timestamp in their Debezium string representation, and fail unsupported bytes/spatial values. Do not fall back to `toString` for unknown types.

- [ ] **Step 5: Run GREEN and mutation checks**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.cdc.MySQLCdcEnvelopeSpec'
```

Then mentally change `u` to `c`, drop `before`, round a decimal, and accept a truncate; identify the exact test that fails for each mutation.

- [ ] **Step 6: Conditional commit checkpoint**

Only with explicit commit authorization:

```bash
git add pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/cdc/MySQLCdcEnvelope.scala \
  pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/cdc/MySQLCdcEnvelopeSpec.scala
git commit -m "feat: define canonical MySQL CDC envelopes"
```

### Task 4: One-Batch Debezium Backpressure Bridge

**Files:**
- Create: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/DebeziumBatchBridge.scala`
- Create: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sources/DebeziumBatchBridgeSpec.scala`

**Interfaces:**
- Consumes: decoded `Vector[String]` envelope rows and a narrow `CdcBatchCommitHandle`.
- Produces: blocking `publish`, stream-side `take`, `acknowledge(batchId)`, `fail(batchId, cause)`, and `close()` behavior with capacity one.

- [ ] **Step 1: Define the test-facing committer boundary and failing concurrency tests**

Use a real worker thread and latches, not assertions on a mock:

```scala
trait CdcBatchCommitHandle {
  def markProcessedAndFinished(): Unit
}

final case class BridgedCdcBatch(
  rows: Vector[String],
  cursorValue: String,
  commitHandle: CdcBatchCommitHandle
)
```

Tests must show:

- `publish` remains blocked after `take` and before `acknowledge`;
- `acknowledge` calls the real fake handle once and releases `publish`;
- a second publish cannot overtake the first;
- `fail` releases the callback without marking records processed;
- `close` releases every waiter and makes later operations fail fast; and
- duplicate acknowledgement does not call the handle twice.

- [ ] **Step 2: Run and verify RED**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.DebeziumBatchBridgeSpec'
```

Expected: compilation fails because bridge types do not exist.

- [ ] **Step 3: Implement the minimal bounded bridge**

Use an `ArrayBlockingQueue` of capacity one and a per-batch `Promise[Unit]`/latch. Keep pending acknowledgement state keyed by batch ID. Do not add multiple in-flight configuration, retry loops, or unordered completion.

The source-side item returned from `take` contains only rows and cursor data; the committer remains private inside the bridge so it cannot accidentally be serialized into `SourceBatch`.

- [ ] **Step 4: Run GREEN and repeat the concurrency test**

Run the suite at least 20 times in one JVM using ScalaTest repetition or an internal table-driven loop. Expected: no timeout, lost release, duplicate callback, or thread leak.

- [ ] **Step 5: Conditional commit checkpoint**

Only with explicit commit authorization:

```bash
git add pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/DebeziumBatchBridge.scala \
  pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sources/DebeziumBatchBridgeSpec.scala
git commit -m "feat: bridge ordered Debezium batches into Pekko"
```

### Task 5: Sink-to-Actor-to-Source Acknowledgement Ordering

**Files:**
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/base/CheckpointedNodes.scala:10-31`
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/WorkflowExecutionEngine.scala:110-197`
- Modify: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/engine/WorkflowExecutionEngineSpec.scala:21-430,569-680`

**Interfaces:**
- Consumes: existing `SourceBatch`, `SnapshotBoundary`, `BatchCommitResult`, and `ReliableRunContext.checkpointCommitted`.
- Produces: backward-compatible `CheckpointedNodeSink.validateSourceBoundary`, `CheckpointedNodeSource.acknowledgeCommittedBatch`, and reliable-engine ordering `sink readiness -> source boundary -> sink boundary validation -> sink commit -> actor checkpoint -> source acknowledge`.

- [ ] **Step 1: Write the failing ordering test**

Extend the reliable fake source, sink, and run context to append literal markers to one `ListBuffer[String]`:

```scala
val order = ListBuffer.empty[String]
ReliableSink.onCommit = _ => order += "sink"
val context = ReliableRunContext(
  "exec-cdc", 1L, Some(boundary), Vector.empty,
  _ => Future.successful(Done),
  _ => { order += "actor"; Future.successful(Done) }
)
ReliableSource.onAcknowledge = _ => { order += "source"; Future.successful(Done) }

Await.result(new WorkflowExecutionEngine().execute(reliableWorkflow(), context, _ => ()), 5.seconds)
order.toVector shouldBe Vector("sink", "actor", "source")
```

Add a second test in which actor checkpoint persistence fails and assert that the source acknowledgement flag stays false. Add a third in which acknowledgement fails and assert the execution failure is attributed to the source node while the sink commit and actor checkpoint each occurred once.

Add a boundary-ordering test whose markers must be:

```scala
Vector("sink-ready", "source-boundary", "sink-boundary", "sink-commit", "actor", "source-ack")
```

When `sink-boundary` fails, assert no source batch is created and no target commit occurs.

- [ ] **Step 2: Run focused engine tests and verify RED**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngineSpec'
```

Expected: the new fake source cannot override the missing acknowledgement method or the expected order lacks `source`.

- [ ] **Step 3: Add the default no-op source hook**

Add exactly this compatibility default:

```scala
def acknowledgeCommittedBatch(
  node: WorkflowDSL.Node,
  batch: SourceBatch,
  onLog: String => Unit
)(implicit blockingEc: ExecutionContext): Future[Done] = Future.successful(Done)
```

Existing MySQL snapshot and Kafka sources inherit it unchanged.

Add this compatibility default to `CheckpointedNodeSink`:

```scala
def validateSourceBoundary(
  node: WorkflowDSL.Node,
  boundary: SnapshotBoundary,
  onLog: String => Unit
)(implicit blockingEc: ExecutionContext): Future[Done] = Future.successful(Done)
```

- [ ] **Step 4: Call acknowledgement only after actor durability**

Pass both `preparedSource` and `CheckpointedNodeSource` into `processBatch`. After `runContext.checkpointCommitted(committed)` succeeds, call:

```scala
nodeFuture(pipeline.source)(
  source.acknowledgeCommittedBatch(preparedSource, batch, onLog)(jdbcBlockingEc)
).map(_ => committed.targetRowsWritten)
```

Do not acknowledge in `recover`, stream completion, sink callbacks, or `finally` blocks.

After boundary discovery/initialization and before `createBatches`, invoke `sink.validateSourceBoundary(preparedSink, boundary, onLog)`. Attribute failure to the sink node. Existing sinks inherit the default no-op.

- [ ] **Step 5: Run GREEN plus source regressions**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) sbt \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngineSpec' \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.MySQLSnapshotSourceNodeSpec' \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.KafkaSourceSpec'
```

Expected: the new ordering cases pass and legacy sources require no changes.

- [ ] **Step 6: Conditional commit checkpoint**

Only with explicit commit authorization:

```bash
git add pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/base/CheckpointedNodes.scala \
  pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/WorkflowExecutionEngine.scala \
  pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/engine/WorkflowExecutionEngineSpec.scala
git commit -m "feat: acknowledge sources after durable checkpoints"
```

### Task 6: Real Debezium Engine Adapter and `mysql.cdc` Source

**Files:**
- Create: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/DebeziumEngineAccess.scala`
- Create: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLCdcSourceNode.scala`
- Create: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLCdcSourceNodeSpec.scala`

**Interfaces:**
- Consumes: `MySQLCdcSourceConfig`, `MySQLCdcStateConfig`, `MySQLCdcEnvelope`, `DebeziumBatchBridge`, and source acknowledgement hook.
- Produces: `MySQLCdcSourceNode.nodeType == "mysql.cdc"`, stable schema-bearing stream boundary, unbounded ordered `SourceBatch` stream, and real Debezium lifecycle.

- [ ] **Step 1: Write failing property-construction tests**

Expose package-private pure configuration construction:

```scala
private[sources] def connectorProperties(
  source: MySQLCdcSourceConfig,
  state: MySQLCdcStateConfig
): Properties
```

Assert literal values for:

```text
name=orders-cdc-v1
connector.class=io.debezium.connector.mysql.MySqlConnector
database.hostname=mysql
database.port=3306
database.server.id=54001
topic.prefix=orders-cdc-v1
database.include.list=pekko_workflow
table.include.list=pekko_workflow.source_orders
snapshot.mode=initial
record.processing.order=ORDERED
record.processing.threads=1
offset.flush.interval.ms=0
decimal.handling.mode=string
include.schema.changes=false
tombstones.on.delete=false
```

Assert the JDBC offset/history class names, URLs, users, passwords, and fixed table names. Verify `properties.toString` is never logged by exercising the node logger with a secret fixture.

- [ ] **Step 2: Write failing source behavior tests through a fake engine boundary**

Define:

```scala
trait DebeziumEngineAccess extends AutoCloseable {
  def start(consumer: DebeziumBatchConsumer): Future[Done]
}

trait DebeziumEngineFactory {
  def create(properties: Properties): DebeziumEngineAccess
}
```

Use a deterministic fake engine that submits one snapshot-read batch, one update/delete batch, and then remains open. Verify:

- boundary equals `SnapshotBoundary(node.id, "mysql-cdc:orders-cdc-v1", Some(streamIdentity))`;
- boundary discovery validates exactly one source primary key and supported source column types;
- stream identity includes the source primary-key name, ordered column names/types, and a schema fingerprint without row data or secrets;
- first sequence is zero and recovery starts at `resumeFrom.batchSequence + 1`;
- each emitted row parses as the expected canonical envelope;
- batch IDs use existing `BatchId.sha256` rules;
- a batch remains unacknowledged before `acknowledgeCommittedBatch`;
- acknowledgement marks the matching Debezium batch exactly once; and
- stream cancellation closes the fake engine and bridge.

- [ ] **Step 3: Run and verify RED**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.MySQLCdcSourceNodeSpec'
```

Expected: compilation fails because the engine access and source node are missing.

- [ ] **Step 4: Implement the real Debezium adapter**

Build the engine using Connect output so schema and logical type data remain available:

```scala
val engine: DebeziumEngine[RecordChangeEvent[SourceRecord]] = DebeziumEngine
  .create(ChangeEventFormat.of(classOf[Connect]))
  .using(properties)
  .notifying(changeConsumer)
  .build()
```

Wrap `RecordCommitter[RecordChangeEvent[SourceRecord]]` in `CdcBatchCommitHandle`; its `markProcessedAndFinished` loops over the exact delivered records, calls `markProcessed` for each, then calls `markBatchFinished` once.

Run the engine on one named daemon executor owned by the adapter. `close()` closes the engine, waits a bounded interval for the executor, then interrupts only its own thread if necessary.

- [ ] **Step 5: Implement `MySQLCdcSourceNode`**

The node:

- rejects legacy `createSource` with `mysql.cdc requires checkpoint-aware execution`;
- returns a stable, non-secret canonical stream identity during boundary discovery;
- uses JDBC metadata during boundary discovery to reject no primary key, composite primary keys, and unsupported source column types before Debezium starts;
- validates node, partition, and stream identity on recovery;
- starts one bridge and one engine per materialized execution;
- converts each Debezium callback batch to one `SourceBatch`;
- keeps a concurrent `batchId -> bridge` acknowledgement index;
- removes acknowledgement entries after success or source close; and
- fails the Pekko stream if the engine terminates exceptionally.

Do not place Debezium engine, committers, promises, or executors inside serializable workflow state.

- [ ] **Step 6: Run GREEN and thread-leak checks**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) sbt \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.MySQLCdcSourceNodeSpec' \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.DebeziumBatchBridgeSpec'
```

Expected: tests pass and the JVM exits without a lingering Debezium executor.

- [ ] **Step 7: Conditional commit checkpoint**

Only with explicit commit authorization:

```bash
git add pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/DebeziumEngineAccess.scala \
  pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLCdcSourceNode.scala \
  pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLCdcSourceNodeSpec.scala
git commit -m "feat: stream real MySQL changes with Debezium"
```

### Task 7: Transactional `mysql.cdc.apply` Sink

**Files:**
- Create: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sinks/MySQLCdcApplyConfig.scala`
- Create: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sinks/MySQLCdcApplySinkNode.scala`
- Create: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sinks/MySQLCdcApplySinkNodeSpec.scala`

**Interfaces:**
- Consumes: `MySQLCdcEnvelope`, schema-bearing `SnapshotBoundary`, target JDBC metadata, `SourceBatch`, and `pekko_sync_batch_ledger`.
- Produces: `mysql.cdc.apply` readiness, cross-source/target boundary validation, and ordered atomic `commitBatch` for `r/c/u/d`.

- [ ] **Step 1: Write failing configuration and readiness tests**

Assert environment password resolution, safe database/table identifiers, required credentials, and rejection of unsupported modes. For readiness, use controlled JDBC fixtures to prove rejection of:

- no target primary key;
- composite target primary key;
- primary-key name mismatch;
- missing target source column;
- required extra target column without default; and
- unsupported BLOB or spatial column.

The successful metadata fixture contains `id BIGINT PRIMARY KEY`, `run_id VARCHAR`, `status VARCHAR`, `amount DECIMAL(18,2)`, `note VARCHAR NULL`, and `updated_at TIMESTAMP`.

Add `validateSourceBoundary` cases showing that source primary-key mismatch, missing target source column, and changed source schema fingerprint fail before the first batch is requested.

- [ ] **Step 2: Write failing CRUD and rollback tests**

Use real H2 connections in MySQL mode for transaction behavior and literal envelopes:

```scala
val events = Vector(read(1, "new"), create(2, "new"), update(1, "paid"), delete(2))
val result = Await.result(
  sink.commitBatch(node, "workflow-cdc", "execution-cdc", batch(events), events.map(_.canonicalJson), _ => ()),
  5.seconds
)
result shouldBe Committed(result.checkpoint)
selectStatus(1) shouldBe Some("paid")
selectStatus(2) shouldBe None
ledgerCount(batchId) shouldBe 1
```

Add replay, absent-row delete, two updates to the same key in order, primary-key-changing event sequence, malformed envelope before ledger claim, unsupported operation, and target failure rollback. The rollback test must assert both target state and ledger count remain unchanged.

- [ ] **Step 3: Run and verify RED**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sinks.MySQLCdcApplySinkNodeSpec'
```

Expected: compilation fails because the sink and config do not exist.

- [ ] **Step 4: Implement typed metadata and SQL planning**

Resolve target table and primary key through `DatabaseMetaData`. Build quoted SQL only from validated metadata:

```sql
INSERT INTO `target_table` (`id`, `run_id`, `status`, `amount`, `note`, `updated_at`)
VALUES (?, ?, ?, ?, ?, ?)
ON DUPLICATE KEY UPDATE
`run_id`=VALUES(`run_id`), `status`=VALUES(`status`),
`amount`=VALUES(`amount`), `note`=VALUES(`note`),
`updated_at`=VALUES(`updated_at`)
```

```sql
DELETE FROM `target_table` WHERE `id` = ?
```

Bind from target JDBC type. Use `setNull` with the actual JDBC type; parse integral/decimal/boolean/date/time/timestamp values explicitly; reject conversion loss.

- [ ] **Step 5: Implement one ordered target-and-ledger transaction**

Parse all envelopes and validate their source table/required images before opening the ledger claim. Inside one connection transaction, reuse the existing ledger columns and exact conflict rules, then apply events in original order. `r/c/u` use the complete `after` image; `d` uses `key` only. Commit once after all events; roll back on every non-fatal failure.

- [ ] **Step 6: Run GREEN and existing-sink regression**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) sbt \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sinks.MySQLCdcApplySinkNodeSpec' \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sinks.MySQLSinkNodeSpec'
```

Expected: CDC cases pass and `mysql.write` behavior is unchanged.

- [ ] **Step 7: Conditional commit checkpoint**

Only with explicit commit authorization:

```bash
git add pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sinks/MySQLCdcApplyConfig.scala \
  pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sinks/MySQLCdcApplySinkNode.scala \
  pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sinks/MySQLCdcApplySinkNodeSpec.scala
git commit -m "feat: apply CDC events transactionally to MySQL"
```

### Task 8: Registry, Workflow Topology, Secrets, and Actor Lifecycle

**Files:**
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/registry/NodeRegistry.scala:24-45`
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/WorkflowValidator.scala:17-79`
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/WorkflowExecutionEngine.scala:216-229`
- Modify: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/WorkflowFixtures.scala:1-88`
- Modify: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/engine/WorkflowValidatorSpec.scala:6-43`
- Modify: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/engine/WorkflowExecutionEngineSpec.scala:39-85,422-440`
- Modify: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/actors/EventSourcedWorkflowActorRecoverySpec.scala:160-190,330-380`

**Interfaces:**
- Consumes: new node classes and existing workflow metadata/schedule contract.
- Produces: supported node types, direct source-to-sink restriction, schedule rejection, runtime password injection, and resumable actor selection.

- [ ] **Step 1: Add failing workflow fixture and validator tests**

Create `WorkflowFixtures.mysqlCdcWorkflow` with exactly two nodes and one edge. Add literal error-code assertions:

```scala
errors(cdcWithTransform) should contain("mysql_cdc_transform_not_supported")
errors(cdcWithLegacySink) should contain("mysql_cdc_sink_required")
errors(legacySourceWithCdcSink) should contain("mysql_cdc_source_required")
errors(scheduledCdc) should contain("mysql_cdc_schedule_not_supported")
WorkflowValidator.validate(WorkflowFixtures.mysqlCdcWorkflow).isRight shouldBe true
```

- [ ] **Step 2: Add failing registry and secret tests**

Assert:

```scala
NodeRegistry.findSource("mysql.cdc").value shouldBe a[MySQLCdcSourceNode]
NodeRegistry.findSink("mysql.cdc.apply").value shouldBe a[MySQLCdcApplySinkNode]
```

Execute a prepared CDC workflow with `passwordEnv` fields and fakes that capture runtime nodes. Assert both receive the resolved secret while the original workflow JSON and collected logs do not contain it.

- [ ] **Step 3: Add failing actor capability test**

Register controllable checkpoint source/sink instances under the CDC node types. Start a workflow and assert the actor invokes `execute(workflow, ReliableRunContext, ...)`, keeps the execution in `running` while the engine promise is incomplete, and returns `already_running` for a second manual request.

- [ ] **Step 4: Run and verify RED**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) sbt \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.engine.WorkflowValidatorSpec' \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngineSpec' \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActorRecoverySpec'
```

Expected: node types are unsupported and CDC-specific validation is absent.

- [ ] **Step 5: Register nodes and enforce the topology**

Add `new MySQLCdcSourceNode()` and `new MySQLCdcApplySinkNode()` to the built-in maps. In `WorkflowValidator`, after the linear path is known, add the four exact CDC validation errors. Evaluate `workflow.metadata.schedule.exists(_.enabled)` only for CDC; legacy schedules retain current behavior.

- [ ] **Step 6: Extend runtime secret preparation narrowly**

Replace the two-node string condition with a fixed set:

```scala
private val JdbcPasswordNodeTypes = Set(
  "mysql.snapshot", "mysql.write", "mysql.cdc", "mysql.cdc.apply"
)
```

Resolve only nodes in this set that contain `passwordEnv`. Never convert every arbitrary node with a `passwordEnv` field.

- [ ] **Step 7: Run GREEN and actor regressions**

Run the three suites from Step 4 plus `EventSourcedWorkflowActorSpec`. Expected: all pass; no production actor change is needed unless the failing test reveals an actual unbounded execution bug.

- [ ] **Step 8: Conditional commit checkpoint**

Only with explicit commit authorization:

```bash
git add pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/registry/NodeRegistry.scala \
  pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/WorkflowValidator.scala \
  pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/WorkflowExecutionEngine.scala \
  pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/WorkflowFixtures.scala \
  pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/engine/WorkflowValidatorSpec.scala \
  pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/engine/WorkflowExecutionEngineSpec.scala \
  pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/actors/EventSourcedWorkflowActorRecoverySpec.scala
git commit -m "feat: expose single-node MySQL CDC workflows"
```

### Task 9: Real MySQL Connector and Apply Integration

**Files:**
- Create: `pekko-server/src/main/resources/db/mysql/pekko-cdc-schema.sql`
- Create: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/integration/RealMySQLCdcProcess.scala`
- Create: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/integration/RealMySQLCdcRecoverySpec.scala`

**Interfaces:**
- Consumes: real `MySQLCdcSourceNode`, `MySQLCdcApplySinkNode`, MySQL 8.4, and environment-provided isolated schema credentials.
- Produces: external integration evidence for initial snapshot, continuous insert/update/delete, durable connector state, replay, and separate-JVM recovery.

- [ ] **Step 1: Add idempotent CDC and acceptance DDL**

Create MySQL-compatible state tables with fixed names and no unconditional destructive statement:

```sql
CREATE TABLE IF NOT EXISTS debezium_offset_storage (
  id VARCHAR(36) NOT NULL,
  offset_key VARCHAR(1255),
  offset_val VARCHAR(1255),
  record_insert_ts TIMESTAMP(6) NOT NULL,
  record_insert_seq INT NOT NULL
);

CREATE TABLE IF NOT EXISTS debezium_database_history (
  id VARCHAR(36) NOT NULL,
  history_data LONGTEXT,
  history_data_seq INT,
  record_insert_ts TIMESTAMP(6) NOT NULL,
  record_insert_seq INT NOT NULL
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
```

If Debezium 3.6.1.Final's JDBC implementation rejects the checked-in table shape during the first real test, compare its official DDL/query constants and adjust this schema and the configured custom DDL together. Do not guess column names or widen privileges to hide the mismatch.

- [ ] **Step 2: Write the external integration test first**

Tag the suite `ExternalIntegration`. Require all of:

```text
MYSQL_CDC_TEST_HOST
MYSQL_CDC_TEST_PORT
MYSQL_CDC_TEST_DATABASE
MYSQL_CDC_TEST_WRITER_USER
MYSQL_CDC_TEST_WRITER_PASSWORD
MYSQL_CDC_TEST_READER_USER
MYSQL_CDC_TEST_READER_PASSWORD
MYSQL_CDC_TEST_SERVER_ID
```

The test must fail fast with a list of missing variable names and must never include values.

Use a unique `runId`, connector ID, workflow ID, execution ID, and non-overlapping numeric ID range. Delete only rows in the dedicated acceptance tables for that run-scoped ID range.

- [ ] **Step 3: Run and verify RED against the real MySQL fixture**

Run with credentials supplied in the shell, never embedded in command history or the plan:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) \
RUN_MYSQL_CDC_EXTERNAL=1 \
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.integration.RealMySQLCdcRecoverySpec'
```

Expected before implementation is complete: connector startup, privilege, JDBC-state, or event-application failure. Capture the first concrete failure and fix only that layer.

- [ ] **Step 4: Implement the child-process protocol**

`RealMySQLCdcProcess` accepts modes through non-secret arguments and reads credentials only from environment:

```text
snapshot-and-stream <connectorId> <executionId> <idStart> <idEnd>
resume-stream       <connectorId> <executionId> <idStart> <idEnd>
```

It writes machine-readable status lines containing only connector ID, execution ID, batch sequence, row counts, and cursor file/position. It exits non-zero on source, sink, acknowledgement, or timeout failure. It closes its ActorSystem, stream, bridge, engine, and JDBC resources in all paths.

- [ ] **Step 5: Prove snapshot and live CRUD**

The parent suite performs:

1. insert three baseline source rows;
2. start the child and wait for three target rows;
3. insert a fourth row;
4. update the first row;
5. delete the second row;
6. wait for target state `{first=updated, second=absent, third=baseline, fourth=inserted}`; and
7. assert offset and schema-history tables contain connector state.

Derive expected values literally in the test. Do not calculate expected target state by reusing sink code.

- [ ] **Step 6: Prove separate-JVM resume and replay safety**

Stop the first child only after target visibility. Start the second child with the same connector ID and a continued execution checkpoint fixture. Apply another update and delete, then assert the exact final target state and no resurrection of deleted rows.

For the replay case, configure a 60-second offset flush interval in the test child, observe target commit while the latest offset-store timestamp has not advanced, terminate that child, and start the recovery child. This provides evidence that at least one already-applied change is eligible for replay.

- [ ] **Step 7: Run GREEN twice**

Run the external suite twice with fresh connector IDs and ID ranges. Expected: both runs pass independently, and retained rows/offsets from the first cannot make the second pass.

- [ ] **Step 8: Conditional commit checkpoint**

Only with explicit commit authorization:

```bash
git add pekko-server/src/main/resources/db/mysql/pekko-cdc-schema.sql \
  pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/integration/RealMySQLCdcProcess.scala \
  pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/integration/RealMySQLCdcRecoverySpec.scala
git commit -m "test: verify real MySQL CDC recovery"
```

### Task 10: Safe `bigdata-lab` CDC Bootstrap and Isolated Single-Node Runtime

**Files:**
- Modify: `deploy/k8s/bigdata-lab/bootstrap-mysql.sh:1-64`
- Create: `deploy/k8s/bigdata-lab/cdc-single/application-cdc-single.conf`
- Create: `deploy/k8s/bigdata-lab/cdc-single/headless-service.yaml`
- Create: `deploy/k8s/bigdata-lab/cdc-single/api-service.yaml`
- Create: `deploy/k8s/bigdata-lab/cdc-single/statefulset.yaml`
- Create: `deploy/k8s/bigdata-lab/cdc-single/kustomization.yaml`
- Modify: `deploy/k8s/bigdata-lab/README.md:19-49`

**Interfaces:**
- Consumes: current MySQL Deployment, workflow DB secret, new CDC schema, Java 17 Docker image, and existing namespace RBAC.
- Produces: dedicated CDC user/Secret, isolated acceptance database, and exactly one Pekko pod on `xjw` without changing `pekko-workflow` replicas.

- [ ] **Step 1: Add shell-level dry-run checks before changing the script**

Run:

```bash
bash -n deploy/k8s/bigdata-lab/bootstrap-mysql.sh
kubectl kustomize deploy/k8s/bigdata-lab/cdc-single
```

Expected before new files: bootstrap syntax passes; kustomize fails because the CDC single-node package is absent.

- [ ] **Step 2: Extend bootstrap inputs without exposing credentials**

Require:

```bash
: "${WORKFLOW_DB_PASSWORD:?WORKFLOW_DB_PASSWORD must be set}"
: "${MYSQL_CDC_PASSWORD:?MYSQL_CDC_PASSWORD must be set}"
```

Resolve the current MySQL pod from `app=mysql` when `MYSQL_POD` is absent, require exactly one Running pod, and print only its name. Escape both password values for SQL without echoing them.

- [ ] **Step 3: Add exact least-privilege SQL and isolated database setup**

The bootstrap creates `pekko_cdc` and grants exactly:

```sql
GRANT SELECT, RELOAD, SHOW DATABASES,
      REPLICATION SLAVE, REPLICATION CLIENT
ON *.* TO 'pekko_cdc'@'%';
```

Create database `pekko_cdc_acceptance`, grant the existing `pekko_workflow` account its existing application privileges only on that database, and apply persistence, ledger, and CDC schemas there. This separate journal prevents the isolated one-node cluster from sharing the constant scheduler persistence ID with the existing two-node cluster.

Create `pekko-cdc-db` from a mode-0600 temporary file using `kubectl create secret --dry-run=client -o yaml | kubectl apply -f -`. Do not print or commit generated Secret YAML.

- [ ] **Step 4: Add the single-node cluster config**

`application-cdc-single.conf` includes `application-prod.conf` and overrides:

```hocon
pekko.pekko-sys = "pekko-cdc-single-system"
pekko.workflow.cluster-bootstrap.enabled = true
pekko.workflow.mysql-cdc.enabled = true
pekko.cluster.seed-nodes = []
pekko.cluster.min-nr-of-members = 1
pekko.discovery.kubernetes-api.pod-label-selector = "app=%s"
pekko.management.cluster.bootstrap.contact-point-discovery {
  discovery-method = kubernetes-api
  service-name = "pekko-cdc-single"
  port-name = "management"
  required-contact-point-nr = 1
  stable-margin = 5s
}
```

CDC state JDBC uses `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, and `DB_PASSWORD`. The normal offset flush interval is `0`; the acceptance StatefulSet may override it to `60000` only for the forced-replay phase.

- [ ] **Step 5: Add one explicit acceptance pod**

The StatefulSet has:

```yaml
metadata:
  name: pekko-cdc-single
spec:
  replicas: 1
  serviceName: pekko-cdc-single-headless
  template:
    metadata:
      labels: {app: pekko-cdc-single}
    spec:
      nodeSelector: {kubernetes.io/hostname: xjw}
```

Use `DB_NAME=pekko_cdc_acceptance`, the existing workflow DB Secret for `DB_PASSWORD`, and `pekko-cdc-db/password` for `MYSQL_CDC_PASSWORD`. Use distinct headless/API Service names. Do not include the existing `statefulset.yaml` or patch its replica count.

- [ ] **Step 6: Render and validate without applying**

Run:

```bash
bash -n deploy/k8s/bigdata-lab/bootstrap-mysql.sh
kubectl kustomize deploy/k8s/bigdata-lab/cdc-single > /tmp/pekko-cdc-single-rendered.yaml
kubectl apply --dry-run=client -f /tmp/pekko-cdc-single-rendered.yaml
rg -n "replicas: 1|pekko-cdc-single|MYSQL_CDC_PASSWORD|pekko_cdc_acceptance" /tmp/pekko-cdc-single-rendered.yaml
```

Expected: render and client validation pass; the output contains no Secret data value and no `pekko-workflow` StatefulSet mutation.

- [ ] **Step 7: Explain the exact live change before applying**

Report in plain language: this creates one database, two dedicated acceptance tables plus metadata/persistence tables, one CDC user with five read/replication privileges, one Secret, two Services, one ConfigMap, and one single-replica StatefulSet pinned to `xjw`. Worst case is isolated CDC acceptance downtime or stale acceptance data; the existing two-node workload and application data are not targets. Recovery is deleting only the named `pekko-cdc-single` resources and retaining or explicitly removing the dedicated database/account in a separately approved cleanup.

- [ ] **Step 8: Apply only after the already-approved scope is rechecked**

Read credentials without echo so neither value appears in shell history or the implementation report:

```bash
read -r -s -p "Workflow DB password: " PEKKO_WORKFLOW_DB_PASSWORD_INPUT
printf '\n'
read -r -s -p "CDC account password: " PEKKO_MYSQL_CDC_PASSWORD_INPUT
printf '\n'
WORKFLOW_DB_PASSWORD="$PEKKO_WORKFLOW_DB_PASSWORD_INPUT" \
MYSQL_CDC_PASSWORD="$PEKKO_MYSQL_CDC_PASSWORD_INPUT" \
  ./deploy/k8s/bigdata-lab/bootstrap-mysql.sh
unset PEKKO_WORKFLOW_DB_PASSWORD_INPUT PEKKO_MYSQL_CDC_PASSWORD_INPUT
kubectl apply -k deploy/k8s/bigdata-lab/cdc-single
```

Never paste actual values into reports, source files, or Git commands.

- [ ] **Step 9: Conditional commit checkpoint**

Only with explicit commit authorization:

```bash
git add deploy/k8s/bigdata-lab/bootstrap-mysql.sh \
  deploy/k8s/bigdata-lab/cdc-single \
  deploy/k8s/bigdata-lab/README.md
git commit -m "deploy: add isolated single-node CDC acceptance"
```

### Task 11: Deterministic `bigdata-lab` Snapshot, CRUD, and Restart Acceptance

**Files:**
- Create: `deploy/k8s/bigdata-lab/run-cdc-e2e.sh`
- Modify: `deploy/k8s/bigdata-lab/README.md`

**Interfaces:**
- Consumes: single-node API Service, dedicated acceptance database/tables, CDC and workflow secrets, and workflow HTTP endpoints.
- Produces: machine-verifiable real snapshot, live CRUD, delayed-offset replay, pod restart, and final-state evidence.

- [ ] **Step 1: Write the acceptance script in fail-closed stages**

Use `set -euo pipefail`, a `mktemp -d` directory, and an EXIT trap. Resolve exactly `pekko-cdc-single-0` and require it Ready before any data mutation. Define helpers for MySQL queries, API POST/GET, bounded polling, and JSON extraction. Every poll has an explicit timeout and prints a diagnostic query on failure.

- [ ] **Step 2: Create run-scoped data and workflow identity**

Generate:

```text
run_id=cdc-<UTC timestamp>-<random suffix>
workflow_id=mysql-cdc-<same suffix>
connector_id=mysql-cdc-<same suffix>
execution request id=<same suffix>-start
```

Choose a numeric ID range that is empty in both dedicated tables. Insert three baseline source rows before starting the workflow. Stop if any selected ID exists; retained data must never make the run pass.

- [ ] **Step 3: Submit the exact two-node CDC workflow**

The JSON contains only:

```json
{
  "nodes": [
    {
      "id": "source-1",
      "type": "source",
      "nodeType": "mysql.cdc",
      "config": {
        "connectorId": "run-specific-connector",
        "host": "mysql",
        "port": 3306,
        "database": "pekko_cdc_acceptance",
        "table": "pekko_cdc_source_acceptance",
        "username": "pekko_cdc",
        "passwordEnv": "MYSQL_CDC_PASSWORD",
        "serverId": 54001,
        "maxBatchSize": 10,
        "pollIntervalMillis": 100
      }
    },
    {
      "id": "sink-1",
      "type": "sink",
      "nodeType": "mysql.cdc.apply",
      "config": {
        "host": "mysql",
        "port": 3306,
        "database": "pekko_cdc_acceptance",
        "table": "pekko_cdc_target_acceptance",
        "username": "pekko_workflow",
        "passwordEnv": "DB_PASSWORD"
      }
    }
  ],
  "edges": [{"id":"source-to-sink","source":"source-1","target":"sink-1"}]
}
```

Use a run-specific `serverId` within an approved reserved range so concurrent or retained runs cannot collide.

- [ ] **Step 4: Verify the initial snapshot**

Wait until the exact three run-scoped source rows equal the exact three target rows by primary key and every data column. Also require workflow status `running`, at least one CDC ledger row for the execution, and at least one Debezium offset/history row for the connector.

- [ ] **Step 5: Verify ordered live insert, update, and delete**

In one source transaction:

1. insert the fourth row;
2. update the first row to `paid` and a new decimal amount; and
3. delete the second row.

Wait for exact target state and compare a canonical SQL projection. Assert the deleted target key is absent, not null-filled or stale.

- [ ] **Step 6: Force the replay window and restart the exact single pod**

Before another source update, record `MAX(record_insert_ts)` from `debezium_offset_storage`. Apply the source update, wait until its target state is visible, and require that the recorded offset timestamp has not advanced under the 60-second acceptance flush interval.

Then delete exactly:

```bash
kubectl --namespace bigdata-lab delete pod pekko-cdc-single-0 --wait=true
```

This is recoverable: the dedicated StatefulSet recreates that one pod. Wait for a new pod UID and readiness. Do not delete `pekko-workflow-0`, `pekko-workflow-1`, MySQL, or any PVC.

- [ ] **Step 7: Verify recovery and final state**

Require the same workflow execution to return to `running`, the offset-store timestamp to advance, and the target to equal source for the selected run range. Apply one final update and one final delete after restart and verify both. Record row counts, ledger counts, old/new pod UIDs, execution ID, and sanitized binlog cursor evidence.

- [ ] **Step 8: Run the acceptance twice**

Run twice with distinct identities and ID ranges. Expected: both pass and print a concise `CDC_E2E_PASS` record. Any missing delete, duplicate-visible row, reused connector state, credential output, or timeout is failure.

- [ ] **Step 9: Conditional commit checkpoint**

Only with explicit commit authorization:

```bash
git add deploy/k8s/bigdata-lab/run-cdc-e2e.sh deploy/k8s/bigdata-lab/README.md
git commit -m "test: add real single-node CDC acceptance"
```

### Task 12: Full Regression, Packaging, Security Review, and Handoff

**Files:**
- Modify: `README.md` only if the root capability summary still describes CDC as simulated.
- Modify: `docs/CONFIGURATION.md` with the final node fields, Java 17 floor, state JDBC block, privileges, and recovery boundary.
- Review: every file changed by Tasks 1-11.

**Interfaces:**
- Consumes: completed implementation and current dirty-worktree baseline.
- Produces: evidence-backed completion report with local, external, packaging, and remaining-risk tiers separated.

- [ ] **Step 1: Compile all production and test sources under Java 17**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) sbt 'pekko-server/Test/compile'
```

Expected: success with no missing ServiceLoader class, Java bytecode incompatibility, or source warning introduced by CDC files.

- [ ] **Step 2: Run focused CDC suites**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) sbt \
  'pekko-server/testOnly cn.xuyinyin.magic.config.ConfigValidatorSpec' \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.cdc.MySQLCdcEnvelopeSpec' \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.MySQLCdcSourceConfigSpec' \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.DebeziumBatchBridgeSpec' \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.MySQLCdcSourceNodeSpec' \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sinks.MySQLCdcApplySinkNodeSpec' \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.engine.WorkflowValidatorSpec' \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngineSpec'
```

Expected: zero failures, errors, or skips in focused non-external suites.

- [ ] **Step 3: Run adjacent recovery and connector regressions**

Run existing MySQL snapshot, MySQL sink, Kafka source, actor recovery, bounded Kafka recovery, and resumable full-sync suites. Expected: zero failures and no change in their test counts except deliberate new cases.

- [ ] **Step 4: Run the full non-external suite**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) sbt 'pekko-server/test'
```

Expected: all suites pass; report exact test/suite counts from current output rather than reusing the earlier 264-test figure.

- [ ] **Step 5: Run real MySQL and `bigdata-lab` evidence**

Run the external Scala suite and `run-cdc-e2e.sh` with credentials supplied out of band. Expected: real snapshot/CRUD/replay/restart acceptance passes twice. If network, permissions, or cluster state prevents the run, mark only this tier `external_blocked`; do not claim it from unit tests.

- [ ] **Step 6: Build packages and inspect dependencies**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) sbt \
  'pekko-server/Universal/packageBin' \
  'pekko-server/Docker/publishLocal' \
  'pekko-server/dependencyTree'
```

Expected: Universal and Docker builds succeed; the image base is Java 17; Debezium SPI implementations load in the packaged process; the dependency tree has one selected Debezium version.

- [ ] **Step 7: Perform secret and diff review**

Run scoped searches for credential assignment patterns, inline passwords, generated Secret data, and fixture secrets. Inspect:

```bash
git status --short
git diff --check
git diff -- build.sbt pekko-server/src deploy/k8s/bigdata-lab README.md docs/CONFIGURATION.md
```

Preserve `.tasks/` and unrelated parser/test changes. Verify no existing user file was staged, deleted, or overwritten by CDC work.

- [ ] **Step 8: Update truthful documentation**

Document:

- `mysql.cdc` and `mysql.cdc.apply` node examples;
- Java 17 requirement;
- required MySQL variables and grants;
- initial snapshot and persistent JDBC state behavior;
- continuous `running` lifecycle;
- at-least-once replay and idempotent-final-state boundary;
- 30-day current binlog retention observation as environment-specific evidence;
- excluded GTID, DDL propagation, multi-table, composite-key, and multi-node failover capabilities; and
- exact commands used for local and live verification.

- [ ] **Step 9: Conditional final commit**

Only after explicit commit authorization, enumerate the exact CDC paths, stage only those paths, show `git diff --cached --stat` and `git diff --cached`, then commit with a user-approved message. Do not include `.tasks/` or unrelated dirty files. Push remains separately unauthorized.

- [ ] **Step 10: Handoff report**

Lead with outcome and separate evidence:

- implemented code and interfaces;
- focused/unit results;
- full-suite result;
- real MySQL result;
- `bigdata-lab` single-node result;
- Universal/Docker result;
- uncommitted or committed Git state; and
- remaining multi-node, GTID, DDL, type, retention, and operator-lifecycle risks.
