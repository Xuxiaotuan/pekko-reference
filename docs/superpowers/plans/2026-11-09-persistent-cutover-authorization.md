# Persistent Cutover Authorization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 guardian 隔离证明、旧账号排空、旧准入拒绝、旧配额迁移和恢复许可绑定成可跨重启恢复的凭据重新绑定授权，并用真实 MySQL、Actor Journal 和 Sink 验证恢复后的逐字段一致性。

**Architecture:** 由 supervisor 在 guardian 签名隔离证明和数据库排空观察均成立后，在同一受 admission lock 保护的控制事务中迁移旧 owner 配额、写入新控制策略和凭据绑定记录。Worker 只接受与当前 workflow、execution、permit、数据库 UUID、原始定义指纹完全匹配的绑定；持久化 Workflow 定义保持旧用户名，运行时连接使用受授权的新身份。任何证明缺失、结果未知或旧入口仍可执行时都暂停，不创建绑定。

**Tech Stack:** Scala 2.13、Pekko Typed/Streams、HikariCP、MySQL Connector/J、Actor Journal、MySQL InnoDB、ScalaTest、现有 cdc-lab-pool。

**Spec:** `docs/experiments/2026-11-08-standalone-guardian/README.md` 及 `docs/experiments/2026-11-08-standalone-guardian/real-workflow-locked-account-failure-20261109/README.md`。

## Global Constraints

- 保留现有 Journal、Snapshot、T/J/D 和旧持久化类型；新增控制表只能 additive，不修改既有事件 payload。
- 旧 Workflow 入口必须在策略切换后拒绝新的准入事务；策略未切换时新入口不能自行初始化或抢占。
- 账号锁定、guardian 隔离、事务排空和配额迁移的任一结果未知时，持久化 `PAUSED`/保留上下文，不消耗恢复预算，不创建凭据绑定。
- 不把测试协调器、H2、启动进程、队列文件或局部组件测试升级为端到端保证。
- 真实 MySQL 实验使用独占实例、独占 schema、独占账号和独占 Journal/Sink；实验结束必须确认事务数和客户端数为 0。
- 只在本计划所有真实验收通过后更新五篇文章、提交和推送；失败证据和未验证边界必须保留。

## Review Focus

- 旧账号被锁定后仍有已建立事务：排空必须拒绝并等待记录消失，不能只看新登录被拒绝。
- Guardian proof 已写入但 guardian 随后失去控制：不能继续切换，必须安全暂停。
- `T`、`J`、`D` 中任一提交结果未知：不能伪造恢复许可，必须保留旧 grant/context。
- 运行时用户名变化不能改变旧 Split/Workflow 定义指纹；未授权用户名或 endpoint/database 变化必须拒绝。
- 切换提交回复丢失或进程重启：新控制器必须由同一 deployment/host/operation 重建状态，旧入口必须拒绝，新入口不能重复迁移配额。

### Task 0: 冻结当前基线与证据索引

**Files:**
- Create: `docs/experiments/2026-11-09-persistent-cutover-authorization/baseline.md`
- Modify: none
- Test: existing build/test commands only

**Interfaces:**
- Consumes: current `git status`, `git log`, existing control schemas and experiment evidence.
- Produces: a redacted inventory of committed/uncommitted files, persistence IDs, control tables, current failures and exclusions.

- [x] Record current branch/HEAD and all dirty paths without staging or deleting unrelated files.
- [x] Record the owners of T/J/L/D and the readers of each state, including the current `TableSchedulingStore` and `CdcManagedControlStore` policy hashes.
- [x] Record the old-account failure (`source=10`, `target=2`, Access denied) and the account-drain observations separately from the policy-migration fixture result.
- [x] Run `git diff --check` and the existing focused regression suite; label each result `passed`, `failed`, or `evidence_incomplete`.

### Task 1: Add durable credential-binding and cutover records

**Files:**
- Create: `server/src/main/resources/db/mysql/pekko-snapshot-cutover-schema.sql`
- Create: `server/src/main/resources/db/h2/pekko-snapshot-cutover-schema.sql`
- Create: `server/src/main/scala/cn/xuyinyin/magic/workflow/scheduling/SnapshotCutoverModels.scala`
- Modify: `server/src/main/scala/cn/xuyinyin/magic/workflow/scheduling/CdcManagedControlStore.scala`
- Modify: `server/src/main/scala/cn/xuyinyin/magic/workflow/scheduling/ManagedControlTransactions.scala`
- Test: `server/src/test/scala/cn/xuyinyin/magic/workflow/scheduling/SnapshotCutoverStoreSpec.scala`

**Interfaces:**
- Produces `CutoverAuthorizationKey(deploymentId, hostId, operationId, oldOwnerId, oldManifest, policyHash)`.
- Produces `CredentialBinding(workflowId, executionId, permitId, ownerId, operationId, endpoint, database, legacyUser, runtimeUser, passwordEnv, definitionHash, phase)`.
- Produces `SnapshotCutoverStore.prepareBinding(...)`, `commitBinding(...)`, `binding(...)`, and `pause(...)`; none store passwords.

