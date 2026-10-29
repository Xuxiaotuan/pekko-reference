# 五：海量常驻 CDC 的自建边界——Pekko 控制面与数据运行时的取舍

[系列导读与验证范围](README.md) · [1：目标架构](01-why-pekko-for-resident-cdc.md) · [2：批转流源码](02-source-and-recovery-contract.md) · [3：真实实验](03-real-mysql-journal-failure.md) · [4：恢复协议](04-protocol-and-validation.md) · [5：自建与复用](05-pekko-vs-flink-cdc.md) · [Flink CDC 深入系列](https://xuyinyin.cn/flink-cdc-series/)

[A/B 补测](../../experiments/2026-10-27-ab-followup/README.md)支持对具体准入与资源释放边界的分析，但矩阵仍未完成；这些数据不是 Pekko/Flink 吞吐、资源占用或高可用能力的同负载比较。

> **文章修订：2026-10-27。** 已提交的启动准入与五个 Bootstrap 定点结果见 [`74b6d9a`](https://github.com/Xuxiaotuan/pekko-reference/tree/74b6d9ad1ebcfe19a786f4e1869aed276a63bb52)及[A1/A2 报告](../../experiments/2026-10-27-capture-admission/README.md)；本轮平台候选的源码版本、实测与未验证项见[平台实验报告](../../experiments/2026-10-27-cdc-platform/README.md)。没有 Pekko/Flink 同负载 benchmark，也没有 70 万真实源表容量结果。

一个实例的表目录很大，今天同步五张，明天增加两张，还要单独取消、恢复。本文回答：为这种长期复制任务，哪些控制与数据职责值得自建，哪些应复用成熟运行时？前四篇的源码与故障证据支持若干机制选择，但没有提供 Pekko 与 Flink CDC 同负载的总体成本或性能胜负。

本项目按各实验的冻结版本分别判断，不能把旧通过数当作新候选已回归；Flink CDC 源码对照沿用 3.6.0 固定提交 `bf160337ff558d70302e7884f68937611873117e`。另一组[Flink CDC 系列](https://xuyinyin.cn/flink-cdc-series/)使用自己的 3.7.0、Flink/Paimon 实验，不能混作同版本、同 Sink 的对照。

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
| 冷身份不占一份活动执行资源 | 当前 SQL 索引、分池 grant、活动 Journal Actor 与 bounded step 源码 | 支持资源按活动量分配的设计；真实成本以新报告为准，不能由普通 Actor 机制实验推算 |

这些证据说明自建方向有具体抓手：独立身份、动态控制和显式恢复事实。它们没有比较同负载内存、吞吐、控制延迟和长期维护成本，因而还不能证明经济性优势。

## 3. 当前实现选择了 SQL 冷控制与活动 Actor，而非每表常驻 Actor

一张表的控制状态不只是 RUNNING。新增请求可能尚未获得 B，初始化可能已封存但未激活，实时可能已暂停，取消可能已受理但资源尚未排空。Actor 可以按身份串行接受命令，Event Sourcing 可以保存接受事实；但这不要求把所有冷身份实例化成 Actor。

本轮平台的[实际装配](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/cdc/CdcBootstrapKernel.scala)是：SQL 保存定义、期望状态、候选索引、owner 与 grant；少量活动任务创建 `BootstrapJournal` Actor；阻塞 JDBC/Sink 工作在受预算限制的 step worker 上执行。一个共享 Reader 继续捕获源变化，L 解耦采集与每表应用。控制台从持久投影分页查询，不遍历所有 Actor 发 ask。

```mermaid
flowchart LR
  Command[HTTP 持久受理] --> Index[SQL task 索引 / owner / grant]
  Index --> Workers[有限 snapshot 与 tail workers]
  Workers --> Actor[活动 BootstrapJournal Actor]
  Reader[共享 Debezium Reader] --> Log[持久日志 L]
  Log --> Workers
  Workers --> Sink[目标页 receipt / 组账本 T]
  Sink --> Actor
  Log --> Wake[有界 GROUP 扫描与持久唤醒游标]
  Wake --> Index
```

Actor 在这里承担活动执行的串行接受边界，SQL 承担可检索的冷事实与事务准入。这是一次明确的架构取舍：冷表数量增长不应直接增加 Actor、Future、线程、RR 事务与会话锁。动态 5→7 的 HTTP 请求返回 202，只表示定义已持久受理；它要经过捕获准入、初始化、审计激活，才可能进入持续同步。

过去持续占据线程、会话锁与默认八个 run 许可的实现已不适合作为本轮资源模型。新的 `step` 以一个完整窗口、一次组批或一次有界审计单元让出。快照和 tail 使用独立预算；已审计且无输入的实时任务退出活动会话，由 L 的新 GROUP 唤醒。新变化与 worker 完成竞争时，用持久 wake_seq 保留后续工作，不能依赖一个可能丢失的内存消息。

这还不是“70 万表已经跑通”。它把需要随总身份数增长的状态压到持久索引，把活动资源限制在预算内；索引扫描、状态写入、读视图时长和唤醒延迟是否可接受，必须实测。即使 70 万条冷索引被顺序遍历，也只证明那个元数据负载，不证明 70 万源表的采集、初始化或持续同步容量。

## 4. 数据面已经复用了什么，自建又增加了什么责任

本项目没有从零编写 MySQL binlog 解析器。它复用 Debezium SDK、JDBC 和 Pekko 持久化能力，自建的是它们之间的持久日志、完整组确认、范围封存与目标证据协议。

| 层次 | 当前复用或自建 | 继续维护的责任 |
|---|---|---|
| 原始采集 | 复用 Debezium；自建共享 capture、L 与原始 ACK 前缀协调 | SDK 启动/offset 阶段、Schema 与日志保留成本，版本兼容 |
| 初始化 | 自建固定范围、LOW/HIGH、fold、FINAL 与 B→F | 读视图丢失、完整日志前提、类型/约束和激活恢复 |
| 表执行进度 | 复用 Actor Journal，自建 BootstrapProgress 与 receipt 协调 | 明确拒绝、结果未知、seal 丢失、同 owner 缓存重建与跨 owner 重验 |
| 控制调度 | SQL 持久 task/grant、有限 step worker、活动 Journal Actor | 未决 grant 计预算、命令幂等、健康过期、物理释放未知与停机恢复 |
| 分布式状态运行时 | 当前没有通用执行图 Checkpoint | 若加入 Join、窗口、定时器等状态计算，需要额外一致恢复设计 |

Flink 的价值包括现有的 Source 分配、Reader 状态、Checkpoint 及运行时协调；外部 Sink 仍需各自的事务/幂等提交契约。[Flink 状态恢复说明](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/concepts/stateful-stream-processing/)若复制需求很快扩展为复杂状态计算，自建范围会明显变大，目标组账本不能替代算子状态的一致恢复。

## 5. 用一次批转流比较责任位置，而非比较术语数量

当前 Pekko 路径是固定 U、串行范围、LOW/HIGH 局部回填、FINAL 封存，再完整回放 B→F 并审计激活。Flink CDC 3.6.0 的[MySqlHybridSplitAssigner.createBinlogSplit](https://github.com/apache/flink-cdc/blob/bf160337ff558d70302e7884f68937611873117e/flink-cdc-connect/flink-cdc-source-connectors/flink-connector-mysql-cdc/src/main/java/org/apache/flink/cdc/connectors/mysql/source/assigners/MySqlHybridSplitAssigner.java#L207)则使用 snapshot split 范围与完成 offset 构造 binlog split 及完成信息，并与其 Reader/分配器状态衔接。

这些机制承担相近问题，但不是相同算法。本项目的 F 不是全局 Checkpoint；当前范围窗口也没有 Flink 的并行领取与完成确认协议。保存 FINAL 和完整 L 使恢复材料显式，却增加持久写入、保留空间、恢复审计和 B→F 应用工作。

[第四篇 G4](04-protocol-and-validation.md#g4)给出当前算法在明确前提下的逐键推理；[第三篇 E0](03-real-mysql-journal-failure.md#e-startup)又显示旧 INITIAL-D 恢复可以越过必要输入。新版 A1/A2 已实测拒绝已识别的不安全重入，旧五字段差异没有被修补。局部算法存在证明、危险状态被拒绝、业务数据已经修复，是三种不同结论；其他系统也应按固定版本和同一故障窗口分别判断。

## 6. 一台实例的 70 万表，成本不会随冷 Actor 消失

令 N 为源表目录数，S 为订阅表数，A 为有待处理工作的表数。当前方案试图把 Actor/worker 的活动成本约束在配置预算附近，而不是随 N 或 S 线性实例化；但系统总成本还包括下列几项：

| 成本 | 主要由什么决定 | 当前架构能否直接消除 |
|---|---|---|
| 冷控制索引与持久意图 | 任务数、版本数、索引与保留策略 | 不能；只是避免实例化同数量活动 Actor |
| binlog 读取与解析 | 源实例写入量、事务大小、SDK 过滤位置 | 共享 Reader 减少重复读取，不等于未订阅写入零成本 |
| Schema 获取、解析及 history | 实际捕获数据库的目录、DDL 历史、SDK 启动策略 | 不能靠减少 Actor 消除 |
| 初始化 | 选定表行数、窗口大小、并发写入与目标速度 | 有界分片限制峰值，但增加次数与追赶工作 |
| L、spool、receipt 与 Journal | 变化率、积压、封存页数、验证与保留时长 | 这是显式恢复证据的成本，不能忽略 |
| 服务端资源核实 | capture 数、专属账号、observer 权限与故障状态 | 必须为正确释放付费，超时不能直接回收 |

当前 [`SharedMySQLCapture`](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/SharedMySQLCapture.scala)使用数据库内全表匹配来支持运行时新增订阅，`no_data` 也不表示没有 Schema 工作。若源库有 70 万表，即使今天只订阅五表，Debezium 的目录、Schema 与 history 成本仍需真实测量。冷调度实验没有执行这一步，不能据此声称整体内存已按五表计。

共享还扩大了故障域。假设 a 目标暂停十分钟，b 仍有写入。L 可以让 b 独立提交，但 a 恢复所需的历史不能被删掉。隔离的代价是积压字节、保留时长和后续追赶；L 达到上限时，整个捕获域可能承压。若 Reader、Schema 或 history 异常，表级 Actor 也不能独立修复采集连续性。

当前没有完成长期 GC；新共享链路已有受管恢复候选，但尚未完成同版本故障矩阵。旧静态快照的隔离实验不覆盖这些新资源。SQL owner 与 UNKNOWN grant 的保留，是防止未经证明的替代执行；它可以选择停住业务，不能因此称作已经实现自动 HA。

## 7. 高可用的成本，首先是区分“未知”与“失败”

单机进程退出并不意味着服务端 dump 连接、事务和命名锁都已经结束。本轮受管 close 将本地 runner/executor 终止、服务端会话/事务排空、owner 释放和 reusable 分开记录。它用原始 observer 连接、server UUID 与 canary 约束观察连续性，账号也按 capture 隔离，且必须覆盖 SDK 与全部 L 连接。旧装配漏掉共享控制账号下的 L，真实事务仍在却提前宣布可复用；修复及同窗口的 UNKNOWN/最终释放证据均单列保留。无法观察时持久保留占用，不能只凭 PID 或经过一段时间开替代 Reader。

这使控制面的状态更多，却比一个 running 布尔值准确。API 分开返回捕获准入、初始化历史证明、当前 runtime health 和 release 状态；超龄观察为 STALE，查询失败显示 UNKNOWN。PAUSED 没有普通 Resume 按钮，202 不是初始化完成，PREEXEC_CANCEL 也不是初始化证明。

Flink 的成熟协调机制可以减少一部分自建工作，但它与具体 MySQL Reader、外部 Sink 的协议仍需在相同故障窗口检验。本项目自建之后，必须自己维护源提交不确定、目标 T/J 缺口、原始 ACK、SDK D、资源释放与调度资格之间的证明链。新增一层 SQL 不能自动把这些不确定性变成事务原子性。

## 8. 怎样用共同负载判断这项取舍是否值得

下一项有意义的比较应固定同一 MySQL 版本、源目录和写入分布、相同目标类型及业务语义，而不是只固定表数量。至少分别报告：

| 维度 | 应测的量 | 关键限定 |
|---|---|---|
| 冷目录 | 控制索引、SDK Schema/history 的内存、启动时长、存储与查询计划 | 真实目录与合成元数据分别计 |
| 5→7 动态订阅 | 受理延迟、捕获准入延迟、初始化完成时长、对既有表延迟的影响 | 202、B、STREAMING 三个时点分开 |
| 活跃数据 | 吞吐、端到端延迟、连接/事务峰值、L/spool 增长 | 同事务规模、类型、热点分布与目标速率 |
| 故障恢复 | 安全暂停率、自动恢复范围、重放量、逐字段终值与恢复时长 | 正确拒绝不计作恢复成功 |
| 长期运行 | 日志保留、GC、反压、失联资源、升级与人工处置成本 | 短期全量对账不能代替长期 SLA |

本轮已经取得三类相互独立的正面证据：P01 的动态 5→7 经过真实平台链路逐字段相等；P02 在十表 tail 之后仍能初始化第十一表，活动步骤不超过四；H 写入并遍历 700,000 条冷控制记录，但不创建 ActorSystem 或 Reader。它们分别支持控制入口、有限执行和冷索引的可行性。受管 close、读视图重建、干净 owner 恢复与未知 owner 暂停又约束了这些资源能够怎样复用。

[本轮报告](../../experiments/2026-10-27-cdc-platform/README.md)同时保留 gate 未命中、磁盘采样竞争、旧 marker 自动收敛期待失败和自然释放迟延。没有同负载 Flink benchmark，没有 70 万真实源表目录或长期活跃 CDC 结论。用这些局部证据支持具体架构决策是合理的，将它们合并成“比 Flink 更省资源、更高可用”则越过了验证范围。

如果主要问题是少量持续活跃表与海量长期控制身份、细粒度动态选择，SQL 冷索引加 Pekko 活动执行值得继续验证。如果需要广泛连接器、复杂 Join/窗口、跨算子一致恢复，成熟数据运行时能减少自建责任。把 Pekko 仅作为控制面、数据交给现成 CDC 运行时也可评估，但当前没有实现该组合。

因此本文支持的是一种有边界的架构选择：让长期身份与活动计算分开，让动态控制与数据正确性分别取证。它尚未支持“比 Flink CDC 更省资源”或“已适合 70 万表生产常驻”的总体结论。

## 新故障证据如何影响比较

本轮在真实 MySQL、Actor Journal 和 Sink 中补充了 ACK/D 故障、并发订阅和旧记录恢复的逐字段对账；它们支持指定窗口的行为，不证明所有恢复窗口均收敛。A14 未知 COMMIT 取消失败，B05 真正的 MySQL task 启动与关闭竞争在两个样本中均未能在观察期确认释放，B06 在源事务未决时已释放 capture owner 锁；A05 的自动继续仍缺可靠证明。这些未解决项是自建 CDC 平台需要承担的工程工作，不能因 Actor 的资源模型而省略。

这些实验没有在相同负载下运行 Flink，也没有验证 70 万张真实表。具体切点和失败材料见[补测报告](../../experiments/2026-10-28-ab-matrix/README.md)。

## 后续补测与边界

这些失败样本有了针对性的后续修复和实验，详见报告；安全暂停、局部资源释放和固定场景对账仍不是完整 CDC 高可用保证。该历史实验版本尚未完成 DDL、GC、自动接管、长时间 churn、70 万真实源表及同负载 Flink 对比。 详见[后续实验](../../experiments/2026-10-28-cdc-followup/README.md)。上文的失败是当时版本的历史结果，原始材料保留。

## 2026-10-29 补充验证

后续实验验证了一个回调异常后重建 Reader/Actor 的恢复样本，也修复了特定启动关闭竞态；该历史实验版本的未知 B 自动恢复与长期资源占用仍未解决或验证，结果不能扩展为平台全故障自动恢复保证。 [实验材料与范围](../../experiments/2026-10-29-p0-followup/README.md)。

## 2026-10-29 写前锚点实验

写前锚点实验版本增加了锚点及局部证据，但当时未知 B 的任务级自动恢复尚未完成；不能据此宣称已具备 Flink CDC 的故障恢复能力。 详见[本轮证据](../../experiments/2026-10-29-unknown-b/README.md)。

## 事务释放与隔离探针

真实事务释放与旧 JVM 迟到重连的探针进一步说明，自建调度需要独立证明旧执行者隔离；该探针样本本身不能证明未知 B 已实现自动恢复。 [实验与边界](../../experiments/2026-10-29-b-release-proof/README.md)。

## 恢复能力比较仍受证据限制

未知 B 的受管恢复候选已经把进程隔离、数据库排空、D/L 证明、持久许可与 Journal 状态衔接起来，这是源码层面的进展。单个[旧候选真实迟提交样本](../../experiments/2026-10-29-cdc-managed-b-e2e-wake-candidate/README.md)恢复到 STREAMING，64 行、384 字段相等；[组件样本](../../experiments/2026-10-29-cdc-b-proof-component/README.md)验证不同许可与证明分支，但使用合成 ACK 与 guardian 数据。更早需要人工唤醒的[失败样本](../../experiments/2026-10-29-cdc-managed-b-e2e-old-candidate/README.md)仍保留。

当前候选基于提交 `62ec856f20a81f3da8d56caf290754f8361ac732` 之后的未提交工作区；不同实验的精确源码摘要以各自证据目录为准，不能把旧样本归给后续修改。 六项固定窗口已在同一候选版运行，但除单个 C1 样本外的生产入口窗口、多任务 TAIL 重放、长期运行、70 万真实表以及与 Flink 同负载性能比较仍未验证，不能根据这些样本声称两者具有等价的高可用或吞吐。

## 新增固定窗口证据

[六项固定窗口的完整证据](../../experiments/2026-10-29-cdc-managed-b-e2e-frozen/README.md)使用同一候选源码、独立 guardian JVM、真实 MySQL、Actor Journal 与 MySQL Sink；这六项的协调器运行在测试 JVM、worker 显式接线；另有一个 [生产入口 C1 样本](../../experiments/2026-10-29-cdc-production-entry-c1/README.md)，实际经过独立 `SnapshotGuardianMain`、`SnapshotSupervisorMain` 和 `PekkoServer` 内的 `CdcPlatformExtension`。

四项提交/回滚窗口恢复到 STREAMING，各自 64 行、384 字段相等；guardian 失联与不可达两项则安全暂停、没有完成目标初始化。收敛样本的后继 worker 在清理时被强制停止，CDC owner 仍 ACTIVE。该结果说明受管候选在这些指定窗口中具备数据恢复或安全暂停证据，但不构成 Flink 与 Pekko 的高可用能力等价，也不是同负载性能比较。

生产入口的独立 C1 样本在真实 MySQL、Journal 和 Sink 上恢复至 STREAMING，源与目标各 64 行、384 字段相等。前两次运行分别在故障注入前因测试配置缺项和明文代理不兼容而失败，原始材料保留；本样本没有生产 raw ACK 顺序证据，强制清理后 CDC owner 仍为 ACTIVE，不证明正常停机。其余故障窗口仍仅有测试入口证据。 [生产入口 C1 证据](../../experiments/2026-10-29-cdc-production-entry-c1/README.md)。

对目标场景还有一项具体限制：未知 B 恢复反查源控制表最多 4096 行，超限安全暂停，而当前没有控制行 GC。长期新增、取消表会累积控制行，这一上限会影响 70 万表场景的恢复可用性；不能用现有实验宣称该规模已经验证。 [组件实验与限制](../../experiments/2026-10-29-cdc-b-proof-component/README.md)。

## 技术附录与证据入口

[历史源码分析与实验档案](appendices/05-framework-comparison-history.md)保留本篇此前全文；[附录索引](appendices/README.md)集中提供版本、完整矩阵和原始材料入口。当前主文只保留支撑核心论点所需的实现、代表性证据和边界。
