# 受管未知 B 的迟提交回归

状态：**失败；源版本尚未接入生产恢复，不能将本回归视为版本许可验证。** 运行现有 `CdcManagedUnknownBSpec` 的 `LATE_COMMIT` 单场景，使用独立 guardian JVM、测试 worker、真实 MySQL、Actor Journal 和目标表。测试入口及源码摘要见样本的 `environment.json` 和 `source-sha256.txt`。

`01-unknown.json` 和 `02-isolated.json` 记录隔离前后的 T/J/D、L、grant、事务、会话和 guardian 证据。旧 source 事务被代理持有时，owner 未进入 `db_drained`，grant 保持 `UNKNOWN`；代理随后记录 `PROXY_LATE_COMMIT_OK`。测试等待旧 owner 排空和替代 worker 启动时超时，见样本 `failure.json`、`failure-state.json` 及 [运行日志](runner.log)，未产生成功的恢复后全量逐字段对账。

隔离阶段的观察结果将状态库两条 `cdc_lab` 空闲连接标记为未归属；测试超时时状态仍为 `drain_wait`、grant 仍为 `UNKNOWN`。这支持本次运行未越过排空门槛的结论；仅凭这次观测不能确定这些连接的来源或证明它们是唯一原因。测试自行清理报告 `clean=false`、`guardianDrainConfirmed=false`、`sessionsGone=true`。之后单独确认实验两库无用户连接及活跃事务，并停止两台专用实例，见 [cleanup-lab.json](cleanup-lab.json)。实验账号、数据库和失败材料未删除。

此次回归与版本行锁探针是两个独立实验；没有把两者结果合并成版本证明、guardian 与生产许可已打通的证据。

本实验槽配置为 `performance-schema=OFF`，观察器无法从 `performance_schema.session_connect_attrs` 获得连接 owner 属性。随后在全新实例开启该功能重跑，结果见[对照实验](../2026-11-06-version-guardian-pfs-regression/README.md)；对照支持环境配置是重要差异，但不消除其他可能差异。
