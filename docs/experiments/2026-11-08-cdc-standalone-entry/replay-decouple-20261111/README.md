# CDC 初始化回放移除 Workflow Node 后的目标事务验证

2026-11-11，使用未提交工作区代码和仅绑定本机的隔离 MySQL 8.0.36 运行 `BootstrapReplayClaimMySqlSpec`。测试直接使用 CDC 目标配置，不再经 Workflow Node 分支。

测试验证目标占用不匹配时拒绝写入、目标事务持锁时并发篡改被阻塞、目标 COMMIT 成功但回复丢失后重放识别已提交；最终两行各两个字段一致，另保存对应的目标占用及两条组账本记录。结果见 [mysql-test.log](mysql-test.log) 和 [target-and-ledger.tsv](target-and-ledger.tsv)。结束前专用库连接和事务数均为 0，见 [server-and-cleanup.tsv](server-and-cleanup.tsv)，随后停止容器。

该实验是目标侧受控组件测试，不包括 MySQL Source、Actor Journal、LOW/HIGH、实际 binlog ACK 或独立 CDC worker 的故障恢复；不能把两行比较扩大为端到端全量一致。
