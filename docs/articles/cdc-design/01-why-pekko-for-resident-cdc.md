# 一：70 万表的常驻 CDC——共享 Reader、表级 Actor 与有界执行

[系列导读与验证范围](README.md) · [1：目标架构](01-why-pekko-for-resident-cdc.md) · [2：批转流源码](02-source-and-recovery-contract.md) · [3：真实实验](03-real-mysql-journal-failure.md) · [4：恢复协议](04-protocol-and-validation.md) · [5：自建与复用](05-pekko-vs-flink-cdc.md) · [Flink CDC 深入系列](https://xuyinyin.cn/flink-cdc-series/)

> **文章修订：2026-10-26；代码基线 [`55fad0c`](https://github.com/Xuxiaotuan/pekko-reference/tree/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059)。** 本文依据源码与保存材料论证，没有重跑数据库。版本、验证范围和未覆盖项统一见[系列导读](README.md#全系列验证范围汇总)。

一个 MySQL 实例有 70 多万张表，今天选择五张同步，明天增加两张。新增表先同步已有数据，再持续追增量；取消一张表时，其余任务仍应运行。这个业务需要长期保存表的身份与进度，同时按实际工作量分配采集和执行资源。

本文回答：任务身份、控制状态、Reader、线程、连接和恢复证据应怎样分开？当前共享捕获实现、动态五表增加到七表的实验，以及 Actor 调度机制，分别回答了其中一部分。它们尚未证明 70 万张真实表的资源成本或整个平台的恢复能力。

## 1. 先区分源目录、订阅集合和正在执行的工作

“70 万表常驻”容易把三个数量混成一个：

| 数量 | 本文含义 | 在业务故事中的取值 |
|---|---|---|
| N：源目录 | 源实例中可选择的物理表 | 需求背景约 70 万，未做相应实表验收 |
| S：订阅集合 | 已要求初始化并持续跟随的表 | 今天 5 张，明天 7 张 |
| A(t)：活动工作 | 时刻 t 真正在读页、回放或提交的执行步骤 | 随数据与调度变化，不应等同于 N |

任务定义可以长期存在，控制实体可以按需处理消息，执行连接则应在需要时申请。这是目标资源关系。当前实现是否真的如此，需要分别检查 Reader、Actor 和阻塞调用，不能用创建了多少 Actor 替代答案。

目录很大仍可能影响采集。当前共享 Engine 的 `table.include.list` 覆盖业务数据库；即使只选择七张表，Schema 发现、history 与非订阅输入处理的成本也不能直接视为只与 S 有关。[共享 Reader 启动配置](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/SharedMySQLCapture.scala#L87)

## 2. Reader 按捕获域共享，目标进度按表推进

[SharedMySQLCapture](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/SharedMySQLCapture.scala#L59) 为一个 capture 管理一个 Engine。新增表先准备 Schema、登记一代订阅，再向源控制表写 B；Reader 捕获完整组、Schema 和控制 marker，将它们持久化到 L。

表消费者从 L 按各自游标追赶：

```text
同一 capture 的共享 Reader
    → 持久化日志 L
        → 表 a 的消费者 → T_a → J_a
        → 表 b 的消费者 → T_b → J_b
        → 新表 f/g：初始化 → 有限回放 → 持续消费
```

这不是“全实例只允许一个 Reader”的全局不变量。Reader 数量取决于 capture 配置及其所有权；当前按捕获域共享，避免把每张表直接绑定一个 Engine。

原始 SDK ACK 依赖 L 中的完整覆盖，不依赖每个目标都写完。因而可以在有限积压内，让 a 表目标提交停住，而 b 表继续推进。[共享捕获 R14](../../experiments/2026-10-25-shared-capture/README.md)验证了这个指定窗口。代价是必须保存 a 仍需的日志；L 容量耗尽时，共享域也可能暂停。表级游标提供的是解耦机制，隔离时长由存储与保留策略约束。

源与 L/offset 状态还必须位于不同物理 MySQL 实例。实验中的同实例不同 schema 曾形成事务元数据反馈，当前启动核对 server UUID 并拒绝该部署。业务仍只有一个源实例，但可靠状态存储是额外基础设施。

## 3. 表级 Actor 串行控制，执行线程按工作借用

Pekko 1.1.3 的 [Mailbox](https://github.com/apache/pekko/blob/38facf752b4e7d5fb43ed7c671eb91888b41bd10/actor/src/main/scala/org/apache/pekko/dispatch/Mailbox.scala#L188)由 Dispatcher 调度。同一 Actor 的普通 handler 串行执行；没有待处理消息时，身份本身不永久拥有一条工作线程。串行边界止于 handler：把外部 Future 发出去，并不会自动使数据库操作也串行或原子化。

历史机制实验让 10,000 个普通 Typed Actor、40,000 条消息共用两条线程，观察到本条件下 handler 不重叠、顺序正确。它给“身份可以多于线程”提供了正面机制证据；持久化实体、CDC 阻塞工作和真实表目录是另外的成本面。[原始实验](../../experiments/2026-10-18-actor-execution/README.md)

当前新内核中的 [BootstrapJournal](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapJournal.scala#L20)已经用 Actor Journal 接受单表阶段与进度。但它还不是接入生产 Sharding、冷索引和统一配额的完整表级控制平台。目标设计要让控制 Actor 推进有限步骤，SQL 在有界阻塞池执行；若直接在 handler 中运行长期 Runner，Mailbox 的线程复用就无法带来预期收益。

## 4. 新增两张表，要先闭合初始化生命周期

只从 B 之后转发 binlog，会漏掉新增表已有的数据。当前 [BootstrapRunner](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala#L172)执行的是：

```text
B 绑定 → 冻结键上界 U
→ 每个固定范围：LOW → RR 分页 → HIGH → 回填 → 封存 → baseline T/J
→ 固定 F → 完整回放 B→F → 审计与激活 → 持续消费 L
```

B 是开始保留增量的边界；U 是扫描键上界；LOW/HIGH 属于一次窗口读视图；F 固定有限追赶终点。它们不等于一个“已完成”状态。捕获就绪、初始化完成和当前持续健康也需要分别查询。

I01 在已有五表之后初始化 f/g，再让七表发生变化并逐字段核对。它验证当前小数据集中的动态初始化与持续跟随组合，证明这条路径已经超出了“先手动复制目标，再从现在读 binlog”。第二篇沿源码解释每一步；第三篇用其他故障案例检验恢复条件。

初始化目标要求独占且最初为空，激活前不向业务发布。取消使用 K 和排空语义；新 generation、新 B 不自动补齐停订空档，旧部分目标也不能冒充新的可信基线。

## 5. 当前长期 Runner 为什么仍占用资源

当前 [BootstrapAdmission](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala#L15)默认提供两个读取、两个写入、八个 run 许可。Runner 获得 run 许可和会话锁后，会在 STREAMING 循环中持续 tail；空读使用 `Thread.sleep(50)`。这条调用需要专用阻塞执行上下文。

因此当前实现中，即使没有新事件，长期会话也没有自动变成一个只存状态的冷身份。八个 tail 可能占住八个 run 许可。给 `run()` 包一层 Actor Future，不会解除这个占用。

目标结构是持久冷索引、表级控制 Actor、可有界推进的快照/追赶/tail 步骤和分开的预算。目前只有[生命周期契约提议](../../development/specs/2026-10-25-cdc-platform-lifecycle-contract.md)。旧[静态调度入口](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/scheduling/TableSchedulingRuntime.scala#L84)只接受 `mysql.snapshot → mysql.write`，不能直接算作这条新链路已经接通。

## 6. 资源收益需要在哪些面上成立

可检验的目标关系是：Reader 与捕获域相关；活动连接、在途 SQL 和写入受执行预算约束；冷任务主要保留定义与恢复所需元数据；日志空间随积压和保留策略增长。这里的预算要跨活动任务统一核算，不能把某个 Admission 对象的 Semaphore 当成全主机或多 owner 配额。

还有四个成本必须实测：库级 Schema/history、待处理控制消息、原始回调与大事务、L/spool 保留。没有这些曲线，无法把“Actor 不独占线程”换算成总体内存更低。

当前已有的正面证据是：单 capture 的多表捕获、表级消费解耦，以及选定表的受限全量转流与恢复。尚未兑现的是这条内核与冷任务激活、有界步骤、统一恢复入口和 API/UI 的组合。项目值得继续推进的命题，就是把这些职责分开后仍保持复制正确性，并用容量与运维成本检验分离是否带来实际收益。

## 技术附录与证据入口

[历史源码分析与实验档案](appendices/01-actor-execution-history.md)保留本篇此前全文；[附录索引](appendices/README.md)集中提供版本、完整矩阵和原始材料入口。当前主文只保留支撑核心论点所需的实现、代表性证据和边界。
