# 真实 MySQL SourceRecord 身份实验：分批不改变身份，但主键修改暴露 ACK 边界

日期：2026-10-19（Asia/Shanghai）。本次完成前一轮方案的第一步，只新增测试设施与证据。**实验没有修改生产恢复协议，没有运行新的目标事件账本；文章与测试材料随后提交到独立文章分支，网站发布另行进行。**

结论分两部分：

1. 在本次固定源日志谱系、单表、固定 Schema、Debezium 3.6.1.Final 的 streaming 样本中，`源谱系 + db + table + file + source.pos + source.row + op` 对重启、拆批、合批保持稳定，并能区分测试中的全部 39 条逻辑记录。
2. 主键修改产生的 DELETE 和 CREATE 共享物理行坐标，也共享完整 restart offset。仅确认 DELETE、持久化 D 后重启，CREATE 没有再次交付。**事件身份可区分，不代表每条逻辑记录都可以独立推进源恢复点。**

因此，本轮不把候选元组冻结为通用生产规范。下一阶段必须把事件身份与 ACK 分组一起设计。

## 1. 固定环境与证据层级

| 对象 | 实际值 |
|---|---|
| 项目 HEAD | `436056d8608e0d2d27ddc24988005cf70a42ff4d` |
| 生产基线 | 相关 `pekko-server/src/main`、`build.sbt` 与 HEAD 无改动；沿用 `d88ec80` 的协议 |
| MySQL | 新建隔离容器，8.4.11，ROW / FULL / GTID ON，InnoDB |
| 镜像 | `sha256:cc52b8b5900d106bc482241091cfa7496d830280868e19aca8a24e8e7086568e` |
| Debezium | 3.6.1.Final；从实际 SourceRecord 的 `source.version` 也可核对 |
| Scala / JVM | Scala 2.13.12 / Microsoft OpenJDK 17.0.14，子 JVM 固定 UTC |
| 正式完整运行 | `eff08bdb5c78`，端口 32792，9 个独立 JVM |
| 数据 | 初始 64 行，`id,label,revision` 三列；核心变更在同一源事务中 |
| 数据库状态 | 真实 JDBC offset/history；没有导入、恢复或直接修改 offset 行 |

探针调用生产 `MySQLCdcSourceNode.connectorProperties` 和 `DebeziumEngineFactory.real`，使用真正的 `RealDebeziumEngineAccess` 与 `CdcBatchCommitHandle`。保持 `ORDERED`、一个处理线程、`tombstones.on.delete=false`、`snapshot.mode=initial`、`offset.flush.interval.ms=0`。

**观测边界是 Engine 的 SourceRecord 回调。** 探针没有启动生产 Source Stream、Bridge、Actor、Journal 或 Sink。它用测试 consumer 保存记录，并有意不确认指定窗口内的交付，使真实 D 停在受控前缀；consumer 可以继续观察后续回调。这与生产 Bridge 等待每次 ACK 的行为不同，不能将本结果直接标为完整作业恢复测试。

没有拆分、合并或伪造回调内容，没有手工构造 SourceRecord，也没有根据目标表重建事件。批次改变来自实际 Debezium `max.batch.size` 的变化。

## 2. 怎样固定历史和恢复点

先运行 initial snapshot，观察 64 行。随后更新 id=64 为 streaming marker，等待真实 JDBC offset 等于该 marker 的完整 `sourceOffset`，再 ARM。初始快照不计入 streaming 身份证明。

ARM 之后，一次 SQL 事务执行：

- id=1 连续四次更新：`base → repeat → base → repeat → finish`。其中第一次与第三次的 before/after 内容完全相同，但属于不同发生。
- 一条 UPDATE 修改 id=2…33，共 32 行。实际记录证明它们位于同一个物理多行事件。
- 主键 id=50 改为 id=150，同时修改字段。
- id=60 更新为 sentinel，作为观测完整历史的终点。

