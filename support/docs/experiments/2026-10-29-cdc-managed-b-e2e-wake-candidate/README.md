# 未知 B 迟提交：WAKE 候选端到端样本

状态：**partial**。一个无人工业务写入的真实迟提交样本通过数据及测试资源释放断言，但这不是完整故障矩阵，也不是生产默认入口：测试 worker 显式启用候选恢复代码，使用独立 guardian JVM、真实 MySQL Source/状态库、共享 Reader、Pekko JDBC Journal 与 MySQL Sink。

测试在 B marker 的真实 InnoDB 事务持有时断开 worker 侧提交句柄，记录到 B_PENDING、UNKNOWN grant、旧 owner UNKNOWN 和持有中的服务端事务；旧 worker 被 guardian 强制隔离且事务尚未结束时，接管保持 drain_wait。释放代理后原事务迟提交；supervisor 向源专用控制表写入 WAKE 以促使旧 Binlog Dump 感知断连，完成旧进程和数据库排空，后继 worker 通过持久化许可恢复并进入 STREAMING。该样本未人工写业务表。

[恢复后证据](LATE_COMMIT-796da5f9b32c/03-recovered.json)显示源与目标各 64 行、384 字段相等，missing、extra 和 fieldDifferences 均为空；包含 B、F、D、L、T、J、许可及签名证明。故障切点和进程隔离分别见 [01-unknown](LATE_COMMIT-796da5f9b32c/01-unknown.json)、[02-isolated](LATE_COMMIT-796da5f9b32c/02-isolated.json)，两个 worker 的事件日志保留原始 ACK 顺序。最终测试清理确认两个 snapshot owner 均 `db_drained`、对应票据均 `retired`，专属 source/sdk 账号会话消失、grant 全部 RELEASED；但清理时强制终止后继 worker，CDC owner 数据库行仍为 ACTIVE，不能称为 CDC 正常停机。

[源码摘要](LATE_COMMIT-796da5f9b32c/source-sha256.txt)在运行开始后生成，结束时对当时工作区逐项校验匹配；测试输出见 [test.log](test.log)，[cleanup.json](LATE_COMMIT-796da5f9b32c/cleanup.json)仅证明上述隔离、排空和账号会话检查。测试时实际 WAKE 和迟提交结果保留在事件与控制表记录。该样本之后源码仍需继续修改，不能代表后续版本已验证；提交响应丢失、回滚、迟回滚、guardian 失联及重启窗口尚需同一最终版本验证。
