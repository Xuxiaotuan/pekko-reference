# CDC 受管 owner 绑定：组件实验

状态：**evidence_incomplete**。本次只验证 CDC owner attempt 与受管身份关联的持久化组件，不是未知 B 自动恢复实验。

新增的绑定在 `CdcTaskStore.beginOwner` 获取 owner 的同一状态库事务中写入。受管入口从 `SnapshotOwnerJdbc` 上下文读取进程身份，通过现有 `SnapshotSupervisorStore.verifyWorker` 校验票据，再将 guardian 身份和 worker 身份交给 CDC runtime。CDC attempt 仍随机生成，避免同进程的两个 runtime 共用 attempt。未知 B、Journal PAUSED 和 UNKNOWN grant 的恢复闸门没有放开。

| 验证 | 实际结果 | 边界 |
|---|---|---|
| 首次新增测试 | 编译失败：`managedAttempt` 参数不存在；随后因该方案会复用 worker boot ID 而改为独立 attempt 加持久绑定 | 测试驱动的初始失败，不是自动恢复故障样本 |
| 本地 H2，三个相关 suite | 10 项通过 | 组件测试，不包含真实 guardian 或 MySQL |
| 项目默认后端测试 | 624 项通过，143 个 suite 完成 | 排除 ExternalIntegration 和 Benchmark，不证明真实 CDC 故障恢复 |
| 独立真实 MySQL 状态库 | 1 项通过；读取记录显示 attempt 与测试 worker boot 不同，owner 正常关闭后为 STOPPED，活动 grant 为 0 | guardian、PID 和启动票据为测试数据，没有真实 guardian、Actor Journal、Sink 或 CDC 事件 |

[初始编译失败摘要](initial-red.txt)、[MySQL 执行日志](mysql-test.log)、[默认测试摘要](default-tests-summary.txt)、[状态表读回](mysql-state.tsv)、[MySQL 实例信息](mysql-environment.tsv)和[源码摘要](source-sha256.txt)均属于本次组件证据。真实 MySQL 9.5.0 使用本机单独初始化的 source 和 state 数据目录，state 在端口 23372；旧实验的真实提交、回滚和迟提交材料仍保留在 `2026-10-29-b-release-proof`。

未验证：实际受管 CDC 启动票据到此绑定的端到端链路、所有数据库账号和连接的完整清单、旧事务排空、UNKNOWN grant 回收、Journal 重启、未知 B 判定及提交/回滚/迟提交/重启后的逐字段对账。此组件证据不能证明自动恢复完成。
