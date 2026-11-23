# CDC Recovery and L Floor Completion Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans or superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve the current RG02 slow-table recovery failure, rerun the current-version LG01—LG16 matrix with real MySQL, Actor Journal, Sink and full-field reconciliation, then publish evidence-bounded results.

**Architecture:** Keep the existing SharedMySQLCapture, TableCdcGraph, BootstrapRunner, Journal and L floor protocol. First isolate the Journal ask timeout and release classification at the existing component boundary. Then rerun the cross-process GC matrix without changing recovery semantics unless a reproduced root cause requires it. Historical evidence remains immutable and is referenced separately from current-version evidence.

**Tech Stack:** Scala 2.13, Pekko, Pekko Streams, Pekko Persistence JDBC, MySQL 8.0, Debezium, ScalaTest, sbt and GitHub Actions/Jekyll.

**Spec:** Current task and `support/docs/experiments/2026-11-22-graph-fence-v2/l-floor-followup-20261122/README.md`.

## Global Constraints

- Preserve existing T/J/L/D persistence types and recovery compatibility.
- Do not turn `actual D proof pending`, `local_release_unconfirmed` or UNKNOWN into a successful result by changing the assertion.
- Keep historical failures under their original evidence directories.
- Current-version claims require real MySQL, Actor Journal, Sink and full-field reconciliation.
- Do not claim 70万 physical-table capacity from the two-table matrix.
- Do not expand this plan into DDL or automatic multi-owner takeover until the RG02 and LG matrix gates pass.

## Review Focus

- A slow target table must not make a different table silently lose its execution opportunity. Test with at least two execution slots.
- A Journal ask timeout must retain the exact task, grant, phase, release status and exception classification.
- Releasing a target lock must not be treated as a successful recovery until T/J/L/D evidence and field reconciliation agree.
- Namespace discovery being incomplete must remain a retryable readiness state, not a test failure caused by an empty SQL result.
- Historical LG evidence must never be presented as a current-version pass.

### Task 0: Freeze the current evidence baseline

**Files:**
- Modify: `support/docs/experiments/2026-11-22-graph-fence-v2/l-floor-followup-20261122/README.md`
- Create: `support/docs/experiments/2026-11-23-rg02-lg-current/README.md`

- [ ] Record code SHA `ef56548`, website SHA `747d9ed`, current RG01/RG07 passes, RG02 retry7 failure and the fact that LG01—LG16 are historical only.
- [ ] Record the disposable MySQL and K8s setup command, source/state ports, test options and evidence directory naming.
- [ ] Verify that no acceptance MySQL deployment or port-forward remains after every run.
- [ ] Commit only the baseline manifest and evidence references.

### Task 1: Reproduce and instrument RG02 at the Journal boundary

**Files:**
- Modify: `common/src/main/scala/cn/xuyinyin/magic/cdc/platform/CdcPlatformRuntime.scala`
- Modify: `common/src/main/scala/cn/xuyinyin/magic/cdc/platform/CdcBootstrapKernel.scala`
- Modify: `common/src/main/scala/cn/xuyinyin/magic/cdc/bootstrap/BootstrapJournal.scala`
- Modify: `common/src/test/scala/cn/xuyinyin/magic/workflow/integration/CdcRuntimeGcWorker.scala`
- Modify: `common/src/test/scala/cn/xuyinyin/magic/workflow/integration/CdcRuntimeGcSpec.scala`

- [ ] Add structured failure evidence for task id, grant id, phase, Journal operation, timeout duration, release classification and underlying cause. Do not change behavior in this step.
- [ ] Add a deterministic RG02 assertion that records the state immediately before the first Journal timeout and after the target lock is released.
- [ ] Run RG02 with `snapshot-steps=2`, preserving the target lock window and the original ACK/T/J/L/D snapshots.
- [ ] Confirm whether the timeout comes from Journal persistence, a shared JDBC pool, a target transaction, or the worker execution context.
- [ ] Stop here if the exception source is still ambiguous. No production fix is allowed without this evidence.

### Task 2: Apply the smallest RG02 production fix

**Files:**
- Modify only the component identified by Task 1, likely one of:
  - `common/src/main/scala/cn/xuyinyin/magic/cdc/bootstrap/BootstrapJournal.scala`
  - `common/src/main/scala/cn/xuyinyin/magic/cdc/platform/CdcBootstrapKernel.scala`
  - `common/src/main/scala/cn/xuyinyin/magic/cdc/platform/CdcPlatformRuntime.scala`
  - `common/src/main/scala/cn/xuyinyin/magic/cdc/bootstrap/BootstrapReplaySink.scala`
- Test: the focused RG02 test and the nearest existing Journal or sink regression suite.

