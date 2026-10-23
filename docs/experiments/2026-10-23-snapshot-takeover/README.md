# 受管静态快照：硬隔离后自动接管

本轮增加生产控制协议，覆盖受管静态 `mysql.snapshot → mysql.write`。旧 [owner-isolation](../2026-10-23-owner-isolation/README.md) 是测试显式重新排队，保持原样；本轮候选 JVM 从开始就运行 dispatcher，由生产 coordinator、排空观察器、cluster retirement 和许可 gate 自动接管，没有测试 `requeue` 或 `START` 命令。静态快照 D=N/A。

最终结果与 [result.json](result.json)、[source-manifest.json](source-manifest.json) 及冻结 runner 对照。基线 `7ec75d716779d760972ab76f925465dbf953e7c0`；工作区未提交，不能以该基线 SHA 代表本次新增实现。精确运行源码以 manifest 和 runner 内 source.zip 为准。

| 验证层级 | 最终结果 | 结论范围 |
|---|---|---|
| 本地后端全量与 package | 553/553，102 suites；使用 `-J-Xmx4g -J-Xms256m` | 包含旧序列化兼容及新增控制协议行为；一次原有时序断言失败另行保留 |
| 受管进程自动接管 | 22/22；`matrix-1792728093283046000` | 22 个独立 schema，每个 10 行×6字段，全部键/字段一致；220 行、1320 个字段值逐项对账 |
| 原 Split 真实回归 | 22/22；`snapshot-regression-1792728416095587000` | 16 个收敛正例，6 个安全暂停负例 |
| 原 CDC 真实回归 | 27/27；`cdc-regression-1792728539459537000` | 20 个收敛/释放正例，7 个安全阻塞负例；不意味着 CDC 多 owner 接管 |
| 旧隔离材料 | 8 项历史材料离线校验通过；四个旧测试设施字节未变 | 未把旧显式 requeue 改标为自动接管，也没有把离线复核算成现场重跑 |

三套真实实验的冻结源码清单一致，并与最终工作区的 276 个源码/配置/构建文件核对一致。最终低干扰全量和打包通过；全部先导失败、一次 552/553 的全量结果及定向重跑仍保留。实验后观察到一个残留 binlog 线程，经下文记载的清理事件后连接/事务/临时账户均归零；容器停止，153 个隔离 schema 和数据卷保留。

## 源码结论

新增 owner/实例/会话、writer permit、转换审计及触发意图六张 SQL 表。隔离回执固定 boot、PID、启动时间、deployment 和 operation；只有 supervisor 自己创建、持有精确句柄并确认退出的进程可以出具证明。仅疑似失联或 SIGSTOP 不释放配额。

控制观察覆盖固定实例上的旧会话、独立 InnoDB 事务及无法归属的 worker 账户连接。两次完整空观测、sealed manifest、旧进程不可恢复之后才可归还旧额度。新许可保持 execution/revision/definition hash，generation 单调递增，`recovering` 占启动额度。原 T/J 批次与 Split 恢复逻辑不变，旧持久化类/字段没有改名或换形状。

Bootstrap 首个 owner 与控制实例/连接/账户原子登记。注册前死亡保留 `startup_failed` 墓碑，COMMIT 回复丢失后也能幂等退休句柄。冷查询和 Cron 入口不创建持久化 writer：查询从原 Snapshot DAO/serializer 加载，再回放尾部；Cron 意图与水位在 SQL 中决定，恢复使用原 scheduled trigger。使用方式和前提见[指南](../../guides/mysql-snapshot-takeover.md)。以上是源码约束，不等同于所有部署均已验证。

## 真实环境与固定故障窗口

独立 Java 17 worker JVM 在同一 Pekko 集群运行；真实 MySQL 8.4、JDBC Actor Journal/Snapshot Store、生产 MySQL Sink、目标批次账本和页摘要。控制库、Journal、Source 和 Sink **共享同一物理 MySQL**，不把不同 JDBC 角色说成跨物理实例实验。门闩和代理只用于固定时序，不重写任务状态，也不代替隔离或提交。

