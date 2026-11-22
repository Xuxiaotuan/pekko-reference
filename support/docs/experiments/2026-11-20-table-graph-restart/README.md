# Table Graph Journal 恢复验证

本轮使用真实 MySQL、Debezium Reader、JDBC Actor Journal 和 MySQL Sink，固定进程崩溃窗口，验证新 JVM 从同一 Graph Journal 恢复表级 cursor。

## 恢复路径

```text
旧 JVM
  Sink commit → Journal persist(deltaAfter, batchSequence) → L ACK → 崩溃
                              ↓
新 JVM
  Journal read → StartRecovered → Source 从 deltaAfter 继续 → Sink/Journal/ACK
```

## 实际结果

`GRAPH-RESTART` 通过。恢复前后的 `a` 表均比较 24 个字段，最终源表和目标表一致，差异、缺失和额外字段均为 0。Journal 记录 2 行，目标 ledger 记录 2 行，停止后的连接清理为空。

首次重跑曾出现测试探针在最终 `STATE` 查询时遇到 `Stream closed`，但对应 `final.json` 已显示恢复后的逐字段一致。该失败材料保留在本地 `target/shared-capture-matrix/GRAPH-RESTART-dfe61d67c646` 和 `GRAPH-RESTART-af29424a0ccc`，不替代本轮通过结果。

## 未验证边界

本轮只验证单表 Graph 的 Journal 恢复。多表同时恢复协调、跨 JVM Savepoint、DDL 和 70 万物理表容量仍未验证。
