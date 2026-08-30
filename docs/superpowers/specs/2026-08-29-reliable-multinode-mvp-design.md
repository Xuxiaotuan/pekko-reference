# Reliable Multi-Node MVP Design

Date: 2026-08-29

## 1. Objective

Turn the current Pekko workflow prototype into a reliable single-data-center,
multi-node MVP. The MVP must preserve workflow definitions across entity and
node restarts, execute a deliberately linear pipeline with correct terminal
state, provide one recoverable cluster-wide scheduler, and expose consistent
HTTP status and history.

The implementation must prefer a small, verifiable correction of the existing
Pekko Typed, Cluster Sharding, Event Sourcing, HTTP, and Streams architecture.
It must not rewrite the runtime or introduce a second authoritative workflow
state store.

## 2. Scope

### Included

- Persisted workflow definitions and revisions.
- Correct workflow execution success and failure state.
- Linear `Source -> Transform* -> Sink` validation and execution.
- Node execution details persisted when an execution finishes.
- MySQL-backed Pekko Journal, Snapshot Store, and Read Journal.
- H2 JDBC persistence for hermetic tests.
- A persistent Cluster Singleton scheduler on coordinator nodes.
- At-least-once schedule-trigger delivery with entity-side deduplication.
- Consistent Sharding, Supervisor, HTTP status, history, and error semantics.
- Two-node recovery and scheduler failover tests.
- Explicit separation of hermetic tests from external MySQL performance tests.

### Excluded

- Branching or joining DAG execution.
- Multiple Sources or Sinks in one workflow.
- A real Kafka consumer.
- DataFusion integration.
- Frontend redesign.
- Multi-data-center singleton semantics.
- Exactly-once guarantees across an external Sink.
- Authentication and authorization.

## 3. Architectural Decision

Retain the existing architecture and close its correctness gaps:

```text
HTTP API
   |
   v
WorkflowSupervisor (stateless routing and protocol translation)
   |
   v
Cluster Sharding -- workflowId --> EventSourcedWorkflowActor
                                      |-- persisted Workflow definition
                                      |-- persisted execution state
                                      |-- linear Pipeline Engine
                                      `-- MySQL Journal and Snapshot Store

Cluster Singleton SchedulerCoordinator
   |-- persistent schedules and pending triggers
   `-- acknowledged delivery to Sharding entities
```

The Event Sourced workflow entity is the authoritative source for a workflow
definition and its execution state. HTTP-local maps are not authoritative.
Workflow listing uses the JDBC Read Journal to find workflow persistence IDs
and asks the corresponding Sharding entities for summaries.

## 4. Workflow Entity Model

Use one persistent state projection instead of storing the complete event list
inside every state and snapshot.

```scala
final case class WorkflowState(
  workflowJson: Option[String],
  revision: Long,
  status: WorkflowStatus,
  currentExecution: Option[ExecutionState],
  recentExecutions: Vector[ExecutionSummary],
  lastAcceptedTriggerBySchedule: Map[String, Instant],
  manualRequests: Vector[ManualRequestRecord]
)
```

`workflowJson` is the canonical Spray JSON representation of `Workflow`. This
keeps the persisted Jackson CBOR state independent of Spray `JsObject`
internals; the Actor decodes it to `Workflow` for validation and execution.

`recentExecutions` and `manualRequests` are bounded by configuration. Full
history remains in the Journal and is queried through Persistence Query.

### Commands and replies

- `DefineWorkflow(workflow, expectedRevision, replyTo)`
- `ExecuteManual(requestId, replyTo)`
- `ExecuteScheduled(scheduleId, scheduledAt, triggerId, replyTo)`
- `GetSummary(replyTo)`
- `GetStatus(replyTo)`
- `GetExecutionHistory(page, pageSize, replyTo)`
- Internal execution completion commands carrying structured results.

Replies distinguish:

- `Defined`
- `RevisionConflict`
- `ExecutionAccepted`
- `DuplicateExecution`
- `AlreadyRunning`
- `NotInitialized`
- `DefinitionRejected`
- `PersistenceUnavailable`

### Events

- `WorkflowDefined`
- `ExecutionStarted`
- `NodeExecutionRecorded`
- `ExecutionCompleted`
- `ExecutionFailed`
- `ExecutionSkipped`

