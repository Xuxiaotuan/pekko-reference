# 从 Flink CDC 到 Pekko：共享捕获、全量转流与恢复协议

一个 MySQL 实例有 70 多万张表，按需选择其中几张，先同步已有数据再持续追实时，并允许不停其他表地增加、取消订阅。本系列围绕这个目标解释当前代码、实验和剩余设计。70 万是业务目录背景，不是已完成的活跃复制规模验收。

2026-10-26 的文章修订按代码 `55fad0c` 组织五篇主文：目标与执行资源 → 分页/LOW/HIGH/B→F 源码 → 真实实验与反例 → 恢复状态与确认协议 → 与 Flink CDC 的具体取舍。每篇围绕同一业务问题给出实现和代表性证据；Actor 机制、旧 T/J/D 推导及历史失败移入独立[技术附录](appendices/README.md)，不再参与正文目录和字数。文章改写不代表重新运行实验。

## 阅读顺序

| 篇目 | 本篇要讲透的问题 |
|---|---|
| [一：70 万表的常驻 CDC——共享 Reader、表级 Actor 与有界执行](01-why-pekko-for-resident-cdc.md) | 一个源实例怎样共享采集；Actor 节省哪种资源，当前 Runner 为什么还不是海量调度器 |
| [二：从分页快照到持续同步——沿源码走通 LOW/HIGH 与 B→F](02-source-and-recovery-contract.md) | 固定 U、范围 spec、RR 分页、真实 marker、窗口 fold、封存、整表回放与 F 激活 |
| [三：批转流怎样验收——从恢复命题到真实故障证据](03-real-mysql-journal-failure.md) | 以 I08/I11/I16/I21 检验命题，再用启动反例检验捕获连续性前提 |
| [四：批转流恢复的证明链——固定范围、封存身份与激活边界](04-protocol-and-validation.md) | 推导固定范围、封存身份和激活条件，并解释完整 B→F 的逐键收敛及其失效条件 |
| [五：海量常驻 CDC 的自建边界——Pekko 控制面与数据运行时的取舍](05-pekko-vs-flink-cdc.md) | 在同一业务承诺下，哪些控制值得自建、哪些运行时能力应复用，正面证据与成本缺口是什么 |

## 统一业务案例与论证入口

全文区分源目录 N、已订阅 S 和活动工作 A(t)：70 万是 N 的需求背景，5→7 是本次案例中的 S，不能互换为容量证据。正面事实是已有共享捕获、动态初始化和指定恢复样本；尚待证明的是捕获连续性与有界平台组合后的可靠性和资源收益。

