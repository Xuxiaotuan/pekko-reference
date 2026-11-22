# 一条 MySQL UPDATE 什么时候才算被 CDC 接受？

[系列导读](README.md) · [第一篇：常驻 CDC](01-why-pekko-for-resident-cdc.md) · [第二篇：恢复协议](02-source-and-recovery-contract.md) · [第三篇：真实故障](03-real-mysql-journal-failure.md) · [第四篇：批转流](04-batch-to-stream-low-high.md) · [第五篇：Pekko 与 Flink](05-pekko-vs-flink-cdc.md)

> **阅读基线**：源码按工作区 `cc09559` 阅读，实验材料保留各自报告的版本、切点和失败记录。五篇文章不把不同版本的结果合并成一个结论。
>
> **证据标记**：
> - **源码结论**：由当前实现和调用链直接得到。
> - **实际实验**：真实 MySQL、Actor Journal、Sink 或集群运行得到。
> - **保存材料复算**：重新读取既有材料，不等于重新运行。
> - **未验证边界**：当前没有足够证据支持的范围。

> 观测版本：`pekko-reference`，2026-11-13 的独立 CDC Runtime 与真实 KubeSphere 验收材料。
>
> 本文只回答一个问题：**一条已经从 MySQL Binlog 读出的变更，经过 Debezium 回调、L、目标事务 T、Actor Journal J、源端 D 和 Checkpoint 之后，系统凭什么认为它可以从内存中释放，并在重启后继续？**

这不是“Reader 收到一条消息，Sink 写成功一条数据”的问题。回调已经返回，不能证明事件已经写入本地日志；目标事务已经 `COMMIT`，不能证明 Journal 已经记录了目标推进；Journal 已经持久化，也不能证明 Debezium 的 D 已经覆盖这条变更。任意一个位置领先，重启时都可能出现重放、重复或漏数。

本文沿着一条具体的 `UPDATE` 往下追。源码结论、实际运行结果、历史失败证据和未验证边界分别标记，避免把局部测试结果写成端到端保证。

## 1. 先给结论：这套实现保存的是一条可检查的证据链

当前实现把一次变更拆成六个可观察位置：

```text
MySQL row event
    │
    ▼
Debezium SourceRecord + 原始 callback handle
    │  callback 只有在 L 覆盖后才允许返回
    ▼
L: pekko_capture_log（GROUP / BARRIER / ACK / SCHEMA）
    │  CapturedLogReader 按 L sequence 组成 SourceBatch
    ▼
T: Sink 目标事务 + pekko_sync_cdc_group_ledger
    │  commit 返回后只说明目标事务的本地结果
    ▼
J: BootstrapJournal 的有序进度事件
    │  recordProgress 先收到 Journal，再投影 consumer floor
    ▼
D: Debezium JDBC offset/history 的实际消费位置
    │  CaptureAckPaging 证明 D 的 ACK ancestry
    ▼
Checkpoint / GC：只回收被完整证据保护的 L 前缀
```

这条链有三个重要性质。

先把一条事件的证据卡片固定下来，后面的故障时间线都只改变其中一列：

| 位置 | 记录什么 | 当前材料能证明什么 |
|---|---|---|
| SourceRecord | 源库事件身份、事务和表键 | Reader 收到了哪一条事件 |
| L | group、Schema、ACK parent、digest | 回调前缀是否已经持久覆盖 |
| T | 目标 DML 与 group ledger | 目标事务是否可幂等重放 |
| J | execution、batch、revision、receipt | 哪次执行已经被 Journal 接受 |
| D | source offset 与 ACK proof | 哪个源前缀可以安全释放 |
| GC | actual-D、pin、audit、proof | 哪些 L 和证明允许删除 |

第一，**L 是回调释放的前置条件**。`SharedMySQLCapture.accept` 把原始回调放进 `pending`，每条 `SourceRecord` 必须得到组装结果或明确忽略结果；`MySQLCaptureStore.append` 以事务方式写入 `pekko_capture_log` 后，`coverSequence` 才会写 ACK。随后才调用 `CdcBatchCommitHandle.markProcessedAndFinished()`。所以“回调返回”对应的是 L 已覆盖的一个前缀，而不是“目标已经提交”。

