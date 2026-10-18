# MySQL CDC：目标已提交、Actor Checkpoint 未持久化的真实恢复实验

2026-10-18。本次实验只增加测试设施，没有修改生产恢复协议。实验完成时尚未提交；本报告与测试设施随后纳入[项目 CDC 设计系列](../cdc-design/README.md)的提交范围。

**实际结果：完整批次重放可以恢复；恢复批次从 16 条拆成 1 条时，复用了相同 batchId，真实 Sink 拒绝账本冲突，真实 Actor 持久化失败状态，后续数据未能追平。** 这是恢复停机的复现，不是已经证明 Reader 静默丢弃事件，更不是修复完成。

## 环境与测试入口

| 项目 | 实际值 |
|---|---|
| 原生产代码 | `ad58cf3`；完整 SHA、测试源码 SHA256 见证据包 `manifest.json` |
| MySQL | Docker `mysql:8.4`，实际 8.4.11；ROW / FULL / GTID ON |
| MySQL 镜像 ID | `sha256:cc52b8b5900d106bc482241091cfa7496d830280868e19aca8a24e8e7086568e` |
| Pekko / Debezium | 1.1.3 / 3.6.1.Final |
| JVM | Microsoft OpenJDK 17.0.14；正式实验子 JVM 显式 UTC |
| 拓扑 | 单活进程，真实 MySQL Source、Sink、JDBC Actor Journal；跨两个 JVM 恢复 |
| 数据 | 每组独立数据库；64 行、6 列；主键、Unicode、空格、NULL/空串、DECIMAL(18,4)、DATETIME(6)、修订号 |
| offset 刷盘 | `offset.flush.interval.ms=0`；仍然在目标提交到 Actor 持久化之间形成窗口 |
| Actor 恢复 | 同一 workflowId、persistenceId、executionId；从 Journal 重放，不从目标账本构造恢复 Checkpoint |

测试文件：

- [子进程、真实 Source/Sink 观测与提交后暂停](../../pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/integration/RealMySQLCdcJournalWindowProcess.scala)
- [实验编排、数据库证据、逐字段对账与断言](../../pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/integration/RealMySQLCdcJournalWindowSpec.scala)

所有注入均位于 `src/test`。子进程直接创建真实 `EventSourcedWorkflowActor` 和 `WorkflowExecutionEngine`；没有替换 `ReliableRunContext`、Actor 持久化回调或恢复状态。Actor 快照阈值设为 100000，本轮未触发 Snapshot Store；本轮验证的是 Journal 重放，没有验证 Sharding 接管。

## 怎样固定故障窗口

先正常完成初始快照和一条 streaming 标记变更。全表对账相等，并确认目标账本 T、真实 Journal J、实际 offset store D 和 Source ACK 记录一致之后，才 ARM 故障切点。

测试 Sink 子类调用 `super.commitBatch`。这个 Future 成功意味着生产 Sink 已完成 MySQL 事务提交。随后测试子类输出 `CUTPOINT`，返回一个不完成的 Promise。生产 Engine 因此停在 Sink 返回边界，尚不能调用 `checkpointCommitted`，也不能确认源批次。

父进程在这个稳定窗口中读取：

- T：真实 `pekko_sync_batch_ledger` 全部记录及目标数据。
- J：通过真实 Actor 查询状态，并使用 Pekko serializer 反序列化 MySQL `event_journal.event_payload`，核对最新 `ExecutionCheckpointAdvanced`。
- D：该 connector 的真实 JDBC offset 表原始 key/value。
- Source/Sink：完整批次、完整规范化事件、完整末尾 sourceOffset、批次 ID、确认调用和 Sink 结果。

断言 T 比 J 多一批，CUTPOINT 中确实是本轮 revision=1 的业务变更，且该 batchId 没有 `ACK_BEGIN`。之后通过 `Runtime.halt(86)` 强制结束 JVM，不执行优雅关闭。再次读取 D，确认与故障切点一致。

新 JVM 使用相同配置和 Journal 自动恢复原执行，记录 `createBatches` 收到的 Checkpoint，并与故障前 Journal 中的值逐项比较。两个场景都观察到不同进程 PID 和不同 Journal writer UUID，但执行 ID 未变。

## 两个对照的实际结果

下表 T/J 的数字均为业务 `batch_sequence`，不是 Journal 的 `sequence_number`。

