# CDC 写入热路径、有界查询与依赖兼容性

2026-10-20。基础提交 `3e8e577`；实际运行版本由[源码清单](source-manifest.json)定位。本轮在 CDC 平台内完成三个局部改造，保留工作流、调度和历史实验。没有改变完整组身份、T/J/ACK 协议、Actor 持久化模型或 C2 失败终态策略。

## 1. 解决的具体问题

### 事务内语句复用

原来的完整组写入路径先转换全部事件，执行新组时再转换一次；每个组重新准备账本查询和插入，每条目标事件重新准备相同 DML。

现在先将每个完整组的事件转换一次，仍在任何账本写入前验证整个批次；`JdbcStatementScope` 在此次批次事务内复用相同 SQL。目标 upsert/delete、账本 SELECT/INSERT 仍逐条执行，组顺序、参数绑定和主键 DELETE/CREATE 的原子边界保留。所有语句在 COMMIT 前关闭；关闭失败仍尝试其余关闭，然后使事务回滚。

语句不跨批次、连接或执行保存；没有新增每表常驻连接池，也没有缓存目标 Schema。目标元数据与 InnoDB/账本校验仍逐批进行，没有扩大 DDL 保证。

### 查询准入与未结束 ask 的预算

原列表通过 `Future.sequence` 一次发出整页摘要 ask，多请求没有准入限制，ID 枚举也不受整体查询完成预算约束。

现在使用有序 `mapAsync`，并分别限制列表请求和未完成 ask。同一 QueryService 默认最多 4 个列表、每列表最多 8 个并发摘要、合计最多 32 个未完成 ask。整体流和单个 ask 默认各等 5 秒。

查询失败或超时只结束其流，已经发出的 ask 仍占额度，直到自身完成或超时。因此反复失败的列表不能提前归还 ask 额度，再堆积更多请求。拒绝返回 503，完成预算耗尽返回 504；原 `{page,pageSize,items}` 契约保留，不返回隐藏的部分成功页。

