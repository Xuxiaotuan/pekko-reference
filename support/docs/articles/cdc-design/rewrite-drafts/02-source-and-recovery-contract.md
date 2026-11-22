# 二：一条 ACK 链如何成为可恢复的 T/J/D 协议

[系列导读](README.md) · [第一篇：常驻 CDC](01-why-pekko-for-resident-cdc.md) · [第二篇：恢复协议](02-source-and-recovery-contract.md) · [第三篇：真实故障](03-real-mysql-journal-failure.md) · [第四篇：批转流](04-batch-to-stream-low-high.md) · [第五篇：Pekko 与 Flink](05-pekko-vs-flink-cdc.md)

> **阅读基线**：源码按工作区 `cc09559` 阅读，实验材料保留各自报告的版本、切点和失败记录。五篇文章不把不同版本的结果合并成一个结论。
>
> **证据标记**：
> - **源码结论**：由当前实现和调用链直接得到。
> - **实际实验**：真实 MySQL、Actor Journal、Sink 或集群运行得到。
> - **保存材料复算**：重新读取既有材料，不等于重新运行。
> - **未验证边界**：当前没有足够证据支持的范围。

> 本文只回答一个问题：当目标事务已经提交、Journal 还没有留下进度，或者 Source 的回调和提交回复丢失时，系统凭什么决定“可以重放”，而不是凭一个 offset 猜测“应该已经成功”？
>
> 结论先说：本项目没有把 `D` 当作唯一真相。要把某个批次推进到受保护的恢复、发布或 GC 边界，必须同时核验目标事务身份、Journal receipt、ACK 祖先链以及 owner/source fence 的代际关系。任意一项无法证明，状态停在安全暂停；普通重放可以依靠目标账本和已有 Journal 证据继续，但不能越过尚未证明的源前缀。

本文依据当前源码和 2026-11-13 的真实集群材料撰写。源码路径是仓库相对路径，实验材料位于 [`support/docs/experiments/2026-11-12-runtime-gc/cluster-live-20261113/`](../../../experiments/2026-11-12-runtime-gc/cluster-live-20261113/)。历史失败记录不因为后续重跑通过而删除。

## 1. 先把三个字母说清楚：T、J、D 不是同一个 offset

CDC 恢复最容易出现的错误，是把三个不同组件里的位置压缩成一个“当前进度”。这里把它们分开：

- **T（Target transaction）**：目标 Sink 对一组事件执行的业务事务，以及目标侧的幂等账本。T 记录的是“这组事件是否已经作用于目标”，不是 Source 读到了哪里。
- **J（Journal）**：Actor Journal 或 Bootstrap Journal 中已经持久化的执行进度、receipt 和 execution identity。J 记录的是“哪一个执行尝试、哪一个批次、哪一个证明已被持久化”，不是 MySQL binlog 的当前位置。
- **D（Debezium/Reader offset）**：Reader 当前看到或已经提交的 Source offset。只有经过 ACK 证明覆盖的 D，才可以作为恢复输入；一条回调参数或内存变量不构成 D 的持久事实。
- **L（capture log）**：共享 MySQL Reader 写入的 append-only 事件日志。ACK、BARRIER、GROUP 等帧通过 `seq`、`digest` 和 parent/reference 组成可验证的祖先关系。

把它们放在同一条时间线上，最危险的窗口是：

```text
Reader 读到 D ──> T 提交 ──X──> J 持久化
                         进程崩溃
```

这里的 `X` 不是语义上的失败。T 可能已经提交，也可能只收到一个丢失的 COMMIT 回复；J 可能完全没有写入；D 也可能已经推进。恢复程序不能因为“目标查到了数据”就把 J 和 D 一起向前移动，因为查询到一行数据并不证明它属于当前执行、当前 schema、当前 source fence。

恢复的最小判定可以写成：

```text
advance(J, D) 只有在
  targetReceipt(T, groupIdentity) = verified
  ∧ journalReceipt(J, executionIdentity) = verified
  ∧ ackProof(L, D, B, anchor) = verified
  ∧ ownerFence(source, guardian, generation) = live
时成立。
```

`verified` 是带身份和版本的持久证据，不是布尔回调。

## 2. 模型假设：证明依赖哪些前提

