# 开启 Performance Schema 的迟提交回归

状态：**既有恢复协议的单场景通过；不是源版本证明接入验收。** 用全新的独立 MySQL 9.5.0 实例，源和状态库的 `performance_schema` 均开启，运行 `CdcManagedUnknownBSpec` 的 `LATE_COMMIT`。代码基准、实例 UUID、资源清理见 [cleanup-lab.json](cleanup-lab.json)，具体源码摘要与受管进程标识保存在样本目录。

真实 guardian 隔离后，旧事务迟提交，排空完成并启动新 worker；任务恢复至 STREAMING。源和目标 64 行、384 字段相等，缺失、额外及字段差异均为空，见样本 `01-unknown.json`、`02-isolated.json`、`03-recovered.json`。测试 `result.json` 为 PASS；`cleanup.json` 记录 guardian 排空确认、会话离开。测试 worker 保存了原始回调和 ACK 日志。

此前 [performance_schema 关闭的尝试](../2026-11-06-version-guardian-regression/README.md)超时并保留失败证据。本次两个实例停止前均无用户连接和活跃 InnoDB 事务。

本例使用测试 worker 显式接线，且没有把源版本检查、锁或版本绑定许可接入生产代码。单次迟提交对账不能证明跨重启版本证明、其他生产入口、ACK/L 证明封存或 GC。
