# Reliable Multi-Node MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a reliable single-data-center, multi-node Pekko workflow MVP with recoverable definitions, correct linear execution, JDBC persistence, one persistent scheduler, consistent HTTP state, and failover tests.

**Architecture:** Keep Pekko Typed, Cluster Sharding, Event Sourcing, HTTP, and Streams. Make the workflow entity authoritative, use MySQL-compatible JDBC persistence, run scheduling through a persistent coordinator-role Cluster Singleton, and accept only one connected Source-to-Sink path.

**Tech Stack:** Scala 2.13.12, Apache Pekko 1.1.3, Pekko HTTP 1.0.1, Pekko Persistence JDBC 1.1.1 candidate, MySQL 8, H2 2.3.232, Spray JSON, ScalaTest.

**Spec:** `docs/superpowers/specs/2026-08-29-reliable-multinode-mvp-design.md`

## Global Constraints

- Preserve the public route families listed in the spec.
- Support exactly one Source, exactly one Sink, and zero or more Transforms in one connected path.
- Exclude branching DAGs, real Kafka, DataFusion, frontend redesign, multi-data-center behavior, authentication, and cross-Sink exactly-once.
- The workflow entity is authoritative for definition and execution state.
- Persist canonical Workflow JSON because Node config is a Spray `JsObject`; decode it at the Actor boundary.
- Use MySQL JDBC persistence in multi-node configuration and isolated H2 JDBC in hermetic tests.
- Keep Pekko at 1.1.3 unless verified binary incompatibility requires a separately reviewed upgrade.
- Do not touch `.tasks/`, run destructive external tests by default, commit, or push.
- Follow test-first RED, GREEN, REFACTOR for every behavior change.

---

### Task 1: Linear Pipeline Contract and Structured Results

**Files:**
- Create: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/WorkflowValidator.scala`
- Create: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/ExecutionModels.scala`
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/WorkflowExecutionEngine.scala`
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/executors/TransformExecutor.scala`
- Test: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/engine/WorkflowValidatorSpec.scala`
- Test: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/engine/WorkflowExecutionEngineSpec.scala`
- Create test fixture: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/WorkflowFixtures.scala`

**Interfaces:**
- Produces `WorkflowValidator.validate(workflow): Either[Vector[WorkflowValidationError], ValidatedPipeline]`.
- Produces `ValidatedPipeline(source: Node, transforms: Vector[Node], sink: Node)`.
- Extends `ExecutionResult` with `nodeResults: Vector[NodeExecutionResult]`.
- Produces `WorkflowFixtures` with linear, branched, disconnected, and failing-Sink definitions for downstream tests.

- [ ] **Step 1: Write failing validator tests**

```scala
"WorkflowValidator" should "return the only connected Source to Sink path" in {
  val result = WorkflowValidator.validate(WorkflowFixtures.linearWorkflow).toOption.value
  result.transforms.map(_.id) shouldBe Vector("transform-1")
}

it should "reject branches and disconnected nodes" in {
  WorkflowValidator.validate(WorkflowFixtures.branchedWorkflow).left.value.map(_.code) should contain("branch_not_supported")
  WorkflowValidator.validate(WorkflowFixtures.disconnectedWorkflow).left.value.map(_.code) should contain("disconnected_node")
}
```

- [ ] **Step 2: Verify RED**

Run: `sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.engine.WorkflowValidatorSpec'`

Expected: compilation fails because validator types do not exist.

- [ ] **Step 3: Implement the validator**

```scala
final case class WorkflowValidationError(code: String, message: String)
final case class ValidatedPipeline(source: Node, transforms: Vector[Node], sink: Node)
```

Build node and degree maps, require one Source and Sink, walk the only outgoing edge from Source, and require the walk to visit every node. Reject cycles, branches, merges, unknown endpoints, disconnected nodes, and executor-advertised types without match cases.

- [ ] **Step 4: Verify GREEN**

Run: `sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.engine.WorkflowValidatorSpec'`

