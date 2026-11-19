# 共享 Debezium 配置解耦验证（2026-11-11）

未提交工作区对应源码见 `source-sha256.txt`。共享 Reader 的 Debezium 配置、状态表命名和 offset 编码由独立 helper 提供；旧节点保留兼容转发。

`unit-final.log` 中旧 Source 节点 33 项测试通过。最终源码还使用 `e2e-final.log` 和 `reconciliation-final.json` 重跑，同样 63 行、6 字段全等；前一次运行结果也保留。`e2e.log` 和 `P09-view-recovery-fa9688864b06` 保存两个本机隔离 MySQL 9.5.0、Debezium 3.6.1、真实 JDBC Actor Journal 与 MySQL Sink 的同 owner 恢复实验：在 RAW 页保存后的观察回调注入 SQL 异常，重建同一 owner 的 Runner，最终进入 STREAMING。`reconciliation.json` 基于 `final.json` 全量源和目标记录比较，63 行、每行 6 字段一致。

这是回调注入异常，并非真实网络断连，也不是独立 guardian 的跨 JVM 接管；没有验证未知 B 自动恢复、其他故障窗口或生产部署。实验前后数据库实例、活动事务与连接记录见 `environment.json` 和两个 `*-final-state.tsv`。实例均已关闭。
