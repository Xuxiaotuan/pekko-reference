# 从 Flink CDC 到 Pekko：共享捕获、全量转流与恢复协议

一个 MySQL 实例有 70 多万张表，按需选择其中几张，先同步已有数据再持续追实时，并允许不停其他表地增加、取消订阅。本系列围绕这个目标解释当前代码、实验和剩余设计。70 万是业务目录背景，不是已完成的活跃复制规模验收。

2026-10-27 的修订把持久任务索引、有界步骤和平台入口接回原来的五篇主线：目标与执行资源 → 分页/LOW/HIGH/B→F 源码 → 真实实验与反例 → 恢复状态与确认协议 → 与 Flink CDC 的具体取舍。源码变化、当前实测和历史材料分别标识；Actor 机制、旧 T/J/D 推导及失败仍保留在独立[技术附录](appendices/README.md)。文章更新不自动改变旧实验的版本或结果。

## 阅读顺序

| 篇目 | 本篇要讲透的问题 |
|---|---|
| [一：70 万表的常驻 CDC——共享 Reader、表级 Actor 与有界执行](01-why-pekko-for-resident-cdc.md) | 一个源实例怎样共享采集；冷索引、活动 Journal、步骤预算和 L 唤醒怎样配合 |
| [二：从分页快照到持续同步——沿源码走通 LOW/HIGH 与 B→F](02-source-and-recovery-contract.md) | 固定 U、范围 spec、RR 分页、真实 marker、窗口 fold、封存、B→F 与步骤恢复 |
| [三：批转流怎样验收——从恢复命题到真实故障证据](03-real-mysql-journal-failure.md) | 以 I08/I11/I16/I21 检验命题，再用启动反例检验捕获连续性前提 |
| [四：批转流恢复的证明链——固定范围、封存身份与激活边界](04-protocol-and-validation.md) | 推导固定范围、封存身份和激活条件，并解释完整 B→F 的逐键收敛及其失效条件 |
| [五：海量常驻 CDC 的自建边界——Pekko 控制面与数据运行时的取舍](05-pekko-vs-flink-cdc.md) | 在同一业务承诺下，哪些控制值得自建、哪些运行时能力应复用，正面证据与成本缺口是什么 |

## 统一业务案例与论证入口

全文区分源目录 N、已订阅 S 和活动工作 A(t)：70 万是 N 的需求背景，5→7 是本次案例中的 S，不能互换为容量证据。正面证据包括历史共享捕获、动态初始化及指定恢复样本；后续 A1/A2 又增加启动恢复分类和准入实测。本轮又经过真实平台 P01 的动态 5→7、P02 的十表 tail 后新增第十一表，以及独立的 700,000 条冷索引实验。它们各自支持对应层次，不从旧内核直接继承结论，也不合并成 70 万真实表验收。

