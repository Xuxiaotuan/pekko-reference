# 控制存储切换模型迁包验证

2026-11-11，切换授权、凭据绑定及绑定计划类型迁至 `cdc.control`。字段约束、数据库表和事务逻辑未改；这些类型的现有存储路径是显式 SQL 字段，不是已发现的 Pekko Journal 事件。

在仅绑定本机的隔离 MySQL 8.0.36 中运行 `CdcCutoverStoreMySqlSpec`：使用合成授权和许可，验证授权及绑定的重复写入幂等、重新创建存储对象后读取、变更身份及错误许可被拒绝。见 [result.json](result.json)、[mysql-test.log](mysql-test.log) 和 [control-rows.tsv](control-rows.tsv)。测试结束后事务和测试库连接数为 0，随后停止容器，见 [server-and-cleanup.tsv](server-and-cleanup.tsv)。[源码散列](source-sha256.txt)仅覆盖部分关键文件，代码未提交。

这只验证控制存储的 SQL 契约，授权和许可由测试构造；没有验证 guardian 进程隔离、旧账号排空、真实 MySQL Source、Actor Journal、Sink 或端到端故障恢复，不能称为完整切换验证。
