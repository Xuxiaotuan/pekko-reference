# CDC 主流程结构整理：验证记录

状态：**所选窗口通过；非完整故障矩阵**。本次整理 `SharedMySQLCapture` 内部回调步骤和 `BootstrapRunner.step` 的准备与推进步骤，保留持久化类型、T/J/D、原始 ACK 和恢复协议。基准提交 `574ba8ffb50330c2ba4b143d614d063ecc91f7f7`，重构后源码摘要见 [refactored-source.sha256](refactored-source.sha256)；受管样本还各自保存完整源码摘要。

| 样本 | 结果 | 范围 |
| --- | --- | --- |
| [I01](bootstrap/I01-e7cdbfc09bd4/) | 7 表、2688 字段全部相等 | `BootstrapRunner.run` 的多表初始化及动态新增 |
| [I11](bootstrap/I11-28a52c428e57/) | 1 表、378 字段全部相等 | `BootstrapRunner.run` 读视图丢失后固定范围恢复 |
| [I16](bootstrap/I16-959d36421cb5/) | 1 表、384 字段全部相等 | `BootstrapRunner.run` 目标与 Journal 故障窗口 |
| [C1](managed/C1-7bc6494fa216/) | 64 行、384 字段相等 | 测试 worker 接线，真实 guardian、MySQL、Journal、Sink；源 B 提交回复丢失 |
| [LATE_COMMIT](managed/LATE_COMMIT-02e01fa17307/) | 64 行、384 字段相等 | 测试 worker 接线，旧事务在隔离后迟提交；排空后恢复 |

受管样本 `03-recovered.json` 保留 B、F、T/J/D、permit、源和目标对账，worker 的事件日志保留原始回调及 ACK 顺序。`final-database-state.tsv` 显示两个样本的 snapshot owner 均 `db_drained`、ticket 均 `retired`、grant 均 `RELEASED`；但测试清理强制终止后继 worker，CDC owner 仍 `ACTIVE`，因此 `cleanup.json` 不是 CDC 正常停机证明。

对四份 worker 事件日志逐份比较 `RAW_CALLBACK`、`RAW_ACK_BEGIN` 与 `RAW_ACK_RETURNED` 的回调序号，已返回的 ACK 均为从零开始的已观察回调前缀，见 [ack-prefix-audit.json](ack-prefix-audit.json)。这只说明四份样本中记录到的顺序，不证明所有跨回调组合。

重构前后的针对性测试均为 26 项通过。真实测试第一次因实验 MySQL 进程随启动 shell 退出而在连接前失败，保留 [日志](real-regression-connection-failed.log)；实例改由持续会话持有后，I01/I11/I16 和 LATE_COMMIT 通过，但同一 sbt 会话切换套件后的 C1 在连接前遇到 JDBC 驱动不可见，见 [日志](real-regression-retry.log)。独立 sbt 会话复跑 C1 通过，见 [日志](managed-c1-isolated.log)。最终未再改变源码后分别运行 [初始化](bootstrap-final.log) 与 [受管恢复](managed-final.log)，均通过。不得把前置连接失败写成业务故障窗口已验证。

最终源码的 [默认后端回归](default-regression.log) 为 643 项通过。默认测试排除 `ExternalIntegration` 和 `Benchmark`，真实数据库窗口结果以前表及对应日志为准。

以上是指定样本，不是 61 项初始化或 6 项受管矩阵全量重跑；未验证生产入口全部窗口、跨 capture、DDL、GC、长期运行或 70 万真实表。首次的旧未提交 cursor 反例实验材料保留在相邻目录，未被本次整理修改。
