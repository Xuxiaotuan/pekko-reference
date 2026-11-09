# 真实 Reader 的同连接 marker 探针

状态：**指定重入样本通过；生产许可协议尚未实现。** 独立 MySQL 9.5.0、真实 Debezium 和生产 `SharedMySQLCapture`，测试连接代理将持有版本行锁的同一连接借给 Reader 写 marker；版本表和触发器均为隔离实验设施。源码摘要见 [probe.sha256](probe.sha256)，原始结果、事件、日志见 [result.json](result.json)、[events.jsonl](events.jsonl)、[runner.log](runner.log)。

读取并锁定版本 0 后，在同一连接上调用 Reader 的订阅写入，版本触发器成功将版本更新为 1；Debezium 接收 marker，订阅进入 `CAPTURE_READY`，Reader 没有报告错误。与[独立连接超时反例](../2026-11-07-marker-version-reentry/README.md)一起，支持这一 MySQL/Reader 样本中的重入可行性。

借用连接和隐藏 `close()` 的代理均只存在于测试；生产 Reader 不具备此连接交接。本实验没有生产版本许可、guardian、Journal、Sink、断连、提交结果未知、重启或实际 D 断言；marker 提交本身会释放该事务的版本锁，因此不能据此认为随后的 Journal 或许可消费受到该锁保护。

实验关闭前没有活跃 InnoDB 事务；源端仍看到一条 binlog dump 连接，之后租约内两台实例已停止，见 [cleanup-lab.json](cleanup-lab.json)。不把本次结果作为正常资源释放证明。
