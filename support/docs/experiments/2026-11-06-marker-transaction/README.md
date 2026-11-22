# 同事务 marker 与目录写入：真实 Reader 探针

状态：**实际观察到 Reader 拒绝；不代表恢复协议或 GC 已完成。** 使用两台隔离 MySQL 9.5.0（源 ROW/FULL、独立状态实例）及项目的真实 Debezium Reader。版本、实例身份、探针源码摘要和结束时连接/事务计数见 [environment.json](environment.json)。源码基准提交 `68b0a1d20d8ccdb05fb244c58ba87dfe09d128e7`；探针本身尚未提交。

先建立两张源业务表，启动 Reader，正常订阅第一张表。然后在专用测试 schema 创建目录表及 `AFTER INSERT` 触发器，使控制表每次 marker INSERT 在同一事务插入目录行，再订阅第二张表。探针使用生产 `SharedMySQLCapture` 和真实 Debezium；`CaptureObservedFactory` 只记录原始 SDK 事件，不制造事件或 ACK。

| 固定场景 | 实际 Debezium END | Reader 状态 |
| --- | --- | --- |
| 基线 marker | `event_count=1`，控制表 1 行 | 订阅 `CAPTURE_READY` |
| marker + 目录写入 | 同一事务 `event_count=2`，控制表和目录表各 1 行 | 抛出 `control marker requires its own transaction`，订阅 `B_PENDING` |

原始结构化输出见 [result.json](result.json) 和 [events.jsonl](events.jsonl)，完整测试运行日志见 [runner-attempt2.log](runner-attempt2.log)。触发事务的 GTID、两条行事件及 END 在事件中可关联；结束前捕获到原始回调 16、17、18，最后返回的 ACK 是 15。此观察不证明实际 D 已刷新到哪个位置，也不涉及 Actor Journal、Sink 或重启后端到端恢复。

默认测试配置排除外部集成测试，初次运行 0 项，见 [runner-excluded.log](runner-excluded.log)；第二次试图设置 sbt 测试选项时发生表达式错误，见 [runner-attempt1.log](runner-attempt1.log)；切换项目后显式启用测试，第三次执行 1 项通过。该通过表示探针成功记录实际行为，不表示 Reader 接受目录写入。

本次没有改变生产协议。结束检查两实例未观察到活跃 InnoDB 事务；源实例仍有一条 binlog dump 连接，不能将其表述为完整资源释放成功。跨重启可验证证明、目录协议、ACK/L 证明封存及安全 GC 均未实现或验证。
