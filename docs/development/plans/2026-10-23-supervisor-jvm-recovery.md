# Supervisor JVM recovery implementation

Approved spec: the conversation design of 2026-10-23. Implement a separate per-host guardian and supervisor, authenticated identity and launch tickets, original-child exit proofs, original database drain and quota transitions. Test S5–S12 and existing regressions with real MySQL/Journal/Sink. No commit or push.

## Constraints

- Existing T/J/D, serialized Journal/Snapshot types, batch identities and recovery budgets stay unchanged.
- Existing probe sources and evidence remain intact. New control tables are additive and opt-in.
- A guardian with lost original Process capabilities is UNKNOWN, never PID-adopted. Pause with reservations retained.
- Keep every failed runner, fixed window, all row/field differences, process identity and signed evidence; secrets outside archives.

## Tasks and verification

1. Control transactions and durable host/launch store: first failing connection/atomicity and stale-epoch tests; then additive schema, bounded tickets, stable operations and readback. Verify focused H2 tests.
2. Guardian transport/service: pinned Ed25519 responses, authenticated sequential requests, original Process ownership, launch/cancel/exit/retire idempotency. Verify real subprocess, wrong identity, replay and capability-loss behavior.
3. Supervisor/bootstrap integration: durable tickets before fork, serialized startup lock, fresh controller epoch, reconstruct and fence/drain/release before successor. Preserve old entry points. Verify restart/unknown and original persistence compatibility tests.
4. Independent JVM MySQL matrix: S1–S4/S13–S15 regression; S5 target transaction, S6 T-before-J, S7 J-before-reply, S8 committed/uncommitted unknown, S9 receipt/SQL reply loss, S10 residual roles/observation restart/denied observer, S11 control reply-loss variants, S12 repeated recovery. All successful cases full row/field comparison; expected pauses retain exact old progress/quota.
5. Original real Split/CDC/takeover regressions, full backend and package, one fresh read-only review, frozen-source archives/independent verifier and cleanup. Report boundaries, no Git mutations.

## Interfaces / preflight

- Task1 short admission transaction must enclose old ownership mutations used by Task3; use the same JDBC connection and no external RPC under SQL locks.
- Task2 guardian keeps original child handles; Task3 receives only authenticated identities/proofs, not Process references.
- Task3 worker ticket gate precedes owner bootstrap; original worker execution remains unchanged.
- Task4 timing injections belong in test utilities, not production commands; production state transitions perform the recovery.

## Progress and rulings

- Baseline afff7ab27560b74ad026aa370dba1cf920fdc587, existing feature branch; only previous probe files untracked.
- Ruling: continue in this approved checkout, without creating a second worktree or touching Git index; previous untracked probe/evidence is required context and remains unchanged.
- Ruling: use this existing repository plan convention as the execution ledger; no separate task/workspace files or unapproved commits.
- Ruling: first version covers one fixed deployment per guardian host, local trusted processes and static snapshot; guardian restart/host reboot/cross-host leader election are excluded.

- Task1: nested admission connection RED (0/1) → GREEN; stale epoch/ticket/rollback/old ownership set27 passed. Retired guardian slot before RPC RED6/7 → GREEN31/31.
- Pilot S6 matrix-1792743817437305000 reached full data convergence, but cleanup used executeQuery on DO and failed. Runner remains failed; replace with SELECT RELEASE_LOCK and explicitly self-halt that pilot worker. Do not count its result.json as a passing run.
- Task2/3: pinned sequential guardian transport, original capability binding, persistent launch/proof and supervisor/bootstrap gate implemented. Original capability replacement before a recorded PID RED7/8 → GREEN; guardian IPC loss after recorded proof RED fenced/drain_wait → GREEN with control rechecks.
- Corrected frozen S6 pilot matrix-1792743959927967000 passed1/1. First broad matrix matrix-1792744095169053000 passed27/30; three no-fork cases failed because the timing fixture wrongly treated the successor's first physical fork as the original worker. Classify by controller epoch; old failed runner retained. No protocol-state edits used to make those cases pass.
- Strengthened real timing: SQL COMMIT reply-loss now throws after real COMMIT and blocks the subsequent readback; permission reply-loss blocks the next permission read after the actual exception. Explicit first empty round, matching residual role/trx and CBOR T/J assertions added.
- Final fresh read-only review found3 Important issues: active-owner ended-session pruning missing; bare JDBC URL parameter delimiter; managed worker HA opt-out bypass. All3 reproduced in /tmp/pekko-supervisor-review-red.log (11 pass,3 fail), then corrected. Extended near-bound4096 registration fixture and negative profile checks; /tmp/pekko-supervisor-review-green.log36/36 passed.
- Review declined runtime/matrix/packaging judgments: those remain governed by actual completed runs, not review. Guardian/host restart, cross-host election and host compromise stay excluded; this costs automatic availability under those failures, intentionally retaining reservations.
- Actual production entry smoke main-smoke-1792745109817704000 passed1/1: fixed PekkoServer worker, full snapshot, supervisor restart, no repeated target effects; HA-disabled profile rejected before fork; bare control URL exercised. Final cleanup used authenticated original-child control, not PID termination.
- Final frozen31-case matrix and original probe/takeover/Split/CDC suites are running sequentially; backend/package and artifact verification still required. No commits, staging or pushes.
- Strong31-case run matrix-1792745148527006000 retained one actual S10 Journal residual-window failure (transaction already ended); other outcomes remain historical, not final acceptance. First relay pilot matrix-1792745557635565000 retained an unintended control-connection transaction, pausing proof persistence. Ruling: role-specific JDBC URL routing through test relay only; control stays direct, no surrogate owner/session or fabricated isolation proof. Added actual Source-role residual transaction case to complete the four-role scope. Production protocol unchanged by this facility correction.

