# 完整组生产协议：重新分批与 T 已提交、J 未持久化恢复实验

本轮将第四篇约定的完整组装配、目标组账本和原始回调前缀确认接入了生产 Source/Sink。**在本次固定环境的三组真实恢复实验中，最终 64 行、6 个字段逐项一致，已经提交的组重放没有新增目标触发器审计记录。** 这不是此前 Reader 探针的手动覆盖结果，也不是所有 CDC 场景的 exactly-once 认证。

实验取证时的工作区基线为 `2274691`，生产改动当时尚未提交；[系列版本表](../../cdc-design/README.md)给出后续发布的固定源码版本。取证时的 Git 状态保留在清单中，不把发布提交冒充实验运行 HEAD。确切文件摘要见 [source-manifest.json](source-manifest.json)，原始回调、T/J/D、表数据和运行日志见 [evidence.tar.gz](evidence.tar.gz)，独立核验输出见 [result.json](result.json)。这份记录不改写系列文章使用的历史源码基线。

## 1. 生产改动与保留的边界

| 部分 | 本轮行为 | 证据层级 |
|---|---|---|
| 完整组装配 | 普通 c/u/d 为单成员组；带 `newkey/oldkey` 的主键 d/c 按物理位置、Schema、分区、共享 restart offset 和双向键关系配对 | 源码、单测、真实跨回调恢复 |
| Bridge 与 Engine | 保留 Bridge 的阻塞交付和 Engine 的 `T → J → Source ACK` 顺序；Bridge 只接收完整组，覆盖代理句柄不调用 SDK | 源码、Bridge/Source 单测、真实作业 |
| 原始 SDK 句柄 | 回调尾部半组在 Bridge 之前保留；A 可以不 ACK 就返回，让 B 的 CREATE 到达；只有涉及的组均覆盖，才在有序回调线程确认原始回调连续前缀 | 单测、真实回调与 ACK 顺序 |
| 目标账本 | 新增 `pekko_sync_cdc_group_ledger`。完整组 DML 与规范化成员证据在同一目标事务提交；相同组相同证据跳过 DML，相同组不同证据回滚整次交付 | 源码、H2 回滚/冲突单测、真实 MySQL 提交/恢复 |
| Actor Journal | 不修改 Actor/Engine 持久化协议；重放返回**本次交付**的 Checkpoint，交付序号继续按 J+1 递增 | 序列化单测、真实 JDBC Journal 恢复 |
| 会话确认 | 新 `SourceBatch.acknowledgementId` 仅在内存中关联会话；不进入持久 cursor 或 Checkpoint。关闭会话、迟到 ACK、错误 batchId 均不能消费新交付的确认能力 | 源码、Source 单测 |
| 启动保护 | 新模式校验配置中的 server UUID；要求已有持久 streaming D、明确可信初始化声明；拒绝未完成快照的 offset 和旧版本 Checkpoint 混用 | 源码、offset 单测；真实实验使用有效基线 |
| 目标存储 | 目标业务表与组账本必须为 InnoDB；提前校验并在提交路径复核 | 真实 MySQL 的 MyISAM 拒绝测试 |

组身份使用以下固定版本的规范化编码：

```text
namespace = replicationId + generation + identityVersion
physical  = sourceLineage + database + table + file + source.pos + source.row
groupId   = SHA256(canonical([protocol, database, table, file, pos, row]))
eventId   = SHA256(canonical([groupId, componentRole]))
```

普通成员角色是 `single.c/u/d`，主键组是有序的 `pk.d, pk.c`。物理身份取自事件 `source`；完整 restart offset 用于配对校验和恢复上下文，两者不混用。规范化成员及完整边界 identity、目标表名保存在目标证据里，交付批次 ID 不参与业务去重。

当前 `SourceBatch.rows` 的每个字符串表示一个完整组。Checkpoint 的 `sourceRowsScanned/targetRowsWritten` 按组内逻辑成员计数，主键组计 2；重放时这些数字表示本次交付覆盖的成员数，**不能当成新增 DML 数**。新应用组与重放组分别记录在 Sink 日志中。