| 检查项 | 完整批次重放 | 拆批重放 |
|---|---|---|
| 证据目录 | `verified/exact-replay-256621e3d9a6` | `verified/split-replay-e0fec7eecef7` |
| 原进程 / 恢复进程 PID | 88833 / 89042 | 89353 / 89418 |
| 故障切点 T / J | 6 / 5 | 5 / 4 |
| 原批次 / 恢复首批业务记录数 | 1 / 1 | 16 / 1 |
| 恢复首批 batchId | 与原批次相同 | 与原批次相同 |
| Sink 结果 | `AlreadyCommitted` | `conflicting MySQL CDC ledger identity` |
| 恢复后 Actor 状态 | running，继续处理新数据 | failed，Journal 有 `ExecutionFailed` |
| 最终 T / J | 9 / 9 | 5 / 4 |
| 最终源 / 目标行数 | 64 / 64 | 64 / 64 |
| 缺失 / 多余主键 | 无 / 无 | 缺少 65 / 多出 64 |
| 共同主键上的字段差异 | 0 | 216，涉及 48 行 |

### 完整批次重放

原批次只有一条 UPDATE。恢复时使用真实 Debezium 的 `max.batch.size=1`，实际收到相同事件、相同 cursor 和相同 batchId：

```text
50e99579f48e916e6a2a19bf8da8e2ea92e8d544c5e48c6b0707e4f8b762582d
```

Sink 返回 `AlreadyCommitted`。Actor 将 J 补到 6，然后确认源批次。本轮没有修改协议；这是现有协议在批次保持一致时的行为。

目标表的事务审计记录数依次为：

```text
基线 65 → 原批次提交后 66 → 重放后 66 → 后续三个变更后 69
```

审计使用隔离目标表的 AFTER INSERT/UPDATE/DELETE 触发器，与目标 DML 同事务。重放后未增加审计记录，说明该批没有再次执行目标业务 DML。随后更新 id=1、删除 id=64、插入 id=65，最终 64 行 × 6 字段全部一致，T/J 都到 9，D 与最后确认的 sourceOffset 一致。

较早的 UTC 对照 `utc/exact-replay-c5dac7252a9a` 也观察到相同行为，作为补充证据保留。

### 16 条拆成 1 条

原始阶段允许 `max.batch.size=16`，对 64 行执行一个 UPDATE，切点仅在实际回调至少包含两条业务记录时触发。本次实际暂停批次包含 16 条记录。暂停期间再更新 id=1、删除 id=64、插入 id=65，制造可判定的待同步数据。

恢复 JVM 的测试 Engine factory 只把真实 Debezium 属性 `max.batch.size` 改为 1。没有手工拆分、拼接、生成或删除事件，也没有改写 offset。实际恢复首批只有一条，且完整规范化事件与原 16 条中的第一条一致。

两批共享：

```text
batchId = 837cc2dd6a2f0627a151956ca59ba2c2be635574ffed6dacd1d7110213b65c24
batchSequence = 5
```

但用于账本比较的内容不同：

| 字段 | 已提交批次 | 恢复首批 |
|---|---:|---:|
| sourceRows / targetRows | 16 / 16 | 1 / 1 |
| cursor.offset.file | mysql-bin.000003 | mysql-bin.000003 |
| cursor.offset.pos | 1390884 | 1390884 |
| cursor.offset.event | 2 | 2 |
| cursor.offset.row | 16 | 1 |

这些 cursor 字段是 Debezium 恢复状态，不能只取 pos 判断两批相同。完整 map、GTID 集合和各条事件保存在 JSON 中。

故障前 D 的 `pos=1385902,event=2,row=1`；故障后与失败终态的 D 均保持同一完整值。真实 Source 从 J=4 生成序号 5，真实 Sink 找到 T 中序号 5 的旧账本，比较失败，拒绝继续。目标审计记录保持 **81 → 81**，没有观察到此次重放产生新的目标 DML。

最终字段差异分布：amount 48、label 48、note 25、revision 48、updated_at 47，共 216 处。源、目标均为 64 行，但缺少新行 65，仍保留应删除的行 64。这直接说明只核对行数不足以验收。

**这些差异代表故障后未能追平，不代表重放新增了 216 处数据污染。** 原始 64 行 UPDATE 在目标端只提交了首批 16 行，之后还有待应用日志；暂停期间也继续提交了三个源变更。失败恢复使这些差异持续存在。没有验证数据永久不可恢复，也没有通过修改账本或协议让该作业继续。

## 全量逐字段对账方法

