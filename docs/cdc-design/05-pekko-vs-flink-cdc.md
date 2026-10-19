# 五：70 万表的 CDC 平台，Pekko 与 Flink CDC 各自解决什么

研究 Flink CDC 的初衷，是把可靠的数据同步能力带进自己的 Pekko 项目。经过前四篇，判断已经比“Actor 轻、Flink 重”具体得多：项目有适合大量任务生命周期管理的基础；旧协议的重分批失败已经复现，新增完整组实现又通过了三个指定恢复样本。共享阻塞池的组件饥饿证据仍然存在，不能因恢复修复而删掉。

我的选择倾向是：**保留 Pekko 作为海量任务的控制底座；是否由它同时承载自建 CDC 数据内核，要按复制范围、恢复要求和团队维护成本单独决定。** 70 多万表使细粒度控制值得投入，但不会自动证明当前一表一 Engine 的执行方式更省资源。

本篇保留项目 `d88ec80` 的历史反例，并把 `65ab541` 的完整组实现纳入当前能力；Flink CDC 对照仍固定为 **3.6.0** 固定源码 `bf160337ff558d70302e7884f68937611873117e`，Flink 运行时概念参照 **1.20** 文档。本轮重新阅读 SourceReader、SnapshotSplitAssigner、HybridSplitAssigner 和 BinlogSplitReader；没有进行两套系统同负载性能实验。前一组网站 Flink CDC 文章使用自己的 3.7.0 研究基线和 Paimon 实验，不能混入本篇成为 3.6.0 的实测结论。[版本与材料](README.md)

## 1. 比较之前，先把业务承诺写成同一份

“支持 70 万表”至少可能指四种不同能力：目录能存 70 万个表定义；70 万个控制实体可寻址；70 万张真实表持续被捕获；或这些表在故障后仍能满足一致性和恢复时限。

创建很多对象只能验证第二类的一部分。少量真实表的正确性测试也不能与对象数量相乘，变成大规模端到端证明。

为比较两种方案，需要固定同一组业务输入：表分布在哪些源实例、多少表同时变化、总变更字节率、事务大小、Schema 数量和变更频率、可接受延迟，以及停止一张坏表时允许影响多少其他表。还需要指定目标：只要求最终行值收敛，还是目标 DML 副作用也不能重复，是否要求源事务整体可见。

这些条件直接改变实现成本。例如最终收敛可以容忍的某些重复写，不一定满足审计一次性；表级暂停若不能阻塞同源其他表，就需要额外的持久缓冲或更细捕获分组。框架名称不能替代这些约束。