得到 39 条逻辑记录：4 + 32 + DELETE/CREATE 两个分量 + 1。所有记录的 `source.gtid` 相同，证明核心负载属于同一源事务；GTID 单独不能区分事务内记录。

每次首次窗口回调进入闸门后，记录真实批次并等待 RELEASE。所有记录写入 JSONL 并 fsync。观察到 sentinel 后，控制端发送 CRASH，子进程以 `Runtime.halt(86)` 退出。恢复使用同一 connector、相同 offset/history 表和另一个 JVM。

前四轮不确认任何窗口记录，检查每次退出前后 D 都等于基线。没有用恢复点覆盖来制造重放。

另有两次有意推进前缀：

1. `advance-prefix-10` 使用真实单条回调，确认前 10 条：四次同键更新及多行事件的前六行。真实 D 的 `event=10,row=6`，下次恢复从物理事件 `source.row=6` 开始。
2. `pk-advance-delete` 从上述 D 开始，确认后续 27 条，恰好停在主键 DELETE。真实 D 的 `event=12,row=1`。CREATE 与 sentinel 已被探针观察，但没有调用确认。

仅声明 ACK 调用成功不够；两次都等待真实 offset 表内容等于最后已确认记录的完整 offset，再终止 JVM。所有 D 的 before/after 副本均保留。

## 3. 实际回调与身份对比

下表中的分组来自 `records.jsonl`，不是配置值推定。

| 进程 | 实际窗口回调 | 记录数 | 结果 |
|---|---|---:|---|
| original-16 | `[16,16,7]` | 39 | 建立原始事件序列 |
| restart-16 | `[16,16,7]` | 39 | 全部身份及规范化内容相同 |
| split-1 | 39 个 `[1]` | 39 | 拆批后全部相同 |
| merge-64 | `[39]` | 39 | 合批后全部相同；覆盖 split-1 中全部单条交付 |
| advance-prefix-10 | 39 个 `[1]`，只确认前 10 条 | 39 | 内容相同，建立真实事务内恢复点 |
| inside-split-1 | 29 个 `[1]` | 29 | 等于原历史第 11～39 条 |
| inside-merge-64 | `[29]` | 29 | 同一后缀合批后相同 |
| pk-advance-delete | 29 个 `[1]`，只确认前 27 条 | 29 | 内容仍相同，建立 DELETE 后的 D |
| pk-component-resume | `[1]`，只有 sentinel | 1 | **未确认的 CREATE 未再交付** |

前八行中，共比较事件身份、有序序列和完整生产规范化 Envelope。被比较事件的 `source` 元数据、`sourceOffset` 都相同。恢复后的顶层处理时间 `value.ts_ms` 则每条都与原观测不同：不能对整个原始 value 直接求哈希来标识源事件。

`source.ts_ms` 是另一个字段，本次随源事件保持不变。不可把两个同名时间层级混用。

### 字段消融提供的反例

| 候选缩减 | 实际观察 | 对身份设计的影响 |
|---|---|---|
| 去掉 op | 39 条记录只有 38 个不同元组 | 主键 DELETE/CREATE 碰撞 |
| 去掉 source.row，保留 op | 39 条记录只有 8 个不同元组 | 32 行物理事件无法区分 |
| 只看主键 | id=1 有四次合法更新 | 不能区分同键的多次发生 |
| 只看 before/after 内容 | 第一次和第三次更新内容完全相同，pos 不同 | 内容相同不代表同一事件 |
| 只看 GTID | 39 条逻辑记录共享一个事务 GTID | 缺少事务内定位 |

完整元组在本次 39 条记录中没有碰撞，并在上表的完整/后缀重放中稳定。这个结论属于实际样本，不是对所有 MySQL 历史的唯一性证明。

## 4. 新发现：主键修改的两个分量不能分别视为安全恢复点

正式运行中，两个分量的字段如下：

