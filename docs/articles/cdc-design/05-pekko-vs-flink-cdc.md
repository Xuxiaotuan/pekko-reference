# 五：海量常驻 CDC 的自建边界——Pekko 控制面与数据运行时的取舍

[系列导读与验证范围](README.md) · [1：目标架构](01-why-pekko-for-resident-cdc.md) · [2：批转流源码](02-source-and-recovery-contract.md) · [3：真实实验](03-real-mysql-journal-failure.md) · [4：恢复协议](04-protocol-and-validation.md) · [5：自建与复用](05-pekko-vs-flink-cdc.md) · [Flink CDC 深入系列](https://xuyinyin.cn/flink-cdc-series/)

> **文章修订：2026-10-26；代码基线 [`55fad0c`](https://github.com/Xuxiaotuan/pekko-reference/tree/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059)。** 本文依据源码与保存材料论证，没有重跑数据库。版本、验证范围和未覆盖项统一见[系列导读](README.md#全系列验证范围汇总)。

一个实例的表目录很大，今天同步五张，明天增加两张，还要单独取消、恢复。本文回答：为这种长期复制任务，哪些控制与数据职责值得自建，哪些应复用成熟运行时？前四篇的源码与故障证据支持若干机制选择，但没有提供 Pekko 与 Flink CDC 同负载的总体成本或性能胜负。

本项目固定代码 `55fad0c`；Flink CDC 源码对照沿用 3.6.0 固定提交 `bf160337ff558d70302e7884f68937611873117e`。另一组[Flink CDC 系列](https://xuyinyin.cn/flink-cdc-series/)使用自己的 3.7.0、Flink/Paimon 实验，不能混作同版本、同 Sink 的对照。

## 1. 先给双方相同的业务承诺

两种方案都应允许合理共享：表目录 N 约 70 万，订阅 S 由 5 增加到 7，活动写入速率和事务大小另外固定。新增 f/g 先初始化再跟随，其他表继续推进；取消的含义、可接受积压和故障恢复结果也必须一致。

Flink CDC 的 MySQL Pipeline 可以选择多表，表与 Job、slot、子任务没有固定的一一对应。[官方多表配置](https://nightlies.apache.org/flink/flink-cdc-docs-release-3.6/docs/connectors/pipeline-connectors/mysql/)因此，“70 万个 Actor 对比 70 万个 Flink Job”不是这个业务的有效实验输入。

公平比较还要固定目标语义。本项目的当前样本使用 MySQL Apply 和独占空目标；Flink 系列中的 Paimon 提交与快照机制不能直接作为同一种 Sink 成本。最终行值、业务副作用次数、跨表事务可见性也是不同承诺。

## 2. 现有正面证据支持哪些自建动机

| 业务需要 | 当前可复查证据 | 得到的结论 |
|---|---|---|
| 订阅变化不等于再复制一套采集器 | 共享 capture 的动态 B/K/L 路径与阶段一指定实验 | 采集身份可以与表消费者分离；不是全实例 Reader 数已有全局上限 |
| 新表先全量再持续 | I01 五表后新增 f/g；第二篇的 LOW/HIGH、封存、B→F 路径 | 当前受限数据集已跑通这条组合 |
| 范围和输出有独立恢复依据 | [I11](03-real-mysql-journal-failure.md#e-i11)、[I16](03-real-mysql-journal-failure.md#e-i16)的原 spec、新 attempt 和原 receipt | 能把恢复决策变成持久化事实核对 |
| 一张表短期落后时另一张继续 | 共享捕获 R14 中表级 T/J 分别推进 | L 在有限积压内解耦目标应用；未证明无限隔离 |
| 大量控制身份共享线程 | 历史普通 Actor 机制实验 | 支持消息调度模型；尚无海量持久 CDC 任务成本曲线 |

这些证据说明自建方向有具体抓手：独立身份、动态控制和显式恢复事实。它们没有比较同负载内存、吞吐、控制延迟和长期维护成本，因而还不能证明经济性优势。

## 3. 控制面可以围绕业务组织，协议语义仍需自己完成

一张表的控制状态不只是 RUNNING。新增请求可能尚未获得 B，初始化可能已封存但未激活，实时可能已暂停，取消可能已受理但资源尚未排空。Actor 适合按身份串行接受这些命令，Event Sourcing 适合保存其接受事实。

与业务紧密相关的部分包括表选择、generation、租户预算、暂停处置、维护停机和用户可见状态。它们可以自建在 Pekko 上，但“串行接收 Cancel”不能替代目标事务结束、K 覆盖和资源释放确认。

当前已有 BootstrapJournal 与旧工作流/静态调度基础；新的共享捕获/初始化链路仍未接通完整冷索引、激活调度和 API/UI。当前 Runner 持续占阻塞线程、会话锁与默认 8 个 run 许可。因此控制模型适合任务表达，是架构动机；冷任务只占元数据则仍是待实现、待测的收益。

## 4. 数据面已经复用了什么，自建又增加了什么责任

本项目没有从零编写 MySQL binlog 解析器。它复用 Debezium SDK、JDBC 和 Pekko 持久化能力，自建的是它们之间的持久日志、完整组确认、范围封存与目标证据协议。

| 层次 | 当前复用或自建 | 继续维护的责任 |
|---|---|---|
| 原始采集 | 复用 Debezium；自建共享 capture、L 与原始 ACK 前缀协调 | SDK 启动/offset 阶段、Schema 与日志保留成本，版本兼容 |
| 初始化 | 自建固定范围、LOW/HIGH、fold、FINAL 与 B→F | 读视图丢失、完整日志前提、类型/约束和激活恢复 |
| 表执行进度 | 复用 Actor Journal，自建 BootstrapProgress 与 receipt 协调 | 明确拒绝、结果未知、取消与资源占用的完整生命周期 |
| 分布式状态运行时 | 当前没有通用执行图 Checkpoint | 若加入 Join、窗口、定时器等状态计算，需要额外一致恢复设计 |

Flink 的价值包括现有的 Source 分配、Reader 状态、Checkpoint 及运行时协调；外部 Sink 仍需各自的事务/幂等提交契约。[Flink 状态恢复说明](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/concepts/stateful-stream-processing/)若复制需求很快扩展为复杂状态计算，自建范围会明显变大，目标组账本不能替代算子状态的一致恢复。

## 5. 用一次批转流比较责任位置，而非比较术语数量

当前 Pekko 路径是固定 U、串行范围、LOW/HIGH 局部回填、FINAL 封存，再完整回放 B→F 并审计激活。Flink CDC 3.6.0 的[MySqlHybridSplitAssigner.createBinlogSplit](https://github.com/apache/flink-cdc/blob/bf160337ff558d70302e7884f68937611873117e/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/main/java/org/apache/flink/cdc/connectors/mysql/source/assigners/MySqlHybridSplitAssigner.java#L207)则使用 snapshot split 范围与完成 offset 构造 binlog split 及完成信息，并与其 Reader/分配器状态衔接。

这些机制承担相近问题，但不是相同算法。本项目的 F 不是全局 Checkpoint；当前范围窗口也没有 Flink 的并行领取与完成确认协议。保存 FINAL 和完整 L 使恢复材料显式，却增加持久写入、保留空间、恢复审计和 B→F 应用工作。

[第四篇 G4](04-protocol-and-validation.md#g4)给出当前算法在明确前提下的逐键推理；[第三篇 E0](03-real-mysql-journal-failure.md#e-startup)又显示必要输入可因 INITIAL-D 恢复被越过。局部算法存在证明，不代表组合中的每个前提已经被实现保障；其他系统也应按固定版本和同一故障窗口分别判断。

## 6. 慢表隔离的收益必须和日志保留一起计算

假设 a 表目标暂停十分钟，b 表仍有写入。把 L 放在中间，可以先保存完整输入，让 b 按自己的游标提交；但 a 恢复所需的历史不能被删掉。隔离的资源代价是积压字节、保留时长、追赶工作和共享捕获域的容量风险。

当前实验支持指定短期窗口；没有完成长期 GC 和新链路多 owner 接管。若 Reader 或 Schema 异常影响整个 capture 域，单表 Actor 也不能独立修复采集连续性。旧静态快照的 guardian 隔离实验不能自动覆盖这条新路径。

因此成本要同时看常驻执行、额外状态写入、日志保留、恢复停顿，以及开发运维工作。只测空载框架内存，会漏掉自建协议的大部分代价。

## 7. 什么证据能够支持下一步取舍

如果主要困难是大量长期身份、细粒度动态控制，而数据语义能够保持在受限复制范围内，那么继续验证 Pekko 控制面与共享数据内核有明确目标。要把动机提升为实际优势，还需固定同源同目标负载，测冷目录、有限活动集合、动态订阅延迟、恢复时长及存储增长。

如果需要广泛连接器、成熟分布式状态计算与执行图恢复，复用对应运行时能减少自建责任。Pekko 只管理任务、数据面交给成熟 CDC 运行时也是可评估方案，当前项目未实现这一组合；不能把它列成已经交付的第三条路径。

下一轮有效比较应预先写下控制命令语义和验收阈值，在同样的 5→7 表故事中测量收益与代价，而不是让框架名称决定结果。当前最可交付的判断是：控制职责值得独立设计，数据正确性需要复用成熟机制并逐项证明；本项目已提供一些正面机制与恢复样本，尚未证明总体成本更低。

## 技术附录与证据入口

[历史源码分析与实验档案](appendices/05-framework-comparison-history.md)保留本篇此前全文；[附录索引](appendices/README.md)集中提供版本、完整矩阵和原始材料入口。当前主文只保留支撑核心论点所需的实现、代表性证据和边界。
