# 历史档案：旧交付、目标事务与 ACK 路径

[当前系列导读](../README.md) · [当前正文](../02-source-and-recovery-contract.md) · [技术附录索引](README.md)

> **历史版本档案，整理于 2026-10-26。** 以下保留此前分析的技术内容和证据链接。“当前”“尚未实现”等词只适用于各段标明的历史版本。当前实现与实验结论以正文和最新验证表为准；原始实验文件未改动。

[重排前全文的固定版本](https://github.com/Xuxiaotuan/pekko-reference/blob/08e5123cc41134f7fbefedf0d4e987c7cc810487/docs/articles/cdc-design/02-source-and-recovery-contract.md)保留原文位置；本档案仅调整相对路径，使其从独立页面仍可访问。

一个批次已经写进 MySQL，Actor 还来不及记录它，进程就退出了。重启之后，目标账本说“已经提交”，Actor Journal 说“尚未完成”，Debezium 又从旧 offset 交出一批事件。恢复程序究竟应该相信谁？

答案不能是“找最大的进度”。三份状态描述的对象不同：目标保存已产生的业务效果，Journal 保存工作流接受的确认，Debezium offset 保存读取器的恢复上下文。**可靠恢复需要重建它们之间的对应关系，而不是把三个数改成一样。** 本篇沿现有源码，推导这份对应关系在何处成立、在何处依赖未被保证的假设。

旧批次账本分析固定为 `d88ec80`；新增完整组模式固定为 `65ab541`。Pekko 1.1.3、Debezium 3.6.1.Final 不变。Debezium 上游源码固定到 `63371830ceff75437f95c775b91eeeaf55a056c1`。版本与证据分层见[目录](../README.md)。下文先保留旧协议的因果分析，再沿新模式源码说明改动；第三篇分别给出旧协议反例与新协议指定窗口的真实验收。其他故障时序仍标为源码推演。

## 1. 先定义三个“已经完成”

项目的可靠执行路径最终产生一个 [`BatchCheckpoint`][models]：

```text
sourceNodeId, partitionId, batchSequence, batchId,
cursor(kind, value, upperBound), sourceRowsScanned, targetRowsWritten
```

旧模式将这个结构同时放入批次账本与 Actor 状态。新模式的 T 改存稳定组及成员内容证据，J 仍保存当前交付的 Checkpoint；不能再把 T 的“组数”与 J 的交付序号直接相等比较。

| 记号 | 持久化对象 | “完成”的含义 |
|---|---|---|
| T | MySQL 业务表与该模式的账本：旧 `pekko_sync_batch_ledger` / 新 `pekko_sync_cdc_group_ledger` | 该目标事务已提交；新模式证明完整组及成员内容，不持久化本次传输批次作为去重身份 |
| J | Journal 中的 `ExecutionCheckpointAdvanced` | 工作流已接受这项提交结果，并能在 Actor 恢复时重建它 |
| D | Debezium JDBC offset store | Connector 重启时可加载这份读取恢复上下文 |

这里 T/J 的“第 6 批”指业务 `batchSequence`，不是 Journal 表的 `sequence_number`。Journal 还记录定义、启动、边界初始化、失败等事件，其序号有另一种含义。

D 更不能直接改写成“第几批”。它可能包含 file、pos、GTID、事务内事件计数、行跳过计数和快照阶段。拿 D 的 pos 与 J 的 batchSequence 作大小比较，没有定义良好的语义。

需要再分清三个同名的 Checkpoint：本文的 Actor Checkpoint 是应用进度事件；Pekko Snapshot 是加速 Actor 状态恢复的持久化快照；Flink Checkpoint 则协调其执行图中的恢复状态。[Flink 状态恢复说明](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/concepts/stateful-stream-processing/)不能直接套在这段 Actor 代码上。`Effect.persist` 的成功，不会顺便把外部 MySQL、Source 和所有 Stream 缓冲保存成一致切面。

## 2. 旧模式怎样把回调序号变成批次身份

以下先沿旧版本 [`MySQLCdcSourceNode.CdcResource`][source] 阅读，新模式的装配路径见第 9 节。构造资源时，Source 同时初始化 `callbackSequence` 和 `outputSequence`。如果 J 中已有 Checkpoint，两者都从 `J.batchSequence + 1` 开始；否则从 0 开始。

Debezium 每调用一次 `handleBatch`，Source 取一个回调序号，生成批次 ID，再解码其中的 SourceRecord：

```text
n = callbackSequence.getAndIncrement()
batchId = SHA256(executionId | sourceNodeId | partitionId | n)
rows = 当前回调中的记录解码为业务事件
cursor = 当前回调最后一个原始 SourceRecord 的 sourceOffset
bridge.publish(batchId, rows, cursor, 原回调的确认句柄)
```

Bridge 还会分配一个 `deliveryToken`。这个 token 关联内存中的队列项和确认句柄；它不是持久化的源事件身份。`nextBatch()` 检查收到的 batchId 是否对应 `outputSequence`，注册当前确认作用域，然后构造 `SourceBatch`。[Bridge 实现][bridge]

两套 sequence 的校验可以发现**本次资源生命周期内**回调顺序与输出顺序不一致。它不比较“这次第 n 批”与“上次运行第 n 批”的业务内容，所以不能证明恢复后的批次身份稳定。

还有一处必须注意：解码器允许 tombstone 或特定元数据记录返回 None，因此业务 rows 数可能小于原始记录数，甚至为零。cursor 却取原始回调的最后一条记录，确认句柄最终也标记整个原始回调。`sourceRowsScanned` 在这条路径记录的是规范化业务 rows 的数量，不是所有原始 SourceRecord 的数量。[解码入口][envelope]

这不是单纯的计数细节。未来设计确认前缀时，必须为被过滤的记录定义处理结果，不能把“零条业务 DML”理解成“Source 没有向前读取”。

## 3. 旧批次账本的 T 边界：效果与账本一起提交

[`WorkflowExecutionEngine.processBatch`][engine] 先处理本批 rows，再依次等待三个 Future：

```scala
sink.commitBatch(batch)
  .flatMap(result => actor.checkpointCommitted(checkpoint(result)))
  .flatMap(_ => source.acknowledgeCommittedBatch(batch))
```

外层 `mapAsync(1)` 限制这条 Stream 的批次处理并发。它没有跨 T/J/D 创建事务，也不等于每个源事务都在一个目标事务中提交。

第一个 Future 进入 [`MySQLCdcApplySinkNode.commitBatch`][sink]。执行次序值得逐项展开：

1. 检查转换前后条数，解析批次身份与事件。
2. 读取目标元数据，验证列类型和源目标兼容性，生成本批 DML 计划。
3. 创建预期 `LedgerRecord`，关闭自动提交。
4. 查找 batchId；存在时比较完整账本记录，不存在时尝试插入账本。
5. 新账本占位成功后，应用所有计划的业务事件，再 COMMIT。

账本与业务 DML 使用同一连接、同一事务。因此，在业务表和账本采用事务型存储的前提下，可以建立一个局部不变量：

```text
本批账本持久存在 ⇔ 本次目标事务中的账本与业务修改一起提交
```

这里的右侧不是“每个事件产生一次可见行数变化”。UPDATE 可能赋相同值，DELETE 可能没有匹配行。账本表示事务接受了这组操作，不能用 MySQL affected rows 反推全部业务语义。

查到已有账本时，Sink 对比 workflowId、executionId 以及整个 Checkpoint。相同则返回 `AlreadyCommitted`；不同则拒绝。两个提交者竞争相同 batchId 时，插入的唯一键冲突分支会回滚，再查询竞争者留下的记录；它不会直接把所有重复键错误当成成功。

这套行为值得保留。它把“重复请求”与“相同 ID、不同提交含义”区分开，第三篇的拆批实验正是被后者拦住。

但账本不是任意内容的完整证明。预期记录中没有整批 payload 的独立摘要：同样的行数和末尾 cursor，并不在类型定义层面蕴含中间每条数据相同。现有实验观察到了元数据不同后的拒绝，没有验证同元数据、不同内容是否能从真实 Source 路径产生。

另外，查账本发生在 Schema 校验和 DML 计划之后。**源码推演：**即使旧批已经提交，若目标 Schema 此后变得不兼容，重放也可能先在校验阶段失败，尚未走到 `AlreadyCommitted`。幂等账本提供的是一条有前置条件的恢复路径，不能独立跨越任意 DDL。

## 4. J 的持久化边界：确认是事件，回复是另一件事

目标成功后，Engine 把结果转换成 Checkpoint，经 `context.self.ask` 发送 `AdvanceCheckpoint`。Actor 的 [`advanceCheckpoint`][actor-checkpoint] 检查：

- 当前存在可恢复执行，executionId 与命令一致。
- sourceNodeId、partitionId 和 stream identity 与已初始化边界一致。
- batchId 符合当前身份公式。
- 当前分区尚无进度时必须从 0 开始；否则只接受紧邻下一序号，或完全相同的已保存 Checkpoint。

正常新批执行：

```scala
Effect.persist(ExecutionCheckpointAdvanced(...))
  .thenReply(replyTo)(_ => CheckpointAccepted(checkpoint))
```

Pekko 等待 Journal 写入响应期间会暂存普通命令，持久化完成后再推进后续动作。事件处理器用新 Checkpoint 替换该分区旧进度。**在这条正常调用路径中，回复成功发生在持久化之后。**

但反向推理不成立：没有收到成功回复，不等于事件没有持久化。请求方可能超时，进程可能在持久化完成后、回复送达前退出。项目 ask 超时为 5 秒；它是调用方的等待预算，不是对 Journal 最终结果的判定器。[Actor 调用与恢复入口][actor]

因此日志中的 `TimeoutException` 不能直接翻译成“J 还没推进”。恢复实验必须读取持久化事实，或通过新 Actor 的真实恢复状态确认。第三篇同时保留 Actor 查询结果与反序列化 Journal，正是为了避免只凭异常猜状态。

还有一个范围限制：Actor 接受的是符合本地状态机的 Checkpoint，校验函数本身不会去目标 MySQL 查询 T。`J ⇒ T` 的依据来自 Engine 先提交 Sink 再发命令的调用关系，以及消息来源和协议约束，不是 `Effect.persist` 天然能证明外部事务已完成。

## 5. SDK 确认与 D 落盘之间，还隔着一条真实路径

旧模式的确认注册表找到当前 Bridge 与 deliveryToken 后，调用 `bridge.acknowledge`。Bridge 将该批标记为已认领确认，再执行 [`DebeziumRecordCommitHandle`][engine-access]：遍历原始回调记录调用 `markProcessed`，最后调用 `markBatchFinished`。成功返回后，Bridge 才完成发布方等待的 Promise。

这里存在三个观察点：

```text
A1：项目开始调用确认句柄
A2：markProcessed / markBatchFinished 返回；旧模式的项目 ACK Future 随之完成
D ：offset store 中已持久保存对应恢复状态
```

不能把 A2 与 D 合并。项目依赖中的 Debezium BuilderFactory 指向 Async Engine；本轮检查了实际依赖 JAR 的 service provider，并阅读了固定版本的 [`AsyncEmbeddedEngine`][async-engine]。

它的 `markProcessed` 把记录的 sourcePartition/sourceOffset 交给 OffsetStorageWriter。`markBatchFinished` 根据提交策略决定是否执行 flush。`commitOffsets` 中，某些 flush 异常会取消本次 flush 并返回 false；调用方只有成功时才重置提交计数。也就是说，**该源码存在确认方法返回、offset 尚未成功更新的路径**。

把 `offset.flush.interval.ms` 设为 0，可以改变提交策略的触发频率，却不会让目标事务、Actor Journal 和 offset store 获得共同的原子边界，也不会消除 I/O 失败。

这是固定版本源码结论，本轮没有注入一次真实 offset store 写失败。第三篇读取了真实 D，并比较崩溃前后的完整值；它证明的是那次实验里的持久状态，不是对所有 ACK 的普遍承诺。

## 6. 恢复时，J 负责编号，D 负责重新读

Actor 的恢复首先重放 Journal，重建 `currentExecution`。`RecoveryCompleted` 为仍可恢复的执行发送 `RecoverInterrupted`，沿原 executionId 启动 Engine。Engine 从该执行的 Checkpoint 取最近进度，再创建 Source。

**源码结论：当前恢复路径没有把 J 中的 cursor 转换成 Debezium 的 seek 指令。** 两条输入实际分别起作用：

```text
J → 执行身份、已初始化的 stream identity、恢复后的下一批序号
D → Connector 的日志恢复起点、事务内跳过信息、快照恢复上下文
```

这就是当前恢复协议最需要说明的接缝：序号可以从 J 连续增长，但下一次回调的内容由 D、源日志和运行时批次划分共同决定。序号连续只证明应用计数器没跳号，不能证明事件覆盖连续。

`SnapshotBoundary.upperBound` 在 CDC 路径里放的是 stream identity JSON，包含连接器、表、主键和列结构。它不是 LOW/HIGH 中的 HIGH 日志水位。另一个有界快照 Source 使用同一结构名，并不表示两条路径实现了同一种快照算法。

如果执行已经持久化成失败终态，事件处理器会清理当前执行，保留终态摘要。它不再等同于“运行中断后自动接续”。第三篇失败组的 Actor 重启语义，不能用“进程总会自动重试”概括。

## 7. 旧模式的最小反例：分组改变，身份没有改变

用 bₙ 表示第一次运行的第 n 个回调。旧模式的 ID 公式只依赖执行、分区和 n，可以记为：

```text
I(bₙ) = H(executionId, sourceNodeId, partitionId, n)
```

设目标已提交 `bₙ = [e1, …, e16]`，J 还在 n−1。恢复时 D 允许从 e1 重读，新回调 `b′ₙ = [e1]`。应用仍从 n 编号，因此：

```text
I(bₙ) = I(b′ₙ)
但 payload(bₙ) ≠ payload(b′ₙ)
```

这不是 SHA-256 碰撞。两次输入本来就完全相同；失效的是“回调序号能跨恢复代表相同内容”的假设。

Sink 会比较行数和 cursor。如果二者不同，冲突检查让恢复失败。这是一种安全拒绝：它没有冒险把旧批账本当成新批内容的提交证据。但系统也失去了自动追平能力。

若想仅靠当前批次账本获得正确重放，至少需要重复批次满足以下条件：同一身份对应同一事件序列，比较元数据能够匹配，执行和 Schema 语义保持一致，账本及源日志仍可用。当前 Source 没有建立“重启后回调划分不变”的机制。第三篇的真实 16→1 回调就打破了这个条件。

### 为什么不能用主键 Upsert 来绕过冲突

考虑同一主键的两个 after-image：e1 把值写成 1，e2 把值写成 2。按 e1、e2 顺序执行后，目标为 2；旧 e1 再次重放，普通 upsert 会把它改回 1。

重复同一事件连续执行可能得到相同终值，不代表它在任意历史位置重放都安全。触发器、审计和下游外部副作用也可能观察到重复操作。最终行值幂等、单事件只应用一次和源事务原子可见，是三个不同承诺。

这是操作语义反例，不是本文新增的数据库故障结果。第四篇的有限模型会显式演示它。

## 8. 为什么 cursor.pos 相同仍然不够

[`BinlogOffsetContext`][binlog-offset] 同时维护当前源事件位置与重启使用的位置。事务开始时可以记录重启起点，随后累计需要跳过的事件数和行数。`setRowNumber` 将当前事件内行号写入 source info，同时把恢复时要跳过的行数设为当前行号加一。

因此，事件中用于描述来源的 `source.pos/source.row`，与 `sourceOffset.pos/sourceOffset.row` 的职责不同。前者帮助描述这条事件，后者服务于重启读取，不能凭名称相近直接替换。

第三篇保存的真实拆批样本中，两批 cursor 的 file、pos、event 相同，row 却分别为 16 和 1；第一条业务事件的 source.position 还与 cursor.pos 不同。这并不矛盾：它们记录的是不同层级的恢复信息。

要讨论“D 是否走在 T 前面”，应先定义一个语义函数：`Skip(D)` 表示从 D 正常恢复时不会再交给应用处理的必需事件集合；`Covered(T)` 表示已由目标提交证据覆盖的事件。安全条件可以写成：

```text
Skip(D) ⊆ Covered(T)
```

这里的集合是推理模型，不是当前代码中已有的数据结构；对被明确过滤的记录，还需要有合法处理规则。它比 `D ≤ J ≤ T` 更准确，因为没有假装不同类型的进度可以直接排序。

### source.row 加 op 解决身份，不自动解决 ACK

2026-10-19 的真实 SourceRecord 实验补充了一个更细的边界：39 条同事务记录在重启、拆批和合批后，候选定位元组与规范化内容一致；主键修改的 d/c 则共享 file/source.pos/source.row 和完整 restart offset。去掉 op，两个成员就碰撞。仅确认 d、让 D 持久化后重启，c 没有再次交付。[身份实验](../../../experiments/2026-10-19-source-record-identity/README.md)

这意味着读取恢复上下文的粒度可能大于一个逻辑 SourceRecord。即使给 c 分配稳定 ID，它不再到达时，去重账本也无能为力。Source ACK 必须以完整物理行组的覆盖为条件；仅看当前回调是否提交还不够。

另一个[真实 Engine/Bridge 探针](../../../experiments/2026-10-19-deferred-ack/README.md)在 Bridge 前配齐 d/c，再用测试信号确认覆盖，成功从后续回调延迟调用旧句柄，且实际 D 没有在覆盖前推进。它没有目标或 Journal，只能作为组件证据；后续生产改造另有真实 T/J/Sink 验收，不能倒过来把该探针称为端到端实验。具体分组和原始回调前缀确认见[第四篇](../04-protocol-and-validation.md)。

## 9. 新模式把“交付连续”与“源事件稳定”拆开

**源码结论，版本 `65ab541`。** [Source 的新路径][group-source]在 Bridge 前调用 [MySQLCdcGroupAssembler][group-assembler]。普通变更形成单成员组；主键 DELETE 带 newkey 时暂存，必须等到相邻 CREATE，并核对物理位置、分区、Schema、完整 restart offset 和双向键对应关系。A 可以不调用原始句柄就返回，B 因而能够到达；只有完整组才进入 Bridge。

```text
raw callback A: [u0,d1] → 交付 [g0] → T/J 覆盖；保留 A，不 ACK d1
raw callback B: [c1,d2] → 配齐 g1 → T/J 覆盖 → 可确认原始 A
                         保留 B 的 d2，返回以等待下一个回调
```

交付序号只在发布完整组时递增，继续满足 Actor 的 J+1 校验。稳定 [groupId/eventId][group-model] 则由复制代次、固定源谱系和 file/source.pos/source.row、成员角色决定，不含 executionId 或回调大小。新 Sink 用完整组证据去重，批次 ID 只承担本次交付的 Journal 顺序。

[新 Sink][group-sink]先解析全部组并验证 Schema/类型，再在同一连接事务中按源顺序查组账本：相同组及规范化证据一致就跳过整个组；不同则回滚整次交付；不存在则写组证据并执行全组 DML。主键 d/c 与证据一起提交，不产生持久的“只完成 d”。目标表与组账本要求 InnoDB，但这不保证整笔源事务的多个组同时可见。

重放全部命中时返回 `AlreadyCommitted(本次Checkpoint)`；混合旧/新组返回本次覆盖的 Checkpoint，而不是旧组最初的批次元数据。Actor/Engine 的持久化与顺序实现没有改动。Checkpoint 的行数按逻辑成员计数，包含本次覆盖的重复成员，不能用 `targetRowsWritten` 断言新增 DML 数。

### 新 Source ACK 的 Future 已不代表原始 SDK 句柄返回

Stream 在 T/J 成功后确认本次交付，只调用 Bridge 的无 SDK 操作代理，解除发布等待。装配器在有序回调路径恢复执行，记录覆盖，再按原始回调连续前缀调用 SDK 句柄。因此新模式中“项目 ACK Future 返回”“原始 SDK markBatchFinished 返回”“观测到 D 持久化”是三个不同事实，旧模式 A2 的对应关系不能直接照搬。

`SourceBatch.acknowledgementId` 是只在内存中存在的会话能力，不进入持久 cursor/Checkpoint；注册表同时核对 batchId。交付 cursor 取最后一个完整组的成员 offset，不取尚未配齐的回调末尾 DELETE。关闭、旧会话迟到确认和错误 batchId 的检查有单测，但完整作业的共享池耗尽/取消仍未验。

新模式显式声明 replicationId、generation、sourceLineage 和可信初始化，boundary 为 v2、cursor 为 `mysql.binlog.groups.v1`；启动检查 server UUID 与已有持久 streaming D。它不创建新的 snapshot 协议，旧 Checkpoint 不可透明续接，也不把 T 的最大 cursor 写回 D。初始化声明不是自动全表校验，server UUID 也不证明日志从未重置。

**真实实验结果：**三组分别固定 reader 16→1、1→max128（实际首个恢复交付 72 组/73 成员）及主键 `[d][c]` 的 T 已提交/J 未持久化窗口。各组最终 64 行×6 字段零差异，已提交组重放没有新增审计，后续新写继续收敛。[原始材料与范围](../../../experiments/2026-10-19-group-recovery/README.md)

## 10. 按持久化事实划分故障窗口

下表先描述源码允许的分支，再标明实测范围。任何“调用失败”都需要进一步判断事务最终是否提交。

| 窗口 | 可能留下的状态 | 恢复必须回答的问题 | 本系列证据 |
|---|---|---|---|
| T 的事务尚未提交 | 本批 T/J 都没有 | D 是否允许重读，事务是否确实回滚 | 源码分析，未固定此窗口实测 |
| COMMIT 已成功，回复丢失 | 调用方不确定，T 可能已有 | 能否查询相同身份、确认实际事务结果 | 未实测；不能默认回滚 |
| T 已提交，J 尚未持久化 | T 已有完整组，J 未记录本次交付，D 仍旧 | 重放身份能否识别既有目标效果 | 第三篇保留旧对照，新增三组完整组真实恢复通过 |
| J 已持久化，回复未被 Engine 接收 | T/J 已有，Source 尚未确认 | 查询/恢复 J，避免以超时覆盖持久事实 | 未固定实测 |
| J 已持久化，D 尚未更新 | T/J 已有，D 重读旧事件 | 旧事件被新交付序号标记时如何去重 | 新组身份源码路径可处理重复；该真实窗口仍未固定实测 |
| ACK 返回，offset flush 未成功 | 调用日志已确认，D 仍旧 | 恢复仍需容忍重复；监控不能只看 ACK | 上游源码分支，未注入实测 |

尤其要区分第三行和第五行。在第三行，恢复从 J+1 编号，可能碰到已经存在的旧批 ID；第五行在旧模式中，J 已前进，旧事件可能获得新批次 ID，批次账本不会按原 ID 命中；新模式的稳定组身份与该交付编号分离。把第三行的成功重放实验推广到第五行，是错误的证据扩张。

两代实现都保留目标证据与 DML 同事务、Actor 确认在持久化后返回的基础。新模式已将业务去重移到完整组，并按覆盖确认原始回调前缀；尚未覆盖的真实窗口不能因为这条源码路径看起来可行就标为通过。

下一篇先用旧版本证明隐含假设失效，再给出新版本在相同类别持久化窗口下的恢复证据。

上一篇：[Actor 的执行模型与资源优势](../01-why-pekko-for-resident-cdc.md)；下一篇：[故障实验的因果证据](../03-real-mysql-journal-failure.md)。

[models]: https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/server/src/main/scala/cn/xuyinyin/magic/workflow/checkpoint/CheckpointModels.scala#L23
[source]: https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/MySQLCdcSourceNode.scala#L129
[bridge]: https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/DebeziumBatchBridge.scala#L25
[envelope]: https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/server/src/main/scala/cn/xuyinyin/magic/cdc/MySQLCdcEnvelope.scala#L110
[engine]: https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/server/src/main/scala/cn/xuyinyin/magic/workflow/execution/WorkflowExecutionEngine.scala#L289
[sink]: https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/sink/MySQLCdcApplySinkNode.scala#L82
[actor-checkpoint]: https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/server/src/main/scala/cn/xuyinyin/magic/workflow/actors/EventSourcedWorkflowActor.scala#L337
[actor]: https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/server/src/main/scala/cn/xuyinyin/magic/workflow/actors/EventSourcedWorkflowActor.scala#L164
[engine-access]: https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/DebeziumEngineAccess.scala#L41
[async-engine]: https://github.com/debezium/debezium/blob/63371830ceff75437f95c775b91eeeaf55a056c1/debezium-embedded/src/main/java/io/debezium/embedded/async/AsyncEmbeddedEngine.java#L1384
[binlog-offset]: https://github.com/debezium/debezium/blob/63371830ceff75437f95c775b91eeeaf55a056c1/debezium-connector-binlog/src/main/java/io/debezium/connector/binlog/BinlogOffsetContext.java#L71

[group-source]: https://github.com/Xuxiaotuan/pekko-reference/blob/65ab5410c32933d0aa3d23510e6c62252d84009b/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/MySQLCdcSourceNode.scala#L169

[group-assembler]: https://github.com/Xuxiaotuan/pekko-reference/blob/65ab5410c32933d0aa3d23510e6c62252d84009b/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/MySQLCdcGroupAssembler.scala#L34

[group-model]: https://github.com/Xuxiaotuan/pekko-reference/blob/65ab5410c32933d0aa3d23510e6c62252d84009b/server/src/main/scala/cn/xuyinyin/magic/cdc/MySQLCdcGroup.scala#L36

[group-sink]: https://github.com/Xuxiaotuan/pekko-reference/blob/65ab5410c32933d0aa3d23510e6c62252d84009b/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/sink/MySQLCdcApplySinkNode.scala#L83

## 共享捕获阶段一：在 D 与表级 T/J 之间增加 L

原单表路径的顺序是“目标 T → Actor J → 原始 ACK → D”。共享 Reader 中，一张慢表不能持有整个源的确认句柄。新增路径将源的持久覆盖条件改为 L，表级应用仍保留 T/J：

```text
原始记录 → 完整组装配 → L 提交 → 原始回调连续前缀 ACK → SDK flush → 实际 D
                            └→ 表级读取 → 目标 DML 与组账本同事务 T → Actor J
```

这是**新增模式的源码契约**，不是对旧 T/J/D 数据结构重新解释。D 仍是 SDK 实际写入的恢复位置；J 的新 cursor `mysql.capture.groups.v1` 则引用某个订阅的 L 序号。二者不能互换大小比较。L 序号只在本捕获域有意义，源 occurrence 仍由 binlog 文件、位置和行号定位。

### B 的含义：从何处开始保留，而不是从何处完成初始化

订阅先等待 Reader 的结构化 Schema 描述，再用 JDBC 校验列与主键，持久化 `B_PENDING`。源控制表中的唯一 operationId 是重试身份；marker 必须独占一个源事务。Reader 读到 marker，并确认对应事务 END 的事件数为一，才在同一个状态事务里落下 BARRIER、B 位置和 `CAPTURE_READY`。

有效捕获区间为 `(B,K]`。新增表在 B 之前的已有数据没有因此出现在目标；`initialized` 明确为 false。当前 `mysql.cdc.log` 要求操作方显式提供可信目标基线，实验提前复制基线只是夹具，不能作为在线初始化能力。

源 marker 的 COMMIT 回复丢失时，先读取原 operationId 的结果；不以一次 RPC 超时认定源事务回滚。L 的提交回复不确定时则停止当前捕获会话，重启后依靠相同 marker/组身份核实已有事实。重放按照持久化历史区间路由，不能用“今天选中了哪些表”过滤昨天的记录。

### 为什么 L 不能只有一个“最后已写位置”

L 中的 ACK 证据引用本回调依赖的 PROFILE、SCHEMA、BARRIER 和 GROUP 帧，并链接前一个 ACK 的序号与摘要。启动时实际 D 必须命中这条覆盖链；缺历史、缺 D、缺被引用的组或谱系变化都会拒绝启动。订阅行中的 B/K 也与不可变 BARRIER 核对。摘要提供损坏检测，不是可抵抗拥有数据库写权限者的密码学防篡改方案。

主键修改的 DELETE/CREATE 可以跨原始回调。回调 A 返回后保留其句柄，让回调 B 抵达；两个成员装配并提交 L 后，才按原始回调前缀调用 SDK。表级 Actor 不再等待、也不能提前确认这些句柄。两次原始 ACK 之间崩溃时，L 已包含完整组；这是允许 D 前进的前提，而不是假设 SDK 会重交付另一个成员。

**实验结果及边界：**本轮使用真实 Engine、目标 MySQL 与 JDBC Actor Journal 验证上述指定窗口，原始 ACK 与实际 D 分开取证。证据和逐项结论见[报告](../../../experiments/2026-10-25-shared-capture/README.md)。当前没有日志截断、历史恢复点清理、自动重建丢失 D 或共享域自动接管；缺证据时安全暂停，不从 latest 猜测恢复。

还要保留一个语义边界：完整组保证 PK 的 d/c 不被拆成独立恢复单位，不代表保留整个源事务。分表消费有各自的 T/J，跨表可见时刻可以不同。同事务多行实验的逐字段终值一致，不能升级为源事务在目标端原子可见。