第二，**T 的幂等边界在目标库**。`MySQLCdcApplyEngine` 将 CDC group 的证据写入 `pekko_sync_cdc_group_ledger`，再在同一 JDBC 事务中写目标表。重放同一 group 时，ledger 中已有相同证据就返回 `AlreadyCommitted`；证据不同则抛出协议冲突。目标事务的 `COMMIT` 回复未知时，代码将其分类为 `CommitUnknown`，不能把异常当成回滚成功。

第三，**J 和 D 是两个不同的游标**。J 是 Pekko Journal 的业务进度，包含窗口、`deltaAfter`、`batchSequence`、激活证明等；D 是 Debezium offset 表中的源消费位置。`MySQLCaptureStore.verifiedOffsetSequence` 只能在 L 中找到与 D 对应的 ACK，并验证其 ancestry 后，才能把 D 当作已被本地捕获证据覆盖。J 前进或 Sink 返回成功，都不会自动替 D 完成证明。

因此，当前系统的安全规则是：**证据不足时暂停，证据完整时允许释放；重放依靠 ledger 和 immutable L，而不是依靠“这次看起来已经成功”。**

## 2. 具体事件：`id=4` 的 UPDATE 从源端走到目标端

真实验收表 `pekko_cdc_runtime_acceptance.source_rows` 有六个字段：

```sql
id BIGINT PRIMARY KEY,
label VARCHAR(...),
amount DECIMAL(...),
note VARCHAR(...),
updated_at DATETIME(6),
revision INT
```

本轮真实操作把 `id=4` 更新为：

```text
id=4
label=gc-final-update
amount=14.2500
note=gc-live
updated_at=2026-11-13 05:48:00.123456
revision=4
```

随后还插入了 `id=6`，用于验证同一运行中不止一种事件。这里先只看 `id=4`，因为它足够暴露所有确认边界。

### 2.1 Debezium 回调不是一个可直接提交的 Batch

Debezium Embedded Engine 将一批 `SourceRecord` 和一个原始 `CdcBatchCommitHandle` 交给 `DebeziumBatchConsumer`。`MySQLCdcSourceNode.CdcResource` 只是把这个回调交给 `MySQLCdcGroupAssembler` 或 `DebeziumBatchBridge`：

```scala
override def handleBatch(
    records: Vector[SourceRecord],
    commitHandle: CdcBatchCommitHandle
): Unit = {
  if (assembler.nonEmpty) assembler.get.accept(records, commitHandle)
  else bridge.publish(batchId, rows, cursorValue(records.last), commitHandle)
}
```

`CdcBatchCommitHandle` 只提供一个动作：

```scala
trait CdcBatchCommitHandle {
  def markProcessedAndFinished(): Unit
}
```

该句柄的语义来自 Debezium SDK：调用它会通知 SDK 这一批可以被标记为已处理，之后 offset 才可能被 flush。它不包含目标事务状态，也不包含 Journal 状态。把这个句柄当成 Sink 的 ACK，是最容易犯的错误。

### 2.2 `SourceRecord` 先获得稳定身份，再进入组装器

`SharedMySQLCapture.processDataRecord` 先从 Debezium `source` 结构中取出 file、position、row，构造 `CapturePoint`。如果这个点命中某个有效订阅，代码会把 record 的 source offset 中的 collection-order 字段去掉，再解析为 `MySQLCdcEnvelope`：

```scala
val point = CapturePoint(src.getString("file"),
  src.getInt64("pos"), src.getInt32("row"))
val envelope = MySQLCdcEnvelope.decode(copy, source.connectorId)
  .fold(x => throw new IllegalArgumentException(x.message), _.get)
val groupId = MySQLCdcGroup.id(store.protocol, envelope)
```

`MySQLCdcGroup.id` 由协议版本、源 lineage、数据库、表、binlog file、position 和 row 计算 SHA-256。这个 ID 不是数据库主键，也不是 Debezium 的 offset；它是一次源事件在本协议中的稳定出现身份。

对于普通 UPDATE，组装器生成 `single.u` group；对于主键修改，MySQL CDC 会先产生带 `__debezium.newkey` 的 DELETE，再在后续 record 中产生 CREATE。`MySQLCdcGroupAssembler` 暂存半组，并要求两条 record 的 source offset、partition 和 schema 相同，最后形成有序的 `pk.d` / `pk.c` group。半组没有完成前，原始 callback 不会被确认。

