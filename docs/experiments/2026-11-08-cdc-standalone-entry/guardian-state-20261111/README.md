# guardian 身份及状态模型迁包验证

2026-11-11，guardian 的身份、绑定、隔离回执、owner 状态、数据库观察和 worker 启动记录迁至 CDC guardian 包。原隔离 capability 的私有访问范围、数据库事务和状态机未改。

干净构建及 guardian、supervisor store、隔离与旧 Split 解码 42 项组件测试通过，见 [component-tests.log](component-tests.log)。测试使用[此前真实实验保存的签名回执](../managed-cdc-c1-20261111/C1-e791978ead66/02-isolated.json)及公钥，验证迁包后仍能校验该旧回执；不代表所有历史数据格式均已验证。

隔离 MySQL 8.0.36 实验对外来标记和无标记会话保持非空判定，连接关闭后观察为空。见 [mysql-test.log](mysql-test.log) 和 [owner-and-audit.tsv](owner-and-audit.tsv)。测试结束时事务与测试库连接为 0，随后停止容器，见 [server-and-cleanup.tsv](server-and-cleanup.tsv)。[源码散列](source-sha256.txt)仅覆盖部分关键文件，工作区尚未提交。

MySQL 实验没有启动独立 guardian/worker JVM、Reader、Actor Journal 或 Sink，不能证明故障接管或端到端一致性。未知 B 仍按独立 CDC worker 的现有门槛安全暂停。
