# 控制模型解耦验证（2026-11-11）

这组证据对应未提交工作区，精确源码见 `source-sha256.txt`。`SchedulingLimits` 和数据库恢复许可视图迁到 CDC 控制模型；Workflow 节点凭据重绑定保留在旧执行器适配层。它们未改变 SQL 表或 Journal payload，持久切换策略哈希使用固定历史值核验。

- `component-tests.log`：43 项组件测试通过，包括授权、隔离能力、旧执行节点凭据校验与策略哈希固定值；使用 H2 或本地进程，并非端到端。
- `mysql.log`、`mysql-state.tsv`：隔离 MySQL 9.5 上两个测试通过，覆盖新旧控制存储竞争同一锁、未知配额在排空前保留、排空后释放、旧准入拒绝，以及合成授权/许可绑定的幂等读回。
- `result.json`：切换授权测试结果；使用合成授权和许可，不是实际 guardian 隔离证明。

没有验证源 MySQL → Reader → Sink 的端到端追平、真实旧作业切换或未知 B 自动恢复。数据类型迁移不代表 guardian、控制存储或旧执行器已经完全去除 Workflow 依赖。
