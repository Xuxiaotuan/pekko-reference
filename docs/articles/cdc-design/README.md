# 从 Flink CDC 到 Pekko：海量常驻任务的执行模型与恢复协议

我研究 Flink CDC，是为了判断怎样把可靠的数据同步能力引入 `pekko-reference`。业务起点是上一家公司 70 多万张表、需要长期管理同步任务的场景。这个数量是需求背景，不是本项目已经通过的容量测试。

五篇文章沿同一条主线展开：Actor 如何复用执行资源；现有代码如何提交与恢复；真实故障怎样反驳隐含假设；新协议应满足哪些不变量；最终如何在同一业务约束下比较 Pekko 与 Flink CDC。文章于 **2026-10-18** 重写，**2026-10-19** 先补充 SourceRecord 身份与跨回调探针，再完成[完整组生产改造及真实恢复实验](../../experiments/2026-10-19-group-recovery/README.md)。本次更新保留旧协议失败反例，同时将新协议的源码结论、运行样本与尚未验证的窗口分开。

## 阅读顺序

| 篇目 | 本篇展开的核心论证 |
|---|---|
| [一：70 万张表如何常驻——从 Pekko 的执行模型理解 CDC 的资源优势](01-why-pekko-for-resident-cdc.md) | Mailbox 调度、串行不变量、线程复用与实体驻留；实际配置和阻塞成本 |
| [二：一次提交，三份进度——沿源码推导 Pekko CDC 的恢复契约](02-source-and-recovery-contract.md) | 旧回调批次与新完整组的身份/确认边界；目标事务、Journal 与 offset flush |
| [三：从恢复失败到完整组验收——真实 MySQL、Actor Journal 与重放证据](03-real-mysql-journal-failure.md) | 旧失败与新验收分开保留；因果闸门、真实 T/J/D、审计与全字段对账 |
| [四：重新分批之后，恢复为什么仍应成立——从不变量构造 T/J/D 协议](04-protocol-and-validation.md) | 稳定身份、原子覆盖证据、确认前缀、所有权和保留边界；有限模型及故障矩阵 |
| [五：70 万表的 CDC 平台，Pekko 与 Flink CDC 各自解决什么](05-pekko-vs-flink-cdc.md) | 对照 Source 状态机与控制粒度，计算共享、隔离和恢复成本，形成有条件的选型判断 |

## 版本与证据口径

