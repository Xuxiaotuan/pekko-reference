# 四：批转流恢复的证明链——固定范围、封存身份与激活边界

[系列导读与验证范围](README.md) · [1：目标架构](01-why-pekko-for-resident-cdc.md) · [2：批转流源码](02-source-and-recovery-contract.md) · [3：真实实验](03-real-mysql-journal-failure.md) · [4：恢复协议](04-protocol-and-validation.md) · [5：自建与复用](05-pekko-vs-flink-cdc.md) · [Flink CDC 深入系列](https://xuyinyin.cn/flink-cdc-series/)

> **文章修订：2026-10-26；代码基线 [`55fad0c`](https://github.com/Xuxiaotuan/pekko-reference/tree/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059)。** 本文依据源码与保存材料论证，没有重跑数据库。版本、验证范围和未覆盖项统一见[系列导读](README.md#全系列验证范围汇总)。

五表增加到七表时，f/g 的初始化需要跨越多个数据库事务、一次或多次进程故障。本文回答：哪些持久事实足以让恢复安全地继续，哪些事实缺失时必须暂停？我们沿当前代码推导 G1—G4，并用第三篇的 I08/I11/I16/I21 对应检验；启动反例又表明，依赖条件 G0 仍未被完整保障。

这里讨论固定 Schema、单列有符号整数主键、最初为空且独占的事务目标。结论限于选定表的行状态与当前页/组提交契约；不包含跨表事务原子可见、任意外部副作用或多 owner 接管。

<a id="g0"></a>

## 1. G0：先声明所有恢复推理依赖的条件

| 条件 | 内容 | 当前证据或缺口 |
|---|---|---|
| A0：捕获连续 | B 之后所需的完整组与 marker 持续进入 L；恢复不跳过尚未捕获的区间 | 指定正常/恢复样本有证据；INITIAL-D 反例破坏通用保证 |
| A1：输入可解释 | 稳定源谱系、InnoDB、ROW/FULL、`binlog_order_commits=1`、固定 Schema、合法单主键 | 当前校验与首版类型限制；不等于支持 DDL 演进 |
| A2：窗口可定位 | B、U、spec 和本次 LOW/HIGH 可核对，所有窗口 HIGH 在固定 F 之前 | 固定范围、fence 与完成链审计 |
| A3：效果可重放 | 页与组身份稳定、完整行替换/删除按规定顺序应用；证据与效果同目标事务 | 当前目标 receipt/组账本；不允许额外目标写者改写业务行 |
| A4：材料可恢复 | J、L、RAW/FINAL、manifest 和目标证据保留且可验证，执行不发生未隔离重叠 | 缺失/冲突/释放未知分支；没有新链路自动接管或 GC |

A0 不是一句“代码应当如此”的声明。当前实现尚有反例，因而下面只能给出满足前提时的推理和已检验的局部性质，不能宣称全部条件都已实现。

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

[当前恢复分支](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala#L202)检查窗口 manifest。没有封存时，新 attempt 登记 `spool.spec(w)` 的原值，appliedPage 归零，再读取原范围；旧 RAW 与失败材料保留。

推理分三步：RR 的一致性属于旧连接事务；故障后只剩若干 RAW 页，并没有“剩余页面属于同一时刻”的证明；这些 RAW 尚未对目标产生 baseline 效果，因此可以放弃本 attempt，重新夹住 LOW/HIGH 并整段读取。重算范围会改变原责任边界，接续旧游标则会把不同视图拼在一起。

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

这是[启动准入提议](../../development/specs/2026-10-25-shared-reader-startup-recovery-design.md)要解决的内容，尚未进入当前生产实现。这里的推导保留反例，而不是把期望行为写成已有保障。

## 8. 取消和释放未知如何保护同一条证明链

取消不是删除目标，也不是命令一到就终止所有在途 SQL。扫描和应用在步骤边界检查取消；已在途的提交可能完成，需要核实。绑定后的订阅以 K 停止捕获，已激活目标按协议排空；未激活目标可能保留部分数据。

PAUSED 当前没有通用 Resume，也不能绕过 Journal 强行 Cancel。源捕获暂停、K 无法到达或释放未知时的操作许可，属于尚未落地的[统一生命周期](../../development/specs/2026-10-25-cdc-platform-lifecycle-contract.md)。

当前[BootstrapJdbc](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapJdbc.scala#L12)及[BootstrapReplaySink](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapReplaySink.scala#L16)会把未确认 close 作为释放未知处理，局部许可不能提前归还。这避免一项未知操作与重试无依据地重叠，但还不是跨 owner 持久配额已恢复的证明。新链路接管和 GC 必须继续保留 A4 所需的恢复事实。

G1—G4 由此构成一条条件性论证：范围固定，输出封存，效果可核实，终点不移动，完整回放后才激活。当前 G0 仍有真实反例，因此整体验收保持 `evidence_incomplete`；各性质的已测范围以第三篇和验证表为准。

## 技术附录与证据入口

[历史源码分析与实验档案](appendices/04-group-protocol-history.md)保留本篇此前全文；[附录索引](appendices/README.md)集中提供版本、完整矩阵和原始材料入口。当前主文只保留支撑核心论点所需的实现、代表性证据和边界。
