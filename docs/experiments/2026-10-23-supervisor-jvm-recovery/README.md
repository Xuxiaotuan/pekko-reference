# Supervisor JVM 重启恢复：第一版生产实现与真实故障验证

本轮覆盖本机受管静态快照：独立 supervisor JVM 重启后，通过仍存活的原 guardian 找回原 worker 的控制能力，完成退出证明、遗留事务确认、配额恢复，再启动后继。代码、测试和材料尚未提交、未推送。

源码基线是 `afff7ab27560b74ad026aa370dba1cf920fdc587`；新增实现属于工作区，不能把这个基线 SHA 当作实现版本。精确版本由[源码散列](source-manifest.json)、归档中的冻结 `source.zip`、两个应用 JAR 和[变更边界](source-boundary.json)共同标识。

## 源码结论：新增控制面，保留原数据恢复协议

生产入口为 `SnapshotGuardianMain <supervisor.conf>` 和 `SnapshotSupervisorMain <supervisor.conf>`，worker 仍使用固定的 `cn.xuyinyin.magic.PekkoServer`。完整配置、密钥、部署锁和运行顺序见[部署指南](../../guides/mysql-supervisor-recovery.md)。不改变原 PekkoServer 启动命令。

| 新组件 | 负责的边界 |
|---|---|
| `SnapshotSupervisorStore` | guardian pin、controller epoch、启动票据、原始进程身份、稳定隔离 operation、签名证明及退休状态 |
| `SnapshotGuardianWire` / `SnapshotGuardianService` | 本机 Unix socket、请求认证与防重放、签名响应；只操作原始 `Process`，不凭 PID 收养 |
| `SnapshotSupervisorRuntime` | 有界控制 tick；复用旧 owner 的隔离、数据库观察、两轮空确认与额度转换 |
| `SnapshotSupervisorProcesses` | 独立 JVM 入口、固定配置及 classpath、启动锁、worker 配置预检 |
| `SnapshotOwnerBootstrap` 的 managed gate | worker 在登记 owner 前校验已持久化票据、身份和配置摘要；不能通过关闭 HA 绕过 |

只新增 `pekko_snapshot_host_control`、`pekko_snapshot_worker_launches` 两张控制表。原 admission 事务中的嵌套控制操作复用同一 JDBC 连接，让 controller 校验、证明写入和旧 owner 转换能原子提交。外部 RPC、进程等待和数据库观察不放在这个 SQL 事务里。

关键顺序是：

1. 固定本机锁与新 controller epoch；认证预配置公钥 pin 对应的原 guardian。
2. 持久化启动票据并占进程槽位，再发起 fork。`dispatching` 回复未知不能当作尚未创建。
3. guardian 从原管理器补录已 fork 的身份；确认从未创建才允许取消。取消、退休后的 boot 墓碑不复用。
4. 新 supervisor 取得原始子进程退出的签名证明；证明与旧 owner 的 fenced 转换同事务记录。
5. 按原 manifest 确认所有固定数据库实例；完整观察、间隔满足的两轮空确认之后，才能归还旧表任务额度。
6. 旧票据退休后再发新票据；后继 worker 继续由原 gate 取得 writer permit，用原 execution、Split、批次身份和目标账本恢复。

进程槽位与表任务连接/页额度是两层状态，不能互相替代。健康 owner 的结束连接登记在完整观察后有界删除，避免每页新连接累计耗尽4096条登记上限；观察不完整时保留。当前 runtime 串行维护一个当前 worker，不能把配置中的进程上限解释为已实现多 worker 并行调度。

控制 SQL COMMIT 回复未知时，按稳定标识回读；无法确认则保留上下文。guardian 原控制能力丢失、身份/认证失败、库存 UNKNOWN 时安全暂停，不以进程超时或 PID 不见代替隔离证明。即使已落证明，排空、归还额度和新启动前也复查控制能力；这不代表能原子预知未来的网络故障。