本文后面的推理依赖以下假设。它们不是“系统天然具备的真理”，而是需要由运行环境或更上层协议提供的前提。

1. **L 只追加，不就地改写。** `pekko_capture_log` 中的 ACK 帧有稳定的 `seq` 和 `digest`；归档副本可以重建帧，但不能改变原有身份。
2. **ACK parent 和 references 指向更早的帧。** parent 链严格向低序号前进，引用不能形成环；引用目标的 digest 必须和帧本身一致。
3. **B 的意图和 Source fence 已被持久化。** `task、offset、head、anchor、intentDigest、sourceFence、guardianAttempt` 共同确定一次 proof identity。只知道表名和 offset 不够。
4. **目标 Sink 提供可核验的 group identity。** 目标账本能区分同一组事件的重放与另一组事件。若 Sink 只有“最终行值”，没有组身份或幂等约束，T 的提交状态不能被可靠反推。
5. **Journal 的 receipt 可按 `persistenceId、executionId、sequence/revision` 重新读取。** 数据库投影只能作为索引或观察值，不能取代 Journal 原始事件。
6. **owner、guardian、连接和 source fence 属于同一代。** PID、进程名或“连接列表为空”都不能单独证明旧 owner 已隔离。
7. **源端事务和目标端事务不是一个原子事务。** 本协议证明的是“事件组及其证据可重放”，不声称跨 MySQL、Journal、Sink 的两阶段提交。

这些前提被破坏时，系统应返回“不确定”并暂停；不能把前提缺失转换成“恢复成功”。

把这些假设压缩成恢复检查表，阅读源码时可以按下面五个问题定位：

| 检查 | 证明对象 | 失败后的最小动作 |
|---|---|---|
| 身份 | task、generation、owner、source fence 是否同代 | 拒绝复用旧 proof |
| 内容 | parent、reference、digest 是否组成同一条祖先链 | 停止 proof，保留原 L |
| 目标 | ledger 是否匹配当前 group identity | 重放或报告 `evidence_incomplete` |
| Journal | receipt、revision、execution 是否可重读 | 不推进 J，不消费恢复预算 |
| 资源 | 旧连接、事务、guardian 是否已经隔离 | 保留 UNKNOWN，禁止新 owner 执行 |

## 3. Proof identity：为什么不能只保存 offset

当前实现把一次 ACK 证明的身份定义为：

```scala
final case class CaptureAckPageIdentity(
  task: String,
  offset: String,
  head: Long,
  anchor: Long,
  intent: String,
  sourceFence: String,
  guardianAttempt: String,
  priorHead: Option[Long] = None,
  priorDigest: Option[String] = None
)
```

源码位置：`common/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/CaptureAckPaging.scala:7-10`。

`proofId = SHA256(canonical(identity))`。身份中有三类信息：

- `offset、head、anchor`：当前 D、实际 ACK head 和回填窗口锚点；
- `intent、sourceFence、guardianAttempt`：这次 B 意图、源端 fencing 代次和 owner 身份；
- `priorHead、priorDigest`：跨 D 继续推进时，旧证明必须位于新证明的已验证祖先中。

pinned witness 还写入 `capture、lineage、intentSeq、headDigest、mode`。`mode` 只有 `PENDING、COVERED、REPLAY` 三种值；解析函数会拒绝未知字段、未知模式、错误 digest 或不匹配的 prior head。

**源码结论：** identity 的作用是防止“同一个 task 的另一次执行”复用旧证明。`CapturePinnedProof.verifyOn` 先读取 B descriptor，再用 descriptor 的 `head、anchor、intentDigest` 构造 identity；`verifyExactOn` 重新计算 descriptor 和 `proofId`，同时锁定当前 head 并比较 `headDigest`。源码位置：`CaptureAckPaging.scala:39-70`。

**未验证边界：** identity hash 是状态库内的完整性检查，不是外部签名；有数据库管理员权限的对手可以同时改写状态表和日志表。guardian 的控制面和数据库账号隔离仍是部署边界，不能把 hash 当成抗管理员篡改证明。

## 4. 源码调用链：从恢复入口到 ACK ancestry

生产恢复路径可以简化成下面这条调用链：