### 2.3 L 的写入和原始 ACK

组装器回调 `publish`，进入 `SharedMySQLCapture` 的闭包：

```scala
val group = MySQLCdcGroup.parse(g, store.protocol)
groupCoverage(group.groupId) =
  store.append(group).getOrElse(
    throw new IllegalArgumentException("group lost its durable route"))
```

`MySQLCaptureStore.append` 在自己的数据库事务里完成三件事：锁住 reader lineage、检查路由区间、向 `pekko_capture_log` 插入一个不可变的 `GROUP` frame。`source_key` 唯一，重复出现时要求 digest 和 payload 完全相同；内容冲突会失败。

回调中每个 record 都调用一次 `done()`。只有 `Pending.remaining == 0` 时，`acknowledgeCoveredCallbacks` 才能处理队头：

```scala
val ack = store.coverSequence(ready.offset, ready.references.toVector)
ready.handle.markProcessedAndFinished()
confirmedAckSequence = ack
pending.dequeue()
```

`coverSequence` 再次锁 reader，沿旧 ACK 建立 parent，写入一个新的 `ACK` frame，并把被引用的 barrier 标记为 covered。于是对 `id=4` 而言，第一条可靠事实是：

```text
GROUP(id=4 的 UPDATE) 已在 L 中落盘
ACK(parent=上一条 ACK, references=[该 GROUP]) 已落盘
原始 callback handle 才被调用
```

**源码结论：** 原始 callback 的“返回”证明 L 覆盖了 callback 的完整前缀；它不证明目标端已经写入。

## 3. T：目标事务如何做到重放不产生第二次业务效果

从 L 读取时，`CapturedLogReader.readBatch` 以 sequence 为边界取 group，而不是按当前 MySQL 表重新查询：

```scala
val entries = consumer
  .map(store.readForConsumer(id, after, limit, _))
  .getOrElse(store.read(id, after, limit))
val groups = entries.map { r =>
  require(r.schemaId == sub.schemaId, "capture row schema mismatch")
  MySQLCdcGroup(...).canonicalJson
}
```

它返回 `CdcCapturedBatch`，其中包含 `batchSequence`、`batchId`、`cursor.value` 和 `cursor.upperBound`。`batchId` 根据 execution、source node、partition 和 sequence 计算，重启后如果继续同一 execution，身份可复原；如果是新 execution，目标仍以 group ledger 的稳定 groupId 做幂等保护。

Sink 的核心事务可简化成下面的伪代码：

```scala
connection.setAutoCommit(false)
for ((group, events) <- plannedGroups) {
  val old = SELECT evidence
    FROM pekko_sync_cdc_group_ledger
    WHERE group_id = ? FOR UPDATE
  old match {
    case Some(evidence) if evidence == expected => // replay
    case Some(_) => throw ProtocolConflict
    case None =>
      INSERT INTO pekko_sync_cdc_group_ledger(group_id, evidence)
      applyEvent(target, events)
  }
}
connection.commit()
```

`evidence` 不只包含 groupId，还包含 cursor identity、target table 和完整 group JSON。这样可以区分两种情况：

* 同一 group 重放，返回 `AlreadyCommitted`，目标业务行不再执行第二次；
* 同一 groupId 对应不同内容，直接拒绝，避免把数据冲突伪装成幂等。

`MySQLCdcApplyEngine` 在 `connection.commit()` 抛异常时包装为 `ExecutionFailure.CommitUnknown`。这是一个重要边界：客户端不知道服务端是否已经提交，重试前必须读取 ledger；不能直接 rollback 后假定目标没有变化。

**源码结论：** T 的幂等性由目标库 ledger 和同事务 DML 共同提供；`Committed` 与 `AlreadyCommitted` 都只表示目标事务路径已完成，不能代替 J 或 D 的证明。

## 4. J：为什么目标已经提交，Journal 仍然可能落后

Bootstrap Runner 对每一个有界工作单元先调用 Sink，再更新 Journal。`replay` 的主线是：

```scala
val batch = log.readBatch(...)
admission.writing {
  waitSink(sink.commitBatch(id, s.executionId, batch, _ => ()))
  emit("REPLAY_T", ...)
}
update(_.copy(
  deltaAfter = entries.last.sequence,
  batchSequence = s.batchSequence + 1
))
```