The workflow entity replies to create or update only after `WorkflowDefined`
has been persisted. An uninitialized entity rejects execution. A failed engine
result produces `ExecutionFailed`; it can never produce `ExecutionCompleted`.

Only one execution may run per workflow. A scheduled trigger received while an
execution is running is acknowledged as `AlreadyRunning` and recorded as a
skipped execution. It is not queued indefinitely.

## 5. Idempotency

Scheduled trigger identity is deterministic:

```text
triggerId = hash(scheduleId + scheduledAt)
```

The entity stores the latest accepted `scheduledAt` for each schedule. A
trigger with an equal or earlier scheduled time is returned as a duplicate and
does not start another execution.

Manual execution accepts a caller-provided request ID. Recent request IDs and
their execution IDs are retained in a bounded state collection. Repeating a
retained request ID returns the original execution ID.

The MVP does not claim exactly-once effects in MySQL or another external Sink.
It guarantees that the workflow entity does not intentionally start the same
retained trigger twice.

## 6. Pipeline Contract

A valid MVP workflow has:

- exactly one Source;
- exactly one Sink;
- zero or more Transforms;
- one connected path from Source to Sink;
- no branch, merge, cycle, disconnected node, or edge to an unknown node;
- every declared node type implemented by its executor.

Validation runs before a definition is accepted and again defensively before
execution. Invalid definitions return structured validation errors. The engine
uses the validated path order instead of flattening every node by category.

The execution result contains terminal status, duration, processed record
count when available, and a node result for every node. The entity persists
the node results followed by exactly one terminal event.

## 7. MySQL Persistence

Use Apache Pekko Persistence JDBC with MySQL for:

- `jdbc-journal`;
- `jdbc-snapshot-store`;
- `jdbc-read-journal`.

The first dependency candidate is Pekko Persistence JDBC 1.1.1 because the
project currently uses Pekko 1.1.3. Dependency resolution, binary compatibility,
compilation, and a recovery test must pass before accepting this version. The
implementation must not upgrade the Pekko platform unless compatibility
evidence makes it necessary and the resulting scope is reviewed.

Production configuration reads:

- `DB_HOST`
- `DB_PORT`
- `DB_NAME`
- `DB_USER`
- `DB_PASSWORD`

Provide a versioned MySQL schema initialization script. Production startup
validates connectivity and required tables but does not create or drop tables.
Tests use the JDBC plugin with H2 and create their schema in isolated test
databases.

LevelDB and local snapshots may remain only in an explicitly named local
development configuration. They are not valid for multi-node acceptance.

## 8. Scheduler Coordinator

Replace per-node `SchedulerManager` objects with one persistent typed Cluster
Singleton named `SchedulerCoordinator`, restricted to the `coordinator` role.

### Scheduler state

```scala
final case class SchedulerState(
  schedules: Map[String, ScheduleDefinition],
  pendingTriggers: Map[String, PendingTrigger]
)
```

### Scheduler events

- `ScheduleAdded`
- `ScheduleUpdated`
- `SchedulePaused`
- `ScheduleResumed`
- `ScheduleRemoved`
- `TriggerPrepared`
- `TriggerAcknowledged`

### Delivery protocol

1. Calculate a schedule's next occurrence.
2. Persist `TriggerPrepared` before sending anything.
3. Deliver `ExecuteScheduled` to the workflow Sharding entity.
4. Treat `ExecutionAccepted`, `DuplicateExecution`, and `AlreadyRunning` as
   terminal acknowledgements for that trigger.
5. Persist `TriggerAcknowledged` and remove it from pending state.
6. Retry unacknowledged triggers with bounded backoff.
7. On recovery, rebuild timers and redeliver all pending triggers.

Fixed rate, fixed delay, and Cron schedules use real next-occurrence
calculation. The current one-hour Cron placeholder is removed. Invalid schedule
expressions are rejected when a schedule is created or updated.

The deployment is single data center and uses Split Brain Resolver. No global
multi-data-center singleton guarantee is claimed.

## 9. HTTP Contract

Keep the existing public route families where practical:

- `/api/v1/workflows`
- `/api/v1/workflows/{id}/execute`
- `/api/v1/workflows/{id}/status`
- `/api/history`
- `/api/v1/schedules`
- `/health/live`
- `/health/ready`
- `/metrics`

HTTP success is returned only after the relevant Actor acknowledgement.

Error mapping:

