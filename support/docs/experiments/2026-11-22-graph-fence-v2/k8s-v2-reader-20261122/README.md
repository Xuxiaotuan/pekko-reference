# bigdata-lab V2_READER 实际材料

环境：bigdata-lab 集群的 `cdc-runtime-acceptance` 命名空间。Source 和 State 使用本轮临时创建的两个 MySQL 8.0.36 Deployment，实验通过 SSH port-forward 连接到本地测试进程。业务 `bigdata-lab/mysql` 未复用、未修改。

## 结果

- Worker 启动阶段全部完成，包含 Guardian attestation、managed manifest、runtime、capture 和 HTTP ready。
- V2_READER 主流程使用无故障 Reader 启动路径，真实 MySQL Source、Actor Journal、Graph 和 Sink 均完成运行。
- `03-v2-reader-b-captured.json` 的 `equal=true`，`fieldsCompared=384`，`missing=[]`、`extra=[]`、`fieldDifferences=[]`。
- `cleanup.json` 显示 `guardianDrainConfirmed=true`、`sessionsGone=true`、`dataAssertionsPassed=true`。
- 旧 C1 `PROXY_COMMIT_OK_DROPPED` 失败材料保存在 [`v2-reader-c1-failure-20261122`](v2-reader-c1-failure-20261122/)，其中任务停在 `RECOVERY_PENDING/local_release_unconfirmed`，不能用主流程通过覆盖这个边界。

`result.json` 的 `V2_READER_B_ONLY_PASS` 对应本次主流程逐字段对账。C1 结果仍按失败证据记录，未知提交回复后的自动恢复尚未宣称通过。