- [x] Write failing H2 tests for missing policy, wrong operation, duplicate binding, wrong permit, wrong definition hash, and restart reconstruction.
- [x] Add additive tables with unique keys on `(workflow_id, execution_id, permit_id, node_role)` and immutable operation/definition fields.
- [x] Make migrated control storage refuse empty policy initialization and preserve old constructor behavior.
- [x] Implement idempotent insert/readback so a lost commit reply returns the existing binding only when every identity field matches.
- [x] Run the focused H2 suite and verify old tables and Journal serializers are untouched.

### Task 2: Implement the atomic cutover authorizer

**Files:**
- Create: `server/src/main/scala/cn/xuyinyin/magic/workflow/scheduling/SnapshotCutoverAuthorizer.scala`
- Modify: `server/src/main/scala/cn/xuyinyin/magic/workflow/scheduling/LegacyAccountLockIntent.scala`
- Modify: `server/src/main/scala/cn/xuyinyin/magic/workflow/scheduling/SnapshotSupervisorStore.scala`
- Modify: `server/src/main/scala/cn/xuyinyin/magic/workflow/scheduling/ManagedOwnerStore.scala`
- Test: `server/src/test/scala/cn/xuyinyin/magic/workflow/scheduling/SnapshotCutoverAuthorizerSpec.scala`

**Interfaces:**
- `authorize(control: SupervisorControl, oldWorker: SnapshotWorkerLaunch, receipt: String, lockPlan: LegacyAccountLockIntent.Plan, grants: Vector[TableGrant], bindings: Vector[CredentialBinding]): CutoverAuthorizationKey`.

- [ ] Write failing tests for forged receipt, stale controller epoch, mismatched worker identity/start identity, active old worker, incomplete database drain, residual old quota, stale account-lock observation, and duplicate operation.
- [ ] Verify `SnapshotGuardianWire.verifyReceipt` against the enrolled guardian key and require the stored launch proof to equal the verified receipt.
- [ ] Re-run the bounded account lock plus `verifyDrain`; treat any unknown process, null attribution, proxy session, or remaining InnoDB transaction as refusal.
- [ ] In one control transaction, require old owner `db_drained`, migrate every old grant with bounded pagination, set the new policy hash, and insert credential-binding rows. If any step fails, roll back control state and keep the old context paused.
- [ ] Add an explicit `PAUSED` state for commit-result-unknown; do not spend retry budget for clear refusal.
- [ ] Verify the transaction with H2 and a real MySQL control schema, including lost commit reply and controller restart readback.

### Task 3: Bind runtime credentials without changing persisted definitions

**Files:**
- Modify: `server/src/main/scala/cn/xuyinyin/magic/workflow/scheduling/SnapshotOwnerJdbc.scala`
- Modify: `server/src/main/scala/cn/xuyinyin/magic/workflow/execution/WorkflowExecutionEngine.scala`
- Modify: `server/src/main/scala/cn/xuyinyin/magic/workflow/snapshot/SnapshotSplitExecutor.scala`
- Modify: `server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/MySQLSnapshotSourceNode.scala`
- Modify: `server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/sink/MySQLSinkNode.scala`
- Test: `server/src/test/scala/cn/xuyinyin/magic/workflow/execution/SnapshotCredentialBindingSpec.scala`
- Test: `server/src/test/scala/cn/xuyinyin/magic/workflow/connectors/mysql/ManagedCredentialBindingSpec.scala`

**Interfaces:**
- `SnapshotOwnerJdbc.authorizedBinding(workflowId, executionId, nodeId, role, originalConfig): Option[CredentialBinding]`.
- `SnapshotOwnerJdbc.rebindRuntimeNode(node, binding): ExecutionNode`.

- [x] Write failing tests proving a changed runtime username with no durable binding is rejected, while a matching binding changes only runtime credentials.
- [x] Match endpoint, database, node role, legacy username, execution, permit, owner, operation and original definition hash before rebinding.
- [x] Resolve `passwordEnv` only from the process environment; never persist or log the password.
- [x] Keep the original node pair for `SnapshotSplitFingerprint.compute`; runtime credentials must not alter the persisted snapshot identity.
- [x] Ensure ordinary non-cutover execution keeps the current behavior and no global mutable credential override is introduced.
- [x] Run all focused unit tests and inspect redacted connection logs.

### Task 4: Connect the new policy and binding to worker startup

**Files:**
- Modify: `server/src/main/scala/cn/xuyinyin/magic/workflow/scheduling/SnapshotOwnerBootstrap.scala`
- Modify: `server/src/main/scala/cn/xuyinyin/magic/workflow/scheduling/TableSchedulingStore.scala`
- Modify: `server/src/main/scala/cn/xuyinyin/magic/workflow/scheduling/TableSchedulingRuntime.scala`
- Modify: `server/src/main/scala/cn/xuyinyin/magic/workflow/scheduling/SnapshotSupervisorProcesses.scala`
- Test: `server/src/test/scala/cn/xuyinyin/magic/workflow/scheduling/CutoverWorkerStartupSpec.scala`