T/J/D 协议、Journal/Snapshot 的业务持久化类型、serializer manifest、persistenceId、executionId、batchId、Split 重试预算均保持原样。本轮静态快照 **D=N/A**；不能拿这个结论外推 CDC 多 owner。格式兼容也不等于允许新旧控制程序同时管理同一批进程。

## 实验结果

| 验证层级 | 实际结果 | 结论与范围 | 冻结 runner |
|---|---|---|---|
| 本轮生产控制协议故障矩阵 | 32/32 | 29项收敛、3项安全暂停；S5—S12的21个变体全部收敛 | `matrix-1792746865880842000` |
| 实际生产 main 与固定 PekkoServer worker | 1/1 | 完整静态快照、supervisor重启无重复目标写入、错误HA配置在fork前拒绝 | `main-smoke-1792747359494097000` |
| 原 guardian 探针 | 10/10 | 8项收敛、2项安全暂停；与生产协议分开计数 | `probe-regression-1792747371188107000` |
| 原同集群自动接管 | 22/22 | 22项收敛，220行×6字段 | `takeover-regression-1792747459543912000` |
| 原 Split 独立恢复 | 22/22 | 16项收敛、6项安全暂停 | `snapshot-regression-1792747745463251000` |
| 原 CDC 恢复与释放 | 27/27 | 20项收敛/释放、7项安全阻塞 | `cdc-regression-1792747872777457000` |
| backend 全量与生产 JAR | 579/579；110 suites；package通过 | 不含上表独立执行的外部集成用例；默认入口保持PekkoServer，生产class/SQL资源与冻结版本一致，测试class未进入制品 | `backend-final.log` |

六套最终真实实验的301个源码/配置/构建文件散列完全一致，并与当前工作区相同。完整结果见[result.json](result.json)，运行环境见[environment.json](environment.json)。故障矩阵的入口使用测试时序包装器驱动生产Runtime/Store/Guardian；实际生产main另有独立验证，不能将两类入口混称。

每个新的正例均使用真实 MySQL Source、MySQL Sink 和 MySQL Actor Journal。10 行覆盖负键、大整数键、Unicode、NULL/空字符串/空白、DECIMAL 和微秒时间；逐项比较 `id/label/amount/note/updated_at/revision`，不只比较条数或 checksum。最终29个收敛场景合计290行、1740个字段值；每例5个目标页账本均与 CBOR Journal 中的页 checkpoint、cursor、upperBound、批次身份和计数核对，目标审计每行仅一次插入。

安全暂停限制接管与资源再分配；旧 worker 仍可能继续已获许可的工作。本轮负例的固定读取门闩使 T/J 保持不变，不代表生产数据面立即冻结。

三个 guardian 负例各保留源10行、目标0行的完整差异，共30个缺失键；这属于预期暂停，**不是数据收敛**。暂停前后 T/J、owner、permit、票据和表额度没有被恢复程序越过。现场只读进程观察还确认：guardian 死亡/丢能力时旧 worker 仍存活，程序并未将其当作已死；证明已持久化后失去 guardian 时，旧 worker 已退出，但仍不归还未知额度。

### 固定窗口矩阵

下表 T/J 数字指已提交目标页与已持久化页 checkpoint 的数量，不是 Journal 全部事件行数；切点仍可能已有工作流启动、枚举等事件。

