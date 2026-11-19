# Shared Reader 启动与恢复准入设计

状态：**讨论稿，未批准实施；运行验收 `evidence_incomplete`。** 本文属于任务包 B，核对当前源码、本机 Debezium 3.6.1.Final 源码、已有 R02 材料，并在 C 提供新结果后复核其 candidate-4 原始证据。B 未修改生产代码或测试，未自行运行数据库实验、迁移或提交。本文的状态、字段及错误名均为候选契约，不是现有 API。

## 1. 要闭合的边界

目标是：只有 Reader 的恢复起点已固定且已有可恢复检查点时，才允许提交新的订阅 B；对已经存在的 B，先证明恢复仍覆盖其承诺区间。启动阶段不能借重新选点越过已有承诺。

现有 R02 已证明一件具体的事：恢复进程发出 PROCESS_READY 后，B 在 SDK 重新选点之前提交，SDK 从 B 的事务末尾开始，B 没有被捕获。它本身不覆盖已绑定 B 的恢复。C 随后在 candidate-4 的独立探针中命中“B 已绑定、该 callback 尚未 ACK、实际 D 仍为 INITIAL”的窗口：重启后实时 witness 已经过真实 Sink 与 Journal，B 后、重启前的一笔更新仍未到目标，出现五字段不一致。这个窗口已有具体失败证据，范围与传输/门差异见下一节；不能扩大成所有配置或完整 Bootstrap 端到端结论。

最小建议是两个互补的门：

1. **启动前恢复检查**：存在已提交或已绑定 B，而实际 D 是 INITIAL、缺失、损坏或无法证明连续性时，暂停，不启动一个可能重新选点的 Engine。历史证据保存原样。
2. **新订阅准入检查**：当前启动 attempt 尚未取得可恢复就绪证据时，明确拒绝新订阅，不登记新的 B 意图、不写源 marker。上层可保留自己的待调度请求，但不能显示 Reader 已接受或 CAPTURE_READY。

这是建议，未获实施批准。它以暂时不可用换取不扩大既有捕获区间中的未知缺口；成功恢复与安全拒绝分别验收。

范围限于一个受控 owner 的进程重启。当前命名锁不是持久 fencing epoch，也没有跨 owner 的 HA 接管证明。本文不引入接管、GC、DDL 演进或目标初始化协议。

## 2. 当前代码与真实 R02 的因果链

以下定位基于 HEAD `00ad18e624568ef595bd90e2d6ac00008bd235a0` 加当前未提交工作区，不能仅凭 HEAD 复现全部代码。源码路径均相对仓库根目录。

| 编号 | 当前代码定位 | 本轮核实的行为 |
|---|---|---|
| P1 | `server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/DebeziumEngineAccess.scala:25–27,133–139,189–254` | `start` 返回运行终止 Future；正常关闭才成功，意外退出或异常失败。它不提供就绪 Future，也未接 ConnectorCallback。 |
| P2 | `server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/SharedMySQLCapture.scala:59–101` | start 建源控制表、取得状态库命名锁、记 PROFILE；有 L 证据时要求 history、D 及 ACK proof 存在；随后异步启动 SDK。检查没有禁止 INITIAL。 |
| P3 | 同文件 `:80–95,190–201,214–232` | 配置 `no_data`、全库表包含、Schema 事件；subscribe 只检查已有 Schema/JDBC 一致性，再 register 和写 B。已有 Schema 可以来自上一个 attempt。 |
| P4 | 同文件 `:119–155,182–186,246–255` | 源 B 控制事务先提交；只有捕获 ROW 与对应事务 END 后才 bind B；L cover 在原始 ACK 前；ACK 返回不是实际 D 写成证明。 |
| P5 | `server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/MySQLCaptureStore.scala:123–180,201–231` | B_PENDING 与 BARRIER 绑定分离；bind 原子写 BARRIER/路由；verifyOffset 验证 D 对应 ACK proof 链，未验证 SDK 此次是否沿用该起点。 |
| P6 | 同文件 `:51–55,145–165,210–214` | 没有源 marker 的 COMMIT 结果列、startup evidence 或 attempt 列。`hasEvidence` 只看 ACK/BARRIER/GROUP，不看 PROFILE/SCHEMA/B_PENDING。 |
| P7 | `server/src/test/scala/cn/xuyinyin/magic/workflow/integration/SharedCaptureMatrixWorker.scala:111–118`；`SharedCaptureMatrixSpec.scala:82–86` | worker 在 `capture.start()` 返回后立即打印 PROCESS_READY；R02 等旧 Schema、只 PREPARE 后崩溃，恢复一见 PROCESS_READY 就 subscribe。 |
| P8 | `server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/CaptureModels.scala:6–26` | CapturePoint 是 ROW occurrence；路由区间为 B 之后、K 及之前。它不是 SDK 完整恢复 offset。`initialized` 固定为 false。 |

R02 的原始事实取自 [r02-startup-boundary.json](../../experiments/2026-10-25-bootstrap/r02-startup-boundary.json)、[README](../../experiments/2026-10-25-bootstrap/README.md) 和 [binlog 截段](../../experiments/2026-10-25-bootstrap/r02-startup-window.txt)：

