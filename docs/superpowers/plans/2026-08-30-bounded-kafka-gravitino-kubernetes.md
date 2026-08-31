# Bounded Kafka, Gravitino, and Kubernetes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run a finite, checkpointed Kafka-to-MySQL workflow on two Pekko pods in `bigdata-lab`, with Gravitino topic discovery and real cross-process recovery.

**Architecture:** `KafkaSource` becomes a checkpoint-aware, manually assigned Pekko Kafka consumer whose frozen multi-partition offsets are encoded in the existing boundary/cursor strings. Existing MySQL batch-ledger ordering remains the data commit boundary, while the event-sourced workflow state projects accepted Kafka checkpoints into a workflow-level cursor for later scheduled runs. Gravitino is consulted only before a new boundary is persisted; Kubernetes runs two Cluster-Bootstrap-discovered StatefulSet members and stores all credentials in a Secret.

**Tech Stack:** Scala 2.13.12, Apache Pekko 1.1.3, Apache Pekko HTTP 1.1.0, Apache Pekko Management 1.1.1, Apache Pekko Connectors Kafka 1.1.0, Kafka 4.0.0, MySQL 8, Apache Gravitino 1.1.0, Kubernetes 1.23.17, ScalaTest 3.2.19, sbt-native-packager.

**Spec:** `docs/superpowers/specs/2026-08-30-bounded-kafka-gravitino-kubernetes-design.md`

## Global Constraints

- Keep node type `kafka.consumer`; do not add a second Kafka source type.
- Kafka recovery truth is Actor/MySQL state, never Kafka auto-committed offsets.
- Preserve sink ordering: MySQL rows + ledger transaction, then Actor checkpoint.
- Kafka output is the UTF-8 record value; null values fail explicitly.
- Gravitino is control plane only and is not required after boundary persistence.
- MySQL Snapshot remains execution-scoped and behavior-compatible.
- Passwords must not enter Git, workflow JSON, ConfigMap, Gravitino, logs, or test output.
- Real failure testing may delete only one new `pekko-workflow-*` pod.
- The corrective static-seed-to-Bootstrap migration may replace both existing Pekko pods once in a controlled cold start; the later recovery experiment still deletes only verified host `pekko-workflow-0` and never deletes pod 1.
- Do not restart or delete shared Kafka, MySQL, Gravitino, or unrelated workloads.
- Preserve the untracked `.tasks/` directory.
- Do not run `git add` or `git commit` until the user explicitly authorizes this feature's commit. Commit commands below are checkpoints, not current authorization.
- No Binlog CDC, Kafka producer, permanent streaming execution, or arbitrary-sink exactly-once claims.

---

### Task 1: Kafka configuration and dependency

**Files:**
- Modify: `build.sbt:36-39,238-240`
- Create: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/KafkaSourceConfig.scala`
- Create: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sources/KafkaSourceConfigSpec.scala`

**Interfaces:**
- Produces:
  - `sealed trait KafkaConnectionMode`
  - `final case class DirectKafkaConnection(bootstrapServers: String)`
  - `final case class GravitinoKafkaConnection(uri: URI, metalake: String, catalog: String)`
  - `sealed trait KafkaOffsetReset` with `Earliest` and `Latest`
  - `final case class KafkaSourceConfig(topic: String, connection: KafkaConnectionMode, offsetReset: KafkaOffsetReset, chunkSize: Int, maxRecords: Long, maxDuration: FiniteDuration)`
  - `KafkaSourceConfig.parse(node: WorkflowDSL.Node): KafkaSourceConfig`

- [ ] **Step 1: Write configuration tests**

Create `KafkaSourceConfigSpec.scala` with focused cases:

```scala
class KafkaSourceConfigSpec extends STSpec {
  private val validGravitino = JsObject(
    "uri" -> JsString("http://gravitino:8090"),
    "metalake" -> JsString("pekko"),
    "catalog" -> JsString("bigdata-kafka")
  )
  private val required = Map[String, JsValue](
    "topic" -> JsString("events"),
    "chunkSize" -> JsNumber(10),
    "maxRecords" -> JsNumber(50),
    "maxDurationSeconds" -> JsNumber(120)
  )
  private def node(entries: (String, JsValue)*): WorkflowDSL.Node =
    WorkflowDSL.Node("source-1", "source", "kafka.consumer", "Kafka", WorkflowDSL.Position(0, 0), JsObject(required ++ entries))
  private def validNode(entries: (String, JsValue)*): WorkflowDSL.Node =
    node((Seq("brokers" -> JsString("kafka:9092")) ++ entries): _*)

  "KafkaSourceConfig" should {
    "parse direct and Gravitino modes" in {
      KafkaSourceConfig.parse(node("brokers" -> JsString("kafka:9092"))).connection shouldBe
        DirectKafkaConnection("kafka:9092")

      KafkaSourceConfig.parse(node("gravitino" -> JsObject(
        "uri" -> JsString("http://gravitino:8090"),
        "metalake" -> JsString("pekko"),
        "catalog" -> JsString("bigdata-kafka")
      ))).connection shouldBe
        GravitinoKafkaConnection(URI.create("http://gravitino:8090"), "pekko", "bigdata-kafka")
    }

    "reject zero or multiple connection modes and non-positive limits" in {
      intercept[IllegalArgumentException](KafkaSourceConfig.parse(node())).getMessage should include("exactly one")
      intercept[IllegalArgumentException](KafkaSourceConfig.parse(node(
        "brokers" -> JsString("kafka:9092"),
        "gravitino" -> validGravitino
      ))).getMessage should include("exactly one")
      intercept[IllegalArgumentException](KafkaSourceConfig.parse(validNode("chunkSize" -> JsNumber(0))))
        .getMessage should include("chunkSize")
    }

    "default offset reset to earliest and reject unknown policies" in {
      KafkaSourceConfig.parse(validNode()).offsetReset shouldBe KafkaOffsetReset.Earliest
      intercept[IllegalArgumentException](KafkaSourceConfig.parse(validNode("offsetReset" -> JsString("middle"))))
        .getMessage should include("offsetReset")
    }
  }
}
```

- [ ] **Step 2: Verify RED**

Run:

```bash
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.KafkaSourceConfigSpec'
```

Expected: compilation fails because `KafkaSourceConfig` and its ADTs do not exist.

- [ ] **Step 3: Add the connector dependency and minimal parser**

Add:

```scala
"org.apache.pekko" %% "pekko-connectors-kafka" % pekkoConnectorsVer,
```

