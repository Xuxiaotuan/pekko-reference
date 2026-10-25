# Guardian JVM restart: Linux isolation probe

**Goal:** exercise G5, G6, G7, G10, G14 and G15 with real systemd/cgroup v2, separate JVMs, MySQL, Actor Journal and Sink. Test facilities only; no commit or push.
**Spec:** approved conversation, 2026-10-24. Unknown isolation must pause with reservations retained. Existing T/J/D and persistence payloads remain unchanged.
**Architecture:** each old guardian and its children start inside a unique systemd service with ExitType=cgroup, Restart=no and KillMode=control-group. A test-owned observer outside that service records its identity and stops the whole boundary. A replacement guardian and supervisor have no original Process handles. A signed laboratory proof authorizes a test-only control-table adapter; it is never relabelled managed-child-jvm. Existing production database drain, quota return, Split, target ledger and Journal recovery remain in use.
**Environment:** dedicated isolated Ubuntu 24.04 OrbStack VM, 2 CPUs / 3 GiB RAM / 6 GiB disk; dedicated MySQL, JRE17. No existing containers or production services are touched.

## Constraints and review focus

- No server/src/main, build/dependency or existing experiment changes. Preserve all initial tracked bytes.
- Signatures establish provenance only. Proof requires a prebound unit/incarnation/cgroup, closed laboratory start path, successful stop and confirmed empty subtree. Missing initial identity is never an exit proof.
- G7 records an accepted asynchronous start with no laboratory attach, including an ExecStartPre JVM gate. Closing its job must prevent delayed main/fork after releasing the gate.
- G10 uses the existing transparent test relay outside the killed cgroup. Preserve actual server connection/transaction evidence before releasing it. The relay is a timing facility, not a production component.
- Negative cases attempt new admission and must be rejected; collect full Source/Target differences without describing pause as data convergence.
- Recovery proof adapter is explicitly laboratory-only because production rejects non-original-Process providers. No fake PID/owner or forged legacy receipt.
- Capture exact source/runtime hashes, immutable failed attempts, raw OS/SQL/T/J evidence and six-field reconciliation. Credentials/private keys remain outside public archives.

## Tasks

1. Environment and proof acceptance: inspect real PID1/cgroup/version; add rejection tests for absent identity, nonempty subtree, unsealed start and altered proof before implementing verifier.
2. Add separate guardian/supervisor JVM probes and narrow test-only proof adapter. Reuse existing real snapshot worker and residual relay. Verify G5/G6/G7 and four G10 roles; G14/G15 must pause.
3. Freeze runtime; run complete selected matrix, independent evidence verification and corruption controls; run backend suite/package; preserve failures, check production/persistence bytes and clean up only owned processes/services.

## Progress and rulings

- Baseline 2fe7c253eea0d5499e3558f5ab1259a068df3f29; clean existing feature checkout reused, no Git mutation.
- Host initially had no Linux machines. Created only pekko-guardian-os-probe-20261024; real PID1 systemd and cgroup2fs observed.
- User explicitly authorized execution of these probes; no additional design approval or production implementation is required.
- Cross-task interface: signed OS proof -> JVM test adapter -> original owner drain/quota APIs. Rule: adapter validates the new laboratory provider; never weaken production provider validation.
- Actual environment is an isolated OrbStack Linux machine, LXC inside its Linux VM, not a separate physical/fully independent kernel VM. Unit-specific overrides were needed to restore effective ProtectControlGroups/NoNewPrivileges; verified from systemd and process status.
- Three Important review findings fixed in test facilities: real G15 absence, continue cleanup after individual failures, and per-instance two-round offline verification. The latter was reproduced RED then rejected in GREEN. No production receipt/provider was forged.
- Partial-state JSON reads and missing Snapshot InnoDB transaction were fixture failures. Preserve both matrices; only add an InnoDB read to the Snapshot trigger, without relaxing the window assertion.
- Selected evidence: 11 scenarios from matrix-1792807956339393928 plus corrected Snapshot from matrix-1792808329205099669, 8 convergence / 4 safe pause, 80 rows x 6 fields. These are two live source versions, differing only in the test Snapshot trigger condition.
- Final uniform run matrix-1792808399413703090 is NOT accepted: G5 data completion exists but the control channel stalled before result/cleanup; independent file pull/export preserved it. A dedicated-machine restart has not restored control. No global OrbStack or unrelated service restart. Resource exit remains unconfirmed.
- Full backend: 586 assertions passed but original ActorSpec suite aborted on 10-second shutdown; isolated ActorSpec reproduced it. Initial 1GiB process hit GC/OOM. No adjacent test/protocol fix or timeout relaxation. Current proof6/6 and package passed; full suite remains evidence_incomplete.
- Final reporting/archiving continues offline; no commit or push. Uniform replay and environment exit confirmation remain external_blocked.
