# A0/B0：Reader 启动证据与连接释放探针

本轮按 [A/B 计划](../../development/plans/2026-10-26-capture-admission-and-release.md)只新增测试设施；2026-10-26 启动，2026-10-27 收尾。生产恢复协议、旧持久化类型和 T/J/D 均未修改，没有提交或推送。**探针已完成限定验证，整个平台仍为 `evidence_incomplete`。**

上段及本文“尚未实现”均描述 A0/B0 冻结探针阶段。后续用户另行授权的生产准入改造和本地提交，见 [A1/A2 报告](../2026-10-27-capture-admission/README.md)；旧探针的版本、原始材料和结论不随新实现追溯改写。

结论不能简化为“heartbeat 开启后就绪”或“close 返回后资源已释放”：本轮分别观察 snapshot/MBean、真实 callback、原始 ACK、实际 D，以及本地终止和服务端会话。

## 1. 版本、环境与证据

- 工作区 HEAD `d7769786db4d1e85085fd244449e30a10831d5bf` 加本轮 Test 设施。最终冻结的全源摘要：`40b901fe17fbc02ca5b94c5c667cfa1ed9a28282957515e47db6338cea516667`。
- 生产摘要 `4953d46ef2fa32c4ecc588afd04c14a067aab024f6b0a218145ea8dc67ee55f4`，与上一轮启动探针一致；收尾逐项核对当前源码与最终候选一致，没有用未编译源文件冒充实际运行版本。
- Debezium 3.6.1.Final、Pekko 1.1.3、Persistence JDBC 1.1.1、Connector/J 8.0.33、JDK 17.0.14、MySQL 9.5.0。SDK 沿用直接连接和原 TLS 配置，本轮没有使用禁 TLS 的 SQL 代理。
- 两个独占物理槽并行：a 为 `14365/14366`，UUID 分别 `889e2d3f-765b-4456-a55e-a441a5ffc580` / `97907728-948c-47ee-9b0e-fa8fbc12b6e3`；b 为 `14375/14376`，UUID 分别 `561ae360-2c2c-4de9-bf01-3b913e7226a6` / `542fff04-a65b-4f5f-a911-90ff25211e3d`。每次 JVM 派发前 TCP 核对 UUID/port/datadir，worker 在任何 fixture 写入前再次核对 UUID。
- 同槽串行，前一个 JVM 退出且服务端连接/事务归零后才进入下一项。没有 KILL 数据库连接、重启 mysqld、purge 或操作旧 3306/13365/13366 服务。

[独立复核汇总](summary.json) · [最终候选](candidate-validated.json) · [最终源码](candidate-validated-sources.zip) · [最终原始材料](validated-evidence.zip) · [归档成员与 SHA-256](archive-index.json) · [清理确认](final-cleanup.json)

## 2. 七个实际组合

每项一个独立 JVM；首次启动与恢复复用同一合法命名空间时，原 D/history 保持不变。Shared Reader 组合与纯 SDK 组合**是两个验证层级**。

| 组合 | 实际观察 | 支持的结论 |
|---|---|---|
| Shared 首次，heartbeat=0 | 当前 MBean SnapshotCompleted；最后一条 Schema callback 为非 INITIAL；原 ACK 后实际 D 通过 L 引用链验证 | 这个样本关闭 heartbeat 也取得了完成态锚点，不支持“关闭必定不能就绪” |
| Shared 首次，heartbeat=1000 | Completed、原生 Heartbeat、当前 callback/ACK 与实际 D 匹配；随后真实 Sink/Journal witness 逐字段相等 | 当前组合的捕获证据和复制基线上的增量应用可成立 |
| Shared 恢复，heartbeat=1000 | 当前 MBean SnapshotSkipped；当前回调为事务/Schema，D 与当前 ACK 关联 | 本次恢复存在实际输入，不能当作完全无事件恢复 |
| Shared 恢复，heartbeat=0 | Skipped；回放了启动路径控制表 DDL 的 Schema callback，实际 D 与 ACK 匹配 | 不能由此推断关闭 heartbeat 的真正静默恢复一定就绪 |
| 纯 SDK 首次，heartbeat=1000 | Completed、Heartbeat、原始 SDK ACK 和实际 D | 支持 SDK 完成态信号取证；没有 L、Actor Journal 或 Sink 的证明 |
| 纯 SDK 恢复，heartbeat=0 | Skipped；完整 60 秒源状态不变、零 callback；仍能读到旧完成态 D | 旧 D/Skipped 单独不足以证明本 attempt 已有新的回调链；本次正确保留“没有新证据” |
| 纯 SDK 恢复，heartbeat=1000 | Skipped；首次原生 Heartbeat 在静默观察起点后 **48.618 秒**出现；ACK 后实际 D 与其匹配 | 配置 1000ms 不是 1 秒内准入的承诺；本例证据需要等待真实输入 |