Implement the exact types above. Parsing must trim required strings, require
positive integer/long values, accept only `earliest|latest`, and require
exactly one connection mode. Do not accept `enable.auto.commit` from workflow
configuration.

- [ ] **Step 4: Verify GREEN and dependency resolution**

Run:

```bash
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.KafkaSourceConfigSpec' \
    'pekko-server/Compile/dependencyClasspath'
```

Expected: all tests pass and `pekko-connectors-kafka_2.13-1.1.0.jar` appears in the classpath.

- [ ] **Step 5: Review checkpoint**

Run `git diff --check` and inspect only the three task files. If commit is later authorized:

```bash
git add build.sbt \
  pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/KafkaSourceConfig.scala \
  pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sources/KafkaSourceConfigSpec.scala
git commit -m "feat(kafka): define bounded source configuration"
```

---

### Task 2: Gravitino topic resolver

**Files:**
- Create: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/GravitinoTopicResolver.scala`
- Create: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sources/GravitinoTopicResolverSpec.scala`

**Interfaces:**
- Consumes: `KafkaSourceConfig`, `DirectKafkaConnection`, `GravitinoKafkaConnection`
- Produces:
  - `final case class ResolvedKafkaTopic(topic: String, bootstrapServers: String)`
  - `trait KafkaTopicResolver { def resolve(config: KafkaSourceConfig)(implicit ec: ExecutionContext): Future[ResolvedKafkaTopic] }`
  - `final class DefaultKafkaTopicResolver(httpClient: HttpClient = HttpClient.newHttpClient()) extends KafkaTopicResolver`

- [ ] **Step 1: Write resolver tests with a local HTTP server**

Use `com.sun.net.httpserver.HttpServer` so no test dependency is added. Test:

```scala
"DefaultKafkaTopicResolver" should {
  "return direct brokers without HTTP" in {
    Await.result(resolver.resolve(directConfig), 2.seconds) shouldBe
      ResolvedKafkaTopic("events", "kafka:9092")
  }

  "load a Kafka catalog and topic from Gravitino" in withServer(
    catalogJson = """{"code":0,"catalog":{"name":"bigdata-kafka","type":"messaging","provider":"kafka","properties":{"bootstrap.servers":"kafka:9092"}}}""",
    topicJson = """{"code":0,"topic":{"name":"events","properties":{}}}"""
  ) { uri =>
    Await.result(resolver.resolve(gravitinoConfig(uri)), 2.seconds) shouldBe
      ResolvedKafkaTopic("events", "kafka:9092")
  }

  "reject wrong providers, missing bootstrap servers, missing topics, and non-2xx responses" in {
    failureFor(catalog(provider = "hive")).getMessage should include("provider kafka")
    failureFor(catalog(properties = JsObject.empty)).getMessage should include("bootstrap.servers")
    failureFor(topicStatus = 404).getMessage should include("topic events")
    failureFor(catalogStatus = 503).getMessage should include("503")
  }
}
```

The server must record request paths and assert both exact `/api/...` paths.
Implement `catalog(...)` as a JSON fixture builder and `failureFor(...)` as a
wrapper around `withServer` that returns the exception from
`Await.result(resolver.resolve(gravitinoConfig(uri)), 2.seconds)`. Give both
helpers defaults for a valid Kafka catalog/topic and HTTP 200, so every
negative assertion above changes exactly one condition.

- [ ] **Step 2: Verify RED**

Run:

```bash
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.GravitinoTopicResolverSpec'
```

Expected: compilation fails because the resolver interfaces do not exist.

- [ ] **Step 3: Implement the resolver**

Use Java `HttpClient` with a 5-second request timeout and
`Accept: application/vnd.gravitino.v1+json`. Parse with Spray JSON. Validate
catalog `type=messaging`, `provider=kafka`, non-empty `bootstrap.servers`, and
exact topic name. Wrap transport and response failures in
`IllegalStateException` messages that contain URI/path and status, never a
secret.

- [ ] **Step 4: Verify GREEN**

Run the focused spec twice to catch leaked HTTP server threads:

```bash
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.GravitinoTopicResolverSpec'
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.GravitinoTopicResolverSpec'
```

- [ ] **Step 5: Review checkpoint**

If commit is authorized:

```bash
git add pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/GravitinoTopicResolver.scala \
  pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sources/GravitinoTopicResolverSpec.scala
git commit -m "feat(kafka): resolve topics through Gravitino"
```

---

### Task 3: Versioned Kafka boundary and cursor codec

**Files:**
- Create: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/KafkaCheckpointCodec.scala`
- Create: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sources/KafkaCheckpointCodecSpec.scala`

**Interfaces:**
- Produces:
  - `KafkaPartitionBoundary(partition: Int, startOffset: Long, endOffset: Long)`
  - `KafkaBoundaryV1(topic: String, bootstrapServers: String, deadlineEpochMillis: Long, partitions: Vector[KafkaPartitionBoundary])`
  - `KafkaCursorV1(nextOffsets: Map[Int, Long], recordsConsumed: Long)`
  - `KafkaCheckpointCodec.CursorKind = "kafka.offsets.v1"`
  - `encodeBoundary`, `decodeBoundary`, `encodeCursor`, `decodeCursor`, and `validateCursor`

- [ ] **Step 1: Write exact canonical round-trip tests**

```scala
val boundary = KafkaBoundaryV1(
  "events", "kafka:9092", 123456789L,
  Vector(KafkaPartitionBoundary(1, 4, 8), KafkaPartitionBoundary(0, 0, 4))
)

KafkaCheckpointCodec.encodeBoundary(boundary) shouldBe
  """{"bootstrapServers":"kafka:9092","deadlineEpochMillis":123456789,"partitions":[{"endOffset":4,"partition":0,"startOffset":0},{"endOffset":8,"partition":1,"startOffset":4}],"topic":"events","version":1}"""

KafkaCheckpointCodec.decodeBoundary(KafkaCheckpointCodec.encodeBoundary(boundary)).partitions.map(_.partition) shouldBe Vector(0, 1)
```

Add rejection tests for duplicate partitions, negative offsets,
`startOffset > endOffset`, unknown versions, missing cursor partitions, cursor
offsets beyond end, and `recordsConsumed < 0`.

- [ ] **Step 2: Verify RED**

Run the new spec; expect missing codec symbols.

- [ ] **Step 3: Implement the codec**

Build canonical JSON explicitly with recursively sorted object keys; never
depend on `Map` iteration order. Decode into typed models and validate before
returning. `validateCursor` must require exactly the boundary partition set.

- [ ] **Step 4: Verify GREEN and property stability**

Run:

