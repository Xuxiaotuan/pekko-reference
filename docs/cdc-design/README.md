# 从 Flink CDC 到 Pekko：面向海量常驻同步任务的设计与验证

我研究 Flink CDC，是为了判断怎样把可靠的 CDC 能力引入 `pekko-reference`。业务起点是我提供的上一家公司场景：70 多万张表，需要长期保持同步能力。表的数量是需求背景，不是本项目已经通过的容量测试。

这组文章先解释为什么值得做，再沿真实源码和故障证据确定边界，最后比较项目与 Flink CDC 的取舍。本文档集写于 **2026-10-18**；本次不修改生产恢复协议。

## 阅读顺序

| 篇目 | 核心问题 |
|---|---|
| [一：70 万张表，为什么考虑用 Pekko 做 CDC](01-why-pekko-for-resident-cdc.md) | 怎样把表的逻辑生命周期与昂贵的数据任务分开？ |
| [二：沿源码理解 T/J/D，三个进度为什么不能自动一致](02-source-and-recovery-contract.md) | 当前提交顺序保护了什么，恢复时又依赖什么假设？ |
| [三：真实 MySQL 故障实验，批次重放为什么会走向两个结果](03-real-mysql-journal-failure.md) | 如何固定目标已提交、Actor Checkpoint 未持久化的窗口？ |
| [四：把实验变成协议约束，下一阶段应该怎样设计](04-protocol-and-validation.md) | 哪些代码保留，哪些契约需要调整，怎样判定改造成功？ |
| [五：Pekko 与 Flink CDC，70 万表场景下怎样取舍](05-pekko-vs-flink-cdc.md) | Actor 的优势在哪里，又需要付出哪些可靠性与运维成本？ |

## 版本与证据口径

实验实际运行在本地提交 `ad58cf3d861597b577e2f5745584fe56e81fb621`。该提交相对远端已有的 [`d88ec80f2a7166cfdaddf1284cc268a68bc62e28`](https://github.com/Xuxiaotuan/pekko-reference/tree/d88ec80f2a7166cfdaddf1284cc268a68bc62e28) 仅包含无关测试与历史任务材料变化；本轮已用 Git 确认两者的 `pekko-server/src/main` 与 `build.sbt` 无差异。为使读者能直接访问源码，本系列生产源码链接统一固定到远端的 `d88ec80`，不把无关本地提交带入文章分支。生产依赖来自该版本的 [build.sbt](https://github.com/Xuxiaotuan/pekko-reference/blob/d88ec80f2a7166cfdaddf1284cc268a68bc62e28/build.sbt)：Scala 2.13.12、Pekko 1.1.3、Pekko Persistence JDBC 1.1.1、Debezium 3.6.1.Final。

上游比较使用 Flink CDC **3.6 文档分支**、Flink **1.20 文档分支**和 Pekko **1.1 文档分支**。Pekko 1.1 在线文档目前显示 1.1.5，不能把文档示例版本当成项目运行版本。本文没有升级任何依赖，也没有在本轮重跑 Flink CDC/Paimon 对照实验。

全文使用四种证据标签：

- **源码事实**：当前基线中的实现与调用关系，不自动等于运行保证。
- **实验结果**：指定环境、故障窗口、数据集的实际观察。
- **设计提案**：计划建立的机制，尚未写入生产实现。
- **未验证**：证据尚不足，标记 `evidence_incomplete`，不给出通过结论。

前一组 [Flink CDC 深入系列](https://xuyinyin.cn/flink-cdc-series/) 是研究背景。本组 MySQL→MySQL 实验与前一组 Reader、Flink 作业、Paimon 实验属于不同证据，不能相互代替。

## 全系列验证范围汇总

| 判断对象 | 当前证据 | 结论与边界 | 详见 |
|---|---|---|---|
| 工作流恢复后重启 Engine | 源码；真实 Journal、两个 JVM | 固定 executionId 的中断执行可以进入恢复路径；不等于所有错误自动重试 | 二、三 |
| T 已提交、J 未持久化 | 真实 MySQL Source/Sink/Journal | 稳定截获 T/J=6/5 和 5/4；D 未推进 | 三 |
| 相同批次重放 | 1→1 的真实回调 | `AlreadyCommitted`；重放未增加目标 DML 审计；后续追平 | 三 |
| 重新分批 | 真实 Debezium 参数 16→1 | 同 batchId 对应不同元数据；账本冲突、Actor failed；不是 Reader 静默漏行证明 | 三 |
| 全量逐字段一致性 | 两组 64 行、6 列全表读取 | 成功组零差异；失败组缺 65、多 64，共同主键有 216 处字段差异 | 三 |
| 时区转换 | 默认 JVM 时区下的前置基线 | 64 行 DATETIME 偏移 8 小时；正式窗口实验固定 UTC；生产问题未修复 | 三 |
| T/J/D 新恢复协议 | 设计与故障矩阵 | 尚未实现，`evidence_incomplete` | 四 |
| 并行增量快照、DDL 屏障、外部 fencing | 差距分析与提案 | 没有本项目端到端通过证据 | 四 |
| 70 万逻辑表与资源共享 | 需求背景、成本模型 | 未做规模压测；当前每工作流独立 Engine 不是目标架构 | 一、五 |
| Pekko 比 Flink 更省或更快 | 架构分析 | 没有同环境、同负载对照数据，不给倍数结论 | 五 |
| 多节点接管、网络分区、Paimon | 本轮未执行 | `evidence_incomplete` | 三、四、五 |

## 可复查材料

[实验报告与复跑命令](../experiments/2026-10-18-cdc-journal-window.md)保存完整方法；[证据包](../experiments/evidence/2026-10-18-cdc-journal-window.tar.gz)包含日志、数据库导出、源目标全量行数据、T/J/D 和测试源码副本。校验见 [SHA256](../experiments/evidence/2026-10-18-cdc-journal-window.tar.gz.sha256)。实验测试设施随文章提交，生产协议保持基线不变。

旧的 `docs/blog` 是另一组调度平台文章。其中历史性能数字与“生产就绪”等表述不作为本系列证据。本系列的完成状态指文章与材料已整理，不表示 CDC 系统改造完成。