Expected: all validator cases pass.

- [ ] **Step 5: Write failing structured-result test**

```scala
val result = Await.result(engine.execute(WorkflowFixtures.failingSinkWorkflow, "exec-1", _ => ()), 5.seconds)
result.success shouldBe false
result.nodeResults.last.status shouldBe "failed"
```

- [ ] **Step 6: Verify RED, implement minimal structured results, then verify GREEN**

Run before and after: `sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngineSpec'`

Expected before: `nodeResults` is absent. Expected after: engine executes only the validated path and reports a failed node without converting it to success.

---

### Task 2: Persistent Workflow Protocol and Entity State

**Files:**
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/events/WorkflowEvents.scala`
- Rewrite: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/actors/EventSourcedWorkflowActor.scala`
- Test: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/actors/EventSourcedWorkflowActorSpec.scala`
- Test: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/actors/EventSourcedWorkflowActorRecoverySpec.scala`

**Interfaces:**
- Consumes Task 1 `ExecutionResult`.
- Produces `DefineWorkflow`, `ExecuteManual`, `ExecuteScheduled`, `GetSummary`, `GetStatus`, and `GetExecutionHistory`.
- Produces `Defined`, `RevisionConflict`, `ExecutionAccepted`, `DuplicateExecution`, `AlreadyRunning`, `NotInitialized`, and `DefinitionRejected`.
- Produces bounded `WorkflowState(workflowJson, revision, status, currentExecution, recentExecutions, lastAcceptedTriggerBySchedule, manualRequests)`.

- [ ] **Step 1: Write failing definition and uninitialized tests**

```scala
entity ! DefineWorkflow(WorkflowFixtures.linearWorkflow, expectedRevision = 0L, reply.ref)
reply.expectMessage(Defined("workflow-1", revision = 1L))

emptyEntity ! ExecuteManual("request-1", executeReply.ref)
executeReply.expectMessage(NotInitialized("workflow-1"))
```

- [ ] **Step 2: Verify RED**

Run: `sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActorSpec'`

Expected: new protocol and state do not exist.

- [ ] **Step 3: Define CBOR-marked protocol and persisted events**

```scala
sealed trait Command extends CborSerializable
sealed trait Reply extends CborSerializable
sealed trait WorkflowEvent extends CborSerializable
final case class WorkflowDefined(workflowJson: String, revision: Long, timestamp: Long) extends WorkflowEvent
final case class ExecutionStarted(executionId: String, trigger: ExecutionTrigger, timestamp: Long) extends WorkflowEvent
final case class ExecutionCompleted(executionId: String, result: PersistedExecutionResult, timestamp: Long) extends WorkflowEvent
final case class ExecutionFailed(executionId: String, result: PersistedExecutionResult, timestamp: Long) extends WorkflowEvent
```

- [ ] **Step 4: Implement definition, revision, execution, and idempotency transitions**

Use `Effect.persist(...).thenReply` before acknowledging definitions. Decode canonical Workflow JSON immediately before validation or execution. Persist `ExecutionFailed` whenever `ExecutionResult.success` is false. Keep one running execution, a bounded manual request index, and one last accepted time per schedule.

- [ ] **Step 5: Verify entity GREEN**

Run: `sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActorSpec'`

Expected: definition, revision, uninitialized, success, failure, duplicate, and already-running cases pass.

- [ ] **Step 6: Write failing recovery test**

```scala
defineAndExecuteScheduled(entity, scheduleId = "daily", scheduledAt = 1000L)
testKit.stop(entity)
val recovered = spawnSamePersistenceId()
summary(recovered).revision shouldBe 1L
executeScheduled(recovered, "daily", 1000L) shouldBe a[DuplicateExecution]
```

- [ ] **Step 7: Verify RED, remove `allEvents`, implement bounded recovery, verify GREEN**

Run before and after: `sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActorRecoverySpec'`

Expected after: workflow definition, revision, terminal status, and schedule watermark survive restart.

---

