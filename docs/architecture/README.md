# 独立 CDC Runtime 的模块边界

目标是单 MySQL 实例按需订阅：已有表持续同步，新增表先全量再追实时，取消表不停止其他表。70 万是源目录背景，不是已经验收的活跃复制规模。

| 生产模块 | 职责 |
|---|---|
| `cdc/runtime` | 独立启动、HTTP 及有界关闭 |
| `cdc/platform` | 持久任务索引、资源预算、动态订阅和步骤调度 |
| `connectors/mysql/source/capture` | 共享 Reader、Schema、B/K、持久日志 L、原始 ACK 与 D |
| `cdc/bootstrap` | LOW/HIGH 分页、回填封存、B→F 追赶、激活和持续应用 |
| `cdc/guardian` | 进程身份、控制会话与隔离/排空观察 |
| `cdc/control` | 受管 owner、切换授权、事务锁、配额及 supervisor 状态 |
| `cdc/compat` | 历史持久化模型、manifest 迁移和旧启动命令转发 |
| `src/test/.../workflow` | 旧执行器、Actor、查询、Cron 与历史实验；不进入生产包 |

生产入口不启动通用 Workflow、Cron 或前端。部分配置键、SQL 表名与历史模型仍带 workflow 字样；它们是兼容接口，不代表仍有 Workflow 执行服务。

持久意图经预算准入后启动共享捕获；新增订阅准备 Schema、绑定 B，再依次执行 LOW→RR 分页→HIGH→窗口回填→目标 T/Journal J，从 B 完整追到 F 后激活，继续处理 L 的完整组。采集侧原始回调按持久 L 前缀确认，SDK 的实际 D 与消费者 T/J 分别取证。

受管 worker 接管必须核实进程身份、隔离证明和旧连接/事务排空；未确认释放时保留配额。未知 B 仍安全暂停，不能伪造捕获就绪。L 当前只具备归档和保留位置投影，没有生产安全删除路径。

本轮证据包括干净编译、历史序列化样本及 Journal 字节读取、真实 MySQL 固定 C0 接管和 64 行/384 字段对账；不证明 guardian 自身重启、未知 B 自动恢复、全历史数据库升级或 70 万真实表容量。

[当前实验与失败边界](../experiments/2026-11-08-cdc-standalone-entry/retire-workflow-runtime-20261111/README.md) · [五篇文章与验证汇总](../articles/cdc-design/README.md)。旧架构描述见[历史文档](../archive/pre-standalone-architecture.md)。
