# 独立 CDC 入口：阶段 0 基线

本目录记录独立入口实施前的工作区边界。`baseline.json` 是逐文件状态和 SHA-256 清单，不是文件内容备份；原有文件和实验材料仍保留在原路径。采样时 HEAD 为 `68b0a1d20d8ccdb05fb244c58ba87dfe09d128e7`，上游为 `574ba8ffb50330c2ba4b143d614d063ecc91f7f7`。清单包含展开后的 665 个已修改或未跟踪文件，不包含构建产物及 Git 忽略文件。

## 持久化边界

本次入口改造不修改 CDC 恢复协议或迁移数据。以下是必须保留和验证的兼容边界：

- `BootstrapJournal.Advanced`、`BootstrapProgress` 等旧持久化类型的 JVM 全限定名和 CBOR 序列化配置；Journal persistenceId 仍使用 `bootstrap-v1-` 与原 capture/task 身份组合。
- `pekko_capture_*` 中的共享 Reader、订阅、fence、L、ACK 和归档；Debezium JDBC offset/history 保存的实际 D。
- `pekko_cdc_*` 中的 task、owner、grant 和恢复许可；不能因替换入口重置 UNKNOWN 或假定已释放。
- 目标数据、bootstrap receipt、完整组 ledger 及旧版本字段；T、J、L、D 不相互替代。
- `docs/experiments` 下已有失败、取消、局部通过与原始证据均保留；历史源码版本不因本次入口变化而升级为当前端到端保证。

## 已知限制

独立入口只暴露回环地址上的 CDC API。单活与旧进程隔离仍须由运行环境提供并验证；该入口不会改变现有协议、自动修复未知 B、启用安全 GC 或证明 70 万真实表。受管 HA / source-fence v2 未由该入口接入；不支持的配置应显式拒绝，不应自动降级。API 已绑定并不等于 Reader 就绪，客户端应读取实际状态。


## 本轮入口验证（2026-11-08）

- 独立入口和关闭阶段测试、旧 CDC 路由及历史 Journal 载荷解码等六个测试套件共 26 项通过；`Universal/stage` 成功。具体输出见 [final-build-validation.log](final-build-validation.log)。构建产物包含独立入口类和既有 Bootstrap Journal/Progress 类；默认发行包启动类仍为原 `PekkoServer`，`sbt runCdcStandalone` 是新入口。
- 独立路由测试通过 stub 验证提交、查询和取消，以及 Workflow、Cron 和前端路由不匹配；不代表数据同步成功。
- 隔离 MySQL 与独立 JVM 的四轮启动/停止对照见 [前三轮摘要](smoke-20261108-result.json)和[第四轮](smoke-20261108-attempt4-result.json)。第四轮预建 SDK 状态表后，CDC capture 观测到 `ADMISSION_READY/HEALTHY`，进程使用回环 CDC API；退出等待约 4.867 秒后，实际 owner 仍为 `UNKNOWN`，源库仍有本次 Reader 遗留 binlog 会话。该会话在归属核对后仅对专用实验实例定向清理；不能把人工清理解释为 Runtime 已确认释放。
- [D 只读回查](smoke-20261108-attempt4-d-readback.json)在独立 state MySQL 再次启动后记录了实际 Debezium offset；没有重启应用做恢复。两个实验实例均已停机，租约释放，随机 schema 与账号保留。原始进程日志保存在用户私有目录 `~/.codex/experiments/2026-11-08-cdc-standalone-entry/`（权限 0600）；未将可能含配置的日志复制进仓库，原始临时文件与私有副本已逐文件比对。
- 当时的关闭等待组件单测覆盖 `STOPPED`、延迟释放、UNKNOWN 超时及真实 CoordinatedShutdown phase 注册；这些不代表当时的真实 MySQL 服务端已确认释放。该轮真实退出与安全自动重启验收未通过；后续修复与指定窗口复验见下节。
- [baseline-verification.json](baseline-verification.json) 显示实施前清单中的 665 个文件内容与基线一致。该清单不覆盖 Git 忽略文件，也不代表所有状态数据或工作区以外文件已备份。