### Task 3: JDBC Persistence, Schema, and Resolved Configuration

**Files:**
- Modify: `build.sbt`
- Create: `pekko-server/src/main/resources/application-jdbc.conf`
- Modify: `pekko-server/src/main/resources/application-prod.conf`
- Modify: `pekko-server/src/main/resources/application-dev.conf`
- Modify: `pekko-server/src/test/resources/application-test.conf`
- Create: `pekko-server/src/main/resources/db/mysql/pekko-persistence-schema.sql`
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/config/ConfigValidator.scala`
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/PekkoServer.scala`
- Test: `pekko-server/src/test/scala/cn/xuyinyin/magic/config/JdbcPersistenceConfigSpec.scala`

**Interfaces:**
- Produces MySQL `jdbc-journal`, `jdbc-snapshot-store`, and `jdbc-read-journal` configuration using one shared Slick database.
- Produces `ConfigValidator.validateOrThrow(config): Unit` before ActorSystem creation.

- [ ] **Step 1: Write failing production and test configuration tests**

```scala
prod.getString("pekko.persistence.journal.plugin") shouldBe "jdbc-journal"
prod.getString("pekko.persistence.snapshot-store.plugin") shouldBe "jdbc-snapshot-store"
prod.getString("pekko-persistence-jdbc.shared-databases.slick.profile") shouldBe "slick.jdbc.MySQLProfile$"
test.getString("pekko-persistence-jdbc.shared-databases.slick.profile") shouldBe "slick.jdbc.H2Profile$"
```

- [ ] **Step 2: Verify RED**

Run: `sbt 'pekko-server/testOnly cn.xuyinyin.magic.config.JdbcPersistenceConfigSpec'`

Expected: JDBC plugin keys and dependency are absent.

- [ ] **Step 3: Add the dependency, official version-matched schema, and shared DB config**

```scala
val pekkoPersistenceJdbcVersion = "1.1.1"
libraryDependencies += "org.apache.pekko" %% "pekko-persistence-jdbc" % pekkoPersistenceJdbcVersion
```

Production values read `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, and `DB_PASSWORD`. No real credential is embedded.

- [ ] **Step 4: Add startup validation**

```scala
ConfigValidator.validateOrThrow(PekkoConfig.root)
```

Validate roles, shard count, seed nodes in production, JDBC plugin IDs, JDBC URL, and schema settings.

- [ ] **Step 5: Verify GREEN and dependency compatibility**

Run: `sbt 'pekko-server/testOnly cn.xuyinyin.magic.config.JdbcPersistenceConfigSpec' 'pekko-server/dependencyTree'`

Expected: tests pass and no Pekko binary eviction conflict is reported.

- [ ] **Step 6: Verify Task 2 recovery through H2 JDBC**

Run: `sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.actors.EventSourcedWorkflowActorRecoverySpec'`

Expected: recovery passes with JDBC Journal and Snapshot Store.

---

### Task 4: Sharding and Stateless Supervisor

**Files:**
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/sharding/WorkflowSharding.scala`
- Rewrite: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/actors/WorkflowSupervisor.scala`
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/cluster/PekkoGuardian.scala`
- Test: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/sharding/WorkflowShardingPropertySpec.scala`
- Test: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/actors/WorkflowSupervisorSpec.scala`

**Interfaces:**
- Consumes Task 2 commands and replies.
- Produces exactly one Sharding envelope per Supervisor request without self-triggering adapters or runtime casts.

- [ ] **Step 1: Write failing one-message routing tests**

```scala
supervisor ! CreateWorkflow(WorkflowFixtures.linearWorkflow, reply.ref)
shardRegion.expectMessageType[ShardingEnvelope[DefineWorkflow]]
shardRegion.expectNoMessage(200.millis)
```

- [ ] **Step 2: Verify RED**

Run: `sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.actors.WorkflowSupervisorSpec'`

Expected: current adapters repeat commands or new typed protocol is missing.

- [ ] **Step 3: Replace adapters with direct typed forwarding**