源、目标是同一隔离 MySQL 中的不同表。比较器直接通过独立 JDBC 连接，在 REPEATABLE READ 事务中读取两张完整表；不复用 CDC 的 JSON 转换函数。按主键匹配所有行，逐一比较 `id,label,amount,note,updated_at,revision`。

使用相同表结构和 JDBC `getString` 取得数据库列值，DECIMAL 保持十进制文本和 scale，NULL 与空串区分，保留 Unicode、空格和 DATETIME 微秒。本轮未覆盖所有 MySQL 类型。比较报告保存源、目标全量数据、缺失键、多余键和每一个字段差异。

正式最终比较时测试源已停止变化；所有业务表查询都没有加 ID 范围过滤。结果文件中的 `comparison.equal` 与复制流水线的运行状态分别记录，不能互相替代。

## 前置失败与证据边界

本次没有隐藏前置失败：

1. 默认 JVM 时区的两次基线都失败：64 行 DATETIME(6) 从源 `2026-10-18 01:02:03.654321` 写成目标 `2026-10-17 17:02:03.654321`，偏移 8 小时。完整 SQL 导出和字段差异保留在最外层两个实验目录。正式实验把子 JVM 固定为 UTC，没有修改生产时间转换代码；这个独立问题仍未修复。
2. 第一轮 UTC 的拆批用例未进入故障窗口：Debezium 首批输出出现在 PROCESS_READY 后约 58 秒，原 60 秒基线等待超时，采集时目标只有 62/64 行，已存在的行字段相等。保留在 `utc/split-replay-cf827a17d120`。之后仅将初始快照等待上限增加至 180 秒，未降低全量一致性标准，并增加 ARM 前 T/J/D/ACK 一致屏障。
3. 最终两个测试断言均通过，表示上面的成功对照和失败复现都成立。不是修复后的正确性验收，也不是整个项目测试套件通过。

未覆盖：初始快照故障恢复、Actor Snapshot Store、多节点/Sharding/网络分区接管、Sink COMMIT 回复丢失、Schema/DDL、Paimon、源主库切换、binlog 清理、任意旧 Checkpoint 回滚、任意批次合并、所有数据类型。拆批由显式修改测试进程的真实 Debezium 批大小实现；本轮没有证明相同配置下自然拆批的发生频率。没有验证源事务整体在目标端原子可见。

## 复跑与证据

在新的、可丢弃的本地 MySQL 中启用 ROW/FULL binlog，为测试账户提供该隔离实例的建库、读写、复制和元数据权限。测试仅接受 loopback 地址，每个场景创建唯一的 `pekko_test_cdc_window_*` 数据库，不删除现有数据。测试结束保留数据供审查。

本次使用容器 `pekko-cdc-journal-window-20261018`，端口 `127.0.0.1:32787`。可重新启动这个保留的实验容器后执行：

```bash
RUN_MYSQL_CDC_JOURNAL_WINDOW=1 \
CDC_WINDOW_MYSQL_PORT=32787 \
CDC_WINDOW_MYSQL_USER=cdc_window \
CDC_WINDOW_MYSQL_PASSWORD=local-window-fixture-only \
CDC_WINDOW_EVIDENCE_DIR=target/cdc-journal-window/rerun \
sbt -java-home /Users/xujiawei/Library/Java/JavaVirtualMachines/ms-17.0.14/Contents/Home \
  'project pekko-server' \
  'set Test / testOptions := Seq()' \
  'testOnly cn.xuyinyin.magic.workflow.integration.RealMySQLCdcJournalWindowSpec'
```

上述密码只是本轮新建隔离容器的测试值。默认构建排除 `ExternalIntegration`，这里临时清空排除参数并只选择这一项 suite，不会运行其他外部集成测试。

- [完整证据包](evidence/2026-10-18-cdc-journal-window.tar.gz)
- [证据包 SHA256](evidence/2026-10-18-cdc-journal-window.tar.gz.sha256)

证据包包含各轮 `environment.json`、原进程/恢复进程日志、T/J/D、完整行数据和字段差异、事务审计、数据库 SQL 导出、执行日志、测试源码副本与哈希。`verified-test.log` 记录最终 `2 succeeded, 0 failed`。`manifest.json` 记录生产代码差异为空。

目前可以据此确认：现有批次身份把回调分组当成恢复后不变的边界，完整重放和拆批重放会走出不同结果。下一步应针对这个已经复现的窗口讨论身份与恢复协议；本轮没有实施那个改造。
