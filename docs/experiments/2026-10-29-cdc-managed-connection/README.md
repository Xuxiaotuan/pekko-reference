# CDC 受管 JDBC 连接登记：组件验证

状态：**evidence_incomplete**。本轮将受管 CDC 控制连接、L 连接与源库连接接入既有 `SnapshotOwnerJdbcDriver`，在连接供应用使用之前登记 owner、数据库实例、账号和连接 ID；源账号在共享 Reader 启动前登记，以便排空观察器覆盖该专属账号的 SDK 连接。未受管路径仍使用普通 JDBC。

真实 MySQL 9.5.0 隔离实例上的单项测试：登记后的连接 ID 为 10，账号 `cdc_lab` 出现在 owner 清单中；owner 进入 revoking 后，新连接被拒绝。结果见 [结构化证据](result.json) 和 [执行日志](mysql-test.log)。本测试通过直接注册的测试 owner 检查单条 JDBC 连接，不包含真实 guardian、CDC Reader、Journal、Sink 或事务延迟提交，不能证明端到端排空或恢复。

[首次运行失败](first-command-failure.log) 是 sbt 项目名表达式解析错误，未执行测试；修正为先选择 project 后重跑。初始测试编译失败保留在 [test-red.log](test-red.log)。相关 4 个本地 suite 共 21 项通过，见 [摘要](unit-tests.txt)；源码摘要见 [source-sha256.txt](source-sha256.txt)。

仍需证明 SDK 自行创建的连接和所有账号都被完整覆盖、真实 guardian 隔离与多次排空观察持久绑定，并完成 UNKNOWN grant、Journal 和未知 B 的原子恢复许可及端到端对账。该改动不放开任何现有恢复闸门。
