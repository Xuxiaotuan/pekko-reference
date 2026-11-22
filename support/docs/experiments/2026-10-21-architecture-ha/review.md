# 结构、生命周期与恢复边界审查

基线为 `b083bec7cf37c1d81087ef9ea69e1cc2380388ce`。本轮从干净工作区开始；检查当前 main/test/resources、构建与运行入口、UI imports/直接依赖和部署预算，重点精读 Actor/Engine/Source/Sink 的所有权与释放路径。全目录导航和引用检查不等于每一行已被形式化证明。

## 已落实的审查结论

| 问题及因果链 | 本轮处理 | 证据层级 |
|---|---|---|
| Entity Stop 直接终止，PostStop 的异步 cancel 无法阻止 entity 先消失 | StopController 等实际 cancel Future；已接收的初始化/J 仍可响应；可靠执行不误写终态 | 固定窗口 Actor 测试；真实 stop-T 另见 result.json |
| recovery gate 可能把 Stop stash；排队的恢复回调可能启动替代执行 | Stop 优先于 gate；取消 timer，停止阶段拦截恢复回调，启动前再检查 | 恢复竞态与 early Stop 测试 |
| 可靠会话注册表为空不能说明普通行流结束 | 等普通流真实结果，持久化成功/失败再停止 | red-row-history.log 的真实断言失败；修复后通过 |
| Stop 后回复 AlreadyRunning 可能被调度器当可确认结果 | 使用原有 DefinitionRejected；保持调度触发待重试 | Actor 测试和源码对照 |
| readiness 无响应预算；反复请求可能重复启动 SQL | 共用一次实际探测，响应超时但不释放未结束的工作 slot | red-readiness.log；并发/超时/后续恢复测试 |
| 旧 /health 返回无条件 HEALTHY；status 的嵌套 path 不可达 | /health 使用真实 readiness；status 独立路径；live 仅进程存活 | 路由测试 |
| HTTP 未注册 binding 的协调关闭；绑定成功前预告成功；重复 hook | 集中 HttpServerLifecycle，绑定结果后记日志；注册 unbind/drain，使用框架 hook | 真实 loopback HTTP 请求与 CoordinatedShutdown 测试 |
| 宽限时间可能耗尽外围 phase timeout | 默认 grace 5s、phase 10s，启动校验 0 < grace < phase timeout | 配置与实际 HTTP 生命周期测试 |
| shard count 配置未传入生产 init | Guardian 传入配置值，正数验证 | 配置测试；映射迁移限制见 ARCHITECTURE |
| legacy CreateWorkflow 临时 reply Actor 可无期限存活 | 5s 超时，报告结果未知并终止回复 Actor | supervisor timeout 测试 |
| 大 Actor 同时承载投影、编码、历史和运行控制 | 只提取三个纯 helper，公开模型仍在原 FQN | 原状态/历史/CBOR/旧快照及全量回归 |
| UI 旧示例与生产目录混杂；未用直接依赖和失效 lint | feature 目录、逐字节归档、精简直接依赖并补 ESLint 配置 | archive hash；隔离安装后的 test/build/lint |
| Docker 暴露 9906，与默认 HTTP 8080 不符 | 仅修 EXPOSE 元数据 | Docker/stage 内容检查；没有构建或发布镜像 |

只读审查提出的 Stop 失败/sync throw、early recovery Stop 和普通行流历史断言都已补充。最终关键路径定向测试 50/50；后端完整测试 503/503、82 套件。当前差异审查未发现阻止本轮提交的问题。

## 保持兼容的范围

没有迁移 EventSourcedWorkflowActor/WorkflowEvents/DSL/Checkpoint/ExecutionFailure 的公开和持久化声明，没有修改 persistenceId、T 账本、完整组身份、原始回调前缀确认、C2 最大三次预算。新增 StopReleased/StopReleaseFailed 是私有进程内消息，不进入 Journal。新 helper 不保存运行资源，不参与 wire/persisted schema。

没有因为当前生产 imports 为空而删除公开 Calcite/Executor 兼容入口；保留必要工作流、调度和历史实验。旧 UI 的四份退役代码及两份旧文档按字节归档，旧实验 154 个文件逐一哈希不变。移除的是十个未用的直接产品依赖声明，部分依赖仍可能由其他包间接引入；不能写成十个包彻底消失或 bundle 大小已经显著下降。

## 验证解释与探索记录

- `backend-final.log` 是冻结源码后的最终后端执行，带测试 JVM 清理等待 60s；默认配置仍未改变。package、Universal/stage、Docker/stage 均成功。
- `quick-test.log` 使用现有根入口，六个局部套件 68/68；不包含真实 MySQL，也不包含旧百万 Probe 示例。
- UI 在独立目录从空 node_modules 安装后执行五项契约测试、TS/Vite 构建和 lint。随后 frozen/offline 安装复核通过；关键实际版本与原安装一致。没有进行浏览器人工验收。
- `exploratory/red-stop-routes.log`、`focused.log`、`focused-compiled.log` 含测试设施编译错误，不能当成生产行为复现。真正行为红灯分别在 `red-stop-routes-behavior.log`、`red-readiness.log`、`red-row-history.log`。
- HTTP 测试最初按 socket EOF 判断完成，连接重置引起误判；改为解析 Content-Length 后验证完整响应及 unbind 后拒绝连接，见 `http-framed.log`。
- 首次完整后端运行与配置编辑重叠，加载了旧 reference.conf，导致新增 phase 配置校验失败；保留在 `exploratory/backend-full.log`。最终冻结源码后完整重跑通过，未将探索失败抹去。
- stop-T 预跑只支持较早源码的单窗口结果。正式矩阵使用独立的 run-source-manifest；两者不能混计。

## 仍不能宣称的保证

1. Pekko 1.1.3 Shard 在 handoff-timeout=60s 时内部约 55s 后可强制停未退出 entity；本轮延迟 Effect.stop 只改善预算内优雅退出。底层 JDBC 永久阻塞、进程强杀和分区不能由本地 Future 证明跨 owner fencing。
2. lab Pod 60s 的退出预算还包含 preStop 10s 和其他阶段，不能直接当成完整 drain 预算。模板未部署，未验证生产 K8s/多节点真实 MySQL 故障切换、固定 RTO 或 SLA。
3. C2 累计预算仍包括 execution 中断/接管；多次滚动重启可耗尽三次后暂停。尚无人工审核后继续/放弃 API。
4. readiness 响应超时没有取消实际 JDBC；永久挂起会保持 503 和工作占用，不声称已治理所有数据库超时。
5. 非默认 shard count 现在真正生效，是兼容迁移事项；现存集群须统一处理映射，不可直接滚动改变。
6. 未新增 DDL、多 owner、共享 Reader、新快照算法、长分区、服务端掉电、binlog 清理/重置和 70 万表容量证据。单 owner/固定 Schema 的恢复测试不等于通用 exactly-once。
7. UI 仍有约 985kB 的产物 chunk 警告和工具链弃用提示；本轮未按需拆包或整体升级依赖，未作安全扫描认证。