## 关闭生命周期与阶段 2 首步（2026-11-08）

上一节 `UNKNOWN` 停机和手动处置证据仍保留，不因本次复验通过而改写。长窗口对照中，旧 binlog dump 会话静默 92～102 秒后自然消失；独立的普通表写入实验观察到源库有新 binlog 时对应残留会话从 1 变为 0。这是该实验环境的观察，不证明所有服务器或网络场景均如此。

本次修复：SDK completion 回调先于 runner 结束时，仍需关闭实际存活的 engine；共享 Reader 配置 MySQL 连接 keepalive 为 5 秒（与 Debezium 输出 heartbeat 不同）；仅独立入口设置 shutdown phase 20 秒默认 fallback、最多等待 15 秒，显式 phase 配置优先。仍由原 guard 核验本地、服务端及 owner 的释放，超时保留 UNKNOWN；没有改变 T/J/D、ACK 或恢复协议。心跳频率提高会增加连接保活流量，并可能更早暴露网络抖动，尚未完成长期和网络分区验证。

[最终冻结版本结果](shutdown-stage2-stage2-20261108.json)与[实际 D、J、目标账本和逐字段回查](stage2-adapter-readback-20261108.json)：隔离真实 MySQL Source、Actor Journal 和 MySQL Sink；首次全量、增量各 2 行 6 字段一致；第一次停止 5 秒 owner UNKNOWN 且 binlog 会话尚在，10 秒采样 STOPPED 且两库会话/事务为 0。同一 schema 未重置状态再次启动后 ACTIVE/READY/STREAMING，重启后再次 UPDATE/DELETE/INSERT，2 行 6 字段一致，第二次停止 5 秒采样 STOPPED 且两库会话/事务为 0。只读回查 J 事件 12 条、目标 group ledger 6 条，实际 D 见证据。未使用人工 KILL 或重置 owner。此结果仅支持所选正常启停窗口和小数据样本，不代表网络分区、多 owner、自动 GC 或 70 万表容量。

失败材料保留：[未预建目标 group ledger 的实验](shutdown-heartbeat-default-20261108.json)先全量一致，增量后 PAUSED/protocol_conflict；源码拒绝路径与实际缺表一致，但缺少直接异常堆栈，不能把原因写成已实测确认。其他启动与关闭失败仍见原记录。

阶段 2 仅完成第一项：`CdcTargetNodeAdapter` 集中旧 WorkflowDSL Node 构造，字段及持久化类型保持原样；CDC 自身的批次、Source/Sink 接口及其它 Workflow 耦合仍待后续拆分。相关组件回归与源码摘要见 [stage2-target-adapter-20261108.log](stage2-target-adapter-20261108.log) 及最终构建日志。

## 阶段 2：CDC 读取与回放接口（2026-11-08）

本轮将 L 读取移入不依赖 Workflow Node/SPI 的 `CapturedLogReader`，新增仅运行期使用的 `CdcCapturedBatch` / `CdcCaptureCursor`；旧 `CapturedLogSource` 通过兼容适配器保留旧类型与 ACK 会话属性。Runner 调用 `CdcReplaySink`；MySQL Sink 的 Node 与旧 `SourceBatch` 转换留在 `BootstrapReplaySink.bind`。旧 `SourceCursor`、`BatchCheckpoint`、Journal 事件类型和 T/J/D 语义不迁移。批次 ID 的 Unicode、分隔符和序号边界与旧算法作了等价测试。

[统一构建与回归](stage2-runtime-final-build-20261108.log)：12 套 94 项通过，`Universal/stage` 成功。真实 MySQL、Actor Journal、MySQL Sink 使用冻结产物，结果按样本分别记录：