`update` 通过 `BootstrapJournalAccess.update` 读取当前状态、递增 revision，并向 `BootstrapJournal.Write` 发送 CAS。Actor 的 `Write` 只接受满足 expected revision、phase 转移、execution identity、recovery attempts 和 gc checkpoint 不变的状态。写入成功后，`recordProgress` 才产生 `J_COMMITTED` 观察事件。

因此有一个明确的故障窗口：

```text
T committed
    │ JVM 在 Journal.update 之前退出
    ▼
目标 ledger 已有 group，J 仍指向旧 deltaAfter
```

重启时 Runner 从旧 `deltaAfter` 重新读 L。Sink 读到相同 group ledger 后返回 `AlreadyCommitted`，随后 J 才能推进。这个窗口的安全性依赖三件事：

1. L 的 group payload 不可变；
2. ledger 的 evidence 与 replay 内容一致；
3. Journal 仍保留原 execution 或有足够的恢复身份，不能把当前源表快照替换成另一个 batch。

**实验结果：** 旧 Bootstrap 矩阵的 I16 固定过“目标页已经提交、Journal 仍停在第 0 页”，恢复日志中同一窗口第 0 页从 `new=true` 变为 `new=false`，最终 64 行、384 个字段逐字段相等。该结果证明指定窗口能够按 ledger 重放，不证明任意外部副作用也恰好一次。

**未验证边界：** 本文没有把 I16 的 baseline 结果扩大为所有 Sink、所有事务隔离级别或所有 Journal provider 的保证；它只覆盖实验中使用的真实 MySQL 目标、Actor Journal 和固定切点。

## 5. D：offset 不是“读到了”，而是一个需要被 L 反向证明的事实

Debezium 使用 JDBC offset store。真实运行日志中可以看到：

```text
offset table: capture_offsets_986a1b7135f4986150aa5fa0028feeaa
previous offset: binlog.000017 / 325166188 / event=3
```

D 的值由 SDK 的 offset flush 产生，应用代码不能把它当作 Sink commit 的结果。`SharedMySQLCapture.readCheckpoint` 从 state MySQL 读取 offset 表；`verifiedOffsetSequence` 再根据 D 的 offset key 查找 L 中同 offset 的最新 ACK，并调用 `verifyChain` 检查 parent chain。

这一点决定了启动和 GC 的顺序：

```text
读 D
  └─找到与 D 绑定的 ACK
      └─验证 ACK parent / reference / digest
          └─才能说 D 覆盖到某个 L sequence
```

旧版 `CaptureAckProof.inspect` 直接在一次调用里遍历整条链，固定最多 4096 个节点和 15 秒预算。真实集群的 `primary` 已有 7,110 条 ACK，最新 ACK 序号为 7,121，因此 GC 返回：

```text
BLOCKED / ACK proof budget exceeded
```

这个失败不是 Source/Sink 不一致，也不是目标 COMMIT 失败，而是证明算法的单次预算不足。它说明“D 已经有值”和“D 的 ancestry 已经被验证”是两个不同事实。

### 5.1 分页 proof 如何把长链改成可恢复状态机

修复后的 `CaptureAckPaging` 不再把整条 proof 放在一次堆栈调用中。它把 proof identity、head、anchor、next_seq、digest、revision、phase 和 done 写入：

```text
pekko_capture_ack_pages_v1
pekko_capture_ack_page_nodes_v1
pekko_capture_ack_page_refs_v1
pekko_capture_ack_prior_v1
pekko_capture_ack_page_pins_v1
```

每次最多处理 128 个父链节点或引用，处理结果先提交。下一次 tick 或重启可从 `revision`、`next_seq` 和 `next_digest` 继续。只有 `phase=VERIFY`、`done=1`、anchor 在链中且 offset 匹配，GC 才能进入删除计划。

**实际结果：** 真实 KubeSphere PipelineRun `pekko-cdc-runtime-g7kcr` 成功。proof head 为 `7742`，分页状态 `done=1`、revision `242`，ACK 节点 `7731`；GC operation `runtime_315c...` 从 `DELETING 42→44` 变为 `DONE 44→44`，删除 1 个 GROUP，回收 235 字节。Source 与 Sink 的两行六字段完全相等，Source 保留 Binlog Dump 会话且没有未提交 InnoDB 事务。