- [x] Require an enrolled guardian launch ticket, exact launch profile, cutover operation and new policy identity before constructing a migrated legacy Workflow store.
- [x] Make an old binary with the old policy fail closed after the policy marker changes.
- [x] Make a restarted new worker reconstruct the same binding from control tables, without consuming a new permit or re-releasing quota.
- [x] If guardian RPC, identity, database UUID or credential binding is unavailable, pause and retain context.
- [x] Test normal startup, restart after policy commit, policy commit reply loss, and stale old worker startup.

### Task 5: Real MySQL + Journal + Sink recovery matrix

**Files:**
- Modify: `server/src/test/scala/cn/xuyinyin/magic/workflow/integration/RealMySQLSnapshotTakeoverSpec.scala`
- Create: `server/src/test/scala/cn/xuyinyin/magic/workflow/integration/RealMySQLCutoverAuthorizationSpec.scala`
- Create: `docs/experiments/2026-11-09-persistent-cutover-authorization/README.md`

- [x] Use an exclusive lab pool and a real old Workflow worker, independent production guardian process, Actor Journal and MySQL Sink. `RealMySQLSupervisorMainSpec` now verifies the actual GuardianMain/SupervisorMain/worker JVM path; the cutover authorization operator command remains a separate boundary.
- [x] Run the fixed matrix: old worker isolated before J, account lock with old transaction, drain delayed, policy/reply loss, supervisor restart, observer denial, stale grant and duplicate recovery. `B_RECOVERY_CRASH`, Journal/target/quota/revoke reply loss and startup reply loss all passed in this run.
- [x] Capture process identity/start identity, guardian receipt, account/processlist/`innodb_trx`, old/new policy hashes, quota rows, permit rows, T/J/D, ACK ordering, Journal sequences, Sink ledger and complete source/target field comparison.
- [x] Require source and target row counts, primary keys and every projected field to match after recovery; retain failing snapshots before cleanup.
- [x] Preserve every failure as `failed` or `evidence_incomplete`; do not rerun only to turn a scenario green.
- [x] Confirm the exclusive MySQL has zero transactions and non-system clients before stopping and releasing the lab pool.

### Task 6: Review and compatibility regression

**Files:**
- Modify only files changed by Tasks 1–5
- Test: focused CDC, scheduling, Journal and Sink suites

- [ ] Verify old persistence manifests and event serializers decode unchanged.
- [ ] Verify T/J/D semantics and existing retry/refusal behavior remain unchanged outside explicit cutover mode.
- [ ] Run `git diff --check`, focused ScalaTest suites, and the real matrix on the final source version.
- [ ] Review for password leakage, unbounded waits, unbounded quota migration, hidden fallback credentials, and policy-hash bypass.
- [ ] Mark every result as source conclusion, test result, real end-to-end result, or unverified boundary.

### Task 7: Update the five articles

**Files:**
- Modify: `docs/articles/cdc-design/01-why-pekko-for-resident-cdc.md`
- Modify: `docs/articles/cdc-design/02-source-and-recovery-contract.md`
- Modify: `docs/articles/cdc-design/03-real-mysql-journal-failure.md`
- Modify: `docs/articles/cdc-design/04-protocol-and-validation.md`
- Modify: `docs/articles/cdc-design/05-pekko-vs-flink-cdc.md`
- Modify: `docs/articles/cdc-design/README.md`

- [x] Add the persistent cutover state machine and runtime credential binding only after Task 5 passes.
- [x] Put the old-account Access denied result, the 10-versus-2 failed comparison, and all later successful/failed matrices in the evidence section with links.
- [x] Separate source/code conclusions, real experiment results, test-fixture results and unverified boundaries in every article.
- [x] Update the validation-range summary so policy migration fixtures cannot be described as Journal/Sink end-to-end proof.
- [x] Verify internal links and code-version references locally.

### Task 8: Commit and push

- [ ] Stage only the completed implementation, focused tests, experiment materials and five article files; leave unrelated dirty paths untouched.
- [ ] Show staged paths and review the final diff.
- [ ] Run the final verification commands again on the staged source.
- [ ] Commit with a message describing persistent cutover authorization and credential rebinding.
- [ ] Push the current branch to `origin` without force-push or remote rewriting.
- [ ] Record commit SHA, remote result, test result, experiment evidence path and any remaining unverified boundary.

## Completion Gate

The work is complete only when Tasks 1–8 pass. In particular, a green H2 test, a running worker, a policy row, a queue file, a successful process launch, or a fixture-level quota release is not enough. The final acceptance requires a real MySQL old Workflow, independent guardian, Actor Journal and Sink, recovery after the selected failure windows, and full source/target field equality.