- [runtime2](shutdown-runtime2-runtime2-20261108.json)：两次启动进入 STREAMING，全量和两次增量各两行六字段一致；第一次关闭确认 STOPPED，第二次 15 秒内未确认，owner 保持 UNKNOWN。后续核对源端 binlog dump 已先消失，state 一条事务持续至 15 秒采样；20 秒采样源/状态会话和事务均为零，但进程已退出，owner 仍 UNKNOWN。该样本是失败证据。
- [runtime3 对照](shutdown-runtime3-runtime3-20261108.json)：两次启动的数据对账一致，两次关闭均于 10 秒采样前得到 STOPPED。第二次关闭初期观测到 SDK 会话关联的 InnoDB 事务在 processlist 连接消失后仍短暂存在；这支持服务端事务排空可能滞后，不能单独证明 runtime2 的具体原因。[最终只读回查](runtime3-state-readback-20261108.tsv)记录 J 12、ledger 6、实际 D 和会话/事务归零；[源码/工件哈希](runtime3-hash-verification-20261108.json)前后相同。

原始进程与 jstack 日志留在用户私有实验目录、权限 0600；两个实验 MySQL 实例已关闭、租约已释放，随机 schema、账号及失败证据保留。一次后续成功不能覆盖 runtime2 的未知关闭；本轮只证明指定样本的数据处理，尚不证明关闭释放稳定，也未验证网络分区、多 owner、自动 GC 或 70 万真实表。

## 第二次关闭事务定位（2026-11-08）

本轮只增加隔离实验与证据，没有修改生产源码或恢复协议。[runtime5](shutdown-runtime5-runtime5-20261108.json) 再次出现第二次关闭超过 15 秒、owner 保留 `UNKNOWN`；[事务关联摘要](runtime5-transaction-association-20261108.json)将事务定位到 SDK 独占账号连接，排除 Reader owner 锁连接，但该轮缺少语句与事务嵌套证据。

[runtime6 PFS 联查](runtime6-pfs-join-20261108.json)直接观察到另一轮中 schema-history SELECT 嵌在同一 SDK 连接的非自动提交只读事务内；该事务在停止后迅速释放，因此这一事实不能单独解释 runtime5 的超时。该轮 jcmd 采集超时被保留，不能计作完整验证通过。运行 classpath 实际解析的 MySQL Connector/J 为 9.1.0；不能用构建文件中的显式依赖版本代替运行结果。

[runtime7 三轮对照](runtime7-public-summary-20261108.json)使用相同 state schema 连续启动、修改、关闭；每轮在 10 秒采样时 owner 均为 STOPPED，源/状态库事务及会话均为零，源与目标两行三字段对账相等。最终实际 D 与 J、账本等按摘要记录；J/D 行数不等于位点或 ACK 前缀证明。三轮通过不能覆盖 runtime5 的超时或证明稳定，亦未证明超时根因。原始 SQL、线程栈和进程日志留在用户私有目录，校验摘要见 [runtime7 哈希清单](runtime7-private-evidence-hashes.json)。专用实验库已在资源核对后停止、恢复实验配置并释放租约。

## 关闭观测修复与连续重启失败（2026-11-08）

本轮工作区快照和 T/J/L/D 归属见 [工作树清单](baseline-current-20261108.json)与[状态归属](state-ownership-20261108.md)。清单并非文件内容备份，按记录时间生成；后续变化不自动更新。

[真实 MySQL 事务缓存对照与五轮回归](innodb-trx-cache-probe/README.md)关联了 JDBC close、PFS 的事务和回滚以及 INNODB_TRX 的采样。源码调整将 guard 未确认时的观察间隔由 20 ms 改为 250 ms，未改变原有确认条件与 UNKNOWN 处理。7 项 guard 测试、12 套 94 项原有回归与 Java 17 的 Universal/stage 通过。冻结版本无探针五次关闭均确认 STOPPED、末态事务零；其中第 5 轮源端变更已进 L，但任务因恢复预算耗尽暂停，目标未追上，五轮端到端对账为 4/5。该失败和前次 UNKNOWN 样本均保留，不能宣称连续重启主流程已稳定。

