# CDC 独立 worker 实时阶段接管（2026-11-11）

这是未提交工作区上的固定窗口实验。使用独立 guardian、supervisor、CDC worker JVM、两个隔离 MySQL 9.5.0、真实 Debezium Reader、Actor Journal 和 MySQL Sink。实验目录中的 `source-sha256.txt` 记录每次运行的生产与测试源码，包含旧实验源码版本；不等于已经提交的 Git 版本。

- `C0-4162bca0b85b`：先完成单表初始化并进入 STREAMING，确认目标全量一致；验证原 worker 身份（PID 和启动时间）后强制结束，由 guardian 持有原始子进程句柄签发隔离证明，supervisor 在排空后启动替代 worker；任务恢复 STREAMING，64 行、384 个字段一致。结果和清理均为 PASS。
- `C0-68f22ce9beff`：重复上述窗口，并在替代 worker 启动后更新源表一行，等待该变化进入真实 Sink，再做全量逐字段比较：源和目标各 64 行、384 个字段一致；T 中有一条增量组，J、L、D、baseline T、隔离证明、配额及会话记录保存在两个快照中。`02-recovered.json` 捕获时状态库存在一条活动事务；不能把该时刻写成全库无事务。最终清理文件报告 guardian 排空确认、会话消失和 clean；后续状态查询另见 `*-final-state.tsv`。
- `legacy-worker-rejected.log`：旧 `RealMySQLSupervisorMainSpec` 尝试启动已从产品移除的 Workflow worker，被当前 CDC 入口规则拒绝，测试失败。该旧测试没有进入接管窗口；没有放宽生产规则使其通过。

这个实验的故障点是单表进入 STREAMING 之后的 worker 强制退出；不覆盖快照中途、事务在途、COMMIT 回复丢失或未知 B 自动恢复。未知 B 的独立 C1 失败及安全暂停证据保留在相邻的 `guardian-capability-extract-20261111` 和 `managed-jdbc-decouple-20261111`。它也不是 70 万物理表容量实验。