```text
CdcManagedRecovery.recover
  ├─ fencePendingB / issueFencedPermit
  │    └─ CdcTaskStore.withFenceState
  │         └─ CapturePinnedProof.verifyOn
  │              └─ MySQLCaptureStore.pendingBDescriptorOn
  │                   └─ CaptureAckPaging.beginOn / stepOn
  ├─ CdcTaskStore.withReadyProofState
  │    └─ CapturePinnedProof.verifyExactOn
  │         └─ currentDOn + advancedIfCompleteOn
  │              └─ verifyAdvancedOn (priorHead/priorDigest)
  └─ readerRecoveryGate
       └─ recordFenceReleased / Journal verifier / target receipt
```

`CdcManagedRecovery` 的注释已经写明：旧 owner 的 source fence、Journal 和 reader release 必须在同一恢复许可中重新核验；COMMIT 回复丢失时，只接受一个新事务读回完整持久 binding 的结果。源码位置：`common/src/main/scala/cn/xuyinyin/magic/cdc/platform/CdcManagedRecovery.scala:20-112`、`:170-253`。

`CdcTaskStore` 维护的是控制索引，不是 kernel Journal、原始 L、SDK D 或目标 receipt 的替代品。初始化时创建 `pekko_cdc_b_recovery_v1`、`pekko_cdc_b_fence_v2`、`pekko_cdc_b_receipt_v2`、`pekko_cdc_b_proof_anchor_v2` 等表；源码位置：`common/src/main/scala/cn/xuyinyin/magic/cdc/platform/CdcTaskStore.scala:44-73`。

`withReadyProofState` 的关键顺序是：锁定 owner、任务、recovery permit、fence、proof anchor；对旧 witness 做 `verifyExactOn`；再读取当前 D；只有 `advancedIfCompleteOn` 得到 `COVERED`，并且新 witness 携带旧 head/digest，Journal 才能进入可恢复状态。源码位置：`CdcTaskStore.scala:467-495`。

这条顺序避免了一个常见但错误的实现：先读当前 D，再回头找旧证明。两个读取之间若 Source 继续追加 ACK，读到的 D 可能来自另一条 lineage，回放便失去了边界。

## 5. CaptureAckPaging：把一条长链拆成可重启的工作

旧实现 `CaptureAckProof.inspect` 在 `MySQLCaptureStore.scala:11-38` 中一次遍历 parent 链，最多 4096 个 ACK、15 秒时间预算和 65536 个 reference。这个上限在短测试中足够，在常驻任务中不是正确性上限，而是一个会把真实数据拒绝掉的工程上限。

分页实现把证明拆成两阶段：

- `BUILD`：从实际 D 的 head 向 parent 链移动，逐个持久化 ACK node 和 reference；
- `VERIFY`：从同一个 head 再走一次，重新读取帧、比较 digest 和引用；若 identity 携带 prior head，还必须把 prior row 标记为 `verified`。

`stepOn` 每次最多处理 128 个 reference 或 ACK 转移，并以 `revision` 做乐观并发检查。核心逻辑见 `CaptureAckPaging.scala:236-296`：

```scala
require(limit > 0 && limit <= 128)
val state = selected(c, id, lock = true)
require(state("revision").toLong == expectedRevision && state("done") == "0")
...
require(target < seq, "capture proof cycle")
require(store.proofFrame(c, target)("digest") == digest,
  "capture proof digest conflict")
...
val done = finished && phase == "VERIFY"
UPDATE pekko_capture_ack_pages_v1
SET next_seq=?, next_digest=?, ref_index=?, revision=revision+1,
    phase=?, done=?
WHERE ... AND revision=?
```

因此“进程在第 73 页退出”不会回到头部猜测。重启时读取 page 的 `next_seq、ref_index、revision、phase`，要么继续同一 proof，要么因为 identity 或 revision 不一致而拒绝。

分页表不等于 Source 日志。`pekko_capture_ack_pages_v1`、`...nodes_v1`、`...refs_v1` 是可丢弃的证明缓存；原始 `pekko_capture_log`、Journal 和目标账本保留，后续可以从 L 重新建立 proof。源码注释和表结构见 `CaptureAckPaging.scala:116-165`。

## 6. T/J/D 故障时间线：如何处理“提交结果未知”

