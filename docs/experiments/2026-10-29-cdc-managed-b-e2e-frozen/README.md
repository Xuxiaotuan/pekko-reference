# 受管未知 B 恢复：六个固定窗口

状态：**四个指定窗口数据收敛，两项 guardian 负例安全暂停**。同一候选源码在独立真实 guardian JVM、受管测试 worker、真实 MySQL 9.5.0 源库与状态库、Pekko JDBC Actor Journal、MySQL Sink 上逐项执行；协调器在测试 JVM 内，worker 显式接入候选实现。本实验不证明 `SnapshotSupervisorMain`、`PekkoServer` 和 `CdcPlatformExtension` 完整生产入口的接线。

| 窗口 | 实测结果 | 对账和恢复许可 |
| --- | --- | --- |
| [C0](C0-71b83dc9c2b5/) | COMMIT 未转发后接管并进入 STREAMING | 64 行、384 字段相等；marker 不存在，permit 已消费 |
| [C1](C1-5f58c5eb636a/) | COMMIT 成功、回复丢失后接管并进入 STREAMING | 64 行、384 字段相等；marker 存在，permit 已消费 |
| [LATE_COMMIT](LATE_COMMIT-490725ef7927/) | 旧事务在隔离后迟提交，排空后接管并进入 STREAMING | 64 行、384 字段相等；marker 存在，permit 已消费 |
| [LATE_ROLLBACK](LATE_ROLLBACK-71d038ef3637/) | 旧事务在隔离后迟回滚，排空后接管并进入 STREAMING | 64 行、384 字段相等；marker 不存在，permit 已消费 |
| [GUARDIAN_LOST](GUARDIAN_LOST-862f69b20cbd/) | 后继 fork、恢复前 guardian 进程失联，安全暂停 | owner/grant UNKNOWN，未发 permit；源 64 行、目标 0 行，未初始化完成 |
| [GUARDIAN_UNREACHABLE](GUARDIAN_UNREACHABLE-b13d870f1ec9/) | 后继 fork、恢复前 guardian socket 不可达，安全暂停 | owner/grant UNKNOWN，未发 permit；源 64 行、目标 0 行，未初始化完成 |

收敛样本的 `03-recovered.json` 保存源与目标、逐字段差异、B/F、实际 D、L、T、Journal 原始事件、grant 与许可，worker 的 `*-events.jsonl` 保留原始回调和 ACK 次序；`01-unknown.json` 与 `02-isolated.json` 保存故障切点、事务、进程隔离证明与排空前状态。迟事务被代理按切点释放，不需要人工修改业务表；恢复过程的控制表 WAKE 仍属于系统写入。

清理尾态必须单独看 `final-database-state.tsv`：四个收敛样本的 snapshot owner 为 db_drained、ticket retired、grant RELEASED，但清理由 guardian 强制停止后继 worker，CDC owner 仍 ACTIVE，不能称为 CDC 正常停机。GUARDIAN_LOST 样本的后继 snapshot owner 为 active、ticket launched，未取得其隔离及排空证明；虽然测试结束后进程与专用账号连接消失，不能推断资源已被安全回收。GUARDIAN_UNREACHABLE 清理时恢复 socket 并确认 snapshot owner db_drained、ticket retired，CDC owner/grant 仍 UNKNOWN。`result.json` 中 PASS 是 fixture 的预期断言，不表示六项全部收敛或证明所有清理通过。

每个样本的 `source-sha256.txt` 在测试启动时写入，矩阵结束后复算与当前文件逐项一致；这不单独证明编译类字节与源码一一对应。[runtime-frozen.log](runtime-frozen.log) 是同一候选的 Java 17 runtime 单测；[runtime-final-corrected.log](runtime-final-corrected.log) 保留修复前 9/10 的失败，[runtime-final.log](runtime-final.log) 是首次错误的 sbt 命令。未覆盖 guardian 自身重启、跨主机、长时间容量、DDL、GC、所有并发时序或生产部署。

默认回归首次运行 642/643，唯一失败为关闭后立即断言线程不存活；线程池终止与线程退出存在时序窗口。保留 [首次日志](default-tests-first.log)；对同一线程增加 2 秒有界 join 后仍检查其已退出，重跑 [日志](default-tests-retry.log) 为 643/643。此回归未启用 ExternalIntegration 或 Benchmark 标签，不替代真实 MySQL 矩阵。
