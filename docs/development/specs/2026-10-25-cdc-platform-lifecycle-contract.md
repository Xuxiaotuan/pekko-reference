# CDC 平台生命周期与恢复契约（任务包 E）

**状态：设计提议，未实施、未冻结为生产 API。** 本轮任务 A/C 只增加测试设施，B/E 交付设计；D/F/G 的生产改造仍需相应设计确认。下文“建议”不是现有能力或实测结果。

## 1. 真实目标与当前证据

目标是在一个 MySQL 实例的大表目录中动态选择少量或逐步增加的表，先初始化再持续跟随，允许取消；冷任务数量与运行时资源不能线性绑定。

当前有 LOW/HIGH、固定范围整段重读、封存输出、T 后 J、完整 B→F 重放、激活证明与 tail 的受限内核。`BootstrapRunner` 当前需要专用阻塞线程，默认 8 个 run 许可；STREAMING 持续占线程和会话锁。`BootstrapJournal` 的 PAUSED 目前为终态，不能直接 Resume。旧 `TableSchedulingRuntime.definition` 只接受静态 snapshot→write，不能据此认为新链路已经接入启动/调度。

61 新场景、80 相关测试以及旧回归 57/58 是前轮保存结果；R02 保留失败。B 已核实初始 Schema snapshot 与订阅准入的源码边界；E 不自行把 PROCESS_READY、历史 Schema 或 Engine lifetime Future 推导为 ready。C 本轮在显式门、batch128、测试代理禁 TLS 的配置下，已复现“B 已绑定、D 仍 INITIAL”后重启的真实五字段不一致，且另一行 witness 的 Sink/J 继续推进；这是该配置的实验证据，不扩展为所有运行配置或完整 bootstrap 的结论。详见 [B 设计与证据](2026-10-25-shared-reader-startup-recovery-design.md)。

源码锚点（以此轮工作区为准）：
- `workflow/bootstrap/BootstrapProtocol.scala`：现有状态与持久进度；
- `workflow/bootstrap/BootstrapJournal.scala`：乐观 revision、取消粘性、PAUSED 终态；
- `workflow/bootstrap/BootstrapRunner.scala`：独占目标、运行预算、阻塞执行、激活与取消；
- `workflow/scheduling/TableSchedulingRuntime.scala`：现有冷队列、有界 SQL、静态定义限制；
- `workflow/scheduling/TableSchedulingModels.scala`：静态快照资源公式，不直接用于 CDC。

## 2. 方案比较与建议

| 候选 | 优点 | 代价与边界 |
|---|---|---|
| 把整个 Runner.run 放到每个 Actor 的 Future 中 | 接入代码少 | 当前默认 8 个长期运行会话会被 tail 占满；更多表意味着更多阻塞线程/锁，不能作为 70 万表目标架构 |
| 冷索引 + 表级控制 Actor + 可有界推进的执行步骤（建议） | 控制状态与 JDBC 分离；只有活动集合有执行资源；全量/追赶/tail 可分别调度 | 需要 F 拆出执行步与完成/释放证据；E 先统一契约，不能仅改类名宣称已 Actor 化 |
| 直接把 bootstrap 接入旧静态快照 worker/guardian | 可借用部分现有设施 | 静态快照租约、结束条件、隔离与配额实验并不证明共享 Reader 生命周期正确；会把 HA 一并拉入，当前不选 |

建议第二种，只覆盖单个受控 owner。多 owner 接管、guardian 新链路隔离、DDL 和 GC 另开设计。以下是该方案的讨论稿，尚未得到针对本契约的实施确认。

## 3. 身份与不变项

| 身份 | 建议含义与不变项 |
|---|---|
| sourceInstanceId | 平台逻辑源配置身份；实际 sourceLineage（server UUID）单独验真，不把切主后的 UUID 自动视为同一历史 |
| captureId | 一份共享 Reader、L、SDK offset/history 的持久命名空间；不随进程重启或分页改变 |
| tableIdentity | captureId + database + table；规范化规则先固定，不能在不同层随意转大小写 |
| generation | 一次从 B 到 K 的订阅/初始化代；取消后重新增加用新代和新的独占空目标 |
| taskId | 平台任务索引的不可变标识，映射一个 tableIdentity/generation；与旧 workflowId 分开 |
| requestId | 在源配置命名空间内幂等；同 id 同定义返回同 taskId，不同定义明确冲突 |
| executionId | 当前代执行身份；批次重组、自动恢复保持不变；不通过换 executionId 绕过目标凭据或恢复预算 |
| controlRevision | 平台已接受命令的单调版本；不复用旧 workflow definition revision |
| journalRevision | 内核实际持久进度的版本；与 controlRevision 分开，不能凭数字大小互相覆盖 |
| runtimeAttemptId | 每次运行尝试的临时身份，用于关联当前控制能力、观察与回复；不进入原始事件/组身份 |