高频查询导致已回滚事务的旧记录继续可见在隔离实验中得到验证；本样本支持关闭观察受缓存影响的解释，不能证明过去每次 UNKNOWN 都由同一原因造成。其他进程高频查询同一 MySQL 仍可能影响刷新，无法确认时仍必须保留 UNKNOWN。

## 清洁 owner 交接候选（2026-11-08）

旧 owner 只有在 `endOwner` 确认无活动或未知 grant、未消费许可和其他既有阻断条件时，才在同一状态事务中记录清洁 lineage。新 owner 仅继承已清洁的 lineage；跨 owner continuation 首次执行禁用 parked-tail 快路径，由 Runner 按原规则与 Journal 精确比对，STREAMING 还要求目标 activation 证明。异常 owner 或旧版本缺少 lineage 时不继承；历史 `Advanced`、`BootstrapProgress` 和 persistenceId 未更名。该表是新增状态，不是旧 owner 隔离能力或 Journal 的替代物。

[五轮正常启停](shutdown-runtime14-runtime14-20261108.json)：隔离真实 MySQL Source、Actor Journal 与 MySQL Sink，同一状态连续 5 次启动/停止，每次初始化及增删改后两行三字段一致，停止采样均为 STOPPED、源和状态事务数均为 0。[Journal 原始 CBOR 解码摘要](runtime14-journal-recovery-attempts.tsv)中 sequence 1–14 的 recoveryAttempts 均为 0；任务表投影为 STREAMING、delta_after=37、wake_seq=37，目标 group ledger 为 15 条。此结果只覆盖小样本和固定正常关闭窗口，不证明高负载或真实 70 万表容量。

[异常退出对照](shutdown-runtime15-runtime15-20261108.json)：第二次运行完成数据对账后对实验 JVM 发送 SIGKILL；第三次启动报告 `OWNER_RELEASE_UNPROVEN`，持久 owner 保持 ACTIVE，新 owner 未获得许可。重启前源/目标均为 `(1,restart,55)`、`(4,last,66)`。这是未证明隔离时的安全暂停，不是自动接管或崩溃后追平验证；实验状态未被改写成 STOPPED。

制品 SHA-256 `3ddef6de3b333da0663157875b1f78d2c58365fa384952ef91453a3068e66b40`；候选源码 `CdcTaskStore.scala` 为 `fab80ea40213a5e0b26229d0f4a1b342db94e7451b29a409b4e102dac086945c`，`CdcBootstrapKernel.scala` 为 `17a63ad7f5b51b1a859a42ca7ac07b08ddd7e17eded2a64ad099c22adbf5f8cf`，`CdcPlatformRuntime.scala` 为 `ce95742a719f606839518921ef1d0d80306b8a2112516bb3443e1466069d94ea`。组件测试及构建输出在私有 `/private/tmp/pekko-handoff-verification-20261108.log`。原 runtime13 预算耗尽及此前关闭失败材料继续保留；未修改其任务状态。

## CDC 目标配置边界与动态表候选验证（2026-11-08）

`CdcBootstrapKernel` 现在使用 `CdcTargetConfig` 传入经过原校验规则检查的 MySQL 目标配置；`BootstrapReplaySink` 的旧 Workflow Node 入口仍在，新配置入口与旧入口共用目标提交、账本和连接释放逻辑。Source/Checkpoint 等其他 Workflow 依赖仍在，不能称阶段 2 全部解耦。

目标配置路径 [runtime16](shutdown-runtime16-runtime16-20261108.json) 使用隔离 MySQL Source、Actor Journal 和 Sink，同一状态连续五轮正常启停；每轮全量和增删改后两行三字段相等，停止采样为 STOPPED 且两库事务数为零，投影 recoveryAttempts 为 0。冻结应用 JAR SHA-256 `b91aa10de3a0bb1b842ee4c7a7c416cf0753fb66bd9330128e666672e3dfd838`。该结果不等于崩溃自动接管或所有故障窗口通过。

