# 常驻 CDC 主流程与状态边界

本文记录重构前后的现有实现，不代表所有窗口都已完成端到端验证。版本与实验边界见 [CDC 系列验证汇总](../articles/cdc-design/README.md)。

```mermaid
flowchart LR
  API[CDC API] --> Task[任务索引与有界准入]
  MySQL[MySQL binlog / 控制表] --> Reader[共享 Reader]
  Reader -->|完整事件组与覆盖证明| L[持久捕获日志 L]
  Reader -->|覆盖后按回调前缀确认| D[Debezium 实际 D]
  L -->|按订阅读取| Runner[表级 BootstrapRunner]
  Task -->|grant| Runner
  Runner -->|目标写入及账本| T[目标 T]
  Runner -->|持久化阶段与游标| J[Actor Journal J]
  Guardian[Guardian / Supervisor] -.隔离和恢复许可.-> Task
```

`SharedMySQLCapture` 对原始回调做解码、控制事件绑定、完整组装配和持久化，只有 L 覆盖后才按回调前缀调用提交句柄；D 由 Debezium 保存，不能用 T 或 J 代替。回调内部分组和回调的边界不同，跨回调事件不能提前确认。

```mermaid
flowchart LR
  B[订阅 B] --> Plan[固定分片范围]
  Plan --> LOW[LOW]
  LOW --> Scan[分页读取 Split]
  Scan --> HIGH[HIGH]
  HIGH --> Fold[回填窗口变化]
  Fold --> Seal[封存窗口与证明]
  Seal --> Apply[目标页 T 后记录 J]
  Apply --> More{还有 Split?}
  More -->|是| LOW
  More -->|否| F[F 边界]
  F --> Replay[回放至 F]
  Replay --> Active[目标激活及 J 确认]
  Active --> Stream[持续消费]
```

B 是捕获订阅边界；LOW/HIGH 界定一次初始化窗口；F 是全量完成后的回放边界。未封存窗口失去读视图时沿用已记录范围重读；封存结果必须经过证明校验。实时回放按完整组提交目标 T 后再更新 J；这是表级交付顺序，与 Reader 的原始回调 ACK / D 是不同的边界。

资源释放或提交结果未知时保留 UNKNOWN，不能仅凭线程停止或超时释放配额。已有证明预算、日志回收、DDL、70 万真实表及 guardian 故障覆盖的限制仍以各实验报告为准；本次结构整理不改变这些协议和结论。
