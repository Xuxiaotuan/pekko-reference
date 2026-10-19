# Pekko CDC 平台收敛设计

日期：2026-10-19，Asia/Shanghai。

状态：用户已确定“以 CDC 平台为主，保留必要的工作流与调度”，随后批准先执行 **A 阶段**。
A 阶段的实际改动和验证见[独立记录](../../experiments/2026-10-19-stage-a-convergence/README.md)；
B 阶段随后获批按最小范围实施，实际接口/页面变更见 [B 阶段记录](../../experiments/2026-10-19-stage-b-console/README.md)。
C 阶段随后获批实施，实际会话边界和真实回归见 [C 阶段记录](../../experiments/2026-10-19-stage-c-sessions/README.md)。D 阶段与恢复策略变化仍待讨论。下文的调查数据和“本设计轮”措辞保留设计时点含义，不代替实施记录。

工作区基线：`dca7d25cfcf7747d7d08231259c3353331755b84`，分支 `feature/pekkosync-mvp-cleanup-datafusion`。上一轮真实恢复测试及 `docs/experiments/2026-10-19-recovery-windows/` 尚未提交，原样保留。完整组生产协议参考为 `65ab5410c32933d0aa3d23510e6c62252d84009b`。

## 1. 产品目标与收敛选择

目标是长期管理大量 CDC 复制任务：定义、启动、停止、进度、异常、受控恢复、运行资源和查询。70 多万表是业务背景，不是当前容量结果。

必要工作流保留一个连通的 Source→Transform*→Sink 线性定义。CDC 当前只允许 MySQL Source→MySQL Apply Sink 直连，继续拒绝内联 Transform。有限全量任务作为辅助能力保留，但不自动成为新组协议的初始化步骤。

比较三个路线：

| 路线 | 收益 | 代价/问题 | 决定 |
|---|---|---|---|
| 只删死代码，维持所有产品入口 | 改动小 | 虚构能力、API 脱节、查询唤醒实体与资源耦合仍在 | 只作为第一阶段的一部分 |
| 在现有工程内分阶段收敛为 CDC 平台 | 保留已有协议、持久化和调度；每步可验证与回退 | 需要逐步处理接口、查询与会话边界 | **推荐** |
| 重写为通用 DAG/SQL/多服务平台 | 远期自由度大 | 扩大范围、重新证明恢复、失去已有实验基线 | 本轮不采用 |

保留当前生产 SBT 项目 `pekko-server`。先按职责整理代码，测试/示例隔离于发布产物；不同时引入新微服务、消息中间件、插件体系或通用执行框架。

## 2. 已核实的问题和证据层级

以下是当前源码/配置与本地静态检查结论，不是线上流量或容量结果。

| 对象 | 当前事实 | 对收敛的影响 |
|---|---|---|
| 代码规模 | 主 Scala 89 文件/13,112 行；启用的 Scala 测试 83 文件/20,104 行 | 不以行数或测试数量决定删除 |
| 旧 Actor | `WorkflowActor.scala` 276 行；生产主链路使用 EventSourcedWorkflowActor，旧名仅有禁用测试引用 | 从生产源码移出，维护历史说明 |
| 模拟 CDC | `stream/cdc` 7 文件/1,520 行，只在模拟子树内部形成运行链 | 迁入测试/示例范围，不作为平台 CDC 实现 |
| CRDT/GC/Stream 示例 | 4 文件/368 行，未发现正式入口调用 | 逐文件核对配置、反射和手工入口后迁移 |
| 查询列表 | currentPersistenceIds→drop/take→逐个 ask 分片 Actor | 查询与实体激活耦合；深页从头丢弃，缺少稳定列表排序契约 |
| 注册表 | NodeRegistry 的全局 mutable.Map 可被覆盖 | 生产装配与测试替换共用全局状态，需要实例级依赖边界 |
| HTTP/UI | 后端列表返回分页摘要，前端期待完整 Workflow[]；存在未实现的 update/delete/node-types/logs 调用 | 先整理能力与接口契约，再做页面收敛 |
| 能力展示 | SQLConsole 返回固定 Alice/Bob；ClusterRoutes 分片/活跃数固定 100，位置固定本机、实体数固定 0 | 页面与 API 不应将模拟值显示为实际运行结果 |
| 前端静态检查 | tsc 无输出检查失败：2 个 Axios 响应断言、1 个未用参数、1 个缺失 ScheduleConfigPanel | 先使类型与实际响应匹配；不能用 any/unknown 强转掩盖 |
| 构建/依赖 | 命令别名写 pekkoServer，但实际项目 ID 为 pekko-server；Arrow/Flight 等直接依赖未发现生产使用 | 修正入口，按生产/测试/运行期分项验证依赖 |

