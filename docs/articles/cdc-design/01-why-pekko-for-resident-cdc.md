# 一：70 万表的常驻 CDC——共享 Reader、表级 Actor 与有界执行

[系列导读与验证范围](README.md) · [1：目标架构](01-why-pekko-for-resident-cdc.md) · [2：批转流源码](02-source-and-recovery-contract.md) · [3：真实实验](03-real-mysql-journal-failure.md) · [4：恢复协议](04-protocol-and-validation.md) · [5：自建与复用](05-pekko-vs-flink-cdc.md) · [Flink CDC 深入系列](https://xuyinyin.cn/flink-cdc-series/)

启动和资源释放的新补测见[A/B 故障记录](../../experiments/2026-10-27-ab-followup/README.md)。其中包含真实 MySQL 观察器故障，但尚未覆盖完整矩阵，也没有 70 万真实源表的资源结论。

> **文章修订：2026-10-27。** 当前平台路径按仓库源码说明，最终源码版本与本轮运行结果统一见[平台报告](../../experiments/2026-10-27-cdc-platform/README.md)。已提交 `74b6d9a` 的启动准入实测另见[A1/A2 报告](../../experiments/2026-10-27-capture-admission/README.md)。历史版本和失败材料保留，不把新增代码直接写成端到端验收通过。

一个 MySQL 实例有 70 多万张表，今天选择五张同步，明天增加两张。新增表先同步已有数据，再持续追增量；取消一张表时，其余任务仍应运行。这个业务需要长期保存表的身份与进度，同时按实际工作量分配采集和执行资源。

本文回答：任务身份、控制状态、Reader、线程、连接和恢复证据怎样分开？当前实现已把共享捕获、持久任务索引和有界内核步骤接在一起。架构是否节省资源，仍要分别检查冷目录、活动执行和源端 Schema 成本；不能由 Actor 的线程复用机制推出 70 万真实表已经验收。

## 1. 先区分源目录、订阅集合和正在执行的工作

“70 万表常驻”容易把三个数量混成一个：

| 数量 | 本文含义 | 在业务故事中的取值 |
|---|---|---|
| N：源目录 | 源实例中可选择的物理表 | 需求背景约 70 万，未做相应实表验收 |
| S：订阅集合 | 已要求初始化并持续跟随的表 | 今天 5 张，明天 7 张 |
| A(t)：活动工作 | 时刻 t 真正在读页、回放、审计或提交的执行步骤 | 随数据与调度变化，不应等同于 N |

任务定义长期存在，不要求执行资源长期存在。[CdcTaskStore](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/cdc/CdcTaskStore.scala)用 SQL 保存请求身份、目标状态、实际阶段、Journal revision、调度条件与 grant。分页查询读取这些投影，不逐条询问 Actor，也不为每条冷记录创建 Future。

目录很大仍可能影响采集。当前共享 Engine 的 `table.include.list` 覆盖业务数据库；即使只选择七张表，Schema 发现、history 与非订阅输入处理的成本也不能直接视为只与 S 有关。[共享 Reader 配置](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/SharedMySQLCapture.scala)中的这个边界没有因增加冷索引而消失。

因此需要分别测量 N、S 和 A(t)。70 万条任务索引只能回答元数据存取问题；70 万物理源表会引入另一份目录和 Schema 成本；高比例活跃订阅又是不同的吞吐与积压实验。

## 2. Reader 按捕获域共享，目标进度按表推进

[SharedMySQLCapture](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/SharedMySQLCapture.scala)为一个 capture 管理一个 Engine。新增表先准备 Schema、登记一代订阅，再向源控制表写 B；Reader 捕获完整组、Schema 和控制 marker，将它们持久化到 L。

表消费者从 L 按各自游标追赶：

```text
同一 capture 的共享 Reader
    → 持久化日志 L
        → 表 a 的步骤 → T_a → J_a
        → 表 b 的步骤 → T_b → J_b
        → 新表 f/g：初始化 → 有限回放 → 持续消费
```

Reader 数量取决于 capture 配置及其所有权。按捕获域共享，避免把每张表直接绑定一个 Engine；当前实现不承诺任意多数据库布局只需一个 Reader。

原始 SDK ACK 依赖 L 中的完整覆盖，不依赖每个目标都写完。因此可以在有限积压内，让 a 表目标提交停住，而 b 表继续推进。[历史 R14](../../experiments/2026-10-25-shared-capture/README.md)验证了这个指定窗口。代价是必须保存 a 仍需的日志；L 容量耗尽时，共享域也可能暂停。表级游标提供的是解耦机制，隔离时长由存储与保留策略约束。

源与 L/offset 状态还必须位于不同物理 MySQL 实例。实验中的同实例不同 schema 曾形成事务元数据反馈，启动会核对 server UUID 并拒绝该部署。业务仍只有一个源实例，但可靠状态存储是额外基础设施。捕获域的专属状态账号还必须覆盖 SDK 与全部 L 连接；全局控制/Journal 账号分离，避免遗漏未决 L 事务。

## 3. Actor 串行接受进度，步骤按工作借用线程

Pekko 1.1.3 的 [Mailbox](https://github.com/apache/pekko/blob/38facf752b4e7d5fb43ed7c671eb91888b41bd10/actor/src/main/scala/org/apache/pekko/dispatch/Mailbox.scala#L188)由 Dispatcher 调度。同一 Actor 的普通 handler 串行执行；没有待处理消息时，身份本身不永久拥有一条工作线程。串行边界止于 handler：把外部 Future 发出去，并不会自动使数据库操作也串行或原子化。

历史机制实验让 10,000 个普通 Typed Actor、40,000 条消息共用两条线程，观察到本条件下 handler 不重叠、顺序正确。它支持“身份可以多于线程”这一机制判断；持久 CDC 实体、阻塞工作和真实表目录是另外的成本面。[原始实验](../../experiments/2026-10-18-actor-execution/README.md)

当前平台使用[BootstrapJournal](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapJournal.scala)接受单表阶段和进度，但不会为所有 SQL 任务常驻一个 Journal Actor。[CdcBootstrapKernel](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/cdc/CdcBootstrapKernel.scala)只为获准的活动工作建立 Runner、Journal 和相关会话；干净步骤完成后确认 Journal 停止，再撤销该活动会话。需要跨步审计的对象留在有界缓存中，校验完成前不会被当作普通空闲 tail。

阻塞 JDBC 在有限工作池执行。Journal ask 由 Actor dispatcher 处理；Sink 使用步骤工作线程，避免 handler 自己等待长事务。线程上界来自统一配置的步骤预算，而不是任务表里有多少行，也不是给每张表独立分配一个永久 Future。

## 4. 新增两张表，要先建立可恢复的起点

只从“现在”转发 binlog，会漏掉新增表已有的数据。当前[BootstrapRunner](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala)保留的批转流链路是：

```text
B 绑定 → 冻结键上界 U
→ 每个固定范围：LOW → RR 分页 → HIGH → 回填 → 封存 → baseline T/J
→ 固定 F → 完整回放 B→F → 审计与激活 → 持续消费 L
```

B 是开始保留增量的边界；U 是扫描键上界；LOW/HIGH 属于一次窗口读视图；F 固定有限追赶终点。捕获就绪、初始化完成和当前持续健康需要分别查询。

写 B 之前还有一道 capture 级门。已经提交的 [A1/A2](../../experiments/2026-10-27-capture-admission/README.md)要求当前 attempt 的 snapshot 生命周期、原始 ACK 和实际完成态 D 共同成立。已有 B 而 D 仍为 INITIAL、未决源 marker 或缺少必要恢复证据时，创建 Engine 前安全暂停。这个行为阻止特定旧风险状态继续启动，不会自动补齐历史漏数。

平台调度读取当前 attempt 的缓存准入状态，避免每个表步骤都重新查询 D、遍历整条 ACK 链。capture 协调器负责刷新完整证据；真正新增 B/fence 的生产入口仍做实际 owner/准入校验。缓存用于分配工作，不能代替源端副作用发生前的检查。

[历史 I01](../../experiments/2026-10-25-bootstrap/README.md)在已有五表之后初始化 f/g，再让七表变化并逐字段核对。它支持受限数据集中的动态初始化组合；本轮 P01 又经过真实 HTTP、持久任务索引、step、Journal 和 Sink 完成五表新增两表，七表逐字段相等；这是新入口的独立样本，不借用旧 I01 的通过数。

初始化目标要求独占且最初为空，激活前不向业务发布。取消使用 K 与排空语义；新 generation、新 B 不自动补齐停订空档，旧部分目标也不能作为新代的空目标复用。

## 5. 从长期 run 会话改为有限执行量子

历史 `run()` 获得 run 许可和会话锁后，在 STREAMING 循环中持续 tail，空读仍 `Thread.sleep(50)`。八个长期 tail 因而可能占满默认八个 run 许可。兼容入口仍保留，但新的平台调用的是 `step()`：

| 阶段 | 一次步骤承担的工作 | 让出后保留的事实 |
|---|---|---|
| 等待 Reader/B/F | 读取就绪状态或检查已请求边界；条件未成立便返回 | 已接受意图、原 operation、实际绑定状态 |
| SCANNING | 一个固定范围的 LOW/RR/HIGH、回填、封存及页接受 | spec、封存内容、T receipt 和 J |
| REPLAYING/STREAMING | 至多一批完整组 | 已确认的 T/J 游标与批身份 |
| ACTIVATING/恢复校验 | 一个已完成窗口或一批组的证明校验 | 有界活动缓存中的审计 cursor；丢失后重新校验 |
| CANCELING | 请求/检查 K，已激活时至多排空一批 | 原 B/K、取消意图和已接受进度 |

一个范围内所有 SQL 页仍属于同一个 RR 事务。步骤化不能把这个读视图拆成几个连接后拼接；失去读视图就按原范围新 attempt 重读。单窗口仍受读视图、SQL 和 marker 的超时约束，“有界步骤”不表示每一步都是固定毫秒时长。

[CdcPlatformRuntime](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/cdc/CdcPlatformRuntime.scala)分别授予 snapshot 和 tail 工作预算；[CdcTaskStore](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/cdc/CdcTaskStore.scala)在持久事务中保存 grant。释放确认才完成 grant；资源结果未知则保留 UNKNOWN 及其占用，不能因 Future 返回、Actor 停止或新 JVM 的 Semaphore 为空就重新授予。

正常调度让出也不是一次故障恢复。上一 grant 的干净结果与当前 runtime owner 绑定，新的 Runner 逐项核对完整 J progress，才可免计一次 Interrupted。干净释放只证明资源边界，不能证明旧 RR 仍在：未封存窗口仍需新 attempt；J 已接受的 seal 丢失则暂停，不能借重新分批生成新内容。第二篇会沿这条边界展开。

## 6. 没有变化的订阅怎样休眠，再由 L 唤醒

只把 tail 空读变成定时步骤，仍可能让海量空闲表不断打开连接。平台进一步把空闲 STREAMING 记录停在持久索引中：不持有任务 grant，也不保留每表 Actor。

capture 级 dispatch 游标按有限页扫描 L，将 GROUP 的订阅身份和序号写入对应任务的 `wake_seq`。该表需要追赶时，索引把它设为可调度。消费者接受一批后，完成事务同时比较实际 `delta_after` 和最新 `wake_seq`：若新组已经到达，继续排队；没有待处理组则 park。这样可以覆盖“消费者刚空读，新组恰好抵达”的竞争，而不是只依赖一次进程内通知。

dispatch 游标、任务唤醒与 grant 完成是 SQL 持久事实，不是每表创建一个定时器。调度页面采用 keyset 查询；当前配置的 capture 数量、步骤预算和冷恢复页大小都有上界。恢复过程可以分页重新激活任务验证，却不会据此一次唤醒全部 Journal Actor。

维护停机也不等于业务退订：停止 runtime 不默默写 K。受控干净停止要核实 Reader 本地结束、服务端资源和 owner 释放，再保存 owner 结束事实；旧 owner 是否结束不清楚时安全暂停。这个单 owner 协议没有自动升级成共享 Reader 的多 owner/guardian 接管。

## 7. 实测支持了哪一段资源判断

现在可从源码检验的资源关系是：Reader 与捕获域相关；活动 Journal、线程、连接和在途 SQL 受步骤预算约束；冷任务保留 SQL 元数据；无变更 tail 可以 park；日志空间随积压和保留策略增长。它比“每表一个长期 Runner”更接近需求，但资源公式不等于实测曲线。

库级 Schema/history、原始回调与大事务、L/spool 保留、唤醒分派以及任务索引，仍要分别测量。`UNKNOWN` 保留额度会降低可用容量，这是避免资源重叠的代价，不能隐藏为“自动恢复成功”。

本轮 P02 先让十个订阅持续同步，再新增第十一表，十一表最终逐字段相等。配置 snapshot=2、tail=2，活动步骤峰值不超过 4。它说明这个负载不再被旧八个长期 run 会话占满；没有证明高活跃率下的延迟、公平性或吞吐上限。

另一个 H 探针确实写入并精确计数 700,000 条冷索引，完整走过 7,000 页 keyset，保存首中尾和 claim 查询计划，并在固定 60 秒内观察堆、线程、连接、事务及配额。它未创建 ActorSystem、Reader 或同量源表。因而它支持冷控制记录与活动执行分离这一段判断；SDK 在 70 万真实表目录下的 Schema/history 成本仍然未知。

这些是两种独立实测，不能拼成“70 万表 CDC 已跑通”。版本、失败和原始材料见[平台报告](../../experiments/2026-10-27-cdc-platform/README.md)。DDL、GC、新共享链路自动接管、长期活跃吞吐和同负载 Flink 性能比较仍未覆盖。

## 资源复用仍取决于可证明的释放

真实关闭实验中，静默和忙源样本分别完成四行逐字段对账；当测试门阻塞 SDK close 或 runner 退出时，资源仍保持 UNKNOWN，不能立即给新执行者复用。在真实 MySQL task 启动期间触发关闭的两组独立实验均在 180 秒内未确认空闲，测试进程退出后槽位才空闲；这属于实际失败，不是已经解决的容量保障。测试门位于 调用原 task 的 start 实现之前。同账号的一条非 capture 连接在组件探针中使观察结果保持 UNKNOWN，连接本身仍可用；这说明账号级观察不能自动区分连接归属。旧 JVM 被杀后，测试代理仍保留真实 L 事务的新窗口中，新 JVM 保持 UNKNOWN、没有创建 Reader；事务回滚后也没有自动接管。[补测报告](../../experiments/2026-10-28-ab-matrix/README.md)区分测试门、服务端采样与端到端样本；这些结果不构成 70 万表容量结论。

## 技术附录与证据入口

[历史源码分析与实验档案](appendices/01-actor-execution-history.md)保留此前全文；[附录索引](appendices/README.md)保留旧版本和失败材料。[A1/A2 报告](../../experiments/2026-10-27-capture-admission/README.md)记录已提交的启动准入实测，[本轮平台报告](../../experiments/2026-10-27-cdc-platform/README.md)承担新调度、生命周期与入口的组合验收。本文解释职责与资源因果链，不把两个版本的结果合成一次实验。