独立测试进程下，当前编译版本的 P01（5→7 表动态新增和逐字段对账）、P04（初始化中取消）、P05（实时中取消且另一表继续）各执行 1 项并通过；材料在 [phase3-candidate](phase3-candidate/)。P01 的更新安排在新增任务提交后，没有逐个固定 LOW/HIGH 窗口。P04 首次与 P01 复用 sbt 进程时因 JDBC DriverManager 未找到驱动失败；在单独 sbt 进程中重跑通过，失败日志保存在私有 `/private/tmp/pekko-phase3-candidate-enabled2-20261108.log`。首次默认排除 ExternalIntegration 导致 0 项运行以及随后的 sbt 设置语法失败也保留为设施证据，不算测试通过。

测试通过显式进程内设置启用指定 ExternalIntegration，用 sbt 当前编译类而非冻结 JAR。各 `environment.json` 的 `sourceManifestSha256` 字段误填了 `candidate-` 加应用 JAR 散列，**不是源码清单散列**；同一运行期间的源码文件清单见 [phase3-source-manifest](phase3-source-manifest-20261108.json)。

## 候选代码的冷元数据实验（2026-11-08）

[H01 结果](metadata-candidate/H01-metadata-63169a06a2ce/result.json)：专用 MySQL 状态库写入并精确计数 700,000 条任务记录，100 条每页完整遍历 7,000 页；写入 175,368 ms，分页 53,738 ms，随后固定观察 60,946 ms。最终采样的测试 JVM 堆使用量 259,690,000 字节、活线程 35；这些值包含 sbt 和测试开销，不是独立生产 Runtime 的堆或 RSS。活动 grant 为 0，未启动 Reader/ActorSystem，也未创建源表。测试结果中的 `sourceManifestSha256` 为 `unfrozen`，运行的是 sbt 编译类；同时间段源码文件清单及制品关联见 [清单](phase3-source-manifest-20261108.json)。这不验证 70 万物理表、实际活跃任务吞吐或 Runtime 冷启动耗时。

I04、I11、I16、I20 四个指定窗口分别执行 1 项并通过，材料见 [窗口证据](bootstrap-window-candidate/manifest.json)。其中 I04 固定读视图期间修改，I11 在 RAW 页后杀进程并重读固定范围，I16 在 baseline T 后杀进程，I20 在增量重放 T 后杀进程；均由相应测试对源和目标全部字段进行比较。这些是受控 Runner 的选定窗口，不替代未跑的 ACK/D、未知 B 和完整端到端矩阵。

P09 新增单个组合场景：[证据](phase3-p09/P09-dynamic-bootstrap-cancel-5e6af19ff118/)记录两张表先进入实时，第三张表固定在 `READ_OPEN` 时执行插入、更新、删除，同时修改原有两表；第三表追到实时后，三表各 64 行、384 字段相等。随后取消第一表并修改三张源表，取消表目标保持取消时的内容，另外两表再次逐字段相等；最终第一表源目标不同是刻意写入后的预期，不是继续同步成功。测试工作进程状态快照记录 B/F/K、L、T、J、D 等；该次使用 sbt 编译类，`sourceManifestSha256=unfrozen`，没有证明其他窗口或 70 万表。测试完成后核对并释放本次专用实验环境。

## L 提交未结束时旧进程崩溃（2026-11-08）

[P07-crash-held-l](held-l-candidate/) 在已初始化并对账的表上，固定真实状态 MySQL 的 L COMMIT 响应，使旧事务保持未结束；查到事务属于 SDK 专用账号后终止测试 worker。替代 worker 以相同状态启动时 owner 为 UNKNOWN、Reader 创建数为 0；旧事务最终回滚后，新 worker 仍保持 UNKNOWN、Reader 创建数仍为 0。故这是**安全暂停的实测**，不是自动接管或恢复成功。注入后的全量比较缺行、多行均为 0，但有 5 个字段不同；该差异在暂停状态下保留，不能称目标已追平。切点材料分别保存旧事务、连接、L、目标、Journal 和实际 D；[源码及私有日志摘要](held-l-candidate/source-log-sha256.json)标明这是 sbt 编译类与测试 worker，不是冻结发行包或独立入口。