| 时刻/顺序 | 证据 | 含义 |
|---|---|---|
| 原进程保存的 D | `mysql-bin.000006:211714068, snapshot=INITIAL` | 原 Schema snapshot 尚未形成可直接恢复的完成状态。 |
| 12:32:09.628 UTC | 恢复 worker 发 PROCESS_READY | 只说明调用 start 已返回，尚未证明 SDK 加载 D。 |
| 12:32:09.684500 UTC | B 源事务提交，ROW `211714759`，END `211714909`，GTID `…:453631` | B 在源库已生效；这时没有 B_BOUND。 |
| 12:32:09.829 UTC | SDK 加载旧 INITIAL D | SDK 尚处于恢复判定阶段。 |
| 12:32:10.856 UTC | SDK 重新确定起点 `211714909` | 新起点位于 B ROW 之后，GTID 集也已包含该事务。 |
| 随后 | `capturedB=false`，`B a timed out` | Reader 没有重新看到 B，订阅没有进入捕获就绪。 |

因此阻断点应在 B 源 COMMIT 之前。只增加等待 B_BOUND 的 timeout 不改变源事务先于选点的顺序。重复调用当前 `writeControl` 使用同一 operation 的 `INSERT … ON DUPLICATE KEY UPDATE operation_id=operation_id`；它不能作为“保证重发出原始 B ROW”的机制。另写一个新 B 会移动承诺起点，也不能用于掩盖此失败。

### 2.1 C 新证据：已绑定 B 与 INITIAL D 的独立失败

本段复核材料来自 C 的 [candidate-4 索引](../../experiments/2026-10-25-capture-startup/candidate-4.json)、[环境](../../experiments/2026-10-25-capture-startup/B-bound-D-initial-0d00664ccfa7/environment.json)、[崩溃前 B/D](../../experiments/2026-10-25-capture-startup/B-bound-D-initial-0d00664ccfa7/bound-B-with-INITIAL-D.json)、[witness 后 T/J/逐字段结果](../../experiments/2026-10-25-capture-startup/B-bound-D-initial-0d00664ccfa7/after-witness-T-J.json)、[outcome](../../experiments/2026-10-25-capture-startup/B-bound-D-initial-0d00664ccfa7/outcome.json) 及 [binlog](../../experiments/2026-10-25-capture-startup/B-bound-D-initial-0d00664ccfa7/binlog-window.txt)。测试源码冻结在同目录上级的 `candidate-4-sources.zip`；不能把后续测试工作区改动当作这次候选。

| 顺序 | 本轮从 C 保存材料复核到的事实 |
|---|---|
| 原起点 | 实际 SDK D 为 `binlog.000003:194970, snapshot=INITIAL`，GTID 集为 `…:1-486`。 |
| B 绑定 | `a/g1` 已是 CAPTURE_READY，B ROW point=`195262,row=0`，BARRIER L seq=`18`；binlog 中 B 事务提交到 `195412`。该 callback 在 B_BOUND 之后、RAW_ACK_BEGIN 之前被暂停。 |
| B 后更新 | 源 `a.id=1` 提交 `committed-between-B-and-restart`，ROW 起点 `195705`、事务结束 `195893`，GTID `…:488`。保存上述 B/D 与完整源/目标行集后 crash。 |
| 恢复选点 | recovered.log 记录旧 snapshot 未完成并重做；实际新 snapshot 点=`196292`，GTID 集=`…:1-489`，已越过 id=1 更新。 |
| 恢复活性 witness | 观察重启 Schema callback/原始 ACK 后，再更新 `id=2` 为 `post-restart-witness`；其 GROUP 为 L seq=`20`、ROW point=`196585`，真实 Sink receipt T 有 1 条；Actor JDBC Journal 的 `ExecutionCheckpointAdvanced` 为 J seq=`6`，cursor 指向 L seq=`20`。 |
| 对账与失败 | id=2 已在源/目标相等；id=1 的 label、amount、revision、note、updated_at 五字段仍分别是旧值。保存结果 `equal=false`，最终断言失败并保留材料；无缺行/多行并不消除此字段错误。 |

传输与切点边界必须保留：该实验使用专用 loopback SQL proxy，SDK 源连接显式设置 `database.ssl.mode=disabled`；状态 JDBC、真实 Sink 与 Actor Journal 沿既有连接路径。original/recovered 的 SDK batch size 均为 128；旧自然 R02 的 original 为 1、recovered 为 128。`snapshot.mode=no_data`、locking=none 沿用原值，未改变生产协议。

为确定性命中，测试先在 SDK 日志 step 7（no_data 清 INITIAL 之前）暂停 producer；真实 Schema callback 经原始 committer ACK 返回且实际 INITIAL D 写入后，又在现有 RAW_ACK_RETURNED observer 暂停 callback return。随后释放 producer、保持 consumer 暂停，提交真实 B，并观察 SDK 的唯一控制 INSERT 已入队，再放开 consumer。下一批真实 callback 含最后 Schema 与完整 B 事务，原 store.bind 后在 B_BOUND observer 暂停于 ACK 之前。这里没有拆分或伪造 SourceRecord、没有伪 ACK、没有 offset SQL 锁门、没有改写 D；它与“故意锁住 D 落库”的候选场景不同。重启使用 batch128/NONE，不再卡门，但 SDK proxy 仍为 disabled TLS 配置。

这些事实证明：**此探针配置下，已有 B + INITIAL D 可以在重启后继续推进 T/J/L，同时遗漏 B 后、重启前的已提交更新。** 这已不是完全未验证的假设。它支持把该类旧状态视为已知风险并默认暂停；尚未验证新的暂停实现、其他 callback/ACK 窗口、其他 batch/传输配置，也不证明完整 LOW/HIGH Bootstrap 或线上环境都出现相同行为。该 fixture 是 B 前已复制的已知基线，不是在线初始化实验。