源码入口：

- [PekkoGuardian](../../../pekko-server/src/main/scala/cn/xuyinyin/magic/cluster/PekkoGuardian.scala)
- [工作流与源能力校验](../../../pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/WorkflowValidator.scala)
- [查询](../../../pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/query/WorkflowQueryService.scala)、[注册表](../../../pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/registry/NodeRegistry.scala)
- [后端 API](../../../pekko-server/src/main/scala/cn/xuyinyin/magic/api/http/routes/WorkflowRoutes.scala)、[前端调用](../../../xxt-ui/src/api/workflow.ts)

本地代码图缓存位于 `/tmp`，只辅助产生候选。图会将经 SQL/配置使用的对象列为“无调用者”，不能直接据此删除。源码引用检查也不能证明没有仓库外调用方；迁移需提供运行入口变更说明。

## 3. 目标职责

| 职责 | 保留的基础 | 应负责 | 不应负责 |
|---|---|---|---|
| 任务控制 | Sharding、EventSourcedWorkflowActor、WorkflowSupervisor | 定义/修订、启动授权、运行归属、状态迁移、持久确认 | 在 handler 内阻塞读取；扫描所有任务生成列表 |
| 执行会话 | WorkflowExecutionEngine、Streams、ReliableRunContext | 一次执行的资源、取消/释放、背压、向 Actor 报告结果 | 生成新的业务事件身份；擅自覆盖 D；决定失败后的产品状态 |
| CDC 数据协议 | Source、组装配器、Bridge、Sink、组账本 | 完整组、规范化、T 事务、J 覆盖后原始前缀 ACK | 任务目录、Cron、前端表现 |
| 查询与展示 | HTTP、前端；后续查询视图 | 分页目录、定义、历史、能力及证据状态 | 借列表查询启动冷 Actor；将摘要当完整定义；合成成功统计 |
| 调度 | SchedulerCoordinator、ScheduleCalculator | 定时触发与触发幂等、暂停/恢复调度 | 代替 CDC 会话恢复；定时创建重复 reader |

这些是逻辑边界，首阶段不重命名持久化类的完整包名，也不修改现有 Journal 的事件/快照结构。

## 4. 代码与依赖处置清单

| 分类 | 具体范围 | 第一阶段处置 |
|---|---|---|
| 正式保留 | EventSourcedWorkflowActor、Sharding、Supervisor、Scheduler、Validator、可靠执行链 | 保持行为与持久化兼容 |
| CDC 核心 | MySQLCdcSourceNode、DebeziumEngineAccess、DebeziumBatchBridge、MySQLCdcGroupAssembler、MySQLCdcGroup、Envelope、Apply Sink、账本 Schema | 保留；不因类长合并或削减边界校验 |
| 辅助节点 | mysql.snapshot/mysql.write、Kafka 及必要有限任务路径 | 保留已有契约，前端突出 CDC 主线；不把 snapshot 节点自动串入 v2 初始化 |
| 开发数据源/目标 | RandomNumbers/Sequence/Memory、Console 等 | 从产品默认选项隐藏；生产注册是否进一步限缩另加兼容检查，不立即删除节点类型 |
| 旧 Actor/CDC 演示 | WorkflowActor.scala、`stream/cdc/**` | 优先移动到 Test 源集的示例区域，保持包名，提供 Test/runMain 运行说明；如无入口可运行则标历史参考 |
| 旧工具示例 | DDataHelper、PNCounterCache、PekkoGc、PekkoStreamUtils | 依赖核对后移入 Test；不用批量“无引用即删除” |
| SQL/Calcite/DataFusion | parser/calcite、SQLConsole、Arrow/Flight/gRPC 候选依赖 | 主产品隐藏 SQL 功能；保留 Calcite 源码、测试及现有材料，不在清理阶段修复/删除两项既有失败 |
| 禁用测试 | `src/test/scala-disabled` | 保留历史分类并从活跃测试说明中排除；不悄悄当作通过覆盖 |
| 实验与文章 | docs/experiments、docs/cdc-design | 原始包与哈希不可改写；仅在后续获授权时更新索引/正文 |

迁移到 Test 而非增加独立示例服务，可以先将演示代码排除在生产 JAR 外，同时不引入额外发布模块。若现有测试引用示例，保持其包名和可见性；迁移后显式执行对应示例/测试。只有确切构建路径验证通过后，才称为可迁移。

