# 跨表有界调度：提交前整体审查与验证

基线为 `b24762f367596abac15a8a40fe044aab1c35edcc`，分支 `feature/pekkosync-mvp-cleanup-datafusion`。本目录记录后续审查与修复；[初次材料](../2026-10-22-table-scheduling/README.md)和其全部失败归档保持原样。当前源码逐文件摘要见 [source-manifest.json](source-manifest.json)，精确差异见 [source-delta.json](source-delta.json)。这里的本地测试及实验不是生产部署或全场景高可用证明。

## 审查结论与修复

审查覆盖新调度包、SQL、冷入口、Supervisor、Actor、Engine、窗口执行器、查询及测试，重点检查恢复、释放和持久化。图分析工具在本会话未提供可调用接口，采用源码调用链、差异、独立只读复审和实际测试，不把空图当成覆盖证据。独立复审没有运行真实实验，其意见与运行日志分别归属。

1. **旧窗口重试误归还新授权。** 旧失败重试仅有 executionId，下一窗口启动后会取到当前 grant。固定局部窗口实际观察到新窗口由 RUNNING 误变 READY。现在失败、重试、成功回调均固定原 grantId，定时器按授权区分，旧成功回调也不停止新的运行。真实 `STALE_DISPOSITION` 中，先读出新窗口页并挂起应用 Reader，再执行旧授权重试；保存的 SQL 仍是新的 RUNNING grant，最终对账一致。门闩不代表仍有开放数据库读事务。
2. **Stop 的控制归还失败后永久 stopping。** 局部实际 RED 保留。现在先等待真实 cancel/排空确认，再重试同一授权的控制库归还；实际资源关闭未知仍保留 fence。最终真实探针记录同一 execution 的 `STOP_REQUESTED → CANCEL_STARTED → CANCEL_CONFIRMED → CONTROL_RELEASE_REJECTED → 再次 ENGINE_STARTED`，最终16表收敛。较早只有 sleep/Reader 观察的 Stop 运行没有被当成这条完整证据。
3. **授权过滤后的旧本地映射滞留。** 新窗口 deferred 时若旧归还已在数据库提交、但本地结果未确认，直接过滤会让旧重试随 Actor 退出而丢失。局部实际 RED 保留。mismatch 分支现在只读 SQL，确认旧 grant 已不存在才条件删除旧本地映射，不更新新授权。旧 retry 遇到 SQL 中仍在运行的新授权时仅无操作返回。SQL 归还已提交且回复/重读均未知的交叉状态由 H2 构造验证，尚未做真实 MySQL 双重观察失败注入，不扩大成端到端双故障保证。
4. **同一完成请求改写定义。** 先 register 再按旧 execution 返回，会保存 revision2 并返回旧 completed。H2 实际 RED 后，在同一控制事务及锁内、修改定义前拒绝“同 execution、不同 definition”。测试核对原完整记录不变、原请求仍可重试、更高 revision 配新请求可 READY。真实 MySQL SQL 契约探针也验证该分支；其中 completed 由测试种入，没有执行 Source/Actor/Sink，Journal 为0，不冒充新工作流已完成的证据。

原 CDC 的 T→J→原始回调前缀 ACK→D 数据恢复协议未改；15份保护源码摘要与基线一致。公共 Actor/Engine 有控制面修改，所以另跑恢复回归，不用15份源码摘要替代实验。18份保存的旧 CBOR 样本以原 manifest 解码后重编码逐字节一致，新增非空 admission/事件/授权消息也实际往返；这不是所有历史状态、滚动升级或降级证明。

## 本轮实际验证

| 范围 | 结果 | 能支持的结论 |
|---|---|---|
| 默认后端测试与 package | 533项，96套，0失败，构建成功 | 本轮默认回归；含旧样本和新控制窗口测试 |
| 真实跨表 Source/Actor Journal/MySQL Sink | 14种窗口，13项全表收敛、1项按设计隔离 | 每项16表、1167源行、六字段及键全量对账；按页可见 |
| 原单表 Split 真实回归 | 22项：16收敛、6安全暂停 | 保留旧暂停行为和独立恢复边界，D为N/A |
| 原 CDC 真实回归 | 27项：20收敛、7安全暂停 | 保留切点T/J/D、原始ACK顺序、实际offset和逐字段材料 |
| 请求身份真实SQL契约 | 原请求改定义拒绝，原记录不变，新请求READY | 控制表事务契约；人工种入完成状态，不是端到端运行 |
| 70万条元数据 | 复核初次材料，没有重新执行规模装载 | 初次版本的持久化目录/有界页实验，不是70万物理表或常驻CDC容量 |

全量默认测试曾出现异步 termination observer 计数过早，改为等待该观察回调，未修改生产取消协议。另一轮532条测试无失败但旧百万 TestProbe 示例关闭超过10秒，整套测试因此失败，不算绿色；在真实设施停止后另跑最终完整结果。该示例实现是100个路由 Actor 与大量 TestProbe/消息，不能用其测试名证明百万业务 Actor 容量。两轮失败日志都保留。新增探针的编译接线失败也单独保留，不当成已运行的协议实验。

早期复用私有端口32808时容器重启映射实际变为32809，14项在连接前失败；随后按当前 docker 映射启动。另一次并行设施运行遇主机负载超过200、可用物理内存很低，停止的仅是本轮三个实验 JVM 树，保留全部数据库与中断记录，再串行运行。初始连接失败和中断运行不计入最终验收，不据此修订生产恢复协议或放宽断言。后续空闲实例停止/重启前置条件、把内部 Daemon 计作客户端的保守检查，以及一次残留客户端尚未自然消失的清理中断也保留；没有执行 KILL，最终实际客户端为0后才停止两个本轮实例，数据与卷均保留。