## 3. 本机 Debezium 3.6.1 实际提供的信号

本轮直接读取本机缓存的 `*-3.6.1.Final-sources.jar`，并以 `javap` 核对 API 二进制中的 ConnectorCallback 和 Builder 方法。没有依靠最新版网页推断 3.6.1 行为。

缓存根目录为 `/Users/xujiawei/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/debezium/`。下文 `common!文件:行` 表示 `debezium-connector-common/3.6.1.Final/debezium-connector-common-3.6.1.Final-sources.jar` 中的精确 Java 成员；`api`、`embedded`、`binlog`、`mysql` 同理。所有成员路径都以 `io/debezium/` 开头。

### 3.1 INITIAL 为何会重新选点

源码链是：

1. `common!snapshot/mode/NoDataSnapshotter.java:27–45`：不快照数据；存在历史化 Schema 时，`shouldSnapshotSchema = !offsetExists || snapshotInProgress`，并继续流读。
2. `common!pipeline/CommonOffsetContext.java:67–89`：`snapshot=INITIAL` 且尚未 completed 时，`isInitialSnapshotRunning=true`；完成后清除 snapshot 标志。
3. `common!relational/RelationalSnapshotChangeEventSource.java:149–175,301–331`：旧 snapshot 未完时重做 Schema snapshot；调用 determineSnapshotOffset。
4. `api!spi/snapshot/Snapshotter.java:68–76`：默认 `shouldStreamEventsStartingFromSnapshot=true`；NoDataSnapshotter 没有覆盖此默认值。
5. `binlog!connector/binlog/BinlogSnapshotChangeEventSource.java:257–272`：只有上述值为 false 才复用 previousOffset；否则新建 offset 并向源库取 snapshot 起点。
6. `mysql!connector/mysql/MySqlSnapshotChangeEventSource.java:54–80`：执行当前源端 binary-log-status 查询，读取 file、position、GTID 集。

这足以解释 R02 的 `211714068 → B ROW 211714759 → 211714909`，但不替代新的故障实验。把 no_data 改成其他模式、定制 Snapshotter 强行保留旧 INITIAL 起点，都会改变恢复协议，超出本设计的最小建议。

### 3.2 信号能力与不能推出的结论

| 信号 | 3.6.1 精确位置 | 可证明 | 不能单独证明 |
|---|---|---|---|
| `connectorStarted` / `taskStarted` | `api!engine/DebeziumEngine.java:74–107` | SourceConnector/SourceTask 的 start 方法已返回 | Schema snapshot 完成、选点结束、D 可恢复 |
| `pollingStarted` | 同文件 `:111–115`；`embedded!embedded/async/AsyncEmbeddedEngine.java:523–534` | Engine 即将开始轮询 task | 已开始 binlog 读取；该回调在实际 polling 之前 |
| 异步 coordinator 启动 | `mysql!connector/mysql/MySqlConnectorTask.java:277–279`；`common!pipeline/ChangeEventSourceCoordinator.java:123–162` | task 把 snapshot/stream 工作提交到另一线程 | taskStarted 与 snapshot 完成之间没有同步 ready 边界 |
| SnapshotProgressListener | `common!pipeline/source/spi/SnapshotProgressListener.java:27–31` | 内核有 completed/aborted/skipped 观察点 | 它不是 Engine Builder 可直接 using 的 SDK 回调；本轮未实现注入 |
| SnapshotCompleted / Running / Aborted / Skipped JMX | `common!pipeline/metrics/DefaultSnapshotChangeEventSourceMetrics.java:31–40,54–70,104–130`；`pipeline/meters/SnapshotMeter.java:137–147,181–203` | 当前 metrics 对象收到相应 snapshot 生命周期通知 | D 已持久化、binlog 已连接、已有 B 连续性；Skipped 不等同 Completed |
| snapshot 完成次序 | `common!relational/RelationalSnapshotChangeEventSource.java:196–215`；`pipeline/source/AbstractSnapshotChangeEventSource.java:113–138` | no_data 先清 INITIAL，执行 postSnapshot，再尝试发 heartbeat；返回后 final stage/close 再通知 completed | 看到一个非 INITIAL offset 就等于所有 snapshot 清理与 streaming 连接已经完成 |
| Streaming Connected metric | `common!pipeline/ChangeEventSourceCoordinator.java:357–369` | coordinator 开始初始化 stream | 实际客户端已经连上；此处在 `streamingSource.init` 前设置 true |
| binlog 客户端 onConnect | `binlog!connector/binlog/BinlogStreamingChangeEventSource.java:300–307,1300–1305` | 客户端连到 binlog，日志含 offset context | D durable、业务 marker 已捕获；它是内部客户端 listener，当前 Engine Access 未暴露 |
| 原始 callback / ACK 返回 | P4；`api!engine/DebeziumEngine.java:135–147` | 批次已进入应用，或 committer 方法已返回 | markBatchFinished 的 API 仅说“may result” in flush；不能据此宣称 D 已落库 |
| 实读 D + `verifyOffset` | P2/P5 | SDK 存储内的 checkpoint 有对应 L ACK 完整性证明 | SDK 后续不会因 INITIAL 重选点；需要单独分类 snapshot 状态 |

