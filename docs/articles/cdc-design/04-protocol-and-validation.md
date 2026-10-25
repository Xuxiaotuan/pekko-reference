# 四：窗口与进度怎样恢复——封存、T/J/D、确认前缀与取消

[系列导读与验证范围](README.md) · [1：目标架构](01-why-pekko-for-resident-cdc.md) · [2：批转流源码](02-source-and-recovery-contract.md) · [3：真实实验](03-real-mysql-journal-failure.md) · [4：恢复协议](04-protocol-and-validation.md) · [5：框架比较](05-pekko-vs-flink-cdc.md) · [Flink CDC 深入系列](https://xuyinyin.cn/flink-cdc-series/)

> **当前主文基线：2026-10-25，源码 [`55fad0c`](https://github.com/Xuxiaotuan/pekko-reference/tree/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059)。** 文章依据当前源码和已保存实验重排；改稿不构成数据库重跑。整体验收仍为 `evidence_incomplete`。源码结论、真实实验、条件性推理和设计提案分别标明；历史版本放在文末可展开附录，不能套作当前能力。

故障恢复首先要回答“哪些事实已经持久化”，然后才决定下一步执行什么。当前批转流新增了范围 spec、RAW/FINAL、manifest、baseline receipt 和激活证明，T/J/D 三份进度仍存在，中间还多了一份可独立回放的 L。把所有状态压成“最后读到第几页”，会丢掉恢复所需的因果关系。

本篇按当前实现解释恢复协议，再把尚未落地的启动准入和平台生命周期单独列出。事件身份、完整组与原始回调前缀的历史证明保留在附录；它们是新链路复用的基础，不等于所有新增故障窗口已经完成。

## 1. 六类状态分别记什么

| 事实 | 当前存放位置 | 恢复时回答的问题 |
|---|---|---|
| 表执行身份及阶段 J | Actor Journal 的 BootstrapProgress | 属于哪次初始化，已接受哪一页/哪个 L 游标 |
| 范围 spec | bootstrap windows | 原本负责哪个 `(lower,upper]`，不能因源边界删除而重算 |
| RAW/FINAL 与 manifest | bootstrap pages/windows | 是否已有不可变输出，可否重新交付同一内容 |
| B/LOW/HIGH/F/K 与原始组 L | CaptureStore | 哪一代订阅、哪些日志和覆盖引用仍可回放 |
| 业务效果与目标证据 T | 页 receipt、完整组 ledger、激活 receipt | 某项效果已提交还是需要重试，内容是否冲突 |
| SDK offset D | Debezium JDBC offset store | Reader 重启会加载什么上下文，包括 snapshot 阶段 |

这些事实可以跨不同事务、不同数据库。J 的 revision、窗口 index、L sequence 和 D 的 file/pos/GTID 也不是同一种序号。不能取最大值“修齐进度”。

## 2. 状态机与进度字段：保存恢复决策，不保存活连接

[BootstrapPhases](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapProtocol.scala#L8) 的正常路径为：

```text
QUEUED → SCANNING → REPLAYING → ACTIVATING → STREAMING
                      取消路径：CANCELING → CANCELED
                      失败路径：PAUSED
```

[BootstrapProgress](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapProtocol.scala#L44) 保存核心字段：

```scala
upper, planned, nextLower, index,
attempt, window, manifest, appliedPage,
fence, deltaAfter, batchSequence,
completionRoot, recoveryAttempts, cancelRequested, failure
```

`upper` 是冻结 U，`nextLower/index` 是已接受的范围完成进度；`attempt` 区分同范围的新读视图；`manifest` 是封存内容摘要；`appliedPage` 是目标页确认；`fence/deltaAfter` 指定有限或持续日志回放。数据库连接、RR 事务和 SDK 提交句柄都不在 Journal 中，JVM 重启不会恢复它们。

因此这里的“独立恢复”是表执行可以依据持久化的范围和材料重建，窗口不必从整表第一行重来。它不意味着每个 Split 都有独立 Actor、独立 owner 或并行调度器；当前 Journal 仍是单表串行状态机。

## 3. 未封存窗口：原范围保留，旧读视图放弃

RAW 页保存过，不代表这次 RR 视图以后还能继续。重启或可重试中断时，[run](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala#L202) 检查 manifest：

```scala
if (spool.manifest(w).isEmpty) {
  val next = id + "_" + p.index + "_" + (p.attempt + 1)
  spool.register(next, spool.spec(w))
  update(_.copy(
    attempt = p.attempt + 1,
    window = Some(next),
    manifest = None,
    appliedPage = 0
  ))
}
```

旧 spec 被原样复制，旧 RAW 和失败材料保留，新 attempt 从范围起点重新读取，并产生自己的 LOW/HIGH。旧页不能拼到新读视图前面，也不能因原上界行消失而改变范围。

未封存 RAW 还没有应用到目标，因此废弃它们不需要回滚业务目标。若程序在 fold 过程中崩溃，恢复也先走这个分支，而不是信任未完成的内存 Map。

## 4. 已封存窗口：原内容可重交，目标先于 J

manifest 与 FINAL 页在状态事务中封存。恢复先验证 spec、LOW/HIGH、RAW 摘要和 L 组摘要链，再核对 FINAL 页摘要。任一必需材料缺失/损坏都拒绝继续，不能改成“再查一次源库补上”。

[目标页提交](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapTarget.scala#L44) 的结构是：

```text
锁定目标认领
→ 查同 operation 的 receipt
→ 已有且内容一致：复用证明
→ 没有：应用页 DML，并在同一事务插入 receipt
→ COMMIT
→ Runner 更新 J.appliedPage
```

目标只认同一 bootstrap、window、page、manifest 和内容。页身份与原始 CDC group ledger 分开，因此 baseline 的存在不会吞掉后续 B→F 的必要日志。

| 故障窗口 | 当前恢复动作 | 必须保留的证据 |
|---|---|---|
| 目标页事务未提交 | 重交原 FINAL 页 | 同 manifest、页摘要和目标事务结果 |
| 页 T 已提交，J 未接受 | receipt 核实相同效果，随后推进 J | 原页 identity、目标 receipt、J 前后状态 |
| 页 J 已接受 | 先验证相应 receipt，再从下一页推进 | J.appliedPage 与原封存内容 |
| 目标 COMMIT 结果未知 | 依故障分类核实/重试同一身份；不能换内容 | 实际目标账本及连接释放事实 |
| J 明确拒绝 | 保留上下文暂停，不当作结果未知消耗重试 | 拒绝原因、已提交 T 和原进度 |

这是各组件的协议，不是跨库原子提交。结果未知必须用原身份协调事实，不能靠把 J 改到 T 的“最大序号”消除分歧。

## 5. 已完成范围与 F：恢复不能移动有限回放边界

每个完成窗口通过 `spool.complete` 接到完成摘要链，随后 J 推进 `nextLower/index/completionRoot`。审计检查 ordinal 连续、parent root、相邻范围边界和页 receipt，防止缺段被当成全量结束。

全部范围完成后写固定 operation 的 F。若 F 已存在，重试识别原绑定，不选择当前更晚位置作为替代。J 进入 REPLAYING，起点仍是原 B，终点仍是这个 F。

[replay](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala#L156) 使用原始完整组进行目标应用，再推进 J.deltaAfter。SDK 回调大小变化只改变本次交付分批；目标去重身份仍来自稳定组，不能改成“这次收到的第几批”。完整物理行组包含主键修改的 d/c 成员，不等于源事务的所有行一起提交。

有限回放没有剩余 GROUP 时，J.deltaAfter 设成 F 帧序号，再进入 ACTIVATING。不能取“最后一个业务组”冒充 F，尤其空表或静默区间中可能根本没有业务组。

## 6. 激活的两个提交点，与持续恢复

[audit](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala#L87) 验证范围链、封存输入/输出及目标账本后，Runner 先写目标激活 receipt，再由 J 接受 STREAMING；两处之间再次检查取消。

若激活 T 已提交、J 还停在 ACTIVATING，新 JVM 重新审计，复用同一激活证明，再尝试推进 J。若 J 已是 STREAMING，当前实现恢复时会再次审计初始化证明，并检查 F 之后直到 J.deltaAfter 的目标组证据与 L 是否保留。

所以激活证明不仅是一个布尔值：它依赖被引用的窗口和 L 仍存在。未来 GC 必须先证明恢复不再需要这些材料；目前没有自动 GC，不能为了让磁盘增长曲线好看而删掉历史。

## 7. L、原始 ACK 与 D：表目标可以落后，捕获连续性不能凭空补

共享捕获把原路径拆为两条：

```text
完整组 / Schema / marker → L 覆盖证明 → 原始回调前缀 ACK → SDK flush → D
                         └→ 各表按游标读 L → 目标 T → Actor J
```

原始 SDK ACK 不等待每张表的目标都完成；它依赖原始回调的记录已获得完整持久覆盖。半组先保留，允许回调返回，让后续 d/c 成员抵达；完整后才能推进连续前缀，不能跳过前面尚未覆盖的回调。

当前 [accept](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/SharedMySQLCapture.scala#L181) 的关键顺序为：

```scala
val ready = pending.head
assertOwner()
store.cover(ready.offset, ready.references.toVector)
ready.handle.markProcessedAndFinished()
pending.dequeue()
```

`cover()` 持久化 ACK 帧和引用；句柄返回与 D 落盘依然有间隙。T/J 可以从 L 独立追赶，D 可能落后；但如果 SDK 恢复时从 INITIAL 重选起点，未曾进入 L 的变更不在任何 ACK 引用链里。已有材料完整不证明一个未观察区间也完整。

这正是第三篇真实五字段反例的边界。当前源码尚未实现新的启动连续性门；不能把“D 有历史 ACK 证明”理解为“任何 SDK 重选点都安全”。

## 8. 取消、暂停与释放未知

初始化中收到取消，扫描每页检查标志；观察到取消后不再开始下一页；已经在途的目标提交可能完成，需要核实其结果。对已绑定订阅，当前 `cancelStep` 请求 K；若目标已经激活，会排空相应已捕获区间再完成。未激活目标可能保留部分初始化数据，取消不负责删除业务表。

暂停与取消不是同义词。当前 Journal 中 PAUSED 为终态，没有通用 Resume，也不能绕过它强行 Cancel。怎样在捕获暂停、K 无法到达或释放未知时处置，需要[生命周期提议](../../development/specs/2026-10-25-cdc-platform-lifecycle-contract.md)单独定义；那份设计不是现有 API。

[BootstrapJdbc](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapJdbc.scala#L12) 对 close 失败标记释放未知并保留自己的许可。[BootstrapReplaySink](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapReplaySink.scala#L16) 还检查真实连接 close 是否确认；等待 Future 超时不等于事务已经取消，Runner 不能据此释放写入额度并启动重叠尝试。

这些是当前局部预算的安全行为，不是跨进程持久配额已经恢复。短 Reader 实验的服务端 dump 延迟消失进一步表明：JVM/进程组结束与远端 MySQL 资源结束仍须分别取证。

## 9. 当前实现、条件性推理和下一步设计

**源码结论：**未封存整段重读、已封存内容复用、T 后 J、固定 B/F、完整组重放、激活双提交点及释放未知有明确实现。

**真实结果：**第三篇的 61 项初始化检查覆盖其指定窗口；确定性启动探针仍出现越过变更。不能把前者写成所有恢复成功，也不能因后者就把已通过的窗口内核写成完全未实现。

**设计提案：**未 ready 的新订阅明确拒绝，已有 B/commit unknown 且原 D=INITIAL 时在重选点前暂停；保留原始 D 分类，不用后来的非 INITIAL D 覆盖历史风险。候选 heartbeat 必须先证实真实 callback、覆盖和实际 D 的关系，不能凭配置名宣布 ready。

**未验证：**启动新契约的完整矩阵、DDL、跨 owner 接管、日志回收、大规模索引/活动调度和长期恢复成本。旧持久化类型继续保留，新 BootstrapProgress 不会把旧 payload 自动迁成新的证据。

## 附录：历史源码分析、完整组协议与原始实验

以下保留本篇此前的完整技术分析，原题为“四：重新分批之后，恢复为什么仍应成立——从不变量构造 T/J/D 协议”。其中的“当前”“尚未实现”均指各段明确标注的历史版本；2026-10-25 的阶段一补充也只描述当时共享捕获，不覆盖后来新增的 LOW/HIGH 内核。当前结论以本文主文和系列验证表为准，历史失败及证据链接不删除。

<details markdown="1">
<summary>展开历史基线分析与实验细节</summary>

第三篇已经把问题缩小到一个明确边界：目标提交了一组事件，恢复后同一组事件换了回调划分，批次 ID 与内容的对应关系失效。修复不能只换一个哈希公式，也不能让 Sink 遇到账本冲突时直接忽略。

我们真正需要的是一个性质：**同一段源历史，无论怎样分成传输批次、在允许的提交窗口崩溃多少次，都应得到规定的目标效果；重复读取不应重复制造业务效果。** 本篇从这个性质推导身份、目标事务、Actor 确认和源进度的契约。

旧协议分析固定为 `d88ec80`，完整组生产实现固定为 [`65ab541`](https://github.com/Xuxiaotuan/pekko-reference/tree/65ab5410c32933d0aa3d23510e6c62252d84009b)。2026-10-19 的[身份反例](../../experiments/2026-10-19-source-record-identity/README.md)和[手动覆盖探针](../../experiments/2026-10-19-deferred-ack/README.md)先收窄设计，后续[真实 Source/Actor Journal/Sink 实验](../../experiments/2026-10-19-group-recovery/README.md)再验收指定恢复窗口。本文分别标出已实现契约、条件性证明和未验证扩展，不再把完整组路径全部列为待实现。

## 1. 先写出事件历史，才能谈“同一批”

设一个捕获分区的业务历史为有序序列：

```text
E = e1, e2, …, en
```

其中每个事件包含足以确定目标操作的内容。传输层将它切成连续批次，比如 `[e1,e2] [e3]`；恢复后可以变成 `[e1] [e2,e3]`。这些括号只描述一次交付，不应改变事件的业务身份。

若 `Apply(e, S)` 表示事件 e 对目标状态 S 的作用，理想结果是按源顺序折叠整段历史：

```text
S* = Apply(en, … Apply(e2, Apply(e1, S0)) …)
```

这里不能把 E 换成无序集合。对同一主键，先 UPDATE 后 DELETE，与先 DELETE 后 UPDATE，结果可能不同。去重解决重复执行，顺序解决新旧覆盖；两个约束都需要存在。

第一阶段先限定在：单个可排序捕获分区、一个主动 writer、固定 Schema、已完成可信初始化的目标、可继续重放的 streaming 日志、单个事务型 MySQL 目标。目标表与去重证据位于同一事务边界，且没有绕开复制协议的任意目标写入。

这些假设不是为了回避工程问题，而是为了知道证明在哪一步使用了什么。若目标被其他程序修改，重复事件被正确跳过也不能自动修复外部修改；若初始全量不可信，仅证明后续每条事件一次，也不能证明整张表一致。

## 2. 逻辑事件、完整分组与交付身份各自承担什么职责

旧 [`BatchId`][models] 对 executionId、sourceNodeId、partitionId 和序号做散列。新模式保留这个公式，但序号按完整交付递增，用于 Journal 连续性，不再承担 T 的业务去重。缺少稳定源身份的问题由 groupId/eventId 解决，不能靠增加哈希强度解决。

新协议需要区分：

| 身份 | 负责什么 | 恢复后的要求 |
|---|---|---|
| deliveryToken | 关联本读取会话的一次完整交付与覆盖信号 | 可以变化，不能作为跨进程去重键 |
| groupId | 标识一个物理行变更及其全部必要逻辑分量 | 不受回调边界影响，完整后才允许确认 |
| eventId | 标识一条可重放的源事件或操作分量 | 同一事件不变，不同事件不混淆 |
| 目标覆盖证据（设计上可称 commitToken） | 证明某完整组及成员内容已提交 | 最小实现直接保存组证据，不新增一个独立 commitToken 字段或批次收据表 |

Bridge 的 deliveryToken 保留为局部关联。生产 [MySQLCdcGroup][group-model]已定义组与成员的固定编码；T 保存完整规范化证据，J 保存本次交付的 Checkpoint；Source 另用内存 acknowledgementId 隔离会话。Actor/Engine 的持久化模型没有新增独立覆盖收据，也没有扫描 T 最大值来重建 J。

### 2.1 主键、payload 哈希和末尾 cursor 为什么都不够

表主键标识一行，不标识一次变更。相同主键可以连续更新多次；把主键当 eventId，会把后续合法更新误删为重复。

payload 哈希标识内容，也不必标识一次发生。两次合法业务事件可以写入相同值；若承诺包含审计等副作用，将它们合并同样错误。

整批末尾 cursor 只描述这一批结束时的恢复上下文。将它分配给批内所有行，会把多条事件合并；将它作为整个批的身份，则在拆批后改变。第二篇已说明 Debezium 的 source position 与 restart offset 也不应混为一谈。

2026-10-19 的真实身份实验在一笔事务中生成 39 条逻辑记录：四次同键更新、32 行单个 binlog event、一次主键修改的两个分量和一条尾部更新。实际 `[16,16,7]`、39 个单条回调和 `[39]` 的身份与规范化内容一致；从多行事件第六行之后的真实 D 恢复也保持原后缀。

去掉 op，39 条只有 38 个不同元组；去掉 source.row，仅剩 8 个。第一次与第三次同键更新的 before/after 完全相同，但位置不同。顶层处理时间 ts_ms 在重启后变化，不应混入源发生身份。这个样本支持候选字段，却没有证明所有谱系或版本都唯一。[原始记录与独立核验](../../experiments/2026-10-19-source-record-identity/README.md)

### 2.2 身份稳定，为什么仍然可能无法重放

主键修改产生 DELETE 与 CREATE。二者的 file、source.pos、source.row 相同，关联 headers 分别指向新键与旧键；完整 restart offset 也相同。真实实验只确认 DELETE 后持久化 D，再启动新 JVM，CREATE 没有重放，只有后续普通更新到达。

这反驳了“给每个逻辑成员稳定 ID，就可以逐成员 ACK”的隐含假设。事件账本只能识别再次到达的事件，无法补回读取器不再交付的成员。

因此，最小设计区分：

```text
namespace = replicationId + generation + identityVersion
groupId   = namespace + sourceLineage + db + table + file + source.pos + source.row
eventId   = groupId + componentRole
```

普通 c/u/d 是单成员组；带主键关联 headers 的 DELETE/CREATE 是双成员组。生产 componentRole 编码为 `single.c/u/d` 或有序的 `pk.d, pk.c`；上面的表达式描述组成关系，实际 ID 是固定 canonical JSON 数组的 SHA-256。配对时校验同一 groupId、分区、Schema、共享 offset，以及 newkey/oldkey 与对应 key 的双向一致性。缺少必要成员或配对冲突必须拒绝。

第一版还限定 Debezium 3.6.1.Final、ORDERED、一个处理线程、无改变记录结构的 SMT，并保留主键关联 headers、关闭 tombstone。没有 newkey 的普通 DELETE 与被错误剥离 header 的主键 DELETE，仅凭剩余字段未必可区分；不能承诺任意消息变换后仍可发现所有不完整组。

本次源谱系由固定实例和未重置的日志历史约束，不能把 server_uuid 当作完整谱系协议。生产编码已实现：装配器直接读取原始 SourceRecord headers 完成配对，Envelope v1 本身不新增 headers 字段；组包装保存明确的成员角色与规范化 Envelope。snapshot 的 r 事件在新模式中拒绝，不套用这套 streaming 规则。

### 2.3 owner epoch 与复制 generation 不能混用

owner epoch 表示当前哪一任执行者有写权限。节点接管时它应变化，但同一源事件的去重身份不应因为换 owner 而变化，否则接管本身会绕过去重。

复制 generation 则表示一次有明确初始化与配置语义的数据复制历史。重新全量装载、目标映射语义变化或重新定义基线，可能需要新 generation；普通进程恢复不应随意换 generation。一次失败后生成新 executionId，也不应自动让已提交事件失去旧去重证据。

因此可以把账本命名空间描述为“目标复制任务 + generation + 身份规范版本”，再在其中识别稳定 eventId。Schema 与转换规则参与内容解释和冲突校验；如果改变规则，必须定义迁移方式，不能仅靠当前配置重新解释旧记录。

## 3. 目标事务：把完整组的效果与证据绑在一起

以下伪代码对应 [生产组 Sink][group-sink]。目标表与组账本要求 InnoDB；generation/Schema 来自固定边界，单 writer 是部署前提，当前没有 owner fencing：

```text
解析并校验边界、全部组成员、Schema/类型，构造 DML 计划
BEGIN
  按源顺序遍历完整组：
    SELECT evidence WHERE group_id = ? FOR UPDATE
    若已有且完整规范化证据一致：跳过全组 DML
    若已有但不一致：ROLLBACK 并失败
    若没有：写入组证据，再应用全组成员 DML
COMMIT
返回本次交付的 Checkpoint（不是旧组最初的批次 Checkpoint）
```

原有 [`MySQLCdcApplySinkNode`][sink] 的事务边界得以保留，新模式改用 `pekko_sync_cdc_group_ledger`，证据包括完整边界 identity、目标表名、组身份、全部成员 eventId 与规范化内容。旧批次账本路径仍保留，但不作为新模式的去重依据。普通组的一条事件可以独立重放；主键组则先配齐 d/c，再查询完整组证据。两者都不要求重新组成旧的 16 条传输批次。

主键组的 DELETE、CREATE 和组账本在同一目标事务内提交，不保存“只完成 DELETE”的持久组状态。这只保证组内原子性，不保证整笔源事务的所有组原子可见。

检查与写入必须形成一个原子决定。若只是事务外查询“groupId 不存在”，两个 writer 都可能通过检查。第一阶段的单 writer 限制减少并发问题，目标唯一约束仍应是最终防线；引入多 owner 后，还必须结合 fencing。

遇到相同 groupId、不同成员集合或规范化内容时，整次交付回滚并沿原失败路径停止，保留诊断证据。不能选择“以新内容为准”，因为此时无法知道是身份冲突、Schema 解释变化还是源历史发生变化。当前实现比较完整 canonical 内容，不以 payload 哈希代替发生身份。若后续改用摘要，仍需固定摘要规则和版本；有限模型没有验证该替代方案。

### 3.1 从原子性推出“每个事件至多应用一次”

假设 groupId 和成员 eventId 稳定唯一，所有完整组的目标写入经过上述事务，去重证据不被提前删除。

第一次处理 e 所属的完整组 g 时，如果事务未提交，全组效果与证据都不可见，恢复可以重新处理；如果事务提交，二者同时存在。以后再遇到完整 g，账本命中且成员内容相同，跳过整组。因此，在这个假设集合内，e 不会产生第二次目标业务效果。

这条证明没有使用批次大小。所以 e 与哪些邻居一起被交付，不影响其判定。

### 3.2 “至多一次”为什么还不是最终正确

永远拒绝所有事件也满足至多一次，却完全不同步。还需要至少一次最终覆盖：源日志保留，恢复不会跳过缺口，依赖最终可用，未提交事件能再次获得执行机会。再加上正确的初始目标状态与同分区顺序，才有条件得到最终状态 S*。

如果两个不同的新事件乱序进入同一主键，即使它们各执行一次，目标仍可能错误。事件级去重也不保证整笔源事务在目标同时可见。64 行 UPDATE 被分成多个目标事务时，中间状态仍然可见；事务级一致性需要另建源事务缓冲与提交机制。

## 4. T、J、D 应该保持哪些关系

定义 `Covered(T)` 为已经有目标持久证据的事件集合；`Acked(J)` 为 Actor 宣告已确认的事件覆盖；`Skip(D)` 为按照 D 正常恢复时不再交付的必需事件。还要给允许忽略的元数据记录定义明确的“已处理”规则。

可以表达三条安全约束：

```text
Acked(J) ⊆ Covered(T)
Skip(D) ⊆ Covered(T)
每个被跳过的记录，必须有已提交或按固定规则无需应用的证据
```

这里不要求 T、J、D 每时每刻相等。T 先提交、J 稍后确认、D 更晚刷盘，是正常路径。正确协议需要能够容忍这种滞后，而不是靠降低 flush 间隔假装滞后消失。

J 仍然是工作流意图和生命周期的权威来源，例如是否启用、处于什么 generation、是否被人工停止。T 则是目标业务提交的事实来源。不能用 T 中存在一些新提交，推翻 J 中明确的停止意图并擅自继续运行。

### 4.1 为什么完成前缀不能取最大值

假设共享日志顺序为 `a1, b1, a2`，分别属于两张表。B 的 writer 已完成 b1，A 的 a1 尚未完成。取最大完成位置会得到 b1，并越过 a1。

正确前缀是：从原安全位置起，直到第一个未完成记录之前，所有必需记录都有处理结果。可记录乱序完成集合，但只有填上连续缺口，才能推进全局确认。

这里“位置”是已定义的源顺序，不是将不同 binlog file、GTID 集合或 JSON pos 随意做数值 max。过滤记录、事务边界和分区都要纳入源恢复语义。

### 4.2 ACK 完成仍不替代读取 D

第二篇已经核实 `markBatchFinished` 存在 flush 未成功却返回的源码分支。因此运行状态至少应分别记录：目标提交、J 确认、ACK 调用返回、观测到的 D 持久化。D 滞后不是立即判定数据损坏，而是意味着下一次恢复可能重复更多历史。

协议必须能消化重复，监控也应区分“数据已到目标，但源恢复点未推进”与“目标尚未收到数据”。两种状态的处理方式不同。

### 4.3 回调返回与允许推进 D，是两个不同的动作

当前 Bridge.publish 会等待确认。若先发布半组 d，再等下个回调里的 c，等待本身就会阻止配对。因此生产 [MySQLCdcGroupAssembler][group-assembler]在 Bridge 前装配完整组，尾部半组留在装配器里；只有完整组进入 Bridge。

```text
回调 A: [u0, d1]
  发布完整 [u0]，等待其覆盖
  保留 d1 和 A 的原始提交句柄
  返回 A，不调用 A 句柄

回调 B: [c1, d2]
  配齐 g1=[d1,c1]，发布并等待其覆盖
  A 涉及的 u0/g1 均完成，调用 A 句柄
  B 仍有 d2 未配齐，保留 B 句柄并返回
```

原始回调和下游交付之间不再一一对应。确认协调器保存“原始回调 → 涉及的 groupId”，只有所有组都完整且获得 T/J 覆盖，并且前面的回调已确认，才能调用该原始句柄。普通前缀可以先写入 T/J，D 则因尾部半组继续滞后。

2026-10-19 的真实探针让 `[d] [c]` 和 `[u,d] [c,d] [c,d] [c,u]` 经由测试装配器进入**未修改的生产 Bridge**，均完成交付。第二组的实际原始 ACK 为 0→1→2→3，待确认回调最多 2 个；旧 A 句柄确实在 B 回调中调用。覆盖前崩溃后 D 等于基线，恢复仍能配对；全部确认后的第三个 JVM 又读到新变更。[延迟确认原始证据](../../experiments/2026-10-19-deferred-ack/README.md)

这个历史探针的覆盖信号来自手动 CONFIRM，没有 T/J。后续生产路径才由 Engine 的真实 T→J 成功触发 Bridge 代理完成，发布等待解除后由回调线程把组记入 covered；代理本身不调用 SDK。新生产实验验证了 `[d][c]` 完整组在 T/J 缺口恢复；不能把它倒灌为历史手动探针的端到端能力。

### 4.4 确认串行化、cursor 与会话边界

固定版本 committer 的实现不是线程安全的。生产 Stream 侧只调用完整交付的无 SDK 操作代理，解除 Bridge 等待；原始 markProcessed/markBatchFinished 由装配器在有序回调路径串行执行。每次确认检查队首原始回调的全部组是否已覆盖，确认后只保留后续回调仍引用的覆盖记录。SDK 调用与 close 由 ackLock 排序：已认领调用可以先完成，关闭生效后不再开始新的原始确认。

原始 A 句柄可以在 B 句柄之前推进 D，即使 CREATE 位于 B：此时 d/c 的完整组已经覆盖。需要禁止的是“只覆盖 d 就推进共享 offset”，不是强行要求两个原始句柄同时执行。

交付 cursor 必须取已交付完整组的安全末尾，不能继续直接使用原回调 records.last；它可能是尚未配齐的 DELETE。回调观察序号与交付序号也要分离。`SourceBatch.acknowledgementId` 是内存会话标识，注册项同时核对 batchId，再关联 Bridge token；它不进入持久 cursor/Checkpoint。旧会话迟到或错误 batchId 不会消费新会话的确认项。交付序号从 J+1 继续，原始回调可以只保留半组而不产生交付。

生产装配器限制原始待确认回调不超过 2 个、每回调记录数不超过配置上限，保留内容按 16 MiB 编码体积计预算，并清除已无引用的覆盖记录。下一条记录不匹配时失败；若没有后续回调，半组保持未确认，不靠超时宣布完整。预算、配对错误、关闭等待和迟到 ACK 有单测，不能据此声称完整 MySQL 作业取消已验收，更不能把预算称为整个 JVM/SDK 内存上限。

## 5. 恢复状态机：先协调事实，再恢复交付

现有 [`EventSourcedWorkflowActor`][actor] 的恢复入口保留。最小实现不新增 Actor 协调态：J 负责执行意图、固定边界和下一交付编号，D 负责重读，T 负责确认完整组效果。实际顺序为：

```text
恢复工作流意图、固定边界/generation 与下一交付编号
  → 校验配置中的 server UUID、Schema 和已有 streaming D
  → 从 D 重放，在 Bridge 前装配完整物理行组
  → 查询 T 中各组证据：旧组跳过，新组提交
  → J 持久化本次交付 Checkpoint
  → Bridge 代理解除等待 → 原始回调连续前缀串行 ACK

单 writer 为前提；本轮没有获取/提升 owner epoch 的步骤
```

这是 Source/Sink 接入现有 Engine 的调用顺序，不是新增 Actor 状态类。最小阶段选择从 D 重放、命中目标组证据后补齐 J，不实现扫描整个目标账本的启动协调器。未确认的内存半组与旧句柄在重启时丢弃；旧协议若已把 D 推过缺失成员，新协议不能凭空补回它，不做透明升级。

若 T 已提交、J 尚未确认，当前只采用重放到达后命中组证据再确认的路径；直接查询 T 并补写 J 的独立协调器仍是后续选择。已经全命中的交付也返回当前 Checkpoint，因此不要求恢复原回调划分。

若 J 已确认、D 仍旧，恢复读取会再次交出旧事件。按新源码，稳定组身份仍可命中 T，不受新交付编号影响；但本轮没有固定这个真实窗口。它是需要后续验收的源码推演，不能从 T 已提交/J 未持久化样本直接升级为实测结论。

若目标 COMMIT 回复丢失，完善协议需要先判定结果不确定，再查询相同组证据；当前没有新增专门的结果不确定协调态或自动查询重试器。查不到不一定意味着永远没提交，查询需要遵循目标事务隔离与最终结果可观察的契约；不能在旧请求仍可能提交时无限发起不受约束的新写入。

如果源日志已清理、源谱系不匹配、证据缺失或同身份内容冲突，应拒绝未知数据并保留证据。当前校验/冲突沿原 Engine 失败路径处理，没有新增一个名为 blocked 的持久 Actor 状态。恢复的“活性”不能建立在跳过未知数据的基础上。

直接把 T 的最大 cursor 写回 Debezium offset store 并不是上述流程的一部分。T 保存的是提交覆盖，D 还包含读取器恢复上下文；未经证明的 seek 加速可能跳过事件或破坏事务内跳过状态。

## 6. 有限模型检查了什么

为了检查重分批不变性，本轮写了一个独立 Python 模型：[protocol_model.py](../../experiments/2026-10-18-series-review/protocol_model.py)。它使用六条有序事件，包含同一键多次更新、删除、重新插入和另一键更新。模型直接提供稳定唯一 eventId，目标事务以复制状态后整体提交模拟。

六条事件有 32 种连续分批方式。程序枚举原始分批、每个原批提交边界、允许滞后的源恢复前缀，以及恢复后剩余事件的全部连续分批方式。共检查 **7,696 个组合**。

所有组合都检查两件事：最终业务状态等于不发生故障的顺序执行；每个事件在目标审计列表中恰好出现一次。最终状态为 `a=3, b=8`。模型还检查同 eventId 不同内容必须拒绝，并保留三个负例：旧 after-image 覆盖新值、最大完成位置越过缺口、提前清理账本后旧事件再次执行。[实际输出](../../experiments/2026-10-18-series-review/protocol-model-result.json)

**模型结果不能升级成实现结果。** 这个旧模型没有构造真实 SourceRecord eventId，也没有建模主键双分量、跨回调装配与原始句柄的确认边界，没有执行 J 持久化、offset flush、并发 writer、DDL 或 MySQL 事务。原子性、稳定身份和单 writer 是输入假设，不是被程序发现成立的性质。

它的价值在于让第四篇的论证有一个可执行的有限检查，能够暴露算法说明中“换个批次就改变含义”的错误；完整正确性仍依赖上文推理及实际实现对假设的兑现。

## 7. 两个会破坏证明的工程边界

### 7.1 旧 owner 不会因为 Actor 被重新分配就失去数据库权限

Cluster Sharding 解决实体归属，进程内 KillSwitch 解决本进程执行取消。它们不能自动让网络分区另一侧的旧进程失去 MySQL 写权限。当前 [`registerReliableExecution`][engine-register] 的取消协调只发生在本进程。

多节点阶段需要目标可验证的 owner epoch。一个可推理的单目标实现是：更新所有权与业务提交都锁定同一条所有权记录；业务事务检查 epoch 后，在锁保护范围内写 DML 和证据；新 owner 增加 epoch 时也经过这条串行边界。

这样旧事务要么在接管前完成，要么在接管后检查失败。若只是事务外读一次 epoch，旧进程可以读完后暂停，新 owner 接管，再由旧进程继续提交，fencing 就失效了。

这只是候选实现。热点锁、接管等待、源 offset 写入权限和多目标提交仍需另外设计；没有分布式实验前，不能用一个 epoch 字段宣称防住了双写。

### 7.2 去重证据清理可能让已经证明的性质失效

假设 e1、e2 已提交，目标当前为 e2 的新值。删除 e1 的去重证据后，从老恢复点读到 e1，目标会把它当新事件应用，造成回退。模型已经给出这个最小反例。

因此清理水位不能只跟随最近 D。还要考虑允许恢复的最老 Checkpoint/备份、旧 owner 是否失效、源历史是否仍可能重读、重新初始化策略和审计保留需求。

连续区间压缩可以减少每事件账本成本，但前提是区间没有洞，事件顺序和分区定义稳定。把离散已完成集合压成一个最大值，只是把缺口藏起来，不是压缩证明。

## 8. 共享读取、快照和 Schema 为什么要扩展模型

第一篇指出当前一表一 Engine 的成本。共享捕获器有机会降低连接和线程数量，却让确认进度覆盖多张表。

慢表阻塞时，可以停止共享 Source 的确认，让同捕获域一起背压；也可以先把未完成事件写入持久 spool，再允许 D 前进。后一种情况下，安全关系变为“目标已完成，或已有可重放 spool 证据”，系统多了一份持久边界及其消费进度。它不是免费获得隔离，必须再验证 spool 丢失、清理和恢复。

并行快照同样不能套用 streaming eventId。当前 [`MySQLSnapshotSourceNode`][snapshot-source] 使用主键上界和 keyset 分页。上界限制扫描范围，不代表每一页来自同一个可恢复数据库视图，更不等于 Flink CDC 的 LOW/HIGH 回填。

快照若重跑后读取到了新值，不能仅用“表主键 + 同一个 snapshot generation”把新内容解释成旧快照行。需要定义快照代次、分片范围、日志衔接和目标覆盖规则；否则稳定 ID 可能稳定地认错内容。

Schema 变化也会改变事件解释。旧 Schema 数据仍在 writer 缓冲时，先执行 DDL 可能使旧数据按新结构写入。一个演进协议至少需要说明：哪个源位置是 Schema 切换边界，旧数据如何排空或保留旧版本解释，DDL 成功而 J 未记录时如何根据目标状态重复确认。

当前 Schema identity 检查与不支持类型拒绝应该保留。它们是边界保护，不是 DDL 演进实现。把检查删除，并不会自动生成排空屏障或幂等迁移。

## 9. 怎样把证明义务落实到现有类

| 组件 | 已保留 / 已实现 | 本轮没有实现 |
|---|---|---|
| Source / Envelope / Group | 直接读取配对 headers，固定成员角色/身份，完整组装配；Envelope v1 仍作成员内容 | snapshot 组身份、新谱系迁移与 DDL |
| Bridge / 原始确认 | Bridge 不变；半组在前暂存；发布完整组；会话隔离；原始前缀串行确认及局部关闭保护 | 共享池耗尽时的完整作业取消保证、SDK 全局缓存预算 |
| Apply Sink | 组证据与全组 DML 同事务、InnoDB 校验、内容冲突回滚、当前交付 Checkpoint | owner fencing、独立持久 commitToken 表、未知提交结果协调器 |
| Engine / Actor | 原 T→J→Source 顺序、J+1、生命周期与现有失败/恢复入口；boundary 持久绑定 generation | 新 Actor 协调状态、自动旧协议迁移、扫描目标最大进度 |
| 测试设施 | 真 MySQL/Journal/Sink、COMMIT 后闸门、新 JVM、原始回调与 ACK、全字段/审计对账 | 其余故障窗口、集群接管与容量验收 |

新模式显式要求 `initializedTarget=true` 与已有持久 streaming D，配置包含稳定 replicationId/generation/sourceLineage；启动对照 `@@server_uuid`，并拒绝旧版本 Checkpoint。boundary v2 与 `mysql.binlog.groups.v1` 区分旧模式；`snapshot.mode=no_data` 不代表可以丢失 D 后从当前时间继续。初始化声明依赖操作者建立可信全量基线，代码不自动证明源目标全表一致。旧 batch ledger 为兼容现有 readiness 仍需保留，新组账本按独立 DDL 预先创建。具体启用步骤见[生产实验报告](../../experiments/2026-10-19-group-recovery/README.md)。

第一篇已复现的共享阻塞池饥饿，也必须计入活性条件。数学上允许事件重试，不代表执行池里真的有线程执行提交和关闭。协议证明与资源验证不能各自假定另一方永远工作。

## 10. 故障矩阵应当检验哪些证据

下面分别列出组件门槛和新协议验收要求，未执行的格子不标通过。真实目标/Actor 场景必须保存 T/J/D、交付内容、目标操作审计和静止源的全量逐字段结果；源/Bridge 探针只有 D，不能填充 T/J 那一栏。

| 固定窗口或变体 | 必须核实的事实 | 期望的新协议行为 | 当前证据 |
|---|---|---|---|
| 主键组跨回调与延迟句柄 | 原始 d/c、返回/进入顺序、覆盖信号和实际 D | 完整组覆盖前不确认，旧句柄按前缀串行调用 | 历史手动探针单独保留；新增真实生产 `[d][c]` 恢复有 T/J/D、审计与全字段对账 |
| 主键仅 d 确认后的旧恢复点 | 实际 D 与恢复记录 | 新协议不能制造这种不完整覆盖 | 身份探针实测 c 不再交付；不是 Sink 丢行验收 |
| T 已提交，J 未持久化；拆批、合批、主键组 | T 中完整组覆盖、恢复记录与实际分批 | 旧组不重写，新组继续，最终全表一致 | 新协议 16→1、1→max128（实际 72 组/73 成员）、`[d][c]` 三组通过；每组 64 行×6 字段零差异 |
| J 已持久化，D 未更新 | Journal 实际有凭据，D 仍旧 | 旧日志重读不重复产生效果 | 组身份源码可消化重复；该真实窗口仍未固定实测 |
| COMMIT 成功、客户端回复丢失 | 独立查询目标事务事实 | 查询后协调，不凭异常猜回滚 | 未测 |
| ACK 返回但 flush 失败 | ACK 日志与实际 D 分开读取 | 容忍更旧重放，告警可辨识 | 上游源码分支；未注入 |
| 相同组身份、不同内容 | 规范化成员与目标证据 | 整次交付回滚并失败，不吞掉冲突 | H2 上真实 Sink 代码拒绝并回滚；未伪装成真实 MySQL 故障注入 |
| 关闭/迟到 ACK、配对错误、预算 | 线程等待、确认能力及 SDK 调用次数 | 取消解除等待；错误输入不提前确认 | 相关单测通过；未验证共享池耗尽的完整 MySQL 作业 |
| owner 交接时旧请求晚到 | epoch 与目标提交线性化位置 | 拒绝接管后的旧 owner 写入 | 未测 |
| 慢表完成有洞，共享 Source 继续 | 每路提交与持久 spool/前缀 | 不跳过未处理事件，资源有明确上限 | 模型有越洞反例；共享实现未做 |
| 账本清理后恢复旧点 | 允许的最老恢复点与被删证据 | 拒绝非法旧恢复或仍正确去重 | 模型有提前清理反例；真实未测 |
| 旧 Schema 缓冲未清空时 DDL | 缓冲版本、DDL 事实、J 的位置 | 不错解释旧数据，DDL 可安全协调 | 本项目未测 |

**本次真实结果：**三组恢复都在 T 已提交/J 未持久化处退出，再从真实 Journal/D 恢复。普通场景恢复后审计为 139，后续新写后 140；主键场景为 68→68→69，已提交组没有重放副作用。实际 D 与最新 J 的 offset 对齐后，全表 64 行、六字段一致。源码实现、102 项相关检查与 4 项真实套件的完整说明见[报告](../../experiments/2026-10-19-group-recovery/README.md)；其中第四项只是 MyISAM 拒绝，不是第四组恢复。全库 414 项通过、两个 Calcite 套件缺类中止，不能称全库全绿。

后续仍需固定：收到半组即崩溃、组内 DML 后 COMMIT 前崩溃、J 已持久化/原始句柄未调用、两个原始句柄之间崩溃、COMMIT 回复丢失及 ACK flush 失败。关闭/迟到 ACK 与预算目前只有组件检查；前两组 Reader/Bridge 探针不能替这些新生产窗口提供目标证据。

这份矩阵的通过标准不是“没有异常”。冲突输入应该安全拒绝；正常重复应该恢复推进；每个结果都要与预先规定的行为一致。

本文的最小实现与样本验收仍有严格边界，但它们已经能够回答为什么重新分批不应改变业务结果、为什么仅有最大进度不够、以及每一处代码变化需要什么证据。下一篇将这些责任放回 70 万表场景，比较自建 Pekko CDC 与使用 Flink CDC 分别承担的成本。

上一篇：[真实故障实验](03-real-mysql-journal-failure.md)；下一篇：[Pekko 与 Flink CDC 的取舍](05-pekko-vs-flink-cdc.md)。

[models]: https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/server/src/main/scala/cn/xuyinyin/magic/workflow/checkpoint/CheckpointModels.scala#L23
[envelope]: https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/server/src/main/scala/cn/xuyinyin/magic/cdc/MySQLCdcEnvelope.scala#L110
[sink]: https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/sink/MySQLCdcApplySinkNode.scala#L82
[actor]: https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/server/src/main/scala/cn/xuyinyin/magic/workflow/actors/EventSourcedWorkflowActor.scala#L164
[engine-register]: https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/server/src/main/scala/cn/xuyinyin/magic/workflow/execution/WorkflowExecutionEngine.scala#L232
[snapshot-source]: https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/MySQLSnapshotSourceNode.scala#L25

[group-model]: https://github.com/Xuxiaotuan/pekko-reference/blob/65ab5410c32933d0aa3d23510e6c62252d84009b/server/src/main/scala/cn/xuyinyin/magic/cdc/MySQLCdcGroup.scala#L36
[group-assembler]: https://github.com/Xuxiaotuan/pekko-reference/blob/65ab5410c32933d0aa3d23510e6c62252d84009b/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/MySQLCdcGroupAssembler.scala#L34
[group-sink]: https://github.com/Xuxiaotuan/pekko-reference/blob/65ab5410c32933d0aa3d23510e6c62252d84009b/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/sink/MySQLCdcApplySinkNode.scala#L83

## 共享捕获阶段一：把证明义务移到可持久核对的位置

增加 L 后，旧的完整组身份和 T/J 提交次序继续存在，但源 ACK 的充分条件变化了。不能把原公式中的 J 简单替换为 L，然后省略订阅、Schema 与保留期。

**模型前提：**一个固定源谱系、一个受管 Reader；源 ROW/FULL；状态表和目标组账本都使用事务存储；L 不被提前删除；选中表的活动 Schema 不变化；目标基线已由其他可信流程建立。当前实现要求独立状态服务器，且不允许通过修改 profile 重新解释已持久化记录。进程锁只阻止重复启动，不构成跨网络分区的自动 fencing 或接管证明。

在这些前提下，新增路径检查以下不变量：

1. **边界绑定。**每个生效订阅的 B/K、generation、Schema 与不可变 BARRIER 一致。`CAPTURE_READY` 只能表示区间捕获生效，不能推出目标包含 B 之前的数据。
2. **完整覆盖。**原始回调只有全部成员已有持久分类或完整组覆盖，才进入 ACK 前缀。PK 两成员不能靠各自 eventId 独立确认。
3. **D 可解释。**实际 D 对应一个 ACK 证据；其父链和引用帧全部存在且摘要匹配。L 的“最新位置”或本地观察到 ACK 返回，都不能代替实际 D。
4. **表级可重放。**J 的 L 序号必须指向该订阅保留的 GROUP。目标以稳定组身份去重；重新分页只改变交付批次，不改变组内容。
5. **失败不回退到猜测。**容量、Schema、源身份或恢复证据不满足时停止；不能借新 B、latest offset 或清空账本绕过保护。

反例也随之具体化。如果 L 已提交但路由行的 B 被改大，仅校验 GROUP 内容仍可能跳过事件，因此读取订阅时核对 BARRIER。如果 D 已持久化却删除了其引用的 GROUP，仅保留一条“我覆盖过”的回执无法恢复，因此 ACK 要链接实际帧。如果把重新订阅当成补全取消期间的数据，新 B 之后捕获完全正确，目标仍可能永久缺少空档变化。

还有两项刻意没有证明。第一，当前证明链在启动时可能扫描很多历史 ACK，安全性不等于恢复时间有界；第二，L 没有 GC，不能推出有限磁盘能无限期容纳慢表。容量耗尽时暂停是这版的明确行为，后续清理协议必须共同考虑表级 J、旧恢复点与保留期。

**实验结果：**真实矩阵检查 B 提交与回复、回调前缀、重新分批、T/J 缺口和缺失证据拒绝；独立脚本复算保存的帧摘要、引用、D、Journal cursor 与全字段对账。脚本成功属于保存材料的再验证，不是又一次数据库运行。详细结果见[本轮报告](../../experiments/2026-10-25-shared-capture/README.md)。共享捕获没有扩展到 LOW/HIGH 回填、在线初始化、DDL 演进或自动接管。

</details>
