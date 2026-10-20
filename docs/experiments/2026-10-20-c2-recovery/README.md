# C2：提交结果不确定时的有界恢复

本轮采用用户批准的生产协议：完整组 MySQL CDC 在同一 execution 中最多自动恢复三次，次数持久化；耗尽、协议冲突、无法确认本地释放时，保留执行上下文暂停。没有提交、推送、更新网站。原来的 ExecutionPlan 边界未提交改动继续保留；本轮基线和最终源码摘要分别记录，不把 HEAD 当成全部已运行源码。

## 生产行为与边界

**源码结论：**Sink 不循环重试。完整组事务的 COMMIT 抛错被分类为 `commit_outcome_unknown`；真实 Debezium 适配器在删除异常敏感内容之前提取安全分类。Engine 保持 T→J→Source ACK，排空后关闭 Reader，再交由 Actor 决策。关闭的观测超时不会打开会话注册表的释放闸门，实际关闭成功才允许替代。

Actor 新增 `ExecutionRecoveryScheduled`、`ExecutionRecoveryAttemptStarted`、`ExecutionRecoveryBlocked`。首次运行不消耗自动恢复预算，后续最多三次，默认退避 1/2/4 秒；预算不因 Checkpoint 增长或进程重启清零。Scheduled 的绝对到期时间和 AttemptStarted 的次数都先持久化，再执行副作用。等待时重启恢复同一计时器；尝试已持久化后崩溃，该次预算不退还。耗尽后 `currentExecution`、边界、J 和主要故障描述仍在，状态为 `recovery_blocked`。

旧尝试的 Engine 结果、初始化和 Checkpoint 回调按随机 attempt token 隔离。内部迟到回调不能推进新尝试的 J。Source 物理恢复从实际 JDBC offset store 的 D 开始；J 用来延续交付序号，不能把 J 或 T 写成 D。重放由目标完整组账本在同一事务内仲裁：证据相同跳过 DML，缺失才应用，冲突暂停。实际数据库行锁处理尚未决议的旧目标事务；本地 Reader 关闭不证明旧服务端事务已回滚。

新增安全描述只持久化代码、阶段、交付批次 ID 和释放标志，不持久化 Throwable、SQL、凭据或业务行。暂停原因与主要失败分开：例如 `local_release_unconfirmed` 仍保留原来的 `commit_outcome_unknown` 和批次 ID。HTTP summary 暴露真实阶段和次数，不增加手工继续接口。

**兼容性：**新可选状态字段对旧快照使用默认值；本轮回归包括旧状态恢复。新增 Journal 事件后，旧二进制不保证能读新事件，不能承诺直接降级回滚。历史已写成 `ExecutionFailed` 且清除了上下文的 C2，不被本轮代码自动“复活”。

## 真实实验方法

真实 MySQL 业务表、Debezium Reader、JDBC Actor Journal 和事务 Sink。每场景独立随机 schema，只操作 loopback 隔离容器。先由旧初始化路径建立可信 64 行目标和 durable streaming D，再启用完整组；没有验证新的全量快照算法。

T 是目标效果及完整组账本，J 是真实 Journal 的交付 Checkpoint，D 是实际 offset 表中的完整 JSON。切点和最终都保存三者、业务审计和六字段全量数据。逐字段比较 `id/label/amount/note/updated_at/revision`，缺行、多行和字段差异分别计算；不只比较 count/hash。全量数据包含 DECIMAL、NULL/空串、中文/emoji、微秒时间、同事务重复更新及主键迁移。

测试设施只控制时序和传输：Sink 专用 MySQL TCP 代理截获真实 COMMIT/OK，原始 SourceRecord、完整组装配、目标 DML、Journal 和 SDK ACK 均调用真实实现。`R4` 在真实 J 已持久化后扣住回调结果，再以超时结束；不是伪造 Journal 成功。`R5` 延迟实际 Reader close。R6/R7 的破坏只发生在该项隔离 schema。

旧事务提交分支：客户端先断开，旧服务端事务保留锁；新尝试真实出现 `LOCK WAIT` 后，才把原 COMMIT 发给旧服务端，并检查服务器 OK。回滚分支：同样先保留锁并观察新事务等待，再关闭旧服务端连接；独立查询确认旧 InnoDB 事务已经消失。不能仅凭“已发送关闭”宣称数据库完成回滚。

正式矩阵包括：提交成功回复丢失、COMMIT 未转发而回滚、旧事务迟到提交/回滚、已提交与未提交组混批、拆批、主键 DELETE/CREATE 跨原始回调；以及等待/启动后崩溃、预算耗尽、J 回复丢失与旧回调、关闭超时、证据冲突、账本缺主键、D 丢失。成功与安全暂停分别验收。