上述映射必须持久化且可核验。具体字符串编码、表名和 DTO 语言类型由 E/F 的实施方案确定，不能先塞进旧持久类型。当前共享 Reader 没有已实现的持久 ownerEpoch；本设计不声称新增身份已提供跨 owner fencing。

## 4. 命令与查询分离

建议控制命令：Submit、RequestCancel、Inspect、StopRuntime。Inspect 是查询；StopRuntime 是维护停机，不能解释成业务退订或默默写 K。

- Submit 的接受凭据包含 taskId、requestId、definitionFingerprint、controlRevision。HTTP 202 只说明控制意图已持久接受。
- RequestCancel 先保存取消意图；重复取消返回同一结果。已有执行的取消由内核排空并提供证据后完成。
- 冷队列中尚未产生任何执行/源端副作用的任务可通过一次队列事务取消；其证明类型是 PREEXEC_CANCEL，不能伪造一个不存在的 Actor Journal 确认。
- Resume 不作为现有可调用能力。PAUSED 的新恢复命令需要单独的许可、原因分类、释放证明和预算规则；不能简单把 phase 改回 RUNNING 或把 counter 清零。

建议查询响应至少返回：身份及定义摘要、desiredState、controlRevision、实际 phase、journalRevision、captureReadiness、initializationEvidence、runtimeHealth、releaseStatus、进度/故障证据引用、observedAt/lastVerifiedAt。

`captureReadiness` 引用 B 的本次启动证据，并保留启动前加载的原始 D 分类；不能在 SDK 重选点后，拿后来变为非 INITIAL 的 D 覆盖原分类并宣称已承诺区间连续。ACK 链完整性不等于未送达 binlog 区间的连续性。`initializationEvidence` 是已经验证的窗口覆盖链、B/F 和目标激活凭据；`runtimeHealth` 表示是否正在持续推进；`releaseStatus` 表示资源处置。它们不是一个 running 布尔值。

## 5. 阶段、发布与暂停

| 观察到的状态 | 平台行为 | 发布含义 |
|---|---|---|
| 控制意图持久排队、未分配运行许可 | 显示 QUEUED；不为每条记录创建 Actor/Future | 未初始化 |
| Reader 启动证据未成立 | 排队等待一次 capture 级状态变化，或明确拒绝；不为每张表独立轮询 MySQL | 不能写成 CAPTURE_READY |
| B 已绑定、窗口扫描/回填 | 显示初始化阶段与实际进度 | 捕获就绪不代表可发布 |
| B→F 回放 | 显示追赶初始化终点；禁止新的 snapshot 覆盖回放效果 | 未发布 |
| 激活 T 存在、J 尚未接受 STREAMING | 显示 ACTIVATING | receipt 单独不够 |
| 激活证据与 J 接受均成立 | 初始化完成，显示 runtime 的当前健康度 | 可依据契约发布；不是 SQL 原子换表 |
| PAUSED | 保留现场、原因和原始阶段证据，禁止自动 reset | 若曾完成初始化，应显示历史完成证明及“实时已暂停”，不能冒充持续健康 |
| UNKNOWN/STALE | 显示最后验证时间和未知范围 | 不凭缓存宣称实时健康 |
| CANCELING/CANCELED | 区分受理、排空、K 与释放证明 | 不自动删除目标或旧凭据 |

初始化完成与持续健康是两回事。若曾完成初始化后暂停，历史 F 证明不会因一条暂停命令消失；首版建议由调用方决定是否继续读取该历史目标，平台必须明确已暂停/可能落后。本设计不实现业务侧原子发布/撤销权限。

## 6. 取消的具体边界

1. 取消意图先持久化，禁止承接下一次窗口/写入；已承接操作继续按 T/J 和释放协议收尾。
2. B 未成为生效区间时必须判断是否可能存在已提交但未绑定的 marker；不能把本地没看到 B 当成源端无副作用。此处依赖 B 的分类结果。
3. 已有 B 时确认 K；未发布的部分目标保留，不能复用它作为新代的空目标。
4. 已激活时按现有内核语义排空至 K，T/J 完成后才显示 CANCELED。取消一个表不关闭仍承载其他表的 Reader。
5. Future 超时不是事务取消；释放未知保留配额，显示 PAUSED/UNKNOWN，不返回“已排空”。晚到回复必须匹配 task/generation/execution/runtimeAttempt 与 operation 身份，不能复活旧代。

当前 Journal 不支持 PAUSED 后再接受 Cancel；不能通过 API 绕过它。本提议要求后续补充“已知释放且可退订的暂停”与“释放仍未知的暂停”两类操作许可，再实现对应命令。

## 7. 受控进程重启与持久事实

建议先限定：旧受控进程已经结束，当前 owner 身份没有歧义。若不能证明，不自动接管，仍安全暂停；不借用静态快照 guardian 的实验结果。

