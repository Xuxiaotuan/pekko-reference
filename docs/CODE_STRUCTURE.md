# 源码导航与职责边界

本项目以 CDC 复制为主，保留必要的线性工作流和调度。生产代码在 `pekko-server/src/main`，前端在 `xxt-ui/src`。不以重新划分 Maven/SBT 模块或改变持久化包名来整理目录。

## 从入口追到一次执行

`PekkoServer → PekkoClusterService → PekkoGuardian → WorkflowSharding → EventSourcedWorkflowActor → WorkflowExecutionEngine`。

以下路径相对于 `pekko-server/src/main/scala/cn/xuyinyin/magic`：

| 位置 | 职责 | 读源码时注意 |
|---|---|---|
| `api/http/routes/` | HTTP 契约和请求转发 | 列表返回摘要；202 表示接受，不表示执行完成 |
| `workflow/actors/`、`sharding/` | 工作流状态、执行归属和持久化确认 | 不在 Actor handler 内执行阻塞 JDBC |
| `workflow/events/`、`model/`、`checkpoint/` | 持久化模型、DSL 和进度类型 | 包名、序列化结构和 persistenceId 属于兼容边界 |
| `workflow/engine/WorkflowValidator.scala` | 校验连通的 `Source → Transform* → Sink` | 不是通用 DAG 执行器 |
| `workflow/engine/registry/NodeRegistry.scala` | 不可变实例目录和稳定的节点适配器 | Engine、Actor 能力判断和 Validator 使用同一目录 |
| `workflow/engine/WorkflowPlanCompiler.scala`、`ExecutionPlan.scala` | 从 DSL 生成不带画布与历史的内存运行计划，绑定当前目录的连接器实例 | 不获取密码、不启动资源；计划不写入 Journal |
| `workflow/engine/WorkflowExecutionEngine.scala` | 能力选择、运行图、可靠批次执行、结果报告 | 可靠批次仍是目标 T 提交 → J 确认 → Source ACK |
| `workflow/engine/ReliableExecutionSessions.scala` | 同一 Engine 内的会话登记、替代等待和有界取消观测 | 超时不等于释放，不是跨进程 fencing |
| `workflow/engine/ReliableExecutionControl.scala` | 单次执行的接纳、取消、排空和释放确认 | 已接纳批次完成后才关闭资源；不负责提交协议 |
| `workflow/nodes/base/BatchSourceSession.scala` | Source 流及关闭句柄、读取与关闭衔接 | 运行期资源不进入 Checkpoint |
| `workflow/nodes/base/SourceResourceScope.scala` | 异步获取前登记所有权、迟到资源关闭、关闭失败报告 | 关闭失败不冒充已释放 |
| `workflow/nodes/sources/` | MySQL/Kafka 读取、Debezium 回调、Bridge 和完整组装配 | 原始回调、完整组和交付批次是不同边界 |
| `workflow/nodes/cdc/` | 事件身份、信封、完整组及 cursor 表示 | 身份不依赖运行期批次大小 |
| `workflow/nodes/sinks/` | 目标写入、事务与目标账本 | 目标提交成功不能单独推出端到端恢复成功 |
| `workflow/query/` | 有界列表查询、摘要与历史查询 | 列表和未完成 ask 均有额度；仍扫描 ID 并可能唤醒实体，不是独立任务目录 |
| `workflow/scheduler/` | 持久化调度、触发去重和暂停/恢复 | 暂停调度不等于取消运行会话 |

## 前端与示例

`xxt-ui/src/api` 对接真实 HTTP 契约，`types` 定义 DTO，`pages` 对应当前产品路由，`components` 放共用 UI。产品支持分页列表、完整 DSL 创建、执行请求、近期历史和已有计划管理；入口以 `App.tsx` 为准。未路由的 SQL/监控历史文件不代表可用能力。

旧工作流 Actor、模拟 CDC 和 CRDT/GC/Stream 示例归档在 Test 源集；入口见[示例说明](../pekko-server/src/test/examples/README.md)。原 DAG 编辑器保存在 `xxt-ui/examples`。它们不是生产 CDC 主链路。

## 测试与实验材料

- `src/test/scala/.../workflow/engine`：执行、取消、排空、关闭失败及资源所有权固定窗口测试。
- `src/test/scala/.../workflow/nodes`：Source、Bridge、组装配和 Sink 的局部契约。
- `src/test/scala/.../workflow/integration`：恢复进程、故障代理和真实数据库实验；真实 MySQL 测试需要显式启用，默认 `test` 不执行。
- `docs/experiments`：各次实验的源码清单、原始材料、校验器和边界。旧记录描述当时源码，不随本次目录整理改写。