JMX 候选观察必须绑定本次 attempt：在 Engine 启动前确认不存在未释放的同 capture MBean，记录本次注册/观察关系，校验 connector/capture 命名空间，保存原始属性与采样时刻。若无法排除同名旧对象或注册失败，记 UNKNOWN，不将“读取到了 true”升级为本次完成。metrics 对象每次新建 SnapshotMeter；其对象身份仍不自动成为持久 owner epoch。

### 3.3 静默源上的关键限制

当前 connectorProperties 没有配置 `heartbeat.interval.ms`（`MySQLCdcSourceNode.scala:429–471`），SDK 默认关闭（`common!heartbeat/Heartbeat.java:40–51`）。MySqlConnectorTask 使用 `HeartbeatFactory.getScheduledHeartbeat`（`mysql!connector/mysql/MySqlConnectorTask.java:220–238`），该工厂在 interval 为 0 时返回 NOOP（`common!heartbeat/HeartbeatFactory.java:115–122`）。

因此，尽管 no_data 源码调用 `alwaysDispatchHeartbeatEvent`，**当前配置不能保证产生一个 snapshot 完成后的 heartbeat SourceRecord**。现有 Schema snapshot 事件可能都携带 INITIAL，静默库可能长时间没有非 INITIAL callback/D。把“看到 Schema”或“等待固定秒数”当 ready 仍不成立。

候选最小取证路径是：经批准显式启用 SDK 自带 heartbeat，观察 no_data 完成/skip、本 attempt 非 INITIAL 原始 callback、L cover/ACK、实际 D 的联合证据。非零 interval 时工厂构造 HeartbeatImpl，emit 会把 offset 的 SourceRecord 入队（`common!heartbeat/HeartbeatFactory.java:124–138`；`heartbeat/HeartbeatImpl.java:128–146`）；这只是源码依据，静默源、INITIAL 恢复和 skip 分支的真实行为仍须 C 验证。优先不用新的业务控制 marker 充当 ready，以免新增生产控制表协议。

若不批准 heartbeat 配置或它不能产生所需证据，保守路径是等待自然非 INITIAL 流事件，静默源保持 NOT_READY 并拒绝新订阅。不得放松证据标准。直接订阅内部 SnapshotProgressListener、依赖 SDK 内部 logger、加入自定义 Snapshotter 都不是已有可用 API；若最终选择它们，需要另行列出侵入范围与版本耦合。

## 4. 最小状态机与就绪判定

以下是进程内 Reader 状态，不覆盖现有 subscription status，也不等于表初始化状态。

| 状态 | 进入证据 | 允许的动作 |
|---|---|---|
| NEW | 尚未开始 | 只允许 start/查询 |
| CHECKING_RECOVERY | 命名锁/owner 校验成功，正在只读核对 D/history/L/旧 B | 查询、请求关闭；不写新 B/K/F |
| STARTING | 恢复分类允许启动 Engine，本 attempt 正在选点/Schema snapshot 或 skip | 取证、查询、关闭；新订阅拒绝 |
| ADMISSION_READY | 满足下述联合判定，保存本 attempt 证据快照 | 经 command 串行门再次核对后，登记并写新 B；已有区间按已证明路径读取 |
| PAUSED_UNPROVEN | 连续性证据缺失或互相冲突 | 查询、保存材料、受控关闭；不自动恢复、不写新 B、不业务发布 |
| FAILED | SDK/owner/store/Schema 等确定失败 | 撤销准入、报告失败并关闭；不把进程仍活着显示为 ready |
| STOPPING / STOPPED | close 已申请 / Engine 与资源释放已确认 | 拒绝新命令；释放不明时仍 STOPPING 并保留错误/资源占用事实 |

转换只前进；重启创建新的 runtimeAttemptId，从 CHECKING_RECOVERY 重建，不能复用旧 attempt 的 true。ready 后失败必须立即撤销准入；phase 与 evidence revision 一起更新，避免查询层缓存一份永久 true。

**ADMISSION_READY 的建议联合判定：**

1. source lineage、capture namespace、运行配置 PROFILE、当前命名锁/owner connection 均有效，尚无失败或关闭意图。
2. 所有旧意图与 B 的恢复分类已完成，没有待裁决的 committed/unknown marker，也没有 INITIAL + 已承诺区间。
3. 本 attempt 的初始 Schema snapshot 已完成，或已明确 skipped 且所加载 D 是可恢复完成态；不能仅检查历史 Schema 有记录。这个生命周期证据证明已越过初始选点阶段；能够取得精确 selectedReadStart 时保存其依据，不能用当前 D 冒充 SDK 最初的选点值。
4. 已观察本 attempt 的合法非 INITIAL offset callback（heartbeat 或自然流记录）及其 L cover/ACK 关系；对正常 skip 恢复，也需本 attempt 的实际观察证明，避免只靠启动前的旧 D 宣告活性。
5. 从实际 SDK offset 表读取完整 D，按唯一合法 partition 解码，snapshot 不为 INITIAL/未知，证明其对应 L ACK 链有效；D 是 SDK 自己写入，不由应用代写。读取到了任意非空行不算通过。
6. D 的恢复语义与已加载起点/SDK 本次选点相容；已有捕获区间不能被跳过。与其他 signal 冲突时暂停。新启动没有任何已承诺 B，允许 SDK 首次选择当前源点；这不承诺首次选点以前的变化历史。

