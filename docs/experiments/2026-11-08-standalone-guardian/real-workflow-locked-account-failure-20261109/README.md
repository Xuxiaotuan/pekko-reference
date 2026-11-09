# 旧 Workflow 账号锁定后失败时的对账

2026-11-09 使用隔离的本地 MySQL、真实 Actor Journal 和 MySQL Sink，固定 `KILL_T_BEFORE_J`。这是上一轮旧账号反例的复测，新增失败前的状态和逐字段对账采集；测试协调器不是独立生产 guardian。

- `legacy-account-locked.json`：记录锁定旧账号时旧 worker 仍存活，新连接被拒绝。
- `failure-before-cleanup.json`：接管等待超时、清理前采集的 T/J、任务与 owner 状态和完整逐字段比较。T 为 1 条、J 为 10 条；源 10 行、目标 2 行，`equal=false`。这是失败窗口的状态，不是最终恢复结果。
- 该实验为静态 Split 快照，没有 binlog Reader；D 和原始 ACK 不适用。
- `worker-1.log` 记录新 worker 使用旧用户名连接被拒绝；主测试日志记录超时及失败。`workers-after-cleanup.json` 和 `database-cleanup.json` 分别记录两个 worker 已退出及残余客户端数为 0。
- 此结果不能证明生产 guardian 隔离、完整配额迁移或受许可约束的凭据重新绑定已完成。实验账号为临时账号，环境密码未写入材料。