| 编号 | 切点及变体 | 验收证据 |
|---|---|---|
| S5 | 目标事务已写但未 COMMIT | 切点 T=0、页 J=0；原进程退出和数据库排空后恢复 |
| S6 | T 已提交，页 J 未持久化 | 切点 T=1、J=0；同批身份重放，目标账本返回已提交，无重复物理写入 |
| S7 | 页 J 已落库，确认尚未返回 | 切点 T=1、J=1；后继沿持久化页继续 |
| S8 | 目标 COMMIT 已应用/未应用，两种结果未知 | 分别观察 T/J=1/0、0/0；恢复后全量逐字段一致 |
| S9 | 退出证明回复丢失；证明/owner 转换 COMMIT 回复丢失 | 稳定 operation、签名证明、重复 supervisor 重启与旧额度转换 |
| S10 | Source、Target、Journal、Snapshot 遗留事务；首轮空观察后重启；观察权限失败 | 前四类记录同一真实连接 ID/trx_id 在 worker 退出后仍存在，原额度保持；后二类不得提前放行 |
| S11 | epoch、票据、dispatch、旧额度归还、票据退休、writer permit 的 COMMIT 回复丢失 | 实际 SQL COMMIT 后抛 SQLException，并阻止回读，再杀 supervisor；permit 场景必须由第三个 supervisor 完成第3代恢复 |
| S12 | 后继在 Journal 恢复中再次失败；后继再次处于 T-before-J | 两个前任均隔离、排空并退休；C controller 身份及第3代 permit；原 execution 收敛 |
| S1—S4 | supervisor 死亡时 worker 存活、票据未 fork、fork 尚未回复、ACK 丢失、bootstrap 前 | 独立 JVM、原始库存、取消/补录与完整对账 |
| S13—S14 | 锁竞争、SIGSTOP supervisor、错误认证/pin/进程身份、旧 epoch/重放 | 锁内身份不被抢占，错误请求拒绝，无关进程存活 |
| S15 | guardian 退出、原管理器控制能力丢失、证明落库后 guardian 退出 | 三项安全暂停，保留原上下文和额度，不能从 PID 重建控制能力 |

S10 的 Snapshot 窗口确实触发了 Actor Snapshot 的 INSERT 和真实阻塞事务；本轮独立离线断言主要核对 Journal replay 与页账本，**没有单独验收“从某个 Actor Snapshot 载入并跳过对应 Journal 前缀”的完整恢复路径**。

S8 在 JDBC COMMIT 返回前固定窗口：一支先真实提交，一支保持事务未提交，随后由原 guardian 终止 worker；它证明新 owner 对两种未知目标结果的恢复，不证明被终止的 worker 已收到 SQLException。S9/S11 则在真实控制 SQL COMMIT 后抛出 SQLException，并固定后续回读窗口，保留异常/回读顺序。这些都不等同真实 TCP COMMIT 数据包丢失。S10 使用测试专用透明 TCP 中继，仅为指定角色保留原服务器连接，不生成 SQL、不创建伪装为旧 owner 的连接。控制库直连；放行后由原观察器确认遗留事务结束。所有角色仍在同一物理 MySQL，不能外推不同物理数据库故障。

## 失败证据与审查修正

所有先导 runner、失败目录、原始日志、冻结源码和数据库 schema 一并归档。最终验收只采用选定最终 runner；不能把后续成功覆盖为前一次成功。

- 最初 S6 数据已收敛，但清理把 `DO RELEASE_LOCK` 当查询执行，runner 失败；保留原失败，改为 `SELECT RELEASE_LOCK` 后定向重跑。
- 第一轮广矩阵的三个未 fork 场景把后继的第一次物理 fork 误判为原 worker，Source 门闩没有放开；按 controller epoch 修正测试分类。
- 强化 Journal 遗留事务时，首次350ms后采样看到原事务已经结束，因此没有建立所要求窗口；不据此宣称验证通过。初版 TCP 中继又意外保留控制连接，阻塞证明事务；改为仅代理所需角色，并增加 Source 遗留事务例。
- `matrix-1792746108721740000` 的 ScalaTest 虽退出0，离线核验仍拒绝 S11 permit 场景：C supervisor 启动超时，旧 worker 在门闩超时后完成，许可仍为第2代。这整轮没有列为最终验收。进一步收紧的 `matrix-1792746680322452000` 明确失败0/1。原因为共享注入标记被已持 admission 锁的心跳线程消费；限定为 COMMIT 失败线程、在其下一条 SQL 前等待后，定向例通过，并重新运行全部最终套件。
- 独立源码审查发现三项 Important：缺少健康 owner 结束会话登记清理、无参数 JDBC URL 分隔符错误、managed worker 可误关 HA。均有 RED→GREEN 测试；修正后相关36项通过。4096登记上限验证使用 H2 构造历史记录，不是4096条真实并发连接实验。

