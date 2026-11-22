# L 安全 GC：实现与真实故障验证

本轮本地实现和验证已结束，代码与材料均未提交、未推送。未知 B 的恢复协议保持原样。

基线 `529abb494b96d0913160d3e3e1c97437569b6c4d`，分支 `feature/pekkosync-mvp-cleanup-datafusion`。最终版本由 [源码清单](release-candidate/source-manifest.sha256) 固定，清单 SHA-256 为 `7b742da650819bbe5dbd9da59c882ebb7ef20383027e4d7bd2154d93c7f09434`。不能用早期运行代替这个版本的结果。

实验环境：Source MySQL 9.5.0、State/Journal/Sink MySQL 9.5.0，分离实例；Source 使用 ROW/FULL。MySQL 8.0/8.4 未在本轮复测。

## 实际结果

- 最终 [验证日志](release-candidate/verification.log)：LG01—LG16 加容量补测共 **17 项通过**；相关主流程、Journal、关闭和持久化兼容回归 **31 项通过**，没有取消或跳过。
- LG01—LG16 的订阅表 a 各 64 行、6 列，共比较 **6,144 个字段值**，缺行、额外行、字段差异均为 0。LG10、LG11 还验证了 b 的完整逐字段结果，详见各自 `final.json`。
- 容量补测按预期暂停：a 存在 **3 个字段差异**，没有标记追平；Journal 保留 PAUSED，`recoveryAttempts=0`，实际 D 的 ACK 证明仍有效。
- 本轮最终矩阵共删除 **82 个原 GROUP 行**（含容量补测），保留对应恢复元数据。每例还核对 `used_bytes` 与原帧、恢复帧逻辑计费的重算值一致。这个数字不代表 InnoDB 文件缩小或系统总存储下降。
- [资源释放](release-candidate/resource-release.json)：三个专用实验 MySQL 观察到外部客户端连接 0、事务 0；随后按 UUID 和 datadir 核对并 [关闭实验实例](release-candidate/shutdown.json)。数据目录与全部失败材料保留。

机器可读结果：[validation-summary.json](release-candidate/validation-summary.json)。

## 源码实现与证明边界

以下是源码结论；真实验证支持范围见后面的矩阵。

1. `BootstrapRunner.requestGcCheckpoint` 让 STREAMING 会话执行原有 StepAudit，逐步核对完成窗口、回填、目标页收据和目标组账本；审计完成且 J 未变化才申请封存。`run` 和 `step` 共用封存逻辑。
2. Actor 的专用封存命令绑定 revision、definition、execution、B/F 初始化证明、completionRoot、deltaAfter、generation、源库与目标表。普通 Journal Write 不能修改 `gcCheckpoint`。旧持久化字段与 persistenceId 保留；新增可选字段缺失时读为 None。
3. 会话持有的 Actor receipt 在同一状态库事务中核验精确 Journal 事件，保存 PENDING_TARGET。激活还验证真实目标 claim、ACTIVATE 收据、受限 writer、不可变目标账本触发器、同一事务/MDL、同一 Reader 实例和 owner 锁，之后才推进 consumer/subscription pin。管理员及账号权限独占仍是信任前提；哈希用于完整性核对。
4. 新消费者、订阅 pin 与 L 写入、GC 共用 capture head 行锁。回收范围为 `seq < min(所有 pin.required_from, 实际 SDK D 对应的 ACK)`。实际 D 在 SDK offset 行的共享锁下读取，再校验分区和 ACK 引用链，不以最大 ACK 代替 D。UNKNOWN 清点、缺 pin、未知 B 均阻止回收。
5. operation 持久化固定的 from/to/next、epoch、阶段、删除行数和逻辑节省字节。epoch 变化时重新检查原固定上界是否仍受保护，不扩大范围。每批最多扫描 128 个原始帧，GROUP 正文读取预算 1 MiB，锁等待 1 秒；ACK 链另有既有的 4096 节点/65536 引用/15 秒证明预算，这不是整批总耗时承诺。
6. 每个实际删除的 GROUP 都重新核验 Journal 证明和精确目标组证据，生成可逆恢复帧，再把恢复帧插入、原行删除、预算扣减、operation 游标推进放在同一 MySQL 事务提交。重复 operation 不扩大范围；回复未知时以持久化状态继续。
7. 恢复读取、按 source_key 去重、ACK 引用与启动连续性检查使用原帧和恢复帧的逻辑序列。GROUP 从仍保留的 T 组账本重建，校验原 payload hash、原 digest、成员和身份；有归档时继续核验原归档叶子与根。Journal/目标证据缺失时拒绝恢复，不跳过原有审计。
8. GC 的事务执行范围涵盖 COMMIT 和连接关闭。关闭确认未知时保留在途计数与 owner；关闭流程不会把仍在途的 GC 当成已排空。

主要实现：[CaptureGcRetention.scala](../../../server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/CaptureGcRetention.scala)、[BootstrapRunner.scala](../../../server/src/main/scala/cn/xuyinyin/magic/cdc/bootstrap/BootstrapRunner.scala)、[MySQLCaptureStore.scala](../../../server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/MySQLCaptureStore.scala)、[SharedMySQLCapture.scala](../../../server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/SharedMySQLCapture.scala)。