配置见[配置指南](../../CONFIGURATION.md#列表查询的资源上限)。上限按服务实例生效；当前 ID 枚举、深页丢弃与冷 Actor 激活仍存在，并不是独立任务目录或稳定快照分页。

### Protobuf 兼容性

原解析结果中的 `protobuf-java 4.26.1` 来自 Connector/J 的传递依赖，缺少 Avatica 旧生成代码需要的 GeneratedMessageV3，造成两个 Calcite 套件中止。

本轮固定使用 4.33.4，验证旧 Avatica 消息和 Connector/J 消息的序列化往返，并保留完整 Calcite 套件。没有降级 Connector/J，也没有修改 CDC 或 Journal 序列化。

版本依据：[官方 v33.4 发布](https://github.com/protocolbuffers/protobuf/releases/tag/v33.4)、[生成代码与 runtime 兼容边界](https://protobuf.dev/support/cross-version-runtime-guarantee/)。本次验证支持仓库所用生成消息，不代表所有第三方 Protobuf 消息或完整 MySQL X Protocol 服务端交互均已验证。

## 2. 实际验证与证据层级

| 检查 | 本轮实际结果 | 支持的范围 |
|---|---|---|
| 正确基线上的失败用例 | 未优化 Sink 的 64 条写入准备相同目标 SQL 64 次；断言 `64 != 1` | 实际 H2 Connection 调用计数；原始日志保留 |
| 事务语句检查 | 同一 64 条写入：目标语句准备 1 次、组账本查询准备 1 次、组账本插入准备 1 次；重放不再执行目标/账本插入 | 局部 JDBC/H2 检查，包含最终行、回滚、PK 组和关闭 |
| 查询固定窗口 | 每流并发、列表准入、整体超时、超时后 4 个悬挂 ask 继续占满测试预算、回复后恢复、初始化失败释放预算、分页顺序 | 测试配置为 2 个列表 × 2 个摘要；不是 32 或 70 万任务压测 |
| 真实 JDBC 目录 | 持久化三个工作流，通过实际 JdbcReadJournal 枚举和真实实体返回 revision；远页为空 | H2 Journal 集成；不是 MySQL 目录规模测试 |
| HTTP 边界 | 列表超载/超时分别为 503/504 | 路由测试；没有重新做浏览器压测 |
| 最终针对性回归 | 67 项通过，8 套件，0 中止 | 包含两种生成消息、两个 Calcite 套件及上述固定窗口 |
| 默认完整后端 | **462 项通过，76 套件，0 失败、0 中止，退出 0** | 本地默认测试门；没有排除 Calcite，默认仍排除 ExternalIntegration |
| 前端 | 5 项契约测试及构建通过 | 没有修改产品 UI 源码，没有重新做浏览器联调；大于 500 kB 的 Vite 主包提示仍保留 |
| 生产分发目录 | packageBin、Universal/stage 通过；193 个依赖 JAR，只含一个 protobuf-java 4.33.4；兼容类存在，测试设施不在生产 JAR 中 | 打包与清单检查；真实 CDC 实验使用 Test Classpath，不冒充部署此分发包运行 |

67 项包含在默认 462 项中，不重复累计。测试数量增加也包含两个既有 Calcite 套件恢复执行的用例，不把所有新增执行数都称为新写的测试。

**64→1 是 `Connection.prepareStatement` 的调用次数。** 每事件的 executeUpdate、每组账本查询的 SQL 执行次数仍保留；没有测量吞吐倍数、p99、MySQL 网络往返减少或 70 万表资源消耗。重复事件转换的消除属于源码结论，不另编造 CPU 时间结果。

[局部证据离线核验](hot-path-result.json)、[全部检查汇总](validation.json)和[运行清单](run-manifest.json)。

## 3. 真实 MySQL 十场景回归

真实环境：MySQL 8.4.11、ROW/FULL、GTID ON；Java 17.0.14、Pekko 1.1.3、Debezium 3.6.1.Final、protobuf-java 4.33.4。Source、业务表、完整组账本、审计和 Actor JDBC Journal 均为真实 MySQL。每项使用隔离测试容器中的新随机 schema；没有连接业务实例。

| 窗口 | 最终缺行 / 多行 / 共同主键字段差异 | 结果 |
|---|---|---|
| N0 正常路径恢复 | 0 / 0 / 0 | 收敛 |
| J1：J 已持久化、Source ACK 尚未调用 | 0 / 0 / 0 | 收敛 |
| J2：原始 ACK 后，D 写锁住并 flush 失败 | 0 / 0 / 0 | 收敛 |
| C1：目标真实 COMMIT、回复被扣住时硬退出 | 0 / 0 / 0 | 收敛 |
| C2：目标真实 COMMIT、回复丢失后异常进入 Actor | **2 / 2 / 212** | **FAIL_TERMINAL，保留失败** |
| C0：COMMIT 未转发、目标回滚负对照 | 0 / 0 / 0 | 收敛 |
| K1：主键 d/c 跨回调，两个原始 ACK 之间崩溃，D 已推进 | 0 / 0 / 0 | 收敛 |
| K2：同一 ACK 窗口，D 写被阻塞并失败 | 0 / 0 / 0 | 收敛 |

逐字段比较 `id/label/amount/note/updated_at/revision`，源与目标均为 64 行；另核对原始 SourceRecord、ACK 连续前缀、组账本和审计，避免只看最终行掩盖重复副作用。

C2 最终仍是 T=17 组、J.sequence=0、审计 82 条，恢复没有启动 Reader。源、目标虽然都是 64 行，实际仍缺行、多行和字段不同。本轮没有清 Journal、重置 D、修改 generation 或手工补数据。

两个取消场景仍验证：空闲追平后确认 Released；T 已提交但 J 尚未推进时先 TimedOut，放行后 J/ACK 完成再确认关闭。两项最终均六字段全量一致，命名 Debezium runner 为 0。

[八窗口独立核验](result.json)、[取消与释放独立核验](session-result.json)。十项测试通过的是其观察断言，**不是十项自动恢复全部成功**。固定切点也不能覆盖任意网络分区、驱动故障或多 owner 并发。

## 4. 版本保全与复核

209 份后端源码/资源及构建文件在正式运行前冻结，最终哈希匹配。此前 96 份实验材料逐文件保持原样。Source、组装配器、Bridge、Engine、Actor 事件/状态和 Checkpoint 等协议相关文件与基础提交相同，具体清单见源码记录；本轮变化在 Sink 事务内实现、查询服务、配置和依赖层。

[原始材料](evidence.tar.gz)包含正式十项的全部行、T/J/D、原始回调和 ACK、数据库 SQL 导出、源码快照、依赖清单及检查日志。归档内有逐文件 SHA256；临时数据库凭据不入包。

```sh
cd docs/experiments/2026-10-20-hot-path-query
shasum -a 256 -c SHA256SUMS
python3 -B verify_hot_path.py
python3 -B verify.py
python3 -B verify_sessions.py
```

后端默认回归：`sbt 'pekko-server/test'`。真实 MySQL 显式复跑条件和命令见 [C 阶段说明](../2026-10-19-stage-c-sessions/README.md#7-如何复核)，使用新随机测试 schema，不指向业务实例。`run-manifest.json` 区分本轮显式真实运行与默认测试门。

早期失败日志保留在 `checks/`：第一版计数 fixture 曾为不同事件生成相同组身份，修正 fixture 后另在优化前 Sink 上复现了正确的 64 次准备基线；异步流终止测试按实际成功/超时终止信号核验；JDBC fixture 对齐插件默认用户后重新通过。它们没有被当作生产协议故障或额外覆盖。正式结论以 `focused-latest.log`、`full-module.log` 和 `real-ten.log` 为准。

## 5. 保留的设计边界

- 列表仍扫描持久化 ID，深页成本和冷实体激活没有消失；独立查询投影/任务目录及稳定游标仍需单独设计。
- 查询额度按服务实例生效，不是集群限流、租户公平队列或 CDC Reader 数量预算。流超时不证明 JDBC SQL 或 Actor 恢复已被中断。
- C2 失败终态策略没有修复；完整组不是源事务 BEGIN/END 原子性。DDL、多 owner、共享 Reader 和跨进程 fencing 不在本轮。
- 没有规模、吞吐、长期运行或生产发布证据。Actor 对象轻量，不意味着每表 Debezium Engine、线程、连接和 Journal 成本也同样轻量。
- Git 推送与运行部署是两种结果；本轮发布目标是现有 feature 分支，不部署服务或更新文章网站。