第 3–6 项的确切采集/匹配实现仍需 C 的真实切点实验冻结，尤其 GTID、event/row skip、Schema snapshot final stage 与 D flush 的先后关系。当前不具备这套判定实现，不能称当前版本已 ready。若 SDK onConnect 尚未可验证，单列 transport 状态 UNKNOWN；上述可恢复准入不冒充“已观察到流记录通过实时链路”。目标初始化与发布继续由 Bootstrap 的 J/T/激活证据决定。

这里区分两个坐标：`selectedReadStart` 是本 attempt 的最初读取起点；`durableResumeCheckpoint` 是准入时已经形成的可恢复锚点，可能更靠后。没有旧 B 的新捕获域，只要第 1–5 项成立，可以用后者建立未来 B 的连续性，不要求为了一个诊断坐标侵入 SDK 内核；前者未知时仍标 UNKNOWN。已有 B 的恢复则必须额外证明从 loaded D 到当前锚点的连续性；若结论依赖无法取得的 selectedReadStart，保持暂停，不把未知位置默认成旧 D。

selectedReadStart 在有值时必须保留原始 SDK offset/GTID 与来源；CapturePoint 只能描述物理 ROW，不能拿三个数字代替 SDK 恢复 offset。非 INITIAL D 也不是凭位置大于 B 就安全：D 之前的已承诺变化必须已有 L 及完整 ACK 证明。涉及 GTID source filter、purge 或 binlog namespace 改变时，先验证可比性，不能只比较 position。

## 5. 订阅准入的三种候选

| 候选 | 准入完成语义 | timeout / 取消 / 重启 | 代价 |
|---|---|---|---|
| 启动中明确拒绝，**本设计推荐** | 只在 ADMISSION_READY 内进入 register+源 COMMIT；否则返回 CAPTURE_STARTING/RECOVERY_UNPROVEN 等可判定原因 | 拒绝前不写新的意图/B；调用方决定同 requestId 重试。已提交后的不确定性按下一节恢复，不能冒充拒绝 | 改动最小、没有潜伏的异步 B 写入；上层需显示/调度等待 |
| 有界等待 | 命令在内存等待 ready，得到准入后才登记/写 B | deadline/cancel 在尚未进入写入时结束；一旦写入开始必须回报 commit unknown 或 accepted。timeout 不能表示 Future 已停止 | 必须限制等待人数/字节、原子处理 deadline 与 ready 竞争；进程重启丢等待者 |
| Reader 持久排队 | 受理只表示意图 durable，之后才排队写 B | 需要排队/写中/commit unknown/bound/cancel 等持久状态及重启 dispatcher；取消不能等于删除意图 | 引入新持久协议、队列和调度预算，与 E/F 重叠；不适合作为 R02 最小修复 |

推荐明确拒绝由最小因果链决定：失败发生在尚未 ready 时提交 B，不需要先为 Reader 引入持久队列。E/F 可以在自己的持久冷队列中等待 Reader；必须显示“请求已受理、Reader 未准入”，不能借 HTTP 202 宣称 B 已写入。

同 requestId 的查询/幂等读取可以在 NOT_READY 返回已有持久事实，但不能因此触发隐式重写 B。真正的新增或待补写 marker 都必须重新通过准入门。公共返回类型与错误分类未冻结，不修改当前同步 subscribe 签名。

## 6. 已有 B 的恢复分类

先读状态库意图/BARRIER/L 与实际 D，再核查源控制行；不能以 `B_PENDING` 单独认定 B 从未提交。源控制表中的行只证明该 operation 存在，不包含 ROW/COMMIT 的物理位置。定位提交位置还需保存的 raw/binlog/GTID 证据。源控制行丢失或被外部改写不能自动解释为未写入。

| 恢复事实 | 建议安全行为 | 明确禁止 |
|---|---|---|
| 无意图、无 B，无旧捕获承诺 | 允许 SDK 正常新启动；待 ready 后才登记新订阅 | 启动返回即写 B |
| 有 B_PENDING，可靠证明源 B 未写入、无旧在途写入 | 保留原身份；SDK 可以完成首次/重做 Schema snapshot；ready 后首次写该 B | 仅凭 B_PENDING 或一次无行查询把 commit unknown 归为未写 |
| B COMMIT 回复丢失或旧写连接释放未确认 | 暂停该命令并保持 UNKNOWN；核源行、原始 COMMIT 证据及旧连接是否结束 | timeout 后启动第二个 B 写入；以新 requestId 绕过 |
| B 已提交但未绑定，D 为 INITIAL | 默认 PAUSED_UNPROVEN，Engine 启动前拒绝重新选点；保留源行、原 offset/history/L | 重新 snapshot 到最新后重发 B，把旧等待变成新起点 |
| B 已提交但未绑定，D 为完成态 | 仅在证明恢复起点可重新覆盖原 marker、Schema/partition 可用后重放并绑定原 B；位置未知或 D 已越过 B 而无覆盖证明则暂停 | 把 marker 存在等同 bound；只用 D 非 INITIAL 判定此 B 连续 |
| B 已绑定，D 为 INITIAL | 默认 PAUSED_UNPROVEN；第 2.1 节已在 batch128/测试 proxy/B_BOUND 后 ACK 前窗口实证不一致。其他窗口仍需证明连续性，新暂停行为仍待实现和验证 | 宣称已有 B 即安全，或把此探针结果扩大到所有配置/完整 Bootstrap；从后续 T/J 进度推断旧缺口已补齐 |
| B 已绑定，D 完成态且 verifyOffset/连续性完整 | 保持 B 的原位置/代次，从 SDK D 重放；允许重复由既有原始组身份处理；新订阅仍等本 attempt ready | 移动 B、改写 D，或以目标 count 相等替代恢复对账 |
| D 缺失、损坏、partition/namespace 不匹配，已有 B/ACK/GROUP | 暂停；报告具体缺项并保留材料 | latest、清空历史、凭当前源全量覆盖旧目标 |
| D 缺失且只有 PROFILE/SCHEMA/未写 B 意图 | 必须先证明没有已提交/在途 B、没有其他承诺，再按未建立区间的新启动处理 | 直接沿用 `hasEvidence=false` 得出可安全 latest |
| CANCELED、已 STOP 的旧代 | 校验其旧 B/K/CANCEL 证据，保留终态；新代使用新的合法身份/独占目标 | 删除旧意图使迟到 B 变成新代的 B；复活旧代 |