- [ ] Write a focused failing test for the reproduced timeout or release race.
- [ ] Implement one root-cause fix while preserving UNKNOWN and release-unknown safety behavior.
- [ ] Verify that a slow B target can remain blocked while A either continues independently or is explicitly paused with durable evidence, according to the demonstrated contract.
- [ ] Verify that lock release and automatic recovery require T/J/L/D evidence, not only a successful HTTP response.
- [ ] Run the focused test, compile, and the relevant unit regression before changing the matrix.

### Task 3: Complete the current RG matrix

**Files:**
- Modify: `common/src/test/scala/cn/xuyinyin/magic/workflow/integration/CdcRuntimeGcSpec.scala` only when the assertion reflects the proven contract.
- Create or modify: `support/docs/experiments/2026-11-23-rg02-lg-current/RG01`, `RG02`, `RG07` evidence directories.

- [ ] Rerun RG01 and confirm GROUP operation `DONE`, `to_exclusive > from_seq`, restart recovery and full-field equality.
- [ ] Rerun RG02 with the fixed production path and preserve both the blocked window and post-release recovery evidence.
- [ ] Rerun RG07 and confirm unclean restart safe pause, owner state, Journal continuity and full-field equality.
- [ ] Record each result as `passed`, `failed` or `evidence_incomplete`.
- [ ] Run the normal non-external regression after the matrix.

### Task 4: Add a current-version LG01—LG16 entry point

**Files:**
- Inspect and reuse: `common/src/test/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/CaptureGcRetentionMySqlSpec.scala`
- Inspect and reuse: historical manifests under `support/docs/experiments/2026-11-12-capture-gc/strict-final/` and `final/verified3/`.
- Create or modify: one current-version matrix runner under `support/scripts/experiments/cdc-gc/` and its Scala test entry point under `common/src/test/scala/`.
- Create: `support/docs/experiments/2026-11-23-rg02-lg-current/LG01` through `LG16` evidence directories.

- [ ] Freeze the current source manifest and record the exact code SHA in every matrix run.
- [ ] Map each LG case to its intended fault window, expected phase and required T/J/L/D evidence before execution.
- [ ] Run LG01—LG16 serially against isolated MySQL and Actor Journal state databases.
- [ ] Preserve failure evidence when a case blocks or times out. Do not substitute historical `strict-final` materials.
- [ ] Require source and target row sets to match field-by-field before marking a case passed.
- [ ] Run the capacity metadata case separately from the real-table cases.

### Task 5: Full verification and reconciliation

**Files:**
- Create: `support/docs/experiments/2026-11-23-rg02-lg-current/verification-summary.md`

- [ ] Run compile and normal regression.
- [ ] Run RG01, RG02, RG07 and LG01—LG16 from the same code SHA.
- [ ] Compare T/J/L/D at each fault cut.
- [ ] Compare source and target rows by primary key and every field.
- [ ] Produce a matrix with `passed`, `failed`, `evidence_incomplete` and `not_run` states.
- [ ] Confirm acceptance MySQL resources, processes, connections and transactions are cleaned up.

### Task 6: Update the five articles and website

**Files:**
- Modify: the five Pekko CDC posts under `/Users/xujiawei/magic/workbench/Xuxiaotuan.github.io/_posts/pekko/`
- Modify: `pekko-cdc-series/index.md`
- Modify: `pekko-cdc-series/cdc-platform/index.md`

- [ ] Update only after Task 5 has produced current-version evidence.
- [ ] Separate RG01/RG02/RG07 and LG01—LG16 current results from historical materials.
- [ ] Preserve failure evidence links and unverified boundaries.
- [ ] Run the website Verify workflow.
- [ ] Confirm Pages deployment and inspect the live series index and all five article pages.
- [ ] Leave pre-existing `assets/images/` untracked files untouched.

### Task 7: Deferred P1 architecture work

- [ ] Complete source fence v2 with guardian, observer/controller and multi-owner end-to-end takeover evidence.
- [ ] Complete DDL capture and Schema propagation matrix.
- [ ] Replace the `TableCdcGraphRuntime` façade and remove its `BootstrapRunner` delegation only after an independent graph contract is specified and tested.
- [ ] Run separate active-table, connection, binlog-throughput and L growth capacity experiments.

## Completion Gate

The main recovery/GC objective is complete only when all of the following are true:

- RG01, RG02 and RG07 pass on the same current code SHA.
- LG01—LG16 either pass with current evidence or are explicitly marked failed with preserved evidence.
- Every passed case has T/J/L/D snapshots and full-field source/target reconciliation.
- Normal regression and compile pass.
- Five articles and the series index link to current evidence.
- Website Verify and deployment succeed, and live pages show the current matrix.