装配器限制待确认原始回调不超过 2 个，单回调记录数不超过配置的 `maxBatchSize`，保留内容采用 16 MiB 编码体积预算，已无回调引用的覆盖集合会清除。该预算约束装配器保留的内容，不能换算成整个 JVM 的堆上限；SDK 队列、对象开销和当前解码峰值不在这个数字内。缺失或冲突的配对、异常顺序、超预算会失败，半组不会因超时自动变成完整组。没有后续回调时，半组保持未确认，直到关闭或引擎终止。

## 2. 新模式怎样启用

本轮没有实现新的 snapshot 初始化协议。新模式只消费**已经可信初始化、已有持久 streaming D** 的历史。`initializedTarget=true` 是操作者提供的前提声明，生产启动代码不会替操作者自动做全表对账。

启用前，应冻结或可靠隔离源端业务写入，建立目标全量基线，逐字段核对目标与源，并确认与该基线对应的实际 D 已持久化。不能只因为旧作业运行过，就认为它没有发生过“只确认主键 DELETE”的缺口。旧缺口需要另行修复或重新初始化。

在目标库按现有方式保留原批次账本，再预先执行：

```text
pekko-server/src/main/resources/db/mysql/pekko-sync-cdc-group-ledger-schema.sql
```

v2 不使用、不写入旧批次账本去重；当前共用的 Sink readiness 仍检查原批次账本表。新表不由提交路径自动创建，也不自动清理历史证据。

在现有 `mysql.cdc` Source 配置中显式增加：

```yaml
groupProtocol:
  replicationId: orders-replication
  generation: trusted-baseline-20261019
  sourceLineage: '<SELECT @@server_uuid 的实际结果>'
  initializedTarget: true
```

固定 Schema 的 boundary identity 使用版本 2，cursor kind 为 `mysql.binlog.groups.v1`，组编码与身份规范为版本 1。旧模式仍使用原版本；不能拿旧版本的 J 自动续跑新模式。可信基线建立后应明确创建新协议的执行上下文，普通进程重启则保持相同 replicationId、generation、connectorId 和目标映射。不能在失败时随意换 generation 绕过去重。

新模式使用 `snapshot.mode=no_data`，但启动前已经要求 offset 表有有效 streaming D；丢失 D 时会阻塞，不能悄悄从当前 binlog 重新起跑。不要清除或搬移对应 connector 的 offset/history 表。server UUID 校验也不能证明 binlog 从未被重置；本轮契约要求固定实例、未重置日志历史，不支持切主或谱系迁移。

## 3. 真实故障实验如何固定窗口

运行环境为 MySQL 8.4.11、ROW/FULL、GTID ON；Debezium 3.6.1.Final、ORDERED、单处理线程、关闭 tombstone；Pekko 1.1.3、Persistence JDBC、Java 17.0.14。Source、目标业务表、组账本、触发器审计、Actor Journal 和 offset/history 都是真实 MySQL 表。各场景使用独立随机 schema，限定 loopback 临时 MySQL。

每组使用三个独立 JVM：

1. **初始化 JVM**：用原有 initial snapshot 路径建立 64 行目标，再完成真实 streaming marker；通过全字段对账和 T/J/D 对齐建立实验基线，随后退出。它只建立本次实验输入，不证明新协议具备快照能力。
2. **新协议 JVM**：新工作流使用固定 generation，从实际旧 D 开始；先完成一个 v2 marker，形成 `T=1 组、J.sequence=0、D 已持久化` 的基线。ARM 后在真实 Sink `Connection.commit()` 成功返回后，用测试 Future 阻止结果返回 Engine，因此 Engine 尚未请求下一条 Actor Checkpoint。
3. **恢复 JVM**：在外部连接记录 T、反序列化真实 `event_journal`、读取 Actor 状态、独立读取 D 与目标审计后，对旧 JVM `halt(86)`。恢复同一个 Actor 执行，只改变真实 Debezium 的 `max.batch.size`；从 D 实际重读，再接入正常生产装配器、组 Sink 和 Journal。

