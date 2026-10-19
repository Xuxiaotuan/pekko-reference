# 完整组协议的剩余恢复窗口：八项真实 MySQL 实验

2026-10-19，Asia/Shanghai。**正式八项观测完成：七项最终收敛，C2 自动恢复失败。** ScalaTest 的八项断言全部通过，包含“确认 C2 失败并保留差异”的断言；不能写成八项恢复都成功。本文及 [result.json](result.json) 分别报告材料核验、恢复能力和逐字段结果。

本轮只修改/增加 Test 源集和实验材料；`pekko-server/src/main`、`build.sbt` 没有改动，与生产参考提交 `65ab5410c32933d0aa3d23510e6c62252d84009b` 的差异为空。实际运行 HEAD 为 `dca7d25cfcf7747d7d08231259c3353331755b84`，测试设施当时未提交。27 个生产/测试/构建文件的摘要见 [source-manifest.json](source-manifest.json)，证据包保留对应源码快照。没有将后续发布版本冒充运行 HEAD；本轮没有提交、推送或更新网站。

## 1. 环境、前提与证据层级

正式环境：MySQL 8.4.11，ROW/FULL、GTID ON；Debezium 3.6.1.Final、ORDERED、单处理线程、关闭 tombstone；Pekko 1.1.3、Persistence JDBC 1.1.1、Scala 2.13.12、Java 17.0.14，测试 JVM UTC。新建隔离容器 `pekko-cdc-eight-20261019`，仅绑定 loopback；每项独立随机 schema。镜像 ID、参数、schema 与实际分批配置见 [run-manifest.json](run-manifest.json)。正式运行后停止容器，保留数据。收尾时 `docker stop` 后容器为 exited、退出码 137；所有正式取证及 mysqldump 在停止前完成，这次收尾不计作 MySQL 服务端崩溃恢复验收。

只验证单 writer、单表、固定 Schema、固定源实例和未重置的日志历史。先用原有初始化路径建立 64 行可信目标和持久 streaming D，再启动显式完整组模式；这不是新 snapshot 协议的验收。复制 namespace/generation/connector 与目标映射在故障恢复间不变。

每项包含初始化、故障、恢复三个独立 JVM，正式共 24 JVM。首轮八项和一次独立 N0 试跑另保留为 exploratory，不累加覆盖数量；探索期间曾补充观测字段，不能将其冒充最终固定测试设施的重复证明。正式八项只各执行一次。

- **源码结论：**Engine 的 T→J→Source ACK 顺序、组装配与原始前缀确认、Actor 失败终态的状态机。
- **真实实验：**真实 SourceRecord、MySQL 业务表/组账本/审计、JDBC Actor Journal、真实 SDK 句柄和 JDBC offset store。
- **测试替代：**闸门、offset 行锁、Sink 专用 MySQL TCP 代理以及进程硬退出；不构造 SourceRecord，不替代生产 DML、组身份、Journal 或 SDK ACK。
- **离线复核：**`verify.py` 从已保存材料独立计算，不是再次启动数据库。

## 2. 故障门与实际切点

T 表示完整组证据与目标效果；J 表示持久化的交付 Checkpoint；D 是数据库里完整的 Debezium 恢复上下文。它们的数量和序号不直接相等比较。所有场景的 v2 基线为 T=1 组、J.sequence=0、目标审计 66 条，D 已对齐。

| 编号 | 实际故障门 | 实际切点 | 结果 |
|---|---|---|---|
| N0 | 不注入故障，正常追平后硬退出 | 正常 T/J/D 已对齐 | 恢复及后续数据收敛 |
| J1 | Source 的 `acknowledgeCommittedBatch` 入口、调用 super 之前 | T=17 组，J=1，D 仍为基线；本交付尚未执行 Source ACK | reader 16→1，旧数据重读去重，后续收敛 |
| J2 | 真实原始 SDK ACK 返回后暂停 | T=2 组，J=1，D 仍旧；真实 offset DELETE 被行锁阻塞，SDK flush 超时 | reader 1→max128，实际首次恢复交付 72 组，后续收敛 |
| C1 | 代理收到 MySQL COMMIT 成功响应后扣住，不转发 JDBC，再硬退出 | T=17 组，J=0，D 仍旧；JDBC 未收到提交成功 | reader 16→1，同一执行恢复及后续数据收敛 |
| C2 | 同上，但丢弃成功响应并断开客户端，让异常正常进入 Actor | 切点 T=17 组，J=0，D 仍旧；随后持久化 ExecutionFailed | 重启不启动 Reader，自动恢复失败 |
| C0 | COMMIT 到代理但未转发 MySQL，关闭上游；客户端暂留等待，随后硬退出 | 原目标事务已回滚，T=1 组，J=0，D 仍旧 | 恢复后首次执行这些组，后续收敛 |
| K1 | 主键原始回调 [d][c]；ACK A 真实返回，ACK B 尚未开始 | 完整组已在 T/J；T=2，J=1；D 已推进到共同恢复上下文 | 无需再交付这组 d/c，后续数据收敛 |
| K2 | 与 K1 同门，但 offset 写被锁住且 flush 失败 | T=2，J=1，ACK A 返回但 D 仍旧，B 未调用 | 原始记录重读、整组去重，后续收敛 |

