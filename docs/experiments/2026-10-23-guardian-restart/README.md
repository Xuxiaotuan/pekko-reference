# supervisor 整个 JVM 重启：guardian 测试探针

本轮只增加 `server/src/test` 下的实验入口和本目录材料，不修改生产恢复协议、生产资源或依赖，不提交、不推送。基线为 `afff7ab27560b74ad026aa370dba1cf920fdc587`，精确测试设施版本见 [source-manifest.json](source-manifest.json) 和冻结 runner 的 source.zip。不能把基线 SHA 当作新增探针的版本。

## 实际结果

最终冻结运行 `matrix-1792740757878036000`：10/10 达到各自预期，其中 **8 个完成恢复，2 个保持安全暂停**。不是十个全部恢复成功。首次 S1 先导单独保留，不混入最终计数。

| 场景 | 实际固定窗口与结果 | 最终 Source/Target 对账 |
|---|---|---|
| S1_READ | 真实页已读、未交给目标；杀 A 后 guardian/worker 仍活，B 认证重建身份、隔离后恢复 | 10/10行，六字段一致 |
| S2_PREPARED | SQL 启动意图已提交、尚未发送创建请求；B 确认关闭未启动意图，之后拒绝同 boot 的迟到启动 | 10/10，六字段一致 |
| S2_FORK_PENDING | guardian 收到创建请求、尚未 fork；杀 A 后放行，真实孤立启动由 B 找回并隔离 | 10/10，六字段一致 |
| S3_ACK_LOST | 进程已创建并到达 Source 门闩，但 guardian 尚未回复 A；杀 A，B 找回原进程而非再启动一个旧 boot | 10/10，六字段一致 |
| S4_PRE_BOOTSTRAP | worker JVM 已启动，停在生产 bootstrap 之前；原 owner 尚不存在；确认退出后写 startup_failed，再启动后继 worker | 10/10，六字段一致 |
| S13_LOCK_CONTENDER | A 活着时启动 C；C 因本机文件锁被拒，SQL epoch 与 T/J 未变；杀 A 后 B 再恢复 | 10/10，六字段一致 |
| S13_SIGSTOP | 精确暂停本轮创建的 A，C 仍不能取得锁；不按超时抢占；恢复 A 信号后杀 A，再运行 B | 10/10，六字段一致 |
| S14_AUTH_IDENTITY | 实际重放已接受的 BIND nonce、错误认证 STOP、真实连接中的错误公钥 pin、错误 PID/start 身份及旧 epoch 请求均被拒；无关测试进程仍活 | 随后正确恢复，10/10，六字段一致 |
| S15_GUARDIAN_DEAD | 杀 guardian 后旧 worker 仍活；B 无法重新取得控制，保留原 owner/grant/permit、启动额度及 T/J | Source10/Target0，缺10键；未完成同步 |
| S15_CAPABILITY_LOST | guardian 仍活，但原 ManagedJvmIsolation 引用被替换，新实例无旧 Process 控制能力；库存明确 UNKNOWN，B 暂停 | Source10/Target0，缺10键；未完成同步 |

八个正例共80行、480个字段值逐项相等。每个正例目标审计10次 INSERT、目标账本5页，J 中对应5次 CommitSnapshotPage；同时核对 batchId、cursor、上下界、行数及原 execution。两个负例分别保留全部 Source/Target 原始行、缺键、额外键、字段差异和原始 T/J，不能把“正确暂停”说成“Source=Sink”。

本轮切点均在旧 worker 的首个目标批次提交之前，T/J **页进度**为0/0；S1等场景仍可能已经持久化 Split 计划等 Journal 事件，因此0/0不表示 Journal 完全空。后继 worker 完成5页。这里没有验证 supervisor 重启叠加 T已提交/J未存、COMMIT结果未知或恢复中再次崩溃；那些是后续 S5—S12，不能借用旧 worker 恢复实验替代。

## 探针如何建立证据

实验主进程只持有它创建的 supervisor/guardian 子进程句柄。**worker 由独立 guardian JVM 调用原生产 ManagedJvmIsolation.launch 创建**，原始 Process 仅留在 guardian。B 是全新 supervisor JVM，读取实验 SQL 和经过认证的 Unix domain RPC 库存，不接收原 Process 引用、不调用 ProcessHandle.of(pid) 获取控制权。

RPC 请求用私有本机密钥认证，并记录 nonce；响应由 guardian 的 Ed25519 密钥签名，首次部署时固定的公钥与 guardian 启动实例公开归档。B 检查响应签名、nonce、guardian、boot、进程启动身份和 SQL owner。原始 RPC 帧、签名、库存、退出回执均保留，可独立验签。认证密钥和数据库密码留在 `/tmp` 私有文件，不进入材料。

guardian 实际终止原始子进程，并由原生产 manager 产生 `managed-child-jvm` 回执。B 验证传输和身份绑定后，调用原生产 confirmIsolation/recordObservation/releaseDrained，保持隔离→实例排空→归还配额→新许可顺序。这是**实验提供方转交原始子进程证明**，不是已经新增了生产 `managed-guardian` 隔离提供方。