The Supervisor owns no workflow map. Commands already carry correct entity reply references; send one envelope and return `Behaviors.same`. Remove legacy/EventSourced switching and `asInstanceOf` casts.

- [ ] **Step 4: Fix Sharding construction and hash edge case**

Construct an uninitialized entity with workflowId and dependencies only. Use `Math.floorMod(entityId.hashCode, numberOfShards)`.

- [ ] **Step 5: Verify GREEN**

Run: `sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.sharding.WorkflowShardingPropertySpec cn.xuyinyin.magic.workflow.actors.WorkflowSupervisorSpec'`

Expected: one message per request, non-negative Shard IDs, and no fake Workflow.

---

### Task 5: Persistent Cluster Singleton Scheduler

**Files:**
- Create: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/scheduler/ScheduleCalculator.scala`
- Create: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/scheduler/SchedulerCoordinator.scala`
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/cluster/PekkoGuardian.scala`
- Retire from active wiring: `WorkflowScheduler.scala`, `SchedulerManager.scala`
- Test: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/scheduler/ScheduleCalculatorSpec.scala`
- Test: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/scheduler/SchedulerCoordinatorSpec.scala`
- Test: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/scheduler/SchedulerCoordinatorRecoverySpec.scala`

**Interfaces:**
- Consumes `ExecuteScheduled` and workflow replies.
- Produces typed add, update, pause, resume, remove, and list commands.
- Persists schedules and pending triggers; retries unacknowledged triggers.

- [ ] **Step 1: Write failing fixed and Cron calculation tests**

```scala
next(FixedRate(1.hour), instant("2026-08-29T00:00:00Z")) shouldBe instant("2026-08-29T01:00:00Z")
next(CronSchedule("0 0 * * *"), instant("2026-08-29T00:30:00Z")) shouldBe instant("2026-08-29T01:00:00Z")
ScheduleCalculator.validate(CronSchedule("not-cron")) shouldBe a[Left[_, _]]
```

- [ ] **Step 2: Verify RED, add cron4s 0.8.2, implement, verify GREEN**

```scala
libraryDependencies += "com.github.alonsodomin.cron4s" %% "cron4s-core" % "0.8.2"
```

Run before and after: `sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.scheduler.ScheduleCalculatorSpec'`

Expected after: real next-occurrence calculation replaces the one-hour placeholder.

- [ ] **Step 3: Write failing delivery and recovery tests**

```scala
coordinator ! Fire(scheduleId, scheduledAt)
workflowProbe.expectMessageType[ExecuteScheduled]
state(coordinator).pendingTriggers.keySet should contain(triggerId)
acknowledge(triggerId, ExecutionAccepted("exec-1"))
state(coordinator).pendingTriggers shouldBe empty
```

- [ ] **Step 4: Verify RED**

Run: `sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.scheduler.SchedulerCoordinatorSpec cn.xuyinyin.magic.workflow.scheduler.SchedulerCoordinatorRecoverySpec'`

Expected: persistent coordinator does not exist.

- [ ] **Step 5: Implement persisted preparation, acknowledgement, retry, and recovery**

Persist `TriggerPrepared` before delivery. Use bounded exponential timer retry. Persist `TriggerAcknowledged` for Accepted, Duplicate, and AlreadyRunning. On recovery, rebuild timers and redeliver pending triggers.

- [ ] **Step 6: Wire coordinator-role Singleton and verify GREEN**

```scala
ClusterSingleton(ctx.system).init(
  SingletonActor(SchedulerCoordinator(shardRegion), "SchedulerCoordinator")
    .withSettings(ClusterSingletonSettings(ctx.system).withRole("coordinator"))
)
```

Run: `sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.scheduler.*'`

Expected: calculation, acknowledgement, retry, and recovery tests pass.

---

### Task 6: MySQL Sink Batch Failure Semantics

**Files:**
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sinks/MySQLSinkNode.scala`
- Test: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sinks/MySQLSinkNodeSpec.scala`

