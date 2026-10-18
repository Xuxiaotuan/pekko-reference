# 二：沿源码理解 T/J/D，三个进度为什么不能自动一致

一个 CDC 批次写入目标库后，进程立刻崩溃。重启时，Actor 说自己还没处理这批数据，目标库却说已经提交，Debezium 又从更早的位置开始读。三个组件都可能正确保存了自己的状态，组合起来仍可能无法继续。

`pekko-reference` 当前的恢复边界就在这里。本篇沿源码拆开 T、J、D，说明正常顺序建立了哪些约束，以及为什么“目标有幂等账本”仍不足以保证任意重放都成功。**本文以源码推理为主；实际成功与失败记录在第三篇。**

基线为 `ad58cf3`，依赖和标签见[系列目录](README.md)。

## 1. T、J、D 分别回答不同问题

| 记号 | 当前存储 | 它能回答的问题 | 它不能单独回答的问题 |
|---|---|---|---|
| T | 目标 MySQL 的批次账本与业务表，同一事务提交 | 目标已经接受了哪一批及其元数据？ | Actor 是否记录了结果，Source 是否持久化确认？ |
| J | Actor Journal 中的 `ExecutionCheckpointAdvanced` | 工作流确认了哪一批，恢复执行身份是什么？ | 目标是否还有已提交但尚未记入 J 的批次？ |
| D | Debezium JDBC offset store | connector 重启应该从什么恢复上下文开始？ | 新回调如何分批、批次 ID 是否与 T 一致？ |

T/J 的实验数字是业务 `batch_sequence`，不是 Journal 表的 `sequence_number`。D 则是完整 offset map，包含日志、事务与行级恢复信息。三者并不是同一条数轴上的三个整数。

“Actor Checkpoint”也需要限定含义。这里指应用持久化的批次进度事件，不是 Pekko Snapshot Store 文件，更不是 Flink 对 Source、算子状态与在途数据建立的分布式一致快照。Flink 对状态恢复的说明可参考 [1.20 状态化流处理文档](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/concepts/stateful-stream-processing/)。两个系统中的同名词不能直接互换。

## 2. 正常执行按 T → J → Source 确认推进