所有观察窗口都保存完整源行和 `SHOW BINARY LOG STATUS` 的起止值，并独立比较相等，未通过写一条业务 witness 帮静默阶段“就绪”。Shared 的 `CREATE TABLE IF NOT EXISTS` 在 `capture.start()` 内、静默窗口起点之前执行；从旧 D 恢复时，窗口内仍可能回放这条 DDL，故“窗口内没有新源写入”与“窗口内没有回调输入”分别报告。

纯 SDK 对照不调用 SharedMySQLCapture.start，不生成这条控制 DDL，也不制造 SourceRecord 或伪 ACK。它直接使用真实 Engine/RecordCommitter，只观察 SDK 层；其 `SDK_D_OBSERVED` 与 Shared 的 `D_VERIFIED` 分开。**SDK 对照不能补标为共享 L 的端到端保证。**

### 源码解释与实验事实

固定版本的 `RelationalSnapshotChangeEventSource` 在 no_data 完成后尝试发最终 heartbeat；最终 Schema callback 的 offset、snapshot 完成与回调调度也存在时序关系。因此首次 heartbeat=0 样本成功不消除旧 INITIAL 反例。

本机 Debezium 3.6.1 sources.jar 中，`BinlogConnectorConfig.KEEP_ALIVE_INTERVAL_MS` 默认 60000ms；`BinlogStreamingChangeEventSource` 设置 server heartbeat 为 keepAliveInterval 的 0.8 倍，并由真实事件路径触发应用 heartbeat。48 秒的源码量级与本例 48.618 秒相符。这里只解释本版条件，未修改 keepalive 配置，也不承诺所有环境 48 秒内 ready。未运行针对网络帧、服务端线程和所有 flush 时序的因果矩阵。

## 3. 唯一一项真实 Journal/Sink witness

Shared 首次 heartbeat=1000 的静默取证结束后，才提交真实 B、启动持久 Actor，并修改源 `a.id=1` 的 label、amount、revision、note、updated_at。源/目标初始四行由 fixture 复制，是已知基线；**不是 LOW/HIGH 在线初始化实验**。

本次完整比较四行、六个字段，共 **24 个字段相等**，无缺失/额外主键。真实目标 T 有一个组账本，J 有四条事件，其中包含 `ExecutionCheckpointAdvanced`；raw J serializer id/manifest/payload、L、实际 D 及原始 ACK 顺序全部保存于 `validated-shared/first-heartbeat-1000/events.jsonl` 的 WITNESS 和前后事件。T 组数、J 事件数不是可比较的进度坐标。

其余六项没有各自运行完整 Sink/Journal 对账，不写成“七次端到端恢复一致”。

## 4. 归属与关闭：本地结束不等于服务器结束

最终七项均观察到：close 正常返回、实际 completion 成功、本地相关线程结束；**5 秒之后仍存在源端 Binlog Dump**。最终首次观察到两实例用户连接与事务归零的时刻如下。

| 组合 | LOCAL_END 后首次 idle 采样（秒） |
|---|---:|
| Shared 首次 heartbeat=0 | 86.848 |
| Shared 首次 heartbeat=1000 | 95.932 |
| Shared 恢复 heartbeat=1000 | 87.090 |
| Shared 恢复 heartbeat=0 | 87.296 |
| SDK 首次 heartbeat=1000 | 87.264 |
| SDK 恢复 heartbeat=0 | 85.065 |
| SDK 恢复 heartbeat=1000 | 84.562 |

这是**首次 idle 采样时间**，不是精确自然释放耗时；根因未确定。等待期间保留槽租约，不启动替代 JVM；没有主动清连接或把最终消失反推为 5 秒内已释放。因此旧“5 秒自然释放”指标本轮仍未通过。

两个槽的 performance_schema 均未提供可用线程/连接属性材料，探针显式记录开关，未将空结果解释为无连接。最终归属使用以下组合：

1. 租约内独占、启动前无其他用户会话，派发前与 worker 写入前核对两实例身份。
2. 父进程持有真实 Popen 对象；worker 自报 PID/JVM start time；只在该对象仍存活时观察它的 TCP socket。
3. 将 `lsof` 的本地端口/远端端口与服务端 PROCESSLIST HOST、connection id 对应，区分 Binlog Dump 和源 JDBC；应用源连接另外记录实际 connection id。
4. Shared 的状态库 owner 通过 `IS_USED_LOCK` 获取实际 connection id，再与同一活 JVM 的状态库 socket 对应。四个 Shared 组合均完成 dump、源 JDBC 和 owner 的该层映射；三项纯 SDK 不存在 Shared owner，按不适用报告。
5. 会话/事务采样、UUID/port/datadir/version 和 Uptime 连续保存；常见重启/观察失败不能当作空连接。Uptime 单调性不是对任意低 uptime 重启的严格证明，更不构成跨 owner fencing。

