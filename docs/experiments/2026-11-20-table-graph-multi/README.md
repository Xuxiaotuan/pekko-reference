# SharedMySQLCapture 多表独立 Graph 验证

本轮使用真实 MySQL、真实 Debezium Reader、JDBC Actor Journal 和 MySQL Sink，验证一个共享 Reader 同时承载两张表的独立 Pekko Streams Graph。

## 运行结构

```text
SharedMySQLCapture
    ├── TableGraph(a) → Sink(a) → Journal(a) → ACK(a)
    └── TableGraph(b) → Sink(b) → Journal(b) → ACK(b)
```

两张表各自注册 consumer、维护 cursor 和 batch sequence。Graph 内部仍按 `Sink commit → Journal persist → L ACK` 顺序执行。

## 实际结果

`GRAPH-MULTI` 场景通过。`a`、`b` 两张表各比较 24 个字段，缺失、额外和字段差异均为 0。实验记录了 3 条目标 ledger、3 条 Graph Journal 记录，停止后的清理连接数为 0。完整结构化证据见 `summary.json` 和 `artifacts/final.json`。

## 边界

本轮证明的是同一 Reader 上两张表 Graph 可以并发 materialize 并独立停止。尚未证明 Graph 从 Journal Checkpoint 重启恢复、跨 JVM 恢复、Runtime Savepoint 协调、DDL 和 70 万物理表容量。
