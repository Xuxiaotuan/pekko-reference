# C 阶段：实例级节点目录与执行会话释放

日期：2026-10-19。

本轮将节点目录与执行会话分开，解决 CDC 空闲读取占用 JDBC worker，以及流结束先于异步资源关闭的问题。没有新增微批策略，没有修改完整组、事件身份、目标组账本和 T/J/D 恢复协议。

最终聚焦验证为 **156 项通过**；全模块为 **440 项通过、0 项失败、两个既有 Calcite 套件中止**，完整测试门仍为 `evidence_incomplete`。真实八窗口保持七项收敛、C2 自动恢复失败；新增两个真实取消场景均确认释放并完成全量逐字段对账。

## 1. 源码版本和实验环境

- 基础 HEAD：`dca7d25cfcf7747d7d08231259c3353331755b84`。工作区已有 A/B 和恢复探针的未提交改动，本轮没有提交、推送或发布。
- [源码清单](source-manifest.json)包含最终冻结源码的 SHA256；归档 `source/` 保留具体文件。不能用基础 HEAD 代替此次实际运行源码。
- Java 17.0.14、Pekko 1.1.3、Debezium 3.6.1.Final；真实数据库为 MySQL 8.4.11，ROW/FULL、GTID ON。
- 新建隔离容器 `pekko-cdc-stage-c-20261019`，仅映射 loopback 临时端口。每个正式场景使用独立随机 schema。Source、目标业务表/组账本/审计、Actor Journal 和 offset/history 均为真实 MySQL 存储。
- [运行清单](run-manifest.json)记录正式十项、源码冻结和镜像信息。两个早期十项轮次归入 `exploratory/`，不累加覆盖数量。

真实测试通过 Test Classpath 运行生产 Source、Sink、Engine 和 Actor 类；生产 JAR 检查属于另一层证据，不算部署该 JAR 后的集群端到端验证。

## 2. 生产实现改变了什么

| 部分 | 本轮实现 | 保持的边界 |
|---|---|---|
| 节点目录 | `NodeRegistry` 变为不可变实例，内置工厂创建独立目录；Engine、Validator、Executor 和 Actor 判断使用同一目录 | 适配器实例在目录内稳定；不会每次 lookup 新建 Source，ACK 注册表仍由原适配器持有 |
| 测试装配 | 测试用各自的目录快照，移除进程级注册/覆盖 | `TestNodeRegistry` 仅是测试 fixture builder，不进入生产 JAR |
| 空闲读取 | Bridge 增加 `takeAsync`；没有数据时挂起 Promise | 不返回 EOF，不占 JDBC worker；保留容量、交付 token、ACK 认领及原始前缀规则 |
| Source 会话 | `BatchSourceSession` 和 `SourceResourceScope` 跟踪获取、迟到资源和关闭完成 | 会话信息不进入事件身份、cursor、Checkpoint 或 Journal |
| 取消排空 | 停止新批次接纳；已接纳批次结束后才关闭所需资源 | 仍执行目标提交 T → J 持久确认 → Bridge 放行；不在链中插入取消即跳过确认的捷径 |
| 读取/关闭 | Snapshot 等待已接纳 JDBC read 结束；Kafka 在 poller 所在线程关闭并报告完成；CDC 关闭唤醒空闲读 | CDC 不等待无数据读取自然完成；已认领的原始 ACK 不重复调用 |
| 释放确认 | `cancel` 等实际释放；`cancelWithin` 给出 `Released/TimedOut/CloseFailed`，默认观察预算 10 秒 | 超时不代表物理停止；释放未确认时，同一 Engine/executionId 的替代会话不能启动 |
| 关闭异常 | 已获取资源的关闭异常、部分获取后的清理异常保留为未确认释放；关闭错误也经过运行期密码脱敏 | 不将关闭异常或观察超时转成提交成功，不修复 C2 失败终态 |

关闭使用独立的固定两线程 dispatcher，避免排在 JDBC 工作之后。它不保证任意驱动的 `close()` 能按时返回，也没有为每个取消等待再创建线程。

源码入口：[NodeRegistry](../../../pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/registry/NodeRegistry.scala)、[Engine](../../../pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/engine/WorkflowExecutionEngine.scala)、[会话资源](../../../pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/base/BatchSourceSession.scala)、[Bridge](../../../pekko-server/src/main/scala/cn/xuyinyin/magic/workflow/nodes/sources/DebeziumBatchBridge.scala)。