**Interfaces:**
- Produces failed materialized Future on batch failure and exact successful row count.
- Tests use an isolated H2 MySQL-mode table; no fake JDBC implementation or external database is required.

- [ ] **Step 1: Write failing batch and rollback tests**

```scala
runSink(rows = 250, batchSize = 100).futureValue shouldBe Done
selectCount("sink_rows") shouldBe 250
runBatchContainingDuplicateKey().failed.futureValue.getMessage should include("batch write failed")
selectCount("sink_rows") shouldBe 0
```

- [ ] **Step 2: Verify RED**

Run: `sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sinks.MySQLSinkNodeSpec'`

Expected: current Sink commits per row and swallows write exceptions.

- [ ] **Step 3: Implement grouped transactions and guaranteed resource closure**

Use `grouped(batchSize)` and one connection plus PreparedStatement batch per group. Roll back and rethrow on failure. Close statement, connection, and HikariDataSource on success, failure, and cancellation.

- [ ] **Step 4: Verify GREEN with engine failure test**

Run: `sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.nodes.sinks.MySQLSinkNodeSpec cn.xuyinyin.magic.workflow.engine.WorkflowExecutionEngineSpec'`

Expected: exact batch count and propagated failure tests pass.

---

### Task 7: Consistent HTTP, History, Readiness, and Test Discovery

**Files:**
- Rewrite: `pekko-server/src/main/scala/cn/xuyinyin/magic/api/http/routes/WorkflowRoutes.scala`
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/api/http/routes/EventHistoryRoutes.scala`
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/api/http/routes/SchedulerRoutes.scala`
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/api/http/routes/HttpRoutes.scala`
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/server/PekkoClusterService.scala`
- Modify: `pekko-server/src/main/scala/cn/xuyinyin/magic/cluster/HealthChecker.scala`
- Create: `pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/query/WorkflowQueryService.scala`
- Modify: `build.sbt`
- Create: `pekko-server/src/test/scala/cn/xuyinyin/magic/tags/ExternalIntegration.scala`
- Modify: `pekko-server/src/test/scala/cn/xuyinyin/magic/connectors/mysql/MySQLRealPerformanceTest.scala`
- Test: `pekko-server/src/test/scala/cn/xuyinyin/magic/api/http/routes/WorkflowRoutesSpec.scala`
- Test: `pekko-server/src/test/scala/cn/xuyinyin/magic/api/http/routes/EventHistoryRoutesSpec.scala`
- Test: `pekko-server/src/test/scala/cn/xuyinyin/magic/api/http/routes/SchedulerRoutesSpec.scala`
- Test: `pekko-server/src/test/scala/cn/xuyinyin/magic/api/http/routes/HealthRoutesSpec.scala`

**Interfaces:**
- Consumes Tasks 2 and 5 typed replies.
- Produces domain mapping to HTTP 400, 404, 409, 503, 504, and 500.
- Lists workflows through JDBC Read Journal IDs and Sharding summaries.

- [ ] **Step 1: Write failing HTTP contract tests**

```scala
Post("/api/v1/workflows", validWorkflowEntity) ~> routes ~> check { status shouldBe StatusCodes.Created }
Get("/api/history/wf-1/status") ~> runningRoutes ~> check {
  responseAs[JsObject].fields("state") shouldBe JsString("running")
}
```

- [ ] **Step 2: Verify RED**

Run: `sbt 'pekko-server/testOnly cn.xuyinyin.magic.api.http.routes.*Spec'`

Expected: current routes return local-map or placeholder responses.

- [ ] **Step 3: Remove route-owned state and implement typed reply mapping**

Wait for `Defined` before 201. Never recover a system failure into empty successful history. Scheduler add, pause, resume, remove, and list must Ask the Singleton proxy.

- [ ] **Step 4: Implement paginated JDBC query service and real readiness**

Use `JdbcReadJournal` persistence IDs prefixed `workflow-`, enforce a page-size cap, and Ask entities for summaries. Readiness requires member Up, initialized Sharding, and successful JDBC probe; liveness is process-only.

