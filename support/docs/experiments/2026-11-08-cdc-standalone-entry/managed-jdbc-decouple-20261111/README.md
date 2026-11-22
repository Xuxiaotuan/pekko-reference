# 受管 CDC JDBC 适配与 C1 边界（2026-11-11）

C1 对应未提交源码指纹见 `source-sha256.txt`，并另保存完整生产/测试源码指纹；随后将旧 JDBC 驱动转发到 CDC 实现，最终源码指纹见 `source-unified-final-sha256.txt`。CDC 受管直连已使用 `ManagedCdcJdbc`，旧受管入口安装同一 owner 的登记适配；旧控制存储、guardian capability 和启动仍未解耦。

`connection-test.log` 与 `connection-result.json`：两个隔离 MySQL 9.5.0 验证旧连接和新连接在返回前登记到同一 owner，源/状态库账号进入 manifest；owner 撤销后新旧入口都拒绝连接。没有在该测试中运行 guardian 或 Sink。转发改动后的最终版本重跑见 `connection-unified-final.log` 和 `connection-unified-final.json`，对应组件测试 44 项通过；C1 没有在这一最终版本上重跑。

`c1-failed.log` 和 `C1-819f0a71dc06/`：独立 guardian、supervisor、CDC worker、双 MySQL 和目标表的未知 B C1 测试在等待 STREAMING 时超时。固定窗口保留了 UNKNOWN grant、旧 worker 隔离证明、旧 owner 排空及 J/L/D；failure-state 中任务 PAUSED、UNKNOWN grant 保留，源 64 行而目标 0 行，逐字段对账不一致。测试清理记录 `clean=true`、`guardianDrainConfirmed=true`、`sessionsGone=true`；测试后两库活动事务数均为 0，实例随后关闭。

独立入口的 `allowPendingB=false` 是现有限制，因此不能把该失败表述为已追平，或把旧 worker 隔离单独提升为端到端接管成功。未知 B 继续安全暂停；本证据不证明其他模式的故障恢复。
