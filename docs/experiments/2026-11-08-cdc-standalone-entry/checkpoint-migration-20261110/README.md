# 检查点迁包后的 Journal 证明实验

2026-11-10，使用未提交工作区编译代码和仅绑定本机的专用 MySQL 8.0.36 容器，运行 `WorkflowJournalFloorMySqlSpec`。测试使用真实 JDBC Actor Journal、真实 MySQL 捕获存储和测试构造的 CDC 数据；它不连接真实 Source binlog，也没有真实业务 Sink。

检查点迁到 `cdc.checkpoint` 后，测试验证了 Actor 发出的真实 Journal receipt 与持久事件绑定，错误身份、过期和已删除的证据被拒绝，持有校验事务锁时后续 Journal 写入等待。结果见 [result.log](result.log)，当次 Journal 事件及序列化载荷见 [journal-events.tsv](journal-events.tsv)。实验结束时事务及测试库连接均为 0，随后停止容器；查询结果见 [server-and-cleanup.tsv](server-and-cleanup.tsv)。

另外，`LegacyCheckpointMigrationSpec` 使用迁包前由旧序列化器生成的测试样本，解码独立 checkpoint 值和包含 checkpoint 的旧 Workflow 事件。这些样本并非取自历史生产库；该测试也不证明旧版本能读新代码写入的数据。

本实验未验证真实 MySQL Source 到 Sink、端到端故障恢复或逐字段一致性，也未覆盖跨 JVM 重启。保留原有历史实验及失败记录，不能用本实验替代它们。
