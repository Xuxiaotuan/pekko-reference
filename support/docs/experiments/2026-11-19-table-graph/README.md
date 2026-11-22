# SharedMySQLCapture / TableActor / Sink 独立 Graph 验证

本轮使用真实 MySQL、真实 Debezium Reader、真实 JDBC Actor Journal 和真实 MySQL Sink，验证单张表的独立 materialized Graph。

## Graph 结构

```text
SharedMySQLCapture.tableSource
        ↓
Pekko Streams mapAsync(1)
        ├── MySQLTableGraphSink
        ├── BootstrapJournalTableGraph
        └── SharedMySQLCapture.acknowledgeTableBatch
```

TableActor 只负责 Graph 的启动、停止和状态拥有，不持有专用 JDBC 连接。Graph 的 materialized value 包含 KillSwitch、完成 Future 和最后一个表级 Checkpoint。TableActor 在 Stop 完成后清除 Graph 所有权，后续 Start 才能重新 materialize。

## 实际结果

首次 GRAPH 运行保留为 `evidence_incomplete`：测试进程返回通过，但 `graphJournal` 为 0。因为初版 `tableSource` 在 L 为空时结束，后续变更没有进入 Graph；仅凭源表和目标表仍相等会掩盖这个问题。证据见 `artifacts/initial-empty-log-failure.json`，原始日志目录为 `target/shared-capture-matrix/GRAPH-281af642c516`。修复为持续轮询 L 后，才进行下面的有效重跑。

GRAPH 场景通过。真实变更从源 MySQL 进入 SharedMySQLCapture 的 L，独立 Graph 读取后写入真实 MySQL Sink，BootstrapJournal 持久化表级 Checkpoint，Journal 成功后才推进 L consumer projection，源表和目标表逐字段对账通过，所有连接和 worker 退出。

结构化证据见 `summary.json` 和 `artifacts/final.json`。`final.json` 同时包含一个 Graph 的 MySQL ledger、逐字段比较和 `bootstrap-v1-graph-*` Journal 行，`cleanup.json` 单独记录停止后的连接与事务清理结果。原始独立 JVM 日志保留在本机 target 目录，未复制到仓库大文件材料中。

## 未验证边界

本轮只验证单表独立 Graph。多表同时 materialize、Graph 从 Journal Checkpoint 重启恢复、跨 JVM 恢复、Runtime Savepoint 协调、DDL 和 70 万物理表容量仍未验证。