以一组事件 `G` 为例，设它已经有稳定的 `groupIdentity`，Source 端 B 也已经被 fence：

```text
1. Reader 捕获事件，L 写入 ACK，D 观察到 head = h0
2. Sink 以 groupIdentity=G 执行 T
3. T COMMIT 返回前进程断开
4. Journal 尚未写入 execution receipt，D 也可能尚未回调
5. guardian 重启，旧 owner 被隔离，读取 permit/fence/witness
6. 查询目标账本是否存在 G，并读取 Journal 原始 receipt
7. 在同一 state transaction 中 verifyExact(old witness)
8. 读取当前 D，要求 verifyAdvanced(new witness) 覆盖 B 且 prior=head h0
9. 目标已存在 G：记录 Journal 的恢复结果，不重复产生业务效果
10. 目标不存在 G：只在 permit、source fence、Journal 和 ACK proof 都可重建时重放
```

这里有两个不同的异常：

- **明确回滚**：目标账本明确记录失败或事务回滚，可以释放当前 permit，重新从同一 groupIdentity 重放。
- **结果未知**：连接断开、超时或 COMMIT reply 丢失，但目标账本没有足够证据判断。此时“再执行一次”可能重复副作用，“直接推进 D”可能丢数据。协议只能先做隔离和只读 readback；readback 仍不能建立完整身份时，进入安全暂停。

`CdcTaskStore.consumeFencePermit` 把 Journal receipt、execution identity、source receipt 和 permit 一起锁定；源码位置：`CdcTaskStore.scala:208-227`。`readerRecoveryGate` 在实际 Reader 启动前再次校验 owner、source lock、permit、Journal 与 release proof；源码位置：`CdcTaskStore.scala:380-465`。

**源码结论：** 代码为“提交结果未知”提供了读回路径和安全失败路径，并没有把网络异常自动分类成提交成功。

**实验结论：** 真实故障材料覆盖了提交回复丢失、异常退出和重启后的 source/target 对账；详细切点保存在 [`support/docs/experiments/2026-11-09-persistent-cutover-authorization/`](../../../experiments/2026-11-09-persistent-cutover-authorization/)。这些材料证明了若干已实现窗口的结果，不等同于覆盖所有 Sink 提交实现或所有网络错误码。

## 7. LOW/HIGH、B 与 F：为什么窗口回填需要 ACK 祖先证明

全量初始化不是“读完表再打开 binlog”。正确的顺序是：先建立 B（增量保留边界），再固定 LOW，读取分页快照，写 HIGH，回填 LOW—HIGH 窗口，最后在 F 点把全量输出和增量重放接成一个连续流。

在这个过程中，B 的控制事务可能和目录写入、marker、业务行事件共存。Reader 必须把控制事件绑定到实际 binlog 事务和 ACK ancestry；不能只按 `event_count == 1` 或单一 marker 行数决定“捕获已生效”。本文只讨论恢复契约，完整的 LOW/HIGH 与 B→F 算法见第一篇和第四篇。

对恢复而言，关键不是 LOW/HIGH 的名字，而是以下关系：

```text
旧 proof(head=h0, anchor=a, intent=i)
        │
        ├─ current D proof(head=h1, priorHead=h0, priorDigest=digest(h0))
        │       └─ B barrier ∈ ACK references
        └─ target group ledger / Journal execution identity unchanged
```

`verifyAdvancedOn` 会拒绝以下情况：B 仍是 `B_PENDING`；当前 descriptor 的 `anchor/intentDigest` 改变；新 proof 没有 `priorHead/priorDigest`；当前 D 没有通过 `completeModeOn == COVERED`。源码位置：`CaptureAckPaging.scala:72-112` 和 `CdcTaskStore.scala:489-495`。

这就是“从批到流”的恢复边界：批次不是一个可以任意删除的文件，而是一条被 Source ACK ancestry、Journal execution 和目标 group ledger 同时夹住的证据链。

## 8. 真实长链实验：4096 的上限为什么不够

### 8.1 失败证据

真实集群的 `pekko_capture_log` 已有 7,110 条 ACK，最新 ACK 序号 7,121。旧 `CaptureAckProof.inspect` 固定最多遍历 4096 条，于是 GC 实际 D proof 返回 `ACK proof budget exceeded`。失败材料见 [`gc-ack-proof-budget-20261113/failure-before.txt`](../../../experiments/2026-11-12-runtime-gc/cluster-live-20261113/gc-ack-proof-budget-20261113/failure-before.txt)。

