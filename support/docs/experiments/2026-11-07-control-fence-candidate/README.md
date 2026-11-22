# 源侧 fence 候选验证

本目录记录隔离 MySQL 上的原语验证，并非 guardian、Journal、Sink 的端到端结论。实验只使用专用实例与临时库；源端与状态库交接时事务和其他连接均为 0，见 `handoff-db-status.txt`。

`trigger-attempt6.log` 使用 Java 17 和真实 MySQL，2 项中 1 项通过、1 项失败。失败原因是受限 writer 虽有 fence 表 SELECT，却无权执行 `SELECT ... FOR UPDATE`。`trigger-attempt6.sha256` 记录当次源码和测试内容。之前 attempt 3 使用了错误的 sbt 项目名；attempt 4 虽然命令成功，但默认标签过滤导致真实 MySQL 测试没有执行；attempt 5 因构建冲突中止。这些日志均保留，不计入通过。

`fenced-matching-writer-counterexample.txt` 使用仅对控制表有 DML、对库有 SELECT 的账号，在 FENCED 时写入完全匹配的 marker，实际成功，version 由 1 变 2，marker 数由 0 变 1。这证明旧权限模型不能把早先的 marker 缺失判断直接当成跨库许可的持久证明。

`definer-identity-probe.txt` 展示触发器中的 `CURRENT_USER()` 是 definer；`definer-open-procedure-probe.txt` 展示另一组简单权限探针：reader 只获过程 EXECUTE 时，可在 OPEN 调用过程，直接 INSERT 和 FENCED 时调用过程均被拒，独立 controller 可直接写入。这只是权限原语实验，未证明过程定义和授权的长期完整性、controller 的 guardian 身份和排空、许可消费、重启或端到端一致性。

更新候选后，`procedure-attempt1.log` 实际执行源侧 grants 和控制表原语 2 项、通过 2 项，对应 `procedure-attempt1.sha256`。此版本采用 OPEN 专用 definer 过程、受限 writer 无直接 DML；测试不含真实 Reader、guardian、Journal、Sink 或故障恢复。独立 controller 尚未接入，FENCED 写入入口主动拒绝。测试后再交接时两台数据库的连接与事务数见 `handoff2-db-status.txt`。

`reader-controller-attempt1.log` 包含真实 MySQL、Debezium 和 Reader 的 3 项测试，3 项通过。测试在私有库以 CALL 过程写 marker，同时过滤 fence 表；观察到 END.event_count 为 1，Reader 到达 CAPTURE_READY，原始 ACK 与实际 D 前后值见 `reader-attempt1/`，重复 CALL 前后版本均为 1。B_PENDING 由测试设施手工登记，不证明生产许可、Journal、Sink 或端到端恢复。Reader 引擎关闭后仍有本探针的 Binlog Dump 会话；定位 MySQL cid 和 TCP 端口后，仅清理该会话，见 `reader-close-residual.txt`，不将本地 close 等同于服务端连接释放。

`controller-unknown-attempt1.log` 实际执行 3 项、通过 3 项：真实 MySQL 先返回 COMMIT OK，测试代理丢弃回复，controller 的调用失败后用新连接在锁内核对出 FENCED、version=1 及完整 marker；证据为 `controller-unknown-attempt1/`。作用域 proof 在回调结束后失效。该实验只是 JDBC 重连与源侧原语，不是 JVM 重启，也不证明 guardian、Journal、Sink 或许可消费。因并行状态侧代码有修改，整体最终源码仍需冻结后重新构建和回归。交接时两实例均无事务和其他连接，见 `handoff5-db-status.txt`。

在改为同时校验 observer、writer、controller 三个真实连接身份后，`controller-unknown-attempt2.log` 的同一组 3 项再次通过；`controller-unknown-attempt2-before.sha256` 中所列 5 个源码和测试文件在运行前后核对一致。另一 sbt 进程曾短暂同时启动，最终构建仍需在源码冻结后串行复验。测试后数据库排空结果见 `handoff6-db-status.txt`。

`release-attempt1.log` 执行 4 项，其中 3 项通过、1 项失败。源侧真实代理实验中，marker 和 release 各自的 COMMIT OK 均被丢弃；后续 OPEN 写入令 version 从 1 增到 2，先前匹配 permit 与 state digest 的 release 行仍可读回。Reader 测试则发现，即使 row 过滤排除 fence/release 表，Debezium 仍会发送这两张表的 SchemaChangeValue，原断言将其视作数据行而失败；实际 Reader 原代码会把内部 schema 写入状态。候选修复跳过内部 schema，但尚需真实 Reader 重跑。该次 Reader 关闭后同样遗留 Binlog Dump 会话，定向清理和第二次双库排空证据分别见 `release-reader-close-residual.txt` 和 `handoff7-db-status.txt`。

后续 `open-v2-attempt3.log` 的真实 MySQL 证据覆盖 v2 OPEN 的 `reader.subscribe`、FENCED 下新 Reader 拒绝、内部 row 过滤和后续业务读取；与更早手工登记 B_PENDING 的实验区别见该日志与 `open-v2-cleanup3/`。fixture-only 对固定 cid 的清理不构成生产自动清理能力。

该目录中的恢复协议仍在实现和验证：普通 OPEN v2 Reader 可以在验证源保护后启动，未知 B 的 guardian/Journal/Sink 完整恢复、最终 runner 放行和安全 GC 不能从上述局部实验推断为完成。
