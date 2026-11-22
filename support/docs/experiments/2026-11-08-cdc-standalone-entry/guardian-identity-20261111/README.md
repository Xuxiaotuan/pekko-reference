# guardian 控制身份迁包验证

2026-11-11，将 guardian 绑定、supervisor 控制令牌、worker 身份和隔离回执迁至 CDC 包；保留字段、校验和 JSON 编码。40 项 guardian、supervisor store 和隔离组件测试通过，其中一项使用[此前真实实验保存的签名回执](../managed-cdc-c1-20261111/C1-e791978ead66/02-isolated.json)及其公钥，验证迁包后仍可校验旧签名。

在仅绑定本机的独立 MySQL 8.0.36 上，`MaterialOwnerDrainMySqlSpec` 使用真实连接和事务验证外来标记、无标记会话阻止空闲判定，关闭后可观察到排空；见 [mysql-test.log](mysql-test.log)、[owner-state.tsv](owner-state.tsv) 和 [owner-audit.tsv](owner-audit.tsv)。实验结束时事务和测试库连接均为 0，随后停止容器，见 [server-and-cleanup.tsv](server-and-cleanup.tsv)。

这些记录通过 SQL 字段和显式 JSON 格式保存；本实验未验证所有可能的历史序列化方式。MySQL 测试没有启动 guardian 或 worker JVM、CDC Reader、Actor Journal 或 Sink，不证明自动接管或端到端一致性。代码为未提交工作区版本。
