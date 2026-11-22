# CDC 平台的职责、目录和优雅退出边界

用户授权自主检查、优化、测试、提交推送，不等待逐项审批。基线 b083bec；本轮不部署外部环境，不删除历史证据，不引入新框架或新库版本。目标是让当前 CDC、必要工作流与调度职责可定位，并修正已发现的可用性缺口；不把目录整理等同于高可用证明。

## 选择

采用一个 SBT 服务和一个 UI 的现有部署形态，拆纯状态投影与编码辅助、集中服务生命周期，UI 按业务 feature 组织。保持 Actor、Command、Reply、State、WorkflowEvents、DSL、Checkpoint 和 ExecutionFailure 的 FQN/字段/persistenceId。无需制造跨模块工程或迁移 Journal。

大范围重命名 package 会破坏兼容风险，单纯移动目录则不解决停止资源未释放的问题；本轮保留协议位置，只拆非持久化实现。

## 后端边界

- Actor 原对象保留全部 wire/persisted 模型和命令处理，纯 replay 投影迁至 WorkflowStateTransitions，定义校验/规范化迁至 WorkflowDefinitionCodec，结果与历史编码迁至 WorkflowHistory。外部及测试兼容方法转发，结果不变。
- Stop 在 recovery gate 之前处理。停止阶段不启动恢复、不接新执行；保留当前 attempt 的初始化/J 回调，让已接纳 T→J→ACK 完成。不把 KillSwitch 正常完成误写为 ExecutionCompleted。可靠执行只在 engine.cancel 的实际释放 Future 完成后 Effect.stop；重复 Stop 幂等，失败不冒充释放。普通行流等待其实际 Engine 结果并保存真实终态，不能用可靠会话注册表空集合证明结束。
- HTTP 绑定注册 CoordinatedShutdown 的 unbind/drain；拆出 HttpServerLifecycle。启动日志不预告绑定成功。兼容 status 路径修正，health 使用实测 readiness，live 仍仅进程存活。
- ReadinessCheck 在同一 Route 生命周期共享一次实际探测。返回有 deadline；超时不释放仍运行探测的占用，后续调用不能无限启动新 JDBC 查询。真实完成后才允许新探测。同步抛错和 Future 失败都按未确认就绪处理。
- Guardian 明确传入配置 shard count，非正数提前拒绝；默认仍 100。运行集群不能滚动修改分片数；过去被忽略的非默认值必须按迁移处理，不能宣称无损热切换。
- compatibility CreateWorkflow 的回复 Actor 有界超时，沿用既有错误回复，不制造成功。

## UI 与目录

当前页面/API/类型迁至 features/workflows、features/schedules、features/cluster；共享 HTTP client 留 shared/api，布局留 app。App 的 URL 不变，pending requestId 语义不变。退役 SQL/TaskMonitor/DAGEditor 及旧类型逐字节归档到 examples/legacy，生产 tsconfig/lint 只覆盖 src。使用已有 ESLint/TS/hooks 插件补可执行 lint；测试使用已安装的 esbuild 0.21.5，并将其显式声明为 devDependency。

根 README/CODE_STRUCTURE 提供目录与运行链路；历史实验路径、.tasks、.kiro、公开 Calcite/Executor 保留。Docker EXPOSE 对齐实际 8080，不发布镜像。

## 验证与限制

先加固定窗口测试复现 Stop 提前终止、readiness 挂起/重入、状态路由与假健康；补真实本地 HTTP unbind/drain 测试，回归原 Actor/CBOR/两节点/Singleton 测试。前端执行 contract tests/build/lint。

生产 CDC 代码变化后重新运行真实 MySQL Source/Actor Journal/Sink 的完整 26 项，加一个真实 Stop-T 窗口：T 已提交、J 未请求时 Stop，放行后确认 J/ACK/物理 close 在 owner 终止前完成；新 JVM 恢复同 execution 并逐字段对账。保存 T/J/D、原始回调/ACK、SQL、审计、全字段和全源码摘要。全部旧材料哈希不变。

仍未证明：Pekko handoff deadline 强制 stop、JVM kill、网络分区/SBR、多 owner fencing、DDL、共享 Reader、70 万表、生产 RTO/SLA。Pekko 可在 handoff 超时后强杀实体，延迟 Effect.stop 只改善框架预算内的优雅退出。人工暂停恢复 API、跨 owner 协议与所有连接器资源治理不在本轮新增范围。