```bash
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.KafkaCheckpointCodecSpec' \
    'pekko-server/testOnly cn.xuyinyin.magic.workflow.checkpoint.CheckpointModelsSpec'
```

- [ ] **Step 5: Review checkpoint**

If commit is authorized, commit the two codec files as
`feat(kafka): add durable offset codec`.

---

### Task 4: Bounded multi-partition Kafka source

**Files:**
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/base/CheckpointedNodes.scala`
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/WorkflowExecutionEngine.scala:106-178`
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLSnapshotSourceNode.scala:20-44`
- Replace: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/KafkaSource.scala`
- Create: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/KafkaClientAccess.scala`
- Create: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sources/KafkaSourceSpec.scala`
- Modify: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLSnapshotSourceNodeSpec.scala`
- Modify: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/engine/WorkflowExecutionEngineSpec.scala`

**Interfaces:**
- Change `CheckpointedNodeSource.discoverBoundary` to:

```scala
def discoverBoundary(
  node: WorkflowDSL.Node,
  resumeFrom: Option[BatchCheckpoint],
  onLog: String => Unit
)(implicit blockingEc: ExecutionContext): Future[SnapshotBoundary]
```

- Produce:

```scala
final case class KafkaRecord(partition: Int, offset: Long, value: String)

private[sources] trait KafkaClientAccess {
  def partitionOffsets(topic: ResolvedKafkaTopic, reset: KafkaOffsetReset)
    (implicit ec: ExecutionContext): Future[Vector[KafkaPartitionBoundary]]

  def records(topic: ResolvedKafkaTopic, partition: Int, startOffset: Long, endOffset: Long): Source[KafkaRecord, NotUsed]
}
```

- [ ] **Step 1: Update engine/source contract tests first**

In `WorkflowExecutionEngineSpec`, add a test source that records the
`resumeFrom` argument and assert the latest source checkpoint is passed to
boundary discovery. Update existing MySQL source calls to pass `None` and add
a test proving MySQL ignores the discovery checkpoint and preserves its
current full-snapshot semantics.

- [ ] **Step 2: Write Kafka source tests against a fake client**

Cover:

```scala
"KafkaSource" should {
  "consume partitions in numeric order and emit deterministic batches" in {
    val access = FakeKafkaClientAccess(
      boundaries = Vector(
        KafkaPartitionBoundary(1, 0, 2),
        KafkaPartitionBoundary(0, 0, 3)
      ),
      recordsByPartition = Map(
        0 -> Vector(KafkaRecord(0, 0, "p0-0"), KafkaRecord(0, 1, "p0-1"), KafkaRecord(0, 2, "p0-2")),
        1 -> Vector(KafkaRecord(1, 0, "p1-0"), KafkaRecord(1, 1, "p1-1"))
      )
    )
    val source = new KafkaSource(StaticResolver("events", "kafka:9092"), access, () => 1000L)
    val boundary = Await.result(source.discoverBoundary(kafkaNode(chunkSize = 2), None, _ => ()), 2.seconds)
    val batches = Await.result(source.createBatches(kafkaNode(chunkSize = 2), "execution-1", boundary, None, _ => ()).runWith(Sink.seq), 2.seconds)

    batches.map(_.rows) shouldBe Seq(Vector("p0-0", "p0-1"), Vector("p0-2", "p1-0"), Vector("p1-1"))
    batches.map(_.batchSequence) shouldBe Seq(0L, 1L, 2L)
    KafkaCheckpointCodec.decodeCursor(batches.last.cursor.value).nextOffsets shouldBe Map(0 -> 3L, 1 -> 2L)
  }
}
```

Define the referenced node fixture in the same spec:

```scala
private def kafkaNode(chunkSize: Int = 10): WorkflowDSL.Node = WorkflowDSL.Node(
  id = "source-1",
  `type` = "source",
  nodeType = "kafka.consumer",
  label = "Kafka",
  position = WorkflowDSL.Position(0, 0),
  config = JsObject(
    "topic" -> JsString("events"),
    "brokers" -> JsString("kafka:9092"),
    "offsetReset" -> JsString("earliest"),
    "chunkSize" -> JsNumber(chunkSize),
    "maxRecords" -> JsNumber(50),
    "maxDurationSeconds" -> JsNumber(120)
  )
)
```

Add separate tests with these exact expectations:

| Case | Input | Expected |
|---|---|---|
| same-execution resume | checkpoint boundary equals current boundary and `recordsConsumed=4` | first new cursor retains prior count and increments it |
| next scheduled run | prior checkpoint boundary differs from newly discovered boundary | offsets start at prior `nextOffsets`; `recordsConsumed` restarts at zero |
| record limit | 10 available records, `maxRecords=3` | exactly 3 rows emitted |
| deadline | clock equals persisted deadline | no rows and no metadata rediscovery |
| retention gap | prior next offset is below broker beginning offset | failure contains `retention gap` and both offsets |
| null value | one `KafkaRecord` carries a null value | failed stream contains `null Kafka value` plus partition/offset |
| persisted boundary | resolver and metadata counters start at zero | `createBatches` leaves both counters at zero |

The fake client returns deliberately shuffled partition metadata; expected
output remains partition 0 then 1 with stable batch IDs and cursors.

Define the test doubles in the same spec with these complete contracts:

```scala
private final case class StaticResolver(topic: String, brokers: String) extends KafkaTopicResolver {
  val calls = new AtomicInteger(0)
  override def resolve(config: KafkaSourceConfig)(implicit ec: ExecutionContext): Future[ResolvedKafkaTopic] = {
    calls.incrementAndGet()
    Future.successful(ResolvedKafkaTopic(topic, brokers))
  }
}

private final case class FakeKafkaClientAccess(
  boundaries: Vector[KafkaPartitionBoundary],
  recordsByPartition: Map[Int, Vector[KafkaRecord]]
) extends KafkaClientAccess {
  val metadataCalls = new AtomicInteger(0)
  override def partitionOffsets(topic: ResolvedKafkaTopic, reset: KafkaOffsetReset)
    (implicit ec: ExecutionContext): Future[Vector[KafkaPartitionBoundary]] = {
    metadataCalls.incrementAndGet()
    Future.successful(boundaries)
  }
  override def records(topic: ResolvedKafkaTopic, partition: Int, startOffset: Long, endOffset: Long): Source[KafkaRecord, NotUsed] =
    Source(recordsByPartition.getOrElse(partition, Vector.empty)
      .filter(record => record.offset >= startOffset && record.offset < endOffset))
}
```

- [ ] **Step 3: Verify RED**

Run the four focused specs. Expected: contract compilation failures and the
static Kafka implementation failing every real-source assertion.

