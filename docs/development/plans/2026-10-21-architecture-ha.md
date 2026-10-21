# Architecture and graceful lifecycle implementation plan

> Execute inline with superpowers:executing-plans; user has authorized autonomous implementation, review, commit and push.

**Goal:** Clarify the CDC platform's ownership and directory boundaries and fix verified graceful-stop/readiness/lifecycle defects.
**Architecture:** Preserve public/persisted identities; extract pure Actor helpers, centralize HTTP lifecycle, bound actual readiness work, group UI by feature and archive retired pages.
**Tech Stack:** Existing Scala/Pekko/JDBC/React/TypeScript; no new library or version upgrade; explicitly declare the already-used esbuild 0.21.5 test tool.
**Spec:** docs/development/specs/2026-10-21-architecture-ha-design.md

## Global constraints

- Preserve T→J→ACK, C2 budget, group identity, all old experiments, wire FQNs and current API/DSL.
- No deployment, force Git, new framework, deletion of unrelated local files, or unsupported HA claim.
- Stop must retain durable execution; observation timeout never proves actual release.

## Review focus

- Stop arriving during recovery gate or release callback must prevent a replacement launch.
- Scheduled requests during stopping must not receive an ACK-worthy AlreadyRunning response.
- Old timed-out readiness work must not release its concurrency slot until actual completion.
- Ordinary row-stream completion is distinct from empty reliable-session registry.
- Non-default shard configuration changes ownership mapping and is not safe for rolling migration.

## Tasks

1. [x] Add red tests for Stop holding T/ACK/close and recovery race; add health/status/deadline/concurrency and supervisor timeout tests. Verify existing code fails the new behavioral assertions.
2. [x] Extract WorkflowStateTransitions, WorkflowDefinitionCodec, WorkflowHistory keeping model declarations and compatibility wrappers. Implement private stopping lifecycle. Run Actor, Engine, CBOR and lifecycle tests.
3. [x] Add ReadinessCheck and HttpServerLifecycle; repair routes/shard count/supervisor reply timeout and EXPOSE metadata. Run route, gate, ephemeral HTTP, sharding and supervisor tests.
4. [x] Move UI code by feature, archive retired files with hash mapping; configure existing lint, retain URL/DTO/request identity. Install from Yarn Classic lockfile in an empty directory, then run test/build/lint.
5. [x] Add real Stop-T probe, compile all tests; run 27 real MySQL cases and full backend/package/Universal-stage. Independently recompute T/J/D, ACK, audit and all fields; hash old artifacts.
6. [x] Record full code review, final source snapshot and explicit uncovered scope. Stage only reviewed paths. Commit/push authorization is explicit; confirm remote commit equality after this plan is committed, without pre-recording a successful push in this file.

No user approval waits: the latest direct request explicitly authorizes autonomous execution and pushing. Subagents are read-only reviewers; implementation stays inline.
