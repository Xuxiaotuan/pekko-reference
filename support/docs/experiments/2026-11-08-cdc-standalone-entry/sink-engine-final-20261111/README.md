# CDC MySQL Sink 核心拆分后的目标事务实验

2026-11-11，使用未提交工作区代码与仅绑定本机的独立 MySQL 8.0.36，运行 `BootstrapReplayClaimMySqlSpec`。CDC 回放直接调用 `MySQLCdcApplyEngine`，其事务逻辑和类型化目标配置不再引用 Workflow Node；旧 Node 解析和接口仍作为生产兼容适配器存在。

结果：目标占用不匹配时拒绝、事务持锁时篡改受阻、目标 COMMIT 成功但回复丢失后重放识别已提交；目标两行各两个字段一致。[测试日志](mysql-test.log)、[目标与账本](target-and-ledger.tsv)、[源码散列](source-sha256.txt)已保存。结束时测试库连接与事务均为 0，见 [server-and-cleanup.tsv](server-and-cleanup.tsv)，随后停止容器。

本实验只覆盖目标侧事务，不包含真实 Source、Journal、LOW/HIGH、原始 ACK 或端到端全量对账。散列对应未提交工作区的部分关键文件，不是冻结发行包。
