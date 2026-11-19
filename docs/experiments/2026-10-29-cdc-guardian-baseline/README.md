# 真实 guardian、MySQL 和目标表基线

本次是已有受管**静态快照**回归，并非共享 CDC 未知 B 恢复。使用独立 MySQL 9.5.0 状态实例、生产 `SnapshotGuardianMain`、`SnapshotSupervisorMain` 和受管 `PekkoServer` worker，运行一个静态快照，终止并重启 supervisor，检查旧 worker 退役、后继 worker 启动和目标行。

执行的 `RealMySQLSupervisorMainSpec` 1 项通过，最终源与目标 10 行、6 字段逐字段相等。进程、隔离和元数据、T/J 及逐字段结果见 [final.json](final.json)，故障前状态见 [before-restart.json](before-restart.json)，测试结果见 [result.json](result.json) 和 [test.log](test.log)。测试中 D 为 N/A；没有共享 binlog Reader、未知 B、CDC UNKNOWN grant 或迟提交事务，本结果不能用于证明这些路径已恢复。
