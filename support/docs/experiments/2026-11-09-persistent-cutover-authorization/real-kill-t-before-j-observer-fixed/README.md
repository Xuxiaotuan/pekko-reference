# KILL_T_BEFORE_J after observer attribution fix

真实 MySQL、Actor Journal、MySQL Sink 的 `KILL_T_BEFORE_J` 复测：旧 owner 被 guardian 隔离，MySQL observer 在 `performance_schema` 开启后确认 `db_drained`，旧配额被释放，新 owner 获得 permit 并启动执行。源表 10 行、目标表仍为 2 行，worker 日志显示旧持久化用户名被拒绝，任务最终进入 blocked。

源码修正了 `innodb_trx.trx_mysql_thread_id` 为空时的处理：空值现在作为未归属事务保留，直到记录消失；实验环境 `performance_schema=OFF` 时也会明确拒绝观察，不把不可见连接当成排空。

这次证明了 guardian 隔离、事务排空和旧配额迁移已能到达新 owner 的 permit，但运行时 credential binding 还未在真实 worker 配置中启用，所以 Source/Sink 仍使用旧用户名。端到端逐字段一致性仍失败，保留 `failure-before-cleanup.json` 和完整 worker 日志。实验后事务数和非系统客户端数均为 0。
