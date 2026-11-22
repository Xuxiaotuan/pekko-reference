# Split 迁包与真实 MySQL 快照恢复

2026-11-10，使用未提交工作区编译代码与仅绑定本机的专用 MySQL 8.0.36，运行真实 MySQL Source、JDBC Actor Journal 和 MySQL Sink 的单表静态快照测试。`SNAPSHOT_RECOVERY` 中固定故障窗口、终止测试进程并启动新进程，`BASELINE` 为正常对照；两者最终均为 65 行、6 个字段逐字段相等，没有缺行、多行或字段差异。完整切点、Journal、目标账本及对账见对应实验目录。

首次在同一 sbt 进程顺序运行两次测试时，恢复场景通过，基线在初始化阶段因 `No suitable driver` 失败；证据见 [initial-with-failure.log](initial-with-failure.log) 和 `BASELINE-7ebe564f9ec4`。单独启动 sbt 后基线通过；随后在测试连接辅助方法中显式加载 MySQL 驱动，同一 sbt 进程顺序重跑两个场景均通过，见 [retry-combined.log](retry-combined.log)。

旧 Split 状态及事件样本由迁包前的旧序列化器生成，组件测试覆盖 9 种更新及其嵌套类型名；它们不是历史生产 Journal 样本。本次真实 MySQL 恢复使用新代码产生的 Journal 和 Snapshot 数据，不证明旧二进制能够读取新序列化版本。

本实验是静态快照，D 为 N/A；没有 binlog Reader、LOW/HIGH 动态回填、guardian 接管或跨表调度。测试库连接和事务归零后停止容器，见 [server-and-cleanup.tsv](server-and-cleanup.tsv)。
