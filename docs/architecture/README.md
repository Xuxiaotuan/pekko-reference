# CDC 平台的运行边界

项目保留一个 SBT 服务、一个静态管理界面和独立部署目录。完整目录和兼容面见[目录迁移说明](../development/directory-layout.md)。按职责拆实现，不通过重命名持久化类型制造模块。当前证据不等同于生产 HA、可用性百分比、固定 RTO 或 70 万表容量证明。

## 目录与依赖

| 目录 | 责任 |
|---|---|
| `server/.../bootstrap` | Guardian、服务启动、HTTP 协调关闭 |
| `.../api/http/routes` | HTTP 输入、错误和 DTO |
| `.../workflow/actors` | entity 命令、持久化、恢复/停止及纯辅助 |
| `.../workflow/execution`、`spi` | 运行计划、会话、执行与连接器接口 |
| `.../workflow/query`、`scheduler` | 有界查询、持久化触发 |
| `.../cdc` | 信封、事件身份、完整组协议 |
| `.../connectors` | 按 MySQL/Kafka/file/builtin/JDBC 归组的实现 |
| `.../infrastructure` | 集群、Sharding、配置、监控、SQL 与工具 |
| `console/src/features`、`shared`、`app` | 业务页面、HTTP client、布局 |
| `deploy/local`、`kubernetes` | 本地与集群环境模板 |
| `docs/experiments` | 不可变源码、结果与原始实验材料 |

公开 `EventSourcedWorkflowActor`、`WorkflowEvents`、`WorkflowDSL`、Checkpoint、`ExecutionFailure` 和 Scheduler nested 模型的 FQN/字段不因本轮拆分迁移。新增的纯 helper 不进入 Journal。Calcite 和 Executor 类保留，普通实现包随目录整理迁移；没有生产 imports 不能单独证明其可删。原持久化 FQN 和用户命令保持，普通实现的旧 import 不作为兼容承诺。

## 所有权与进度

API/Singleton 调度器 → Sharding → Event Sourced entity → Engine → Source/Sink。entity 运行在 worker 角色，Scheduler Singleton 位于 coordinator。执行计划绑定实例级注册表，CDC 仍直连目标 Sink。

T 是目标事务效果与完整组账本，J 是 entity 的交付 Checkpoint，D 是 Source 实际持久化 offset。顺序为 T 提交 → J 确认 → 原始回调前缀 ACK；恢复从 D 读取，J 延续交付序号，T 仲裁组重放。当前 C2 同一 execution 最多三次持久化恢复；明确拒绝、冲突、释放不能确认或额度耗尽保留上下文暂停。

## 优雅停止

Stop 在恢复 gate 之前处理，关闭新的执行接纳、取消后续恢复启动，但允许当前 attempt 已接纳的初始化/J 回调完成。新调度请求明确拒绝，不用 AlreadyRunning 冒充可 ACK 的投递结果。

可靠执行等待 `engine.cancel` 的实际释放 Future，再终止 entity。等待期间的流正常结束/失败不产生新的终态，保留 durable currentExecution 给替代 owner。普通行流不在可靠会话目录中，等待实际 Engine 结果，并持久化其真实成功/失败后停止。同步取消异常或失败不会被当成释放。

关闭观测超时只是观测，不能打开会话 fence。优雅退出仍受 Pekko Sharding handoff 和外部进程预算约束：本项目所用 Pekko 1.1.3 默认 handoff-timeout=60s，内部约 55s 后可能强制停止未结束 entity。阻塞 JDBC、强杀、分区/SBR 后的跨 owner fencing 尚未证明。延迟 Effect.stop 不会消除这一风险。

恢复继续沿用累计三次预算，包括中断后的接管；多次滚动重启也可能耗尽后暂停。当前没有人工审核后的继续/放弃 API，不承诺无限自动恢复。

## HTTP 与探测

HTTP binding 注册 CoordinatedShutdown 的 service-unbind 和 service-requests-done。默认 `http.shutdown-grace=5s`，外围 phase timeout=10s；配置必须满足 0 < grace < phase timeout。进程使用 Pekko 自带的协调关闭 hook；不再额外注册一个竞争 terminate hook。

`/health/live` 仅说明进程响应。`/health` 与 `/health/ready` 共用成员 Up、Sharding 和 JDBC 的实际探测；无条件 HEALTHY 文本撤下。`/api/v1/status` 保持独立 API 状态资源，不能替代就绪检查。

同一 Route 生命周期只允许一次实际 readiness 探测。响应预算默认 `pekko.workflow.readiness-timeout=2s`；挂起时返回未确认就绪，底层仍占用探测 slot，重复请求不会继续堆积 SQL。实际工作结束才允许下一次。没有声称响应超时已取消 JDBC；永久挂起仍需数据库超时/运维处理。

## 分片与部署

Guardian 使用 `pekko.cluster.sharding.number-of-shards`，默认 100，非正数提前拒绝。此前非默认生产配置曾被忽略；现在会真正改变 hash 映射。已有集群不得直接滚动修改 shard count，须按停机/迁移策略统一确认；本轮只提交代码和模板，不对现网执行迁移。

Docker EXPOSE 与默认 HTTP 8080 对齐。环境变量、角色、JDBC schema、端口和 K8s 模板仍需按部署指南准备。Pod 的总退出预算必须覆盖 preStop、HTTP、Sharding 和后续协调阶段；当前 lab 模板不是生产 HA 认证。

## 验证级别

纯函数/Actor/路由测试证明其局部契约；H2 两节点接管不替代真实 MySQL。真实 MySQL 实验保留 SourceRecord、ACK、T/J/D、Journal、SQL dump、审计和六字段对账。安全暂停保留实际差异，不计为同步收敛。

优雅退出改造记录见[架构与退出实验](../experiments/2026-10-21-architecture-ha/README.md)。历史目录、源码快照和旧 C2 失败全部原样保留。DDL、共享 Reader、多 owner fencing、新快照算法、生产服务端掉电、长分区、日志清理/重置和容量测试仍在证据范围之外。

当前目录迁移及恢复回归见[目录整理记录](../experiments/2026-10-21-directory-layout/README.md)，该记录对应新实现包；历史材料保留原始源码路径。
