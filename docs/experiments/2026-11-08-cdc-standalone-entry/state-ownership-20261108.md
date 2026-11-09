# 独立 CDC 入口状态归属快照

本文件记录当前工作树源码的静态归属，不代表所有路径经过端到端验证。版本与每个改动文件的哈希、Git 状态和保守评估见 [工作树清单](baseline-current-20261108.json)；清单不是备份，也不包含 Git 忽略文件。原始基线与实验不得覆盖。

| 状态 | 创建或写入 | 读取及用途 | 兼容边界 |
| --- | --- | --- | --- |
| T | `BootstrapTarget` 写目标占用与初始化回执；`MySQLCdcApplySinkNode` 在目标事务中写组账本和数据 | `BootstrapTarget`、Sink 的账本与重放检查 | 目标表、`pekko_bootstrap_targets`、`pekko_bootstrap_receipts`、`pekko_sync_cdc_group_ledger` 不得当作 J 或 D |
| J | `BootstrapJournal` 通过 Pekko Persistence 写 `Advanced`，由 `CdcBootstrapKernel` 建立任务 Actor | Runner 和 Kernel 恢复进度及验证；实际 Journal JDBC 由 Pekko 配置 | `bootstrap-v1-<captureId>-<taskId>`、旧事件类和序列化配置必须保留；不能把任务表中投影当作 Journal 原文 |
| L | `SharedMySQLCapture` 和 `MySQLCaptureStore` 持久化捕获记录、控制事件和 ACK 等 | `CapturedLogReader` 与兼容的 `CapturedLogSource` 按订阅读取，任务侧推进消费进度 | `pekko_capture_*`、顺序、ACK 及消费引用是持久协议；不能以 D 前进代替目标应用 |
| D | Debezium JDBC offset store 按其提交路径保存实际 offset，独立 JDBC schema history 保存结构历史 | Debezium 重启读取；共享 Reader 与恢复检查读取关联内容 | 表名通过 `MySQLCdcSourceNode.connectorStateTables` 与 connector ID 及配置确定；原始 ACK、L 和 D 是不同证据 |
| 任务、owner、grant | `CdcTaskStore` 写 `pekko_cdc_tasks_v1`、`pekko_cdc_owner_v1`、`pekko_cdc_grants_v1` 等 | `CdcPlatformRuntime`、`CdcBootstrapKernel` 和恢复校验读取；continuation 与 owner 绑定 | UNKNOWN 不能当作释放；任务表记录的 Journal revision 不是 Actor Journal 本身 |

独立入口 `CdcStandalone` 使用原 `CdcPlatformExtension`、`CdcPlatformRuntime` 与 `CdcBootstrapKernel`，沿用配置中的 Journal、序列化、capture state、任务表和目标配置；其 local ActorSystem 不初始化旧入口的 Workflow Sharding、Scheduler 和前端。旧入口 `PekkoGuardian` 同样创建 CDC Extension，但还初始化 Workflow Sharding、执行引擎和 Scheduler。两个入口若指向同一配置则访问相同 CDC 状态，不是互相复制或自动隔离；不得并行启动绕过 owner 约束。独立入口当前拒绝受管 HA 和 source fence v2 配置。

真实验证范围与失败证据见本目录 README。此次 5 轮关闭均 STOPPED，但第 5 轮目标未追上且任务因恢复预算耗尽暂停；因此不能据此宣称连续重启主流程稳定。阶段 0 清单只是这一次工作树的快照，后续修改需要重新核对，尚不是全部持久化结构的迁移证明。
