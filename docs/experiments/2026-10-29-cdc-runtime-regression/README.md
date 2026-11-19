# CDC 运行主流程回归

隔离双 MySQL 9.5.0 的 `P01-dynamic-seven` 真实 Reader、Actor JDBC Journal、MySQL Sink 回归通过；7 张表每张源和目标均 64 行、384 字段相等，停止后物理释放确认。见[对账结果](final.json)、[释放结果](release.json)和[测试日志](test.log)。关闭期间曾观察到 binlog 会话未立即消失，测试等待实际释放后才通过。

首次运行在源库创建测试账号时失败，原因是实验管理员没有 GRANT OPTION；见[失败记录](fixture-failure.json)和[首次日志](first-test-failure.log)。只在独立实验实例补充授权后重跑。本样本不是 guardian 管理的 CDC owner，也没有未知 B 或故障恢复，不能证明本次自动恢复实现。