## 独立复核

[verify.py](verify.py) 只读证据包，不连接数据库，不重新运行应用：

- 从原始 SourceRecord 的物理 file/pos/row、复制 namespace 和角色重算 group/event identity，检查重放成员稳定性及目标账本证据。
- 从原始 Struct before/after 推导审计逐行内容和源顺序，与真实触发器审计比较，识别重复副作用。
- 按每个物理 Reader 检查原始 ACK 连续前缀、真实 J 确认先于 Source ACK、原始 ACK 没有跨过未确认组。
- 检查旧 Reader 真实 close 返回早于替代 Reader 创建；关闭超时的负例没有创建替代 Reader。
- 从完整源/目标行数据重算全部字段差异；检查同 execution 和三次累计预算。

`SAFE_BLOCK` 只说明安全暂停、保留证据及上下文，不能算同步恢复成功；其最终逐字段差异照实保留。

## 正式结果：2026-10-20 至 2026-10-21

正式 24 项断言全部通过（3 套件、0 中止）：新恢复矩阵 15 项中 **10 项恢复收敛、5 项安全暂停**；历史七窗口全部重新收敛；两项取消/释放回归通过。这不是“24 项自动恢复成功”。

[新矩阵结果](automatic-result.json)、[历史回归与释放结果](regression-result.json)、[环境与运行源码](run-manifest.json)、[证据包](evidence.tar.gz)。证据包保存全部 server 源码/资源、改造前源码、所有运行源码哈希、24 个库的 SQL dump、原始回调/ACK、真实序列化 Journal、切点/最终 T/J/D 和完整六字段数据。HEAD 只是基线，实际运行还包含此前未提交的 ExecutionPlan 改动及本轮 C2 代码。

| 场景 | 切点可见 T 组 / J.sequence | 自动恢复次数 | 实际结果 | 最终缺行 / 多行 / 字段差异 |
|---|---:|---:|---|---|
| AUTO-A | 17 / 0 | 1 | PASS | 0 / 0 / 0 |
| AUTO-B | 1 / 0 | 1 | PASS | 0 / 0 / 0 |
| LATE_COMMIT | 1 / 0 | 1 | PASS | 0 / 0 / 0 |
| LATE_ROLLBACK | 1 / 0 | 1 | PASS | 0 / 0 / 0 |
| AUTO-D | 2 / 0 | 1 | PASS | 0 / 0 / 0 |
| AUTO-E | 17 / 0 | 1 | PASS | 0 / 0 / 0 |
| AUTO-F | 2 / 0 | 1 | PASS | 0 / 0 / 0 |
| R1 | 17 / 0 | 1 | PASS | 0 / 0 / 0 |
| R2 | 17 / 0 | 2 | PASS | 0 / 0 / 0 |
| R3 | 17 / 0 | 3 | SAFE_BLOCK | 1 / 1 / 214 |
| R4 | 17 / 1 | 1 | PASS | 0 / 0 / 0 |
| R5 | 17 / 0 | 0 | SAFE_BLOCK | 1 / 1 / 214 |
| R6 | 17 / 0 | 1 | SAFE_BLOCK | 1 / 1 / 214 |
| R7-PK | 17 / 0 | 1 | SAFE_BLOCK | 1 / 1 / 214 |
| R7-D | 17 / 0 | 1 | SAFE_BLOCK | 1 / 1 / 214 |

全部成功项源/目标各 64 行，384 个字段值逐一相同。普通新矩阵最终审计 137 条，主键跨回调场景 69 条；内容与原始 SourceRecord 推导的源顺序相同，不能仅由审计 count 推断。五项暂停仍保留 `150` 缺失、`50` 多余和 214 个共同键字段差异，故明确不计收敛。切点 D 均保持基线；R7-D 随后主动删除 D，最终仍为空，未隐式重新快照。

AUTO-D 实际出现旧已提交组与新组混在同一恢复交付（Reader 1→max128）；AUTO-E 为 16→1 拆批。AUTO-F 在原始回调 DELETE/CREATE 分开时完成整组并重放。R2 用掉第一次已持久化的尝试后崩溃，恢复计数实际为 2；R3 实际开始三次替代，耗尽后跨进程仍暂停。R5 因本地关闭无法确认，在任何替代尝试前暂停；后来实际关闭成功也没有自行解除暂停。

