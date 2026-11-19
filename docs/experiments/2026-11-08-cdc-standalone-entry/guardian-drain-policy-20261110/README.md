# guardian 排空观察判断拆分验证

2026-11-10，在仅绑定本机端口的专用 MySQL 8.0.36 容器上运行 `MaterialOwnerDrainMySqlSpec`。迁出的是会话身份和 PROCESS 权限判断函数；事务排空观察、隔离证明及配额状态机未改。

首次运行在观察前因测试账号仅允许 `localhost` 和 `127.0.0.1`，而容器实际看到客户端网桥地址，连接被拒绝；保留 [失败日志](initial-failure.log)。测试设施读取本次连接实际的客户端地址并为该地址单独创建临时实验账号后，[重跑通过](retry.log)：其他 owner 标记的连接和未标记连接都阻止空闲判定，连接关闭后观察为空。

这是使用真实 MySQL 会话和事务的排空观察组件测试，没有启动独立 guardian/worker JVM、CDC Reader、Actor Journal 或 Sink，也没有验证故障接管和端到端逐字段对账。实验仅在独占容器中执行；结束时事务和测试库连接均为 0，见 [server-and-cleanup.tsv](server-and-cleanup.tsv)，随后容器停止。源码为未提交工作区版本。