“可靠证明未写入”要求旧进程/Engine 及可能提交该 marker 的源连接已结束，operation 身份与源行内容核对一致，且没有外部删除证据。命名锁位于状态库，只拿到新命名锁不能证明旧源事务已经停止。若无法证明，使用 UNKNOWN 分支。

对于已有捕获域，检查源控制表/行必须先于任何可能掩盖缺失的初始化动作；不能先沿用当前 start 的 `CREATE TABLE IF NOT EXISTS` 重建丢失控制表，再将空表解释成“所有旧 B 都未写”。若表缺失，保留该事实并暂停。真正全新捕获域才允许正常创建控制表。

不建议用人工篡改 offset/history 解除暂停。如果未来提出从保留 binlog 与完整 L 证明恢复旧区间，需要单独的恢复许可、校验工具与实验；本文不提供自动修复通道。即使另建 generation 初始化当前最终状态，也不能宣称补齐旧承诺的变化历史，必须作为用户明确决定的新任务。

## 7. 并发、关闭与取消

当前 `commandLock` 串行 subscribe/unsubscribe/fence，store.tx 锁 reader 行；callback 不持 commandLock，close 先置 closed 再等 commandLock（P3/P4 与 `SharedMySQLCapture.scala:257–268`）。建议保留这些既有串行边界，在其上定义最少的准入线性化点：命令取得 commandLock 后，检查当前 attempt/revision、owner、failure/close 与 readiness，然后才 register/write。准入检查与写入之间若已经关闭或失败，返回明确的写入阶段事实，不得伪装“完全未受理”。实际实现不能只在方法入口读一次旧的 ready=true。

并发新订阅可以依次通过同一门；不为每个请求启动 Engine，也不让单表取消关闭共享 Reader。同一 requestId 的不同 table/generation/schema 仍是冲突；同表活动代次约束继续由 store.register 保证。

取消需区分阶段：

- **Reader 尚未接受、没有 B**：E 的排队请求可取消；不写 STOP，不创建临时订阅。
- **B_PENDING 且未绑定**：当前 store.cancelPending 的事务锁与 bind 竞争；cancel 先提交时迟到 bind 保留 CANCELED，bind 先提交时 cancelPending 拒绝，必须转 K 流程。此语义不等于源 B 从未提交。
- **B COMMIT unknown**：保留未知与资源占用，不声称取消已阻止源提交。查询/核实不应生成第二个 marker。
- **B 已绑定**：取消依赖原 K 被捕获/绑定及上层释放证据；不能仅改状态。若 Reader 因 INITIAL 连续性未知而暂停，不能直接绕过暂停写 K 并宣称完成。
- **close 与启动/ready 竞争**：close 先撤准入；迟到 SDK completed/JMX/raw 信号不能重新开启门。资源释放未确认不能标 STOPPED 或让新 attempt 并行接管。

这些是建议的并发契约。现有 close 超时与 SDK tasks-starting retry 的实现（`DebeziumEngineAccess.scala:256–319`）只说明已存在释放处理，不能替代上述新增准入竞争的测试。

## 8. 给 E 的 StartupEvidence 候选语义

此处供 E 的平台设计保留外部只读接口，不是批准后的 DTO，也不要求 C 增加生产代码。

| 候选字段 | 语义及约束 |
|---|---|
| `captureId`, `sourceLineage`, `profileRef` | 标识捕获域及已经核对的配置；不含密码/JDBC 凭据 |
| `runtimeAttemptId`, `ownerControlProofRef` | 本次受控进程的观察域、命名锁/连接校验材料引用；**不是持久 fencing epoch，不提供 HA 保证** |
| `revision`, `phase`, `observedAt`, `reasonCode` | 一份单调更新的事实视图；时间只用于诊断，不能证明事件先后或连续性 |
| `loadedCheckpoint` | 启动前实际 D 的原始值/安全摘要、partition、snapshot 分类、对应 ACK proof ref；缺失与未读分开 |
| `snapshotObservation` | 本 attempt COMPLETED/SKIPPED/ABORTED/UNKNOWN、实际来源及证据引用；不能从旧 Schema 推导 |
| `selectedReadStart` | SDK 实际选定/沿用的原始 offset/GTID、origin=`NEW_SNAPSHOT` 或 `RESUMED_D`、观测来源。无法直接取得时为 UNKNOWN，不伪造为当前 D |
| `rawObservation` | 非 INITIAL callback 的 offset/partition、记录类型、摘要和本 attempt 归属；heartbeat 不等于业务 B |
| `durableResumeCheckpoint` | 准入时实际 D 的实读结果、L ACK proof ref、snapshot 分类；与 raw ACK 返回分开 |
| `continuityDecision` | `NEW_INTERVAL_ALLOWED` / `EXISTING_INTERVAL_PROVEN` / `PAUSED_UNPROVEN`，附所校验 B/区间集合的引用与原因；不能只有一个 ready 布尔值 |
| `transportObservation` | SDK binlog onConnect/真实流事件等证据或 UNKNOWN；与可恢复起点和目标初始化分开 |