这次结果证明：长 ACK 链可以分页、可以跨运行推进、proof 未完成时可以安全阻塞，并在 proof 完成后再做 GC。它没有证明 70 万活跃表、无限 ACK 历史、自动扩容或所有并发 B proof。

## 6. 从批到流：B、F、J 与 T/D 的接缝

`BootstrapRunner` 把一次表初始化拆成窗口，而不是把整张表放进一个长事务。

### 6.1 LOW/HIGH 窗口

`scanStep` 为窗口生成固定 spec：`lower`、`upper`、schema fingerprint。先写 LOW fence，再按分页读取源表；读完后写 HIGH fence。LOW 和 HIGH 是源 Binlog 中的控制点，不是 Reader 内存里的两个时间戳。

随后 Runner 从 L 中读取 LOW 到 HIGH 之间的 group，折叠到窗口 manifest，再按 page 应用目标。manifest 中保存 LOW/HIGH、raw page hash、groupRoot 和 page 证明。Journal 只接受 manifest hash 不变的后续进度。

### 6.2 F：全量转持续流

当最后一个窗口完成，Runner 写 F fence：

```scala
emit("BEFORE_F")
val f = fence(id + "_F", "F", context("F"))
update(_.copy(
  phase = "REPLAYING",
  fence = Some(f.id),
  deltaAfter = logStore.subscription(id).get.bSequence.get
))
```

F 的意义是：初始化已经覆盖到一个固定的源点；随后从 B 之后的 L 前缀回放到 F，目标 activation receipt 验证通过后，Journal 才能进入 STREAMING。进入 STREAMING 后，`deltaAfter` 是 L sequence，不是 binlog position；D 仍由 Debezium offset 表维护。

**源码结论：** F 是状态机中的交接证明，不是“最后一条 UPDATE 的时间”。

## 7. 真实故障顺序：哪些窗口自动重放，哪些必须暂停

把上面的状态放进真实故障时间线，可以得到四类不同结论。

### 7.1 T 已提交，J 未持久化

```text
L GROUP/ACK 已存在
T ledger + target DML 已提交
J revision 尚未写入
进程退出
重启读取旧 J → L replay → ledger AlreadyCommitted → J 前进
```

在 I16 中，最终 64 行、384 个字段相等。这是“可重放”的窗口。

### 7.2 J 已持久化，D 或 raw ACK 仍滞后

J 的 `deltaAfter` 只会在 `sink.commitBatch` 成功后更新，但原始 Debezium callback 的 ACK 仍由 `SharedMySQLCapture.acknowledgeCoveredCallbacks` 管理。若进程在 L ACK 或 SDK offset flush 之间退出，重启必须重新检查 D 的 ACK ancestry；不能凭 J 的 revision 直接前移 D。

当前代码在证明不足时进入启动门或 GC 的安全暂停。这个结果是正确的安全边界：它宁可重复读取已持久化 L，也不把未经证明的 D 当成已经消费。

### 7.3 COMMIT 回复丢失

目标端 commit 结果未知时，`MySQLCdcApplyEngine` 返回 `CommitUnknown`。恢复归属在 Sink：先查询 batch/group ledger，再决定 `AlreadyCommitted`、重试或暂停。Journal 不应单独决定目标是否已提交。

### 7.4 两个原始 ACK 之间崩溃

`pending` 是队列，ACK 只能按 callback 前缀推进。队头尚未 `markProcessedAndFinished` 时，后续 callback 即使已经进入内存，也不能跳过队头。重启后，SDK 可能重放队头及其后续记录；L 的 occurrence unique key 和 group ledger 负责去重。若无法证明原始 source connection 已关闭，新的 owner 不应创建 Reader。

历史真实材料中，旧 worker 的连接列表曾经为空，但五秒窗口内仍有一条未归属事务；随后该事务消失。这类结果被记录为 `OWNER_RELEASE_UNPROVEN`，不能被后续排空成功覆盖。资源释放证明属于 reader/guardian 层，不等同于数据已经对账。

## 8. 失败证据必须保留：ACK proof 的预算反例

为了说明“为什么不能只看最终行集”，把这次真实失败单独列出。

旧实现面对 7,110 条 ACK 时返回 `ACK proof budget exceeded`。当时 Source 与 Sink 的业务行仍可以对账相等；失败发生在“是否有资格回收 L”的证明阶段。若把这个失败删掉，只保留之后的通过结果，读者会错误地认为 ACK proof 从未遇到长链边界。

