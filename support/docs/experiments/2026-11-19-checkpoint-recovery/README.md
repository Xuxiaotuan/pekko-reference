# Checkpoint 真实 MySQL / Actor Journal / Sink 恢复验证

本轮在独立 MySQL 槽运行当前源码 `472e809`，源库、状态库、JDBC Actor Journal 和 MySQL Sink 均使用真实服务。测试进程是独立 JVM，故障注入只控制提交回复、Journal 回复、Source D 和进程退出时序。

## 结果摘要

- 表级 Checkpoint 恢复窗口 7/7 完成。N0、J1、J2、C0、C1、K1、K2 均在重启后重新读取 Checkpoint，目标 Sink 返回幂等结果或继续提交，最终逐字段对账通过。
- 自动恢复套件本轮 14/17 完成。AUTO-A、AUTO-B、LATE_COMMIT、LATE_ROLLBACK、AUTO-D、AUTO-E、AUTO-F、R1、R2 通过。R3、R5、R6、R7-PK、R7-D 按预期进入 SAFE_BLOCK，保留上下文且未伪造恢复许可。
- R4、REJECT-J、REJECT-J-RETRY 的故障注入探针未进入预期的 `J_REPLY_HELD` 或 `J_REJECTION_READY` 事件。本轮和逐场景重跑均在注入前失败。它们是 evidence_incomplete，不计入通过，也不据此判定生产协议失败。

## 证据

`summary.json` 给出每个场景的结果、逐字段比较结果、源码版本、实验端口和材料路径。`artifacts/` 只保留结构化环境、Checkpoint、T/J/D、对账、阻断和探针失败材料。原始独立 JVM 日志保留在本机 `target/cdc-recovery-windows/` 与 `target/cdc-automatic-recovery/` 对应目录，未复制进公开材料，路径在 `summary.json` 的 `rawLogPath` 中记录。

材料中的 `comparison.equal=true` 只支持该场景的源/目标逐字段一致。SAFE_BLOCK 说明系统保守暂停，不代表目标已经追平。探针失败说明故障窗口没有建立，不应升级成协议结论。

## 代码层结论

当前生产实现的 Checkpoint 语义由 `BatchCheckpoint`、`BootstrapProgress`、`BootstrapJournal`、目标 ledger 和 L/ACK 引用共同承担。真实测试通过的是现有 `mysql.cdc` 兼容执行路径。它证明表级 Checkpoint、真实 JDBC Journal、目标幂等账本和 Source D 在指定窗口可以恢复衔接，不证明新 Runtime Savepoint 的跨 JVM 端到端恢复。

## 未验证边界

R4 和两个明确拒绝场景需要修复或重写故障探针后重跑。Runtime Savepoint 的真实 MySQL + Journal + Sink 恢复、70 万真实物理表容量、DDL、共享 Reader 自动接管和跨表目标原子提交仍未验证。
