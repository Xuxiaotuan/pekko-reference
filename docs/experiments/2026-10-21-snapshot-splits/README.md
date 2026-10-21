# 单表快照 Split：分页与独立恢复

本目录保留首次实现时的材料。后续整体审查发现并修复了在途 T→J 排空竞态，最终源码及重跑结果见 [提交前审查](../2026-10-21-snapshot-splits-review/README.md)。以下计数与源码摘要属于首次实验，不能用于宣称修复后版本已重跑。

首次实验的源码基线 `9881fa26a75d625cb96ff44589ba67ea1a939aab` 加本轮未提交工作区。实验运行时尚未提交或推送；本目录不记录后续 Git 操作。未部署生产服务。运行的 237 个源码/资源/构建文件已冻结并保存摘要，包中同时保留基线与最终源码。

本轮完成显式启用的 `mysql.snapshot → mysql.write` 单表路径：实际主键分片、片内 keyset 分页、每片独立 J、目标批次内容摘要和同 execution 最多三次局部恢复。没有扩展跨表调度、70 万表、在线增量快照或现有 CDC 恢复协议。

启用方式、SQL 资源和限制见 [使用说明](../../guides/mysql-snapshot-splits.md)，审查修复及前期失败见 [审查记录](review.md)。

## 源码结论

- 分片先持久化再执行；单窗口最多 16 片，窗口全部完成才归档和规划下一窗口。已保存范围、分页参数、revision 和配置指纹在同 execution 内冻结。
- 片内序号与游标独立，目标数据、旧批次账本和新增摘要证据同事务。T 确认后保存该片 J；EOF 后还需要持久化完成事件。已经完成但回复丢失的片不会重新读取。
- 目标/进度结果不确定先确认旧资源释放再恢复；明确拒绝、摘要冲突、额度耗尽及释放未知保留上下文暂停。释放未知关闭新准入并保留 Engine fence。
- 新路径使用独立的 JDBC 游标、超时和目标连接配置。未启用 splitRows 的旧路径保留；15 个 CDC/Checkpoint/故障模型源文件字节未改。
- 这里的快照没有 Debezium offset，D 为 N/A。现有 CDC 仍为 T → J → 原始回调前缀 ACK，D 取实际 offset 表。没有用一个全局最大序号替代各片进度。

以上是源码机制，不等同于所有故障或生产高可用保证。

## 实际验证

| 检查 | 本轮结果 |
|---|---|
| 完整后端 | 515/515，88 套件、0 中止；最终测试 JVM 实际 4 GiB |
| 局部行为、资源与兼容 | 57 项通过；旧 18 份 CBOR 仍按原 manifest 解码且字节重编码一致；新增非空状态/事件 CBOR 往返通过 |
| JAR | 打包通过，含 Split 运行类和显式建表资源，保留原 WorkflowState FQN，不含新实验探针/测试类 |
| 真实单表 Split | 20 项通过：14 项收敛、6 项安全暂停；暂停不算数据一致 |
| 原 CDC 真实回归 | 27 项通过：10 项有界自动收敛、7 项安全暂停、7 个历史恢复窗口收敛、3 项释放/Stop 检查 |
| 历史保全 | 181 个既有实验文件 SHA-256 均未变，旧 C2 失败材料保留 |

实际环境为 MySQL 8.4.11、Java 17、Pekko 1.1.3、Connector/J 8.0.33。原 CDC 回归使用项目的 Debezium 3.6.1.Final。数据库配置在运行后另行观察为 flush=1、sync_binlog=1；这不是服务器断电实验。

每个非空场景使用 65 行、六字段，包含负数/稀疏主键、9007199254740993、中文与 emoji、DECIMAL(18,4)、NULL/空字符串/空格、DATETIME(6)。全量比较全部主键和六个字段，同时检查目标触发器审计；不是只比较行数或总哈希。WINDOWS 用 22 片覆盖两个窗口，观察窗口最多 16 片、Reader 会话最多 2；这不是数据库全局物理连接数或内存容量证明。

| 场景 | 结果 | 源 / 目标行数 | 相同主键字段差异数 | 最大恢复次数 |
|---|---|---:|---:|---:|
| BASELINE | 逐字段一致 | 65 / 65 | 0 | 0 |
| CHANGED | 保留上下文暂停 | 65 / 60 | 1 | 1 |
| COMMIT_ABSENT | 逐字段一致 | 65 / 65 | 0 | 1 |
| COMMIT_LOST | 逐字段一致 | 65 / 65 | 0 | 1 |
| EMPTY | 逐字段一致 | 0 / 0 | 0 | 0 |
| EOF | 逐字段一致 | 65 / 65 | 0 | 1 |
| EOF_J_LOST | 逐字段一致 | 65 / 65 | 0 | 0 |
| J_LOST | 逐字段一致 | 65 / 65 | 0 | 1 |
| PAGE_BYTES | 保留上下文暂停 | 65 / 0 | 0 | 0 |
| PLAN | 逐字段一致 | 65 / 65 | 0 | 0 |
| QUERY_TIMEOUT | 逐字段一致 | 65 / 65 | 0 | 1 |
| REGISTER_BEFORE | 逐字段一致 | 65 / 65 | 0 | 0 |
| REJECT_J | 保留上下文暂停 | 65 / 60 | 0 | 0 |
| RETRY_EXHAUSTED | 保留上下文暂停 | 65 / 60 | 0 | 3 |
| SNAPSHOT_RECOVERY | 逐字段一致 | 65 / 65 | 0 | 1 |
| STOP_T | 逐字段一致 | 65 / 65 | 0 | 1 |
| TARGET_CLOSE | 保留上下文暂停 | 65 / 6 | 0 | 0 |
| T_J | 逐字段一致 | 65 / 65 | 0 | 1 |
| WIDE_PAGE | 保留上下文暂停 | 65 / 57 | 0 | 0 |
| WINDOWS | 逐字段一致 | 65 / 65 | 0 | 0 |