这些证据支持本轮专用槽中的连接归属和最终释放，不支持生产共享账号下任意连接识别、只凭 PID 接管、恶意 PID/connection id 复用或多 owner 隔离。

## 5. 测试设施、审查与保留失败

- [真实 SDK factory](../../../server/src/test/scala/cn/xuyinyin/magic/connectors/mysql/source/CaptureAdmissionProbeFactory.scala)只观察原记录、原 committer 和真实生命周期；记录 SDK completion 后仍调用原回调，由原 wrapper 决定 Future 结果，不代写 offset。
- [独立 JVM worker](../../../server/src/test/scala/cn/xuyinyin/magic/workflow/integration/CaptureAdmissionReleaseProbe.scala)检查实际 offset key 的唯一合法 partition，保存 MBean 与 complete/skip、运行结束结果。
- [runner](../../../scripts/experiments/capture-admission-release/run.py)复用既有 Pool 的身份/租约/候选冻结；[离线 verifier](../../../scripts/experiments/capture-admission-release/verify.py)按 QUIET_END 截断，并关联当前 callback → 原 ACK → 实读 D；旧 D、异 partition 或后续 witness 不能填补本次就绪证据。
- verifier 的负例由 RED 到 GREEN，最终 **19/19**；涵盖 ACK 冒充 D、旧 D、异 partition、异步 completion 失败、静默窗口污染、错误 PID、跨 source/state 同号连接和实例重启等检查。它们是取证工具测试，不是生产协议修复测试。
- Test compile 通过；已有 `DebeziumDependencySpec` 2/2。没有重跑完整后端、R01—R16、I01—I24 或生产 A/B 的 26 项故障矩阵。

独立只读审查发现并修订了探针的正例缺信号仍继续、历史 D 与本次证据混淆、init 身份检查顺序、completion 失败分类和 partition 关联等问题。旧 exploratory 原样保留，最终验收只使用最终冻结源树与新 verifier 复算，不覆盖旧结果。

第一轮四组合及中间候选、取证工具 RED 日志、构建记录、主动中止的两个中间 runner 和各自现场见 [探索材料](exploratory-evidence.zip)。中止仅针对本次新建的 runner/worker 进程组，为补全取证检查后重跑；数据库连接没有被 KILL，槽在重新确认 idle 后复用。

[凭据扫描](credential-scan.json)对文件及 ZIP 成员比对两槽实际秘密，零匹配，检查数量见原始结果。私有 token、密码文件、原始未脱敏日志和 datadir 不进入归档；旧 R02、B_BOUND+INITIAL 五字段遗漏及历史 C2 失败材料均未修改。

## 6. 下一步与未验证范围

可以据本轮证据继续实施启动前恢复分类和准入门：新的 attempt 不能借旧 Schema/旧完成态 D 开门；当前 MBean completed/skipped、真实 callback/ACK、唯一 partition 和实际 D 必须联合判断。真正静默恢复的等待条件不能直接使用 1000ms 配置值，也不能通过新增 B 来刺激就绪。

生产启动准入、INITIAL+旧 B 的安全暂停、有界 close、持久未知资源与配额恢复均**尚未实现**。本轮没有修复既有漏更新，也没有证明相关业务可自动恢复。下一步先做 A1/A2 的最小生产适配；B 的生产释放门需保留本地结束/服务器未知两种事实。

尚未覆盖：多次重复频率、TLS/网络差异矩阵、权限不足的真实观察实验、MySQL 重启/主机重启、PID 或连接 id 复用、SDK 中途异常/关闭永久阻塞的真实故障、任意并发新增/取消、完整 LOW/HIGH 初始化与故障矩阵、DDL、GC、新链路 HA、70 万真实表和长期容量。

### 复跑入口

先使用既有 cdc-lab-pool 取得独占租约并冻结已编译的候选；所有配置、token 和凭据留私有目录。新 runner 不自动取得/释放调用方租约，调用方须在最终真实连接/事务归零后释放。示意命令中的路径均须替换为该次新 attempt：

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/experiments/capture-admission-release -p 'test_*.py'
python3 scripts/experiments/capture-admission-release/run.py \
  --config /private/pool.json --lease /private/lease.json \
  --candidate /private/new-candidate/candidate.json --java /path/to/jdk17/bin/java \
  --suite all --output /private/new-shared-attempt
# 第二个物理独占槽可同时执行 --suite sdk；不能只换 schema/server-id 就并行。
```