| 组 | 用例 | 需要的证据 |
|---|---|---|
| 进程与数据 | SIGSTOP_READ、KILL_READ、KILL_TARGET_TX | 活进程暂停时不接管；杀进程后证明、排空、许可、原 execution |
| T/J | KILL_T_BEFORE_J、KILL_J_AFTER_STORE | 原页重放命中真实账本；J 已存时从已确认 cursor 之后继续 |
| 服务端遗留 | KILL_DB_TARGET_WAIT、KILL_DB_JOURNAL_WAIT、KILL_DB_SNAPSHOT_WAIT | GET_LOCK 等待的精确角色/连接及同一 InnoDB trx；进程已死但服务端工作仍在时配额不变 |
| 观察与证明 | OBSERVER_DENIED、OBSERVER_SEPARATE_ACCOUNT、WRONG_ISOLATION_RECEIPT | 查询失败/未归属连接/错误启动身份不能放行 |
| 协调与竞争 | COORDINATOR_RESTART、COMPETING_CANDIDATES、STALE_GRANT | SQL operation 复用，竞争无双授权，真实旧 Activate 及后续 barrier 不改变 T/J/许可/额度 |
| 二次故障 | B_RECOVERY_CRASH | B 已取得 generation=2、Journal 恢复门闩内死亡；再次 fence/drain 后 C 的 generation=3 |
| 控制提交回复丢失 | REVOKE、FENCE、DRAIN、QUOTA_COMMIT_REPLY_LOST | 真实 COMMIT 成功后客户端抛错，自动重读，不双归还、不换 execution |
| 原入口 | CRON_RECOVERY | 真实 SchedulerCoordinator 集群单例、运行中重叠触发、冷 readiness/status/history、完成后重复/落后水位 |
| 启动边界 | KILL_BOOTSTRAP、STARTUP_COMMIT_REPLY_LOST | prepare 后 ActorSystem 前死亡仍可观测排空；墓碑提交回复丢失不永久占启动槽 |

每个数据用例 10 行、6 字段、5 页，逐主键逐字段比较真实源/目标，检查缺键、额外键、字段差异及实际行效果审计。数据包含负键、超过 JS 安全整数的 BIGINT、UTF-8、NULL/空串、DECIMAL 和微秒时间。不能用最终行数相同替代字段一致；不能把这组小数据对账外推为吞吐、规模或所有类型保证。

额外 Journal AFTER INSERT 探针记录真实连接 owner、当时 permit/generation 和控制序号，核对每条工作流 Journal 写入发生在许可之后。只读查询合法地可以在 writer 许可前打开 Journal 池，不能用“连接打开时间”冒充“持久化写入时间”。

## 失败材料和修订

所有先导及失败 runner、原始日志和数据库 schema 保留在归档中。包括：测试 fallback 缺失、布尔值字符串绑定触发 MySQL 1366；旧门闩误认任意事务、Snapshot 等待前尚未触碰 InnoDB；B 二次故障的错误全局时序断言；新增观察账户未授权默认库；默认 1 GiB 全量测试 OOM，以及本机 `JVM_OPTS` 未被 SBT launcher 采用的停滞运行。之后分别按实际原因修订，不把失败运行改标为通过。

独立审查补出了冷查询/Cron、实际旧 Actor 消息、句柄退休、旧 Snapshot+删除 Journal 前缀、启动注册原子性和墓碑提交结果未知的缺口。新增两项行为断言曾实测失败，修后通过。旧 C2 失败材料及旧恢复测试均保留；本次不扩展或重写 CDC 协议。

另补了冷 gate 的旧 held grant 用例：原实现按 workflowId 取当前 grant，旧消息可以唤醒新授权；H2 Actor 失败断言复现后，恢复检查绑定激活消息 grantId。该冷 gate 是 H2 行为验证；真实 STALE_GRANT 用例是在新 owner 读取门闩内投递旧 Activate，两者分别记录，不混称同一个真实窗口。

