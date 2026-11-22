# bigdata-lab V2_READER 实际材料

环境：bigdata-lab 集群的 `cdc-runtime-acceptance` 命名空间。Source 和 State 使用本轮临时创建的两个 MySQL 8.0.36 Deployment，实验通过 SSH port-forward 连接到本地测试进程。业务 `bigdata-lab/mysql` 未复用、未修改。

## 结果

- Worker 启动阶段全部完成，包含 Guardian attestation、managed manifest、runtime、capture 和 HTTP ready。
- `PROXY_COMMIT_OK_DROPPED` 已实际发生，旧 owner 的 Source binlog 会话被 Guardian 观察并最终排空。
- 旧 owner 隔离证明、`db_drained`、新 owner 启动和 source fence recovery permit 均有记录。
- 新 Reader 实际收到 `B_BOUND` 和 `BARRIER`，L 中存在对应 barrier，订阅状态进入 `CAPTURE_READY`。
- 任务在新 owner 控制轮询期间遇到 `ManagedOwnerStore.registerInstance` 的资源登记竞态，最终回到 `QUEUED`，目标表没有写入。
- `03-v2-reader-b-captured.json` 的 `equal=false`，缺失源表 64 行，不能记为 Sink 逐字段一致。

`result.json` 中测试套件的 PASS 只表示故障清理和 Guardian 证据检查通过，不能升级为端到端数据对账通过。原始 `03` 文件保留了这一差异。
