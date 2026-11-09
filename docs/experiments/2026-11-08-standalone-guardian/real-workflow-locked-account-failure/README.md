# 旧 Workflow 账号锁定后的恢复反例

- 结果：`failed`；2026-11-08，隔离的本机真实 MySQL、Actor Journal 和 MySQL Sink。固定 `KILL_T_BEFORE_J`，即目标批次已提交、Actor Journal 尚未确认的窗口。
- 测试使用专属旧账号创建 Workflow，并在旧 worker 存活时锁定该账号。`legacy-account-locked.json` 记录旧 worker 存活且旧账号新登录被拒绝。旧 worker 随后被隔离，测试协调器尝试接管。
- 新 worker 的持久化 Workflow 配置仍引用旧用户名；`worker-1.log` 记录 MySQL `Access denied`，主测试等待自动恢复超时。`pekko-real-old-account-20261108.log` 保留原始测试结果。未完成故障后逐字段一致性对账，不能据此声称自动接管成功。
- `cut.json` 和 `legacy-account-locked.json` 的数据包含切点 T/J/D、批次证据及当时的比较；这些只是切点观测，不是恢复后的完整对账。`workers-after-cleanup.json` 和 `database-cleanup.json` 记录实验后两个 worker 退出以及残余客户端数。
- 本实验使用测试协调器与受管进程设施，不证明独立生产 guardian 的完整切换链路。旧账号和专用连接仅用于实验；环境密码已从材料中移除。
- 源码显示 Workflow DSL 持久化 `username`，新 worker 从旧 DSL 恢复 Source/Sink 配置；待设计并验证受身份、数据库与恢复许可约束的凭据重新绑定。不得仅据此断言任意部署都会发生此问题。