| 字段 | DELETE | CREATE |
|---|---|---|
| key.id | 50 | 150 |
| op | d | c |
| source.file | mysql-bin.000003 | mysql-bin.000003 |
| source.pos | 24040 | 24040 |
| source.row | 0 | 0 |
| restart offset pos / event / row | 21604 / 12 / 1 | 21604 / 12 / 1 |
| header | `__debezium.newkey={id:150}` | `__debezium.oldkey={id:50}` |

注意 `source.pos=24040,row=0` 与 restart offset 的 `pos=21604,event=12,row=1` 含义不同。

测试只确认 DELETE 后，保存到 D 的 offset 已表示跳过这个物理行。新 JVM 的日志出现 `Skip 1 rows on streaming start`；实际交付直接来到 sentinel，CREATE 没有重放。这里同时保留原进程未确认 CREATE 的批次、D 内容、恢复日志和恢复记录，不靠“读了一会没看到”作结论。

**实际结论是读取器从这个 D 不再交付该 CREATE。** 本次没有目标写入，不能直接声称真实目标缺 id=150，也没有证明现有完整 Actor/Bridge 链路一定能以相同方式触发这个窗口。完整作业验证是下一阶段工作。

### 固定源码对结果的解释

上游统一固定到 Debezium 3.6.1.Final 提交 `63371830ceff75437f95c775b91eeeaf55a056c1`：

