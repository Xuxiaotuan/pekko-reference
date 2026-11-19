# 源 fencing 校验与同事务 marker 写入候选

状态：**真实 MySQL 组件测试通过；候选仅保存在 test 源集，未接入生产 Reader 或恢复协议。** 测试使用独立 MySQL 9.5.0，源端口 25421、状态端口 25422；实例身份、停止和租约释放见 [cleanup-lab.json](cleanup-lab.json)及[复测清理](review-cleanup-lab.json)。最终 test 源码摘要见 [test-only-source-final.sha256](test-only-source-final.sha256)；[source.sha256](source.sha256)是迁移前历史版本的摘要，旧路径已迁出生产源集。

候选方法要求调用方提供全新、专用的源事务；它在同一连接读取源 UUID 和数据库名、以 `FOR UPDATE` 锁住源 fence 行并校验 capture、源 UUID、代次、token、版本和状态，然后插入 marker，并通过锁定读取核对 operation 及内容。方法不创建 fence 表、不发放许可、不提交；遇到异常会回滚，回滚失败时尝试关闭连接。它无法可靠判断调用方是否传入了已包含其他操作的事务，所以不能作为生产安全入口。

真实 MySQL 测试在源库**测试表**中记录：自动提交、缺失 fence、错误源 UUID、代次、token 或版本均拒绝；正确 fence 下 marker 在提交前对另一连接不可见，持锁期间并发版本更新以 MySQL 1205 超时，提交后 marker 可见；相同 operation 不同 marker 内容被拒绝。该测试不包含版本表触发器、Debezium Reader、guardian、Journal、实际 D、Sink、提交结果未知或重启。

测试先记录类缺失的预期红灯 [red.log](red.log)，再记录初次通过 [green-first.log](green-first.log)，增加事务锁竞争断言后复跑通过 [green.log](green.log)。独立审查指出大小写不敏感主键可能使不同 operation 错误复用，新增断言后真实复现失败 [review-red.log](review-red.log)；修正并迁入 test 源集后，[review-final.log](review-final.log)通过，还覆盖数据库名不符、异常后回滚、旧 REPEATABLE READ 视图，以及注入回滚失败后的关闭尝试。停止实例前未发现用户连接和活跃 InnoDB 事务。

重要边界：此方法不能阻止通过其他代码或 SQL 直接写控制表，尤其尚未覆盖 supervisor 的 WAKE 写入；也无法保证事务提交之后 fence 状态仍有效。生产启用前仍需可信表/触发器与权限约束、跨 capture 协议、旧记录迁移、恢复许可与 Journal 的持久化联动、提交未知与故障测试。不得根据该组件测试宣称端到端恢复或安全 GC 完成。
