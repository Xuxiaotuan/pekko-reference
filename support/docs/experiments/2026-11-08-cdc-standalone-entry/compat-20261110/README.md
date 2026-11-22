# 旧 Bootstrap Journal 类名兼容实验

2026-11-10，在仅绑定本机随机端口的专用 MySQL 8.0.36 容器中，使用当前工作区未提交的编译代码和 JDBC Actor Journal 运行了两项测试。实验后查得专用数据库连接数与 InnoDB 事务数均为 0，再停止容器；[查询结果](cleanup-before-stop.tsv)保留在此。

`ManagedBootstrapJournalRecoverySpec` 在新代码下写入并多次停止、重建 Actor，验证恢复许可值和 Journal 序号；其许可校验为注入函数。结果见 [result.json](result.json)。

`LegacyBootstrapJournalMySqlSpec` 将[历史实验的首个真实事件](../../2026-11-05-cdc-flow-refactor/managed/C1-7bc6494fa216/03-recovered.json)复制到隔离库中的新 persistence ID，重放旧 manifest 后追加新版本事件，停止并重建 Actor 并核对状态。结果见 [legacy-event-restart.json](legacy-event-restart.json)，两项测试的事件载荷与 manifest 见 [journal-events.tsv](journal-events.tsv)。

这是 Actor 在同一测试 JVM 内重建的结果，不是 JVM 或机器重启实验；没有接入 guardian、Reader、Source 或 Sink，也没有执行逐字段对账。实验不证明旧程序可以读取新版本写入的事件，也不证明其他历史 Journal 或 Snapshot 类型兼容。工作区未冻结为提交版本。
