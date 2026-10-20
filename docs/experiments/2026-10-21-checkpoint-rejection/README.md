# Checkpoint 明确拒绝：立即暂停，不增加恢复预算

本轮只修正拒绝分类及传播，保留此前 ExecutionPlan/C2 的未提交改动。没有暂存、提交、推送或发布。旧实验材料（包括旧 C2 失败）逐个 SHA-256 核对，未改写历史结果。

## 源码结论

此前有两处丢失分类：Actor 将真实 `CheckpointRejected` 变为普通异常，Engine 又将 J/ACK 回调异常统一包成 `progress_outcome_unknown`，最终错误地安排恢复。

本轮两个生产文件的变化：

- `EventSourcedWorkflowActor.reliableRunContext`：真实 `CheckpointRejected` 转为已有 `CdcExecutionException(protocol_conflict)`。初始化阶段标为 `initialization`，交付 Checkpoint 标为 `journal` 并保留批次 ID。`CheckpointAccepted`、`CheckpointAlreadyStored` 和初始化已完成回复继续成功。
- `WorkflowExecutionEngine`：J 与 ACK 边界保留已有类型化失败；非类型化异常仍归入 `progress_outcome_unknown`，不会依据异常文案推断明确拒绝。

已有 Controller 在实际本地释放确认后，将非重试类失败直接持久化为 Blocked；不写新的 Scheduled/AttemptStarted，不增加预算。若已经使用一次恢复，则保持一次；不会退款或清零。currentExecution、executionId、workflowRevision、快照边界、最后 J 和故障描述保留，跨重启仍暂停。

T→J→Source ACK 的先后次序、目标组账本、原始回调前缀确认和最大三次恢复均不变。明确拒绝发生在 J 时，该交付不会调用后续 Source ACK；已提交 T 不回滚，不把 J 或 T 写成 D。若实际本地释放也未确认，原有释放闸门仍优先保守暂停，并保留主要失败。

这里的“结果不确定才重试”针对 Checkpoint 回复分类；原有已批准的暂态基础设施、execution 中断和 COMMIT 不确定恢复规则没有扩展或删除。没有新增持久化事件/状态字段或人工继续接口。

## 新真实实验：固定 T 提交后、J 拒绝前的窗口

使用真实 MySQL Source、Debezium Reader、事务 Sink 和 JDBC Actor Journal。先建立可信 64 行初始化基线及持久化流式 D。测试设施在真实目标事务提交后扣住 J 请求，再将 Checkpoint 的交付序号加一并重算合法 batchId，调用真实 Actor 得到“sequence has a gap”拒绝。设施只制造合法身份但不连续的协议请求，没有伪造 Actor 回复、Journal 写入、D 或业务数据。

- **REJECT-J**：初次执行直接拒绝。T 已提交，J 尚未确认，恢复预算必须保持 0。
- **REJECT-J-RETRY**：先截获真实 COMMIT 成功回复，引发一次结果不确定恢复；新 Reader 实际重放后，再触发真实 J 拒绝。预算保持 1，不得再启动第二次恢复。

两项都在闸门处保存 T/J/D；拒绝后再取证，硬杀进程并以新 JVM 从真实 Journal 重启，检查上下文和预算保留、无新 Reader、无 J/D 推进。原始 SourceRecord、完整组、确认顺序、Journal 序列化 payload、T 账本、触发器审计及全部源/目标字段均保留。

这两项证明真实拒绝的传播与持久化暂停路径，不表示生产系统会自然生成跳号 Checkpoint，也不替代其他拒绝原因的真实实验。初始化拒绝和 AlreadyStored 边界另由 Actor 测试覆盖。

## 正式结果

真实 MySQL 矩阵 **26/26 断言通过，3 套件、0 中止**：本轮矩阵 17 项中 **10 项自动收敛、7 项安全暂停**；原七恢复窗口全部收敛；两项取消/资源释放回归通过。安全暂停不计为同步成功。

| 场景 | 累计恢复次数 | 结果 | 最终 T 组 / J.sequence | 最终缺行 / 多行 / 字段差异 |
|---|---:|---|---:|---:|
| AUTO-A | 1 | PASS | 71 / 6 | 0 / 0 / 0 |
| AUTO-B | 1 | PASS | 71 / 70 | 0 / 0 / 0 |
| LATE_COMMIT | 1 | PASS | 71 / 70 | 0 / 0 / 0 |
| LATE_ROLLBACK | 1 | PASS | 71 / 70 | 0 / 0 / 0 |
| AUTO-D | 1 | PASS | 71 / 2 | 0 / 0 / 0 |
| AUTO-E | 1 | PASS | 71 / 70 | 0 / 0 / 0 |
| AUTO-F | 1 | PASS | 3 / 2 | 0 / 0 / 0 |
| R1 | 1 | PASS | 71 / 70 | 0 / 0 / 0 |
| R2 | 2 | PASS | 71 / 70 | 0 / 0 / 0 |
| R3 | 3 | SAFE_BLOCK | 17 / 0 | 1 / 1 / 214 |
| R4 | 1 | PASS | 71 / 71 | 0 / 0 / 0 |
| R5 | 0 | SAFE_BLOCK | 17 / 0 | 1 / 1 / 214 |
| R6 | 1 | SAFE_BLOCK | 17 / 0 | 1 / 1 / 214 |
| R7-PK | 1 | SAFE_BLOCK | 17 / 0 | 1 / 1 / 214 |
| R7-D | 1 | SAFE_BLOCK | 17 / 0 | 1 / 1 / 214 |
| REJECT-J | 0 | SAFE_BLOCK | 17 / 0 | 1 / 1 / 214 |
| REJECT-J-RETRY | 1 | SAFE_BLOCK | 17 / 0 | 1 / 1 / 214 |