## 最终矩阵

所有行均使用真实 MySQL Source/Debezium Reader、MySQL JDBC Actor Journal 和 MySQL Sink；故障由原始 SDK 调用、生产观察点、真实 COMMIT 回复代理或数据库证据故障注入控制。未制造 SourceRecord 或 ACK。LG14 的 B_PENDING 来自生产登记接口，不代表新增一次源端 COMMIT 结果未知的完整恢复实验。

| 用例 | 固定窗口与验收 | 实际结果 | 证据 |
|---|---|---|---|
| LG01 | 正常封存、激活、小批次删除、重启及新变更 | 通过；a 全量逐字段一致 | [验收](release-candidate/LG01-2f5121d3a4f4/acceptance.json) / [T/J/L/D 与对账](release-candidate/LG01-2f5121d3a4f4/final.json) |
| LG02 | REPLAY_T 返回后、J 更新前强制终止；只回收旧受保护前缀 | 通过；a 全量逐字段一致 | [验收](release-candidate/LG02-7bf7a7523cb2/acceptance.json) / [T/J/L/D 与对账](release-candidate/LG02-7bf7a7523cb2/final.json) |
| LG03 | J_COMMITTED 返回后、floor 投影前强制终止；保留新 GROUP | 通过；a 全量逐字段一致 | [验收](release-candidate/LG03-1736985a2ad0/acceptance.json) / [T/J/L/D 与对账](release-candidate/LG03-1736985a2ad0/final.json) |
| LG04 | GC_AUDIT_COMPLETE 后、Actor 封存前终止；新候选未获许可 | 通过；a 全量逐字段一致 | [验收](release-candidate/LG04-0a425763841a/acceptance.json) / [T/J/L/D 与对账](release-candidate/LG04-0a425763841a/final.json) |
| LG05 | 首次激活的真实 MySQL COMMIT OK 被代理截留；重启重复激活 | 通过；a 全量逐字段一致 | [验收](release-candidate/LG05-ac25d6a93df4/acceptance.json) / [T/J/L/D 与对账](release-candidate/LG05-ac25d6a93df4/final.json) |
| LG06 | 实际 GROUP 删除的 COMMIT OK 被截留；重读固定 operation | 通过；a 全量逐字段一致 | [验收](release-candidate/LG06-88f19ccc5164/acceptance.json) / [T/J/L/D 与对账](release-candidate/LG06-88f19ccc5164/final.json) |
| LG07 | 确认已删除 1 个原 GROUP 后终止；续跑原固定范围 | 通过；a 全量逐字段一致 | [验收](release-candidate/LG07-2051b176bb08/acceptance.json) / [T/J/L/D 与对账](release-candidate/LG07-2051b176bb08/final.json) |
| LG08 | 原始 markBatchFinished 调用前阻塞；断言实际 D 对应 ACK < 最新 ACK | 通过；a 全量逐字段一致 | [验收](release-candidate/LG08-69895e15e561/acceptance.json) / [T/J/L/D 与对账](release-candidate/LG08-69895e15e561/final.json) |
| LG09 | ACK 仍引用已删除 GROUP；最新受保护 J 重启；旧游标登记被拒绝 | 通过；a 全量逐字段一致 | [验收](release-candidate/LG09-71bfafb24ee3/acceptance.json) / [T/J/L/D 与对账](release-candidate/LG09-71bfafb24ee3/final.json) |
| LG10 | 慢表 b 的 T 后/J 前阻塞；快表 a 推进；释放慢表后逐字段追平 | 通过；a 全量逐字段一致 | [验收](release-candidate/LG10-28d7fd885376/acceptance.json) / [T/J/L/D 与对账](release-candidate/LG10-28d7fd885376/final.json) |
| LG11 | GC 实际持有 head 锁时并发启动 b；准入等待，释放后初始化及追平 | 通过；a 全量逐字段一致 | [验收](release-candidate/LG11-0e79e0c41386/acceptance.json) / [T/J/L/D 与对账](release-candidate/LG11-0e79e0c41386/final.json) |
| LG12 | c 在 LOW 后读视图关闭处取消；K/取消完成前后均保留保护 | 通过；a 全量逐字段一致 | [验收](release-candidate/LG12-f5bf2eba4c2e/acceptance.json) / [T/J/L/D 与对账](release-candidate/LG12-f5bf2eba4c2e/final.json) |
| LG13 | 真实归档后删除；归档损坏、证明停用、Journal 删除标志、账本缺失拒绝；停用证明时独立 JVM 重启拒绝 | 通过；a 全量逐字段一致 | [验收](release-candidate/LG13-f1b349a1f779/acceptance.json) / [T/J/L/D 与对账](release-candidate/LG13-f1b349a1f779/final.json) |
| LG14 | 生产 register 建立 B_PENDING，GC 拒绝；清点标记 UNKNOWN 时也拒绝 | 通过；a 全量逐字段一致 | [验收](release-candidate/LG14-b8b4fecb1514/acceptance.json) / [T/J/L/D 与对账](release-candidate/LG14-b8b4fecb1514/final.json) |
| LG15 | 独立 worker 抢同一 owner 被拒；真实异库 UUID/数据库身份、源 lineage 不匹配拒绝 | 通过；a 全量逐字段一致 | [验收](release-candidate/LG15-d2a7965f2910/acceptance.json) / [T/J/L/D 与对账](release-candidate/LG15-d2a7965f2910/final.json) |
| LG16 | GC 锁等待超时退出；恢复后追平；实际删除后的物理关闭确认丢失保留 owner | 通过；a 全量逐字段一致 | [验收](release-candidate/LG16-aceefc071c89/acceptance.json) / [T/J/L/D 与对账](release-candidate/LG16-aceefc071c89/final.json) |
| LG16-capacity | 真实 Reader 在 90,000 逻辑字节上限写满 L；持久化 PAUSED，保留实际 D 和差异 | 安全暂停，3 处差异，未追平 | [验收](release-candidate/LG16-capacity-961e61d223d6/acceptance.json) / [T/J/L/D 与对账](release-candidate/LG16-capacity-961e61d223d6/final.json) |