J1 是“尚未 ACK”；J2 是“ACK 已调用并返回、真实 flush 未成功”，两种证据不合并。J2/K2 的 flush timeout 测试值为 2000 ms，flush interval 为 0；失败日志、数据库 `LOCK WAIT` 及完整 D 均保留。旧 JVM 退出后，等待其阻塞数据库写入结束，再释放 offset 行锁；不让迟到写入污染恢复起点。MySQL 服务器等待清理使用真实默认锁等待路径，其耗时不作为恢复性能指标。

COMMIT 代理只接收 Sink 连接，Source、Journal、offset/history 和取证连接直连 MySQL。代理按 MySQL 包长度读取，识别 sequence=0 的 COM_QUERY COMMIT，C1/C2 确实收到服务器 OK 后才扣住响应；不是 `commitBatch` 已成功返回后在 Future 上抛错。C0 是独立的“未提交”负对照，不用于声称 JDBC 异常后的自动恢复通过。代理限于 loopback、无 TLS、无压缩、小报文的隔离实验，不能作为通用 MySQL 代理。

## 3. 全量逐字段与副作用结果

| 场景 | 最终 T 组数 / J.sequence | 审计条数 | 缺行 / 多行 / 字段差异 | 自动恢复 |
|---|---|---:|---|---|
| N0 | 74 / 7 | 140 | 0 / 0 / 0 | PASS |
| J1 | 74 / 74 | 140 | 0 / 0 / 0 | PASS |
| J2 | 74 / 3 | 140 | 0 / 0 / 0 | PASS |
| C1 | 74 / 73 | 140 | 0 / 0 / 0 | PASS |
| C2 | 17 / 0 | 82 | 2 / 2 / 212 | FAIL_TERMINAL |
| C0 | 74 / 73 | 140 | 0 / 0 / 0 | PASS |
| K1 | 6 / 5 | 72 | 0 / 0 / 0 | PASS |
| K2 | 6 / 3 | 72 | 0 / 0 / 0 | PASS |

每项最终均保存源/目标各 64 行，完整比较 `id/label/amount/note/updated_at/revision`。成功的七项为 384 个字段值全部相同，缺行、多行及字段差异均为 0。比较由独立 JDBC 连接在 REPEATABLE READ 的同一读事务中执行；最终比较前源写入已停止，保留完整行数据，不只比 count/hash。

普通工作负载为单语句更新 64 行、同事务四次更新相同主键、一次主键迁移，以及故障后的 update/delete/insert，随后再更新新键。审计独立 SQL 预期：基线 66 + 64 + 4 + 2 + 3 + 1 = 140。K1/K2 仅注入主键迁移，加故障后三个变化和新键更新：66 + 2 + 3 + 1 = 72。两次 before/after 相同的合法更新具有不同物理身份，仍分别保留。

离线 verifier 还从原始 SourceRecord 的 Struct before/after 字段重建审计内容与顺序，再逐行比较，不复用生产 Envelope 来计算审计预期。旧审计前缀、所有新旧组成员与原始记录、完整 d/c 配对及 ACK 连续前缀均被检查。不能只因最终行值相同就宣称没有重复副作用。

K1/K2 原进程均保留原始回调 ACK 顺序 `[0,1]`：0 是基线，1 是主键 DELETE 所属 A，CREATE 所属 B=2 已到达但未开始确认。K1 的实际 D 已能跳过共同物理行；目标 d/c 整组此前已经提交，恢复未再收到它不构成漏行。K2 的 D 仍旧，恢复交付包含重读的完整组，目标副作用没有重复。

## 4. C2：保留的真实失败

正式运行 `C2-28962ae6eef6` 中，MySQL COMMIT OK 在代理被扣住并丢弃，客户端异常链为：

```text
IllegalStateException
→ com.mysql.cj.jdbc.exceptions.CommunicationsException
→ com.mysql.cj.exceptions.CJCommunicationsException
→ EOFException
```

目标已持久化 17 个组，审计 82 条；该故障交付没有推进 Checkpoint，最新 J.sequence 仍为 0，D 不变。随后 Actor 将 `ExecutionFailed` 写入真实 Journal，并清除当前执行。新 JVM 从真实 Journal 恢复后的状态仍为 failed，未调用 Source 的 `createBatches`，没有 Reader 启动。

继续发生源端 update/delete/insert 及新键更新后，目标、组账本、审计和 D 均保持切点状态。全量比较实际缺主键 `150,65`，多主键 `50,64`，共有 212 个字段差异。没有删除 Journal、重置 D、变更 generation、手动复制数据或新建执行来让它转绿。

**本次失败是提交结果不确定被持久化为失败终态后，原执行不能自动继续。** 切点已提交效果保留、D 没有越界，不等于同步可用性恢复成功；后续业务历史也不能因此被称为已同步。C1 成功不能替代 C2。下一轮应先讨论失败终态与恢复职责，再决定是否改生产状态机，本轮没有修复。

