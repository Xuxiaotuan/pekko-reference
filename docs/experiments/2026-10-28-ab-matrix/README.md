# A/B 故障矩阵补测：2026-10-28

本轮继续验证[原计划](../../development/plans/2026-10-26-capture-admission-and-release.md)和[此前补测](../2026-10-27-ab-followup/README.md)。整体仍为 **evidence_incomplete**：一个编号包含多个分支，以下结果只对应明确列出的切点；安全暂停不等于恢复收敛，组件级释放探针也不等于端到端对账。

生产源码摘要为 `a41ffa49378afa2f0e424c37be248ab889a274bae9275b1148ebc0a52b36846a`，没有修改生产恢复协议。测试设施分别冻结多个候选，源码摘要以每次 `receipt.json` 和候选清单为准。两个隔离槽使用真实 MySQL source/state（14365/14366、14375/14376），每次核对 UUID、端口、datadir、独占租约和后置空闲。只有注明 Journal 和 Sink 的实验包含真实目标链路；预复制基线不代表在线全量初始化。

[原始证据包](evidence.zip)及[摘要索引](archive-index.json)保留切点、回调与 ACK、L/D/T/J、连接及事务采样、结果和独立对账。日志的密码配置行脱敏，凭据和租约不归档。跨库对账在写入受控停止后，按主键集合及所有字段比较，并拒绝重复主键；这不是跨库原子快照。