逐文件对比确认：`MySQLCdcGroupAssembler`、`MySQLCdcApplySinkNode` 与 Engine 的 `processBatch` 主体和本轮前工作区一致。本轮没有新增按源事务 BEGIN/END 聚合的保证。

## 3. 固定窗口测试支持哪些结论

以下是本地测试证据，不能当作真实 MySQL 故障运行：

| 窗口 | 实际检查 |
|---|---|
| 目录隔离 | 两份目录的同名替换、稳定适配器身份、Validator 决策和 Actor 可靠能力路由 |
| 空闲读取 | 16 个实际 `MySQLCdcSourceNode`、单 JDBC worker，使用假 SDK/元数据；全部建立空闲读取后 worker 仍可执行探针，取消后会话退出 |
| 异步 Bridge | 已排队交付在前一个 ACK 结束后被唤醒；关闭释放挂起读取 |
| T/J 排空 | 分别挂起 T 结果、J 确认和关闭；未放行前返回等待超时，不提前关闭，不提前 ACK |
| 原始 ACK | 实际 Assembler/Bridge/Engine，假 SDK 句柄被扣住时取消保持未释放；放行后才结束 |
| 主键半组 | DELETE 已保留、CREATE 未到达时取消；无目标提交、无补发 ACK，并释放会话 |
| 迟到和重入 | 迟到旧 ACK、重复取消、安装前取消、旧会话未释放时同 ID 接管、迟到获取资源 |
| 失败清理 | Snapshot 关闭抛错、获取后元数据拒绝且清理抛错、CDC 同步启动/清理双失败、Kafka 部分获取清理失败，均不伪装释放 |
| 初始化取消 | boundary discovery 在取消后返回，不再调用 initializeBoundary 或打开 Source |
| 信息边界 | 新增异步关闭失败路径不向执行结果泄露运行期密码 |

另外，两节点 H2 恢复测试仍验证同一 executionId、首批 `committed → already_committed`、三个唯一账本记录和目标副作用一次。该 fixture 现在是**停止 Actor owner、使人工 J 闸门失败、等待真实 Engine 排空，再终止节点**，不冒充强制进程故障实验。

## 4. 新增真实 MySQL 取消实验

这两个场景运行真实 Reader、组协议、目标 Sink 和 JDBC Actor Journal。唯一的额外暂停是已成功提交 T 后、结果返回 Engine 前的测试闸门。

| 场景 | 固定边界与观测 | 全量对账 |
|---|---|---|
| 无新增源写入时取消 | 基线 T/J/D 追平后取消；关闭成功、`Released`，命名 Debezium runner 存活数为 0 | 源/目标各 64 行，缺行/多行/字段差异均为 0 |
| T 已提交、J 尚未推进时取消 | 数据库确认 T 已改变、J 仍为基线；取消返回 `TimedOut`，Engine 尚未关闭；放行后 J 推进，按既有 ACK 链路收尾，再确认 `Released`、runner 为 0 | 源/目标各 64 行，缺行/多行/字段差异均为 0 |

完整比较 `id/label/amount/note/updated_at/revision`，不是只比 count/hash。[结构化结果](session-result.json)由独立离线核验生成。归档 `sessions/` 保留基线、切点、取消回复、最终行、T/J/D、原始回调与 ACK 顺序，以及数据库导出。

runner 为 0 只表示此次命名 runner 的退出观测，不代表对 SDK 所有内部资源做过规模泄漏审计。Journal 服务级连接由 ActorSystem 管理。

## 5. 真实八窗口回归

| 场景 | 固定故障窗口 | 缺行 / 多行 / 共同主键字段差异 | 自动恢复 |
|---|---|---|---|
| N0 | 正常追平后硬退出 | 0 / 0 / 0 | PASS |
| J1 | J 已持久化，Source ACK 尚未调用；16→1 重分批 | 0 / 0 / 0 | PASS |
| J2 | 原始 SDK ACK 返回，真实 offset 写被锁住、flush 失败；1→最大128 | 0 / 0 / 0 | PASS |
| C1 | MySQL 返回 COMMIT OK，代理扣住回复，硬退出 | 0 / 0 / 0 | PASS |
| C2 | 同样真实提交，但丢失回复后让异常进入 Actor | **2 / 2 / 212** | **FAIL_TERMINAL** |
| C0 | COMMIT 未转发、目标回滚的负对照 | 0 / 0 / 0 | PASS |
| K1 | 主键 d/c 跨回调，ACK A 返回而 B 未开始，实际 D 已推进后崩溃 | 0 / 0 / 0 | PASS |
| K2 | 同上，但 D 写被阻塞并 flush 失败 | 0 / 0 / 0 | PASS |

