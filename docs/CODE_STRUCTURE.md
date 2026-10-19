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
| `workflow/engine/WorkflowExecutionEngine.scala` | 能力选择、运行图、可靠批次执行、结果报告 | 可靠批次仍是目标 T 提交 → J 确认 → Source ACK |
| `workflow/engine/ReliableExecutionSessions.scala` | 同一 Engine 内的会话登记、替代等待和有界取消观测 | 超时不等于释放，不是跨进程 fencing |
| `workflow/engine/ReliableExecutionControl.scala` | 单次执行的接纳、取消、排空和释放确认 | 已接纳批次完成后才关闭资源；不负责提交协议 |
| `workflow/nodes/base/BatchSourceSession.scala` | Source 流及关闭句柄、读取与关闭衔接 | 运行期资源不进入 Checkpoint |
| `workflow/nodes/base/SourceResourceScope.scala` | 异步获取前登记所有权、迟到资源关闭、关闭失败报告 | 关闭失败不冒充已释放 |
| `workflow/nodes/sources/` | MySQL/Kafka 读取、Debezium 回调、Bridge 和完整组装配 | 原始回调、完整组和交付批次是不同边界 |
| `workflow/nodes/cdc/` | 事件身份、信封、完整组及 cursor 表示 | 身份不依赖运行期批次大小 |
| `workflow/nodes/sinks/` | 目标写入、事务与目标账本 | 目标提交成功不能单独推出端到端恢复成功 |
| `workflow/query/` | 定义摘要、历史查询 | 当前分页仍可能唤醒实体，不是独立任务目录 |
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

当前已确认的限制仍包括 C2 COMMIT 回复丢失后持久化失败终态、两个 Calcite 套件中止，以及未经证明的 DDL、多 owner、共享 Reader 和 70 万表容量。结构整理不改变这些结论。此次拆分及回归材料见[结构整理记录](experiments/2026-10-20-code-structure/README.md)。