**公平的部署单位也必须一致。** Flink CDC 的 MySQL Pipeline 配置可以匹配多张表，单表不必对应一个 Flink Job；TaskManager、slot、算子子任务与表也没有一一对应关系。[MySQL 多表配置](https://nightlies.apache.org/flink/flink-cdc-docs-release-3.6/docs/connectors/pipeline-connectors/mysql/)、[Flink 执行架构](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/concepts/flink-architecture/)

因此，不能拿“Pekko 的未来共享读取设计”去比较“Flink 每表独占一个作业”。双方都应允许合理分组，同时承担同样的隔离和恢复要求。

## 2. Pekko 的优势：让每个任务都有身份，却不各自拥有线程

第一篇已经沿 Mailbox 和 Dispatcher 解释了执行模型，并用 10,000 个普通 Actor、两条工作线程、40,000 条消息进行了机制验证。它说明大量实体能够共享执行资源，不是持久化 CDC 的规模验收。

这种模型与任务控制很匹配。一张表可以拥有独立的配置版本、暂停原因、重试阶段和运行身份；针对该表的命令按 Actor 状态机串行处理。调用者使用 workflowId 寻址，不需要追踪承载它的具体线程。

项目现有 [`WorkflowSharding`][sharding] 与 [`EventSourcedWorkflowActor`][actor] 已有这样的骨架：前者按 workflowId 路由实体，后者持久化定义和执行状态，恢复时重建仍在运行的工作。业务可以围绕实体表达“暂停快照但保留注册”“某租户耗尽配额”“等待人工确认 Schema”等状态。

优势来自控制粒度与生命周期表达，而不是免费的业务语义。比如 pause 命令返回究竟意味着“不再接收新数据”，还是“已经排空并持久化所有在途数据”，仍需协议定义。Actor 串行接受命令，不会自动使外部 Stream 在同一时刻停止。

### 当前实现还没有兑现的两层收益

第一，remember entities 开启使自动钝化关闭。当前不能声称所有冷任务只保留磁盘身份、不驻留实体。若要启用钝化，必须先处理 Actor 停止会取消 Engine、内部完成消息又不更新 Shard 活动时间的问题。

第二，当前单表 Source 创建独立 Debezium Engine，每个运行中的 Engine 有外层 runner；空 Bridge 读取还会阻塞共享 JDBC 线程池。[Source][source]、[EngineAccess][engine-access] 这意味着控制 Actor 的线程复用，没有自动变成整个 CDC 路径的资源复用。

这些差距没有否定选择 Pekko 的理由。它们说明：要把模型优势变成业务收益，昂贵的采集和写入资源必须按明确预算管理，而不能全部挂在一表一个长期执行实例上。

## 3. Flink CDC 的优势：快照交接已经是一套运行状态机

如果只把 Flink CDC 看成“读 MySQL，再写 Sink”，就看不到它真正节省了多少自建工作。本轮阅读 3.6.0 的 Source 实现，可以看到它如何保存扫描阶段和日志阶段之间的关系。

[`MySqlSnapshotSplitAssigner.snapshotState`][flink-assigner] 保存已处理表、剩余 split、已分配 split、表 Schema、各 split 完成 offset、分配器状态和 chunk splitter 状态。这比给每张表记录一个最大主键多得多：恢复需要知道哪些范围已交出，哪些完成信息已收到，哪些工作可以重新分配。

全部快照 split 完成后，分配器也不总是立即切换。在并行度大于 1 的对应路径，它等待包含完成状态的 Checkpoint 成功，再通过 `notifyCheckpointComplete` 推进状态；并行度为 1 有直接完成分支。这个细节很重要，不能概括为“任何情况下都等待一个 Checkpoint”。

Reader 一侧也保留独立状态。[`MySqlSourceReader.snapshotState`][flink-reader] 不只保存正在读的 split，还保存已经完成但未收到确认的 snapshot split、不完整 binlog split，以及被挂起的 binlog split。完成消息发出但确认尚未到达，是可恢复状态的一部分，而不是一个可随便丢掉的内存标记。

然后 [`MySqlHybridSplitAssigner.createBinlogSplit`][flink-hybrid] 根据各快照 split 的完成水位构造日志 split：保存表、范围与对应高水位，选择恢复起点，并在元数据过大时分组传递。日志 Reader 再按相应范围与水位过滤已由快照覆盖的事件。[`BinlogSplitReader.shouldEmit`][flink-binlog]

这条链说明 Flink CDC 的价值并不只是提供一个 JDBC 扫描器。它已经把并行扫描、完成确认、Checkpoint、恢复分配与日志去重衔接成一个协议。具体算法仍有前提与边界，但项目若要自建同级能力，就必须承担这些状态关系。

当前 Pekko 项目有 Debezium initial snapshot，另有主键上界与 keyset 分页的有界 Source。它们都不能仅凭名字替代这套 split 与水位协议。Actor 很适合承载状态机，但状态机要保存哪些信息、何时推进，需要另行设计与验证。

## 4. 两种恢复路径，承担责任的位置不同

当前 Pekko 路径主要依赖应用自行安排：

```text
目标事务 T → Actor Journal 确认 J → Source ACK → Debezium offset D
```

第二篇说明了这条顺序保护什么；第三篇分别保留旧批次账本失败与新完整组恢复样本。第四篇的最小生产路径已经实现完整物理行组、成员身份、目标组证据及原始回调前缀确认。当前以单 writer、固定 Schema、可信 streaming 基线和固定未重置日志历史为前提；并没有因为顺序未变，就获得所有权切换或所有故障点的保证。

Flink 的托管状态则通过 Checkpoint 建立执行图的恢复关系：Source 的读取状态与算子状态共同参与快照，失败后回到兼容的恢复切面，再处理之后的数据。这使业务开发者不必为每个有状态算子重新设计独立 Journal 与源位点如何协调。[状态化流处理](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/concepts/stateful-stream-processing/)

不过，外部数据库不会因为 Flink 内部状态恢复就自动回滚。最终提交语义取决于 Sink 是否参与相应的事务、提交或幂等协议，必须逐个连接器判断。[Connector 容错保证](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/connectors/datastream/guarantees/) 一个普通外部调用可能在 Checkpoint 之后执行成功，恢复重放时又执行一次。

因此，合理的对照不是“Pekko 没有一致性，Flink 天然全链路 exactly-once”，而是：Flink 已有一套托管状态的协调运行时，外部 Sink 契约仍需验收；Pekko 项目当前需要自己承担更大部分的数据恢复协议。

### 小到一个主键修改，也有应用必须承担的恢复责任

2026-10-19 的[真实身份实验](../experiments/2026-10-19-source-record-identity/README.md)发现，同一次主键修改的 d/c 共享完整 restart offset。只确认 d 后恢复，c 不再交付；稳定 eventId 无法补救源不再交付的成员。工程责任因而扩展到完整性分组，而不是只增加一张去重表。

[真实 Engine/Bridge 探针](../experiments/2026-10-19-deferred-ack/README.md)进一步验证：在 Bridge 前装配完整组、暂存半组并返回回调，再按原始回调前缀调用保留句柄，能够通过两个固定跨界场景。实际 D 在测试覆盖信号前不推进；旧句柄在后续回调中串行调用。这是历史组件门槛，覆盖是手动信号，不能与后来的真实生产 T/J 实验混为同一次运行。

这类边界不会因为 Actor 更轻就消失，也不能据此断言另一框架在同一故障窗口已经通过。比较仍要回到具体版本、恢复单位与实测证据。后续 [完整组生产验收](../experiments/2026-10-19-group-recovery/README.md)把真实 Source、Actor JDBC Journal 与 MySQL Sink 接起来，在 T 已提交/J 未持久化处验证了 16→1 拆批、1→实际 72 组的合批，以及主键 [d][c]。三组各 64 行、六字段零差异，已经提交的组没有新增审计；后续新写也收敛。关闭、迟到/错误 ACK和预算有组件检查，内容冲突与 PK 双成员回滚用 H2 验证；完整作业取消、J 已提交/D 滞后、COMMIT 回复丢失仍未固定实测。

这改善了本项目特定 Source/Sink 的恢复事实，但不是与 Flink 同负载的对照，也不覆盖新的 snapshot 初始化、DDL 或多 owner。不能从“修复三个样本”直接推导“已经获得 Flink 全部恢复能力”。

### 有状态计算会显著改变取舍

只有确定性的逐行复制时，事件级目标去重可以成为较窄的方案。加入窗口聚合、双流关联、定时器或缓存中的中间状态后，光记录“源读到哪里、目标写到哪里”就不够了。

例如累计金额保存在某个中间对象里：源重放了，累计状态却没有回到同一恢复点，可能重复累加；累计状态回退了，Sink 却已经保留新结果，又需要提交协调。每增加一类状态，就增加一致恢复的证明义务。

项目当前 CDC 可靠路径限制为直连 MySQL Apply，[WorkflowValidator 的显式校验](https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/WorkflowValidator.scala#L78)拒绝内联 Transform；项目没有实现任意分布式算子状态快照。Pekko Stream 提供数据流组织能力，不意味着自动获得 Flink 的有状态计算恢复协议。若业务很快要进入复杂计算，自建范围会从“复制协议”扩展到“数据执行运行时”。

## 5. 资源成本应看曲线，而不是比较框架空载大小

设 N 为注册表数，R 为驻留 Actor 数，G 为捕获/部署分组数，λ 为总业务事件率，b 为平均提交批量。还要记录实际队列字节、Schema 大小、最大事务与恢复积压。

对经过资源分离的 Pekko 方案，可以建立以下设计账本：

```text
控制状态：N 的目录成本 + R 的实体状态成本
捕获基线：G × 每组读取器与连接成本
数据工作：真实变更字节的解码、转换与目标写入
提交证据：批次确认与事件级去重的写入、索引和保留
缓冲：尚未确认数据、慢表积压、大事务与可能新增的 spool
```

当前实现还未完成捕获资源分离，不能把其 Engine 项直接按较小的 G 计算。先实测运行工作流数与 Engine、线程、连接之间的斜率，才知道资源成本是否已经按预期缩减。

对 Flink 多表分组，基线包括作业协调、TaskManager/子任务、网络缓冲与状态后端，数据工作同样受真实负载约束，Checkpoint 和 Sink 提交也有成本。增大每组表数可以摊薄基线，却同时扩大元数据和故障影响范围。

### 5.1 元数据不能因为表很冷就忽略

假设每表常驻元数据净占 4 KiB，70 万张约为 2.67 GiB；16 KiB 则约为 10.68 GiB。这里只是容量算术，不是 Actor 内存测量，也没有包括索引、字符串共享、JVM 和副本。

Flink Source 的 tableSchemas、split 完成信息等同样随表数和分片数增长。3.6.0 的 Hybrid Assigner 会将过大的 split 元数据分组传输，这解决单次传递大小，不表示系统不再需要保存这些状态。

所以“冷表不消耗数据 CPU”与“冷表没有元数据成本”是两回事，两套系统都要测。

### 5.2 细粒度持久化也有写放大

当前项目每批推进 Actor Checkpoint。如果平均批量为 b，稳定情况下与数据相关的进度事件率大致受 λ/b 影响，此外还有控制事件；这是理想化估算，不是运行测量。

海量稀疏表可能每次只有一条变更，难以在单表内攒大批，Journal 确认成本就难以按总事件量充分摊薄。新实现的完整组账本增加查询、写入、索引及规范化成员证据存储；主键组一次保存两个成员，但并没有免除事件级信息的持久成本。即使 Actor 本身几乎不占 CPU，持久化数据库也可能先达到瓶颈。

如果为了摊薄成本跨表合批，又要回答一个表失败是否回滚整批、所有权如何验证，以及确认覆盖怎样拆回各表。这是资源复用与隔离粒度之间的真实取舍。

不能忽略这些成本，再用空 Actor 测试宣称 Pekko 更省；也不能忽略 Flink 的 Checkpoint、状态与部署基线，只看高吞吐时的摊销结果。

## 6. 用一个慢表场景检验“表级隔离”

设同一 MySQL 捕获域中，A 表因不兼容 Schema 暂停，B 表继续高频更新。业务要求 A 修好后不丢数据，B 的延迟还要保持可接受。

Pekko 能把 A 的控制实体置为 blocked，并继续响应 B 的控制命令。但这只完成控制隔离。若 A/B 共用一条日志读取和确认进度，A 的未完成记录仍然是确认前缀中的缺口。

此时只有明确的数据策略才能兑现业务要求：整个捕获域背压；把 A 的未完成数据可靠写入可重放存储；或调整捕获分组，承担额外连接与读取开销。把 A 的事件暂存在内存邮箱，继续推进 D，然后等待以后处理，无法承受进程崩溃。

Flink 也要通过实际作业分组、Schema 处理行为、数据通道与 Sink 策略回答同一问题。把多表放进同一 Pipeline 可以复用运行时，却可能共享背压和恢复范围。拆成更多 Pipeline 可以改善隔离，也会增加基线资源和运维对象。

因此，隔离不是 Actor 或 Job 名称附带的一项布尔能力。需要分别衡量：

| 隔离对象 | 要观察的问题 |
|---|---|
| 控制命令 | A 出错后，B 的查询、暂停、恢复能否继续响应？ |
| 数据吞吐 | A 的队列满后，B 是否被反压？ |
| 持久进度 | B 完成后，是否错误越过 A 的缺口？ |
| 进程故障 | 一个节点退出时，需要恢复哪些任务或分组？ |
| 人工修复 | 修复 A 是否需要回滚或重新初始化 B？ |

第一篇的组件实验已经展示一种控制与执行的差别：默认 Dispatcher 上的 Actor 仍能响应，JDBC 池却已被八个空读取占满。控制面健康不等于数据面有进展。

## 7. 恢复成本往往比稳态内存更影响选型

如果 K 个实体要恢复，实际存储与恢复处理能力为每秒 q 个，那么仅实体恢复就存在约 K/q 的理想化吞吐下界。q 必须测量，不能用线程数代替；Journal 大小、快照命中、连接建立和源端启动都会改变它。

当前 Pekko 配置记住活跃实体，默认实体恢复策略为 all。即使改为 constant，上游限速也是按 shard，不自动形成整个集群的统一数据库预算。能够创建很多 Actor 与能够同时恢复它们及其 Engine，是两个问题。

数据追平还需要计算剩余处理能力。用同一种工作量单位表示积压 B、稳定输入速率 λ、可用处理速率 μ；只有 μ>λ，才可能持续缩小积压。简化追平时间约为：

```text
追平时间 ≈ B / (μ − λ)
```

这不是本项目测试结果，也没有计入启动、Schema 阻塞和目标长尾。它说明如果平时已接近满载，故障后的恢复时间可能远大于停机时间。源日志保留窗口必须覆盖实际恢复与追平需要，而不是只覆盖 Actor 重启的几秒钟。

Flink 的恢复同样要承担状态加载、Source 重读和 Sink 协调成本。较大的作业分组有利于摊薄稳态开销，却可能一次恢复更多状态。因此应比较完整的恢复曲线和受影响表数，不能只比较进程 RSS 的一个截图。

## 8. 当前事实、目标设计和框架能力要分栏

| 维度 | 当前 Pekko 项目 | 若继续自建需要承担 | Flink CDC / Flink 对应机制 |
|---|---|---|---|
| 表级控制 | 持久 workflow Actor、按 ID 分片 | 细粒度命令契约、配额、钝化与唤醒 | 多表作业分组及外部控制平台；不是每表必有独立 Job |
| 执行资源 | 本地 Stream、每单表 Engine、共享阻塞池 | 捕获与 writer 预算、等待与取消、慢表隔离 | 并行子任务、共享运行时资源、背压；仍需配置预算 |
| 恢复 | 旧失败保留；新组实现通过指定 T/J 缺口的拆批/合批/主键恢复，原始前缀确认与全字段/审计可查 | 其余故障窗口、多 owner、证据保留、旧点与升级语义；不能把局部验收写成全协议完成 | 托管状态 Checkpoint 与连接器恢复；外部 Sink 语义仍需逐个验收 |
| 全量到增量 | Debezium initial snapshot 与独立分页 Source | split、水位、回填和阶段切换协议 | 现成的 snapshot/binlog split 状态机，具体前提仍需验证 |
| Schema | 身份、类型及兼容性检查 | 数据版本、排空边界、DDL 幂等恢复 | Schema 事件链与具体 Sink 的能力约束 |
| 目标系统 | 本系列实测 MySQL Apply | 每种 Sink 都需提交与重放契约 | 连接器生态可复用；不能把一种 Sink 结果推广给全部 |
| 大规模证据 | 普通 Actor 机制实验 + 旧协议反例 + 三组各 64 行的新恢复样本 | 真表数量、吞吐、延迟、集中恢复分级验证 | 本轮没有同负载容量或性能对照 |

中间一列是工程责任，不是已经交付的能力。右列是对应机制，也不是对任意配置与外部系统的无条件保证。

## 9. 三条可选路径，各自应在哪个边界负责

**继续自建 Pekko CDC 内核。** 适合可以收窄到特定 Source/Sink、确定的复制语义、少量转换，并且团队愿意长期维护源事件身份、类型、DDL、日志保留和故障接管的场景。收益是控制与资源政策高度可定制；代价是每扩展一种源、目标和计算形态，都扩大证明与回归矩阵。

对当前项目，重分批恢复的最小路径已落地，并在受控样本中通过；默认时区偏移和共享阻塞池问题仍未修复。接下来扩大故障窗口、验证完整作业取消与长期重放，比直接扩到更多连接器更有判定价值。这项优先级不能省略初始化、类型和副作用边界。

**Pekko 管理任务，Flink CDC 执行分组数据链路。** Pekko 保留表目录、租户配额、生命周期和运维入口，Flink 负责选择好的作业分组中的采集、状态和 Sink 提交。这样可以保留 Actor 的业务控制优势，同时复用已有数据运行时。

组合也不是零成本：启动请求超时但 Flink Job 实际已经创建，如何避免重复启动？Actor 的暂停状态与外部作业状态如何协调？Job ID、配置版本与恢复点由谁持久保存？这些属于控制协议，需要幂等命令和状态协调。当前项目没有实现这套组合，不应把架构图当成可用功能。

**直接围绕 Flink CDC 构建平台。** 如果核心需求已经是复杂有状态计算、多个目标和成熟数据恢复能力，减少自建内核范围通常更合理。代价是业务侧细粒度控制仍需要外部平台，不能期待一个 Pipeline YAML 自动表达所有租户生命周期。

这三条路径不要求一次性选定终局。可以先保留 Pekko 的控制模型，用最窄的真实复制范围检验自建内核的维护成本，再决定扩大还是引入外部执行引擎。判断依据应是可交付边界和实测结果，而不是对已有投入的偏好。

## 10. 什么样的对照实验才足以改变判断

后续比较应固定硬件、依赖、Source/Sink、Schema、数据分布、正确性标准和恢复点策略。分组数允许双方合理调优，但必须记录，由此产生的隔离差异不能隐藏。

建议把结果分别画成随规模变化的曲线：

| 变量 | 两边都要测量的结果 | 避免的错误替代 |
|---|---|---|
| 注册表数 N、Schema 大小 | 元数据内存、加载时间、查询响应 | 空 Actor 数代替真实表目录 |
| 活跃比例、事件字节率 | CPU、RSS、队列字节、端到端 P99、源连接 | 平均延迟代替热点与大事务 |
| 分组数 G | 基线资源、慢表影响范围、操作复杂度 | 一方池化、一方逐表独占 |
| 故障积压 B | 恢复时间、追平时间、日志保留需求 | 进程启动成功代替数据恢复 |
| DDL 与目标故障 | 缓冲版本、重复副作用、全表逐字段结果 | 任务 running 代替数据正确 |

合成控制目录可以先测，但必须与真实 MySQL 表数量分开报告。当前10,000 Actor 机制实验与三组各 64 行的新数据库恢复属于不同层级，不能组合成 70 万表结论；新恢复样本也没有测量组账本的吞吐、内存或长期存储斜率。第四篇 7,696 个抽象组合也不能替代一次真实提交恢复。

基于现有证据，我会继续发展 Pekko 的任务控制能力，因为它与海量长期身份、独立状态机和细粒度运维匹配；对 CDC 数据内核则保持有明确边界的投入。Flink CDC 提供的价值，是已经把大量采集、分片、状态与恢复关系落实成可复用运行时。学习它的目的，是带回这些协议约束，而不是只复制接口、或提前宣布自建方案胜出。

上一篇：[恢复协议与验证矩阵](04-protocol-and-validation.md)；[返回系列目录与验证范围](README.md)；[Flink CDC 深入系列](https://xuyinyin.cn/flink-cdc-series/)。

[sharding]: https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/sharding/WorkflowSharding.scala#L38
[actor]: https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/actors/EventSourcedWorkflowActor.scala#L164
[source]: https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/MySQLCdcSourceNode.scala#L129
[engine-access]: https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/DebeziumEngineAccess.scala#L185
[flink-assigner]: https://github.com/apache/flink-cdc/blob/bf160337ff558d70302e7884f68937611873117e/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/main/java/org/apache/flink/cdc/connectors/mysql/source/assigners/MySqlSnapshotSplitAssigner.java#L420
[flink-reader]: https://github.com/apache/flink-cdc/blob/bf160337ff558d70302e7884f68937611873117e/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/main/java/org/apache/flink/cdc/connectors/mysql/source/reader/MySqlSourceReader.java#L124
[flink-hybrid]: https://github.com/apache/flink-cdc/blob/bf160337ff558d70302e7884f68937611873117e/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/main/java/org/apache/flink/cdc/connectors/mysql/source/assigners/MySqlHybridSplitAssigner.java#L207
[flink-binlog]: https://github.com/apache/flink-cdc/blob/bf160337ff558d70302e7884f68937611873117e/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/main/java/org/apache/flink/cdc/connectors/mysql/debezium/reader/BinlogSplitReader.java#L289
