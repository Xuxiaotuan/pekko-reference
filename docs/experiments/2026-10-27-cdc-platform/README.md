# 共享 CDC 平台：有界步骤、持久调度与受管释放

本轮把既有 LOW/HIGH、封存页、完整 B→F 回放和 T/J/D 内核接入可持久查询的控制面。业务样本仍是一个源实例，动态 5→7 张表，独占空目标，初始化完成前不发布。**它不是 70 万真实源表的容量或可用性验收。**

本次实施范围来自 [A/B 计划](../../development/plans/2026-10-26-capture-admission-and-release.md)和[平台生命周期契约](../../development/specs/2026-10-25-cdc-platform-lifecycle-contract.md)。新增 SQL 冷控制索引、有界 `step()`、持久 grant、L 分派、API/控制台及受管释放；保留旧事件载荷、组账本和 T/J/D 语义。新链路的 DDL、GC、多 owner 自动接管及强制 Resume 不在本次范围。

本轮已提交的实现源码为 [`2fd592f`](https://github.com/Xuxiaotuan/pekko-reference/tree/2fd592f0c7e43af9b15f68801b7ac8f242da696b)。它与候选 8 的源清单逐文件匹配；文章和归档另作提交，不将取证时的工作区状态改写成当时已经提交。

| 实际验收集合 | 结果与口径 |
|---|---|
| 常规后端 / 控制台 | 650 / 13 项通过，前端构建与目录检查通过；后端排除 ExternalIntegration 与原 ActorSpec |
| 最终生产 P 平台矩阵 | 8 项检查通过，含收敛、取消、暂停和控制面；实际 730 个 Journal 事件反序列化，必需相等 7,680 字段 |
| 初始化内核 | 完整 61 项检查通过，其中 13 项源 marker 未知时安全暂停；保存材料复算完整，必需相等 12,882 字段 |
| 共享捕获回归 | 31 项检查通过；6 项 pending 启动拒绝，12 项原有安全负例，其余 13 项功能/恢复检查，不是 31 次收敛 |
| 原非共享恢复回归 | 7 + 17 + 3 项通过，按原冻结版本单列，不能冒称最后一版整仓重跑 |
| 读视图 / 正常释放 / L 未决归属 | P09 为 63 行、378 字段相等；B 两项为 Reader 释放；L 一项实际未决期间不复用、回滚后释放，无目标/J 对账 |
| 冷索引 H | 最终生产 700,000 条，7,000 页；写入 49,540ms、遍历 25,415ms、固定观察 60,889ms；无 ActorSystem/Reader/源表 |
| 实验隔离收尾 | 两槽实际会话/事务及用户锁为 0，无本轮存活 worker；实验租约归还，应用 UNKNOWN 行与旧失败仍保留 |

H 最后一次测试进程堆样本为 111,684,608 字节、活线程 9、活动 grant 0。样本包含测试自身的验证开销，不是生产总 RSS 或规模斜率。最终归档再检查 943 份比较快照的原始行集/字段、139 份共享/初始化 ACK 日志，以及六个拒绝样本的独立 SQL 前后不变。复算不重跑数据库；不同协议的原 27 项 ACK 格式另按其原测试断言保留，不套用共享 Reader 的 id 前缀解析。

## 版本与复核入口

运行使用冻结 JAR，不使用可变的 `target/classes`。源码清单覆盖 `server/src`、`project`、`build.sbt`，不覆盖文章、前端和 Python runner；后者分别由归档清单及构建日志记录。

| 候选 | 生产 SHA-256 | 用途 |
|---|---|---|
| 1—3 | `f714270f95c176ad640026c157f1298565f20f3c963712a9e86c4907c4b7641d` | 首轮 B/P/H、读视图及原恢复回归；测试设施分版，不能统一全源 hash |
| 4—6 | `3da309fb5f32981f001a377dd3cb74a6227edbda9ba042d556a133cbbe5b3e2d` | 控制队列准入修复后；Shared 启动测试按真实 B 状态演进，旧期待失败保留 |
| 7—8 | `a41ffa49378afa2f0e424c37be248ab889a274bae9275b1148ebc0a52b36846a` | 修复真实 L 事务账号归属；7/8 生产相同，8 仅延长测试侧 purge 安全门的观察预算 |

最终源码候选 8 的全源 SHA-256：`4a4b2a2a640489b46958d486df7d128a2da715f70877e0f32db24c1931e15862`；测试 SHA-256：`76d4e80e95ee00c43c8e48dfe9a2a0b71707a56e17986b32433b42db2ecb5001`。候选 7 全源为 `9b0138a548d76c52ff5251984b27ae460f71d7da4e1ed6f4cde9dbd0f2245638`。每个 receipt 保留实际运行的全源、生产和 runner 摘要，不用一个 hash 冒充所有测试设施。

生产变化分两次：f714→3da 只修 `pollNow` 的前置 BUSY 分类；3da→a41 只修 Kernel 的 L 连接凭据及 Observer 账号前提注释。L 的 URL/schema/命名空间和 T/J/D 格式未迁移。旧原恢复 27 项、正常 B/读视图和完整初始化 61 项在其各自候选运行；最终生产再跑平台 8 项、Kernel L 归属、共享 31 项、冷索引及常规检查。各集合不因版本相近而自动合并为一次运行。

[候选清单](candidate.json) · [冻结源码](source.zip) · [逐项结果](summary.json) · [独立材料复算](independent-verification.json) · [最终运行材料](validated-evidence.zip) · [中间失败与旧期待](exploratory-evidence.zip) · [归档索引](archive-index.json) · [凭据检查](credential-scan.json) · [最终资源确认](final-cleanup.json)

环境沿用 Scala 2.13.12、Pekko 1.1.3、Persistence JDBC 1.1.1、Debezium 3.6.1.Final、Connector/J 8.0.33、JDK 17.0.14、MySQL 9.5.0。两个已租用的专用物理 source/state 槽为 `14365/14366` 和 `14375/14376`。启动前核实 UUID/端口/datadir，前后检查实际会话、事务；未对其他服务执行 KILL、重启或清理。

## 生产实现结论

### 1. 冷任务不等于活动 Actor

`CdcTaskStore` 新建独立版本的 owner/task/grant/dispatch 控制表，不改旧持久事件。Submit 保存稳定请求身份和定义摘要；查询使用 taskId keyset 分页，不 ask 所有 Actor。当前实现是 **SQL 冷控制索引＋活动 Bootstrap Journal Actor＋有限步骤工作池**，没有为 70 万条记录实例化同数量 Actor。

snapshot 和 tail 有独立持久预算；ACTIVE 与 UNKNOWN grant 都占用额度。L dispatcher 每次最多读取 128 帧，GROUP 更新对应任务的 wake sequence；空闲 tail 持久 park，无需每表一条永久线程/定时器。配置捕获域数量也有上限，并非开放无界实例扫描。

维护停止不写业务 K。新进程仅在旧 owner 已确认为 STOPPED 且无未处置 grant 时恢复；旧状态 ACTIVE/UNKNOWN、缺 owner 但有遗留控制行，均拒绝开始替代 Reader。没有 TTL、空 Semaphore、PID 或命名锁即可接管的捷径。

### 2. 执行量子保留读视图和内容责任

兼容 `run()` 保留；平台调用 `step()`。一次推进一个完整范围窗口、一批完整组、一项激活审计或取消排空单元。等待准入/边界时让出。一个窗口内的分页属于同一 RR 连接事务，量子不承诺固定毫秒耗时。

干净步骤保存完整 J continuation，且只允许同 owner 使用；它不保存 RR 读视图。Runner 被重建时，未封存 RAW 必须按原 spec/range 新 attempt 重读，不能拼接旧页，也不能重复消耗已记入的恢复预算。J 已接受 manifest 或目标页却缺 seal 时暂停，禁止降级为重算新内容。审计 cursor 留在有界活动缓存，未完成校验的会话不会被普通空闲 tail 驱逐。

### 3. 释放要分别证明本地、服务器和 owner

`DebeziumEngineAccess.localTerminationConfirmed` 区分 callback、runner 和 executor；completion Future 单独不足。`CaptureReleaseGuard` 合并重复关闭操作，最多四个后台 close worker，独立响应截止；同步 SDK close 卡住仍保留原操作。

`requestClose/refreshRelease` 只有在本地终止、服务端相关连接/事务结束、owner 关闭且复核通过后才给 `reusable=true`。超时返回 UNKNOWN，保留配额。只读 `releaseEvidence` 不发起停止；全局关闭设施忙时后续请求仍可启动原关闭操作，不被第一次拒绝永久卡死。

服务器观察依赖捕获域专属的 source 和 SDK-state 账号，以及独立、原始、带 canary 的观察连接；source/state UUID、连接身份、权限或观察连续性不明即 UNKNOWN。生产 Kernel 的 L 仍使用 controlState 的 URL/schema，但连接凭据使用本 capture 的 SDK-state 用户，使 SDK、Reader owner 和全部 L 连接属于同一可观察账号域。共享控制用户继续用于 task/grant/dispatch、spool、目标 receipt/Sink 和 observer，不承担 L 事务。并不从共享账号的一条 PROCESSLIST 猜归属，不 KILL 旁对象。详细配置与真实字段见[配置指南](../../guides/configuration.md#resident-cdc-platform)和[部署前提](../../guides/deployment.md#resident-cdc-prerequisites)。当前 controlState 同时用于 Sink，不能误写成支持任意独立 Sink 配置。

兼容旧 `AutoCloseable.close()` 的边界是**有界本地清理**；没有可信 observer 时不能证明服务器释放，旧调用者不能将其返回当成 quota 可复用。新受管平台必须检查 `requestClose/refreshRelease.reusable`。这是与原设计提议的显式差异。

### 4. 接受、初始化、健康与释放是四种事实

API 的 202 只表示控制意图已持久接受。PREEXEC_CANCEL 不能伪造 B 或初始化证明。列表和详情分别显示 captureReadiness、initializationEvidence、runtimeHealth、releaseStatus、revision 和观察时间。

Schema snapshot/完成态 D 的准入证明不自动等于 SDK 当前 Connected。capture 健康缓存按单调时间过期；刷新卡住显示 STALE，查询失败显示 UNKNOWN。前端列表和 Drawer 同步使用版本化投影，失败时遮蔽历史健康与取消权限；未知提交重试保留原请求身份，不换 requestId 重复创建。

## 真实实验如何分类

P01—P08 使用独立 worker JVM。数据路径采用真实共享 Reader、MySQL Source、Actor JDBC Journal 与 MySQL Sink；P03/P08 刻意停在 capture 未开始的控制窗口。P08 是控制面检查；P03/P04/P07 的未初始化或安全暂停目标允许不相等。不能把八项全部写成“自动恢复且全表一致”。逐个版本、退出码和切点保存于 summary 与 ZIP 中。

| 场景 | 验收命题 | 结果类型与边界 |
|---|---|---|
| P01 | HTTP 动态 5→7，新增表先初始化，再跟随变化 | 所选七表逐字段收敛；不是七十万表 |
| P02 | 先有十个持续订阅，再新增第十一张表 | 十一表收敛；固定 snapshot=2、tail=2，活动步骤峰值不超过 4 |
| P03 | 尚无执行/B 的冷任务取消 | PREEXEC_CANCELED，J=0，无初始化证明 |
| P04 | 初始化 READ_OPEN 固定切点取消 | 原 K 和排空证据；部分目标不发布，不要求终值相等 |
| P05 | 已 STREAMING 的 a 取消，b 继续变化 | a 至 K，b 逐字段相等；不关闭共享 Reader |
| P06 | 真实释放并保存 STOPPED 后，同命名空间新 JVM | 重验并继续，选定表逐字段相等；不代表强杀后的自动接管 |
| P07 | ACTIVE grant 时旧 worker 停止，再启动新 owner | SAFE_PAUSED，新 JVM 创建 Engine 数为 0，未知配额保留 |
| P08 | API 幂等、冲突、keyset、取消权限与状态 | CONTROL_PLANE_ONLY；不计作数据恢复实验 |

P09 在真实 RR 已保存一页 RAW 后，测试 observer 注入一次 SQLException，再停止/重建 Journal Actor 和 Runner，改源端五字段并删除原范围上界行。它检验原范围、新读视图、新 attempt 与恢复预算不重复消费。**这是明确的测试切点异常，不是实际网络断线；缓存替换也是测试显式动作。**全量逐字段对账、原 spec、旧/新连接和 spool 都保留。

B 的两个真实案例分别在静默源和忙源调用受管 close，保留同一 operation 的本地结束、服务器 UNKNOWN 和最终 REUSABLE，并确认其他账号连接仍存活。它们只证明 Reader 释放，不含目标/Actor 逐字段验收；不能写成完整 B01—B10 扩展矩阵。

这两个既有案例手工装配 Store，未注入未决 L 事务，不能补作 L 资源归属的验证。新增真实 Kernel ownership 探针另行固定 L 的 COMMIT 未转发、backend 事务仍在的窗口；旧装配已实际出现 control 用户的 L 事务仍为 RUNNING，而释放返回 reusable、平台 owner 提前 STOPPED 的反例。旧材料保留，不能追溯改成新账号域下的结果。生产修复只将 L 的连接凭据纳入该 capture 专属 SDK-state 账号，物理 URL/schema、L 格式与 ACK/T/J/D 协议不变；修后验证按新候选单列，不由前两个正常 Reader 案例推定通过。

### 原共享矩阵的拒绝不能伪装成恢复

原期待回归的 30 项中，24 项通过、6 项失败，均保留原日志。R02、R03-late-commit、R03-late-rollback、R04、R07、R11-rollback 在该次实际切点留下 B_PENDING，新 JVM 准确拒绝 UNBOUND_MARKER_UNPROVEN，旧代码却继续等 PROCESS_READY。当前测试不按名称统一宣布暂停：先等旧 fixture 的真实事务结束，再独立读取 B/L/D/T/J/sourceControls；精确 pending 才验拒绝、Engine factory 调用 0、源 INSERT 尝试 0、SQL 全量不变，已 bound 则保留原恢复和逐字段断言。legacy 开关为 `SHARED_LEGACY_STARTUP_EXPECTATIONS=1`。

R07 尤其不能称为“原始 ACK 已返回”：batch=1 时，含 marker INSERT 行的回调已经返回，但 END 在后续回调，f 的 b_point/b_seq 仍为空。故障切点未移动，原失败也没有被覆盖。新矩阵的全部预期检查通过不等于全部恢复收敛；具体 SAFE_PAUSED 与其他正负例以 summary 为准。

R16-binlog 单独最后派发，必须等待专用 b 源 `14375` 和状态实例的实际会话/事务归零，再按 UUID、租约及 datadir 门核验，私有保存关闭 binlog 和 SHA 后 purge，最终确认恢复拒绝缺失历史。它没有重启数据库或 KILL 其他会话。原始 binlog 可能含认证材料，只保存在权限 0700 的实验私有目录；公开证据只有文件摘要、身份与切点，不能在线复核原始 binlog payload。

第一轮外部 purge 门曾在残留 dump 尚未结束时立即拒绝，没有执行 purge；随后清理信号又被 OS 拒绝，旧 runner 未完成 receipt。该缺失明确保留，另有真实后置物理状态和原始日志，不补造退出码。修正只让测试安全门等待物理 idle，并保存清理错误；等待预算 200 秒不是“自然释放 200 秒算快”的指标。后续成功运行另建目录。

## 70 万条冷索引的范围

H 先跑 50,000 条 pilot，按实际磁盘增长与剩余空间确认预算，再写入 700,000 条新控制记录。验收包含精确 COUNT、7,000 页 keyset 无重复遍历、首中尾 EXPLAIN、claim 索引计划、固定 60 秒观测、线程/堆/连接/事务/配额以及 datadir/binlog。MySQL 的估计 TABLE_ROWS 不代替精确计数；EXPLAIN 的估计 rows 不代替实际扫描成本。

这个探针没有创建 ActorSystem、Reader 或同量源表，不加载 70 万份 Journal。它证明的是特定索引与查询在该元数据规模和短观察窗内可执行，**不是总 RSS 上界、Schema 发现成本、长期 churn、活跃 CDC 吞吐或 70 万真实表 SLA**。最终耗时、堆样本和磁盘数字以 summary 原值为准。

## 失败保留与验收规则修正

1. 首轮 P04/P07 的测试 gate 用 String 引用做 CAS，stdin 新字符串未命中，未真正停在声明切点。保留失败；用真实字符串相等且 CAS 当前引用的测试探针修复后重新冻结。未改生产协议。
2. 首次 70 万条元数据探针在采样磁盘时与 MySQL redo 文件轮换竞争，`NoSuchFileException #innodb_redo/#ib_redo...` 中止。已提交的部分元数据保留。只对该精确临时文件消失重启完整扫描，最多三次；其他 IO 错误仍失败，不报零字节。
3. 旧 Bootstrap 期待在源 marker COMMIT unknown 后继续 STREAMING，原候选实测出现 PAUSED 并超时。这些失败和 outer 124 中止完整保存。已授权的新安全契约是保留上下文暂停，不能为了旧断言让生产继续。当前测试另明确 13 个 SAFE_PAUSED 分支，检查原 operation 一次、J PAUSED、预算 0、无 ACTIVATE、不重写源；I22 还检查取消未被接受、无 K。原期待代码保留于 `BOOTSTRAP_LEGACY_MARKER_EXPECTATIONS=1`，历史失败没有改成恢复成功。
4. 只读审查发现并通过 RED/GREEN 固定：旧 JMX 健康不能永久有效、关闭设施容量满后同操作应可再发、详情页不能保留过期健康；另以真实行为 RED/GREEN 修复前置 BUSY 污染 owner。独立审查不是运行证据。
5. 旧 ownership 探针的私有 runner 把编译 receipt 和运行 receipt 用同一个原子 create 路径写了两次，最终 receipt 缺失。真实 held/提前 reusable/STOPPED/result 及原始 ScalaTest 日志完整保留，release.json 实际 idle；不补造 worker 退出码。最终成功实验改用标准冻结 runner。首次探针还因访问 package-private cursorValue 编译失败，改为记录真实 rawOffsetText。两者均属测试设施材料，不能算行为 RED 或 GREEN。
6. 独立 verifier 反序列化实际 `event_ser_id/manifest/payload_hex`，按 Journal revision 核对控制 continuation/激活证明，并复算全字段、L digest、ACK 引用/前缀及 spool 链。控制查询与 Journal 采样非原子，投影可落后，但必须有匹配的真实持久 revision。复算是保存材料检查，不是数据库重跑。

## 验证范围汇总与未覆盖边界

| 层次 | 本轮交付 | 不能升级的结论 |
|---|---|---|
| 源码与常规检查 | 版本兼容、有界步骤、持久调度、受管释放、API/UI；命令和实际总数见 summary | 测试集合排除 ExternalIntegration 与已知旧 ActorSpec，不叫“全仓所有测试” |
| 真实 P/B/P09/H | 按上表分别验收，证据与失败均归档 | 暂停不等于修复；Reader 释放不等于数据收敛；H 不等于真实源表容量 |
| 原内核/旧恢复回归 | 最终实际集合及契约冲突单独报告 | 不能直接继承旧 61/58 个数字或合计成端到端保证 |
| 发布材料 | 源码摘要、原始证据、独立复算、五篇文章及入口 | 发布成功不等于新做数据库实验 |

整体验收仍为 **`evidence_incomplete`**。固定 Schema/有符号单列整数键/独占空目标的边界保留；无源目录 70 万真实表、任意活跃率、长时 SLA 或同负载 Flink 对照。新共享链路无自动 owner 接管、DDL 演进、GC、PAUSED 自动 Resume、业务原子换表。源/L/SDK 历史缺失或已识别的捕获连续性风险选择暂停；旧漏数、旧 C2 和旧资源迟延记录仍保留原版本。