实验结束后两台专用 MySQL 的事务和其他客户端均为 0，实例已停止并释放租约；未修改实验 owner 为 STOPPED，也未触碰其他服务。

## 存活 owner 和 D 落后对照（2026-11-08）

[P07-live-owner](live-owner-candidate/) 固定旧 worker 的 `BASELINE_T` 切点，让替代 worker 尝试运行。替代 owner 保持 UNKNOWN，Reader 创建数为 0；旧 worker 解除切点后继续，后续写入的源与目标逐字段相等。最终两个测试进程均退出，专用数据库会话和事务为 0。源码和测试日志摘要见[清单](live-owner-candidate/source-log-sha256.json)；该用例使用 sbt 编译类和受控 worker，并非自动接管实验。

[I13](i13-offset-candidate/manifest.json) 在 F 已绑定、ACK 覆盖尚未完成时锁住 Debezium offset 表，随后放行；持锁窗口内状态从 SCANNING 到 STREAMING，Journal 采样从 16 条变为 19 条，实际 D 两次采样相同，源与目标 64 行、384 字段均相等。持锁结束后事务已释放，测试进程正常退出。此实验支持“J 进展不能当成 D 已推进”的观察；**没有在 D 滞后时崩溃或验证重启后的收敛**。首次 `-t` 选择器未匹配任何测试，完整名称重跑 1 项通过；两次日志均保留于清单所列私有目录。两个实验均未改写旧 owner 状态以伪造恢复；专用实例已在事务、客户端均为 0 后停止并释放租约。

## D 被锁住后进程崩溃（2026-11-08）

在上一项 I13 的基础上新增 `I13-lag-crash`：F_BOUND 时锁定真实 Debezium offset 行，随后放行回调并写入业务变更；固定切点检查 Journal revision 从 16 到 20、实际 D 与持锁前相同、F 已覆盖，源与目标 64 行 384 字段一致。此时终止受控 Runner JVM，释放测试持有的 offset 行锁，在源表再次修改后用同一持久状态重启。恢复后 revision 为 22，实际 D 与切点不同，源与目标仍为 64 行 384 字段一致，缺行、多行和字段差异均为 0。

[切点、恢复快照与源码摘要](i13-lag-crash-candidate/manifest.json)分别保留 T、J、L、D、回调日志和逐字段比较。此结果证明该受控 Runner、该固定 F/offset 锁时序下的恢复；未覆盖独立入口或 guardian 自动接管，也未证明所有 J/D 和原始 ACK 窗口。运行的是 sbt 编译类和测试 worker，不是假装冻结发行包。实验结束后确认专用库事务与客户端均为 0，已停止并释放租约。

## 全量 baseline 目标 COMMIT 结果未知（2026-11-08）

[I17 与 I17-C0](target-unknown-candidate/manifest.json)分别在真实 MySQL baseline 页事务中固定两种情况：I17 的 COMMIT 已转发并收到服务端 OK，再丢弃回复；I17-C0 的 COMMIT 未转发，断开旧连接。两个切点目标尚不完整，分别保留 1 条和 0 条 baseline receipt；Journal 均采样 4 条，不能把不完整数据当作故障恢复通过。后续 Runner 在同一实验状态继续，二者最终 STREAMING、baseline receipt 各 9 条，源目标各 64 行 384 字段一致，缺行、多行及字段差异均为零。

两项都使用 sbt 编译类、真实 MySQL 和测试代理，指定 `SEALED → baseline T` 切点；不是独立入口的跨 JVM 接管，且本场景的增量组账本 T 为空，不证明增量组事务结果未知已验证。原始代理事件、切点与最终 T/J/L/D、全字段结果和进程日志位于材料清单及其所列私有目录。专用实例在事务和其他客户端归零后关闭并释放租约。

## 增量 replay 目标 COMMIT 结果未知（2026-11-08）