[审查记录](code-review.json)区分审查判断与作者修正、实际运行证据，不把源码审查当作故障实验。

## 保留与兼容范围

[变更边界](source-boundary.json)逐文件核对旧 checkpoint/snapshot/CDC/事件与状态代码未改，旧探针源码未改，旧已跟踪实验文件未改。新表独立增加；已有兼容测试在最终 backend 全量中回归。未验证新旧控制程序混跑、跨版本滚动回退和生产数据升级。

旧[guardian 探针](../2026-10-23-guardian-restart/README.md)、[自动接管](../2026-10-23-snapshot-takeover/README.md)及 C2 失败材料保持原样：[最初恢复窗口](../2026-10-19-recovery-windows/README.md)、[C2 改造](../2026-10-20-c2-recovery/README.md)、[Checkpoint 拒绝](../2026-10-21-checkpoint-rejection/README.md)、[C2 终审](../2026-10-21-c2-final-review/README.md)。

本轮没有验收 guardian JVM 自身重启后的能力重建、主机重启、跨主机选主、真实 OS PID 复用、恶意同 UID 进程、数据库故障切换、不同物理数据库、700000张物理表、长期负载或性能。固定配置摘要只覆盖配置文本、堆上限和 classpath 字符串，不是全部 JAR 内容证明。新矩阵每个 worker 使用独立单节点测试集群；原同集群接管回归另计，不能合并为新 supervisor 下同集群 sharding 迁移已验收。DDL、共享 CDC 和 CDC 多 owner 也不在范围。

## 复现、离线核验与现场清理

在线设施 [run.py](run.py)要求 Java17、已构建 `Test/fullClasspath`、隔离的回环 MySQL，以及 `RUN_MYSQL_SUPERVISOR_RECOVERY=1` 和 `CDC_WINDOW_MYSQL_HOST/PORT/USER/PASSWORD` 环境变量。它新增随机 schema，不删除旧 schema。可运行 `matrix`、`main-smoke`、`probe-regression`、`takeover-regression`、`snapshot-regression`、`cdc-regression`；`matrix --scenario S6_T_BEFORE_J` 可定向固定窗口。不要对生产数据库运行。

[离线验证器](verify.py)只读取材料，不访问数据库、不发 ACK、不恢复任务、不操作进程；需 Python3 与 `cryptography`。运行 `python3 docs/experiments/2026-10-23-supervisor-jvm-recovery/verify.py`；以后源码继续变动时加 `--archived-source`，只验归档版本。[核验输出](verification-result.json)覆盖分卷/文件散列、签名响应、T/J、准入顺序、逐字段对账、原回归和三项篡改反例。原始证据按[分卷清单](archive-parts.json)顺序拼接，归档内路径与[文件散列清单](archive-manifest.json)对应；根文件见 [SHA256SUMS](SHA256SUMS)。

现场仅停止本轮容器 `pekko-supervisor-recovery-20261023`；停止前全局客户端0、InnoDB事务0、临时账户0，冻结实验JVM均已结束。234个隔离schema全部导出并保留原数据卷；没有触碰其他容器。MySQL版本、镜像、server UUID和卷信息见result.json的cleanup及归档`database/cleanup.json`。

归档递归扫描实际 MySQL 密码及本轮/回归探针认证和签名密钥，包含冻结 JAR/ZIP 内部；私有配置与密钥留在受限 `/tmp` 目录，不放入仓库。公钥 pin、进程身份与签名证明作为可核验证据保留。源码、实验设施、全部失败过程均未暂存、未提交、未推送。