建议恢复次序：
1. 分页读取控制索引的非终态任务及仍未处置的 grants，而非扫描全部 Journal。
2. 按 capture 分组，在 sourceLineage、L、SDK D/history 和 B 的启动恢复证据校验后恢复一个共享 Reader。
3. 配额先按持久授予与未知资源事实重建。超时/租约过期/Actor 不在内存不能证明物理释放；不得直接归零。
4. 只为获准的活动集合加载 Journal、材料和 T 凭据；已封存窗口不用当前源数据重造结果。
5. 重放与激活仍由现有证据决定；恢复不能把 Queued/CaptureReady 直接升级为 Streaming。

这是需要实施的设计，不是当前 BootstrapAdmission 已有能力。当前它是进程内信号量，进程重启不会自动恢复额度。Source/target 的遗留事务确认和旧进程身份存在歧义时，当前阶段不扩大成 HA 处置。

## 8. 给 F 的调度接口边界

建议由平台协调器维护冷索引，表级 Actor 只负责控制和状态迁移；有界执行器承担 snapshot/replay/tail 步骤与 JDBC。共享 Reader 回调线程不等待某张表的 Sink/J。

资源至少分为：capture 级读取、snapshot 窗口、replay/tail 写入、在途 SQL、启动速率、窗口字节预留，以及结果未知仍占用的 grants。连接在确认关闭后可释放，但同表执行序和窗口完成证据不能因此跳过。

无变更表可仅保留订阅/索引和游标，暂停其执行资源；L 中存在该表新数据时再按预算激活。仍需衡量共享 Reader 的全库 Schema/订阅目录内存，不能把“不创建 70 万线程”当成“70 万真实表已验证”。不以给 Runner.run 再包一个 Future 代替步骤化调度。

建议 F 提供的行为契约（命名待实施设计冻结）：submit/get/listAfter/cancel、admit bounded work、report work outcome、report release outcome、reconcile controlled restart。每个完成回复携带对应 grant/operation 身份；UNKNOWN 不等于可重新授予。

## 9. 给 G 的 API/UI 边界

API/页面消费同一持久投影或带 revision 的查询结果。keyset 分页不逐表问 Actor；未知/失联不显示健康；取消受理不显示取消完成。用于组件开发的 fixture 只进入测试/开发，不能作为产品真实状态。

旧 /snapshot-tasks 继续保持静态快照语义。新 CDC 能力必须走明确的新任务类型和入口，不能靠一个 initializedTarget=true 绕过证明。原始 B/F/K、材料摘要、故障原因可按权限查看；凭据、原始行内容不塞进普通任务列表。

## 10. 兼容与验证清单

- 旧 Snapshot/Workflow/Checkpoint 类型、命名空间和 revision 含义不改。新控制意图使用新版本类型/索引；旧 bootstrap progress 的读取需要明确迁移适配。
- 不增加多租户、跨实例切主、DDL、GC、共享 Reader HA 等本轮未要求的功能。
- E 是提议，不构成已通过的 API、重启或 70 万表实验结果。

| 用例 | 可判定结果 |
|---|---|
| 同 requestId 同定义重复提交 | 同 taskId/代/执行，不重复写 B，不重复认领目标 |
| 同 requestId 换定义 | 明确冲突，不部分更新 |
| Reader 未就绪并发新增/取消 | 无伪 ready；命令幂等，源端副作用按 B 方案证明 |
| 控制命令提交、执行尚未激活时重启 | 意图仍在；不唤醒全部 Actor，按预算恢复 |
| 激活 T 后 J 前重启 | 显示 ACTIVATING，校验 T 后再持久推进 |
| 请求取消、T 尚不确定 | 不显示 CANCELED，不释放未知 grants |
| 暂停后再请求运行/取消 | 明确许可或拒绝原因；不重置执行身份和恢复预算 |
| 空闲 tail 与新初始化同时存在 | 无每表阻塞线程；全量与实时预算不互相饿死 |
| 大量冷记录与分页查询 | 活动 Actor/线程/连接不随冷记录线性增长 |
| 旧静态快照任务和持久事件 | 原命令、旧恢复与查询行为保持，另做兼容验证 |

首轮集成验收使用有限真实表动态 5→7、部分取消、受控重启、真实 Journal/Sink 逐字段对账。70 万元数据、70 万真实源表目录、长期活跃吞吐分别验收。每项给出生产源码、测试设施、环境与证据摘要，不互相升级结论。

## 11. 需要在实施前确认的选择

- 本稿建议“冷索引 + 表级控制 Actor + 有界执行步骤”；不是直接扩容当前阻塞 Runner。
- B 当前建议 Reader 启动中明确拒绝新订阅；E 建议平台冷队列可保留用户意图并稍后重试。平台排队不冒充 Reader 已接受 B。
- PAUSED 的恢复/取消许可、维护停机与业务取消的区别、发布后暂停的业务展示行为需要确认；旧类型不能直接改变语义。
- 具体 DTO/SQL migration、资源额度持久化与旧 bootstrap 适配在 F/G 实施方案中定稿，当前没有新生产入口。
