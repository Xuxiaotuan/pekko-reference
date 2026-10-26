# Shared Reader 准入与释放：A/B 最小改造计划

> 执行方式：后续按 `superpowers:executing-plans` 逐任务实施；本文件仅交付方案和验收矩阵，不批准生产改造、数据库操作、提交、推送或发布。任务使用复选框记录未来执行结果，不能把计划中的预期当成实测。

**状态：**方案待讨论，运行验收 `evidence_incomplete`。2026-10-26 只读核对源码和既存证据，未运行新的 MySQL/Journal/Sink 实验。

**后续执行记录（2026-10-27）：**上句描述方案初写时的状态。A0/B0 已完成七个启动/恢复探针组合和一项真实 Journal/Sink witness，见[探针报告](../../experiments/2026-10-26-capture-admission-release/README.md)。随后用户授权继续下一步及本地提交，现已接入 A1/A2 的启动前分类和命令准入，实际实现及定点验证见[生产准入报告](../../experiments/2026-10-27-capture-admission/README.md)。B1/AB 尚未实施；两轮材料都不能记作下面全部 26 项生产故障矩阵通过。

**Goal：**在单个受控 owner 内，阻止 Reader 重新选点越过已承诺区间，阻止未就绪时写入新的订阅 B；关闭超时或资源释放未知时保留隔离状态，不宣称可重新使用额度。

**Architecture：**启动前恢复检查、新订阅准入和资源释放是三个独立判定。保留现有 L、原始回调前缀 ACK、目标组账本和 T/J/D 语义；按本次 runtimeAttempt 建立只读运行证据，不以新的进程状态覆盖旧持久进度。

**Tech Stack：**Scala 2.13.12、Pekko 1.1.3、Persistence JDBC 1.1.1、Debezium 3.6.1.Final、Connector/J 8.0.33、Java 17；实验基线 MySQL 9.5.0。沿用现有依赖，不升级 SDK、不引入第二套 CDC 引擎。

**Spec：**[共享 Reader 启动恢复设计](../specs/2026-10-25-shared-reader-startup-recovery-design.md)、[平台生命周期契约](../specs/2026-10-25-cdc-platform-lifecycle-contract.md)。本文件把上一轮路线图的 A/B 具体化；与 2026-10-25 并行任务包的同名字母不是同一组编号。

## 1. 当前事实与根因边界

核对 HEAD：`d7769786db4d1e85085fd244449e30a10831d5bf`；当前生产实现与文章固定的 `55fad0cf27f3a289dbb5d4fb4f2a31d60a661059` 相同。本轮不把历史运行 HEAD 冒充当前提交，也不把源码检查冒充数据库重跑。

| 事实 | 源码或原始证据 | 对改造的约束 |
|---|---|---|
| `start(): Future[Done]` 返回运行终止 Future，未提供 ready | [`DebeziumEngineAccess.scala`](../../../server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/DebeziumEngineAccess.scala) | 保留旧签名；另提供准入证据，不能改变 Future 含义 |
| 当前 `start` 在恢复检查前创建控制表；恢复检查未禁止 INITIAL | [`SharedMySQLCapture.scala`](../../../server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/SharedMySQLCapture.scala) | 已有捕获域先只读检查，不能重建丢失控制表后将空表解释为未提交 |
| 当前 subscribe 检查 Schema 后 register 并写 B；历史 Schema 可来自旧 attempt | 同上 | 准入门必须在登记新意图及源提交前 |
| `cover` 在原始 ACK 前保存，ACK 返回不等于 D durable | 同上及 [`MySQLCaptureStore.scala`](../../../server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/MySQLCaptureStore.scala) | ready 要实读 SDK D；不由应用伪造、修补或代写 D |
| 已绑定 B、实际 D=INITIAL，重启后新 witness 到 T/J，旧更新五字段仍旧 | [启动探针](../../experiments/2026-10-25-capture-startup/README.md) | 这是限定配置下的真实反例；已有这类状态先暂停，不能称自动补齐 |
| JVM exit 0 后，两个槽的服务端 dump 未在 5 秒内归零，后续约 149 秒观察时归零 | [双槽报告](../../experiments/2026-10-25-cdc-lab-pool/README.md) | 释放迟延事实成立；精确耗时及根因未确定，不能预先归因于 SDK 缺陷 |
| close 中 `current.close()` 是同步调用，2 秒截止用于 tasks-starting 重试；executor 等待发生在它返回之后 | `DebeziumEngineAccess.scala` 的 `closeEngineWithRetry/close` | 现有数字不能证明整个 close 调用有界；要测试同步 close 不返回 |
| completion Promise 可先于 runner 的 finally/线程退出完成 | 同上 | 终止 Future、runner 结束、executor 结束必须分开观察 |

