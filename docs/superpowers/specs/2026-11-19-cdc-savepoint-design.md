# CDC Runtime Savepoint 设计

## 目标

在现有表级 `BatchCheckpoint`、`BootstrapProgress`、Journal 和 T/J/D 恢复协议之上，增加 Runtime 级 Savepoint。Savepoint 以共享 MySQL Reader 的 `captureEpoch` 为全局边界，以分页 Manifest 保存每张表的恢复状态引用；不改变旧持久化类型和既有恢复协议。

## 范围

- 保留现有表级 Checkpoint 和恢复状态。
- 为每个 capture 提供单调递增的 capture epoch、已持久化 L sequence、route digest 和表集合 digest。
- 增加关系型 Savepoint header/page/table reference 存储。
- 只允许完整、不可变的 `SEALED` Savepoint 恢复读取。
- 动态新增表不回写旧 Savepoint；取消表通过 tombstone 表示。
- 不实现跨表目标事务原子性，不重写 LOW/HIGH/F、T/J/D 或 UNKNOWN B 协议。

## 不变量

1. capture epoch 只单调递增，不能回退。
2. Savepoint 只能引用已存在的表级状态和当前 generation。
3. Manifest page digest 和最终 manifest digest 必须可重算。
4. 非 `SEALED` Savepoint 不可恢复。
5. Savepoint 创建失败不改变表级 CDC 任务的恢复状态。
6. JDBC、ActorRef、Debezium engine 和 ACK handle 不进入 Savepoint 持久化数据。

## 恢复语义

恢复时先校验 source lineage、capture id、protocol version、owner epoch、manifest digest，再逐页读取表引用。表级 phase 原样交回现有恢复调度器；不能证明的表保持暂停，不伪造 STREAMING。

## 未覆盖

跨表目标事务一致性、外部对象存储、DDL 专项协议、自动接管协议和 70 万真实活跃表容量均不由本次改动宣称完成。