测试设施不伪造 SourceRecord、稳定事件 ID、Source ACK、目标组账本或 Journal。故障门只位于真实 COMMIT 之后、Engine 得到结果之前。原始回调及 SDK ACK 观察包装不会修改 SDK 句柄的提交内容。stdout 取证与 SDK stderr 分文件保存，避免大段 JSON 被日志交错破坏。

切点检查了：T 比基线多出完整组；J 仍等于基线序号 0；该交付没有进入 Source ACK；实际 D 等于基线，崩溃后也未改变。恢复后继续等待真实 D 与最新 J 中的源 offset 一致，再做逐字段对账。**ACK 返回不是 D 已刷盘的替代证据。**

普通场景在停机期间继续生成新的更新、删除与插入，保证恢复必须处理“旧组 + 新组”，不能只恢复旧值就结束。主键场景在重放完成后再更新新键 150，验证确认之后仍可继续流动。

## 4. 实际结果

| 场景 | 切点新增完整组 | 首次恢复交付的实际组数 | 切点 J → 最终 J | 最终组账本 / 审计行数 | 全量逐字段对账 |
|---|---:|---:|---|---|---|
| split-gap：真实 reader 16 → 1 | 16 | 1 | 0 → 73 | 74 / 140 | 64 行 × 6 字段，差异 0 |
| merge-gap：真实 reader 1 → max 128 | 1 | **72**，含 73 个逻辑成员 | 0 → 2 | 74 / 140 | 64 行 × 6 字段，差异 0 |
| pk-gap：d/c 各在单条回调 | 1，含 2 个成员 | 1，含 2 个成员 | 0 → 2 | 3 / 69 | 64 行 × 6 字段，差异 0 |

这里的 128 是配置上限，不是实际收到 128 条。本次合批实际收到 73 个逻辑成员，装配为 72 个完整组。拆批场景恢复时，主键 d/c 也确实分别落在两个原始回调。

普通工作负载包括 64 行单语句更新、同一事务内对同一主键的四次更新、一次主键修改，以及停机后的 update/delete/insert。四次更新中两次的 before/after 完全相同，但物理位置不同；最终原始记录与组账本均保留这两次合法发生，没有因为 payload 相同而合并。

审计预期独立按 SQL 工作负载计算：初始化与两个 marker 共 66 条；普通场景恢复后为 `66 + 64 + 4 + 2 + 3 = 139`，后续新写入后为 140；主键场景恢复后为 `66 + 2 = 68`，后续新写入后为 69。切点已经产生的审计前缀原样保留，其对应效果没有在恢复新增审计中重现。主键切点的整份审计，在重放完成、后续新数据到来之前完全不变。

逐字段核对使用另一个 JDBC 连接，在同一只读比较事务中读取源与目标，按主键索引比较完整六列：`id/label/amount/note/updated_at/revision`。不复用生产 Envelope 转换器，不只比 count 或 hash；保留 NULL、空字符串、前后空格、中文/emoji、DECIMAL 和微秒时间。缺行、多行和字段差异分别记录。

## 5. 自动化检查与它们各自能支持的结论

| 检查 | 实际结果 | 不能据此扩大成什么 |
|---|---|---|
| Source / Sink / Bridge / 模型及新增边界测试 | 102 项通过；覆盖回调链、配对 offset 冲突、孤立成员、预算、取消、迟到/错误 ACK、内容冲突回滚、PK 双成员回滚及成员计数 | 单测不是 MySQL 故障恢复认证；PK 回滚用例使用 H2 |
| 真实 MySQL 恢复套件 | 4 项通过：上面三组恢复 + 非事务 MyISAM 目标拒绝 | 未枚举全部故障点和 SDK flush 分支 |
| 独立 `verify.py` | 校验归档文件摘要，重新逐字段对账，核对原始 SourceRecord 与完整目标成员证据、ACK 连续前缀、J/D 与审计预期 | 离线复核没有再次启动 MySQL，不能代替真实运行 |
| `pekko-server / test` 全模块检查 | 414 项通过，两个 Calcite 套件中止，整体命令失败 | 不能称全库测试全绿 |

