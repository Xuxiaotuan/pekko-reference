# 非 UTC JVM 下 DATETIME 回放验证

2026-11-11，使用两个仅绑定本机的隔离 MySQL 8.0.36、真实 Debezium 捕获、JDBC Actor Journal 和 MySQL Sink，运行 `BootstrapStepViewRecoverySpec`。它在 RAW 页保存后注入 SQL 异常、重建同一 owner 的 Runner，并对源与目标逐字段比较；注入不是实际网络断连，也不是 guardian 跨 JVM 接管。

首次实验进入 STREAMING，但最终对账超时：[原始失败日志](initial-failure.log)、[切点与失败材料](P09-view-recovery-089514db0cdf/)保留。实际差异是一条 MySQL `DATETIME(6)`：源端 `2026-10-27 04:05:06.123456`，目标端 `2026-10-26 20:05:06.123456`；完整源与目标读回分别见 [failure-source.tsv](failure-source.tsv)、[failure-target.tsv](failure-target.tsv)。

独立 JDBC 临时表探针在 JVM `Asia/Shanghai`、连接会话 UTC、Connector/J 9.1.0 下复现：`Timestamp.valueOf(LocalDateTime)` 写入早 8 小时，直接绑定 `LocalDateTime` 保持原值。探针源码及输出见 [CdcDateTimeProbe.java](CdcDateTimeProbe.java) 和 [jdbc-probe-result.txt](jdbc-probe-result.txt)。

将无时区时间绑定改为 `LocalDateTime` 后，显式设置 JVM `Asia/Shanghai` 重跑：63 行、378 字段逐字段一致，状态为 STREAMING；本地终止与服务端释放均确认。[重跑日志](retry.log)、[切点和最终数据](P09-view-recovery-d3880314d1ba/)和[部分关键源码散列](source-sha256.txt)已保存。两个专用库事务及测试账号连接归零后停止容器，见 [source-cleanup.tsv](source-cleanup.tsv)、[state-cleanup.tsv](state-cleanup.tsv)。

这是指定时间类型、一次故障及同一 owner 重建的实验；不证明独立 CDC 入口、guardian 自动接管、未知 B 或其他时间类型全部正确。工作区和运行产物均未冻结为提交版本。
