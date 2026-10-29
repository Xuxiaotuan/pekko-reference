# 实际 D 上的 B 候选检查：组件验证

状态：**evidence_incomplete**。使用既有真实 CDC 七表回归产生并保留在 MySQL 中的完成态 D 和 L ACK，在同一状态库为尚未订阅的 `source_rows` 追加 B_INTENT。源库上固定一个真实 InnoDB 控制表事务：在途事务可见、但其他会话看不到 marker；回滚后 marker 仍不存在；随后另一事务提交相同 marker，候选检查读取到它。见[结构化结果](result.json)、[测试日志](test.log)及[源码摘要](source-sha256.txt)。

候选检查在旧事务仍在途时也报告控制行不存在。这是该组件的有意边界：行不存在并不证明回滚；只有旧执行者隔离和服务端事务排空后才可以使用候选检查。此实验没有真实 guardian 隔离、恢复许可消费、Actor Journal 状态恢复或 Sink 对账，不能证明自动恢复。提交样本是回滚后另起的事务，不是此前在途事务的迟提交样本。
