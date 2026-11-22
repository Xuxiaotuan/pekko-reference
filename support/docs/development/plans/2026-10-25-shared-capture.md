# Shared MySQL capture phase 1 implementation plan

**Spec:** approved conversation, 2026-10-25: source control table accepted; implement shared Reader, dynamic subscriptions, durable L; execute R01–R16 against real MySQL, Actor Journal and Sink. No LOW/HIGH bootstrap or automatic takeover. Latest user instruction additionally authorizes article updates, scoped commit and push.
**Baseline:** cc1c43dd7176057976af1c11375443e24206ac16, clean existing feature checkout reused. Preserve existing serialized types, commands and evidence.
**Architecture:** one supervised capture session owns the real Engine and original ACK handles. JDBC CaptureStore atomically stores versioned Schema/log/routing/frontier evidence. A source control marker establishes B; capture-ready never means initialized. Table consumers keep existing target group ledger and T→J order.

## Tasks and verification

1. Capture models/store: write failing subscription/B/idempotency/corruption tests; implement bounded JDBC log and durable interval transitions. Source and state schemas remain separate. Verify real transactions and immutable identity conflicts.
2. Shared Reader: failing callback/pair/control tests, then classify Schema/transaction/control/business records, assemble complete groups, persist before continuous-prefix ACK. Verify exact Debezium 3.6.1 callbacks before claiming Schema readiness. Keep legacy classes unchanged.
3. Subscription/control and L consumer: establish idempotent B/K, stable generation and bounded table reads. Verify restart and duplicate command behavior. Existing target Sink and actual JDBC Actor Journal supply T/J evidence; fixture baselines are explicitly not online initialization.
4. Freeze source/runtime, execute R01–R16 with original callbacks, B/Schema/L/ACK/actual D/T/J and independent full-field comparison. Preserve every failed attempt. Run regressions, layout check and package; fresh final review. Update the five articles and validation summary; commit and push scoped changes after verification.

## Review focus

- Crash after marker or L COMMIT, before reply: no replacement B or premature ACK.
- Replay with different raw batch boundaries: stable identities and historical routing, never current routing applied to pre-B rows.
- Callback A holding PK DELETE must return so CREATE can arrive; original handles stay on one controlled path.
- Schema readiness requires Reader evidence, not JDBC metadata alone. Schema/history failure cannot silently restart at latest.
- Log capacity and connection/close uncertainty fence progress; no TTL deletes or automatic owner takeover.

## Progress and rulings

- Interface scan: B/schema/routing/log/frontier must share one CaptureStore transaction; consumer J references L, never the original SDK handle. Existing snapshot/CDC payloads retain their meaning.
- Initial baseline invocation used inherited Java 8 and failed compiler release 17 before tests. Rerun with the installed JDK17; no source change is justified.
- Existing MySQL :3306 has no authorized test credentials. Use a new dedicated loopback mysqld and private datadir/port; do not change or restart the existing service.

- Wire attempt 01 observed real zero-row transaction metadata from Offset writes when source and state shared an instance; this forms an ACK/Offset feedback loop. Preserve original callbacks. Ruling: shared capture requires an independent state server UUID, not merely another schema. Dedicated test state instance added; legacy paths unchanged. The same probe also exposed unquoted fixture table name `rows`; quote the fixture SQL.

- First real matrix: 16 scenario families passed after a test-only String CAS gate correction; expanded 26 then 30 cases passed. Earlier failed cutpoints remain invalid-cutpoint evidence, not data-loss observations.
- Final fresh-context review found four important issues: reverse B/K registry integrity, sealed historical Schema lookup, actual Store/owner server+database binding, and sticky source-control close uncertainty. Reproducing tests were observed failing before the one review-fix pass; production fixes preserve old serialized classes.
- Ruling: a stopped subscription consumes its immutable Schema version, even after unselected DDL; the current Schema registry is only for new subscriptions. Missing retained proof still pauses.
- Ruling: resource-release uncertainty fences future commands; it is separate from ordinary marker COMMIT ambiguity, which remains recoverable via the same operationId.
- Full backend default1GiB attempt exhausted heap. At3GiB 590 tests passed but legacy ActorSpec aborted during million-probe cleanup; preserve both logs, do not change this unrelated stress test. Run the remaining backend suites with an explicit ActorSpec exclusion and report the full-suite gate as incomplete.
- Unit real Reader ran concurrently with old recovery load and timed out waiting for K, with the Reader processing unselected transaction metadata. Preserve the jstack; final real runs are sequential, with source/manifest identity checked.

- Independent verifier found a harness export race in the first 31-case run (R10 baseline Actor cursor newer than the earlier Journal query). Test-only STATE now reads Actor first, then Journal, preserving a confirmed-prefix observation. The intermediate regression run was intentionally interrupted and retained; final unit/matrix/regression are rerun from the ordered-export source manifest.

- Final ordered-export version: source manifest 310fabe7d526c290659a7e96fb82d3aa996d8b7cebe4bc8756211c279925264c; 52/52 related tests, 31/31 shared matrix and 27/27 legacy real regressions passed. Three frozen runtime class-jar sets are byte-identical. Saved-evidence verification passed for both matrices.
- Packaging and explicit ActorSpec-excluded backend589/589 passed. Ruling: preserve the independently reproduced legacy million-probe cleanup failure rather than expand this phase into an Actor stress-test repair; original full-suite gate remains evidence_incomplete. User explicitly authorized scoped commit/push, so no integration menu or unsolicited PR is needed.
- Five articles, series validation table and architecture index updated. Failed cutpoints, sampling failures, interrupted run, all source snapshots and old C2 artifacts retained. Dedicated lab processes are cleanly shut down after checking transactions and connections, preserving private datadirs.