- [ ] **Step 4: Change boundary discovery plumbing**

Select the latest checkpoint by `sourceNodeId` before discovering a new
boundary. Existing persisted boundaries bypass discovery. Update
`MySQLSnapshotSourceNode` to accept and ignore `resumeFrom` during discovery;
its `createBatches` validation remains unchanged.

- [ ] **Step 5: Implement the production Kafka client**

Use `ConsumerSettings[String, String]` with `StringDeserializer`, configured
bootstrap servers, derived group/client ID, and
`ENABLE_AUTO_COMMIT_CONFIG=false`. Metadata discovery uses a temporary Kafka
consumer and closes it in `finally`. Record streams use
`Consumer.plainSource` with `Subscriptions.assignmentWithOffset`, stop before
the frozen end offset, map to `KafkaRecord`, and close Consumer control on
completion/cancellation.

- [ ] **Step 6: Implement `KafkaSource`**

Constructor:

```scala
class KafkaSource(
  resolver: KafkaTopicResolver = new DefaultKafkaTopicResolver(),
  clientAccess: KafkaClientAccess = new PekkoKafkaClientAccess(),
  nowMillis: () => Long = () => System.currentTimeMillis()
) extends NodeSource with CheckpointedNodeSource
```

`createSource` must fail with `UnsupportedOperationException` because reliable
context is required. `discoverBoundary` resolves Gravitino/direct brokers,
builds a sorted frozen boundary, and copies prior next offsets when present.
`createBatches` concatenates partitions, applies deadline/max-record limits,
groups by `chunkSize`, and emits aggregate cursors.

- [ ] **Step 7: Verify GREEN**

Run:

```bash
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.KafkaSourceConfigSpec' \
    'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.KafkaCheckpointCodecSpec' \
    'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.KafkaSourceSpec' \
    'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.MySQLSnapshotSourceNodeSpec' \
    'pekko-server/testOnly cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngineSpec'
```

- [ ] **Step 8: Review checkpoint**

If commit is authorized, stage only the nine files above and commit as
`feat(kafka): add bounded checkpointed consumer`.

---

### Task 5: Workflow-level Kafka progress

**Files:**
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/actors/EventSourcedWorkflowActor.scala:100-450`
- Modify: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/actors/EventSourcedWorkflowActorSpec.scala`
- Modify: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/actors/EventSourcedWorkflowActorRecoverySpec.scala`

**Interfaces:**
- Produce:

```scala
final case class WorkflowSourceProgress(
  workflowRevision: Long,
  sourceNodeId: String,
  sourceNodeType: String,
  checkpoint: BatchCheckpoint
) extends CborSerializable
```

- Add `workflowSourceProgress: Option[WorkflowSourceProgress] = None` to `WorkflowState`.
- Do not add or change a `WorkflowEvent` case class.

- [ ] **Step 1: Write event-projection tests**

Add pure/event-sourced tests proving:

1. `ExecutionCheckpointAdvanced` with cursor kind `kafka.offsets.v1` updates
   current execution and workflow progress;
2. a MySQL cursor updates only current execution;
3. `ResumableExecutionStarted` for the same Kafka workflow revision seeds the
   new execution checkpoint;
4. a failed Kafka execution still leaves accepted progress for the next run;
5. `WorkflowDefined` at a new revision clears progress; and
6. Actor recovery reconstructs progress using only existing journal events.

Use the public execution/checkpoint protocol and complete the engine's promise;
`EngineCrashed` is private implementation detail and must not be referenced by
the test. The core next-run assertion is:

```scala
val firstCompletion = Promise[ExecutionResult]()
val secondCompletion = Promise[ExecutionResult]()
val completions = mutable.Queue(firstCompletion, secondCompletion)
val pendingEngine = new WorkflowExecutionEngine() {
  private def nextResult(): Future[ExecutionResult] = completions.dequeue().future
  override def execute(workflow: WorkflowDSL.Workflow, executionId: String, onLog: String => Unit): Future[ExecutionResult] =
    nextResult()
  override def execute(workflow: WorkflowDSL.Workflow, runContext: ReliableRunContext, onLog: String => Unit): Future[ExecutionResult] =
    nextResult()
}
val actor = spawn(EventSourcedWorkflowActor("kafka-progress", pendingEngine), "kafka-progress")
val reply = createTestProbe[Reply]()

actor ! DefineWorkflow(kafkaWorkflow, expectedRevision = 0L, reply.ref)
reply.expectMessage(Defined("kafka-progress", revision = 1L))
actor ! ExecuteManual("run-1", reply.ref)
val first = reply.expectMessageType[ExecutionAccepted]

val frozenBoundary = "{\"0\":50}"
val boundary = SnapshotBoundary("source-1", "kafka-boundary-1", Some(frozenBoundary))
actor ! InitializeSnapshot(first.executionId, boundary, reply.ref)
reply.expectMessage(SnapshotInitialized(boundary))
val checkpoint = BatchCheckpoint(
  sourceNodeId = "source-1",
  partitionId = "kafka-boundary-1",
  batchSequence = 0L,
  batchId = BatchId.sha256(first.executionId, "source-1", "kafka-boundary-1", 0L),
  cursor = SourceCursor("kafka.offsets.v1", "{\"0\":30}", frozenBoundary),
  sourceRowsScanned = 10L,
  targetRowsWritten = 10L
)
actor ! AdvanceCheckpoint(first.executionId, checkpoint, reply.ref)
reply.expectMessage(CheckpointAccepted(checkpoint))
firstCompletion.success(ExecutionResult("failed", success = false, "planned-test-failure", None, Some(1L)))
eventuallySummary(actor)(_.status shouldBe WorkflowStatus.Failed)

actor ! ExecuteManual("run-2", reply.ref)
val second = reply.expectMessageType[ExecutionAccepted]
second.executionId should not be first.executionId
val stateProbe = createTestProbe[ReliableRunState]()
actor ! GetReliableRunState(stateProbe.ref)
stateProbe.receiveMessage().currentExecution.value.checkpoints.single.cursor.value should include("\"0\":30")
```

Define `kafkaWorkflow` in the test by copying the existing reliable linear
fixture and changing only its source node type to `kafka.consumer`; register a
test `CheckpointedNodeSource` exactly as the existing reliable-checkpoint test
does. Keep both promises pending until the assertions for their run are done,
and complete `secondCompletion` in test cleanup so the actor does not leak work.

- [ ] **Step 2: Verify RED**

Run actor specs. Expected: missing progress field and next-run checkpoint empty.

- [ ] **Step 3: Implement derived progress without new events**

Decode the persisted workflow definition in deterministic helper functions.
Recognize workflow-scoped progress only for source node type `kafka.consumer`
and cursor kind `KafkaCheckpointCodec.CursorKind`. Update the projection in the
existing event handler. Seed only when revision, node ID, and node type match.
Clear progress on `WorkflowDefined`.

- [ ] **Step 4: Verify GREEN and recovery**

Run:

```bash
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActorSpec' \
    'pekko-server/testOnly cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActorRecoverySpec'