修复后分页 proof 通过，但另一个证据仍未闭合：分页 proof 表自身的长期容量、并发 B proof、无限 ACK 历史和 70 万活跃表没有验证。文章必须同时保留：

| 层级 | 本轮事实 | 不能推导出的结论 |
|---|---|---|
| Reader/回调 | 原始 callback 只有 L 前缀覆盖后才返回 | 不能推导目标已提交 |
| Sink/T | MySQL ledger 对同一 group 重放返回 `AlreadyCommitted` | 不能推导 Journal 已持久化 |
| Journal/J | I16 固定窗口可在重启后推进 | 不能推导所有 provider 都一致 |
| D/ACK proof | 7,110 条 ACK 的旧一次性证明失败；分页 proof 通过 | 不能推导无限历史不需要容量治理 |
| Source/Sink 对账 | 两行六字段完全相等 | 不能推导 70 万活跃物理表已验收 |

## 9. 逐字段对账：它证明了什么，没证明什么

本轮 `reconciliation.json` 的字段顺序是：

```text
id, label, amount, note, updated_at, revision
```

Source 与 Sink 均得到：

```text
4 | gc-final-update | 14.2500 | gc-live | 2026-11-13 05:48:00.123456 | 4
6 | gc-final-insert | 15.5000      | row-six | 2026-11-13 05:48:01.123456 | 0
```

`sameRowCount=true`、`sameRowsAndFields=true`。这证明在该真实 MySQL、该目标表、该运行版本和该故障窗口下，最终可见行集一致。

它不证明：

* 目标事务只执行过一次；`AlreadyCommitted` 可能是重放后的第二次观察；
* 每一条 row event 都有独立的线上日志映射；本轮保存的材料没有把 `id=4` 的 binlog row event 与某个 L sequence 逐条导出；
* D、J、T 的采样在同一数据库事务中同时发生。它们是不同存储的观察，时间上可能错位；
* 删除、DDL、主键跨回调、未知 B、自动接管等其他边界已经通过。

因此逐字段对账是端到端的必要证据，但不是单独的正确性证明。它需要和 T/J/D 的切点、ACK 顺序、连接与事务采样一起阅读。

## 10. 读源码时最值得记住的四条不变量

### 不变量一：ACK 只能覆盖连续前缀

`pending` 按 callback 顺序排队，`while (pending.headOption.exists(_.remaining == 0))` 只处理队头。任何中间 callback 未完成，后续 callback 不能让 D 越过它。

### 不变量二：L 的 occurrence 不能改变内容

`pekko_capture_log.source_key` 唯一；相同 source key 再次写入时 digest 和 payload 必须相同。重放可以重复执行“发现”，不能改变历史 frame。

### 不变量三：目标 ledger 与事件内容必须同时匹配

同一 groupId 已存在但 evidence 不同，必须报 `ProtocolConflict`。只有 evidence 完全相同才返回 `AlreadyCommitted`。

### 不变量四：证明未完成时不能做 GC

`CaptureAckPaging.statusOn` 要求 proof identity、pin、parent digest、anchor 和 prior head 均成立；GC 只删除 `done=1` 且不是当前 actual-D、也没有被 audit/frame/anchor 引用的 proof。证明分页状态本身先持久化，删除后仍依靠受保护恢复点。

## 11. 当前结论和剩余边界

### 已由源码确认

1. Debezium 原始 callback handle 被保存在私有 pending 队列中，不能直接暴露给 Sink。
2. L 的 GROUP、ACK、BARRIER、SCHEMA frame 由 `MySQLCaptureStore` 事务写入，并具有 occurrence/digest 约束。
3. Sink 目标事务和 group ledger 在同一 JDBC 事务中提交，重放结果区分 `Committed` 与 `AlreadyCommitted`。
4. Journal 通过 revision CAS 接受进度；`recordProgress` 在 Journal 成功后再投影 consumer floor。
5. D 必须通过 ACK ancestry 验证；分页 proof 把长链验证拆成可恢复步骤。

### 已由实际实验确认

