# 跨 Chunk 与跨重启基线重建验证

本轮验证针对同一张 MySQL 表的多 Chunk 分页、Reader 读视图中断、同 owner 继续执行，以及新 JVM 不携带 continuation 时的跨重启恢复。数据链路使用真实 MySQL 8.0.36、Debezium MySQL Reader、JDBC Actor Journal 和 MySQL Sink。测试使用独立 source/state 实例，source UUID 与 state UUID 记录在环境材料中。

## 实际结果

| 场景 | 结果 | 关键证据 |
|---|---|---|
| 同 owner continuation | PASS | `same-owner-final.json` |
| 跨 JVM、无 continuation 的 full rescan | PASS | `full-rescan-final.json` |
| 关闭释放 | PASS | 对应 `*-release.json`，local termination、owner released、server release 均为 CONFIRMED |

两种通过场景都在最终状态执行源表与目标表的逐字段比较，差异、缺失和额外字段均为 0。日志中可以看到 LOW、HIGH、F、目标页写入、Journal 提交以及最终 STREAMING。Reader 读视图在故障后回滚，连接随后释放。

跨 JVM 场景额外出现 `BASELINE_RESET_AFTER_RESTART`。它删除旧 spool 页和独占目标表内容，在新的 Journal revision 下重新建立基线。该重建不增加 recoveryAttempts，避免正常进程替换耗尽恢复预算。逻辑 executionId 保持不变，新的 revision/epoch 是读视图与目标内容的隔离边界。

## 失败材料

`failure-page-count-assertion.json` 和 `failure-abandoned-assertion.json` 是修正测试断言前的真实失败输出，保留用于说明实现过程。它们分别暴露了完整分页实际为 5 页而旧断言写死 4 页，以及 full rescan 不应要求旧窗口产生 `ABANDONED` 事件。失败材料没有被通过结果覆盖。

## 源码结论

- 同 owner continuation 可以保留已经封存的窗口，只替换未封口的当前窗口。
- 新 JVM 没有 continuation 时，任何处于 STREAMING 之前且已经产生计划、窗口或完成索引的状态都触发持久化基线重建。
- 新窗口 ID 包含 Journal revision、Chunk index 和 attempt，避免跨重启复用旧读视图命名空间。
- `TableCdcGraphRuntime` 是当前 Kernel 使用的统一 per-table façade，内部仍委托 `BootstrapRunner`。这一步统一了入口，不等于已经把所有执行逻辑重写成独立 materialized Graph。

## 未验证边界

本轮没有在受管 guardian、独立 source observer/controller 账号和多 owner 进程环境中运行 source-fence-v2 的端到端接管矩阵。相关代码已有准入约束和 Journal/数据库证明路径，但真实 fencing、旧 owner 隔离、事务排空及 L floor 的完整生产链路仍需专用受管环境验证。也没有把本轮单表结果外推到 70 万物理表容量。
