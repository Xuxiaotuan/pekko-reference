# 四：批转流恢复的证明链——固定范围、封存身份与激活边界

[系列导读与验证范围](README.md) · [1：目标架构](01-why-pekko-for-resident-cdc.md) · [2：批转流源码](02-source-and-recovery-contract.md) · [3：真实实验](03-real-mysql-journal-failure.md) · [4：恢复协议](04-protocol-and-validation.md) · [5：自建与复用](05-pekko-vs-flink-cdc.md) · [Flink CDC 深入系列](https://xuyinyin.cn/flink-cdc-series/)

[A/B 补测](../../experiments/2026-10-27-ab-followup/README.md)中，源 marker 回复不确定时保留原身份并暂停；观察连接断开与权限缺失单列为真实 MySQL 组件结果。取消先于 marker 绑定的样本中，订阅未复活，但代码仍可能发出 `B_BOUND` 观察事件；判断持久绑定须读取实际屏障。尚缺的固定门、旧状态证明和跨 JVM 资源准入仍为 `evidence_incomplete`。

> **文章修订：2026-10-27。** G1—G4 的原实验链接保留原冻结版本；启动门已提交于 [`74b6d9a`](https://github.com/Xuxiaotuan/pekko-reference/tree/74b6d9ad1ebcfe19a786f4e1869aed276a63bb52)。有界 step、SQL owner/grant 与受管释放按本轮候选源码论证；其冻结版本及真实验证结果见[平台实验报告](../../experiments/2026-10-27-cdc-platform/README.md)。源码分支不是自动恢复的实验结果。

五表增加到七表时，f/g 的初始化需要跨越多个数据库事务、一次或多次进程故障。本文回答：哪些持久事实足以让恢复安全地继续，哪些事实缺失时必须暂停？我们先推导 G1—G4，再说明有界调度为什么不能削弱这些数据协议。第三篇的 I08/I11/I16/I21 给出指定窗口的证据；旧启动反例与新版安全拒绝，则划出了 G0 尚不能无条件成立的边界。

这里讨论固定 Schema、单列有符号整数主键、最初为空且独占的事务目标。结论限于选定表的行状态与当前页/组提交契约；不包含跨表事务原子可见、任意外部副作用或多 owner 接管。

<a id="g0"></a>

## 1. G0：先声明所有恢复推理依赖的条件

| 条件 | 内容 | 当前证据或缺口 |
|---|---|---|
| A0：捕获连续 | B 之后所需的完整组与 marker 持续进入 L；恢复不跳过尚未捕获的区间 | 旧 INITIAL-D 反例保留；新版启动门拒绝已识别风险，不追溯补齐旧缺口 |
| A1：输入可解释 | 稳定源谱系、InnoDB、ROW/FULL、`binlog_order_commits=1`、固定 Schema、合法单主键 | 当前校验与首版类型限制；不等于支持 DDL 演进 |
| A2：窗口可定位 | B、U、spec 和本次 LOW/HIGH 可核对，所有窗口 HIGH 在固定 F 之前 | 固定范围、fence 与完成链审计 |
| A3：效果可重放 | 页与组身份稳定、完整行替换/删除按规定顺序应用；证据与效果同目标事务 | 当前目标 receipt/组账本；不允许额外目标写者改写业务行 |
| A4：材料可恢复 | J、L、RAW/FINAL、manifest 和目标证据保留且可验证，执行不发生未隔离重叠 | 缺失/冲突应暂停；本轮增加持久 grant 与受管释放，未扩展新链路自动接管或 GC |

A0 不是一句“代码应当如此”的声明。新版可以在创建 Engine 前拒绝旧 B+INITIAL，然而已经静默越过变化、后来又写回完成态 D 的旧历史，并不一定可由现有证据识别。因此下面仍是满足前提时的推理和已检验的局部性质，不能宣称全部历史都已连续。

T、J、D 和 L 分别描述目标效果、Actor 接受进度、SDK 恢复上下文和已保存输入。它们有不同坐标，无法用一个最大值修齐。源变化若从未进入 L，现有 ACK 链与目标账本都不能把它补出来。

## 2. 哪些状态参与决策，哪些资源不能持久化恢复

[BootstrapProgress](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapProtocol.scala#L44)主要保存：

```scala
upper, planned, nextLower, index,
attempt, window, manifest, appliedPage,
fence, deltaAfter, batchSequence,
completionRoot, recoveryAttempts, cancelRequested, failure
```

U 和 spec 回答“原来负责哪个范围”；manifest 回答“可否重交同一输出”；appliedPage 和 deltaAfter 回答“J 已经接受哪项效果”。相应的 RAW/FINAL、范围链和目标 receipt 在各自存储中核对。

数据库连接、RR 视图和 SDK 提交句柄不在 Journal 里。JVM 重启后重新创建这些资源，必须重新证明它们与持久事实的关系。当前正常阶段为 `QUEUED → SCANNING → REPLAYING → ACTIVATING → STREAMING`，另有取消和暂停分支。

<a id="g1"></a>

## 3. G1：丢失读视图后，为什么只能保留范围整段重读

要求是：同一个逻辑范围在失败前后不改变，未封存输入不能被当成跨读视图可拼接的结果。

[原实验版本的恢复分支](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala#L202)检查窗口 manifest。没有封存时，新 attempt 登记 `spool.spec(w)` 的原值，appliedPage 归零，再读取原范围；旧 RAW 与失败材料保留。

推理分三步：RR 的一致性属于旧连接事务；故障后只剩若干 RAW 页，并没有“剩余页面属于同一时刻”的证明；这些 RAW 尚未对目标产生 baseline 效果，因此可以放弃本 attempt，重新夹住 LOW/HIGH 并整段读取。重算范围会改变原责任边界，接续旧游标则会把不同视图拼在一起。

新调度路径还要处理同 owner 的缓存替换。`step` 正常返回、JDBC close 已确认，只能证明这一轮资源可让出；它没有让旧 RR 事务跨越 Runner 对象重建。当前 [`BootstrapStepContinuation`](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapStep.scala)区分两个条件：

```text
有 window + 没有 spool manifest + J 未接受 manifest 且 appliedPage=0
    → 原 RAW 放弃，原 spec 新 attempt 整段重读

J 已接受 manifest 或已接受页效果 + 对应 seal 丢失
    → 材料破坏，暂停；禁止回源重定义该页
```

第一个分支避免把“干净让出”误当成“读视图还在”；第二个分支防止以重读掩盖已接受输出丢失。clean continuation 本身还须属于同一 runtime owner，并与完整 J 相符；单个 phase、revision 或最新游标都不足以替代这项核对。允许的并发变化仅是保留全部数据进度的取消接受。对象重建并非新的源故障，不能无依据地再扣一次相同恢复预算。

[I11](03-real-mysql-journal-failure.md#e-i11)直接检查这项性质：边界键 32 被删除后，新旧 spec 仍为 `(null,"32"]`，窗口由 `a_0_0` 变为 `a_0_1`，最终 378 字段相等。范围不漂移与终值相等是两项断言，缺一不可。

<a id="g2"></a>

## 4. G2：封存身份怎样跨过 T 已提交、J 未接受的缺口

要求是：一旦输出封存，同一窗口/页 identity 只允许同一 manifest 和内容；T 领先 J 时，恢复核实原效果，不重新定义那项效果。

[BootstrapStore.seal](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapStore.scala#L76)在状态事务保存 FINAL 页和 manifest；[BootstrapTarget](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapTarget.scala#L44)在目标事务内核对认领、查询 receipt，必要时一起提交页 DML 与 receipt。Runner 随后才更新 J.appliedPage。

因此目标事务只有两类已知结果：未提交时没有这项 receipt 与效果，重交原页；已提交时二者同时存在，核对同一 payload 后不再次执行该页 DML。若客户端不知道 COMMIT 结果，仍协调同一 identity，不能换一个内容后重试。已有 receipt 但内容不同，意味着证据冲突，应停止。

[I16](03-real-mysql-journal-failure.md#e-i16)的真实缺口中，原 receipt 存在、J 仍停第 0 页，恢复产生 `BASELINE_T new=false`，再推进其余页。这支持该指定窗口的页重交机制。完整组账本在 B→F 与 tail 中承担对应职责，交付重新分批不应改变组身份。

| 观察到的故障事实 | 当前协议要求 |
|---|---|
| COMMIT 结果未知 | 用原 identity 核实/重试，遵守恢复预算及释放证据 |
| J 明确拒绝 | 保留上下文暂停，不把明确拒绝当作结果未知消耗重试 |
| J 声称页已接受，目标证据缺失 | 验证失败；不能直接从下一页继续 |
| Future 超时或连接 close 未确认 | 不能推定在途事务停止；释放未知保留占用 |

这没有把一个源事务整体包成一个目标事务。baseline 可能已经包含某行的新值，B→F 还会按组应用更早再更新的行值；目标副作用的次数不是本文的通用承诺。

<a id="g3"></a>

## 5. G3：固定 F 与激活为什么需要两个提交点

要求是：恢复不能移动原有限回放终点；只有完整范围与目标应用证据审计通过，J 才接受 STREAMING。

每完成一段，`spool.complete`接到范围摘要链，J 推进 index/nextLower。全部范围完成后登记固定 operation 的 F，J 进入 REPLAYING，起点是原 B。重复请求核实原 F，不选择当前更新的位点替代它。

[replay](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala#L156)先提交目标完整组，再推进 J.deltaAfter；没有剩余 GROUP 时，游标明确落在 F 帧序号。空表也不需要伪造一个业务组。随后[audit](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala#L87)核对范围连续、封存证明、目标页 receipt 和 B→F 组账本；它审计保存的证明，不是重新全量查询源表对账。

Runner 先写激活 T，再由 J 接受 STREAMING。若在两者之间崩溃，恢复重新审计、复用同一激活 receipt，不能凭它单独声称业务已经发布。I21 切点的 J 仍为 ACTIVATING、F 序号 54；恢复保留证明后转入 STREAMING，继续到 L 序号 57。这就是 G3 的代表性实测窗口。

<a id="g4"></a>

## 6. G4：完整 B→F 回放为何能够得到 F 的行状态

令 `S_F(k)` 为源在固定 F 边界处主键 k 的行或不存在状态，`E_(B,F]` 为应保留区间内的变化。baseline 由不同窗口读出并回填，它不是全表同一个 RR 时刻。满足 A0—A4，并完成有限 B→F 回放、尚未应用 F 之后事件时，可按每个主键分两类推理。以下证明这个切面的行状态；G3 激活接受这份基线，STREAMING 随后继续推进，不承诺任意时刻查询目标都得到 F 快照，也不证明固定完成时限：

**区间内没有触及 k 的事件。** 该键的存在与行内容在 B 到 F 之间不变。被固定范围覆盖的快照读取能保留这个值；源与目标都不存在的键保持不存在。初始空目标排除了无来源的额外旧行。

**区间内存在触及 k 的事件。** 不论 baseline 暂时包含哪个窗口时刻的值，按顺序回放至最后一次触及 k：完整 after image 把该键设为对应行，DELETE 把它设为不存在；在 F 之前再无后续变化，因此这一结果就是 `S_F(k)`。主键修改由 d/c 成员分别处理两个键，必要成员不能丢失。

这说明采用完整行替换/删除且身份、顺序正确时，B→F 的最后一次有效作用可消除混合 baseline 时刻带来的差异。窗口 fold 负责局部输出，完整回放负责所有窗口间和 U 之外的后续变化；[I08](03-real-mysql-journal-failure.md#e-i08)检验了其中双向迁移的一个实例。

推理依赖必要事件真正进入 L，baseline 与原始组去重命名空间分开，目标提交不发生未隔离重叠，并且激活之前不向业务发布中间状态。若目标有其他写者、使用增量累加而非完整替换、提前清掉账本，或漏掉一笔源变化，这个结论都不能沿用。

## 7. INITIAL-D 反例具体破坏了哪一步

[E0](03-real-mysql-journal-failure.md#e-startup)中，源确实存在 id1 的更新，但它没有进入这次可回放的 L；SDK 从 INITIAL 恢复时重新选了更晚起点。按“L 里最后一次触及 k”的推理仍会得到旧值，因为输入集合已经不完整。后续 id2 witness 到达，不会修复缺失输入。

当前顺序也必须分开理解：

```text
完整组/Schema/marker持久到L → cover记录ACK引用
→ 原始连续回调句柄调用 → SDK后续flush → 实际D
                  └→ 表消费者应用目标T → Actor接受J
```

[accept](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/SharedMySQLCapture.scala#L181)先执行 `store.cover(...)` 再调用原始句柄；covered 不等于 D 已落盘，ACK 链完整也不等于未观察区间连续。已有 B 而原始 D=INITIAL 时，必须在 SDK 重选点前作连续性判定，不能用之后变成非 INITIAL 的 D 覆盖启动事实。

[`CaptureStartupGuard`](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/CaptureStartupGuard.scala)已经把这项判定接到 Engine 创建之前。[A1/A2 真实实验](../../experiments/2026-10-27-capture-admission/README.md)确认旧 PREPARED/INITIAL 与 B-bound/INITIAL 被拒绝，Engine 创建次数为 0；后者的旧五字段差异保留。新 B 另需本 attempt 的生命周期、原始 ACK 和完成态实际 D，而不是仅凭旧 Schema 或一次 start Future。

这闭合的是“能否从这些已识别状态继续”的准入决策，没有证明旧缺失事件被补进 L。若旧命名空间已静默跳点再写出合法完成态 D，现有引用校验只能核实已交付输入，不能重建从未交付的变化。源 marker COMMIT 不确定也保留原 operation 暂停，不重新写一个 B 来逃避判断。

## 8. SQL 调度资格如何衔接 T/J/D，而不成为另一套数据进度

把阻塞 `run` 拆成 `step` 后，持久控制索引记录 task 定义、期望状态和待执行条件。`BootstrapJournal` 继续记录数据协议的接受事实。两者职责不同：SQL 中“已领取”不是 J 已接受，“已初始化”投影也不能替代目标激活 receipt。

[`CdcTaskStore`](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/cdc/CdcTaskStore.scala)先在事务中核对 owner、预算和候选，写 ACTIVE grant，然后才交给有限 worker。worker 使用真实 Journal、L 与目标执行一步。确认本轮资源释放后，事务同时记下 clean progress、让出 grant 和安排下次工作；结果未知时 grant 进入 UNKNOWN，仍计入预算。

对每个资源池，安全不变量是：

```text
持久 ACTIVE grant 数 + UNKNOWN grant 数 ≤ 配置预算
发起实际 step ⇒ 存在属于本 owner、本 task 的 ACTIVE grant
归还预算 ⇒ 本轮资源释放已确认，且 disposition 已持久化
```

内存里 worker 已结束，不能跳过最后一条持久 disposition。若它在 J 已接受之后、grant 结束之前崩溃，新 runtime 看到旧 ACTIVE/UNKNOWN，不能凭 J 看起来完整而领取替代资格。当前 owner 启动要求上一个状态为 STOPPED 且无 ACTIVE/UNKNOWN grant。拒绝自动接管是这一版的安全边界，不是 HA 已经完成。

clean continuation 是这种让出协议的附属证据：它只对原 owner 生效，完整内容须与真实 J 匹配；跨 owner 恢复不能拿 SQL 缓存的 clean 标记跳过重验。维护停止也不是取消：它不写业务 K，不把 desiredState 改成 CANCELED。只有步骤排空、Journal 停止、Reader 物理释放及 grant 清理都确认，owner 才能持久为 STOPPED。

### 空闲实时任务怎样避免重新占满 worker

已完成审计且无新组的 tail 可以退出活动会话，不保留每表循环。调度器按 capture 有界扫描 L，新 GROUP 对应 task 时，在同一事务更新 `wake_seq`、待执行标记与 dispatch 游标。task 完成时再比较 wake_seq 与本轮接受的 deltaAfter。新变化若在执行期间抵达，不应因完成旧 grant 而丢掉唤醒。

这里的 wake_seq 只回答“还有工作吗”；它既不覆盖 J.deltaAfter，也不推进 D。实现用原有完整组、T/J 和 ACK 前缀保证数据语义，SQL 索引只安排谁先执行。本轮 P02 已实测十表持续同步后新增第十一表并逐字段相等，活动步骤不超过 snapshot=2 加 tail=2 的四个预算；这支持指定负载下跨越旧八会话限制，不证明任意负载都不饥饿。

## 9. 取消、close 和资源可复用是三件事

取消是持久业务命令。未执行的 QUEUED 任务可以在尚未获得 execution/J/B 时终止，保存独立的 `PREEXEC_CANCEL` 证明，初始化证明仍为空。已经绑定 B 的订阅则经 K 结束捕获，并核实在途 T/J 与资源释放。取消不删除目标，未激活目标可能保留部分数据；PAUSED 没有通用 Resume，不能强行改状态逃过原证据。

停止 Reader 是另一个协议。[受管释放](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/CaptureReleaseGuard.scala)把事实拆开：

| 事实 | 要证明什么 | 证明不了什么 |
|---|---|---|
| 本地终止 | SDK close 已调用，runner/executor 确实结束 | completion callback 成功不代表服务端 binlog dump 消失 |
| 服务端释放 | 原 observer 锚点仍有效；专属 source/state 账号下无遗留会话和事务 | 新连一次查询为空不能证明原观察连续，权限不足也不能判空 |
| owner 释放 | 原 owner 连接已关，并在关闭后再次核实服务端 | 本地方法返回不等于命名锁与服务器资源均释放 |
| reusable | 上述事实及 observer 关闭都成立 | 仅超时、仅无事务或仅本地线程退出都不成立 |

[`CaptureResourceObserver`](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/CaptureResourceObserver.scala)用启动前打开、禁止重连的源/状态连接绑定 server UUID、connection id 和 canary lock，并要求 PROCESS 可见性。账号专属于该 capture；source 的 marker/snapshot 短连接也在其观察域内。全局 Journal/控制库账号与该 capture 的专属状态账号分离。专属账号覆盖 SDK offset/history、Reader owner 和全部 L 连接；L 仍在原 controlState 数据库，改变的是连接凭据。无法确认归属的孤儿事务保守保留未知。

observer 锚点丢失、权限不足、服务端仍有会话时返回 UNKNOWN，不能以另连一个 observer 或等待超时恢复额度。同一 close operation 可以在较晚确认真实释放后变为 reusable，但不可不断创建新 close 线程。兼容旧 `close` 的本地行为和受管 `requestClose/refreshRelease` 必须单列；旧接口本地结束不能被调用方升级为资源复用许可。

这个账号约束有真实反例。旧装配让 L 使用共享 control 账号，观察器却只识别 SDK-state 账号；在代理保留的 L 事务仍 RUNNING 时，旧 Kernel 返回 reusable，并将平台 owner 置为 STOPPED。修复把同一 L URL 的连接纳入专属账号，真实新样本在事务结束前保持 UNKNOWN 和 owner 占用，实际回滚后才可复用。该样本没有执行目标和 Journal，不是数据恢复对账；它验证的是 G0 中不允许遗漏旧执行资源的前提。原失败与修后证据按不同版本保留于[平台报告](../../experiments/2026-10-27-cdc-platform/README.md)。

健康同样需要时间边界。已获得 B、已经初始化、当前可捕获和资源已释放是独立事实。capture 的 HEALTHY 还要求当前 SDK Connected 与五秒内的本 capture 观察；过期为 STALE。一个域观察阻塞，不能让另一个域无限保持旧健康。API 请求失败时，控制台把缓存详情的健康遮为 UNKNOWN 并禁用取消，历史初始化证明仍可保留。历史 proof 和当前动作许可不能混用。

本轮 P06/P07 从两个相反方向检验运行资格：只有真实 STOPPED 后的新 owner 才继续；ACTIVE/UNKNOWN 遗留时新 JVM 创建 Engine 数为 0。两个真实 close 样本还保留本地已停而服务端仍有会话的阶段，之后才确认同 operation 可复用。这些样本分别支持其边界，不证明未知状态能够自动消除。

控制面的过载也不能被当成存储事实。一次已复现的问题是：HTTP 请求占满控制预算后，调度尚未进入执行体就收到 BUSY，却把健康 owner 永久改成 UNKNOWN。修复仅让这类前置拒绝等待下次调度；已获准工作内的 BUSY 或 SQLException 仍暂停。三个有界 H2/假 Kernel 检查支持这个分类，不能将它说成 MySQL 故障恢复实测。

G1—G4 由此仍是一条条件性证明：范围固定，输出封存，效果可核实，终点不移动，完整回放后才激活。新增调度与释放协议负责保护它的运行前提，不自动扩大它的输入类型、恢复范围或规模结论。[本轮平台实验](../../experiments/2026-10-27-cdc-platform/README.md)分别记录源码结论、定点实测和未验证边界；尚未取得结果的路径保持 `evidence_incomplete`。

## 原始事实比观察事件更严格

一次 A14 定点实验中，源 marker 的 COMMIT 返回未知且事务仍在途，Store 取消却先将待定订阅标为 CANCELED，随后源事务实际迟提交。这是验收失败。实验直接调用与 Runner 共用的 Store 路径，而非完整 Runner 的端到端复现；其他活动表的对账相等也不能修补该失败。`B_BOUND` 事件在晚到 bind 未写入时仍可能出现，应以持久订阅状态和源事务事实判断。

A12 还注入过 ACK 父引用摘要错误，并重算该帧摘要；真实重启仍返回 `CHECKPOINT_UNCOVERED`，没有创建 Engine，停写后的四行 24 字段相等。这只覆盖一种引用损坏。

B06 的 Reader 级实验暴露了另一条边界：源 marker 事务仍在 MySQL 运行时，关闭结果仍不可复用且服务器状态 UNKNOWN，但 capture 的 owner 命名锁已释放。活动表 T/J 不变且逐字段相等，这不证明事务已结束或可以接管；该样本未配置资源观察器，也没有测试平台 owner。

B09 的双 JVM 样本中，新 JVM 已安全暂停后，旧执行者才进入 STREAMING；新 JVM 仍为 UNKNOWN、Engine 创建数为 0。旧执行者继续运行后 64 行 384 字段相等；这是并存实例隔离实验，不是向新 JVM 注入所有可能的迟到消息。

A09 的未绑定 B 样本当前选择启动前安全暂停；可证明能够重放时自动继续的协议尚未完成。B02/B03 只证明指定测试门下的有界关闭行为，B05 则保留未释放观察期的失败。证据及未覆盖项见[补测报告](../../experiments/2026-10-28-ab-matrix/README.md)。

## 技术附录与证据入口

[历史源码分析与实验档案](appendices/04-group-protocol-history.md)保留本篇此前全文；[附录索引](appendices/README.md)集中提供版本、完整矩阵和原始材料入口。当前主文只保留支撑核心论点所需的实现、代表性证据和边界。