1. 真实 MySQL、Actor Journal、Debezium Reader 和 MySQL Sink 在指定运行中可完成 Source/Sink 全量逐字段对账。
2. 目标已提交、Journal 滞后的指定 I16 窗口可按 ledger 重放并收敛。
3. 7,110 条 ACK 的一次性 proof 会触发预算失败；分页 proof 可在 128 节点步长下跨运行完成。
4. proof 完成后，旧 proof 可按引用保护规则回收，Source Binlog Dump 仍在且没有未提交 InnoDB 事务。
5. 完整本地回归为 722 成功、0 失败、415 取消；取消项仍需要各自外部环境，不能当作真实故障矩阵全部通过。

### 仍未验证或只保留失败证据

* 70 万张真实活跃物理表的连接数、binlog 吞吐和长期 ACK/L 容量；
* DDL 传播、共享 Reader 跨实例、多 owner 自动接管；
* commit reply 丢失后所有目标事务隔离级别的自动恢复；
* 分页 proof 表自身无限增长、并发 B proof 和 proof state 二次 GC；
* source fencing 版本锁在跨库竞态中的完整端到端证明；
* 把 `id=4` 的这次行事件逐条映射到线上 callback、L sequence、T、J 和 D 的统一 trace。

最后一项很容易被忽略。当前系统已经有足够的身份、digest 和 sequence 让每一层独立校验，但本轮实验材料没有保存一条“同一个业务 UPDATE 的全链路 trace ID”。所以本文可以严谨地说明**代码如何保证边界、实验最终如何对账、长链 proof 如何恢复**，但不能伪造一条不存在的事件级日志链。要把这一点补齐，下一步应在 `observe`、target ledger evidence、Journal progress 和 D checkpoint 中记录同一个 groupId，而不是只追加一条成功日志。

## 附录 A：关键源码入口

* [`SharedMySQLCapture.scala`](../../../../../common/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/SharedMySQLCapture.scala)：Reader owner、callback pending、L 覆盖、原始 ACK、启动门。
* [`MySQLCdcGroupAssembler.scala`](../../../../../common/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/MySQLCdcGroupAssembler.scala)：普通事件和主键 DELETE/CREATE 的跨 callback 组装。
* [`MySQLCaptureStore.scala`](../../../../../common/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/MySQLCaptureStore.scala)：L frame、ACK parent、B/F fence、D 反查。
* [`CapturedLogReader.scala`](../../../../../common/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/CapturedLogReader.scala)：从 L 构造 checkpoint-aware `CdcCapturedBatch`。
* [`MySQLCdcApplyEngine.scala`](../../../../../common/src/main/scala/cn/xuyinyin/magic/connectors/mysql/sink/MySQLCdcApplyEngine.scala)：目标事务、group ledger、`CommitUnknown`。
* [`BootstrapRunner.scala`](../../../../../common/src/main/scala/cn/xuyinyin/magic/cdc/bootstrap/BootstrapRunner.scala)：LOW/HIGH、F、replay、J progress 和 GC checkpoint。
* [`BootstrapJournal.scala`](../../../../../common/src/main/scala/cn/xuyinyin/magic/cdc/bootstrap/BootstrapJournal.scala)：revision CAS、恢复 receipt、Journal 事件状态。
* [`CaptureAckPaging.scala`](../../../../../common/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/CaptureAckPaging.scala)：长 ACK ancestry 的分页持久化证明和 proof GC。

## 附录 B：真实材料和失败材料

* [ACK proof 预算修复与真实集群验证](../../../experiments/2026-11-12-runtime-gc/cluster-live-20261113/gc-ack-proof-budget-20261113/README.md)：旧 4096/15 秒预算失败、分页 proof 通过、Source/Sink 对账。
* [分页 proof 表 GC 与容量边界](../../../experiments/2026-11-12-runtime-gc/cluster-live-20261113/gc-ack-proof-gc-20261113/README.md)：旧 proof 回收、当前 proof 保留、容量与未验证边界。
* [最终 acceptance](../../../experiments/2026-11-12-runtime-gc/cluster-live-20261113/final/README.md)：B/F、重启、资源状态、逐字段对账和完整回归摘要。
* [历史初始化故障矩阵](../../../experiments/2026-10-25-bootstrap/README.md)：I08、I11、I16、I21 的固定窗口与失败证据。

这四份材料的时间、源码版本和实验入口不同。阅读时应以每个目录中的 `README.md`、状态快照、原始日志和 checksum 为准，不把不同版本的通过项相加成一项未执行的保证。