```

- [ ] **Step 5: Review checkpoint**

If commit is authorized, commit the three actor files as
`feat(workflow): persist Kafka progress across runs`.

---

### Task 6: Environment-backed MySQL passwords

**Files:**
- Create: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/JdbcPasswordResolver.scala`
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLSnapshotSourceConfig.scala`
- Create: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sinks/MySQLSinkConfig.scala`
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sinks/MySQLSinkNode.scala`
- Modify: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLSnapshotSourceNodeSpec.scala`
- Modify: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sinks/MySQLSinkNodeSpec.scala`

**Interfaces:**
- Produce:

```scala
object JdbcPasswordResolver {
  def resolve(
    fields: Map[String, JsValue],
    getenv: String => Option[String] = sys.env.get
  ): String
}
```

- `MySQLSnapshotSourceConfig.parse` and `MySQLSinkConfig.parse` accept an
  injectable environment lookup for tests.

- [ ] **Step 1: Write secret-resolution tests**

Test inline compatibility, `passwordEnv` success, both/missing modes,
missing/empty environment values, and error messages that contain no secret.
Serialize the workflow node and assert the resolved value never appears.

```scala
val fields = Map("passwordEnv" -> JsString("WORKFLOW_DB_PASSWORD"))
JdbcPasswordResolver.resolve(fields, name => Option.when(name == "WORKFLOW_DB_PASSWORD")("s3cr3t")) shouldBe "s3cr3t"

val failure = intercept[IllegalArgumentException] {
  JdbcPasswordResolver.resolve(fields, _ => None)
}
failure.getMessage should include("WORKFLOW_DB_PASSWORD")
failure.getMessage should not include "s3cr3t"
```

- [ ] **Step 2: Verify RED**

Run source/sink specs; expect `passwordEnv` to be rejected or ignored.

- [ ] **Step 3: Implement the shared resolver and extract sink config**

Require exactly one of non-empty string `password` and non-empty string
`passwordEnv`. Resolve only at node setup/validation. Never log the returned
value. Preserve existing inline-password tests for backward compatibility.

- [ ] **Step 4: Verify GREEN**

Run both MySQL node specs and `WorkflowExecutionEngineSpec`.

- [ ] **Step 5: Review checkpoint**

If commit is authorized, stage only the six files and commit as
`feat(mysql): resolve node passwords from environment`.

---

### Task 7: Reliable Kafka-to-sink integration regression

**Files:**
- Create: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/integration/BoundedKafkaRecoverySpec.scala`
- Modify: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/engine/WorkflowExecutionEngineSpec.scala`
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/registry/NodeRegistry.scala` only if construction must pass a production dependency explicitly.

**Interfaces:**
- Consumes: real `KafkaSource` batching contract, `ReliableRunContext`,
  checkpoint-aware sink, workflow-level progress.
- Produces: one deterministic in-process recovery scenario with no external
  service dependency.

- [ ] **Step 1: Write a failing integration scenario**

Use a fake `KafkaClientAccess` with 25 records across three partitions and a
checkpoint-aware test sink that records commit attempts. Execute two runs:

1. first run crashes after sink commit for batch 2 but before checkpoint
   acknowledgement;
2. recovered same execution gets `AlreadyCommitted`, advances checkpoint, and
   finishes;
3. second scheduled execution receives only newly appended fake records.

Assert unique output IDs, stable batch IDs on replay, expected ledger count,
and no Gravitino call after boundary persistence.

```scala
final case class AttemptTrace(
  committedBatchIds: Vector[String],
  expectedBatchIds: Vector[String],
  sourceRows: Vector[String]
)

firstAttempt.committedBatchIds.take(2) shouldBe firstAttempt.expectedBatchIds.take(2)
recoveredAttempt.committedBatchIds.head shouldBe firstAttempt.expectedBatchIds(1)
allTargetIds.distinct shouldBe allTargetIds
allTargetIds.sorted shouldBe (1 to 25).map(i => f"event-$i%04d")
secondRun.sourceRows shouldBe Vector("event-0026", "event-0027")
resolver.calls.get shouldBe resolverCallsRecordedAfterBoundaryPersistence
```

Capture `resolverCallsRecordedAfterBoundaryPersistence` immediately after the
first `SnapshotBoundary` has been accepted and assert the counter remains
unchanged through recovery. Derive `expectedBatchIds` with `BatchId.sha256`
from the actual execution ID, source ID, persisted partition ID, and sequence;
do not copy IDs out of the implementation under test.

- [ ] **Step 2: Verify RED**

Run the new spec and confirm failure at the first missing recovery behavior,
not test setup.

- [ ] **Step 3: Make only integration-driven corrections**

Fix production code only where the scenario reveals contract mismatch. Do not
add broker failover, producers, or new sink behavior.

- [ ] **Step 4: Verify GREEN and registry truthfulness**

Run:

```bash
sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.integration.BoundedKafkaRecoverySpec' \
    'pekko-server/testOnly cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngineSpec'
```

Assert `NodeRegistry.findSource("kafka.consumer")` is a
`CheckpointedNodeSource`; the registered built-in must no longer expose demo
behavior.

- [ ] **Step 5: Review checkpoint**

If commit is authorized, commit only the integration and necessary registry/
engine files as `test(kafka): cover bounded recovery pipeline`.

---

### Task 8: Kubernetes and idempotent bootstrap assets

**Files:**
- Create: `deploy/k8s/bigdata-lab/application-k8s.conf`
- Create: `deploy/k8s/bigdata-lab/headless-service.yaml`
- Create: `deploy/k8s/bigdata-lab/api-service.yaml`
- Create: `deploy/k8s/bigdata-lab/statefulset.yaml`
- Create: `deploy/k8s/bigdata-lab/pod-disruption-budget.yaml`
- Create: `deploy/k8s/bigdata-lab/kustomization.yaml`
- Create: `deploy/k8s/bigdata-lab/bootstrap-mysql.sh`
- Create: `deploy/k8s/bigdata-lab/bootstrap-gravitino.sh`
- Create: `deploy/k8s/bigdata-lab/run-e2e.sh`
- Create: `deploy/k8s/bigdata-lab/README.md`

