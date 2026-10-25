# 技术附录与历史档案

[返回当前系列导读](../README.md)

主文只呈现业务问题、关键实现、代表性证据与推理。以下档案保留此前源码精读、旧失败、模型和阶段记录；其中的“当前”按原段落版本解释，不能套作当前代码能力。重排前完整文章固定在提交 `08e5123`，每份档案均保留永久链接。

## 历史档案

| 档案 | 内容 |
|---|---|
| [Actor 执行机制与资源实验](01-actor-execution-history.md) | 保留原技术分析及证据入口；当前结论见主文 |
| [旧交付、目标事务与 ACK 路径](02-delivery-and-ack-history.md) | 保留原技术分析及证据入口；当前结论见主文 |
| [真实 MySQL 与完整组历史实验](03-failure-experiments-history.md) | 保留原技术分析及证据入口；当前结论见主文 |
| [事件身份与完整组协议推导](04-group-protocol-history.md) | 保留原技术分析及证据入口；当前结论见主文 |
| [框架比较的历史基线](05-framework-comparison-history.md) | 保留原技术分析及证据入口；当前结论见主文 |
| [历史版本与验证范围](00-versions-and-evidence-history.md) | 保留原技术分析及证据入口；当前结论见主文 |

## 当前源码与完整实验材料

- [当前代码基线](https://github.com/Xuxiaotuan/pekko-reference/tree/55fad0cf27f3a289dbb5d4fb4f2a31d60a661059)：正文源码链接固定到该提交，文章提交不替代运行版本。
- [LOW/HIGH 完整报告与 61 项矩阵](../../../experiments/2026-10-25-bootstrap/README.md)、[逐项复算](../../../experiments/2026-10-25-bootstrap/final-summary.json)、[运行版本](../../../experiments/2026-10-25-bootstrap/final-version.json)、[归档索引](../../../experiments/2026-10-25-bootstrap/artifacts/archive-index.json)。
- [共享捕获阶段一](../../../experiments/2026-10-25-shared-capture/README.md)：预先可信基线、B/K/L 与独立消费，不能改称 LOW/HIGH 初始化。
- [启动恢复探针](../../../experiments/2026-10-25-capture-startup/README.md)、[真实结果](../../../experiments/2026-10-25-capture-startup/summary.json)、[摘要清单](../../../experiments/2026-10-25-capture-startup/SHA256SUMS.json)：保留 INITIAL-D 反例及测试配置。
- [双槽与资源释放](../../../experiments/2026-10-25-cdc-lab-pool/README.md)：隔离、并行、连接延迟释放各按其证据层级解释。
- [启动准入提议](../../../development/specs/2026-10-25-shared-reader-startup-recovery-design.md)、[生命周期提议](../../../development/specs/2026-10-25-cdc-platform-lifecycle-contract.md)：设计，不是已交付能力。

## 阅读代表性原始样本

第三篇四个初始化案例位于 `raw-evidence-07.zip`，目录前缀及子目录在正文给出。先看 `cut.json` 的 T/J 与 spool，再看原运行/恢复日志，最后对照 `final.json` 的完整行集与字段比较。I08 没有进程崩溃，不应要求不存在的恢复日志。

原始对账、日志和摘要不因文章重排重写；没有新增数据库运行。当前整体验收仍为 `evidence_incomplete`，历史字段对账不替代未完成的启动、DDL、接管或容量验证。
