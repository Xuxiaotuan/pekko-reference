# C2 与 Checkpoint 明确拒绝：整体提交前审查

用户授权整体审查后提交、推送代码、测试及实验材料。此次不发布网站。审查基线为 `d046cce2dc850ea60bc3513db84378484b0a1043`；待提交内容还包含 C2 依赖的未提交 ExecutionPlan 实现，不能只提交 C2 的局部 diff。

## 审查结论与源码依据

主审及独立只读复核均未发现当前范围内的阻断问题。这不是“整个 CDC 平台不存在缺陷”的保证。

| 检查对象 | 当前源码结论 | 运行/测试证据与限度 |
|---|---|---|
| 恢复状态和预算 | Scheduled 与 AttemptStarted 先持久化；同一 execution 最多三次；Checkpoint 与重启不重置预算 | Actor 测试、真实 R1/R2/R3；已经开始的一次不因崩溃退款 |
| 尝试边界 | 初始化、Checkpoint 和 Engine 结果按随机 attempt token 隔离 | 旧回调测试、真实 R4；不是跨 owner fencing |
| 提交链路 | T → J → Source ACK 顺序保留；实际物理读取从 D 开始，J 只延续交付号 | 真实窗口、原始 ACK 前缀及组账本证据；不能把 T/J 写成 D |
| COMMIT 不确定 | Sink 不自行循环重试；Engine 排空并关闭后交由 Actor 仲裁 | 真实代理、旧目标事务迟到提交/回滚及 LOCK WAIT 材料 |
| 本地释放 | 观测超时不释放会话闸门，不据此创建替代 Reader | 真实 R5 与取消窗口；不能由 Reader close 推出服务端旧事务已回滚 |
| Checkpoint 明确拒绝 | 真实回复转为 protocol_conflict；Engine 保留类型，直接暂停且不新增预算 | 新真实 REJECT-J 与 REJECT-J-RETRY 为 0/1 次；初始化拒绝和 AlreadyStored 另有单测 |
| 计划依赖 | 内存计划绑定注册实例，CDC 直连不进入 Transform；计划不写 Journal | 编译/能力丢失/旧快照等现有回归；不表示所有连接器均有恢复保证 |

本轮只修正 `docs/CODE_STRUCTURE.md` 中仍将 C2 写成待实现或当前失败终态的现状描述，未改变生产或测试源码。旧实验报告中的限制、失败和“当时未提交”状态都是历史事实，不改写为当前通过。

## 本轮实际执行的验证

- 重新执行完整后端 `test` 和 `package`：488 项、79 套件、0 失败、0 中止；主 JAR 不含测试设施。
- 测试 JVM 使用 `system-shutdown-default=60s`，仓库默认 10 秒未改；不据此宣称默认等待的旧百万 Probe 清理已经验证。
- 两个离线 verifier 重新运行均 PASS，输出随本目录保存。核对正式矩阵 10 项自动收敛、7 项安全暂停、7 个历史窗口收敛及 2 项释放；安全暂停不是数据恢复成功。
- 当前所有 215 个 server 源码/构建文件摘要与最近正式 26 项 MySQL 实验完全相同；本轮没有重新启动数据库或再次运行 MySQL 矩阵。源码未变，因此采用原始真实运行加本轮独立证据重算，明确区分两者。
- 三组待提交实验目录的 SHA256SUMS 全部通过；创建本审查目录之前的 146 个实验文件哈希全部不变，包含旧 C2 FAIL_TERMINAL 和新拒绝负例的实际差异。
- `git diff --check` 通过；提交前按显式文件清单核对暂存区，排除凭据、运行设置、临时输出及无关文件。

[验证清单](validation.json)、[后端完整日志](backend.log.gz)、[运行源码摘要](source-manifest.json)、[自动恢复与暂停复核](automatic-result.json)、[历史及释放复核](regression-result.json)、[旧实验文件摘要](preserved-experiment-hashes.json)。

原始材料：[运行计划边界与旧 C2 失败](../2026-10-20-execution-plan-boundary/README.md)、[C2 真实恢复矩阵](../2026-10-20-c2-recovery/README.md)、[Checkpoint 拒绝修复与 26 项真实矩阵](../2026-10-21-checkpoint-rejection/README.md)。这些目录中的原始回调、ACK、真实 Journal、T/J/D、SQL dump、审计和六字段全量数据全部保留。

## 保留的限制

暂停后仍可能缺行、多行或字段不一致，目前没有人工审核后的继续/放弃 API。新增 Journal 事件不保证旧二进制直接降级，历史已清除上下文的终态失败不会自动复活。

DDL、多 owner、共享 Reader、新快照算法、70 万表、生产长期压力、跨机房、长时间网络分区、服务端掉电和 binlog 清理/重置均未由这些实验覆盖。不能从单 owner 固定 Schema 的恢复结果推出一般端到端 exactly-once，或与 Flink CDC 整体成熟度相同。