旧协议分析的生产源码链接固定到公开提交 [`d88ec80f2a7166cfdaddf1284cc268a68bc62e28`](https://github.com/Xuxiaotuan/pekko-reference/tree/d88ec80f2a7166cfdaddf1284cc268a68bc62e28)。真实 MySQL 实验运行于本地 `ad58cf3d861597b577e2f5745584fe56e81fb621`；Actor 机制实验运行时 HEAD 为 `72088fef0b8f691316410ee7a24ef4220c956848`。2026-10-19 两组源侧实验运行时 HEAD 为 `436056d8608e0d2d27ddc24988005cf70a42ff4d`。以上历史实验对应的生产代码与构建依赖仍相同，源侧探针仅位于 Test 源集。新增完整组生产实现另行固定到 [`65ab541`](https://github.com/Xuxiaotuan/pekko-reference/tree/65ab5410c32933d0aa3d23510e6c62252d84009b)；不将它冒充此前探针运行的版本。无关 Calcite 测试及任务文件不纳入文章发布分支。

新协议真实实验取证时位于本地 `2274691` 的未提交工作区。该状态的 23 个生产源码、Schema 及测试文件摘要保存在 [source-manifest.json](../../experiments/2026-10-19-group-recovery/source-manifest.json)，已与上述发布提交逐文件核对；证据包同时保留源码快照。运行 HEAD、发布提交和源码字节匹配是三种不同事实，不能只替换一个 SHA 就宣称重跑过实验。

| 项目版本 | 本系列用途 | 恢复语义 |
|---|---|---|
| `d88ec80` | 历史批次账本路径、失败反例与未改动的 Actor/Bridge/Engine 机制 | `mysql.binlog.v1`，回调批次去重 |
| `65ab541` | 完整组生产实现及本次指定窗口验收 | 显式 `groupProtocol`，boundary v2、cursor `mysql.binlog.groups.v1`；可信 streaming 基线，组证据去重 |

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
| 旧协议 T 已提交/J 未持久化 | 2026-10-18 真实 MySQL Source/Sink/Journal，两个 JVM | 固定截获 T/J=6/5、5/4；D 未推进 | 该历史结果不证明新组协议 |
| 旧协议完整批次重放 | 实际 1→1 回调 | AlreadyCommitted；审计未重复；后续追平 | 任意重新分批、任意旧恢复点 |
| 旧协议拆批重放 | 真实 Debezium 批大小 16→1 | 同 batchId 内容不同，账本冲突，Actor failed | 相同配置自然拆批频率；不得作为新协议当前行为 |
| 旧协议全量逐字段一致性 | 历史真实行数据 + 独立复算 | 成功组零差异；失败组缺 65、多 64、216 字段差异 | 全 MySQL 类型、跨库表示、70 万表 |
| 默认时区 | 原实验前置基线失败 | 64 行 DATETIME 偏移 8 小时；正式用例固定 UTC | 生产转换问题未修复 |
| 抽象事件去重模型 | 6 事件 / 7,696 个抽象组合 | 模型假设下的分批不变性和审计成立 | 模型未包含 d/c 分组、真实确认边界、T/J/D |
| SourceRecord 身份与操作区分 | 新增真实 MySQL，正式 9 JVM、39 条同事务逻辑记录 | 实际拆/合批及事务内后缀重放的候选身份和规范化内容相同 | 源谱系切换、快照、DDL、所有类型；不构成通用规范 |
| 主键成员 ACK 边界 | 上述身份探针的一个恢复窗口 | 仅确认 d 后 D 跳过共同物理行，c 未再交付 | 没有目标，不能宣称真实 Sink 缺行；不是独立新增一组实验 |
| 回调返回后的延迟确认与装配 | 另行两组真实 Engine/Bridge、6 JVM，实际 [1,1] 和 [2,2,2,2] | 完整组经测试信号覆盖后，旧句柄在后续回调按序调用；确认前 D 不推进；后续新 JVM 可继续读 | 没有真实 T/J、新组账本或生产 Source Stream；关闭竞争、字节预算、长期容量未验 |
| 完整组生产路径 | `65ab541` 源码；102 项相关检查通过 | 完整组进入 Bridge；T 组证据与 DML 同事务；当前交付确认 J；原始回调前缀 ACK | 单测不替代真实 MySQL；本次未修改 Actor/Engine 或引入 owner fencing |
| 新协议拆批恢复 | 真 MySQL/Actor JDBC Journal/生产 Sink，T 已提交/J 未持久化，reader 16→1 | 旧组不重写，新数据继续；64 行×6 字段差异 0，已提交组重放新增审计 0 | 任意自然分批频率、长期多次恢复 |
| 新协议合批恢复 | 同一窗口，reader max 1→128；实际首个恢复交付 72 组/73 成员 | 混合旧/新组正确覆盖，64 行×6 字段差异 0 | 128 是配置上限，不是实际收到 128 条；不是全部批量组合 |
| 新协议跨回调主键恢复 | 原始 [d][c]，完整组 T 已提交/J 未持久化后新 JVM 恢复 | 两成员整体去重；重放前后审计 68→68；后续更新后 69；64 行×6 字段差异 0 | 收到半组即崩溃、两个原始 SDK 句柄之间崩溃仍未固定实测 |
| 冲突、关闭、迟到 ACK 与预算 | Source/Bridge 单测；组冲突和 PK 双成员回滚使用 H2 | 错误 batchId 不消费确认能力；错误组/冲突拒绝，目标事务回滚；取消释放等待 | 未做完整 MySQL 作业的共享池耗尽/取消、组内 DML 后 COMMIT 前崩溃 |
| 新目标存储前提 | 真实 MySQL 的独立拒绝检查 | MyISAM 目标在组数据提交前被拒绝 | 不是第四组恢复实验；非事务外部副作用不在保证内 |
| 构建与全库检查 | 最终 102 项相关检查、4 项真实套件通过；全模块执行 414 项通过、2 个 Calcite 套件中止 | 明确记录已执行检查及缺类错误 | 完整测试门为 evidence_incomplete；不能写成全库全绿 |
| 多 owner、证据清理、共享前缀 | 协议分析与部分模型反例 | 明确证明义务和故障矩阵 | 没有生产实现后的端到端证据 |
| Flink/Pekko 成本与取舍 | 两边机制源码 + 条件性成本模型 | 能区分控制粒度、数据运行时与工程责任 | 没有同负载资源或吞吐胜负 |

两组源侧材料不与端到端恢复场景合并计数。新协议实际是三组恢复，每组初始化、新协议故障、恢复三个 JVM；真实套件的第四项是 MyISAM 拒绝检查，不是第四组恢复。2026-10-18 的旧协议成功/失败对照仍单独保留。身份实验此前的七 JVM 核心运行是重复检查，不另累计覆盖；有限模型和只读复算也不是新增数据库运行。

## 可复查材料

- [真实 MySQL / Journal 实验报告与复跑命令](../../experiments/2026-10-18-cdc-journal-window.md)，[原始证据包](../../experiments/evidence/2026-10-18-cdc-journal-window.tar.gz)，[压缩包哈希](../../experiments/evidence/2026-10-18-cdc-journal-window.tar.gz.sha256)。包含日志、数据库导出、全部行数据、T/J/D、审计和测试源码。
- [Actor 执行机制实验](../../experiments/2026-10-18-actor-execution/README.md)。包含探针、运行器、原始输出、环境与 Pekko 源码清单。
- [本轮复算、有限模型及上游源码清单](../../experiments/2026-10-18-series-review/README.md)。可独立运行 Python 标准库脚本核对。

- [真实 SourceRecord 身份及主键 ACK 反例](../../experiments/2026-10-19-source-record-identity/README.md)：原始回调、九 JVM、D、失败记录与只读核验。
- [延迟句柄与跨回调装配](../../experiments/2026-10-19-deferred-ack/README.md)：两组、六 JVM、实际确认顺序和 D；覆盖来自测试信号。
- [完整组生产恢复报告](../../experiments/2026-10-19-group-recovery/README.md)、[运行结果](../../experiments/2026-10-19-group-recovery/result.json)、[原始回调/T/J/D/源码归档](../../experiments/2026-10-19-group-recovery/evidence.tar.gz)、[SHA256SUMS](../../experiments/2026-10-19-group-recovery/SHA256SUMS)、[独立核验脚本](../../experiments/2026-10-19-group-recovery/verify.py)：真实提交、Journal 与 Sink 的指定恢复样本。

旧 `docs/blog` 是另一组调度平台文章，其中历史性能数字和“生产就绪”等表述不作为本系列证据。本次已完成的是收窄范围内的完整组生产实现与指定窗口验收；DDL、多 owner、共享读取、快照初始化和其余故障窗口仍未完成。文章或代码发布不会扩大这些保证。