- PLAN / REGISTER_BEFORE：分别固定计划已持久化和下一范围尚未持久化的窗口，结束子 JVM，再由相同 persistenceId/execution 恢复。
- T_J：第一片首批实际目标 COMMIT 已返回，J 尚未请求；健康片继续完成。结束 JVM 后，未完成片重放并由目标账本仲裁，已完成片未重开，最终 65 条插入审计。
- EOF / EOF_J_LOST：分别覆盖完成事件写入前和已持久化但回复丢失；后者实际未增加重试次数、未重读该片。修复前误暂停材料留在探索证据中。
- J_LOST：真实 J 已保存但观测回复丢失，恢复从实际 J 继续。COMMIT_LOST / COMMIT_ABSENT 通过 MySQL 线协议代理分别验证实际服务器提交与未转发 COMMIT，两种情况最终逐字段一致。
- STOP_T：T 已提交时请求停止，放行在途页保存 J，确认资源关闭/owner 退出；新 JVM 同 execution 恢复并对账。
- SNAPSHOT_RECOVERY：同时存在非空 JDBC Snapshot Store 和 Journal 的进程恢复；保存 snapshot payload，另有非空状态 CBOR 测试。
- QUERY_TIMEOUT：真实查询注入 SLEEP(31)，约 30 秒后旧 Reader 关闭，持久化一次重试，健康片保留进度，最终对账一致。
- REJECT_J：明确拒绝后恢复次数为 0。RETRY_EXHAUSTED：达到 3 次后保留上下文暂停。CHANGED：故意违反静态源约束，重放时相同游标/行数但内容摘要不同，暂停并保留 1 个字段差异与缺行。
- PAGE_BYTES / WIDE_PAGE：分别验证极小页预算与 6 MiB 单字段被拒绝，未截断或重新分批。TARGET_CLOSE 注入的是实际池已关闭后观测返回异常，验证安全暂停及停止后续新准入；不是永久未释放连接的实验。

切点 T 与 J 分别查询，不构造虚假的全局原子快照；指定故障片的 gate 保证其关键窗口成立。最终对账在该用例停止写入后进行。通过有限窗口不能推广为在线增量快照、全表原子发布或全库 exactly-once。

## 材料与复核

[结果](result.json)、[冻结源码](source-manifest.json)、[包与全部文件摘要](archive-manifest.json)、[历史保全](historical-artifact-check.json)、[未改的 CDC 源文件](cdc-protected-source.json)、[JAR 检查](package-check.json)、[数据库设置观察](mysql-observed-settings.json)、[原始证据包](evidence.tar.gz)、[SHA256SUMS](SHA256SUMS)、[独立核验](validation.json)、[清理状态](cleanup.json)。

证据包保存 1252 个文件和 66 个本轮实验库的 SQL dump；包含 20 个正式快照场景、27 个原 CDC 场景的切点/最终数据、逐字段比较、目标账本、Journal 原始 CBOR、回调/ACK、审计和 SQL dump；包含本轮所有快照探索/失败材料及构建测试日志、基线/最终源码。探索材料与正式冻结源码分开标识，不把接口接线编译失败、测试数据构造失败或启动器堆参数错误当成生产协议失败。私有设置与实际数据库密码不打包。

在当前仓库内只读核验：

```sh
python3 docs/experiments/2026-10-21-snapshot-splits/verify.py --archived-source
```

核验器独立重算行/字段差异，解析保存的 Journal CBOR 检查最终 J 对应 T，并核对 T_J 切点没有提前推进指定片 J；也核对原 CDC 正向场景的实际 D 与 J。`--archived-source` 只核对这轮旧归档内的源码，不要求已修复的当前工作区回退；仍核对历史文件未变。它不会连接数据库，不能替代真实重跑。

真实重跑需准备独立 loopback MySQL，显式提供 CDC_WINDOW_MYSQL_HOST/PORT/USER/PASSWORD，并设置 RUN_MYSQL_SNAPSHOT_SPLITS=1。使用 Java 17：

```sh
sbt -J-Xmx4g 'project pekko-server' \
  'set Test / testOptions := Seq()' \
  'testOnly cn.xuyinyin.magic.workflow.integration.RealMySQLSnapshotSplitSpec'
```

这会新建随机 pekko_test_cdc_window_* 测试库。不要指向生产。原 CDC 三组重跑另需 RUN_MYSQL_CDC_RECOVERY_WINDOWS=1；类名仍为 RealMySQLCdcAutomaticRecoverySpec、RealMySQLCdcRecoveryWindowsSpec、RealMySQLCdcSessionReleaseSpec。

## 尚未验证

并发源写入/主键迁移/DDL 的一致性、全量到 binlog 切换、跨表/跨节点调度、多 owner fencing、网络分区、数据库掉电、真实永久阻塞的 JDBC close、源实例替换、其他 MySQL 字段类型、硬 JVM RSS 上限、混合版本升级/旧二进制降级和 70 万表容量。没有新增暂停后的人工继续/放弃 API；计划/枚举元数据回复丢失的自动重试也未全覆盖。

隔离 MySQL 在全部导出完成且确认没有其他客户端连接后恢复为原来的停止状态；测试库和卷未删除。