证据目录中的 `initial.log`、重启日志保留原始回调、SourceRecord、原始 ACK/SDK 调用顺序；`final.json.worker` 包含 L、实际 D、Actor J 和表状态，外层 T 为完整组账本。专门窗口另存 `cut-before-recovery`、`lost-reply-durable`、`between-batches`、`actual-D-window`、`slow-table-protected`、`cancel-before-K`、`corruption-rejected`、`database-identity-rejected`、`close-unknown` 等文件。

## 初次失败及修正

| 材料 | 实际失败 | 修正与后续证据 |
|---|---|---|
| [run-01](run-01/suite.log) | 读取 SDK offset 使用 FOR UPDATE，受限 GC writer 被要求拥有更新 SDK 表的权限 | 改为 FOR SHARE；SDK 保留 offset 写入所有权 |
| [run-02](run-02/suite.log) | 已删 GROUP 后，启动检查只统计原 L，报 CAPTURE_LOG_HEAD_INCONSISTENT | 启动检查核对完整逻辑序列，同时保留计数、唯一性与连续性约束 |
| [matrix-01](matrix-01/suite.log) | 11/16 通过；4 例测试在 Reader 未重新捕获就绪时调用 GC，被正确拒绝；另 1 例锁等待拖慢表读取导致暂停 | 驱动同时等待 Reader ADMISSION_READY 和表 STREAMING；GC 锁等待缩短为 1 秒；全部失败文件保留 |
| [verified-final](verified-final/verification.log) | 矩阵 16/16 通过；本地关闭回归 30/31，H2 驱动未显式加载，首例未进入待测关闭故障 | 仅在测试夹具显式加载 H2 驱动，未削弱异常断言；最终 31/31 |
| [matrix-02](matrix-02/suite.log)、[final](final/suite.log) | 中间版本通过记录 | 供追溯；最终证据使用 release-candidate 的源码清单与 17+31 项结果 |

上一阶段的 [组件证据](../2026-11-11-capture-gc/README.md) 仍保留原验证层级，未升级为端到端结果。

## 当前限制和未验证范围

- 首版回收 L 的重复 GROUP 正文。原 ACK、控制帧、Schema、恢复帧、目标完整组账本、快照 spool、基线收据和 Journal 继续保留；对应证据仍有存储成本。没有验证磁盘文件缩容。
- GC 需显式调用审计、发布、激活与分批删除方法；尚未接入独立 CDC 入口的自动 GC 调度、运维 API 或配置开关。该矩阵验证数据面链路，不代表现有集群已经开启 GC。
- 激活要求专用 `pekko_gc_*` 命名空间、受限 writer、受控管理员和 fresh birth 证明。旧 UNKNOWN 命名空间默认拒绝，没有自动清点迁移。
- 只承诺受保护恢复点。旧游标登记拒绝已测；没有证明任意历史 Journal/快照备份都可恢复。删除证明引用的 Journal、T 组账本或所需归档将安全拒绝。
- 取消表的保护仍保守保留，没有自动撤销 pin 的证明路径；慢表和取消表可能阻挡全局前缀，容量不足时暂停。
- 容量补测验证安全暂停，没有验证容量耗尽后的自动恢复或自动扩容。关闭确认未知时保留 owner，没有在该状态下允许自动接管。
- 未覆盖 DDL、跨库/外部 Sink、恶意管理员、GC 与 guardian 故障接管的完整组合、70 万真实表、长时间 ACK 链超过既有证明预算、长期吞吐与全部元数据 GC。
- 本轮 Source 的 INSERT/DELETE/主键迁移与复杂字段类型没有作为 GC 专项新增故障矩阵；本轮对账数据覆盖 bigint、UTF-8/空白、decimal、NULL/空串、datetime(6)、int。

当前没有提交、推送或部署。下一步是审阅本地结果；若需要让独立 Runtime 自动执行 GC，应单独接入调度和配置，并验证旧命名空间启用及 GC 资源与 guardian 的衔接，继续保留未知 B 的暂停边界。
