# 旧 worker 进程隔离与遗留数据库事务：真实边界实验

源码基线 `7ec75d716779d760972ab76f925465dbf953e7c0`。本轮仅新增四份测试源码、显式运行入口与实验材料，116份已跟踪生产/构建文件逐字节对照基线未变，未暂存、提交或推送。MySQL实际版本8.4.11、Java17，Pekko1.1.3 / Persistence JDBC1.1.1。worker日志使用UTC，目录日期使用北京时间。

本轮验证的是“旧进程已退出”与“遗留数据库操作已结束”是否可以合并为一个条件。结果不能合并：目标事务和Journal INSERT两种服务端等待中，进程已经死亡，MySQL仍有旧连接和事务。测试等待两类观察均为空后，由测试代表运维显式重新排队，B恢复后逐字段一致。这里没有实现生产自动接管。

## 真实路径和固定窗口

A、B为独立Java子进程，各自创建单节点Pekko Cluster；实际工作使用生产WorkflowSharding、EventSourcedWorkflowActor、WorkflowExecutionEngine、SnapshotSplitExecutor、MySQL Source与Sink。B先作为测试candidate，只调用生产Store.bind尝试旧授权并得到false；其工作流实体和Dispatcher须由测试在取证后发送START才启动。这不是同一多节点Cluster里的故障转移或SBR验收。

每项创建独立随机schema。Source、目标、控制表、Journal处于同一个真实MySQL实例，使用独立连接。Source静止，10行、六字段，含负主键、超过JavaScript精确整数范围的大主键、中文/emoji、NULL/空串、DECIMAL和微秒时间。沿用原execution、计划、Split和批次身份，页大小2、Split大小4、并行度1，最终五个已提交页。

连接通过Connector/J连接属性标记进程UUID和journal/source/target/control角色。观察方保存PROCESSLIST、session_connect_attrs、INNODB_TRX与已知连接ID的并集，并保留未归属连接；查询失败不能当作空列表。记录进程PID、启动时间和ps状态。进程身份由本轮创建的Java Process对象绑定，不把集群移除或心跳丢失当成死亡。

- 读取后门闩：保留真实Source页，尚未交给Sink；SIGSTOP场景冻结两秒，进程仍活着。两秒是实验观察阈值，项目尚无新的owner lease，不能称生产租约已经过期。
- 目标事务门闩：真实Sink已在同一连接执行数据、账本和摘要写入，在COMMIT前等待。对观察连接而言T和目标数据尚不可见。
- T/J门闩：真实目标事务已确认并关闭，但页Checkpoint尚未交给Actor。
- J回复门闩：原Actor的update Future已成功，真实J已落盘；仅延迟返回执行器的观察。
- 服务端目标门闩：在实际目标提交连接、原事务内执行GET_LOCK等待，控制方持有锁，再杀掉A。不是由另一个连接代替目标事务。
- 服务端Journal门闩：仅测试schema新增BEFORE INSERT触发器，在第一条CommitSnapshotPage的真实event_journal INSERT内等待GET_LOCK；此前的T已经提交。门闩解除后，观察这条旧Journal事务实际消失，再恢复。

GET_LOCK和Journal触发器均为测试注入，不进入生产资源。测试Source/Sink池把socketTimeout延长为120秒，门闩最多90秒以稳定观察；不能据此推导生产默认10秒超时下的发生概率或故障耗时。连接、事务视图分次读取，附有观察起止时间，并非同一原子快照。

## 最终运行结果

最终冻结运行时为 `isolation-1792722670696328000`。八项全部完成原execution的恢复与全量对账。表中J页数从保存的真实Journal CBOR解码重算；T是目标批次账本已提交记录数。

| 固定窗口 | 切点T/J页数 | 杀A后首份观察：旧会话/事务 | B目标账本重放页 | 最终对账 |
|---|---|---|---|---|
| KILL_DB_JOURNAL_WAIT | 1/0 | 1/1 | 1 | 10/10，六字段一致 |
| KILL_DB_TARGET_WAIT | 0/0 | 1/1 | 0 | 10/10，六字段一致 |
| KILL_J_AFTER_STORE | 1/1 | 0/0 | 0 | 10/10，六字段一致 |
| KILL_READ | 0/0 | 0/0 | 0 | 10/10，六字段一致 |
| KILL_TARGET_TX | 0/0 | 0/0 | 0 | 10/10，六字段一致 |
| KILL_T_BEFORE_J | 1/0 | 0/0 | 1 | 10/10，六字段一致 |
| OBSERVER_DENIED | 0/0 | 0/0 | 0 | 10/10，六字段一致 |
| SIGSTOP_READ | 0/0 | 0/0 | 0 | 10/10，六字段一致 |

KILL_DB_TARGET_WAIT中，死进程后仍有实际target会话与InnoDB事务；KILL_DB_JOURNAL_WAIT中仍有实际journal会话与事务。两个场景强制要求该反例实际出现，缺失则以evidence_incomplete断言失败，不能跳过后仍宣称通过。释放服务端门闩后，样本还出现连接列表已空、事务视图仍有记录，再变为两者均空；考虑视图刷新及回滚观察差异，这不用于计算精确回滚时长。

