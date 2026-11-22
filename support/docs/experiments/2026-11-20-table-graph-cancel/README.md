# Table Graph 动态取消验证

本轮使用真实 MySQL、Debezium Reader、JDBC Actor Journal 和 MySQL Sink，先同时运行 `a`、`b` 两个表 Graph，再停止 `a`。

## 实际结果

`GRAPH-CANCEL` 场景通过。停止 `a` 后，`a` 不再消费后续变更，因此它与源表最终状态存在预期差异。`b` 继续消费并完成 24 字段逐字段对账，缺失、额外和字段差异均为 0。停止后的连接清理为空。

这里的“通过”只表示取消边界清晰且其他表不受影响，不把已取消表写成最终一致。结构化证据见 `summary.json` 和 `artifacts/final.json`。

## 未验证边界

取消后同一表重新订阅的 generation 恢复、多表取消与 Savepoint 协调、DDL 和 70 万物理表容量仍未验证。