[I17-replay-C1 与 I17-replay-C0](replay-unknown-candidate/manifest.json)在固定的 REPLAY 目标事务中分别丢弃真实 COMMIT OK、阻止 COMMIT 转发。C1 的切点组账本有 1 条，最终 3 条且切点记录完整保留；C0 切点 0 条，最终 3 条。两项切点 J 均采样 17 条，最终 23 条；最终 phase 均为 STREAMING，64 行 384 字段相等，缺行、多行和字段差异均为 0。代理的实际事件分别包含 `PROXY_COMMIT_OK_DROPPED` 和 `PROXY_COMMIT_NOT_FORWARDED`。

这是当前 sbt 编译类和受控 Runner 的两种指定增量事务窗口；未覆盖独立入口跨 JVM 自动接管、迟提交或所有批次重排。切点与最终 T/J/L/D、原始日志及源码散列保存在材料清单，专用实例确认事务和其他客户端为 0 后停止并释放租约。

## 增量 replay 目标迟提交与断连排空（2026-11-08）

[I17-replay-late-commit 与 I17-replay-late-rollback](replay-late-candidate/manifest.json)在测试代理持住目标 COMMIT 时分别让旧事务迟提交或断开旧服务端连接。迟提交样本记录了真实 `PROXY_LATE_COMMIT_OK`；断连样本记录连接 807 的旧事务在切点为 RUNNING、另一事务处于 LOCK WAIT，随后记录该连接断开，最终事务采样为空。该样本没有保存对应 PFS ROLLBACK 事件，因而只将其记为断开并最终排空，不推断其他事务窗口。

两个样本切点 J 各为 17 条、增量组账本为 0；最终均为 STREAMING，J 各 23 条、组账本各 3 条，源目标 64 行 384 字段一致。它们使用 sbt 编译类、真实 MySQL 和受控 Runner，不能代表独立入口跨 JVM 自动接管、任意迟提交或 70 万表规模。原始代理顺序、连接/事务和 T/J/L/D 证据见材料清单；专用实例在事务及客户端归零后停止并释放租约。

## CDC 回放契约与独立入口崩溃后新增数据（2026-11-08）

CDC 回放接口位于 `cn.xuyinyin.magic.cdc.CdcReplaySink`，不依赖 Workflow Node；旧 `workflow.bootstrap.CdcReplaySink` 作为兼容桥保留。`BootstrapRunner` 同时保留旧和新 sink 类型的完整构造器描述符，历史 Journal 事件、checkpoint 类型和 persistenceId 未迁移。组件回归 65 项通过，含旧构造器反射检查及历史 Journal CBOR 解码；`Universal/stage` 成功。这只分离回放契约，旧 Workflow 适配器、bootstrap 持久化类型与受管 guardian 调度依赖仍在。

[独立入口 runtime18](shutdown-runtime18-runtime18-20261108.json)使用冻结应用 JAR SHA-256 `07ba18f231c36d8b7320d33b0212d28299f90bdd826a2afbb7486f8dbde72199`：前两次启动各自初始化/增量对账一致，第二次运行中对测试 JVM 发送 SIGKILL，进程终止后再修改源 id=1。第三次启动报告 UNKNOWN / OWNER_RELEASE_UNPROVEN、capture readiness UNKNOWN；采样 T 组账本数维持 6、J 事件数维持 12、实际 D 两次相同。源 id=1 为 `(aftercrash,99)`，目标仍为 `(restart,55)`，这是安全暂停下尚待追平的数据差异，不是最终一致或恢复成功。

该实验仅验证当前独立入口的非受管安全暂停：旧 owner 状态保留 ACTIVE，未人工改写为 STOPPED；不证明 guardian 隔离、自动接管或崩溃后追平。源码、制品与私有日志摘要见 [清单](replay-interface-runtime18-hashes.json)，初次缺少新接口和初次缺少旧构造器的失败日志均保留。专用两库在事务及其他客户端归零后关闭并释放租约。