第三篇围绕[跨窗口迁移 I08](03-real-mysql-journal-failure.md#e-i08)、[丢失读视图 I11](03-real-mysql-journal-failure.md#e-i11)、[T 领先 JI16](03-real-mysql-journal-failure.md#e-i16)给出“命题—切点—证据—判定”。第四篇以[G0—G4](04-protocol-and-validation.md#g0)统一前提与推理；[启动反例 E0](03-real-mysql-journal-failure.md#e-startup)继续保留。新准入门会拒绝特定不安全状态，但安全暂停不等于历史漏数已经补齐。

## 主流程与关键术语

```text
持久接受意图 → 步骤预算准入 → capture启动证据
→ Schema准备 → B绑定 → 冻结键上界U
→ 每个固定范围：LOW → RR分页RAW → HIGH → 日志回填 → FINAL封存 → baseline T/J
→ F绑定 → 从B完整回放到F → 审计 → 激活T → STREAMING J
→ L dispatch唤醒 → 一批完整组T/J → 无待处理组则park
→ 取消使用K与分批排空；维护停止不隐式退订
```

| 术语 | 含义 | 不能混同 |
|---|---|---|
| U | 固定的全表扫描最大主键 | binlog offset、事件时间 Watermark |
| B / K | 一代订阅的捕获开始/停止边界 | 全量完成、取消命令刚被接受 |
| LOW / HIGH | 一次范围读视图前后的真实日志 marker | 全局 Checkpoint、D 已经落盘 |
| F | 全量之后有限追赶的固定终点 | 最后一个业务 GROUP、业务已原子发布 |
| L | 持久完整组、Schema、marker 与 ACK 引用链 | 单一最大 offset、目标 T |
| T / J / D | 目标事务 / Actor 接受进度 / SDK 持久 offset | 可以直接比较大小的三个计数器 |
| grant / clean continuation | 一次有限工作许可 / 同 owner 已确认释放步骤的完整 J 证据 | 新执行身份、RR 读视图或内容封存 |
| dispatch / park | capture 级 L 分派 / 无待处理组的持久休眠 | 每表定时器、数据已被 GC |

## 版本与证据口径

本轮平台源码以仓库相对链接进入，最终提交、生产/测试摘要与运行环境由[平台报告](../../experiments/2026-10-27-cdc-platform/README.md)固定。项目依赖仍为 Scala 2.13.12、Pekko 1.1.3、Persistence JDBC 1.1.1、Debezium 3.6.1.Final；已归档实验使用 MySQL 9.5.0、JDK 17.0.14，没有借此升级 SDK。

版本分为三层，不能互换：

| 层次 | 源码与证据口径 |
|---|---|
| 历史 LOW/HIGH 内核与旧反例 | 文章此前固定 [`55fad0c`](https://github.com/Xuxiaotuan/pekko-reference/tree/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059)；原冻结摘要和失败保留 |
| 已提交的启动分类与准入 | [`74b6d9a`](https://github.com/Xuxiaotuan/pekko-reference/tree/74b6d9a)，实际候选源树与定点结果见[A1/A2](../../experiments/2026-10-27-capture-admission/README.md) |
| 本次生命周期、有界调度与入口 | 当前源码及[平台报告](../../experiments/2026-10-27-cdc-platform/README.md)；按冻结候选分别记录 B/P/H、L 归属反例与修后结果，测试设施不同的全源 hash 不互换 |

初始化矩阵在 `00ad18e` 加未提交实现的冻结工作区运行，全源树摘要为 `c71f260870325aa54bb6818200a875689afaace93d10dc8496a11c99e5f362da`。后续启动探针候选 1—4 生产文件一致，但测试设施不同，不能用一个全源树 hash 冒充所有运行。candidate4 保留 339 项源码清单，与发布后的相应源码逐项匹配；发布提交、运行时 HEAD 和材料复算是三种事实。[初始化版本记录](../../experiments/2026-10-25-bootstrap/final-version.json) · [启动候选 4](../../experiments/2026-10-25-capture-startup/candidate-4.json)

历史版本 `d88ec80`、`65ab541` 和 `00ad18e` 的来源、运行 HEAD 与证据见[独立版本档案](appendices/00-versions-and-evidence-history.md)。第五篇的 Flink CDC 源码对照固定 3.6.0；另一组[Flink CDC 系列](https://xuyinyin.cn/flink-cdc-series/)的 3.7.0 与 Paimon 实验不混作同版或同 Sink 结果。没有同负载性能基准。

全文区分：**源码结论**、**真实实验结果**、**保存材料复算**、**条件性推理/模型**、**设计提案/未验证**。小规模终值一致、组件 suite 通过或归档校验都不能替代更高层验收。

<a id="全系列验证范围汇总"></a>

## 当前验证范围汇总

> **2026-10-27 A/B 补测：**[证据与未完成清单](../../experiments/2026-10-27-ab-followup/README.md)记录真实 MySQL、部分 Journal/Sink 故障及逐字段复算。A/B 展开矩阵尚未跑完；A05 的可证明未源写分支目前安全暂停，不能宣称自动继续。

| 对象 | 证据与结果 | 支持的结论 | 未覆盖/失败边界 |
|---|---|---|---|
| Actor 执行模型 | 历史 10,000 普通 Actor、40,000 消息、2 线程 | 本条件下 handler 串行及共享线程机制 | 持久 CDC 实体、70 万真实表与资源斜率 |
| 共享捕获阶段一 | 历史 R01—R16 展开 31 项、可信复制基线 | 对应版本的动态 B/K、L 和指定表级恢复语义 | 不是在线全量初始化；旧 R02 的启动失败保留 |
| LOW/HIGH 与批转流 | 历史 I01—I24 展开 61 项，全部预期断言成立 | 对应版本的分页、窗口回填、封存、B→F、激活及故障窗口 | 含安全暂停负例，不能写成 61 次自动恢复收敛 |
| 全量逐字段核验 | 历史 61 项材料复算，17,874 个必需相等字段，另有新 generation 对账 | 保存材料的行集、字段比较与证明链一致 | 复算不是数据库重跑；不证明任意外部副作用 |
| 相关检查与旧回归 | 历史同冻结源树 80 通过；旧真实 58 项中 57 通过、R02 失败 | 分别支持其检查范围 | 不与后续数字相加称完整验收通过 |
| 历史后端检查 | 593 通过 | 当时明确选择集合的回归 | 排除 ExternalIntegration 与旧 ActorSpec |
| 启动自然/确定性反例 | 自然样本 PASS，选点前 B 提交 FAIL；B-bound＋INITIAL 样本实际五字段旧值 | 证明限定时序下旧行为会越过必要更新 | copied baseline、batch/TLS/双门条件保留；不推断失败频率 |
| A0/B0 信号与资源探针 | 七个实际组合、一个真实 Journal/Sink witness；heartbeat 静默对照与最终会话确认 | snapshot、当前 callback/ACK、实际 D、本地结束和服务端释放必须分开观察 | 原 5 秒自然释放未通过；SDK-only 不能替代 L/目标保证 |
| A1/A2 生产准入 | 最终候选 6 项启动/Reader 检查、5 个定点 Bootstrap variant；603 项常规后端、24 项取证/runner 工具检查 | 特定风险状态启动前拒绝、当前 attempt 准入及所选 T/J 恢复窗口 | 不补齐旧漏数；不是完整 A/B 26 项或全部 I 矩阵重跑 |
| 本轮关闭与释放 | 两个正常 Reader 场景、全局关闭容量与有界响应检查；真实 Kernel 另验证未决 L 事务的账号归属、UNKNOWN 与 owner 保留 | 修复 L 在共享控制账号下被漏观察的真实反例；只有实际事务结束才可复用 | 旧正常关闭不补作 L 故障证明；不是 B01—B10 全扩展矩阵或快速释放 SLA |
| 本轮平台与入口 | 真实 P01—P08：动态 5→7、11 表、三类取消、干净重启、遗留 owner 拒绝与 API；真实 Journal 解码和全字段复算 | 按 CONVERGED / CANCELED / SAFE_PAUSED / CONTROL_PLANE_ONLY 分开；所需相等切面 7,680 字段 | 八项不都是恢复收敛；旧静态快照 guardian 证据不等于新共享链路 HA |
| 冷元数据与资源 | 50,000 pilot 后，700,000 条精确 COUNT、7,000 页遍历、EXPLAIN、60 秒固定观察；无 ActorSystem/Reader/源表 | 对应配置的索引与查询可执行，测试进程堆/线程/磁盘另列原值 | 不是同量真实源表、CDC 吞吐、长期稳定或总 RSS 上界 |
| 本轮内核及相关回归 | 最终生产代码的所选常规后端 650 项、前端 13 项和构建；完整 61 项初始化检查含 13 个源 marker 未知的 SAFE_PAUSED；31 项共享检查与原 27 项按报告分版 | 失败期待、正确拒绝、功能恢复与保存材料复算分别报告 | 常规集合排除 ExternalIntegration 和已知旧 ActorSpec；61 项不是 61 次自动恢复，旧反例不删除 |
| 规模与长期维护 | 当前有机制、有限运行样本及独立容量口径 | 可继续分级验证 | 70 万真实表、DDL、GC、长时 churn 与性能胜负未验证 |

整体验收保持 **`evidence_incomplete`**。新增门改变了特定风险状态的行为，旧不一致没有因此被改成成功；新的平台代码也不能直接继承历史内核的实验数字。捕获就绪、初始化完成、当前持续健康和资源已释放分别报告，不用一个 running 字段概括。

## 当前材料与源码入口

- [BootstrapRunner](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala)及[步骤结果/continuation](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapStep.scala)：内核与有界调度边界。
- [CdcTaskStore](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/cdc/CdcTaskStore.scala)、[CdcPlatformRuntime](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/cdc/CdcPlatformRuntime.scala)、[CdcBootstrapKernel](https://github.com/Xuxiaotuan/pekko-reference/blob/2fd592f0c7e43af9b15f68801b7ac8f242da696b/server/src/main/scala/cn/xuyinyin/magic/workflow/cdc/CdcBootstrapKernel.scala)：持久意图、grant、L 唤醒及活动会话。
- [初始化报告、范围与故障清单](../../experiments/2026-10-25-bootstrap/README.md)、[独立复算](../../experiments/2026-10-25-bootstrap/final-summary.json)、[归档索引](../../experiments/2026-10-25-bootstrap/artifacts/archive-index.json)。
- [启动恢复反例](../../experiments/2026-10-25-capture-startup/README.md)、[结果摘要](../../experiments/2026-10-25-capture-startup/summary.json)、[材料摘要清单](../../experiments/2026-10-25-capture-startup/SHA256SUMS.json)。
- [双槽与资源释放报告](../../experiments/2026-10-25-cdc-lab-pool/README.md)、[双槽 suite 与后置结果](../../experiments/2026-10-25-cdc-lab-pool/scala-dual-slot-summary.json)。
- [A0/B0 探针](../../experiments/2026-10-26-capture-admission-release/README.md)、[A1/A2 准入实测](../../experiments/2026-10-27-capture-admission/README.md)、[本轮平台报告](../../experiments/2026-10-27-cdc-platform/README.md)：按版本追踪后续变化。
- [启动准入设计](../../development/specs/2026-10-25-shared-reader-startup-recovery-design.md)、[平台生命周期设计](../../development/specs/2026-10-25-cdc-platform-lifecycle-contract.md)：原设计背景；实际实施范围以源码和对应报告为准。

## A/B 矩阵补测范围

| 证据 | 实际结果 | 限制 |
|---|---|---|
| A10、A13、A16 | 指定真实 MySQL、Actor Journal、Sink 样本逐字段相等 | 预复制基线和指定旧版本；不是所有故障窗口或在线初始化 |
| A11 完成态 D 命名空间冲突 | 真实 Reader 重启前安全暂停，Engine 创建数 0；4 行 24 字段相等 | 文件名由测试注入；不是自动恢复收敛 |
| A12 ACK 父引用摘要错误 | 真实 Reader 重启前安全暂停，Engine 创建数 0；4 行 24 字段相等 | 测试注入一种引用损坏；不是自动恢复收敛 |
| A14 未知 COMMIT 取消 | 定点 Store 路径实际失败，事务迟提交 | 不将其他表对账相等写成该路径成功；完整 Runner 仍需验证 |
| B06 未决 COMMIT 与关闭 | 实际事务仍在途时 capture owner 锁已释放；结果不可复用 | Reader 级失败，不代表平台 owner 或接管结论 |
| B05 启停竞态 | factory 前门与真实 MySQL task start 门在 180 秒内未确认释放 | 不能断言永久泄漏；测试门位于调用原 task 的 start 实现之前 |
| A05、A09 | 未绑定 B 当前可安全暂停 | 尚无可靠证据支持自动继续；`B_BOUND` 观察事件不能替代持久状态 |
| B04 callback 异常 | 异常回调未 ACK，T/J 不变；4 行中有 2 个字段差异 | Actor 在异常前已停止；未验证自动恢复 |
| B10 旧 JVM 崩溃、L 事务仍在 | 新 JVM UNKNOWN，Engine 创建数 0；事务回滚后仍暂停 | 初始化基线 64 行相等，故障后 5 字段差异；未自动接管 |
| B08 观察连接断开 | 关闭及复查均 UNKNOWN、owner 保留；4 行 24 字段相等，T 1/J 4 | 人为断开观察连接；未验证恢复观察或自动重启 |
| B09 双 JVM 旧执行者迟到 ready | 新 JVM 保持 UNKNOWN、Engine 数 0，旧执行者继续后 64 行 384 字段相等 | 未覆盖所有跨实例旧消息形式 |
| B02/B03/B08/B09 | 指定关闭、观察器和过期回复切点已取证 | 测试门或组件探针不升级为端到端保证 |

[2026-10-28 补测报告与原始证据](../../experiments/2026-10-28-ab-matrix/README.md)保留各候选源码摘要、实际 T/J/D、回调及失败记录。矩阵整体仍为 `evidence_incomplete`。

## 独立技术附录

[技术附录索引](appendices/README.md)提供六份独立历史档案、固定版本入口和完整实验报告。原始证据、失败样本与校验脚本留在既有实验目录；后续报告另存新结果，不覆盖原始实验内容，也不把文章整理当作新增运行证据。