依赖清理以“生产编译、测试编译、运行期配置/反射、打包产物、启动 smoke”五项共同判断。当前候选：直接 Arrow/Flight/gRPC、commons-pool2、未用连接器，以及只服务历史示例的 LevelDB。Calcite 保留期间不得随意删除其传递依赖；不同时升级 Debezium、Pekko、MySQL 驱动。不会为了让主测试变绿而删掉或静默跳过 Calcite 测试。

## 5. 工作流与调度保留到什么程度

保留线性定义和版本校验；不扩展分支、合流、任意 SQL 或新转换器。现有 DAG 画布可以保留为线性路径编辑器，但前端和后端都拒绝不支持的拓扑。CDC 任务首选简单 Source/Sink 表单，生成同一份受校验 Workflow DSL，不维护第二种执行模型。

需要区分三个产品对象：任务定义（长期身份）、一次执行（executionId）、一次源交付/Checkpoint。先在服务与类型层明确区分，不立即把它们改成新的 Journal 事件结构。

有限辅助任务可使用 Cron/定时触发；常驻 CDC 的启动应具备幂等和“已有执行返回 AlreadyRunning”语义。暂停调度不等于停止正在运行的 CDC；停止会话也不等于允许丢掉未确认数据。UI 必须区分这些动作。当前后端未提供完整的停止/恢复产品 API，首阶段隐藏或禁用这些控件，不能只增加一个按钮宣称功能完成。

常驻会话的重试由执行恢复策略负责。Cron 不应与恢复策略竞相启动 reader；后台运行中状态不能只由前端定时器推断。

## 6. 前后端契约和能力展示

首阶段以现有后端已实现接口为准，修正前端类型、解析和调用路径。使用一个明确的 HTTP client 约定：客户端返回 AxiosResponse，调用方只在 API 层读取 data；页面只收到领域 DTO。避免响应拦截器改了运行时结构、TypeScript 仍声明另一种结构。

| API/页面 | 最小处理 | 后续新增能力的条件 |
|---|---|---|
| GET /api/v1/workflows | 使用 `{page,pageSize,items}` 分页摘要 DTO，不能当 Workflow[] | 独立查询视图落地后保持响应契约 |
| GET /api/v1/workflows/:id | 继续解释为 summary，保留当前语义 | 完整定义用独立 definition 资源，不静默替换旧响应 |
| 创建/执行 | 对齐现有 POST 响应、requestId 与错误码 | 创建界面只发送真实支持的 CDC 配置；不能以空 nodes 当有效定义 |
| 更新/删除/日志/node-types | 首阶段移除未实现请求及误导控件 | 新增接口时单独定义 revision、幂等与删除语义；不删除 Journal 来实现删除 |
| 历史 | 对齐现有 /api/history 路由与真实响应 | 不继续调用不存在的 /executions 路径 |
| SQLConsole | 从产品导航及可执行入口移除，保留示例历史 | 没有真实执行链路前不显示执行成功 |
| 集群/监控 | 真实值才显示；未知值使用 unavailable/null 与原因 | 获取真实 Sharding 统计需使用固定 Pekko 版本 API 并验证；不能用 0 或配置上限充当测量值 |

第一阶段的产品界面是“可验证的最小 CDC 管理入口”，不以增加未实现功能填满页面。已部署客户端若依赖当前集群统计的数值字段，需要给出可空/状态字段迁移说明，再改变响应；这是接口变更，不能混作无行为差异的清理。

## 7. 查询与注册表的下一阶段设计

查询目标：任务列表只查询有索引的目录视图，不为每项 ask Actor。目录可按 workflowId 稳定分页并返回状态的更新时间/投影位置；命令与强一致状态校验仍由 Actor 完成。

目录是派生视图，Journal 是事实来源。视图写入失败不回滚 T/J，不阻止原始 SDK ACK；用户看到陈旧数据时必须可识别。重建、幂等 upsert、检查点和旧数据回填都需要验收。

当前源码未使用 withTagger。不得假设旧 Journal 已有可用的全局目录事件流。实现查询视图前需要单独完成“新增事件发现、旧 Journal 回填、版本水位、重复/乱序、投影自身恢复”的小设计和验证；现有 Read Journal 可复用，但不在本轮临时增加无法重建的同步双写。

NodeRegistry 目标为不可变实例级目录：启动创建生产目录，将目录传给 Validator/Engine；测试使用各自目录而非覆盖进程单例。先保留既有节点类型和构造入口，逐个迁移调用方。这个调整不能改变会话确认注册表的生命周期，也不能用“无状态单例”共享实际有状态的 Source/Sink 会话。共享读取是另一个协议，不在这里顺手实现。