全量回归曾出现一次原 `WorkflowExecutionEngineSpec` 的 `close-1/open-2` 顺序断言失败（552/553）。该 fixture 的 close 事件由另一个异步终止观察回调记录；这是时序原因的源码推断，尚未通过专门确定性实验排除所有原因。定向重跑通过，最后全量结果另列，失败日志保留；本轮未顺手修改该 fixture 或执行会话协议。

CDC 回归结束后，测试 worker JVM 已全部退出、InnoDB 事务为零，但服务端仍残留一个等待发送更新的 Binlog Dump 线程（ID 36982）。首次清理检查因此失败，未记为已释放；随后在全新的隔离清理库写入一条记录以产生 binlog 事件，再检查服务端。该操作与前后观测记录在 `observations/cleanup-pending.json`，清理库也随 dump 保留。这是实验结束后的主动清理，不是 CDC 协议自行确认服务端释放；原取消测试的 EngineRunner 释放结果不能扩大为服务端所有连接立即消失，更不能据此宣称 CDC 多 owner 接管已覆盖。

## 证据和复核

- [汇总](result.json)、[源码清单](source-manifest.json)、[归档逐文件清单](archive-manifest.json)、[校验和](SHA256SUMS)、[分卷索引](archive-parts.json)。完整材料按 40 MiB 分卷：[00](evidence.tar.gz.part-00)、[01](evidence.tar.gz.part-01)、[02](evidence.tar.gz.part-02)，共 108815465 字节，1679 个逻辑文件，保留全部先导/失败 runner 与原始材料。按索引顺序连接分卷即可还原 gzip tar；校验器直接读取分卷并核对整体及逐文件 SHA256。
- [现场 runner](run.py) 需要显式环境开关和隔离 MySQL；先 Java 17 编译并导出 Test/fullClasspath，再冻结 JAR 和 source.zip。凭据只从环境读取，禁止把私有凭据文件归档。
- `python3 docs/experiments/2026-10-23-snapshot-takeover/verify.py` 离线验证归档、当前源码、全量行字段、T/J 页身份、许可/证明顺序及三个篡改反例。以后源码变化可用 `--archived-source` 只核对历史归档；这不是重跑真实故障实验。
- [最终离线校验输出](verification-result.json)：归档完整、当前源码一致，22 场景/220 行×6 字段，旧回归 36 收敛/13 安全暂停，三个篡改反例均被拒绝。[分卷检查](packaging-check.json)保留了首次辅助脚本路径表达式错误及原单卷未删除的记录；修正后逐字节还原一致。

## 未验证边界

只覆盖可信 supervisor 管理的本机子 JVM 和固定 MySQL 部署，不包含 supervisor 整个 JVM 丢失后的可信重新收养、远端机器/Kubernetes fence、多 supervisor 竞争、数据库主备切换、实际网络分区、多物理数据库排空、权限在线变更、恶意属性伪造和永久阻塞的 JDBC 操作。Coordinator 对象重启实验复用同一个进程句柄管理器，不能称为 supervisor 进程自动接管。

未做 70 万张物理表、多 owner 长期压测、RSS 硬上限、共享 CDC/DDL/在线增量快照或跨表原子可见性。Source 在实验期间静止。SOURCE/SINK 逻辑连接额度不包含 Journal、控制和观察池。旧 Snapshot 形状和保留前缀兼容有 H2 实测；真实 MySQL Snapshot Store 残留事务另有固定窗口，二者不能相互替代。

源码补查另发现一个长期运行问题：`TableSchedulingRuntime.reject` 清除本地 owned 后，尚未同步移除定时触发缓存 `scheduledTriggers`。失败的定时任务可能残留轻量触发对象；SQL 配额和 writer permit 走独立持久化路径。此处尚未做专门复现实验，不把它升级为已验证的内存影响；也不能因此声称长驻大量失败定时任务的资源增长已经验收。本轮保留该问题，未扩大为长期调度缓存治理。
