# 从 Flink CDC 到 Pekko：海量常驻任务的执行模型与恢复协议

我研究 Flink CDC，是为了判断怎样把可靠的数据同步能力引入 `pekko-reference`。业务起点是上一家公司 70 多万张表、需要长期管理同步任务的场景。这个数量是需求背景，不是本项目已经通过的容量测试。

五篇文章沿同一条主线展开：Actor 如何复用执行资源；现有代码如何提交与恢复；真实故障怎样反驳隐含假设；新协议应满足哪些不变量；最终如何在同一业务约束下比较 Pekko 与 Flink CDC。文章与核验材料于 **2026-10-18** 重写整理，生产恢复协议没有改动。

## 阅读顺序

| 篇目 | 本篇展开的核心论证 |
|---|---|
| [一：70 万张表如何常驻——从 Pekko 的执行模型理解 CDC 的资源优势](01-why-pekko-for-resident-cdc.md) | Mailbox 调度、串行不变量、线程复用与实体驻留；实际配置和阻塞成本 |
| [二：一次提交，三份进度——沿源码推导 Pekko CDC 的恢复契约](02-source-and-recovery-contract.md) | Source 身份、目标事务、Journal 回复与 offset flush；正常顺序及恢复窗口 |
| [三：怎样证明恢复失败——真实 MySQL、Actor Journal 与批次重放实验](03-real-mysql-journal-failure.md) | 闸门建立因果窗口，独立读取 T/J/D，用副作用审计和全量字段比较判断结果 |
| [四：重新分批之后，恢复为什么仍应成立——从不变量构造 T/J/D 协议](04-protocol-and-validation.md) | 稳定身份、原子覆盖证据、确认前缀、所有权和保留边界；有限模型及故障矩阵 |
| [五：70 万表的 CDC 平台，Pekko 与 Flink CDC 各自解决什么](05-pekko-vs-flink-cdc.md) | 对照 Source 状态机与控制粒度，计算共享、隔离和恢复成本，形成有条件的选型判断 |

## 版本与证据口径

