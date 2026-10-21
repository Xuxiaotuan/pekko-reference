# 运行计划边界实施计划

目标：按本轮已讨论并获执行授权的第一步，在已有 Workflow DSL 与执行之间增加内存计划；明确区分 CDC、可检查点线性任务和普通行流。

## 约束

- 保留旧 HTTP/DSL、Actor execute 入口、Journal 类型、事件字段、persistenceId。
- 保留 T → J → Source ACK、批次顺序、完整组身份、取消排空与释放确认。
- 不修 C2，不实现查询投影、资源配额、共享 Reader、DDL、多 owner。
- 当前干净功能分支原地实施；本轮不提交、推送或发布。
- 连接器仍使用已有 Node 接口；仅在适配边界生成无画布字段的 Node。配置仍由原连接器解析，不宣称已完成全平台配置类型化。

## 文件与职责

- 新增 `workflow/engine/ExecutionPlan.scala`：不带位置、样式、边、历史的运行描述及绑定了实例级适配器的计划。
- 新增 `workflow/engine/WorkflowPlanCompiler.scala`：复用 Validator，确定当前注册能力和运行路径；只解析定义，不取凭据、不访问数据库、不启动资源。
- 修改 `WorkflowExecutionEngine.scala`：旧入口编译计划后分发；运行方法只处理计划内的运行节点和任务信息，保持提交代码顺序。
- 修改 `EventSourcedWorkflowActor.scala`：可恢复判断委托同一编译器规则，移除重复 Source/Sink 类型检查；不改事件处理与 Kafka 历史进度规则。
- 新增 `WorkflowPlanCompilerSpec.scala`，必要时补充现有 Engine 测试：计划分类、画布隔离、注册能力替换、能力丢失、错误归因。
- 更新 `docs/architecture/code-structure.md`；在独立实验目录记录本轮实际检查与边界，历史材料只读保留。

## 执行与验收

1. 运行原有 Engine/Actor 恢复测试，建立当前基线。
2. 补计划契约测试，观察新 API 尚不存在的失败；实现最小模型与编译器。
3. 接入 Engine 与 Actor，保留旧重载和错误归因。可恢复性沿用当前能力组合，不能把计划分类解释为新增可靠性保证。
4. 针对注册实例替换、无副作用编译、画布改动不影响计划、无 Context 拒绝可靠执行、恢复能力丢失等执行测试。
5. 执行全后端测试、打包；真实 MySQL/Journal/Sink 八窗口及两个取消场景回归。七窗口应继续收敛，C2 必须准确保留失败证据；取消场景检查六字段、Released、runner 为 0。
6. 核对历史实验哈希、持久化模型差异、生产包与最终 diff；记录本轮证据，不把测试通过升级成容量或生产保证。

## 审查重点

- CDC 必须仍直连，禁止 Transform；普通行流 Source 搭配支持 Checkpoint 的 Sink 仍保持旧行流行为。
- 不能仅凭 mysql 名称认定可恢复；当前注册实例失去能力时须拒绝恢复降级。
- 编译不解析环境密码，不捕获 ACK 句柄或运行资源，不写入 Journal。
- 不将画布位置/style、边动画或 metadata.history 传给执行连接器。
- 不改变旧序列化 payload、组账本证据、批次身份和 Source ACK 顺序。