[`WorkflowExecutionEngine.processBatch`](https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/WorkflowExecutionEngine.scala#L289)的关键顺序可以压缩成下面的伪代码：

```scala
sink.commitBatch(batch)
  .flatMap(result => actor.checkpointCommitted(checkpoint(result)))
  .flatMap(_ => source.acknowledgeCommittedBatch(batch))
```

外围 `mapAsync(1)` 让这条 Stream 逐批等待完成。这里没有跨三个存储的分布式事务。

第一步进入 [`MySQLCdcApplySinkNode.commitBatch`](https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sinks/MySQLCdcApplySinkNode.scala#L82)。Sink 解析事件、校验目标 Schema，然后关闭自动提交。如果账本不存在，它在同一个 MySQL 事务中写账本并应用全部业务 DML，最后 COMMIT；如果账本已存在且元数据相同，返回 `AlreadyCommitted`；如果不同，拒绝冲突。

第二步进入 Actor 的 [`advanceCheckpoint`](https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/actors/EventSourcedWorkflowActor.scala#L337)。Actor 检查 executionId、分区、批次 ID 和连续序号，执行 `Effect.persist(ExecutionCheckpointAdvanced(...)).thenReply(...)`。Engine 收到成功答复后才继续。

第三步通过 Source 的确认句柄标记 Debezium 记录已处理。**确认完成与 D 已落盘仍是两个观察点。** Engine 的 offset 存储与刷盘有自己的生命周期；不能仅凭调用过确认，就把 JDBC offset 表也标为成功。Debezium Engine 对故障后重复投递的说明见[官方文档](https://debezium.io/documentation/reference/3.6/development/engine.html)。

这条顺序的价值很明确：正常调用链不会在目标提交之前主动把本批推进为 Actor 已完成，也不会在 Actor 成功确认之前确认本批 Source。它留下的代价是，目标与 Actor 之间必然存在一个可发生崩溃的间隙。

## 3. 恢复从两个地方取信息

[`EventSourcedWorkflowActor`](https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/actors/EventSourcedWorkflowActor.scala#L164)收到 `RecoveryCompleted` 后，会针对仍在执行的状态发送 `RecoverInterrupted`。可恢复执行保留原 executionId，再调用 Engine。已经持久化成失败终态的执行，不应混同为“重启一定无限重试”。

Engine 从 J 取最近的 `BatchCheckpoint`，交给 [`MySQLCdcSourceNode.createBatches`](https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLCdcSourceNode.scala#L129)。Source 用它校验身份，并把下一批序号设为 `checkpoint.batchSequence + 1`。

真正启动 Debezium 时，connector 又读取自己的 JDBC offset store。当前代码没有把 J 中的 cursor 直接转换成 Debezium 的 seek 指令。因此恢复时有两条来源：

```text
J ──> 原 executionId、下一次回调的 batchSequence
D ──> Debezium 从哪里重读、怎样重建日志读取状态
```

它们必须在业务意义上兼容，却没有原子提交来保证同时更新。这正是需要分析重放窗口的原因。

还有一个容易误读的名字：CDC 路径把 stream identity JSON 放在 `SnapshotBoundary.upperBound` 中。它描述 connector、表、主键和列结构，不是增量快照算法里的 HIGH binlog 水位。不能仅凭字段名把该实现解释成 Flink CDC 分片协议。

## 4. 同一个 batchId 为什么可能代表另一批数据

Source 收到一次 Debezium 回调，就递增一次 callbackSequence，并计算：

```text
batchId = SHA256(executionId, sourceNodeId, partitionId, callbackSequence)
```

生产实现见 [`handleBatch`](https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLCdcSourceNode.scala#L213)。这个散列保护的是四个输入组成的身份，不能使“第 n 次回调的内容”跨恢复保持不变。

设第一次运行的源事件顺序是 e1 到 e16，恰好被放进第 n 批。目标已经提交这 16 条，J 仍停在 n−1。恢复后 D 让读取器重读这些事件，但回调被拆成单条，恢复首批仅有 e1。Source 仍从 J 推算出序号 n，于是两个不同批次拥有相同 ID。

可以把当前幂等重放的充分条件写成：在相同执行和分区内，**每个重复批次的身份及账本比较元数据保持一致，并且这些元数据确实对应同一批业务事件**。现有 ID 公式本身不能证明这个条件。

Sink 的比较内容包括 workflowId、executionId 和 Checkpoint，其中包含末尾 cursor、行数等；它没有对整个批次业务 payload 做独立摘要。拆批导致行数、cursor 改变时会被拒绝，这是保护。但不能进一步声称：任何内容不同的批次都会被账本识别。相同元数据而 payload 不同是否可达，需要另做实验。

哈希算法没有出错。问题是把传输分组的序号当作跨恢复稳定的业务身份。

## 5. 为什么不能只比较 binlog pos

当前 Source 将最后一条 `SourceRecord.sourceOffset` 保存成 cursor。Debezium 的 [`BinlogOffsetContext.getOffset`](https://github.com/debezium/debezium/blob/v3.6.1.Final/debezium-connector-binlog/src/main/java/io/debezium/connector/binlog/BinlogOffsetContext.java)会组织恢复位置和需要跳过的 event/row 等状态。事件展示位置与恢复起点也不必是同一个数。

因此，以下推理不成立：

```text
原批 cursor.pos == 重放批 cursor.pos
所以两批消费了同样的事件
```

第三篇会展示实际的 `pos` 相同、`row` 分别为 16 和 1 的批次。它们在同一日志位置内部覆盖了不同范围。

同理，不应写出未经定义的 `D ≤ J ≤ T` 数字不等式。更准确的协议问题是：D 所代表的恢复语义，会不会让 Source 跳过任何尚未有目标提交证据的必需事件？J 中的每一项确认，能否追溯到 T 中的持久事实？回答这些问题需要事件或区间的语义模型。

## 6. 当前顺序可以推导出哪些故障分支

下面是源码推演，不代表每个分支都做过实验。

| 故障窗口 | 可持久状态 | 恢复需要的条件 | 本系列实测 |
|---|---|---|---|
| 目标 COMMIT 之前 | 本批无 T、无 J | 事务回滚，Source 能重放 | 未测 |
| 目标 COMMIT 后、J 持久化前 | T 有本批，J 没有 | 能识别重放并补确认；不能靠重新编号猜测事件身份 | 已测，结果取决于分批 |
| J 已持久化、D 未推进 | T/J 有本批，D 可能更早 | 重读与 J 推导的新序号需要协调 | 未固定窗口实测 |
| T/J/D 都完成后 | 本批三份状态已覆盖 | 后续日志可读，身份与 Schema 一致 | 成功组继续处理了三个变更；不等于全故障覆盖 |

尤其不能把第二行的成功复制到第三行。因为当前序号来自 J，J 前进后，旧事件重读可能被赋予新的批次 ID。账本可能因此无法认出重复业务事件；在某些事件顺序下，旧镜像重放还会产生短暂回退。这里只提出待验证风险，没有把它写成已经观察到的数据错误。

本篇得到的结论是：当前实现拥有有用的事务账本、持久 Actor 与确认顺序，但恢复正确性仍依赖批次边界与进度之间的隐含对应。下一篇用真实 MySQL 和真实 Journal，把其中一个窗口固定下来，检查这个对应是否成立。

上一篇：[70 万张表，为什么考虑用 Pekko 做 CDC](01-why-pekko-for-resident-cdc.md)；下一篇：[真实 MySQL 故障实验](03-real-mysql-journal-failure.md)。