### 复用与候选选择

1. 复用项目已有 `CaptureStartupRecoveryFactory/Worker/SqlProxy` 的真实 SDK 门、`MySQLCommitResponseProxy`、真实 JDBC Journal/MySQL Sink，以及 `cdc-lab-pool` 的物理隔离和冻结设施。旧场景与失败材料保持原样，新断言另开 case。
2. 官方 [DebeziumEngine 3.6.1 API](https://github.com/debezium/debezium/blob/v3.6.1.Final/debezium-api/src/main/java/io/debezium/engine/DebeziumEngine.java)只给出启动生命周期和最终可能触发 flush 的 ACK 接口；不提供单个现成的“可恢复准入”信号。复用这些真实接口，不自造 committer 或修改 SDK。
3. 对照官方 [AsyncEmbeddedEngine 3.6.1](https://github.com/debezium/debezium/blob/v3.6.1.Final/debezium-embedded/src/main/java/io/debezium/embedded/async/AsyncEmbeddedEngine.java)，结合本机相同版本 sources.jar 核对 snapshot 和 heartbeat；部分 GitHub 文件抓取失败，使用本机固定版本原始源码补核，未据此宣称上游没有方案。许可证/依赖沿用当前 SDK，本轮无新依赖选型。
4. “启动中拒绝新增”优于在 Reader 再造持久队列：最小改动发生在 B 之前，平台冷队列留给后续任务 D。固定 sleep、反复重发同 B、改为 latest、定制 Snapshotter 均不采用。
5. B 首版以正常关闭和只读确认处理资源；不增加自动 KILL。MySQL [KILL 文档（8.4）](https://dev.mysql.com/doc/refman/8.4/en/kill.html)说明请求终止不等于资源立即结束；它只是通用机制依据，不当作本项目 MySQL 9.5 的实验结果。9.5 文档链接已跳转到新版本，因此最终行为以专用 9.5 实验为准。

## 2. 不变项、范围与五个重点审查输入

- 保留旧 CaptureSubscription、B/K/CapturePoint、ACK digest、SDK offset/history、Workflow/Bootstrap Journal 事件及序列化 manifest；不追溯改写 PROFILE。
- T 仍表示目标事务及账本，J 表示 Actor 接受进度，D 仍由 SDK 持久化；L 的覆盖证据不是 T/J，也不是实际 D。
- 一个受控 owner、物理独立 source/state 实例、固定 Schema和现有受支持目标；不扩展新链路 HA、GC、DDL、70 万真实表或正式 API/UI。
- 不恢复丢失的历史输入来“修绿”旧证据。已有缺口可暂停；新 generation 是另一个业务决定，不冒充补齐旧代历史。
- 资源未知不归还可重用额度。当前没有新链路持久 grant/owner epoch；此计划不声称 JVM 重启可自动恢复配额或证明旧 worker 已被隔离。

重点输入及所属验证：① B_PENDING 实际已提交或结果未知（A05—A07）；② 旧完成信号、晚到 callback/JMX、close 与 ready 竞争（A15/B09）；③ D 非空但 INITIAL/partition/ACK/history 不合法（A03/A11/A12）；④ SDK close 不返回或 completion 先完成（B02/B03）；⑤ 连接身份或观察权限缺失（B07/B08/B10）。未知必须显式返回，不能当成 false/零连接。

## 3. A：三道准入条件

### 3.1 Engine 启动前：重建持久事实

先取得受控执行许可，再只读核对 source UUID、state UUID、命名空间、控制表/旧 operation、订阅与 fence、L、history 和实际 D。控制库命名锁只证明该锁当前归属，不证明旧源连接或事务已经结束。

| 已有事实 | 最小处理 |
|---|---|
| 没有旧承诺、没有未决源写入 | 可首次启动/重做 Schema snapshot，仍不能立即接受新增 B |
| 只有 PREPARED/B_PENDING 且确证从未进入源写入，旧执行已结束 | 保留原身份，ready 后首次写入；无法确证则归入未决 |
| B 已提交、已绑定或可能已提交，D=INITIAL | Engine 重新选点前 `PAUSED_UNPROVEN`，不自动启动可能跳点的 Engine |
| B/其他 marker COMMIT unknown 或源写连接释放未知 | `PAUSED_UNPROVEN`；保留原 operation，不补写第二个 marker |
| 完成态 D，原承诺由 L、ACK、history 和完整恢复坐标覆盖 | 允许受控启动；重放保留原 B、组身份和 execution，准入仍等本 attempt 证据 |
| D/history/ACK/源控制表缺失、损坏，namespace/lineage 不匹配 | 明确分类暂停；不 CREATE 掩盖旧缺项、不改 D、不自动 latest |

不能只调用现有 `hasEvidence` 分类：它只查 ACK/BARRIER/GROUP，没有覆盖 B_PENDING 与可能已提交但未绑定的源 marker。旧记录没有新证据字段，默认重新分类，不默认 ready。源控制行只能证明 operation 存在，不携带完整物理提交坐标；恢复条件不足时继续暂停。

### 3.2 本 attempt 建立可恢复锚点

候选阶段为 `CHECKING_RECOVERY → STARTING → WAITING_CHECKPOINT → ADMISSION_READY`；任一阶段可进入 `PAUSED_UNPROVEN/FAILED`，关闭进入 `STOPPING`。这些是进程内观察阶段，不写入旧 Journal phase。

`ADMISSION_READY` 必须同时满足：

1. 当前 capture/lineage/owner 有效，没有 failure、关闭意图或未决源写；证据绑定当前 `runtimeAttemptId`。
2. 本 attempt 已越过初始选点：snapshot completed；或有可核验的 skipped 证据且 loaded D 为合法完成态。旧 Schema、taskStarted、pollingStarted 均不能代替。
3. 本 attempt 收到合法非 INITIAL callback（原生 heartbeat 或自然流），经既有 L cover 和原始 ACK 路径处理；callback/ACK 不能伪造。
4. 从实际 SDK 存储读取唯一合法 partition 的完整 D，非 INITIAL，验证对应 L ACK 引用链；它与本 attempt 的恢复/选点证据相容。不能仅比较 pos 大小。
5. 已有承诺还须证明 loaded D 到当前锚点连续；依赖但取不到 selectedReadStart、GTID/event/row skip 不可比时暂停。无旧承诺的新域可以以合法 durableResumeCheckpoint 建立未来 B 的起点，selectedReadStart 未知仍如实保留。

**前置探针门槛 A0：**先在真实 SDK 上验证上述第 2—4 项的可取得性。静默库分别测试 heartbeat 关闭与候选 `heartbeat.interval.ms=1000`；首次 snapshot 与完成态 D 的 skip 恢复分别测试。候选只作用于新 Shared Reader 的探针配置，不改全部连接器默认值、不覆盖旧 PROFILE。SDK snapshot 原生生命周期是否能用本 attempt 独占 MBean/正式回调取得，必须由探针确定；生产不解析 DEBUG 日志来制造 ready。

1000ms 只是取证候选，不能承诺静默库 1 秒 ready。若真实源没有产出可验证完成态 callback/D，则保持 NOT_READY；若正例一直不能完成，A0 的可用性验收失败，回到信号方案，不能把安全拒绝当作正常启动通过。不会为了打开门先写业务 B，也不另加自造 START marker。

### 3.3 命令线性化及原始 ACK

`subscribe` 在 commandLock 内读取当前 attempt/revision、failure/关闭和 owner，先判定准入，再做 Schema 准备、register、源写。B 开始写入前再次确认操作状态；close 先撤准入。已经越过源写线性化点的命令按在途操作收尾，不能因后来 close 而伪装“没有受理”。命令在源事务前后都携带原 operation 身份。

未准入返回明确 `CAPTURE_STARTING/RECOVERY_UNPROVEN/CAPTURE_STOPPING`，不登记新意图、不写 B；同 requestId 的无副作用查询可返回已有事实，但不能隐式触发补写。源 COMMIT 未确定要报告 `MARKER_COMMIT_UNKNOWN`，不能归为普通拒绝或安全重试。

保留顺序：完整组/Schema/marker END 持久到 L → cover 原始 callback 的引用 → 按 callback 前缀调用原始 ACK → SDK 按自己的节奏持久 D。ready 的取证查询运行于受限控制 IO，不能在 SDK callback 等待 D，否则可能阻断产生/flush D 的后续处理。

## 4. B：关闭与物理释放分层

关闭观察按四个维度返回，不压成一个布尔值：`acceptingCommands`、`localTermination`、`serverRelease`、`reusable`。

1. 原子撤销准入，登记一个关闭操作；相同 attempt 的重复 close 共用同一结果，不再提交另一个关闭任务。
2. 在途源控制操作保留写入阶段；等待其确定结束或标 UNKNOWN。撤准入不等于撤销已发出的 COMMIT。
3. 在有界关闭执行设施中请求 SDK close；callback 已失败时观察其实际 shutdown，不并行重复 stop。独立观察 runner finally、executor、callback 退出，不单独相信 completion Promise。
4. 本地处理结束后关闭 assembler、命令连接和受控资源。未确认 SDK 停止/源事务结束时，不主动归还 owner 保护或宣称可开第二个 attempt；owner 自身丢失则整体 UNKNOWN，不能把锁丢失当作旧执行已停止。
5. 从独立、受限、带权限校验的观察连接核对该 attempt 的实际数据库连接、事务和锁。确认全部相关源资源结束后关闭 owner 连接，再核查 owner 锁/会话释放。observer 自身连接单独标记，不能计作业务遗留。
6. 所有必需证据成立才 `STOPPED/reusable=true`；否则 `STOPPING/RELEASE_UNKNOWN/reusable=false`。本地已停、服务器仍未知应同时显示，不抹掉已知事实。

**等待预算建议：**每次关闭请求最多等待本地终止 5 秒，再最多等待服务端确认 5 秒，使用单调时钟和剩余预算，含 commandLock 等待、JDBC 查询及 executor 等待；不是把每次重试重新给 10 秒。同步 `AutoCloseable.close()` 使用同一操作和总截止，到期明确抛出 release-unknown。后台实际 close 可能仍在执行，保留操作/额度，不启动第二个关闭线程或替代 Reader。5+5 是待验证的调用方响应预算，不是承诺数据库资源 10 秒内消失。

后续只读复核可把 UNKNOWN 更新成 CONFIRMED，但必须核对同一 attempt、操作和身份；不自动开始新业务执行。不以 `shutdownNow`、Future timeout、JVM exit、租约 TTL 或取得命名锁代替物理释放。JVM 重启后的持久配额恢复仍属于后续任务 D/K；本轮不能抹去未知状态后自动重启旧 capture。

### B0：连接归属的前置门槛

- JDBC 连接在执行业务前登记 source/state UUID、当前数据库启动上下文、connection/thread 身份、角色、attempt 和 operation；可借鉴旧 [`SnapshotOwnerJdbc`](../../../server/src/main/scala/cn/xuyinyin/magic/workflow/scheduling/SnapshotOwnerJdbc.scala) 的方法，但不启用其静态快照 HA 上下文或沿用旧 owner 票据。
- SDK 内部 JDBC 与 Binlog Dump socket 不等同应用 sourceConnection，必须分别验证归属。测试用专用账号、独占实例、原始连接/端点证据建立映射；若生产适配需要专用 capture 账号，这是需明确的部署约束，不能从共享账号下的一条 PROCESSLIST 猜出归属。
- 不只凭 connection ID/PID；要关联可信创建记录与实例启动上下文。数据库重启、归属不明、权限不足或观察窗口不一致都返回 UNKNOWN。没有事务记录也不证明 Binlog Dump 连接已经结束。
- 本计划不授予全库扫描后 KILL 用户连接的能力。无法取得归属或可靠观察时可安全暂停，但 B0 的“正常可复用”正例仍须通过；不能用永久 UNKNOWN 冒充完整实现。

## 5. 代码职责与可审查任务

下表保留初拟接口，不能当作实际文件清单。A1/A2 实现将运行 DTO、纯分类及当前 attempt 的 snapshot 观察合并在 `CaptureStartupGuard.scala`，没有单设 `CaptureRuntimeEvidence.scala`；B 的 `CaptureReleaseGuard` 尚未实现。具体差异以生产准入报告为准。

| 文件（均在现有 server 源码树） | 职责与变化 |
|---|---|
| `connectors/mysql/source/capture/CaptureRuntimeEvidence.scala`（新增） | 非持久运行 DTO：`CaptureStartupEvidence`、`CaptureReleaseEvidence`，携带 attempt/revision/原始证据引用及 UNKNOWN 原因，不混入旧事件 |
| `.../capture/CaptureStartupGuard.scala`（新增） | 纯恢复分类与准入判定；`classify(facts: CaptureRecoveryFacts): CaptureRecoveryDecision`、`admit(evidence: CaptureStartupEvidence): Either[String, Unit]` |
| `.../capture/CaptureReleaseGuard.scala`（新增） | 单一关闭操作、截止和释放证据聚合；`requestClose(): Future[CaptureReleaseEvidence]`，重复调用共用结果 |
| `.../capture/MySQLCaptureStore.scala` | 增加分页/有界的只读旧承诺查询和完整 D/ACK 校验调用；保持旧 SQL 数据格式及 digest |
| `.../capture/SharedMySQLCapture.scala` | startup guard、命令门和关闭协作；新增 `startupEvidence: CaptureStartupEvidence`、`requestClose(): Future[CaptureReleaseEvidence]`；保留 start/subscribe/close 既有含义 |
| `connectors/mysql/source/DebeziumEngineAccess.scala` | 在真实 builder 增加已验证生命周期观察、runner/executor 终止观察及受控关闭适配；不改变旧 start Future 语义，旧 fake 默认 UNKNOWN |
| `workflow/bootstrap/BootstrapRunner.scala`（条件性适配） | 启动未准入不进入 B/SCANNING；区分明确未接受与已在途未知；只在需要时适配，不重写分页、fold、T/J/D 或扩展 Resume |

**任务 A0/B0：先验证信号及身份。**

- [x] 复用既有真实 SDK 工厂/worker，在新的 `CaptureAdmissionReleaseProbe` 中记录 attempt、snapshot 生命周期、heartbeat、callback/ACK、实读 D 与 SDK/session 归属；不写生产协议。
- [x] 无业务写入，分别完成首次启动和 skip 恢复；关闭 heartbeat 的对照不伪造通过。Shared 恢复由控制 DDL 提供输入；另设无控制 DDL 的 SDK 组件对照，heartbeat=0 的 60 秒窗口零 callback，heartbeat=1000 的首次 Heartbeat 在约 48.6 秒出现。SDK 对照不提供 L/Sink 保证。
- [ ] 验证关闭前后 SDK dump/JDBC/事务的归属；无法归属分支必须 UNKNOWN，同时正常正例必须 CONFIRMED。

第三项的正常专用槽映射和最终释放已完成；七项均在 5 秒后仍有 dump，后续实际归零。权限不足、实例重启、归属失效等真实负例尚未执行，不能整体勾选为 B 生产释放协议验收完成。

**任务 A1：纯恢复分类及兼容。**

- [x] `CaptureStartupGuardSpec` 覆盖全新域、INITIAL+旧 B、未决 marker、D/history/coverage 缺项、非法 partition/snapshot 和当前 ACK floor；8 项纯测试。
- [x] 保存首次缺少实现的编译 RED 和后续 GREEN。这个 RED 只证明测试先于实现，不冒充真实数据库缺陷复现；真实旧版窗口另由冻结旧 worker 构造。
- [x] 接入只读库存、D 和保留控制行校验，不改旧 payload。补充 `CaptureStoreReleaseSpec` 两项真实 H2 查询/关闭异常的 RED→GREEN，关闭未知及后续访问均保留 `releaseConfirmed=false`。

**任务 A2：本次证据和命令准入。**

- [x] 实际测试为 `CaptureAdmissionMatrixSpec`：真实旧版 PREPARED/INITIAL、B-bound/INITIAL，以及固定 latch 的提前拒绝、幂等和 close/已准入源写竞争，共 3 项；另含受控缺 D/控制表/L 的注入。
- [x] 接入当前 attempt 的 MBean 生命周期、原始 callback/ACK 和实际 D；先拒绝新增，再允许真实准入。既有未绑定意图不隐式补写，源标记 COMMIT 未知单独暂停。
- [x] `CaptureMarkerUnknownSpec` 验证真实源 COMMIT 已成功但回复丢失；Bootstrap 只加有界准入等待，定点回归 I01/I11/I16/I21/I21-postF。每项实际状态及候选摘要见生产准入报告。

上述完成项不表示 A01—A16 已逐项跑完。尤其没有新增持久化 startup proof，不能事后识别所有历史静默跳点后又形成完成态 D 的旧状态；已有漏数不会自动补齐。B1 的正常物理释放、有界关闭及未知资源持久恢复仍是后续工作。

**任务 B1：有界关闭及保留未知。**

- [ ] 新 `CaptureReleaseGuardSpec` 固定 SDK close 不返回、completion 早到、回调阻塞、重复 close、observer 无权限及晚到回复；断言同一 close 操作、总预算有界、UNKNOWN 不释放、不重开准入。
- [ ] 先运行 `sbt "project pekko-server" "testOnly cn.xuyinyin.magic.connectors.mysql.source.CaptureReleaseGuardSpec"` 保存 RED，再实现 B0 验证过的身份和关闭适配，运行 GREEN；原 `MySQLCdcSourceNodeSpec` 的真实 Engine 包装回归必须保留。
- [ ] 在真实 MySQL 重放 B01—B10，分别保存本地终止与服务端释放结果；超时继续保留现场，不增加 sleep 或主动清除连接来“绿化”自然释放指标。

**任务 AB：统一候选集成。**

- [ ] A/B 共改的 `SharedMySQLCapture` 与 `DebeziumEngineAccess` 由同一个集成步骤处理。可并行准备独立探针/纯测试，不能两个工作者同时改公共状态机。
- [ ] 一次冻结最终候选，再运行第 6 节全部展开用例、旧 R01—R16 与 I01—I24 相关回归；同一物理源槽内串行。不同独占槽才并行，逐 case 验证 UUID/port/datadir 和源码摘要。
- [ ] 使用真实完整 LOW/HIGH 作业追加动态 5→7、初始化取消、正常重启与 T 领先 J 的集成核验；不能只用复制基线的 Reader 探针替代。
- [ ] 单独保存自然旧 R02、确定性旧反例与新安全行为；旧断言不改成等待 ready 后称“旧场景全绿”。每个改动按具体调用契约适配旧测试，保留原失败。

初写时本文件只交付设计，所列命令均为拟执行项。后续 A0/B0、A1/A2 的真实命令、退出码和证据已分别记录在报告中；B1/AB 未完成项仍是计划。提交和推送以用户当次授权为准，本文件不代替授权。

## 6. 统一真实故障矩阵

结果类别：`CONVERGED`＝该 case 要求的原承诺区间和逐字段对账完成；`SAFE_PAUSED`＝安全边界达到但未完成复制；`REJECTED_NO_EFFECT`＝命令未登记/未源写；`FAILED`＝偏离预期；`NOT_RUN/EXTERNAL_BLOCKED`＝未运行/外部阻塞。case 符合预期不等于都是恢复收敛。

| ID | 固定切点/展开分支 | 预期与必需证据 |
|---|---|---|
| A01 | 无业务写入：首次 snapshot/skip × heartbeat 关闭/1000ms 候选 | 关闭且无证据时 NOT_READY；启用正例需真实完成态 callback、L cover、原始 ACK、实际 D 后才准入，不用测试写业务行帮它就绪 |
| A02 | 历史 Schema 存在，SDK 选点前发新增请求 | REJECTED_NO_EFFECT；register/源 B 均无新增，保留 SDK 门和 loaded D |
| A03 | snapshot 完成信号已到，D flush 卡在 INITIAL | 门保持关闭；分别证明实际锁门、D 和 ACK，不用其他 ACK 前门代替 |
| A04 | 无旧 B/在途 marker，Schema snapshot 中进程退出 | 可受控重启重新选点，完成 ready 后首次 B，CONVERGED；无旧承诺证明单列 |
| A05 | B_PENDING：确定未调用源写/无法排除旧调用两支 | 前者 ready 后同身份首次写；后者 SAFE_PAUSED；只查源行一次不足以通过未写证明 |
| A06 | B 源 COMMIT 回复丢失：实际提交/实际回滚 | 不补写；保存服务端提交、原连接/事务、源行/binlog和 L/D；确定分类前 UNKNOWN |
| A07 | B 已提交未绑定，实际 D INITIAL | SDK 重选点前 SAFE_PAUSED；原 B/SDK D/history 不移动、不重建 |
| A08 | 旧 B_BOUND 后 ACK 前、实际 INITIAL；更新 id1 后退出 | 新代码暂停于 Engine start 前；旧五字段反例保留。id2 witness 不得被用来证明 id1 已修复 |
| A09 | B 已提交未绑定，完成态 D：原 B 前/后两支 | 仅完整证据可重放原 B 的分支 CONVERGED；已越过且无覆盖证明 SAFE_PAUSED；保留 GTID/event/row |
| A10 | B 已绑定，完成态 D 落后 L；原始 ACK/D 分别卡门 | 从实际 D 重放，组身份/B 不变；真实 T/J 与全量逐字段相等，重复不产生额外效果 |
| A11 | D 缺失/语法损坏/partition 错误/namespace 冲突 | 每支 SAFE_PAUSED；保存原值及明确原因，无 latest/清空历史 |
| A12 | history 缺失/ACK 链断裂/旧控制表或 operation 缺失 | 每支明确拒绝或暂停；检查在 CREATE 前，无空表掩盖缺失 |
| A13 | 两表并发新增、同 requestId 重复、同 id 不同定义 | 同 id 同定义最多一个原 B；冲突拒绝；不为每请求启动 SDK |
| A14 | cancelPending 先提交/bind 先提交/COMMIT unknown 时取消 | 迟到 bind 不复活取消代；已绑定走原 K；未知不提前完成，其他合法表继续 |
| A15 | close 先于 ready/ready 先于 close/准入与源 COMMIT 交错 | 准入不可迟到复活；已发出的源写报告实际阶段；没有隐藏 B；与 B09 交叉 |
| A16 | 旧持久记录、旧 CBOR manifest、无运行证据的新旧版本读取 | 旧 payload/原 D/L digest 不变；重新分类，完成态恢复与 INITIAL 暂停分别报告 |
| B01 | 正常关闭，静默与有业务流量各一支 | 本地 runner/executor/callback 完成、源/state 会话/事务/锁确认后才 reusable |
| B02 | SDK 的同步 close 被 latch 阻塞至总截止之后 | 调用方在预算内 UNKNOWN，唯一关闭任务仍可追踪；释放门不打开；该模拟切点不能代替真实关闭测试 |
| B03 | completion 已失败/成功，而 runner finally 或 executor 仍卡门 | completion 单独不释放；实际本地终止后再核服务端，无并行替代 Reader |
| B04 | callback 异常触发 SDK 自行 shutdown，再请求 close | 无重复 stop 竞争；保存原异常、真实 runner 结束及 ACK 前缀，无强行确认残组 |
| B05 | tasks-starting 时关闭/启动直接异常/尚未 start 即关闭 | 每支有界、重复操作幂等、晚到事件不能 ready；原 wrapper 回归不削弱 |
| B06 | 源 COMMIT 在途或 close 回复不确定 | 源连接/事务未定时 UNKNOWN；目标 T/J 不借本地 timeout 推进；与 A06 共用原始事实 |
| B07 | JVM/SDK 本地已停，真实服务端 dump 仍存在 | serverRelease=UNKNOWN/reusable=false；保存逐次采样时间，后续确证消失再更新，不能由晚到一次采样倒推出精确释放耗时 |
| B08 | observer 无权限/网络断/UUID 或启动上下文改变/归属不明 | 各支 UNKNOWN，无伪造零连接、无误杀；恢复观察本身不自动恢复业务执行 |
| B09 | 重复 close、上个 attempt 的迟到 ready/释放回复 | 同一关闭操作；旧回复不能改变新 attempt 证据，不能重开命令门 |
| B10 | 旁 capture/服务仍活跃；旧运行释放未知后请求重开 | 不关闭、不 KILL 旁对象；本域重开拒绝。JVM 重启未建立持久恢复机制时不得以空内存注册表开门 |

这是 26 个基础编号，实际运行数按分支展开；不得预先写成“26 项通过”。B02/B03 的确定性包装测试单列，真实 MySQL 场景另计；旧 MySQL/Journal/Sink 八项恢复矩阵及历史 C2 失败证据按关联范围回归，不用新组件测试替代。

## 7. 对账、证据和最终门槛

每 case 保存：源码/测试/依赖及工具摘要；source/state UUID、版本和实际配置；attempt/命令/operation；loaded D、callback 原始 offset、原 ACK 调用顺序、实读 D；B/K/LOW/HIGH/F、L 引用链；目标 T 账本和 Actor J 原始事件；连接/事务/锁/本地线程的切点与最终状态。凭据和含敏感信息的原始 binlog 留私有目录，公开材料只留脱敏内容、路径和摘要。

恢复正例在测试写入停止并到达固定终点后，读取完整源/目标行集，比较主键集合和每一字段的 null、数值、字符串、时间精度，独立复算差异；逐组核对账本和 J 指向的 L，不以 COUNT 相同替代。跨库读取不冒充原子全局快照，采用受控静默与固定 marker 证明比较范围。

安全暂停/拒绝负例保存其本来就可能不一致的行集，并验证暂停后的该任务 T/J 稳定、原 B/身份不动、没有非法 ACK/源写；共享 Reader 合法的其他任务进度和正常 D flush 不要求全局冻结。命令未接受和“接受后暂停”分别验收，不把拒绝实验的空目标说成端到端相等。

**M0 完成条件：**A0/B0 的正常正例通过；A/B 各展开 case 命中真实切点并符合各自预期；原风险状态不会继续静默漏数；正常启动、恢复和关闭仍可用；完整 bootstrap 相关回归及原恢复/序列化兼容检查有同候选证据。任何必要信号/归属无法取得、正例不能推进、未知释放被归零，均不通过。

可并行：A0 和 B0 的只读分析/独立探针准备、A1 纯模型测试和 B1 包装测试。必须串行：公共 SDK/capture 状态机集成、候选冻结、同槽故障实验和最终对账。完成 M0 后再进入 Runner 步骤化/持久调度，不用此次安全门替代 70 万表容量、长期运行或 HA 验收。
