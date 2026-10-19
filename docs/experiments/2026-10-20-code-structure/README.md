# 代码结构整理：编排、执行会话与资源所有权

2026-10-20。工作区基础 HEAD 为 `dca7d25cfcf7747d7d08231259c3353331755b84`，原有 A/B/C 和八窗口探针尚未提交。本记录区分本次结构整理、此前实现及最终回归；本次只做本地提交，不推送或发布。

## 改动与兼容边界

本次在已有 C 阶段实现上拆分职责，没有引入新模块、新依赖、节点类型或恢复策略：

| 文件 | 职责 |
|---|---|
| `WorkflowExecutionEngine` | 工作流校验、执行图和可靠批次链；由 625 行减至 501 行 |
| `ReliableExecutionSessions` | 同一 Engine 内会话登记、替代等待和取消的有界观测 |
| `ReliableExecutionControl` | 单次执行的批次接纳、取消、排空和物理释放确认 |
| `BatchSourceSession` | Source 流与关闭句柄、读取与关闭衔接 |
| `SourceResourceScope` | 异步获取前登记所有权、迟到资源关闭及清理失败信号 |

`WorkflowExecutionEngine` 的公开执行/取消入口及 `ReleaseStatus` 保留；拆出的两个执行内部类只在 engine 包内可见。`SourceResourceScope` 和 `SourceAcquisitionCleanupFailed` 保留原完整包名。Actor 事件、状态、Checkpoint、cursor、persistenceId 和序列化结构没有因本次拆分改变。

源码比对确认：相对本轮前工作区，只有 Engine 与 BatchSourceSession 两份已有 Scala 文件改变，新增三份职责文件。`processBatch` 的 T 提交 → J 持久确认 → Source ACK 主体逐字节一致，Control 方法主体及 ResourceScope 主体一致；Source、组装配器、Bridge 和 Sink 未修改。静态比对见[独立结果](structure-result.json)，不是端到端正确性的替代证明。

[源码导航](../../CODE_STRUCTURE.md)说明生产主链、各层归属、前端、示例和测试入口。本次本地提交也纳入此前已授权且未提交的 A/B/C 工作：示例迁移、构建收敛、API/管理界面对齐、实例级节点目录、会话释放和故障探针。其独立依据仍分别保留在 [A](../2026-10-19-stage-a-convergence/README.md)、[B](../2026-10-19-stage-b-console/README.md)、[C](../2026-10-19-stage-c-sessions/README.md)记录中，不将它们全部称作此次纯结构改动。

## 本轮验证

| 检查层级 | 实际结果 | 支持的结论与限制 |
|---|---|---|
| 拆分前针对性检查 | 44/44 通过 | 现有会话与执行测试基线 |
| 最终针对性检查 | 59/59 通过，5 套件 | 包括取消、排空、关闭失败、Actor Stop 和 H2 owner 释放后恢复；不与全模块重复累计 |
| 最终完整后端 | 440 通过、0 失败、2 套件中止；退出 1 | Calcite 两个既有类加载问题保留，完整测试门为 `evidence_incomplete` |
| 前端 | 5 项契约检查通过，构建通过 | 本次没有重新做浏览器联调；Vite 仍提示主包超过 500 kB |
| 生产 JAR | 构建和类清单检查通过 | 新职责类在包内；测试注册表和迁出的旧示例不在包内；不是运行该 JAR 的部署实验 |
| 源码与材料保全 | 203 份后端源码/资源有运行冻结清单；最终两处 EOF 空行整理另做编译等价核验；82 份旧实验文件哈希不变；四组旧材料的 38 项校验和通过 | 旧实验仍绑定其原始源码清单，没有改写历史结果 |
| 示例迁移复核 | 13 份被移出产品源码的文件均有逐字节一致副本 | 包括旧后端示例与前端编辑器，材料未删除 |

最终运行源码、构建配置和 UI 源码由[源码清单](source-manifest.json)定位；[运行清单](run-manifest.json)与[验证汇总](validation.json)记录实际运行。差异文件[structure-only.diff](structure-only.diff)只描述本轮结构拆分，相对本轮前的 A/B/C 工作区，不是相对 HEAD 的全部提交差异。