**Interfaces:**
- Consumes: image `pekko-reference:bounded-kafka-mvp`, Secret
  `pekko-workflow-db`, existing services `mysql`, `kafka`, `gravitino`.
- Produces: two-pod StatefulSet, headless/API services, PDB, safe bootstrap and
  acceptance scripts.

- [ ] **Step 1: Record the missing-manifest RED check**

Before creating files, run:

```bash
kubectl kustomize deploy/k8s/bigdata-lab
```

Expected: FAIL because `kustomization.yaml` does not exist.

- [ ] **Step 2: Create the Kustomize resources**

`application-k8s.conf` includes `classpath("application-prod.conf")`, sets minimum members
to 2, reads `PEKKO_HOSTNAME`, and uses these two stable seed FQDNs:

```hocon
pekko.cluster.seed-nodes = [
  "pekko://pekko-cluster-system-prod@pekko-workflow-0.pekko-workflow-headless.bigdata-lab.svc.cluster.local:2551",
  "pekko://pekko-cluster-system-prod@pekko-workflow-1.pekko-workflow-headless.bigdata-lab.svc.cluster.local:2551"
]
pekko.cluster.min-nr-of-members = 2
pekko.remote.artery.canonical.hostname = ${?PEKKO_HOSTNAME}
```

The StatefulSet uses:

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: pekko-workflow
  namespace: bigdata-lab
spec:
  serviceName: pekko-workflow-headless
  replicas: 2
  template:
    spec:
      affinity:
        podAntiAffinity:
          requiredDuringSchedulingIgnoredDuringExecution:
            - labelSelector:
                matchLabels: {app: pekko-workflow}
              topologyKey: kubernetes.io/hostname
      containers:
        - name: pekko-workflow
          image: pekko-reference:bounded-kafka-mvp
          imagePullPolicy: Never
          env:
            - name: DB_HOST
              value: mysql
            - name: DB_PORT
              value: "3306"
            - name: DB_NAME
              value: pekko_workflow
            - name: DB_USER
              value: pekko_workflow
            - name: DB_PASSWORD
              valueFrom: {secretKeyRef: {name: pekko-workflow-db, key: password}}
            - name: WORKFLOW_DB_PASSWORD
              valueFrom: {secretKeyRef: {name: pekko-workflow-db, key: password}}
```

The same StatefulSet must contain these exact remaining fields (merge them with
the fragment above, without replacing the generated image entrypoint):

```yaml
  selector:
    matchLabels: {app: pekko-workflow}
  template:
    metadata:
      labels: {app: pekko-workflow}
    spec:
      terminationGracePeriodSeconds: 60
      containers:
        - name: pekko-workflow
          command: ["/bin/sh", "-c"]
          args:
            - >-
              export PEKKO_HOSTNAME="${POD_NAME}.pekko-workflow-headless.bigdata-lab.svc.cluster.local";
              exec /opt/docker/bin/pekko-reference
          env:
            - name: POD_NAME
              valueFrom: {fieldRef: {fieldPath: metadata.name}}
            - name: PEKKO_PORT
              value: "2551"
            - name: HTTP_PORT
              value: "8080"
            - name: JAVA_OPTS
              value: "-Dconfig.file=/opt/docker/conf/application-k8s.conf"
          ports:
            - {name: artery, containerPort: 2551}
            - {name: http, containerPort: 8080}
          volumeMounts:
            - {name: application-k8s, mountPath: /opt/docker/conf/application-k8s.conf, subPath: application-k8s.conf, readOnly: true}
          resources:
            requests: {cpu: 250m, memory: 512Mi}
            limits: {cpu: "1", memory: 1Gi}
          livenessProbe:
            httpGet: {path: /health/live, port: http}
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet: {path: /health/ready, port: http}
            initialDelaySeconds: 10
            periodSeconds: 5
          lifecycle:
            preStop:
              exec: {command: ["/bin/sh", "-c", "sleep 10"]}
      volumes:
        - name: application-k8s
          configMap: {name: pekko-workflow-config}
```

The headless Service sets `clusterIP: None`, `publishNotReadyAddresses: true`,
and exposes named port `artery:2551`. The API Service is `ClusterIP` and exposes
`http:8080`. The PDB uses `policy/v1`, selects `app: pekko-workflow`, and sets
`minAvailable: 1`.

- [ ] **Step 3: Create safe bootstrap scripts**

All scripts use `set -euo pipefail` and never `set -x`.

`bootstrap-mysql.sh` requires `WORKFLOW_DB_PASSWORD`, creates database/user
only if absent, applies the checked-in persistence and ledger schemas, creates
`pekko_kafka_e2e_sink`, and creates/updates Secret via
`kubectl create secret ... --dry-run=client -o yaml | kubectl apply -f -`.
SQL is sent on stdin to `mysql`; neither root nor application password appears
in command arguments printed by the script.

`bootstrap-gravitino.sh` performs GET-before-POST against
`http://gravitino:8090/api`, creates metalake `pekko` and catalog
`bigdata-kafka` with this exact body, and rejects mismatches:

```json
{
  "name": "bigdata-kafka",
  "type": "messaging",
  "provider": "kafka",
  "comment": "Pekko workflow Kafka catalog",
  "properties": {"bootstrap.servers": "kafka:9092"}
}
```

`run-e2e.sh` creates topic `pekko-workflow-e2e` only when absent, verifies
three partitions, produces IDs `event-0001` through `event-0050`, submits the
workflow, and asserts database counts. It does not delete any resource.

- [ ] **Step 4: Verify rendered manifests and shell syntax**

Run:

```bash
kubectl kustomize deploy/k8s/bigdata-lab > /tmp/pekko-workflow-rendered.yaml
kubectl apply --dry-run=client -f /tmp/pekko-workflow-rendered.yaml
bash -n deploy/k8s/bigdata-lab/bootstrap-mysql.sh
bash -n deploy/k8s/bigdata-lab/bootstrap-gravitino.sh
bash -n deploy/k8s/bigdata-lab/run-e2e.sh
rg -n 'passwordEnv|secretKeyRef' deploy/k8s/bigdata-lab
! rg -n --ignore-case '(password|secret|token)[a-z0-9_]*[[:space:]]*[:=][[:space:]]*["'"'"'][^$<{]' deploy/k8s/bigdata-lab \
  | rg -v 'set-in-your-shell'
```

Expected: render/dry-run/syntax pass; the first scan finds the intentional
environment/Secret references, while the credential-literal scan returns no
matches after excluding the documented placeholder.

