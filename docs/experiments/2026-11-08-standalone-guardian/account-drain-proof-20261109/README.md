# 旧账号连接与事务排空观察

2026-11-09 在本机隔离 MySQL 上运行 `CutoverAccountMySqlSpec` 与 `SnapshotSupervisorStoreSpec`，最终 21 项通过。此测试验证账号观察组件和控制代次，不是 guardian、Journal、Sink 串联的完整切换实验。

- 旧账号锁定后原有连接和事务仍存在；检查拒绝视为排空。旧事务实际提交后、连接退出，仍短暂观察到一个没有 processlist 对应项的 InnoDB 事务；本次记录中约 110 ms 后，检查通过。不能把这一时长推广为其他环境的上限。
- 检查拒绝缺少全局 PROCESS 权限的观察者，也拒绝可信代理用户名仍存在其他连接的情况；不能归属的事务会使检查保守失败。
- 经 `LegacyAccountLockIntent.verifyDrain` 的调用会重验当前 supervisor 代次、固定计划和账号锁定；代次过期会拒绝。它只是一时的数据库观察，不是持久化恢复许可，不能单独授权配额迁移或凭据替换。
- `first-failure.log`、`second-failure.log`、`third-observation.log` 是发现未归属事务及采集诊断时的失败记录，`eventual-clear.log`、`controller-final.log` 和最终版本的 `final-nullguard.log` 是有界等待后的结果。最终版本对空用户和空事务线程号保守拒绝是源码结论；本实验未人工构造该空值场景。测试使用一次性数据库，旧账号在清理中删除。
- 未验证独立 guardian 与账号排空的端到端绑定、跨实例全局排空、崩溃后凭据重绑及 Sink 逐字段追平；控制管理员独占仍是明确的外部前提。

## 新旧控制策略基础测试

`migrated-policy-final.log` 记录最终源码在隔离 MySQL 和 H2 的 22 项测试。测试显式写入切换后的策略哈希，旧存储随后拒绝事务；新 CDC 控制存储能读取旧 `UNKNOWN` 配额、重建存储实例并释放该配额。测试直接将 owner 设为 `db_drained`，因此释放结果仅证明存储机制，不证明实际 guardian 隔离、事务排空或自动授权。新策略在切换标记不存在时不会自行初始化，原入口默认策略保持不变；生产切换主流程和凭据重新绑定尚未接入。

## 运行时凭据与 permit 顺序

最终真实 MySQL 控制存储回归为 30 项通过。新增检查要求 credential binding 必须能关联 `pekko_snapshot_writer_permissions` 的 ACTIVE/RECOVERING permit、execution、owner 和 running task；没有 permit 时不会激活绑定。切换授权器现在只写已验证的切换授权并要求下一 worker 仍是 prepared ticket，绑定必须在下一 owner 获得 permit 后再激活。`cutover-binding-real-final.log` 保留真实 MySQL 结果。

这组实验没有把测试夹具升级成旧 Workflow + 独立 guardian + Journal + Sink 的完整恢复证明；该端到端故障仍需下一阶段实验。