| 项目与窗口 | 实测结果 | 证据边界 |
|---|---|---|
| A01 完成态 D、无当前 callback ACK | 在真实 SDK 第一个 callback 加测试门，加载已有完成态 D，仍为 STARTING，新增订阅拒绝；自然运行的两次 heartbeat=0 没有命中缺 ACK 窗口，均保留 | 测试门模拟无 callback，不能推断 heartbeat 关闭必然不就绪，也未覆盖所有 snapshot/skip 组合 |
| A09 已提交未绑定 B、完成态 D 在 B 之前 | D 行锁和真实 binlog 坐标固定；重启返回 `UNBOUND_MARKER_UNPROVEN`，Engine 创建数 0 | SAFE_PAUSED；不符合计划中可证明重放时自动收敛的可用性目标。A05 的保守策略未修改 |
| A09 D 已越过未绑定 B | 测试先插入真实控制行再登记 pending，构造历史不一致；新版安全暂停、不创建 Engine | 这是显式构造的历史状态，不是当前正常订阅写入顺序 |
| A10 原始 ACK 与 D | 主键变化路径固定 MID_ACK，并锁住实际 D；崩溃后重启，B 不变，T 唯一组 1→2、J 7 条，4 行 24 字段相等 | 对账基于预复制基线；不是全部 ACK/D 联合窗口 |
| A11 完成态 D 命名空间冲突 | 基于真实 Actor/Sink 的已完成 D，测试将文件名改为另一命名空间，重启返回 `CHECKPOINT_UNCOVERED`，Engine 创建数 0；独立复算 4 行 24 字段相等 | 注入是测试构造；这是启动安全暂停及停写时目标相等，不是故障自动收敛 |
| A12 ACK 父引用摘要错误 | 测试修改已持久 ACK 的父引用摘要并重算该帧自身摘要，重启返回 `CHECKPOINT_UNCOVERED`、Engine 创建数 0；独立复算 4 行 24 字段相等 | 只覆盖一种引用损坏；是安全暂停，不是故障自动收敛 |
| A13 并发订阅 | 两表及同身份请求在 admitted/dispatch 门重叠，同身份不重复建立 B，冲突拒绝，实际 Engine 创建一次；a/b/c 各 4 行 24 字段相等，T 4、J 13 | 其他表为预复制见证，不把它们计为动态初始化 |
| A14 源 COMMIT 结果未知时取消 | **失败**：源事务仍未决、返回 `MARKER_COMMIT_UNKNOWN` 时，直接调用与 Runner 共用的 Store 取消路径，B_PENDING 被置为 CANCELED；随后源事务迟提交。活动表 a 的 T/J 稳定且 4 行 24 字段相等 | 不是完整 BootstrapRunner 端到端取消实验；目标对账不能使失败变通过。原始 before/after/resolution 与失败断言保留 |
| A15 关闭后迟到 callback | 测试门固定真实 SDK callback，close 后放行；消费逻辑拒绝 callback，phase 保持 STOPPING，新订阅拒绝且未登记，后续物理空闲 | 同一实例的关闭门，不是跨 JVM 迟到 ready。测试门人为保留被中断的等待；首轮因中断导致 callback 未返回而失败，原结果保留。无 Actor/Sink 业务执行 |
| A16 旧记录 | 旧 JVM 创建实际 CBOR Journal 和 L/D，新 JVM 恢复；旧 Journal 四条 payload 与 L 21 帧保留为前缀，写后 4 行 24 字段相等，T 2、J 7 | 覆盖该旧版本和本样本，不涵盖全部历史 manifest 与 INITIAL 分支 |
| B01 静默及忙源关闭 | 真实 Actor Journal/Sink；静默 4 行 24 字段相等、T 0/J 3；忙源 4 行 24 字段相等、T 1/J 4；旁账号未被关闭 | 基线预复制；采样只证明当次释放过程 |
| B02 同步 SDK close 卡门 | 真实 SDK close 调用被测试门阻塞，预算内返回 UNKNOWN 且 owner 保留；释放测试门后观察到服务端释放 | 确定性包装切点，单独作为资源实验，不含 Journal/Sink 对账 |
| B03 completion 与 runner 分离 | 真实 SDK completion 成功和失败各一支；测试门阻塞外层 runner 退出，提前关闭结果 UNKNOWN，放行并确认服务端释放后才复用 | 这是对实际 SDK 的确定性包装；不是所有 executor 卡死方式 |
| B04 callback 异常与已执行目标 | 实际 Actor/Sink 已应用一次更新，4 行 24 字段相等；关闭 Actor 后，真实 callback 4 注入异常，ACK 已返回 0—3，T/J 保持不变，源/目标在 label、revision 上出现 2 个字段差异。初次关闭本地 CONFIRMED、服务器 UNKNOWN，后续真实采样确认可复用，旁连接存活 | 对账中差异是未应用的真实写入；没有执行后续自动恢复，不可写成收敛。故障是测试注入，基线预复制 |
| B05 关闭与启动竞争 | 尚未 start 即关闭、启动直接抛错样本通过；factory 创建前门失败。进一步以测试子类固定真实 Debezium MySQL task 的 `start`，两槽均在放门后 180 秒未确认空闲，退出测试 JVM 后槽位空闲；放门前 UNKNOWN、owner 未释放 | 测试门位于真实 调用原 task 的 start 实现之前；不能称永久泄漏，也不覆盖所有启动位置。组件级，未运行 Actor/Sink；所有失败保留 |
| B06 COMMIT 未知与关闭 | **失败**：真实源 marker 事务仍 RUNNING 时关闭返回 `reusable=false`、server UNKNOWN，但 `ownerReleased=true`，状态库命名锁也已释放；T/J 不变，活动表 4 行 24 字段相等 | Reader 级、无资源观察器；此 owner 是 capture 命名锁，不能等同平台 owner 或自动接管结论。断言失败后测试清理，没有验证迟提交后恢复 |
| B08 观察器上下文重启 | 专用 source MySQL 正常重启后原观察连接与受控连接均报真实 JDBC 断连，UUID/datadir 不变，进程 PID 和 uptime 改变 | 组件级探针，不含 Actor/Sink；首轮因测试端口 TIME_WAIT 检查而重启失败，恢复过程与失败记录保留，修正测试设施后重试通过 |
| B08 观察连接断开后的受管关闭 | 已有真实 Actor/Sink 更新后切断观察连接；关闭及再次检查均为 local CONFIRMED、server UNKNOWN、owner 未释放、不可复用。4 行 24 字段相等，T 1、J 4，旁连接仍可用 | 人为切断观察连接；没有恢复观察或自动重启业务。对账是预复制基线上的已应用更新 |
| B08 归属不明 | 实验中的非 capture 连接与 capture 共用账号，观察器返回 UNKNOWN，并记录实际连接 ID；该连接执行 SELECT 仍正常 | 专用实验账号的组件探针；没有任务、Journal 或 Sink，不证明共享账号能区分各连接归属 |
| B09 旧 attempt 回复 | 测试包装将真实资源观察返回的 attempt 改为旧值，结果 `RELEASE_ATTEMPT_MISMATCH`，owner 未释放；正确 attempt 确认后复用，4 行 24 字段相等，T 1/J 4 | 模拟过期回复，不覆盖所有跨 JVM 迟到 ready 时序 |
| B09 双 JVM 的旧执行者迟到 ready | 新 JVM 已 UNKNOWN、Engine 创建数 0 后，旧执行者解除测试门并进入 STREAMING；新 JVM 仍为 UNKNOWN、Engine 创建数 0。旧执行者继续处理，64 行 384 字段独立对账相等 | 两个并存实例；未向新 JVM 直接注入任意旧 ready 消息，也不代表故障接管 |
| B10 活 owner 冲突 | 独立 JVM 初始化时固定目标写入门，另一个 JVM 报 UNKNOWN、Engine 创建数 0；原执行继续后 64 行 384 字段相等 | 活 owner 冲突不等于旧 JVM 已崩溃且资源未知后的完整接管 |
| B10 旧 L 事务未决、新 JVM 启动 | 真实 L 写事务仍在 MySQL 运行时启动独立新 JVM，新 owner 为 UNKNOWN、Engine 创建数 0，旁会话仍可用；旧事务回滚及服务端释放确认后原关闭操作才可复用 | Reader/L 资源探针，无任务 grant、Journal 应用或 Sink 对账；不是旧 JVM 崩溃后自动接管验证 |