## 真实 MySQL 恢复证据

正式回归使用 MySQL 8.4.11、ROW/FULL、GTID ON，真实 Source、目标业务表/组账本/审计及 MySQL Actor Journal。复用隔离测试容器，每项使用新随机 schema。容器重启后 loopback 端口发生变化，探针先拒绝旧端口，再使用实际映射；没有连接业务数据库。

八窗口保留 T/J/D、原始 SourceRecord 与 ACK 顺序，逐字段比较 `id/label/amount/note/updated_at/revision`：

| 窗口 | 缺行 / 多行 / 字段差异 | 结论 |
|---|---|---|
| N0、J1、J2、C1、C0、K1、K2 | 均为 0 / 0 / 0，源/目标各 64 行 | 此次固定切点恢复收敛；组账本和审计另行核对 |
| C2 | 2 / 2 / 212，源/目标行数也都是 64 | **FAIL_TERMINAL**；真实 COMMIT 回复丢失后 Actor 记录失败，恢复未启动 Reader |

两个真实取消场景：空闲追平后取消确认 `Released`；T 已提交但 J 尚未推进时取消先返回 `TimedOut`，放行后按既有链完成 J/ACK 再确认关闭。两项最终均源/目标 64 行、六字段全量一致，命名 Debezium runner 为 0。

[八窗口离线核验](result.json)与[会话离线核验](session-result.json)分别重算原始材料；十项测试通过观察断言，**不表示十项自动恢复均成功**。C2 失败证据原样保留，没有清 Journal、重置 D 或手工补数据。

## 拆分中发现的实际回归

第一版拆分将 `cancelWithin` 搬入会话管理类，并直接调用管理类自己的 `cancel`，绕过了原来的虚调用 `Engine.cancel`。完整模块中两项测试因此失败：Actor Stop 的取消钩子等待超时，H2 恢复 fixture 的人工 J 闸门未得到中断，节点不能按时关闭。它们不是既有 Calcite 问题，也没有归因于并行压力。

最终由 Engine 将原来的 `cancel(executionId)` 按名传给取消观测方法，在原来的时点调用，从而保留子类钩子；没有放宽断言或修改持久化恢复协议。失败轮次、早期已完成的真实观察及第一版差异保留在归档 `checks/` 和 `exploratory/`。早期真实轮次被中断，不计入正式十项覆盖。修正后的 59 项、完整 440 项和正式十项重新运行。最终差异检查后，只移除两个新拆文件的尾部空行，再回归 59 项并打包；全部 621 个生产 class 的 SHA256 与真实实验使用的 class 一致。源码清单分别保留真实运行时和空行整理后的哈希。原始 `.diff` 材料包含上下文行的空白，保留原样；源码与其他文档单独进行差异空白检查。

## 复核与未验证边界

[原始材料](evidence.tar.gz)包含正式运行的完整行、T/J/D、原始回调、ACK、最终数据库 SQL 导出、源码快照、运行日志、前后端检查及初版失败记录。归档内有逐文件哈希；临时数据库凭据未打入归档。

```sh
cd docs/experiments/2026-10-20-code-structure
shasum -a 256 -c SHA256SUMS
python3 -B verify_structure.py
python3 -B verify.py
python3 -B verify_sessions.py
```

后端可用 Java 17 执行 `sbt 'pekko-server/test'`；两个 Calcite 中止仍会使命令失败。真实复跑需要显式启用外部集成测试，命令和环境变量见 [C 阶段说明](../2026-10-19-stage-c-sessions/README.md#7-如何复核)。默认测试不执行真实 MySQL 实验。前端执行 `cd xxt-ui && npm test && npm run build`。

本次没有证明吞吐或内存改善，也没有 70 万表容量、DDL、多 owner、共享 Reader、跨进程 fencing、滚动升级或生产部署证据。有界取消仍是等待预算，不能证明任意驱动已停止；未确认释放时仍不能据此启动替代会话。C2 自动恢复失败和两个 Calcite 中止继续保留。