`admissionReady` 若最终保留，只能是第 4 节联合判定的派生值，不能独立持久 true。E 可展示 capture 启动、ready、恢复暂停与证据原因；表是否 CAPTURE_READY 看原 B，是否初始化完成/STREAMING/可发布看 Bootstrap 自己的持久事实。旧 subscription 状态不能反向构造新的 StartupEvidence。

建议第一版以实际 D/L 现有材料重建证据，不先增加一个可以过期的持久 ready 标记。若 C 证明必须持久保存 SDK 选点/完成见证才能判定，新增表或 L frame 类型须在 D 实施前另行确认；读者兼容、事务边界、崩溃恢复与旧版本回读都需明确。

## 9. 旧持久化与升级策略

最小建议保持现有 CaptureSubscription 状态、B/K/CapturePoint、ACK digest、SDK offset/history 原始内容和 Bootstrap Journal 事件/快照类型。不得为迁就新状态机重写旧 B/D 或把进程 phase 混入旧 Journal enum。PROFILE 当前由固定 key 密封（`MySQLCaptureStore.scala:78–79`）；不能顺手变更 PROFILE 内容绕过既有内容一致性检查。

升级时没有 StartupEvidence 的旧记录标为 LEGACY_UNCLASSIFIED，由当前代码重新读取 D、Schema、L、源 marker 并分类。缺新字段不等于损坏，也不等于 ready；旧 INITIAL + 已有 B 进入明确暂停。旧完成态 D + ACK proof 可以成为恢复证据的一部分，仍需本 attempt 完成/skip 及读取观察。

若批准候选 heartbeat 配置，把本次生效属性及其配置摘要记入 attempt 证据；不追溯修改已经密封的旧 PROFILE。是否需要升级新的 PROFILE 版本与命名空间，在 D 实施前明确，不能为了添加一个配置项改写旧 frame digest。

新增 schema/frame/version 尚未获得授权。本建议若只增加进程内准入与只读投影，旧格式可以原样读取；是否确实无需新持久证据要由 C 实验确认。回退旧二进制会恢复旧的早写 B 风险，不能称为安全回滚；已升级的运行域在回退前应停止新订阅并保留材料。自动跨版本接管不在范围内。

## 10. C/D 必须完成的故障矩阵

下列是验收矩阵。原始 R02 材料覆盖第一行的旧行为；第 2.1 节给出已绑定 B、ACK 前、INITIAL D 的独立旧行为失败证据。除此之外，新增建议行为及未注明已复核的场景均为**待验证**。新探针必须保存实际切点被命中的证据，不能通过增加 sleep 命名一个并未命中的窗口。

| 场景/实际切点 | 建议预期 | 必需证据 |
|---|---|---|
| 旧 R02：旧 Schema + INITIAL，恢复 PROCESS_READY 后原样发 B | 原代码仍可复现 B 未捕获；新契约在 B 源写前拒绝。分别报告，不能修改旧断言冒充原样通过 | loaded D、源 ROW/END/GTID、重新选点、B_BOUND 缺失、拒绝前后源控制行 |
| Schema 已持久，SDK 尚未完成选点 | 新订阅拒绝 | SDK 选点门、持久 Schema、无新增源 B |
| snapshot completed/skip 已观察，D 写入被阻塞仍 INITIAL | 未 ready，不写 B | 本 attempt metrics/原始观察、D 实读、L/ACK 与 flush 门 |
| 无 B，INITIAL，Schema snapshot 中崩溃 | 可重新选点；完成完整就绪后才首次写 B | 两 attempt、无旧 B/在途提交、原始 D、完成后 D 与 ACK |
| PREPARED/B_PENDING，确认 B 尚未写入 | 重启先 ready，再写同身份的首次 B | 源行缺失证明、旧写连接结束、register 与实际 COMMIT |
| B COMMIT 回复丢失，未绑定 | 保留 UNKNOWN；确认真实源提交结果后分类 | 服务端 COMMIT 帧/连接、源行、binlog、D/L/BARRIER |
| B 已提交未绑定 + D INITIAL | Engine 重新选点前安全暂停 | 启动预检、源 B 坐标、未启动新 snapshot/未重写 D/B |
| B_BOUND 后、该 callback ACK 前、实际 D INITIAL，再崩溃重启 | **旧行为在 C candidate-4 配置下实际不一致**：id=2 witness 已达 T/J/L，id=1 五字段旧值；新契约应在重选点前暂停，尚待实现验证 | 第 2.1 节 B195262/D194970、新点196292、T1/J6/L20 与逐字段差异；保留 batch128/disabled TLS proxy/双门区别 |
| B_BOUND 后，另用 D SQL 锁门保持 INITIAL，再崩溃重启 | **该具体锁门场景未验证**；默认暂停，不能借上一行替代切点证明 | BARRIER、实际锁与 D、业务完整行集、选点/拒绝点、L/ACK 连续证明、逐字段差异 |
| B 已提交未绑定 + 完成态 D 在原 B 前/后两分支 | 前者只有证据充分才重放原 B；后者无覆盖证明暂停 | 完整 SDK D/GTID/event/row、原 marker occurrence、原始回调及 END |
| B 已绑定 + D 完成态，D 比 L frontier 落后 | 从实际 D 重放，B 不动；原始组可重复但不能漏 | T/J/L/D、组身份、目标逐字段对账，不只 count |
| 缺 D、坏 D、错 partition、丢 history、坏 ACK 链 | 启动拒绝/暂停 | 原损坏材料、明确原因、没有 latest/清空/替代 B |
| 静默源 heartbeat 关闭/候选非零配置；首次 snapshot 与 skip 各一组 | 关闭时没有证据则拒绝；候选配置须证实实际非 INITIAL D 后再准入 | 生效属性、真实 callback 类型/offset、metrics、ACK、实际 D、无业务写入 |
| 并发两订阅、重复 requestId/冲突参数 | 串行准入、身份一致、冲突拒绝 | command 顺序、源 marker 数、BARRIER 唯一性 |
| cancel 先于 bind / bind 先于 cancel；commit unknown 时取消 | CANCELED 不复活；已绑定走 K；UNKNOWN 不提前释放 | 事务门、迟到 callback、原 B/K、其他表继续 |
| close 与 snapshot 完成、ready、源 COMMIT 竞争；启动异常 | 不再准入、不迟到复活；写入结果按阶段报告，释放未知保留占用 | Engine 终止、source/state 连接、owner 锁、晚到信号/命令结果 |
| 旧持久化记录无 StartupEvidence | 不改旧 payload；重分类，可证明恢复与安全拒绝分开 | CBOR/serializer manifest、SQL 原行、D/L/旧状态前后摘要 |