历史七窗口的最终缺行、多行、字段差异全部 0；普通场景审计 140 条，K1/K2 为 72 条。取消控制和 T 已提交但 J 尚未请求的取消场景各 64 行全部字段一致，审计分别 66/67 条，实际 Reader 线程数降到 0；T 门未释放时只观察到 TimedOut，实际释放后才返回 Released。

| 本轮检查 | 真实结果与范围 |
|---|---|
| 默认后端 `test` | 481 项断言通过、0 失败，但旧 ActorSpec 百万 Probe 示例在 10 秒清理等待内未结束，1 套件中止；命令失败，不记全绿 |
| 后端 `test`，仅 JVM 测试清理等待改为 60 秒 | 481/481、79 套件、0 中止；没有修改仓库默认等待配置 |
| `package` 与主 JAR 检查 | 成功；包含新恢复类，不包含真实 MySQL 测试设施 |
| 显式真实 MySQL Runner | 24/24；默认 test 排除 ExternalIntegration，不能代替此项 |
| 两个离线 verifier | 均 PASS；新 15 项、旧七窗口、两释放项分别核验 |
| 旧实验材料 | 120 个原有文件 SHA-256 全部不变，详见 historical-artifact-check.json |

正式新矩阵测试退避为 200/400/800 ms；R1 为固定崩溃窗口使用 10/20/40 s，恢复 JVM读取已持久化的绝对到期时间；生产默认仍为 1/2/4 s。R5 close 观测预算 2 s，其他真实进程 10 s。测试缩放没有扩大三次额度，不将这些耗时当生产性能。

复核还保留三类探索失败：首轮状态/事件消费观测竞态；旧事务回滚的初版代理转发未取得确认、以及 R1 两秒窗口被取证越过；补充 SQL 导出未显式指定字符集。最终回滚门改成真实断开旧服务端连接并查 InnoDB 事务消失，R1 窗口加长；导出指定 utf8mb4 后再次与 JDBC 审计逐行相等。CLI 默认 latin1 的诊断及错误导出均在 exploratory，原始 JDBC 全字段结果和原始 mysqldump（SET NAMES utf8mb4）不受该导出问题影响。探索失败不混入正式通过次数。

复现需要独立 loopback MySQL 与 `CDC_WINDOW_MYSQL_HOST/PORT/USER/PASSWORD` 环境变量，启用 ROW/FULL 与 GTID。Java 17，`pekko-server/Test/compile` 后，通过 ScalaTest Runner 显式选择 `RealMySQLCdcAutomaticRecoverySpec`、`RealMySQLCdcRecoveryWindowsSpec`、`RealMySQLCdcSessionReleaseSpec` 并设置 `RUN_MYSQL_CDC_RECOVERY_WINDOWS=1`。每项会新建专用随机 schema；不要指向业务实例。证据包中的正式运行日志、全源码和 run-manifest 保留实际命令参数与版本，凭据不打包。

```sh
PYTHONDONTWRITEBYTECODE=1 python3 docs/experiments/2026-10-20-c2-recovery/verify.py
PYTHONDONTWRITEBYTECODE=1 python3 docs/experiments/2026-10-20-c2-recovery/verify-regressions.py
```

## 历史失败与未验证范围

旧 C2 的真实失败保留在 [2026-10-19-recovery-windows](../2026-10-19-recovery-windows/README.md)、[stage-c-sessions](../2026-10-19-stage-c-sessions/README.md) 和 [execution-plan-boundary](../2026-10-20-execution-plan-boundary/README.md)。旧文件摘要逐个核对。本轮把活动测试中的 C2 改为新协议的恢复验收，旧失败不是被改写为成功。

未验证：生产环境、长时间网络分区、大规模持续压力、70 万表、跨 owner、共享 Reader、DDL、新快照切换、目标/Journal/MySQL 服务端掉电、跨机房和 binlog 清理/重置。单 owner 固定 Schema 的完整组恢复结果不能扩展为一般端到端 exactly-once 或整个项目达到 Flink CDC 同等成熟度。暂停状态目前没有人工审核后的继续/放弃 API；恢复预算也没有自动补充。另一个已知分类限制是：Actor 明确拒绝 Checkpoint 后，当前适配层仍保守归入 `progress_outcome_unknown`，可能消耗三次预算才暂停，而不是立即报告协议冲突；J 和后续 ACK 不会因拒绝推进。

实验设施的 COM_QUERY 代理只用于无 TLS/压缩的本地 Sink 连接，不能用于生产代理。默认生产退避与故障测试用的短退避分开记录；实验耗时不作为吞吐或恢复延迟指标。
