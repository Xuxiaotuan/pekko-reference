# Guardian restart boundary probe implementation plan

**Goal:** test the approved S1–S4 and S13–S15 boundaries with separate supervisor, guardian and worker JVMs, real MySQL, Actor Journal and Sink.
**Spec:** approved conversation: one guardian per host; loss of its process-control capability must pause safely; test facilities only; no commit or push.
**Architecture:** test-only authenticated Unix-domain RPC; guardian owns original production ManagedJvmIsolation/Process objects; supervisor persists laboratory launch/epoch records and calls existing production ownership/drain APIs. Worker entry reuses the existing real snapshot runner but takes commands from a test control file, never supervisor stdin.
**Tech stack:** existing Java17/Scala/Spray JSON/JDBC dependencies; no dependency or production source changes.

## Constraints and evidence

- Existing source, persistence payload types, T/J/D protocol and all prior archives remain unchanged.
- Laboratory control rows/proofs are test authority, not a production supervisor protocol or new production isolation provider.
- Negative cases must keep original grant/permit/quota and T/J; report full Source/Target differences without claiming completed synchronization.
- All processes and databases are created for this experiment; preserve schemas, failures, frozen runtime/source hashes and raw signed RPC evidence. Secrets remain outside archives.
- S13 signal injection targets the exact test-owned supervisor; no arbitrary PID adoption. S14 uses wrong identity/foreign test process as a negative control; actual OS PID reuse is not presumed.
- Guardian-loss cleanup is a separate test-only worker self-stop, never a recovery permission or accepted isolation proof.

## Tasks

1. Add test-only wire/store/guardian/supervisor/worker facilities; positive and corrupted authentication checks; characterize that a fresh production manager cannot adopt a previous manager's child.
2. Compile and run focused local checks, freeze source and runtime, pilot S1.
3. Run S1_READ, S2_PREPARED, S2_FORK_PENDING, S3_ACK_LOST, S4_PRE_BOOTSTRAP, S13_LOCK_CONTENDER, S13_SIGSTOP, S14_AUTH_IDENTITY, S15_GUARDIAN_DEAD, S15_CAPABILITY_LOST. Each independent schema records all six source/target fields plus T/J, process/guardian identity, proofs and quota.
4. Review the complete test facility with fresh context; verify targeted regressions, archive integrity and production-byte invariance; report results and unverified boundaries without committing/pushing.

## Progress / rulings

- Baseline afff7ab27560b74ad026aa370dba1cf920fdc587, clean feature checkout verified.
- Ruling: execute in the existing checkout, preserving its branch and existing build/runtime evidence; no new worktree or Git mutations are needed for test-only additions.
- Ruling: use laboratory tables and an authenticated relay of the original guardian-created `managed-child-jvm` receipt. This explores the boundary without pretending the proposed production guardian provider already exists.
- Facilities compile first failed on missing imports/ambiguous write and JsonWriter; log retained, not a behavioral RED. Corrected compile and 2 local wire/manager checks passed; subsequent focused ownership/compatibility set18 passed.
- Initial S1 pilot1/1 passed. Review found cleanup/finally and Journal/permit assertion gaps; test facilities corrected, including missing start identity treated as unknown and real repeated authenticated request nonce test.
- Final exact-source matrix `matrix-1792740757878036000`:10/10 expected outcomes;8 converged,2 S15 safe pauses. Positive80rows×6fields,5T/Jpages each. Negative Source10/Target0 each with unchanged grants/permits/T/J; cleanup self-halt is separate test authority.
- Final backend564/564,106suites andpackage passed. All125 production/config/build files match baseline bytes; selected frozen286-file source manifest equals current. No old tracked files changed, index empty, HEAD remains baseline.
- Resource observations clients0/trx0;11 isolatedschemas+volume retained, all probeJVMs exited and only task-owned MySQL container stopped. Artifact/archive verification and final review calibration recorded in experiment report.