- [ ] **Step 5: Remove hard-coded networking**

Bind resolved `http.host` and `http.port`; do not overwrite configured seed nodes. Apply a remoting port override only when explicitly supplied.

- [ ] **Step 6: Replace source filtering with an external test tag**

```scala
object ExternalIntegration extends Tag("cn.xuyinyin.magic.tags.ExternalIntegration")
Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-l", "cn.xuyinyin.magic.tags.ExternalIntegration")
```

Remove `Test / sources` filtering and tag every test that uses the real database or truncates a table.

- [ ] **Step 7: Verify HTTP and full test compilation**

Run: `sbt 'pekko-server/testOnly cn.xuyinyin.magic.api.http.routes.*Spec' 'pekko-server/Test/compile'`

Expected: HTTP tests pass and every test source compiles without connecting to an external database.

---

### Task 8: Two-Node Recovery, Scheduler Failover, and Final Evidence

**Files:**
- Create: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/integration/TwoNodeWorkflowRecoverySpec.scala`
- Create: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/integration/SchedulerFailoverSpec.scala`
- Create: `pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/integration/MySQLPersistenceRecoverySpec.scala`
- Create: `pekko-server/src/test/resources/application-multinode-test.conf`
- Modify: `README.md`, `docs/CONFIGURATION.md`, `docs/DEPLOYMENT.md`, `docker-compose.yml`

**Interfaces:**
- Consumes all prior tasks and produces runtime acceptance evidence.

- [ ] **Step 1: Write failing two-node workflow recovery scenario**

Start two ActorSystems with distinct loopback ports, the same cluster name, worker/coordinator roles, and one temporary file-backed H2 JDBC database. Define and execute a workflow, terminate the entity-hosting node, then query and execute it through the remaining node.

- [ ] **Step 2: Verify RED, complete lifecycle wiring, verify GREEN**

Run before and after: `sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.integration.TwoNodeWorkflowRecoverySpec'`

Expected after: definition revision and terminal state survive node loss.

- [ ] **Step 3: Write failing Singleton failover scenario**

Prepare a trigger, terminate the Singleton-hosting node before acknowledgement, wait for takeover, and assert that one execution is accepted while subsequent delivery returns Duplicate.

- [ ] **Step 4: Verify RED, complete failover wiring, verify GREEN**

Run before and after: `sbt 'pekko-server/testOnly cn.xuyinyin.magic.workflow.integration.SchedulerFailoverSpec'`

Expected after: pending triggers resume and acknowledged triggers are not accepted twice.

- [ ] **Step 5: Run complete hermetic verification**

Run: `sbt scalafmtCheckAll 'pekko-server/Test/compile' 'pekko-server/test'`

Expected: exit 0 with external tests excluded by tag.

- [ ] **Step 6: Run optional isolated MySQL recovery verification**

Run with isolated credentials: `sbt 'set pekkoServer / Test / testOptions := Seq()' 'pekko-server/testOnly cn.xuyinyin.magic.workflow.integration.MySQLPersistenceRecoverySpec -- -n cn.xuyinyin.magic.tags.ExternalIntegration'`

The session override is required because the default suite excludes
`ExternalIntegration`; without clearing that default for this one sbt session,
combining `-l` and `-n` for the same tag executes zero tests.

Expected with configured isolated schema: Journal, Snapshot, and recovery pass. Without it, report `evidence_incomplete` and do not claim MySQL runtime verification.

- [ ] **Step 7: Align documentation with evidence**

Document the linear restriction, schema command, environment variables, two-node startup, at-least-once scheduler semantics, and external-test command. Remove unverified production-ready, availability, failover-time, DataFusion, and scaling claims.

- [ ] **Step 8: Inspect final changes**

Run: `git diff --check && git diff --stat && git status --short`

Expected: no whitespace errors; `.tasks/` remains untracked and untouched; every changed file maps to this plan.
