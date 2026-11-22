# 控制入口及排空观察接口（2026-11-11）

未提交代码指纹见 `source-sha256.txt`。guardian 排空观察只依赖只读 owner 连接/账号清单；授权和同事务检查仍由旧控制存储承担。生产 supervisor 和 owner bootstrap 现在直接创建 CDC 控制存储；此前的旧 Workflow 分支已被 `workerMain` 的 CDC-only 检查挡住，不再创建旧调度存储。旧测试仍可编译，但访问旧 store 会明确失败；这不表示全部 Workflow 代码已删除。

- `component-tests.log`：57 项组件测试通过。
- `C0-3b0f32041bd7`：双 MySQL 9.5.0、独立 guardian/supervisor/CDC worker、真实 Reader、Journal 和 Sink；任务进入 STREAMING 后终止原 worker，接管后写入一条新的源变更，源目标 64 行、384 字段一致。记录含 T/J/L/D、隔离证明与清理；两个实例的最终事务数均为 0，随后关闭。这不覆盖未知 B。
- `c0-and-driver-failure.log`：同一 SBT 会话第二项排空测试因 JDBC 驱动在新测试类加载器中未注册而失败，未进入排空观察。为测试显式加载驱动后，后续诊断仍失败，失败日志均保留。
- `drain-timing-failed.log`：测试连接 1488 的 ROLLBACK 与 JDBC close 返回后，观察器在原有 5 秒期限内仍看到同一连接 ID 的未归属 `INNODB_TRX`。`drain-lockprobe-failed.log` 中，连接 1564 的 ROLLBACK/close 后约 2 毫秒，独立事务已成功获取同一行的 NOWAIT 锁；5 秒期限末观察器仍看到该连接的事务，约 5.25 秒时一次后续观察为空。原 5 秒断言保持失败，诊断等待不作为通过结果。

[MySQL 8.4 官方文档](https://dev.mysql.com/doc/refman/8.4/en/innodb-information-schema-internal-data.html)说明 `INNODB_TRX` 与 PROCESSLIST 不保证是同一快照；这里的实验为 MySQL 9.5.0，官方说明和观察现象不能单独证明本次延迟的内部实现原因。排空判定仍保守拒绝未归属事务，不能根据连接消失或可获取某一行锁就放行。未知 B 继续安全暂停。