- [ ] **Step 5: Review checkpoint**

If commit is authorized, stage only `deploy/k8s/bigdata-lab` and commit as
`feat(deploy): add bigdata-lab Pekko topology`.

---

### Task 9: Local verification and Linux AMD64 image

**Files:**
- Modify only files required by failing verification from Tasks 1-8.

**Interfaces:**
- Produces: compiled package and node-local Docker image
  `pekko-reference:bounded-kafka-mvp`.

- [ ] **Step 1: Run focused suites**

```bash
sbt \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.KafkaSourceConfigSpec' \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.GravitinoTopicResolverSpec' \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.KafkaCheckpointCodecSpec' \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sources.KafkaSourceSpec' \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActorSpec' \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActorRecoverySpec' \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.integration.BoundedKafkaRecoverySpec'
```

- [ ] **Step 2: Run compile and broader regression**

```bash
sbt 'pekko-server/Test/compile' 'pekko-server/test'
```

Do not weaken or filter a failing test. Classify external tests separately.

- [ ] **Step 3: Stage the native-packager Docker context**

```bash
sbt 'pekko-server/Docker/stage'
```

Inspect the generated entrypoint and verify it honors `JAVA_OPTS`; the
StatefulSet invokes that generated script and supplies
`-Dconfig.file=/opt/docker/conf/application-k8s.conf`.

- [ ] **Step 4: Build for the cluster architecture**

Both Kubernetes nodes are Linux AMD64 with Docker runtimes. Run:

```bash
docker buildx build --platform linux/amd64 \
  -t pekko-reference:bounded-kafka-mvp \
  --load pekko-server/target/docker/stage
docker image inspect pekko-reference:bounded-kafka-mvp \
  --format '{{.Architecture}} {{.Os}}'
```

Expected: `amd64 linux`.

- [ ] **Step 5: Final local diff review**

Run `git diff --check`, focused `git diff --stat`, and `git status --short`.
Confirm `.tasks/` remains untracked and no generated `target/` file is staged.

---

### Task 10: Deploy and run real `bigdata-lab` acceptance

**Files:**
- No repository changes unless a real failure produces a test-first code correction.

**Interfaces:**
- Consumes: approved manifests/scripts/image and SSH access to `xjw`, `xxt`.
- Produces: real Kafka/MySQL/Gravitino evidence and one scoped Pekko pod-failure recovery result.

- [ ] **Step 1: Reconfirm exact external targets read-only**

On `xxt`, run:

```bash
kubectl -n bigdata-lab get deploy kafka mysql gravitino
kubectl -n bigdata-lab get statefulset,service,configmap,pdb | grep pekko-workflow || true
kubectl get nodes -o wide
```

Proceed only when shared services are Ready and no conflicting
`pekko-workflow` resources exist. An exact matching prior deployment is
updated; a name collision with different ownership stops execution.

- [ ] **Step 2: Import the image on both Docker-backed nodes**

```bash
docker save pekko-reference:bounded-kafka-mvp | ssh xjw@xjw 'docker load'
docker save pekko-reference:bounded-kafka-mvp | ssh xxt@xxt 'docker load'
```

If Docker requires sudo, use interactive sudo without embedding passwords.
Verify `docker image inspect` on both hosts.

- [ ] **Step 3: Upload deployment assets and bootstrap isolated state**

Copy `deploy/k8s/bigdata-lab` to an explicit temporary directory on `xxt`.
Run `bootstrap-mysql.sh` with a generated application password supplied only
through the process environment, then `bootstrap-gravitino.sh`. Verify:

```bash
kubectl -n bigdata-lab get secret pekko-workflow-db
kubectl -n bigdata-lab exec deploy/gravitino -- \
  curl -fsS http://127.0.0.1:8090/api/metalakes/pekko/catalogs/bigdata-kafka
```

The Gravitino curl runs inside its pod or another namespace pod, not against a
localhost NodePort.

- [ ] **Step 4: Apply and observe the Pekko topology**

```bash
kubectl apply -k /tmp/pekko-bounded-kafka/deploy/k8s/bigdata-lab
kubectl -n bigdata-lab rollout status statefulset/pekko-workflow --timeout=180s
kubectl -n bigdata-lab get pods -l app=pekko-workflow -o wide
```

Expected: two Ready pods, one on `xjw`, one on `xxt`. Port-forward the ClusterIP
API and require `/health/live` and `/health/ready` HTTP 200.

- [ ] **Step 5: Run real Kafka/Gravitino/MySQL flow**

Run `run-e2e.sh`. Verify:

- Gravitino lists `pekko-workflow-e2e`;
- first run writes 50 unique IDs and five ledger rows;
- appending 12 records then running again writes only 12 new IDs; and
- workflow history shows two completed executions with no source error.

- [ ] **Step 6: Run the approved single-pod recovery test**

Produce enough new records for at least five batches. Start one execution,
identify the entity-hosting pod from node-attributed logs, and wait for two
ledger commits. Delete exactly that pod:

```bash
PEKKO_POD=pekko-workflow-0
case "$PEKKO_POD" in
  pekko-workflow-0|pekko-workflow-1) printf '%s\n' "$PEKKO_POD" ;;
  *) printf 'refusing unexpected pod name: %s\n' "$PEKKO_POD" >&2; exit 1 ;;
esac
kubectl -n bigdata-lab delete pod "$PEKKO_POD"
```

The executor must substitute only a name already verified to match
`^pekko-workflow-[01]$`. Do not use a glob or variable before printing and
validating the exact name. Wait for the remaining pod to recover and finish,
then for StatefulSet readiness 2/2. If recovery fails, stop without deleting
the second pod.

- [ ] **Step 7: Collect evidence without cleanup**

Capture:

- `kubectl get pods -o wide`;
- Pekko cluster/readiness output;
- workflow execution history;
- target row uniqueness/count;
- ledger batch IDs/checkpoints;
- Gravitino catalog/topic response; and
- relevant logs from both Pekko pods.

Retain the acceptance topic, Gravitino metadata, database, user, and deployed
Pekko resources. Their deletion is destructive and requires separate user
authorization.

- [ ] **Step 8: Report evidence boundaries**

Report compile, focused tests, full suite, real services, and pod recovery as
separate evidence. Explicitly retain these limits: Kafka broker HA untested,
MySQL HA untested, network-partition safety unproven, arbitrary sink
exactly-once unsupported, and Binlog CDC not implemented.

---

### Task 11: Replace static Kubernetes seeds with Cluster Bootstrap