最近的实现记录：[A：示例与构建收敛](experiments/2026-10-19-stage-a-convergence/README.md)、[B：接口与管理界面](experiments/2026-10-19-stage-b-console/README.md)、[C：会话与资源释放](experiments/2026-10-19-stage-c-sessions/README.md)。

结构整理当时的 C2 COMMIT 回复丢失失败终态保存在[结构整理记录](experiments/2026-10-20-code-structure/README.md)及后续旧实验中；当前完整组路径已增加下述有界恢复，历史失败证据不改写。DDL、多 owner、共享 Reader 和 70 万表容量仍未验证。


写入热路径在 `nodes/sinks/JdbcStatementScope.scala` 内管理一次事务的语句；
`MySQLCdcApplySinkNode` 负责完整组、目标事务和账本，语句复用不决定事件身份或 ACK。
`WorkflowQueryService` 对列表及未结束 ask 分别计数，查询失败不能提前清空 ask 额度。
Protobuf 兼容调整及原有 Calcite 中止的最新回归见[本轮优化记录](experiments/2026-10-20-hot-path-query/README.md)，
各次历史实验仍以其原始源码和环境为准。

## 定义与运行计划

旧 HTTP/Workflow DSL 和 Actor 的 JSON 定义继续保留。执行入口先调用 `WorkflowPlanCompiler`，
复用现有拓扑与 CDC 限制校验，生成三类内存计划：

- `MySqlCdcPlan`：当前适配器支持 Checkpoint 的 MySQL CDC 直连路径，不进入通用 Transform 构建。
- `CheckpointedLinearPlan`：支持 Checkpoint 的辅助线性任务，保留原有 Transform 语义。
- `RowStreamPlan`：普通行流；Source 无 Checkpoint 能力时，即使 Sink 支持 Checkpoint，也保持原行流行为。

`ExecutionTask` 仅保存任务 id/name，`ExecutionNode` 仅保存节点身份、角色、类型、标签和配置；
位置、样式、边及 metadata 历史不传入执行内核。旧连接器仍接收 `WorkflowDSL.Node`，
由 `connectorNode` 在调用边界生成零坐标、无样式的兼容参数。配置解析仍由原连接器负责，
这一步没有将全部连接器改成强类型配置接口。
已有 `SourceExecutor`/`SinkExecutor` 类保留为旧调用入口；生产 Engine 直接使用计划绑定的实例，
不再经过这些入口二次查询目录。本轮没有移除既有公开类。

Actor 的可恢复判断委托 Engine 使用同一编译规则；旧 execute 重载保留以兼容现有调用和测试替身。
恢复时仍按当前注册能力重新编译，不能从可靠路径降级成普通行流。
这里的“可恢复”沿用已有 Checkpoint 能力判断，不表示提交结果不确定等所有故障均能自动恢复。

运行计划本身的实现及原始回归见[运行计划边界记录](experiments/2026-10-20-execution-plan-boundary/README.md)。Actor 中的 Kafka 跨运行进度规则、独立查询投影和 Reader 资源预算仍属于后续设计。

## 完整组 CDC 的 C2 恢复

Sink 分类提交结果，不自行循环重试；Engine 保持 T → J → Source ACK，并在替代 Reader 前确认实际本地资源释放。Actor 持久化等待、尝试启动和暂停状态，同一 execution 最多自动恢复三次，次数不因 Checkpoint 增长或进程重启清零。物理读取从实际 D 恢复，J 延续交付序号，T 的完整组账本仲裁重放；不能把 J 或 T 当作 D。

Checkpoint 明确拒绝保留为 `protocol_conflict`，不新增恢复尝试，保存 execution/revision/边界/J 和故障描述后暂停；回复超时等结果不确定继续使用已有恢复预算。关闭无法确认、预算耗尽和证据冲突同样保守暂停。暂停不保证数据已经一致，尚无人工审核后的继续/放弃 API。

源码结论和真实实验分开记录：[C2 有界恢复](experiments/2026-10-20-c2-recovery/README.md)、[明确拒绝修复](experiments/2026-10-21-checkpoint-rejection/README.md)。正式 26 项真实 MySQL 断言中，10 项自动收敛、7 项安全暂停、7 个历史窗口收敛、2 项释放通过；不能写成 26 项自动恢复成功。新增 Journal 事件不保证旧二进制直接降级，历史已终态失败也不会自动复活。

整体提交前复核见[恢复链路审查](experiments/2026-10-21-c2-final-review/README.md)。