两个新增拒绝场景：最终 T 17 组、审计 82 条，J 仍为基线 sequence 0，D 与基线完全相同；预算分别为 0 和 1。没有拒绝后的 Source ACK、额外恢复启动或重启后的 Reader。executionId、revision 和边界保持一致。两项最终仍缺 `150`、多 `50`，共同键字段差异 214，故明确记为 SAFE_BLOCK，不能写成恢复收敛。

原 R4（真实 J 持久化但回复丢失）仍以一次恢复收敛，说明未知结果没有被当作明确拒绝。所有成功同步项源/目标各 64 行，对 `id/label/amount/note/updated_at/revision` 六字段逐一比较，缺行、多行、字段差异均为 0。DECIMAL、NULL/空串、中文/emoji、微秒时间、同事务多次更新、主键迁移均在原始材料中。

原七窗口 N0/J1/J2/C1/C0/K1/K2 的 T/J/D、审计和全字段断言重新执行；两项取消回归确认实际 Reader 释放、liveRunners=0，T 门未放开前只返回 TimedOut，实际释放后才返回 Released。

| 检查 | 本轮实际结果 |
|---|---|
| 定向红绿 | 先观察 47 项中 4 项失败；修复后 47/47 |
| 完整后端 test | 488/488，79 套件、0 中止 |
| package / JAR | 成功；含生产类型化失败类，不含真实实验设施 |
| 显式真实 MySQL Runner | 26/26；与默认排除 ExternalIntegration 的后端 test 分开 |
| 独立离线复核 | 两个 verifier 均 PASS，重新计算全部字段、审计和 ACK 顺序 |
| 历史实验 | 133 个原有文件 SHA-256 全部不变，包括此前 C2 失败证据 |

完整后端命令仅在测试 JVM 指定 `-Dpekko.actor.testkit.typed.system-shutdown-default=60s`；仓库默认清理等待仍为 10 秒，本轮没有重新验证默认等待下旧百万 Probe 示例的清理结果。不能把此次 60 秒参数结果写成默认命令已验证通过。

测试退避和本地关闭观察预算沿用前轮设施；不变更最大三次额度，不以测试耗时表示生产性能。新增两项的预跑通过与选择器范围错误中止记录在 exploratory，仅正式 26 项计入结果。

## 证据与复核

[自动恢复和暂停结果](automatic-result.json)、[七窗口及释放回归](regression-result.json)、[运行版本](run-manifest.json)、[源码审查](review.md)、[证据包](evidence.tar.gz)、[历史文件核对](historical-artifact-check.json)、[JAR 检查](package-check.json)、[最终检查](final-check.json)、[清理状态](cleanup.json)。

证据包保存 26 个实验库的 UTF-8 SQL dump、独立 SQL 审计导出、完整运行日志、切点/最终 JSON、原始回调和 ACK、全量字段、完整 server 源码/资源及本轮前源码。run-source-manifest 与最终全源码摘要一致；change-manifest 只记本轮两个生产文件和四个测试文件。基线 HEAD 为 `d046cce2dc850ea60bc3513db84378484b0a1043`，实际运行还包含此前未提交改动，不能只 checkout HEAD 复现。实际依赖版本、Java/MySQL 与 loopback 环境见 run-manifest；不打包凭据。

`verify.py` 只读压缩包，从原始 SourceRecord 重新推导 group/event identity、成员证据和审计顺序；检查真实 J 确认先于 Source ACK、原始 ACK 是连续前缀且不跨未确认组、旧 Reader 释放早于替代创建；重新计算全部字段差异。新增拒绝项另检查实际拒绝事件、预算 0/1、J/D 不动和跨 JVM 暂停。`verify-regressions.py` 复用不可变历史核验器重算七窗口，并核对两项释放的原始记录及独立 SQL 审计。离线核验支持材料内部一致性，不代替重新运行数据库实验。

```sh
PYTHONDONTWRITEBYTECODE=1 python3 docs/experiments/2026-10-21-checkpoint-rejection/verify.py
PYTHONDONTWRITEBYTECODE=1 python3 docs/experiments/2026-10-21-checkpoint-rejection/verify-regressions.py
```

重新运行需要独立 loopback MySQL，ROW/FULL、GTID ON；Java 17，编译 test 后显式运行 run-manifest 列出的三个 ScalaTest 套件，设置 `RUN_MYSQL_CDC_RECOVERY_WINDOWS=1` 及 `CDC_WINDOW_MYSQL_HOST/PORT/USER/PASSWORD`。每项新建随机专用 schema；凭据只从环境读取。导出显式使用 utf8mb4，并与 JDBC 捕获的审计逐行对比。

## 未验证边界

没有验证 DDL、多 owner、共享 Reader、新快照算法、70 万表或生产长期压力；没有验证服务端掉电、跨机房、长时间网络分区、binlog 清理/重置。真实拒绝只注入序号不连续；其他拒绝原因由源码分析或单测支持，不宣称全部有真实 MySQL 证明。

暂停后的数据仍不一致，尚无人工审核后继续/放弃 API；本轮没有修这个相邻问题。既有 C2 持久化事件的旧二进制降级限制、历史已终态 C2 不自动复活等边界维持。单 owner 固定 Schema 的证据不能扩展为一般 exactly-once，或项目已达到 Flink CDC 的完整成熟度。
