# 受管 CDC worker 的未知 B 故障实验

2026-11-10 至 11-11，在本机独占的两个 MySQL 8.0.36 容器上，将 `CdcProductionEntryUnknownBSpec` 的 worker 配置改为受管 `CdcRuntimeMain` 并运行 C1。源码为未提交工作区版本，每次源码摘要、B 切点、进程状态、T/J/L/D 与数据比较保存在各自的实验目录。

- [第一次](C1-432e6eaa6eda/)：真实 B 提交回复被固定，任务进入 UNKNOWN；随后测试设施错误地访问已关闭的 Workflow 调度存储而失败。会话消失、guardian 已退出，但测试未确认 guardian 排空证明，`clean=false`。不能把会话消失等同于隔离证明。
- [第二次](C1-e791978ead66/)：测试改为访问 supervisor 自身的 owner 注册表后，记录了 UNKNOWN、旧 worker 隔离和新 worker 启动；等待 STREAMING 超时，测试失败。失败状态源表 64 行、目标 0 行，缺少 64 行，不能称为逐字段一致。清理记录为 guardian 排空已确认、会话消失、`clean=true`，数据断言未通过。

第二次 worker 日志反复记录恢复校验 `IllegalArgumentException`，但未输出具体异常消息。源码中独立 CDC worker 明确设置 `allowPendingB=false`，当存在待恢复 B 时会拒绝；这与观察到的 UNKNOWN/PAUSED 一致，但日志本身未单独证明每次异常的具体来源。不得为通过实验绕过该门槛。

测试结束后两个容器的事务及专用账号会话均为 0，见 [source-cleanup.tsv](source-cleanup.tsv) 和 [state-cleanup.tsv](state-cleanup.tsv)，随后停止容器。两次命令输出分别保存在 [initial.log](initial.log) 和 [retry.log](retry.log)。
