# 专用目录排除捕获的真实 Reader 探针

状态：**指定样本通过；尚不是生产协议。** MySQL 9.5.0、Debezium 3.6.1.Final，独立源/状态实例；代码基准提交和关停检查见 [environment-and-cleanup.json](environment-and-cleanup.json)，测试探针源码摘要见 [probe-final.sha256](probe-final.sha256)。

测试工厂覆盖 Reader 的 `table.include.list`：在保留其余表的同时排除专用 `pekko_marker_directory`。源库控制表的触发器仍在同一事务写入目录。基线订阅和触发器启用后的新增订阅均为 `CAPTURE_READY`，两张业务表分别插入一行后各观察到一个完整组。触发事务的 Debezium END 中只有控制表的一条事件、`event_count=1`；目录 INSERT 不在捕获的原始记录中。[最终结果](result.json)、[原始事件及 ACK](events.jsonl)、[运行日志](runner-attempt2.log)。

首轮仅检查绑定与事务计数，结果保留在 [result-attempt1.json](result-attempt1.json)、[events-attempt1.jsonl](events-attempt1.jsonl) 和 [runner-attempt1.log](runner-attempt1.log)；其后补充两张业务表的捕获断言，使用全新实验实例复跑。停止实验实例前未观察到用户连接或 InnoDB 事务，实验租约已释放。

对照未过滤目录的[旧探针](../2026-11-06-marker-transaction/README.md)，这里支持“在这一配置和时序下排除目录可避免 `event_count=2`”的结论。**只在测试工厂修改配置，生产 Reader 未修改**。尚未验证实际 D、Journal、Sink、恢复、所有控制操作、过滤配置变更兼容性、用户表与目录同名冲突、源表被直接修改/绕过触发器，以及长期运行。过滤掉目录变更不等于目录完整性已得到证明。