| B10 旧 JVM 崩溃、L 事务仍在 | 独立旧 JVM 的初始化基线为 64 行 384 字段相等；测试代理在真实 L COMMIT 前保留 state MySQL 事务，杀掉旧 JVM 后事务仍 RUNNING。新 JVM 为 UNKNOWN、Engine 创建数 0；代理回滚事务后仍 UNKNOWN | 新 JVM 没有自动接管；源/目标有 5 个字段差异，独立复算保留。代理是测试设施，不能推广到所有真实网络故障 |

`B_BOUND` 观察事件在 bind 因取消未实际持久化 B 时仍可能发出，不能单凭事件名证明绑定；本轮没有改生产事件语义。A05、A09 未绑定旧 marker 自动继续所需的持久证明尚缺。除上表之外，A01、A09—A12、A14—A16 和 B04—B10 的部分展开窗口仍需核对原计划，不能把编号本身记为全部通过。70 万真实源表、长时负载、自动接管及 DDL/GC 仍未验证。

## 按原编号检查的覆盖情况

“局部”表示只有部分展开分支取得证据；“失败”表示对应验收不满足，包含局部失败的不计为整项通过。此前证据详见[前一轮报告](../2026-10-27-ab-followup/README.md)。

| 编号 | 状态 | 当前边界 |
|---|---|---|
| A01 | 局部 | 首次、skip 与无 callback 测试门均有样本；组合及自然静默窗口未穷尽 |
| A02 | 局部 | Schema 可见但尚未 ready 时拒绝，保存无源 B；不同历史状态需另证 |
| A03 | 局部 | 完成信号、实际 INITIAL D 锁和 ACK 已固定；非端到端恢复 |
| A04 | 已测指定窗口 | 无旧 B 中断后恢复及四行逐字段对账 |
| A05 | 验收未满足 | 未写证明尚缺；安全暂停不是自动继续 |
| A06 | 局部 | COMMIT 未知及迟提交/回滚有样本；资源状态不代表自动恢复 |
| A07 | 已测指定窗口 | 已提交未绑定 B + INITIAL，启动前安全暂停 |
| A08 | 已测指定窗口 | 旧 B_BOUND + INITIAL 安全暂停，旧历史差异保留 |
| A09 | 验收未满足 | D 前后分支均暂停；D 前有完整证明时自动重放尚未实现 |
| A10 | 局部 | 已测一次 ACK/D 同时卡门和恢复对账，其他排列未穷尽 |
| A11 | 局部 | 缺失、坏 JSON、错误 partition 和一次文件命名空间冲突；其他格式未穷尽 |
| A12 | 局部 | history、源控制行、ACK 帧缺失及一次父摘要错误；其他引用损坏未穷尽 |
| A13 | 已测指定窗口 | 并发重复请求与冲突，三张活动表对账 |
| A14 | 失败 | COMMIT 未知时直接 Store 取消过早完成；Runner 端到端仍未测 |
| A15 | 局部 | admitted 源写与 close 交错、关闭后迟到 callback 已测；未穷尽所有顺序或跨 JVM 迟到 ready |
| A16 | 局部 | 一种旧版本 CBOR 与 L 前缀恢复通过；不能涵盖所有 manifest |
| B01 | 已测指定窗口 | 静默与忙源关闭，真实 Journal/Sink 基线对账 |
| B02 | 已测指定窗口 | 测试门阻塞真实同步 close；组件证据 |
| B03 | 局部 | 成功/失败 completion 与外层 runner 门已测；executor 故障方式未穷尽 |
| B04 | 局部 | 真实 callback 异常、ACK 前缀及 Journal/Sink 切点已测；未验证异常后自动恢复 |
| B05 | 失败 | factory 前门和实际 MySQL task start 门在观察期内均未确认释放；不代表所有启动位置 |
| B06 | 失败 | 源事务仍在途时 capture owner 锁已释放；reusable 仍为 false；平台级接管未验证 |
| B07 | 局部 | 真实服务端连接延迟消失期间 UNKNOWN；释放时刻精度受采样限制 |
| B08 | 局部 | 权限、UUID、断连、专用数据库重启和共用账号连接组件样本；其他归属模式仍待验证 |
| B09 | 局部 | 模拟旧 attempt 释放回复、两个 JVM 并存时旧执行者迟到 ready 已测；任意旧消息注入未覆盖 |
| B10 | 局部 | 活 owner 冲突及旧 JVM 崩溃、L 事务仍在时的新 JVM 拒绝已测；自动接管未覆盖 |
