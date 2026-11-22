# 生产 Reader 的 marker 与版本锁冲突探针

状态：**已在真实 Reader 复现锁等待；重入改造尚未实现。** 两台隔离 MySQL 9.5.0（源 ROW/FULL）和项目的真实 Debezium Reader；测试源码摘要见 [probe.sha256](probe.sha256)，[运行日志](runner.log)、[结构化结果](result.json)、[回调与 ACK](events.jsonl)、[实验清理](cleanup-lab.json)均保留。

测试先启动并等待 Reader 准入，然后仅在实验 schema 中建立版本表及控制表 INSERT 触发器，并由测试工厂排除版本表捕获。测试连接锁住版本行，随后调用生产 Reader 的 `subscribe`；Reader 为 marker 新建的源连接在触发器更新同一行时遭遇 MySQL 锁等待超时，异常链包含 `MySQLTransactionRollbackException`，订阅状态为 `B_PENDING`。测试通过表示成功观察到该阻塞，而不是重入已修复。

结果支持先前关于独立连接会与持有的版本锁冲突的源码推演。实验没有生产版本许可、guardian、Journal、Sink、实际 D 或崩溃恢复，不能说明跨库竞态已解决。关闭测试 Reader 后，源实例仍观察到一条 binlog dump 连接；没有活跃事务。随后只停止租约内的专用实例，不把本次结果描述为正常资源释放证明。