**Files:**
- Modify: `build.sbt`
- Modify: `pekko-server/src/main/resources/reference.conf`
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/config/ConfigValidator.scala`
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/server/PekkoClusterService.scala`
- Create: `pekko-server/src/test/scala/cn/xuyinyin/magic/server/ClusterBootstrapConfigSpec.scala`
- Modify: `deploy/k8s/bigdata-lab/application-k8s.conf`
- Modify: `deploy/k8s/bigdata-lab/statefulset.yaml`
- Modify: `deploy/k8s/bigdata-lab/kustomization.yaml`
- Create: `deploy/k8s/bigdata-lab/rbac.yaml`
- Modify: `deploy/k8s/bigdata-lab/README.md`

**Interfaces:**
- `pekko.workflow.cluster-bootstrap.enabled: Boolean` defaults to `false`.
- `PekkoClusterService.shouldStartClusterBootstrap(config): Boolean` is package-visible for a focused decision test.
- Kubernetes enables Bootstrap, resolves `seed-nodes = []`, discovers `app=pekko-workflow`, and requires two contact points on named port `management` 7626.
- Static production, development, and test profiles keep their existing seed behavior.

- [ ] **Step 1: Write the failing configuration/decision tests**

Create `ClusterBootstrapConfigSpec.scala` with literal configurations proving:

1. enabled Bootstrap plus empty seed nodes is accepted and selected;
2. enabled Bootstrap plus non-empty static seeds is rejected;
3. disabled Bootstrap plus empty static seeds is rejected; and
4. default production configuration remains static-seed mode.

- [ ] **Step 2: Verify RED**

Run:

```bash
COURSIER_REPOSITORIES=https://maven.aliyun.com/repository/central \
  sbt 'pekko-server/testOnly cn.xuyinyin.magic.server.ClusterBootstrapConfigSpec'
```

Expected: compilation fails because the Bootstrap enable key and service decision do not exist.

- [ ] **Step 3: Add the minimal compatible runtime**

Use Pekko Management `1.1.1`, explicit Pekko Discovery `1.1.3`, and Pekko HTTP
`1.1.0`. Add Management Cluster HTTP, Cluster Bootstrap, and Kubernetes API
Discovery. When the enable key is true, start `PekkoManagement` and
`ClusterBootstrap` immediately after ActorSystem creation; terminate the
system if Management binding fails. Never start them in static-seed mode.

Validation must require empty seeds in Bootstrap mode and non-empty seeds in
static mode. Remove the Kubernetes-specific Bootstrap defaults from shared
`reference.conf`; keep only the disabled application switch there.

- [ ] **Step 4: Add the Kubernetes discovery contract**

Set the K8s resolved configuration to empty seeds, service name
`pekko-workflow`, method `kubernetes-api`, named port `management`, two required
contact points, 5-second stable margin, Management hostname from `POD_IP`, bind
hostname `0.0.0.0`, and port 7626. Add `POD_IP`, the named container port, a
dedicated ServiceAccount, and namespace-scoped Pod `get/list/watch` RBAC.

- [ ] **Step 5: Verify GREEN and package compatibility**

Run the focused spec, `pekko-server/Test/compile`, the exact unfiltered suite,
`pekko-server/Compile/dependencyTree`, `pekko-server/evicted`, Kustomize render,
Kubernetes client dry-run, shell syntax checks, credential scan, and
`git diff --check`. Require Pekko Core 1.1.3, Management 1.1.1, and Pekko HTTP
1.1.0 without relevant eviction conflicts. Do not stage or commit.

---

### Task 12: Rebuild and prove Bootstrap recovery live

**Files:**
- Update only the Task 10 report and SDD ledger with observed evidence.

- [ ] **Step 1: Build and import an immutable corrective image**

Build Linux AMD64 after Task 11 passes, inspect its user and dependency
contents, and checksum-verify the same image on `xjw` and `xxt`.

- [ ] **Step 2: Preflight RBAC and exact live targets**

Require both Kubernetes nodes Ready and Kafka/MySQL/Gravitino 1/1. Apply RBAC,
verify the dedicated ServiceAccount can get/list/watch Pods, and confirm no
resource outside the `pekko-workflow` prefix changes.

- [ ] **Step 3: Perform one controlled cold migration**

Pause new workflow starts, apply the new image/config/template, and replace
both existing Pekko pods once so old static-seed and new Bootstrap mechanisms
never coexist. This temporarily removes the Pekko API only; workflow durable
state remains in MySQL. Rollback restores the previous image/config and repeats
the same controlled cold start. Never restart Kafka, MySQL, or Gravitino.

- [ ] **Step 4: Require one healthy two-member cluster**

Both pods must report Management bound on 7626, Kubernetes API discovery, one
deterministic initial self-join, the other joining it, identical two-member Up
views, application readiness 200, JDBC available, and sharding initialized.
Stop before E2E if any condition fails.

- [ ] **Step 5: Run the deterministic in-flight takeover**

Use an isolated one-partition topic/table/workflow and a MySQL named-lock
trigger. With `chunkSize=10` and 30 records, hold batch 3 after exactly two
committed ledger rows. Proceed only when logs prove the workflow entity is on
pod 0 and pod 1 is Ready. Delete exactly pod 0 once, prove its old UID/process
exits and pod 1 restarts the same execution ID while target/ledger remain at
20/2, then release the lock.

- [ ] **Step 6: Verify durable completion and rejoin**

Require the same execution ID to complete with exactly 30 unique target rows
and three unique ledger batches (`0,1,2`), source/target sums 30/30, cursor kind
`kafka.offsets.v1`, third commit after old pod exit, and the recreated pod 0 to
return the StatefulSet to 2/2. Stop without deleting pod 1 on any failure.

---

## Plan Self-Review Map

| Spec requirement | Implemented by |
|---|---|
| Configuration and connection modes | Task 1 |
| Gravitino catalog/topic validation | Task 2 |
| Versioned canonical boundary/cursor | Task 3 |
| Deterministic bounded Kafka batches | Task 4 |
| Existing MySQL snapshot compatibility | Tasks 4 and 9 |
| Workflow-level Kafka progress without new events | Task 5 |
| Secret-free workflow definitions | Task 6 |
| Commit/replay integration | Task 7 |
| Initial static Kubernetes topology | Task 8 |
| Corrective Kubernetes Cluster Bootstrap | Task 11 |
| Compile/full regression/package | Task 9 |
| Real services and single-pod failure | Tasks 10 and 12 |
| Evidence limits and non-goals | Tasks 9, 10, and 12 |

No task deletes shared or newly created external data. No task implements
Binlog CDC or changes the shared Kafka/MySQL/Gravitino deployments.
