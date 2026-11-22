# 分页 proof 表 GC 与容量边界（2026-11-13）

## 已验证的 GC 规则

分页 proof GC 只删除：

- `done=1` 的 proof；
- 不是当前 `pekko_capture_gc_actual_d_v1` proof；
- 没有被 `pekko_capture_gc_audits_v1`、`pekko_capture_gc_frames_v1` 或 `pekko_cdc_b_proof_anchor_v2` 引用；
- 删除前锁定 proof page，先删除 refs、nodes、prior、pin，再删除 page。

源 `pekko_capture_log`、ACK、Journal、GC checkpoint、目标账本不被删除。单次最多处理 8 个 proof；单个 proof 的子行超过 20,000 时保持 `BLOCKED/ACK_PROOF_CHILD_BATCH_LIMIT`，不截断。

## 真实 MySQL/Journal/Sink 结果

真实运行中，旧 actual-D proof `d9b4921a...`（head 7742，7731 nodes、7740 refs）在新的 actual-D proof 建立后被回收；当前 proof 为 `f777e7d3...`（head 13052，节点和引用各 12,672）。Source 与 Sink 当前两行六字段逐字段相等，API GC 保持安全状态，Source 仍有 Binlog Dump 会话且无 InnoDB 未提交事务。原始过渡采样见 `proof-gc-transition.txt`，最终状态见 `final-state.txt`，逐字段结果见 `reconciliation.json`。

这证明 proof 表不会随着 actual-D proof 无限累积，并且重启后的新 Runtime 仍能从当前 proof 继续验证。Recovery Anchor、GC audit 和活动 proof 被保留。

## 容量边界与未完成项

本轮功能代码已提交 `754f9aa`，修正已完成 operation 后 actual-D proof 不刷新的问题；后续 `0aaf013` 只把删除计数从瞬时状态改成保留状态。该 metric-only 版本的 KubeSphere PipelineRun `p6jr9` 长时间停在 Jenkins `RUNNING`，已作为外部阻塞材料保留，当前线上运行镜像仍是 `build-5-30cad31042c9`。因此“proof 删除功能”是实际通过，“删除计数持久化修订”是 `evidence_incomplete`。

仍未验证：多个并发 B proof、恶意/超大 proof、70 万活跃表的 proof 表容量、proof state 自身的二次 GC，以及 ACK 历史无限增长下的长期运行。