C 可以使用独立测试 worker、SQL wire gate、真实 SDK builder 的测试工厂或 JMX 读探针记录这些信号，不需要先增加生产回调接口。SnapshotProgressListener 是内核观察点而非已确认 Builder 注入口；探针若无法精确命中，记录缺口，交由 B/D 冻结最小观察接口后再补。不得要求 C 先改生产以制造 green。

## 11. 本轮结论与批准前剩余决定

本包实际完成：阅读任务包 B 与 R02 保存材料；逐行检查 SharedMySQLCapture、MySQLCaptureStore、DebeziumEngineAccess 及其配置/worker 调用点；读取本地 Debezium 3.6.1 API、embedded、connector-common、binlog、mysql sources；用 `javap` 确认二进制 API；比对工作区范围。C 出具 candidate-4 后，B 只读复核其四份场景 JSON、冻结测试源码、original/recovered 日志与 binlog，并更新第 2.1/6/10 节；B 没有自行执行数据库、SDK 引擎或测试。

源码结论：当前 start Future 是终止信号；Schema 历史不是本次就绪；INITIAL 会让 no_data 重做 Schema snapshot 并重新取源点；当前默认 heartbeat 关闭，不能假设存在完成后 callback；原始 ACK 也不是实际 D。R02 与 C candidate-4 的限定实验事实均与这条源码因果链相符。后者已给出具体已绑定 B 窗口的真实不一致，不能继续把整个问题表述为完全未验证。

设计建议：新订阅采用明确拒绝；已有 B/commit unknown + INITIAL 先暂停；每次 attempt 重新建立联合证据；先保持旧格式、单 owner 和 T/J/D/完整组语义。需要用户决定是否接受这一准入行为，以及是否批准显式启用 SDK heartbeat 作为最小取证候选。C/D 必须先证明候选信号确实可取得、D 语义可恢复，再冻结接口和实现；本文件没有批准或实现任何一个方案。

剩余风险是准入信号可用性、INITIAL 与已承诺区间的恢复连续性、GTID/skip offset 的精确匹配、取消/关闭竞争，以及旧格式重分类成本。上述矩阵未完成前保持 `evidence_incomplete`；未遇到外部条件阻塞，不标 `external_blocked`。

### 本轮本地 SDK 取证摘要

| artifact，版本均为 3.6.1.Final | sources.jar SHA-256 |
|---|---|
| debezium-api | `9e3784441e6e58a88afc17d3c856917097575dd15debc71dd8f3022adf6fe442` |
| debezium-embedded | `230995b723e967a4b3361bb0e24c56d6f844d11193a4c3cb940418dc692999c4` |
| debezium-connector-common | `f9ae7357d442b21a66b52ce546bda77b10da4ff5c67e1b4cf4d3f7c060e6e9f0` |
| debezium-connector-binlog | `6929915d5362ca04cdf97beffdae8f66d21f5a92f10f35a4ee66c837bd090901` |
| debezium-connector-mysql | `31e2f5d4b0fbe953be4e5e3df9bc6b6f114c0161c899981d666bb34ee4114ae2` |

API 二进制 SHA-256：`893c73a44dd05b3a102830efb53b2636c63cfdf890860411dcc4969282c9544b`。embedded 二进制 service descriptor `META-INF/services/io.debezium.engine.DebeziumEngine$BuilderFactory` 指向 `io.debezium.embedded.async.ConvertingAsyncEngineBuilderFactory`，与本文核对的 AsyncEmbeddedEngine 路径一致。