- [`RelationalChangeRecordEmitter.emitUpdateAsPrimaryKeyChangeRecord`](https://github.com/debezium/debezium/blob/63371830ceff75437f95c775b91eeeaf55a056c1/debezium-connector-common/src/main/java/io/debezium/relational/RelationalChangeRecordEmitter.java#L172)取得一次 offset/sourceInfo，分别发出 DELETE 与 CREATE，并添加关联 key headers。
- [`BinlogOffsetContext.setRowNumber`](https://github.com/debezium/debezium/blob/63371830ceff75437f95c775b91eeeaf55a056c1/debezium-connector-binlog/src/main/java/io/debezium/connector/binlog/BinlogOffsetContext.java#L287)把当前物理行转换成恢复时应跳过的行数。
- [`BinlogStreamingChangeEventSource`](https://github.com/debezium/debezium/blob/63371830ceff75437f95c775b91eeeaf55a056c1/debezium-connector-binlog/src/main/java/io/debezium/connector/binlog/BinlogStreamingChangeEventSource.java#L1170)从 startingRowNumber 继续处理物理行，而不是从逻辑 DELETE/CREATE 的中间继续。

这是源码解释，与上面的实际观察分开计量。[源码 URL 与哈希](source-manifest.json)用于复查。在线 [3.6 文档的主键更新说明](https://debezium.io/documentation/reference/3.6/connectors/mysql.html#mysql-primary-key-updates)描述 DELETE/CREATE 和关联 headers；该在线分支页面当前显示更新版本，具体行为仍以固定源码及本次 3.6.1 记录为准。

## 5. 对下一步身份规范的约束

可以保留为待实现候选的是：

```text
命名空间：replicationId + generation + identityVersion
逻辑事件：受验证的 sourceLineage + db + table + file + source.pos + source.row + op
确认分组：同一物理行及其全部必要逻辑分量
```

本次 sourceLineage 在隔离实例中由固定 server_uuid 和未重置的日志历史约束。**server_uuid 不是完整谱系协议**：同一 UUID 下重置 binlog、恢复旧备份或更换源都不在证明内。命名空间、generation 和 identityVersion 也尚未写入生产。

下一阶段不能只实现“每条 eventId 命中账本就单独 ACK”。主键 DELETE 与 CREATE 应在所有必要分量都获得覆盖后，才允许推进共同恢复点。`op` 解决逻辑身份碰撞，关联 headers 和分组边界解决完整性，这两件事职责不同。

这也修正了上一轮最小方案对 Bridge 的假设：生产 Bridge 会等待一个回调 ACK；当两个分量落在不同回调中，仅仅“暂缓 DELETE 的 ACK 等 CREATE”可能阻止后续回调抵达。下一方案必须明确成组交付、缓冲和确认如何衔接，而不是直接套一个等待条件。**这条生产链路影响是源码推演，尚未实施或做完整作业验证。**

仍未验证：快照中断、DDL、复合主键、所有数据类型、多个源分区、主库切换、日志轮转/重置、事务压缩、不同 Debezium 版本、任意旧恢复点、owner fencing、目标事务、Actor Journal、新事件账本、端到端逐字段一致性和副作用恰好一次。

## 6. 文件、复跑与核验

- [测试探针](../../../pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sources/RealMySQLSourceRecordIdentityProbe.scala)：只在 test 源集中编译。
- [run.py](run.py)：创建独立容器、初始化负载、启动各个 JVM、控制 ACK 前缀并保存结果。
- [verify.py](verify.py)：只读重算保存的原始记录和 D，不启动数据库。
- [正式结果摘要](result.json)、[上游源码清单](source-manifest.json)。
- [完整证据包](evidence.tar.gz)、[证据包哈希](evidence.tar.gz.sha256)、[本目录文件哈希](SHA256SUMS)。

前置条件：Docker 可用，已取得 `mysql:8.4`，本项目 SBT 依赖已配置。实验启动自己的 loopback MySQL，创建新的唯一库，不访问用户业务库；完成后停止自己的容器并保留数据库。默认 JDK 路径属于本机，可用 `IDENTITY_JAVA_HOME` 指定 JDK 17。

从仓库根目录执行：

```sh
sbt -java-home /path/to/jdk17 -J-Xmx3G \
  'project pekko-server' 'Test / compile' 'export Test / fullClasspath'
IDENTITY_JAVA_HOME=/path/to/jdk17 \
  python3 docs/experiments/2026-10-19-source-record-identity/run.py
```

脚本打印的 `IDENTITY_PROBE_PASS` 表示身份断言完成，且 ACK 边界观察被记录；**不是 CDC 恢复协议通过**。`verify.py` 对本次归档明确验证 CREATE 缺席这个反例。若将来版本改变行为，应重新解释结果，不应为了匹配旧报告修改读取记录。

只读复查示例（在新的空目录中解压证据包）：

```sh
python3 docs/experiments/2026-10-19-source-record-identity/verify.py \
  /path/to/extracted/eff08bdb5c78
```

本轮实际检查：SBT Test/compile 成功；正式 9 JVM 实验成功完成；独立 verify.py 对序列、规范化内容、D 的实际前缀、碰撞反例及 CREATE 缺席复算通过。没有运行整个项目测试套件，也没有把源码编译或只读复算计作新增端到端实验。

## 7. 失败记录与材料保留

- `a5629695fd7a`：初次容器就绪检查走 Unix socket，临时初始化服务提前满足 SELECT 1，随后服务退出导致下一次查询失败；尚未启动 SourceRecord 探针。改为 TCP 连接正式服务后排除该竞态。保留原失败信息与容器日志，不计为身份测试失败或成功。
- `21383b2ceb64`：第一组 7 JVM 正式身份检查通过，随后从原始记录发现主键两个分量 offset 相同。
- `eff08bdb5c78`：在新隔离实例重跑原检查并增加 DELETE 后 D 的补充边界，共 9 JVM；本文表格和结论以这一组为准。第一组是重复验证，不另累计独立覆盖。

归档保存全部运行记录、回调、D、核心 SQL、源表终态、编译日志、驱动日志、正式运行的测试源码与脚本副本，以及各文件哈希。JSONL 中保存 key/value schema、sourcePartition、完整 sourceOffset、原始 value、headers 和规范化 Envelope。宿主路径和隔离数据库的演示参数不是可移植的生产部署配置。

后续[延迟确认探针](../2026-10-19-deferred-ack/README.md)复用本探针的原始记录编码函数，仅将该测试函数改为包级可见。身份实验归档保留实际采集时的源码；不能要求它与后续可见性调整后的文件哈希相同。