## 5. 检查与材料

| 检查 | 实际结果 | 能支持的范围 |
|---|---|---|
| 八项真实 MySQL 套件 | 8 项观测断言通过；7 项收敛，C2 恢复失败 | 正式八个固定窗口；不是八项恢复通过 |
| COMMIT 代理单测 | 4 项通过；保留 RED 阶段的 4 个预期失败 | 包分片、截断、COMMIT 识别和 ERR/OK 区分；真实包拦截另由 C0/C1/C2 覆盖 |
| 独立 verify.py | PASS | 归档哈希、全部行、组成员/身份、审计、ACK 前缀、T/J/D 和 C2 失败分类 |
| verify_tests.py | 5 项拒绝篡改检查通过 | 修改数据并重新计算文件哈希后，语义核验仍拒绝缺 PK 成员、字段篡改、同数量审计破坏、C2 假成功和 ACK 缺口 |
| `pekko-server / test` | 418 项通过，66 套件完成、2 套件中止；退出码 1 | 完整测试门仍为 evidence_incomplete |
| 独立只读审查 | 未发现阻断本轮的重要问题 | 审查注入因果与断言，不算额外数据库运行 |

全模块中止为 `MysqlSqlParserSpec` 缺 `com/google/protobuf/GeneratedMessageV3`，以及 `ComprehensiveCalciteTest` 无法初始化 `ConnectionPropertiesImpl`。本轮未改 Calcite/依赖，也不把这次全模块执行写成全绿。默认 test 排除 ExternalIntegration，八项真实运行是用 ScalaTest Runner 显式执行。

[evidence.tar.gz](evidence.tar.gz) 包含 324 个归档成员：正式八组的完整切点/最终行、T/J/D、序列化 Journal 载荷与解码结果、原始回调和确认顺序、stdout/stderr、SQL 工作负载、最终数据库 mysqldump、探索运行、原始检查日志、源码快照和内部文件校验表。探索记录单独标记，不计入正式八项。归档中的 `source/` 是本次确切源码；容器及数据库导出均保留，但可下载不表示其他环境已独立复现。

- [结构化结果](result.json)
- [运行清单](run-manifest.json)
- [源码摘要](source-manifest.json)
- [独立复核](verify.py)与[拒绝篡改检查](verify_tests.py)
- [全部材料 SHA256SUMS](SHA256SUMS)

只读复核无需启动数据库：

```sh
cd docs/experiments/2026-10-19-recovery-windows
shasum -a 256 -c SHA256SUMS
python3 -B verify.py
python3 -B verify_tests.py
```

复跑请使用新的隔离 MySQL 8.4、ROW/FULL/GTID，按环境提供仅实验用账号；运行会创建随机 `pekko_test_cdc_window_<12位后缀>`，保留 schema，不操作已有业务库。新实例上需确保实验账号具备观察数据库锁/事务和建立实验 schema、Journal、触发器的权限。不要将示例直接指向业务实例。

```sh
export RUN_MYSQL_CDC_RECOVERY_WINDOWS=1
export CDC_WINDOW_MYSQL_HOST=127.0.0.1
export CDC_WINDOW_MYSQL_PORT='<临时端口>'
export CDC_WINDOW_MYSQL_USER='<隔离实验账号>'
export CDC_WINDOW_MYSQL_PASSWORD='<仅实验用密码>'

sbt -java-home /Users/xujiawei/Library/Java/JavaVirtualMachines/ms-17.0.14/Contents/Home \
  -J-Xmx3G 'project pekko-server' 'Test / compile' 'export Test / fullClasspath'

/Users/xujiawei/Library/Java/JavaVirtualMachines/ms-17.0.14/Contents/Home/bin/java \
  -Duser.timezone=UTC -Xmx1G \
  -cp "$(cat pekko-server/target/streams/test/fullClasspath/_global/streams/export)" \
  org.scalatest.tools.Runner -o \
  -s cn.xuyinyin.magic.workflow.integration.RealMySQLCdcRecoveryWindowsSpec
```

依赖缓存和 Java 路径是本次环境，不是可移植部署配置。输出位于 `target/cdc-recovery-windows/`。运行、打包材料不会自动提交代码或发布文章。

## 6. 仍未验证

C2 自动恢复失败已确认，不能再列为“尚未验证”。其余仍未覆盖：收到主键半组即崩溃、组内 DML 后 COMMIT 前任意故障、Journal 插件写失败、目标/offset 磁盘故障、MySQL 服务器断电、任意网络分区、长期反复恢复、DDL、多 owner/fencing、共享读取、快照初始化/旧协议透明升级、binlog 清理/重置/切主、证据清理和 TTL、非事务外部副作用、全部 MySQL 类型/时区，以及吞吐、整个 JVM 内存和 70 万表容量。

本轮只证明这些固定环境与窗口的实际行为，没有扩大为通用 exactly-once 或生产就绪保证。
