# B_PENDING、B_BOUND 和许可预算：组件实验

状态：**组件通过，端到端证据不足**。隔离 source/state MySQL 9.5.0 中，使用真实 MySQL 事务和真实 L 表，但 D、BARRIER/ACK、guardian 身份及签名为测试构造；没有 CDC Reader 原始回调、真实 guardian、Actor Journal 或业务 Sink。不能把该结果当作真实批转流或自动恢复保证。

本次验证：B_PENDING 和 B_BOUND 均要求 D 的 ACK 父链引用证明及有界 source 控制表反向校验；B_BOUND 在原 D 时可重放，在前进的 D 缺少 BARRIER 引用时拒绝，有正确引用后为 COVERED；源 B 行缺失、未知 STOP、未知 WAKE 和未绑定 fence 拒绝，符合 hash 和已登记 owner 的 WAKE 接受。源 marker 的存在始终不代表 ACK 或 D 覆盖。source 控制表按主键分页 128 行，总量最多 4096 行、预算 15 秒；ACK 链最多 4096 个父节点及 65536 个引用，超限会暂停，因此未证明 70 万表规模恢复。

grant/permit 组件还验证：相同时间戳许可选择、已绑定未领取 grant 的确认停机保持 UNKNOWN、错误身份拒绝、最多 3 次预算、预算耗尽不释放 grant；明确拒绝事务执行真实 MySQL COMMIT 后，JDBC 测试代理抛出回复丢失，通过 permit+task 精确读回，重复拒绝仅回退预算一次。它不是网络代理丢包实验，也没有连接真实 Journal 拒绝信号。

[结构化结果](result.json)包含合成 D、源控制行、L、grant、许可、预算及拒绝记录，T/J 和原始 ACK 标记为 N/A；[测试日志](test.log)与[源码摘要](source-sha256.txt)提供样本版本。首次 ACK 测试因缺少实现而编译失败，见[红阶段日志](ack-test-red.log)；较早候选的测试结果见[历史日志](prior-candidate.log)。

2026-10-29 22:20 CST，在收紧引用总数和反查截止时间后重跑：`CaptureAckProofSpec` 和 `CdcGrantRecoveryMySqlSpec` 各通过 1 项；[最终结构化结果](final-result.json)、[测试日志](final-test.log)、[运行前摘要](final-source-sha256.txt)和[运行后摘要校验](final-checksum-check.txt)对应同一候选版本，5 个文件均一致。测试外层 shell 收尾因误用 zsh 只读变量 `status` 退出为 1；日志中两项 sbt 测试均显示 success，摘要校验随后独立执行。实验层级和边界不变。

22:44 CST 后续候选重新执行两个套件，各通过 1 项，进程退出码 0，5 个关键文件运行前后摘要一致：[结果](final4-result.json)、[日志](final4-test.log)、[源码摘要](final4-source-sha256.txt)、[摘要校验](final4-checksum-check.txt)。组件断言包括真实 MySQL COMMIT 后由 JDBC 代理模拟 `beginOwner` 回复丢失并读回匹配身份、其他 SNAPSHOT 或多个 UNKNOWN grant 拒绝，以及合成 STREAMING TAIL 状态的 grant 转换。TAIL 的初始化证明在该测试中人为构造，既没有真实 Journal 和目标账本，也没有多任务端到端重放；上述断言见对应测试源码，不应从最终 JSON 中推断额外 T/J 数据。
