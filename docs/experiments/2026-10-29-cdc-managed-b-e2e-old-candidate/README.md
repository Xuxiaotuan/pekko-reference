# 受管 CDC 未知 B 旧候选：迟提交样本

状态 **evidence_incomplete**。此实验采用真实 MySQL、独立 guardian JVM、受管测试 worker、共享 Reader、Pekko JDBC Journal 和 MySQL Sink。测试 worker 显式接入尚未启用的候选恢复实现；这不是已启用的生产入口，也不是最终源码版本。

切点固定真实源 marker 事务在途，`01-unknown.json` 显示 B_PENDING、UNKNOWN grant 和 1 条真实 InnoDB 事务；`02-isolated.json` 保留 guardian 隔离旧 worker 的票据、签名证明及仍未排空的源事务。迟提交后旧 Binlog Dump 连接迟迟未退出，实验人员对隔离源表 `a` 的 id=1 执行 `revision+1`，触发一个新的 binlog 事件，旧连接才退出；此人为干预和后续清理操作见 [manual-intervention.txt](late-commit/manual-intervention.txt)。

恢复后任务进入 STREAMING，`03-recovered.json` 记录源/目标各 64 行、384 字段逐项相等，T 包含 1 条增量组、基线收据 9 条，J 和实际 D/L 原始记录均保留。但测试随后未确认后继 worker 的 db_drained：guardian 已退出、worker 已退出，后继 ticket 留在 launched/drain_wait，且需要对同一隔离 schema 的非对账表追加更新以使旧 binlog 会话退出。`result.json` 的 PASS 仅代表测试当时的数据断言通过，不能升级为无人工干预自动恢复和资源释放成功；`cleanup.json` 当时未检查 db_drained，本说明以手工复查为准。

代码版本见 [source.sha256](source.sha256)，文件摘要在启动测试后、其他代码修改前记录，不能单独证明类文件与源码字节逐项对应。原始 worker 事件按两个 boot 保存，`test.log` 是 ScalaTest 输出。仍须在最终候选上不作人工干预重跑迟提交、回滚、正常提交响应丢失、重启和 guardian 失联窗口，并核对全部资源与逐字段结果。