| Domain result | HTTP status |
|---|---:|
| Not found or not initialized | 404 |
| Definition validation failure | 400 |
| Revision conflict or already running | 409 |
| Ask timeout | 504 |
| Persistence or required dependency unavailable | 503 |
| Unexpected internal failure | 500 |

History failures are never converted to an empty successful response.
Workflow definitions, summaries, status, and execution history are never read
from HTTP-local mutable maps.

`/health/live` reports process liveness only. `/health/ready` checks that the
cluster member is Up, Sharding has initialized, and JDBC persistence is
reachable. A required dependency failure returns a non-2xx readiness result.

HTTP host, port, seed nodes, roles, and JDBC settings come from the resolved
configuration. Startup must not overwrite configured seed nodes or bind HTTP
to a hard-coded address.

## 10. Serialization and Compatibility

All commands sent across nodes, replies carried across remoting boundaries,
persistent events, and snapshot states use an explicit Jackson CBOR marker and
binding. Java serialization is not part of the acceptance path.

Persistent event manifests are stable. Renaming or reshaping an event after
release requires a migration adapter. The MVP tests recovery from data written
before an ActorSystem restart.

## 11. Testing Strategy

### Unit and behavior tests

- Definition persistence and revision conflict.
- Uninitialized execution rejection.
- Success, failure, duplicate, and already-running transitions.
- Scheduled and manual idempotency.
- Linear pipeline validation and implemented node types.
- HTTP domain-to-status mapping.

### Recovery tests

- Recover Workflow definition, revision, status, and trigger watermark.
- Recover Scheduler definitions and pending triggers.
- Confirm an acknowledged trigger is not redelivered.
- Confirm an unacknowledged trigger is redelivered after recovery.

### Multi-node integration tests

Run two ActorSystems with distinct ports and shared H2 JDBC persistence:

- create and execute a workflow;
- terminate the node hosting its entity;
- verify another node recovers the definition and status;
- terminate the node hosting the Scheduler Singleton;
- verify another coordinator resumes timers and pending delivery;
- prove one trigger creates at most one accepted execution.

### MySQL integration test

An opt-in test uses a dedicated schema supplied by environment variables. It
verifies Journal writes, Snapshot writes, and recovery. It does not truncate a
user data table.

### Test discovery

Remove the custom `Test / sources` filtering. Hermetic tests run by default.
External MySQL performance tests use an explicit ScalaTest tag and are excluded
from the default command without being excluded from compilation.

## 12. Implementation Sequence and Parallelism

The shared protocol and event model are load-bearing and must be completed
first. Parallel work starts only after those contracts compile and their
initial behavior tests fail for the expected reason.

1. Establish commands, replies, state, events, serialization, and test
   fixtures.
2. In parallel:
   - JDBC dependency, schema, configuration, and recovery test infrastructure.
   - Scheduler Singleton, delivery protocol, and scheduler behavior tests.
   - Linear pipeline validator, structured results, and Sink failure semantics.
3. Integrate WorkflowSupervisor, Sharding, and execution state transitions.
4. In parallel:
   - HTTP workflow and history routes.
   - Health, configuration validation, and deployment configuration.
   - Test discovery cleanup and multi-node scenarios.
5. Run the complete hermetic suite and the opt-in MySQL integration test when
   credentials and an isolated schema are available.

Agents must not edit the same load-bearing files concurrently. Each parallel
task receives an explicit file boundary and test command. Existing `.tasks/`
content and unrelated user changes remain untouched.

## 13. Acceptance Contract

The MVP is accepted only when fresh evidence demonstrates:

1. A workflow definition survives entity and node restart.
2. A valid linear pipeline executes; an invalid graph is rejected.
3. A failed node produces a failed workflow and non-empty error history.
4. Repeated scheduled triggers do not start duplicate executions.
5. Scheduler failover redelivers pending work and does not redeliver
   acknowledged work.
6. Two nodes share MySQL-compatible JDBC persistence and recover the same
   entity state.
7. HTTP status and history reflect Actor state rather than local maps.
8. Default tests contain no destructive external database operation.
9. Compilation, hermetic tests, two-node integration tests, and configuration
   validation exit successfully.

Claims are limited to the evidence level actually run. H2 multi-node evidence
does not by itself prove MySQL deployment readiness; without the opt-in MySQL
test, that item remains `evidence_incomplete`.