生产源码链接统一固定到公开提交 [`d88ec80f2a7166cfdaddf1284cc268a68bc62e28`](https://github.com/Xuxiaotuan/pekko-reference/tree/d88ec80f2a7166cfdaddf1284cc268a68bc62e28)。真实 MySQL 实验运行于本地 `ad58cf3d861597b577e2f5745584fe56e81fb621`；Actor 机制实验运行时 HEAD 为 `72088fef0b8f691316410ee7a24ef4220c956848`。本轮核对上述版本相关 `pekko-server/src/main` 与 `build.sbt` 无差异。无关 Calcite 测试提交不纳入文章发布分支。

项目依赖为 Scala 2.13.12、Pekko 1.1.3、Pekko Persistence JDBC 1.1.1、Debezium 3.6.1.Final。上游精读分别固定为：

| 对象 | 固定版本 / 提交 | 用途 |
|---|---|---|
| Pekko | 1.1.3 / `38facf752b4e7d5fb43ed7c671eb91888b41bd10` | Mailbox、Dispatcher、Sharding、Persistence 与异步 Source |
| Debezium | 3.6.1.Final / `63371830ceff75437f95c775b91eeeaf55a056c1` | 恢复 offset 的语义，ACK 与 flush 的区别 |
| Flink CDC | 3.6.0 / `bf160337ff558d70302e7884f68937611873117e` | 第五篇 SourceReader、split 分配和快照交接源码对照 |
| Flink | 1.20 文档分支，本轮页面显示 1.20.3 | 执行架构、托管状态与连接器容错边界 |

前一组 [Flink CDC 深入系列](https://xuyinyin.cn/flink-cdc-series/) 使用自己的 3.7.0 研究基线与 Reader/Flink/Paimon 实验。本组没有将那些实验重新标为 3.6.0 结果，也没有进行 Flink 与 Pekko 同负载基准。Pekko 在线 1.1 文档不作为项目运行版本，具体机制以固定 1.1.3 源码为准。

全文区分以下证据层级：

- **源码结论 / 源码推演**：固定版本实现、因果路径与条件性推理；不等于已运行所有分支。
- **真实实验结果**：明确组件、环境、数据集和窗口的运行观察。
- **历史证据复算**：从已保存的原始行数据重新计算，检查材料一致性；不是重跑数据库。
- **模型推导 / 有限模型检查**：依赖显式假设的推理和枚举；不是生产协议实现验证。
- **设计提案 / 未验证**：尚未实现或证据不足，保留 `evidence_incomplete`。

## 全系列验证范围汇总

| 判断对象 | 当前证据 | 支持的结论 | 未覆盖边界 |
|---|---|---|---|
| 大量 Actor 共享线程 | Pekko 源码 + 10,000 Actor / 40,000 消息 / 2 线程 | 本次普通 Typed Actor 运行无重叠、无本条件下顺序错误 | 持久化实体容量、70 万表、P99 与内存斜率 |
| 阻塞处理占用 Dispatcher | 两个 handler 的门闩实验 | 同池第三个 handler 等待，释放后执行 | 生产负载公平性与延迟上界 |
| remember 与钝化 | 1.1.3 源码 + 三套配置解析 | 都选择 NoPassivationStrategy | 线上覆盖配置、真实钝化/迁移 |
| 空 Bridge 占用共享 JDBC 池 | 真实 Bridge + Pekko Dispatcher，线程栈门槛 | 8 个读取阻塞，sentinel 等待；外部 close 后释放 | 完整 MySQL 作业是否进入同一窗口、生产取消路径 |
| ACK 返回与 D 持久化 | 项目调用链 + Debezium 固定源码 | 存在 flush 未成功而确认方法返回的路径 | 本轮未注入真实 offset 写故障 |
| T 已提交/J 未持久化 | 真实 MySQL Source/Sink/Journal，两个 JVM | 固定截获 T/J=6/5、5/4；D 未推进 | J 已推进/D 滞后、COMMIT 回复丢失 |
| 完整批次重放 | 实际 1→1 回调 | AlreadyCommitted；审计未重复；后续追平 | 任意重新分批、任意旧恢复点 |
| 拆批重放 | 真实 Debezium 批大小 16→1 | 同 batchId 内容不同，账本冲突，Actor failed | 相同配置自然拆批频率、新协议修复结果 |
| 全量逐字段一致性 | 真实实验数据 + 本轮独立复算 | 成功组零差异；失败组缺 65、多 64、216 字段差异 | 全 MySQL 类型、跨库表示、70 万表 |
| 默认时区 | 原实验前置基线失败 | 64 行 DATETIME 偏移 8 小时；正式用例固定 UTC | 生产转换问题未修复 |
| 新事件级恢复契约 | 推导 + 6 事件 / 7,696 个抽象组合 | 稳定身份等假设下，枚举的重分批结果与审计成立 | 真实 ID 提取、MySQL 事务、J/D、并发、DDL |
| 多 owner、证据清理、共享前缀 | 协议分析与部分模型反例 | 明确证明义务和故障矩阵 | 没有生产实现后的端到端证据 |
| Flink/Pekko 成本与取舍 | 两边机制源码 + 条件性成本模型 | 能区分控制粒度、数据运行时与工程责任 | 没有同负载资源或吞吐胜负 |

## 可复查材料

- [真实 MySQL / Journal 实验报告与复跑命令](../experiments/2026-10-18-cdc-journal-window.md)，[原始证据包](../experiments/evidence/2026-10-18-cdc-journal-window.tar.gz)，[压缩包哈希](../experiments/evidence/2026-10-18-cdc-journal-window.tar.gz.sha256)。包含日志、数据库导出、全部行数据、T/J/D、审计和测试源码。
- [Actor 执行机制实验](../experiments/2026-10-18-actor-execution/README.md)。包含探针、运行器、原始输出、环境与 Pekko 源码清单。
- [本轮复算、有限模型及上游源码清单](../experiments/2026-10-18-series-review/README.md)。可独立运行 Python 标准库脚本核对。

旧 `docs/blog` 是另一组调度平台文章，其中历史性能数字和“生产就绪”等表述不作为本系列证据。文章完成与代码推送，也不表示 CDC 恢复协议已经改造完成。