这次失败说明的是 proof 算法预算不适配长链，不是 Source/Sink 行数据已经不一致。不能把 “ACK proof budget exceeded” 写成“目标丢数据”，也不能因为后续修复成功而抹掉它。

### 8.2 分页实现与运行结果

部署分页 proof 后，真实运行观察到：

| 证据 | 结果 |
| --- | --- |
| Actual-D proof | `head=7742`，`done=1`，`revision=242` |
| ACK nodes | `7731` |
| Source/Sink 行数 | 均为 2 |
| 字段对账 | `id、label、amount、note、updated_at、revision` 逐字段相等 |
| Source 运行态 | 1 个 Binlog Dump 会话，无未提交 InnoDB 事务 |
| Runtime owner | `ACTIVE`；任务 `STREAMING / ADMISSION_READY / HEALTHY / RELEASED` |

逐字段结果在 [`reconciliation.json`](../../../experiments/2026-11-12-runtime-gc/cluster-live-20261113/gc-ack-proof-budget-20261113/reconciliation.json)，证明分页可以推进到 DONE 并完成目标对账。运行材料同时记录了重启前后 proof 状态；这属于真实集群运行结果，不是单元测试推断。

### 8.3 proof GC 的独立验证

随后建立新的 actual-D proof 后，旧 proof `d9b4921a...`（head 7742、7731 nodes、7740 refs）被回收；当前 proof `f777e7d3...`（head 13052，nodes/refs 各 12,672）仍保留。GC 只删除 `done=1`、非当前、未被 audit/frame/proof anchor 引用的 proof，并且先删 refs、nodes、prior、pin，最后删 page。源码位置：`CaptureAckPaging.scala:164-197`；真实材料见 [`gc-ack-proof-gc-20261113/`](../../../experiments/2026-11-12-runtime-gc/cluster-live-20261113/gc-ack-proof-gc-20261113/)。

这次结果证明的是“旧分页证明可安全回收且 Source/Sink 仍逐字段相等”。它没有证明 proof 表会无限扩展后自动压缩，也没有证明 70 万活跃表的容量。

## 9. 反例：哪些看似合理的推进会漏数据或重复执行

### 反例一：只保存 `D.offset`，跨重启从 offset 继续扫

新连接在不同快照或不同 source lineage 中从 `D.offset` 继续读取，可能看见不同可见性窗口。没有 ACK head、anchor、source fence 和 prior digest，程序无法证明“offset 以前的范围已经被覆盖”。正确行为是读取旧 witness；旧证据不完整时暂停，而不是把 offset 当作已提交事实。

### 反例二：目标行存在，就认为 T 已成功

目标表当前值相同，可能来自上一轮执行、人工修复或另一条任务。没有 group ledger 的 identity，行值无法证明当前 T 的提交结果。系统应要求目标 receipt/ledger 与 execution identity 一致；否则只能报告 `evidence_incomplete`。

### 反例三：ACK head 更新了，就认为 B 已被覆盖

当前 D 比旧 head 新，并不等于新 ACK ancestry 包含 B。`verifyAdvancedOn` 要求 `barrier` 在新 proof 的 references 中，且旧 head/digest 作为 prior 被重新验证。缺任一项都不能进入 `COVERED`。

### 反例四：分页 proof 被删了一半，重启时从剩余节点拼接

如果 page、node、reference、pin 不在同一个可验证身份下，残余数据可能构成一条“看起来完整”的伪链。`statusIfPresentOn` 会检查 orphaned pin/prior/node/ref；`statusOn` 会拒绝 identity、phase、anchor 或 prior verification 不一致。状态损坏应暂停并重建 proof，不能降级为空 proof。

### 反例五：连接列表为空，直接接管旧 owner

连接关闭只说明一个瞬时观察；旧事务可能在另一个会话、代理或延迟回滚路径中仍然存在。guardian 隔离证明、source account、owner attempt 和事务排空必须同时满足。历史材料中曾出现“连接列表为空但未归属事务在 5 秒窗口末仍存在”的失败，随后事务消失；后一次成功不能覆盖前一次失败。