关闭未知场景实际目标为 914/1167 行，253 个缺键、0 个同键字段差异；table-00隔离，其余15表一致。不能称全部14种场景最终数据均完整。unknown 继续计费，未实现自动回收或生产人工继续/放弃 API。

## 源码版本与材料归属

最终所选14项包含两个补跑：最新 Store 身份检查版本的 BASELINE，以及补齐 cancel 直接观察的 STOP_RELEASE_RETRY；每项 runtime 在下表及 result.json 中单独标记。其余所选窗口和原22/27项冻结在同一恢复/释放修复版本，只比最终 Store 少三行 enqueue 身份拒绝检查，其余生产文件相同。核验器严格检查该唯一差异，不写成全部生产源码逐字节一致。探针新增观察前的两份源码也按原运行时摘要保存；JAR冻结，不随后续编译覆盖。

70万元数据材料仍归属初次运行时；Store 的 claim/bind/list 与模型、SQL未改，但最终 enqueue 增加了身份检查，故不能称整个 Store 字节不变或本轮又完成了一次规模实验。核验器只核对这条精确源码差异，不能由此推导新版本任意负载的性能等价。

| 场景 | 最终分类 | 目标/源行数 | 冻结运行时 |
|---|---|---|---|
| BASELINE | 16表逐字段一致 | 1167/1167 | tables-1792679078624263000 |
| BIND_REPLY_LOST | 16表逐字段一致 | 1167/1167 | tables-1792676911869209000 |
| CLOSE_UNKNOWN | 15表收敛、1表隔离 | 914/1167 | tables-1792676911869209000 |
| COORDINATOR_RESTART | 16表逐字段一致 | 1167/1167 | tables-1792676911869209000 |
| GRANT_REPLY_LOST | 16表逐字段一致 | 1167/1167 | tables-1792676911869209000 |
| HELD_REDELIVERY | 16表逐字段一致 | 1167/1167 | tables-1792676911869209000 |
| J_REPLY_LOST | 16表逐字段一致 | 1167/1167 | tables-1792676911869209000 |
| READ_T | 16表逐字段一致 | 1167/1167 | tables-1792676911869209000 |
| RELEASE_REPLY_HELD | 16表逐字段一致 | 1167/1167 | tables-1792676911869209000 |
| RELEASE_REPLY_LOST | 16表逐字段一致 | 1167/1167 | tables-1792676911869209000 |
| STALE_DISPOSITION | 16表逐字段一致 | 1167/1167 | tables-1792676911869209000 |
| STOP_READ_T | 16表逐字段一致 | 1167/1167 | tables-1792676911869209000 |
| STOP_RELEASE_RETRY | 16表逐字段一致 | 1167/1167 | tables-1792677542500206000 |
| T_J_CRASH | 16表逐字段一致 | 1167/1167 | tables-1792676911869209000 |

## 复核与保全

[结果](result.json)、[证据包摘要](evidence-archive.json)、[逐文件摘要](archive-manifest.json)、[先前材料保全](prior-evidence-hashes.json)、[凭据扫描](credential-scan.json)、[本轮构建检查](package-check.json)、[校验清单](SHA256SUMS)。证据包分片：[part1](evidence.tar.gz.part-001)、[part2](evidence.tar.gz.part-002)。包内包含 RED/GREEN、各运行的真实日志/原始数据/切点/Journal/ACK/offset、SQL dump、冻结JAR、源码与中断记录；私有 settings 和实际凭据不打包。初次208份既有实验文件及旧C2失败证据保持原样。

```sh
python3 docs/experiments/2026-10-22-table-scheduling-review/verify.py
# 后续源码变化后仅复核归档版本：
python3 docs/experiments/2026-10-22-table-scheduling-review/verify.py --archived-source
```

核验器离线重算键和六字段、解析保存的真实 Journal CBOR 对照T，检查授权向量、固定窗口顺序及源码归属；不是新一轮数据库实验。真实 Source/Actor/Sink 重跑沿用[显式运行入口](../2026-10-22-table-scheduling/run.py)，本轮SQL契约见[专用入口](run-request.py)。均需先用 Java17 编译 Test/fullClasspath，并显式提供独立 loopback MySQL 环境及 RUN_MYSQL_TABLE_SCHEDULING=1；会创建随机库并保留，不能连接生产。

## 未验证和未实现边界

仅静态、固定 Schema、quiescent Source、数值单主键快照。没有共享 CDC、多 owner 自动接管/fencing、在线主键迁移一致性、DDL、全量到binlog切换、多节点网络分区/单例自动迁移、混合版本升级或旧二进制降级实验。新 JVM 不绑定旧 owner；崩溃恢复实验先确认旧进程退出且连接消失，再由测试代表运维重新排队，不能称自动接管。

连接预留是连接器逻辑向量；Journal、控制/观察池等另计，实际采样可能超过预留值，也可能漏峰值。3×P×maxPageBytes 是应用页预留，不是 JVM RSS 上限；永久阻塞的 JDBC close 未验证。队尾轮转不承诺固定等待时间或大任务无饥饿；旧 Cron 不是70万冷任务目录入口。历史较多实例的 CDC 初始化超时仍保留为未解决边界，新隔离实例通过不等于修复历史加载成本。配额在线迁移、跨库原子定义注册和暂停后的生产处置API仍不在本轮实现内。