七项成功场景均逐字段比较源/目标各 64 行；原始组成员、SDK ACK 连续前缀和审计内容/顺序另行核对，防止最终行相同掩盖重复副作用。J2 首次恢复实际交付 72 组，K2 为 4 组，说明没有将配置上限直接当成实际交付大小。

C2 最终 T=17 组、J.sequence=0、审计 82 条；恢复没有启动 Reader。源/目标行数虽然均为 64，实际缺键 `150,65`，多键 `50,64`，共同主键字段差异 212。**保留切点已提交效果和 D 未越界，不等于自动恢复成功。** 本轮没有清 Journal、重置 D、改 generation 或手工补数据。

[八项结构化结果](result.json)、[校验与构建结果](validation.json)、[仅 C 阶段差异](stage-c.diff)与[原始材料](evidence.tar.gz)。归档包含正式十项、早期观察、完整源码、运行日志和数据库导出；内部有逐文件哈希。

## 6. 仍不能推出的保证

- C2 自动恢复失败是已确认问题，不改写为未知；本轮不改变失败终态策略。
- 有界的是控制面一次等待。驱动永久阻塞、关闭池耗尽或外部 Future 不结束时，可以持续未释放；不得因此启动同 ID 替代 Reader。
- 接管闸门仅在同一 Engine/executionId 内成立，不是跨进程或不同 executionId 的 fencing。DDL、多 owner、共享读取不在本轮。
- 原始 SDK ACK 被扣住和主键半组取消由固定窗口单测覆盖；没有扩大成所有真实 MySQL 网络故障和关闭窗口都已验证。
- 直接终止整个 ActorSystem、Journal 插件写失败、磁盘故障、任意网络分区、长期恢复、所有 MySQL 类型/时区及 SDK 全部内部资源，仍未全面验证。
- 自定义可靠 Source 若有异步资源，必须提供 `openBatches` 的关闭完成信号；默认适配不能替代任意扩展节点的释放证明。
- 没有吞吐、延迟、完整 JVM 内存或 70 万表容量结果；每个 Debezium Engine 的独立 runner 仍存在。

## 7. 如何复核

离线核验不需要数据库：

```sh
cd docs/experiments/2026-10-19-stage-c-sessions
shasum -a 256 -c SHA256SUMS
python3 -B verify.py
python3 -B verify_tests.py
python3 -B verify_sessions.py
python3 -B verify_sessions_tests.py
```

八窗口核验及 5 项拒绝篡改检查通过；新增会话核验及 3 项拒绝错误材料检查通过。后者检查提前声称 Released、runner 仍存活，以及行数不变但字段被改写。

真实复跑须使用新的隔离 MySQL，按环境设置 `RUN_MYSQL_CDC_RECOVERY_WINDOWS=1` 和 `CDC_WINDOW_MYSQL_HOST/PORT/USER/PASSWORD`。先运行 `project pekko-server` 的 `Test / compile`、`export Test / fullClasspath`，再用 Java 17 的 ScalaTest Runner 显式执行：

```sh
java -Duser.timezone=UTC -Xmx1G \
  -cp "$(cat pekko-server/target/streams/test/fullClasspath/_global/streams/export)" \
  org.scalatest.tools.Runner -o \
  -s cn.xuyinyin.magic.workflow.integration.RealMySQLCdcSessionReleaseSpec \
  -s cn.xuyinyin.magic.workflow.integration.RealMySQLCdcRecoveryWindowsSpec
```

默认 SBT test 排除 ExternalIntegration，不能把没有执行真实测试当作通过。复跑会创建随机测试 schema 并保留材料；不要指向业务实例。

## 附录：检查过程与证据边界

正式记录以 `checks/final-validation.log`、`checks/final-real-ten.log` 为准。早期全模块曾受并发重编译测试类污染，出现 NoSuchMethodError，该轮无效；之后串行运行。目录迁移遗漏继承式 Engine 构造造成的 H2 恢复失败也已修正并重跑，未归因于既有基线。

最终两个中止分别为 `MysqlSqlParserSpec` 缺少 `GeneratedMessageV3`，以及 `ComprehensiveCalciteTest` 无法初始化 `ConnectionPropertiesImpl`。它们没有被排除或顺手修复。生产 JAR 构建和新增会话类存在性检查通过，测试注册表不在 JAR 中；这不等于真实作业使用该 JAR 完成过部署验证。

69 份既有实验文件的 SHA256 与本轮前一致。C 阶段差异之外的 A/B 改动保留，所有代码和材料均未暂存、提交或推送。