## 10. 现在能说到哪里

### 已由源码明确的结论

- ACK proof identity 包含 source lineage、B intent、guardian attempt、head/anchor 和可选 prior head。
- proof 以 InnoDB 状态表持久化，`BUILD → VERIFY`，每步最多 128 个引用或 ACK 转移，可按 revision 跨重启继续。
- `verifyExactOn` 验证旧证明，`verifyAdvancedOn` 验证新 D 覆盖 B 且承接旧 head；Journal 只在这些条件成立后推进。
- proof GC 不删除原始 L、Journal、目标账本，只删除完成、未引用且非当前 proof 的分页材料。

### 已由真实实验确认的结果

- 7,110 条 ACK 的长链会触发旧 4096 budget 失败。
- 分页 proof 可在真实 MySQL、Journal 和 Sink 环境推进到 DONE；Source 与 Sink 两行六字段逐字段相等。
- 新 proof 建立后，旧 proof 可被回收；Source ACK/L、Journal 和目标账本仍保留。
- 未提交 InnoDB 事务为空时，运行态保留一个 Binlog Dump 会话是持续 CDC 的正常状态。

### 仍未验证或不应外推的边界

- 70 万张真实活跃表的 Reader、proof 表和连接容量。
- 多个并发 B proof、超大 references、恶意篡改和数据库管理员级对手。
- DDL、复合主键、字符串排序键、跨表源事务原子性。
- proof state 表自身的长期归档/GC，以及 ACK 历史无限增长时的容量曲线。
- metric-only 的删除计数持久化修订。其 KubeSphere PipelineRun `p6jr9` 长时间 `RUNNING` 后被取消，材料明确标为 `evidence_incomplete`；当前通过的是功能性 proof 删除，不是该指标修订。

## 11. 小结：恢复协议的核心不是“保存进度”，而是保存可验证的关系

T、J、D 各自记录不同事实。L 把 Source 的 ACK 和 ancestry 固定下来，proof identity 把 B、owner、lineage 与 head 绑定起来，Journal receipt 把执行身份固定下来，目标 ledger 把重放与提交结果固定下来。恢复时，程序要重新验证这些关系；任何关系断裂，结果就是安全暂停。

这套设计的代价是状态表更多、恢复步骤更长、证明需要分页，也不能替代源端 fence 或目标幂等。收益是：当 COMMIT 回复丢失、进程重启或 ACK 链超过短期预算时，系统不会用一个“看起来合理”的 offset 代替事实。

---

## 附录 A：关键源码和证据索引

| 内容 | 路径 |
| --- | --- |
| proof identity、pinned witness、exact/advanced verify | `common/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/CaptureAckPaging.scala:7-112` |
| 分页 BUILD/VERIFY、revision、128 步预算 | `.../CaptureAckPaging.scala:236-310` |
| proof GC 候选、引用保护、删除顺序 | `.../CaptureAckPaging.scala:164-197` |
| capture descriptor、Journal projection、ACK coverage | `common/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/MySQLCaptureStore.scala` |
| fence receipt、Journal consume、reader recovery gate | `common/src/main/scala/cn/xuyinyin/magic/cdc/platform/CdcTaskStore.scala:170-227, 380-495` |
| source fence、guardian 和恢复入口 | `common/src/main/scala/cn/xuyinyin/magic/cdc/platform/CdcManagedRecovery.scala:20-253` |
| 长链预算失败与分页结果 | `support/docs/experiments/2026-11-12-runtime-gc/cluster-live-20261113/gc-ack-proof-budget-20261113/` |
| proof GC 与逐字段对账 | `support/docs/experiments/2026-11-12-runtime-gc/cluster-live-20261113/gc-ack-proof-gc-20261113/` |

## 附录 B：阅读源码时的三个检查点

1. 看到 `D` 时，先问它是不是由 ACK proof 覆盖；裸 offset 不是恢复许可。
2. 看到 `Journal` 时，先问它是原始事件、持久 receipt，还是数据库观察投影；三者不能互换。
3. 看到 `COMMIT unknown` 时，先问能否锁定同一 source fence、owner attempt、target group identity 和 Journal execution；不能回答完整时，安全暂停就是正确结果。