## 8. C2 和资源模型单独处理

上一轮正式真实 MySQL/Journal/Sink 实验为八项观测、七项收敛，C2 自动恢复失败，缺 2 行、多 2 行、212 个字段差异。[报告与原始材料](../../experiments/2026-10-19-recovery-windows/README.md)。这是已观测的失败，不改写为未知。

C2 设计需要决定：如何表示“提交结果不确定”、哪一层拥有重试权、重试是否延续原 executionId、什么时候可以持久化永久失败、如何处理现有失败终态。每个决定都必须绑定 T/J/D 及同一 namespace/generation 的安全关系。不能通过换 generation、重置 D、清理组账本或从 T 的最大位置拼接 D 来消除冲突。

第一阶段保持 C2 的失败观察不变，作为行为基线。修复另行设计/实现/验收，只有真实 C2 收敛后才改变其结论。

资源方面先处理已知共享阻塞池、空 Bridge 等待和取消释放问题，再逐级量化每任务 Engine/连接/线程/内存成本。保留 Actor 与执行资源解耦的方向，但不把 70 万 Actor 身份等同于 70 万独立 Engine。读取分组、多 owner fencing、共享 D 前缀及 writer 配额属于后续能力，不计为本次完成。

## 9. 分阶段实施与停止线

| 阶段 | 交付范围 | 明确不包含 | 验收 |
|---|---|---|---|
| A：发布边界与构建收敛 | 旧 Actor/模拟器/工具示例逐项迁出生产产物；验证后删直接冗余依赖；修正 SBT 别名与说明 | 持久化类重命名、事件升级、CDC 算法改写、Calcite 失败修复 | 主/测试编译、示例可运行、生产 JAR 无示例类、依赖与启动 smoke、原始证据包不变 |
| B：可用界面与 API | 类型检查修复；正确分页与摘要；移除假 SQL 成功与未实现请求；真实/未知监控状态契约 | 新 SQL 引擎、完整删除/停止/恢复功能、任意 DAG | tsc/build、路由与 API 契约测试、列表/创建/执行/历史浏览器验证 |
| C：依赖与会话边界 | 实例级节点目录、Validator/Engine 注入；有界等待及取消责任的小步整理 | 共享 reader、多 owner、改变 T/J/D 语义 | 两份测试目录互不污染，关闭/迟到 ACK/取消回归，相关真实窗口回归 |
| D：专门协议设计 | 查询视图、C2 恢复、资源分组分别设计与实施 | 一次性“全部优化”大改 | 各自真实故障与规模证据；独立结论 |

每阶段独立差异和回退点。恢复协议行为变化、持久化类型变化、数据迁移、未知运行期依赖或既有证据被推翻时，停下重新评估该阶段，不用削弱断言换取通过。

## 10. 验收基线与证据保全

- 迁移和依赖清理前记录生产 JAR、源码路径、依赖及启动配置；每个候选都核对直接引用、配置/反射、Test 源集和文档运行入口。
- 对已通过的功能，只保留最低充分回归；目录搬迁不机械增加镜像式测试。
- 生产 CDC 类、身份/编码、组账本、Journal、Bridge、会话注入或打包依赖受影响时，执行真实八窗口。通过标准为七项仍收敛、C2 仍准确保留失败；这只证明重构保持当前行为，不意味着平台恢复完整。
- 不触及生产链路的文档改动不要求重跑数据库。纯前端变更执行类型/构建、契约与必要浏览器验证。
- 上一轮全模块为 418 项通过、两个 Calcite 套件中止；此为历史基线，本设计轮未重跑。完整检查仍须运行并分别报告，不能静默排除它们后称全库通过。
- 原始实验 archive、SHA256SUMS、源码快照不改写。前述未提交实验文件与本设计文件分别识别，不广泛暂存，不自动提交、推送、部署。
- 任何历史 Journal 保留、升级、工作流归档/删除功能都需另设兼容与恢复验收，不物理清空持久化记录。

## 11. 本设计轮的实际检查与边界

本轮核对了工作区状态、已有设计约定、Validator 的 CDC 限制、Scheduler 的触发/去重边界、Actor 持久化事件，以及 HTTP/UI 契约。上一轮只读审查的代码图和前端检查用于定位问题，没有将其当作运行或容量证明。

本轮只创建本设计文档；生产、前端、测试设施和八窗口材料未修改，未启动数据库、未重新执行恢复测试。下一步是评审 A 阶段具体迁移范围与 B 阶段接口兼容处理，再形成可执行计划。