- Independent offline verification rejected S11_PERMIT_REPLY_LOST in matrix-1792746108721740000 despite ScalaTest exit0: C claimController timed out because the readback timing fixture held the admission lock; the B worker later completed after its gate timeout. Preserve the entire run as a semantically rejected attempt. Move only the test gate before the first readback SQL; require final controller C, generation3 and two retired predecessors in S11/S12. No production recovery change for this fixture correction.
- Tightened S11 pilot matrix-1792746680322452000 correctly failed0/1 at C CONTROLLER_BOUND. Its raw SQL evidence showed an unrelated heartbeat thread consumed the shared timing flag after acquiring the lock. Scope the injection gate to the original COMMIT-failure thread, before that thread's next SQL; retain this failed fixture and do not count late worker convergence.
- Thread-scoped S11 pilot matrix-1792746809536672000 passed1/1 and independent signature/ledger/controller verification: C bound, generation3, two retired predecessors, ten rows/six fields exact. Freeze this source and rerun all six live suites; semantic matrix verification is now a gate before remaining suites/backend.
- Final frozen matrix matrix-1792746865880842000 passed32/32 and all32 independent semantic checks:29 convergence,3 expected safe pauses,290 rows x6 fields. Guardian-loss live process observations captured for all3 negative cases. Original mains/regressions/backend and final evidence are still pending.
- Exact-source actual mains1/1, original guardian probe10/10 (8 convergence/2 safe pauses) and same-cluster takeover22/22 (220 rows x6) passed; independent original validators accepted all corresponding evidence. Split/CDC/backend/archive/cleanup pending.
- All six final live suites passed on identical301-file frozen source:32 matrix,1 actual-main,10 old probe,22 takeover,22 Split,27 CDC. Backend579/579, 110 suites and package passed; packaged production classes/resources match frozen artifacts and exclude test classes. Task MySQL stopped after global zero clients/transactions, all234 schemas dumped and volume retained. Offline archive verification and final Git checks are the last gates; no staging/commit/push.
- Final offline verifier passed:5443 archived files,39359 total recursive file inspections for credentials; four <=40MiB parts,165466131 compressed bytes. Current301-file source, production artifact, signatures,29+3 outcomes, original regressions and3 corrupted-evidence negative controls accepted/rejected as expected. Report/guide links valid, HEAD afff7ab unchanged, index empty; no commit/push.

## Commit review authorized by the subsequent user request

- New authorization: review the complete change, then commit and push code, tests and evidence if accepted; the earlier no-commit/no-push notes above describe the implementation phase.
- Fresh whole-change review found one inherited fork/identity exception path to harden. Java17 real-fork permission denial reproduced NOT_STARTED vs UNKNOWN (RED0/1); retain the original unidentified Process and slot, reject cancellation/reuse/over-capacity, then GREEN16/16 and backend580/580 plus package. No T/J/D or legacy persistence-type changes.
- Final current-source live acceptance:32 supervisor matrix (29 converge/3 safe pauses),1 actual-main,22 same-cluster takeover,22 Split (16 converge/6 pauses),10 probe (8 converge/2 pauses). Independent signatures, CBOR page ledgers, generation/controller order and full-field comparisons passed. Original frozen27 CDC cases verified offline, not rerun after this final Process guard.
- Preserve the current takeover21/22 failure (required post-exit session/transaction window absent), targeted1/1 and full22/22 rerun; preserve Split21/22 failure (STATE probe JDBC handshake timeout), ten healthy new JDBC connections, targeted1/1 and full22/22 rerun. No original assertion/source or timeout relaxation. The instantaneous handshake cause remains unverified. Auxiliary probe output-parser failure is also retained.
- All367 task schemas and volume preserved;133 new schemas exported; zero clients/transactions before stopping the task container. Final-review archive includes2087 files,19507 recursive credential inspections and two <=40MiB parts; original283 tracked experimental files unchanged. Final offline verification passed, including three corrupted-evidence rejection controls. See docs/experiments/2026-10-23-supervisor-final-review/README.md for exact versions, failure boundaries and commands.