两张 `guardian_probe_*` 控制表只存在于实验 schema，保存 controllerEpoch 和启动意图；另有 `guardian_probe_journal_writes` 取证表。启动额度限制为一个未退休意图。旧意图在未知时占槽，S15实际尝试额外申请并被拒。它们不是生产迁移 DDL，也不能证明生产启动/epoch协议已经接入。

每个 worker 使用原生产 MySQL Source、Actor Journal 和 MySQL Sink，沿用原单表 Split 与恢复身份。本轮沿用原持久化配置，验证 Journal 与事件重放，未触发 Actor Snapshot 的保存或读取。worker 的测试控制来自独立文件；supervisor stdin 消失不会释放门闩或停止 worker。材料实际保存 A死亡后旧 worker 仍存活的进程身份。各 worker 使用各自单节点测试集群；本轮不验收同一集群的 shard 故障迁移。

Source 静止，共10行六字段，含负主键、大于JavaScript精确整数范围的主键、中文/emoji、NULL/空串、DECIMAL及微秒时间。控制库、Source、Journal和Sink共享一台隔离 MySQL，不能描述成多物理数据库验证。静态快照 D=N/A，没有CDC ACK。

## 配额、Journal 与清理

保留 cut、supervisor-dead、reconstructed、after-proof-recorded、drained-before-quota-return、after-quota-return 和最终状态。对已 bootstrap 的旧 owner，真实 SQL 审计必须满足 fenced < db_drained < old_quota_released < 新 recovery_permitted；drained 时旧 grant 仍在，归还后才变 ready。S4走实际 startup_failed 墓碑分支；S2未创建进程不伪造 PID 或退出证明。

实验 MySQL trigger 在实际 Journal INSERT 内保存连接上的 owner、permit、generation 和 admission sequence。验收逐行绑定到对应 recovery_permitted，核对新许可晚于旧额度归还，避免只收集日志而不验证因果关系。

S15负例完成取证后，由**实验主进程**写 STOP_TEST，让本轮 worker 自行 halt；不释放 Source 门闩，不生成退出证明，不改变生产配额，也不作为B自动恢复的一部分。这是清理逃生设施，生产 worker 没有该入口。所有外层清理异常仍执行 guardian/pool关闭；worker 存活但启动身份不可读时记为清理未知，不能当作退出。

## 构建、失败过程与审查

第一次编译因测试入口缺少 duration import、两个 write 导入冲突和一个 JsonWriter 用法错误而失败，原日志保留；属于设施编译失败，不是故障场景结果，也不算行为 RED。修正后本地签名/身份与原 ownership 相关18项定向测试通过。首次独立 S1先导1/1通过，之后补充断言再冻结完整十项，因此其源码清单单独保存。

独立只读审查发现清理异常可能跳过 guardian/pool关闭，以及 Journal许可证据只归档未断言。补充外层finally、未知启动身份处理、逐行许可与顺序断言；将仅响应nonce绑定的单测更名，并在S14增加真实请求重放。复审未发现新的已核实阻断项。[审查记录](code-review.json)仅支持源码结论，最终运行由独立冻结矩阵证明。

最终后端全量 **564/564、106 suites** 及package通过。实验结束客户端与InnoDB事务均为0，无探针JVM遗留；只停止本轮测试容器，11个schema及volume保留。汇总、资源核对和原始日志见 [result.json](result.json)。完整材料见 [evidence.tar.gz](evidence.tar.gz)、[逐文件清单](archive-manifest.json)和[校验和](SHA256SUMS)。现场重跑使用 [run.py](run.py)，显式设置 RUN_MYSQL_GUARDIAN_PROBE=1 及隔离 MySQL 环境变量；先 Java17 编译并导出 Test/fullClasspath，再冻结 source.zip 和JAR。

[verify.py](verify.py)是离线核验，不连接数据库或恢复任务。它检查归档散列、当前源码、签名、T/J页、所有行字段、未知状态保留和许可顺序，并拒绝损坏行值、cursor或签名的反例。使用 `--archived-source` 时只校验历史源码。需要当前环境已存在的 Python cryptography 验签库；本轮未安装新依赖。

## 尚未验证与下一步

本轮没有接入生产 supervisor/guardian 的启动恢复、没有新增生产隔离提供方、没有验证新的生产协议持久化兼容。旧持久化类型保持原样；生产与构建文件字节不变，可从 [source-boundary.json](source-boundary.json)核对。

没有验证 guardian 整个 JVM 重启后的控制重建、主机重启、远端fence、多 supervisor跨主机竞争、数据库failover、多物理实例排空、恶意同权限客户端、真实OS PID复用、长期密钥/证明保留治理、70万物理表或长期资源开销。S14错误PID/start和无关测试进程存活，不等同于实际发生PID复用。

本机文件锁只覆盖参与同一锁协议的受管supervisor；实验epoch表和HMAC认证不是跨机器领导权协议。IPC异常、guardian身份变化或原Process控制能力缺失时，实验选择暂停，没有PID兜底。

这些结果支持继续细化最小生产改造：明确持久化启动票据、guardian实例绑定、控制代次验证和退出证明重放。但应先讨论生产接口和S5—S12的注入范围，不能把本轮探针直接升级为生产恢复保证。全部旧实验、旧失败证据保持原样。