第三篇围绕[跨窗口迁移 I08](03-real-mysql-journal-failure.md#e-i08)、[丢失读视图 I11](03-real-mysql-journal-failure.md#e-i11)、[T 领先 JI16](03-real-mysql-journal-failure.md#e-i16)给出“命题—切点—证据—判定”。第四篇以[G0—G4](04-protocol-and-validation.md#g0)统一前提与推理；[启动反例 E0](03-real-mysql-journal-failure.md#e-startup)保留为当前整体承诺的阻断事实。

## 主流程与关键术语

```text
Schema准备 → B绑定 → 冻结键上界U
→ 每个固定范围：LOW → RR分页RAW → HIGH → 日志回填 → FINAL封存 → baseline T/J
→ F绑定 → 从B完整回放到F → 审计 → 激活T → STREAMING J
→ 持续读L、完整组T/J；取消使用K与排空协议
```

| 术语 | 含义 | 不能混同 |
|---|---|---|
| U | 固定的全表扫描最大主键 | binlog offset、事件时间 Watermark |
| B / K | 一代订阅的捕获开始/停止边界 | 全量完成、取消命令刚被接受 |
| LOW / HIGH | 一次范围读视图前后的真实日志 marker | 全局 Checkpoint、D 已经落盘 |
| F | 全量之后有限追赶的固定终点 | 最后一个业务 GROUP、业务已原子发布 |
| L | 持久完整组、Schema、marker 与 ACK 引用链 | 单一最大 offset、目标 T |
| T / J / D | 目标事务 / Actor 接受进度 / SDK 持久 offset | 可以直接比较大小的三个计数器 |

## 版本与证据口径

当前生产与测试源码链接固定到 [`55fad0cf27f3a289dbb5d4fb4f2a31d60a661059`](https://github.com/Xuxiaotuan/pekko-reference/tree/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059)。项目依赖为 Scala 2.13.12、Pekko 1.1.3、Persistence JDBC 1.1.1、Debezium 3.6.1.Final；已归档实验使用 MySQL 9.5.0、JDK 17.0.14。

初始化矩阵在 `00ad18e` 加未提交实现的冻结工作区运行，全源树摘要为 `c71f260870325aa54bb6818200a875689afaace93d10dc8496a11c99e5f362da`。后续启动探针候选 1—4 生产文件一致，但测试设施不同，不能用一个全源树 hash 冒充所有运行。candidate4 保留 339 项源码清单，与发布后的相应源码逐项匹配；发布提交、运行时 HEAD 和材料复算是三种事实。[初始化版本记录](../../experiments/2026-10-25-bootstrap/final-version.json) · [启动候选 4](../../experiments/2026-10-25-capture-startup/candidate-4.json)

历史版本 `d88ec80`、`65ab541` 和 `00ad18e` 的来源、运行 HEAD 与证据见[独立版本档案](appendices/00-versions-and-evidence-history.md)。第五篇的 Flink CDC 源码对照固定 3.6.0；另一组[Flink CDC 系列](https://xuyinyin.cn/flink-cdc-series/)的 3.7.0 与 Paimon 实验不混作同版或同 Sink 结果。没有同负载性能基准。

全文区分：**源码结论**、**真实实验结果**、**保存材料复算**、**条件性推理/模型**、**设计提案/未验证**。小规模终值一致、组件 suite 通过或归档校验都不能替代更高层验收。

<a id="全系列验证范围汇总"></a>

## 当前验证范围汇总

| 对象 | 证据与结果 | 支持的结论 | 未覆盖/失败边界 |
|---|---|---|---|
| Actor 执行模型 | 历史 10,000 普通 Actor、40,000 消息、2 线程 | 本次 handler 串行及共享线程机制 | 持久 CDC 实体、70 万真实表与资源斜率 |
| 共享捕获阶段一 | R01—R16 展开 31 项、可信复制基线 | 动态 B/K、L 和指定表级恢复语义 | 不是在线全量初始化；旧 R02 重跑仍有启动失败 |
| LOW/HIGH 与批转流 | I01—I24 展开 61 项，全部预期断言成立 | 指定分页、窗口回填、封存、B→F、激活及故障窗口 | 含安全暂停负例，不能写成 61 次自动恢复收敛 |
| 全量逐字段核验 | 61 项材料独立复算，17,874 个必需相等字段，另有新 generation 对账 | 保存材料的行集与字段比较、证明链一致 | 复算不是数据库重跑；不证明任意外部副作用 |
| 相关检查与旧回归 | 同冻结源树相关 80 通过；旧真实 58 项中 57 通过、R02 失败 | 分别支持其检查范围 | 不能合并称完整验收通过 |
| 后端常规检查 | 历史 593 通过 | 明确选择集合的回归 | 排除 ExternalIntegration 与旧 ActorSpec，非全库全绿 |
| 启动自然/确定性 R02 | 自然本次 PASS，选点前 B 提交 FAIL | 调度窗口会改变结果，旧失败未消除 | 新准入/heartbeat/并发取消等矩阵未完成 |
| 已绑定 B 且 D=INITIAL | 真实 SDK/MySQL Sink/JDBC Journal；id1 五字段旧值，id2 witness 到 T/J | 指定 batch128/禁 TLS 代理/双门配置下实际不一致 | 初始目标为复制基线，不是完整 LOW/HIGH 作业；不推断失败频率 |
| 物理隔离与并行设施 | 16 项隔离、15 项 Python 编排检查；双槽 Reader 各 2/2 | 各层对应的隔离、准入、预算与真实并行 | 不是完整双槽故障矩阵或容量验收 |
| 服务端资源释放 | Reader suite4/4；两个 wrapper125，5 秒仍有 dump 记录；后续约 149 秒观察时归零 | 后续最终连接/事务/进程/租约状态已确认 | 5 秒释放未通过，精确自然释放耗时未知 |
| 生命周期/平台 | B/E 设计已有；F/G 生产接入未完成 | 当前提议可审查 | 无新链路多 owner、持久配额恢复及 API/UI 闭环证据 |
| 规模与长期维护 | 只有机制与有限场景 | 可继续逐级验证 | 70 万真实表、DDL、GC、长期 churn 与性能胜负未验证 |

整体验收保持 **`evidence_incomplete`**：已实现且已测的内核可以复用；启动连续性失败必须先收口。捕获就绪、初始化完成、当前持续健康和资源已释放应分别报告，不能用一个 running 字段概括。

## 当前材料与源码入口

- [BootstrapRunner](https://github.com/Xuxiaotuan/pekko-reference/blob/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala)：主状态机。
- [初始化报告、范围与故障清单](../../experiments/2026-10-25-bootstrap/README.md)、[独立复算](../../experiments/2026-10-25-bootstrap/final-summary.json)、[归档索引](../../experiments/2026-10-25-bootstrap/artifacts/archive-index.json)。
- [启动恢复反例](../../experiments/2026-10-25-capture-startup/README.md)、[结果摘要](../../experiments/2026-10-25-capture-startup/summary.json)、[材料摘要清单](../../experiments/2026-10-25-capture-startup/SHA256SUMS.json)。
- [双槽与资源释放报告](../../experiments/2026-10-25-cdc-lab-pool/README.md)、[双槽 suite 与后置结果](../../experiments/2026-10-25-cdc-lab-pool/scala-dual-slot-summary.json)。
- [启动准入设计](../../development/specs/2026-10-25-shared-reader-startup-recovery-design.md)、[平台生命周期设计](../../development/specs/2026-10-25-cdc-platform-lifecycle-contract.md)：提议，未实施。

## 独立技术附录

[技术附录索引](appendices/README.md)提供六份独立历史档案、固定版本入口和完整实验报告。原始证据、失败样本与校验脚本留在既有实验目录；本次只调整文章组织，不改变实验内容或新增运行结果。