KILL_T_BEFORE_J和Journal服务端等待场景均实际重放一页，由原目标账本判为AlreadyCommitted；KILL_J_AFTER_STORE恢复时首个Reader从cursor=1继续，未重放已确认页。每项最终10个INSERT审计记录，无额外UPDATE/DELETE；原始Source/Target逐行数据、缺键、额外键与全部六字段差异均保存，不只对计数或总摘要。

OBSERVER_DENIED使用本轮创建的仅有schema SELECT权限的账户，实际读取session_connect_attrs被MySQL拒绝。测试拒绝重新排队并核对T/J/控制状态未变；随后使用完整观察确认释放。临时账户在finally精确删除，schema和证据保留。

## 验证范围与保全

| 层级 | 本轮实际结果 | 边界 |
|---|---|---|
| 默认后端test + package | 537项、98套，0失败/中止，打包成功 | 默认排除ExternalIntegration；真实八项由独立Runner另外执行 |
| 最终真实矩阵 | 8/8，逐字段一致 | 静态Source、独立JVM、单MySQL实例、测试显式恢复 |
| 离线核验 | 重算键/字段、Journal页、T/J对应关系、进程与SQL观察顺序 | 不连接MySQL，不冒充新一次真实实验 |
| 原单表/CDC历史实验 | 原材料保留，生产源码未变 | 本轮未重跑原22/27项真实回归，D始终N/A |
| 清理 | 0客户端、0InnoDB事务、0临时测试账户后停止本轮容器 | 仅操作本轮容器，18份schema dump及容器/卷保留 |

保留全部18次运行：读取退出先导1次通过；目标等待先导1次因“Source物理连接必须仍开放”的错误测试假设失败；首轮八项通过；审查收紧后的最终八项再次通过。快照页读取后物理Source连接可能已经关闭，不能把Reader的存续当成数据库连接仍开放。失败探针、源码快照与日志完整保留，没有改成生产故障或从统计中删除。

最初SBT编译通过，但classpath导出命令写错；修正为`export Test / fullClasspath`，原日志保留。这是运行设施错误，不能算真实MySQL用例已执行。独立只读复审还要求等待场景不能缺少关键观测仍通过、临时观察账户应清理；两处均只修改测试并重跑最终矩阵。首轮遗留的同一测试账户也精确清理，未删除任何数据schema。两轮默认完整构建日志及冻结源码/JAR分别保存，最终报告只引用最终运行时。

[完整结果](result.json)、[源码摘要](source-manifest.json)、[生产源码边界](production-source-boundary.json)、[完整原始材料](evidence.tar.gz)、[包摘要](evidence-archive.json)、[包内逐文件摘要](archive-manifest.json)、[凭据扫描](credential-scan.json)、[离线核验结果](validation.json)、[校验清单](SHA256SUMS)。归档内cases/含每次原始进程、T/J/D、行数据和观察；runners/含各版源码与冻结JAR；dumps/含18份SQL快照；logs/含失败、编译、复审、清理和完整后端测试记录。实际密码与私有settings不归档。

## 复核和重跑

```sh
python3 docs/experiments/2026-10-23-owner-isolation/verify.py
# 后续源码变化后，仅核对归档版本：
python3 docs/experiments/2026-10-23-owner-isolation/verify.py --archived-source
```

离线核验器还在原证据的内存副本中移除残留事务、篡改目标字段、伪称自动接管，确认三种错误证据均被拒绝；不修改原件。

真实重跑先用Java17执行：

```sh
sbt 'project pekko-server' 'Test / compile' 'export Test / fullClasspath'
# 显式设置CDC_WINDOW_MYSQL_HOST/PORT/USER/PASSWORD，必须为独立loopback实验实例
RUN_MYSQL_OWNER_ISOLATION=1 python3 docs/experiments/2026-10-23-owner-isolation/run.py isolation
```

可加`--scenario KILL_DB_TARGET_WAIT`选择一个场景。会创建并保留随机schema，杀掉的仅是本测试启动的worker子进程。账户需要创建测试schema、触发器和临时观察用户以及读取服务端事务/连接信息的权限；不要连接生产实例。

## 未验证边界和设计影响

未验证生产部署平台的可靠终止接口、机器断电、远端网络分区、同一多节点集群的SBR/Shard迁移、旧代际被平台重新启动、多库间观察、永久阻塞close或任意未标记驱动连接。连接属性是实验归属标记，不能当作防伪的生产隔离凭证。多次空观察也不能独立证明任意系统未来不会再出现旧连接；本轮依赖已确认死亡且不会重启的受控子进程。

没有新增owner epoch、目标fence表、生产恢复许可门或自动接管状态机；没有让旧进程继续运行的双写者实验。Source没有并发修改、DDL或binlog切换；未启动Debezium，未验证跨owner ACK/offset/history，D=N/A不是已验证D安全。没有70万表或容量、接管时延、物理连接硬上限结论。

实际结果支持后续方案继续保留两道独立许可：先可靠隔离旧owner，再确认旧数据库操作结束；期间保留原授权与配额。随后按原T/J事实恢复原execution，而不是因超时清空进度。生产实现仍需另行讨论。
