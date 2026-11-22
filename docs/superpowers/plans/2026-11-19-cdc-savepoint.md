# CDC Runtime Savepoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在现有表级恢复协议上增加以 `captureEpoch` 为边界的分页 Runtime Savepoint。

**Architecture:** SharedMySQLCapture 只产生单调 epoch 和当前 L/route 摘要；CdcSavepointStore 在控制库保存不可变 header、分页表引用和 digest；恢复读取只接受 SEALED manifest，表级状态仍交给现有 Bootstrap/Journal 调度器。

**Tech Stack:** Scala 2.13、JDBC/MySQL、Spray JSON、现有 Pekko CDC runtime。

**Spec:** `docs/superpowers/specs/2026-11-19-cdc-savepoint-design.md`

## Global Constraints

- 不修改 `BatchCheckpoint`、`BootstrapProgress`、`BootstrapJournal` 的既有序列化格式。
- 不把连接、ActorRef、Debezium engine、ACK handle 写入持久化 Savepoint。
- 非 SEALED Savepoint 不得恢复。
- 保存失败不得改变表级任务状态。
- 未做真实 MySQL/Journal/Sink 端到端实验前，不宣称 Savepoint 已完成生产验证。

## Review Focus

- 重复创建同一 savepointId 必须幂等或明确冲突；测试 `SAVEPOINT_ALREADY_SEALED`。
- 分页缺失、乱序或 digest 被篡改必须拒绝恢复；测试缺页和 digest mismatch。
- 新增或取消表不能改写已封存 manifest；测试 tombstone 和 generation mismatch。
- capture epoch 回退必须拒绝；测试单调性。
- 旧数据库没有 Savepoint 表时初始化必须可重复且不影响旧表；测试兼容初始化。

### Task 1: Savepoint domain model and digest

**Files:**
- Create: `common/src/main/scala/cn/xuyinyin/magic/cdc/savepoint/CdcSavepointModels.scala`
- Test: `common/src/test/scala/cn/xuyinyin/magic/cdc/savepoint/CdcSavepointModelsSpec.scala`

- [x] Define `CaptureEpoch`, `TableCheckpointRef`, `CdcSavepointHeader`, `CdcSavepointPage`, `CdcSavepointStatus`.
- [x] Define canonical JSON and SHA-256 digest functions.
- [x] Test deterministic digest, ordering, and forbidden empty identities.

### Task 2: Control-store persistence

**Files:**
- Create: `common/src/main/scala/cn/xuyinyin/magic/cdc/savepoint/CdcSavepointStore.scala`
- Modify: `common/src/main/scala/cn/xuyinyin/magic/cdc/platform/CdcTaskStore.scala`
- Test: `common/src/test/scala/cn/xuyinyin/magic/cdc/savepoint/CdcSavepointStoreSpec.scala`

- [x] Add idempotent Savepoint tables to `CdcTaskStore.initialize`.
- [x] Implement `create`, `appendPage`, `seal`, `readSealed`, and `delete` with manifest checks.
- [x] Test page ordering, immutability, digest verification, and legacy initialization.

### Task 3: Shared Reader epoch

**Files:**
- Modify: `common/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/SharedMySQLCapture.scala`
- Test: `common/src/test/scala/cn/xuyinyin/magic/cdc/savepoint/CaptureEpochSpec.scala`

- [x] Add read-only epoch snapshot and monotonic seal operation.
- [x] Bind epoch to capture id, lineage, current L sequence, route digest and table-set digest.
- [ ] Test epoch behavior against a real SharedMySQLCapture; the current focused tests cover persisted epoch storage and digest paths.

### Task 4: Runtime Savepoint coordinator

**Files:**
- Create: `common/src/main/scala/cn/xuyinyin/magic/cdc/savepoint/CdcSavepointCoordinator.scala`
- Modify: `common/src/main/scala/cn/xuyinyin/magic/cdc/platform/CdcBootstrapKernel.scala`
- Test: `common/src/test/scala/cn/xuyinyin/magic/cdc/savepoint/CdcSavepointCoordinatorSpec.scala`

- [x] Build deterministic table references from persisted task continuations.
- [x] Create header, write pages, seal manifest, and expose sealed restore reads.
- [x] Keep incomplete tables as references with their phase; reject missing/invalid pages without altering task state.

### Task 5: Runtime service boundary

**Files:**
- Modify: `common/src/main/scala/cn/xuyinyin/magic/cdc/platform/CdcTaskService.scala` equivalent in `CdcTaskModels.scala` and `CdcPlatformRuntime.scala`
- Modify: `common/src/main/scala/cn/xuyinyin/magic/api/http/routes/CdcTaskRoutes.scala`
- Test: `common/src/test/scala/cn/xuyinyin/magic/api/http/routes/CdcSavepointRoutesSpec.scala`

- [x] Add internal create/restore methods and HTTP boundaries.
- [x] Keep API errors explicit and avoid exposing unsealed manifests.

### Task 6: Verification

- [x] Run focused model/store/route tests.
- [x] Run CDC compile and existing checkpoint/runtime regressions.
- [x] Run static diff review; real MySQL, Journal and Sink matrix remains external validation.
