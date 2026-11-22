# bigdata-lab V2_READER 实际材料

环境：bigdata-lab 集群的 `cdc-runtime-acceptance` 命名空间。Source 和 State 使用本轮临时创建的两个 MySQL 8.0.36 Deployment，实验通过 SSH port-forward 连接到本地测试进程。业务 `bigdata-lab/mysql` 未复用、未修改。

## 结果

- Worker 启动阶段全部完成，包含 Guardian attestation、managed manifest、runtime、capture 和 HTTP ready。
- V2_READER 主流程使用无故障 Reader 启动路径，真实 MySQL Source、Actor Journal、Graph 和 Sink 均完成运行。
- `03-v2-reader-b-captured.json` 的 `equal=true`，`fieldsCompared=384`，`missing=[]`、`extra=[]`、`fieldDifferences=[]`。
- `cleanup.json` 显示 `guardianDrainConfirmed=true`、`sessionsGone=true`、`dataAssertionsPassed=true`。
- C1 `PROXY_COMMIT_OK_DROPPED` 复跑已完成旧 Owner 排空、Journal `RECOVERY_PENDING` 绑定、新 Owner Reader gate、两窗口扫描、回放、激活和持续流转。`03-v2-reader-captured-c1-20261122.json` 的 `equal=true`，`fieldsCompared=384`，三类差异均为空。`cleanup-c1-20261122.json` 显示 Guardian drain、会话释放和数据断言全部通过。
- 旧 C1 失败材料仍保存在 [`v2-reader-c1-failure-20261122`](v2-reader-c1-failure-20261122/)，其中任务停在 `RECOVERY_PENDING/local_release_unconfirmed`，保留为修复前证据。

`result.json` 的 `V2_READER_B_ONLY_PASS` 对应主流程逐字段对账。`result-c1-20261122.json` 的 `V2_READER_B_ONLY_PASS` 对应 C1 自动恢复后的逐字段对账。两次证据均使用同一套 source/state UUID 和实验 worker 源码清单。

## C1 关键证据

| 阶段 | 证据 |
| --- | --- |
| 旧 Owner | `01-unknown-c1-20261122.json`、`02-isolated-c1-20261122.json`，旧进程 exit 137，Guardian 证明隔离和排空 |
| Journal | `RECOVERY_PENDING` permit 被新 Owner 消费，随后恢复到 `QUEUED`，没有重新生成 B |
| Reader | 同一 source fence permit 安装 gate，SharedMySQLCapture 从已验证的 D 启动 |
| Graph/Sink | `03-v2-reader-captured-c1-20261122.json`，384 个字段逐字段一致 |
| 清理 | `cleanup-c1-20261122.json`，`guardianDrainConfirmed=true`、`sessionsGone=true` |

本轮还观察到 ACK proof 分页与 Reader 回调并行时的锁竞争。capture-state 单条 JDBC 控制语句超时从 5 秒提高到 30 秒，C1 在相同故障窗口下完成，未把超时重试当成数据正确性证据。