全库中止分别是 `MysqlSqlParserSpec` 缺少 `com/google/protobuf/GeneratedMessageV3`，以及 `ComprehensiveCalciteTest` 无法初始化 `ConnectionPropertiesImpl`。本轮没有修改 Calcite 或依赖。全库检查在最后一次 ACK 绑定收紧之前执行；最后改动后重新执行了全部 102 项相关检查及全部 4 项真实 MySQL 检查。完整测试门仍为 `evidence_incomplete`，原始日志一并归档。

最初一次真实运行因 stdout/stderr 交错造成 JSON 取证解析失败，未计为 PASS；分离 stderr 后使用新 schema 重跑。最终归档只收录 source-manifest 指定的三组正式恢复运行。

## 6. 本轮未验证与未实现的边界

- 新的快照初始化、快照到 streaming 的通用衔接、从已经漏掉 CREATE 的旧 D 自动修复、旧账本/旧 J 的透明迁移。
- DDL、Schema 演进、多 owner fencing、多 writer、共享读取、跨表/源事务原子性、跨目标提交。
- 切主、GTID 谱系变更、binlog reset 或日志已清理；server UUID 不能覆盖这些历史语义。
- 此轮没有在原始 A ACK 已 flush、B 尚未调用之间再次暂停生产作业；也没有注入 COMMIT 回复丢失、Journal 插件写失败、SDK flush 超时或磁盘故障。此前 Reader 探针的相关事实不能自动升级为这些生产窗口的结果。
- 非事务触发器副作用、外部写目标、删改组账本、去重证据 TTL、安全压缩与长时间反复重启。样本中的审计触发器和审计表均在 MySQL 事务内。
- 吞吐、70 万表规模、整个 SDK/JVM 内存上限。本轮只验证单表恢复协议。
- 本次 JVM 明确使用 UTC；已有 DATE/DATETIME 转换在其他 JVM 默认时区下的问题没有修复，也未据此宣称所有类型/时区均可正确转换。

因此，当前结论是：**在上述单 writer、固定 Schema、可信 streaming 基线和固定日志历史的契约内，这份生产实现通过了指定重新分批与 T/J 缺口的真实恢复样本。** 其余窗口保持未验证。

## 7. 重跑与复核

先准备独立、loopback 可访问的临时 MySQL 8.4，开启 ROW/FULL binlog、唯一 server-id、GTID；账号只用于这套隔离实验。测试创建 `pekko_test_cdc_window_<随机后缀>`，保留 schema 和原始材料，不操作已有业务库。密码通过环境变量提供。

```sh
export RUN_MYSQL_CDC_GROUP_RECOVERY=1
export CDC_WINDOW_MYSQL_HOST=127.0.0.1
export CDC_WINDOW_MYSQL_PORT='<临时端口>'
export CDC_WINDOW_MYSQL_USER='<实验账号>'
export CDC_WINDOW_MYSQL_PASSWORD='<仅实验用密码>'

sbt -java-home /Users/xujiawei/Library/Java/JavaVirtualMachines/ms-17.0.14/Contents/Home \
  -J-Xmx3G 'project pekko-server' 'Test / compile' 'export Test / fullClasspath'

/Users/xujiawei/Library/Java/JavaVirtualMachines/ms-17.0.14/Contents/Home/bin/java \
  -Duser.timezone=UTC -Xmx1G \
  -cp "$(cat pekko-server/target/streams/test/fullClasspath/_global/streams/export)" \
  org.scalatest.tools.Runner -o \
  -s cn.xuyinyin.magic.workflow.integration.RealMySQLCdcGroupRecoverySpec
```

仓库默认 `test` 排除 `ExternalIntegration`，因此真实实验用 ScalaTest Runner 显式执行；不能看到普通 `test` 成功就认为真实 MySQL 用例跑过。生成材料在 `target/cdc-group-recovery/<场景-随机后缀>/`。

只核对这次保留的证据，无需启动数据库：

```sh
(cd docs/experiments/2026-10-19-group-recovery && shasum -a 256 -c SHA256SUMS)
python3 docs/experiments/2026-10-19-group-recovery/verify.py
```
