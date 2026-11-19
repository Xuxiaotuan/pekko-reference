# 系列实验历史与版本补充
> 本文件保存五篇正文中移出的历史补测、候选版本和运行日志摘要。正文保留与论证直接相关的实验结论；本文件保留完整时间线和失败材料入口。
> 当前文章源码基线：`cc09559`；正文整理提交：`547f716`。历史实验按各自冻结版本解释。

## 1. 01-why-pekko-for-resident-cdc.md

## 后续补测与边界

补充实测中，关闭请求在工厂创建前到达和 MySQL SDK task 启动时到达这两个固定窗口均已观察到资源空闲。关闭期间结果仍按 UNKNOWN 处理；task 长期无法结束时，有限的关闭工作线程仍可能被占满。它们是资源释放样本，并非 70 万表容量或高可用接管证明。 详见[后续实验](../../experiments/2026-10-28-cdc-followup/README.md)。上文的失败是当时版本的历史结果，原始材料保留。

## 2026-10-29 补充验证

2026-10-29 补测中，工厂返回后、SDK 启动前收到关闭曾在 180 秒内无法确认连接全部释放；后续修复该固定窗口及相邻两个启动窗口已实测通过。SDK 长期未进入轮询或结束时，有限关闭线程仍可能被占满；单次释放不构成容量或高可用保证。 [实验材料与范围](../../experiments/2026-10-29-p0-followup/README.md)。

## 2026-10-29 写前锚点实验

2026-10-29 写前锚点实验的版本中，Reader 对未绑定 B 采取安全暂停；当时平台 grant 和旧资源确认后的自动接续尚未打通。 详见[本轮证据](../../experiments/2026-10-29-unknown-b/README.md)。

## 事务释放与隔离探针

真实 MySQL 与独立旧 JVM 探针中，数据库暂时没有旧账号连接时观察器返回 CONFIRMED，但未隔离的旧 JVM 随后仍重新连接并提交；后续采样才变为 UNKNOWN。恢复配额需要进程隔离与数据库排空共同证明。 [实验与边界](../../experiments/2026-10-29-b-release-proof/README.md)。

## 受管恢复改变了什么

共享 Reader 的未知 B 恢复候选把 guardian 隔离、旧连接与事务排空和持久许可串起来。只有隔离与排空得到证明，旧 UNKNOWN grant 才能按严格条件释放；无法证明时仍暂停。隔离是执行者边界，数据库一时无连接并不能代替它。

**实验结果：**旧候选在独立 guardian JVM、真实 MySQL、Journal 和 Sink 下完成一例迟提交恢复，新表源和目标各 64 行、384 字段一致；该次没有人工业务写入，但后继 worker 在清理中被强制终止，不能证明 CDC 正常停机。[样本与源码摘要](../../experiments/2026-10-29-cdc-managed-b-e2e-wake-candidate/README.md)。更早样本需人工触发 binlog，保留为[失败及干预证据](../../experiments/2026-10-29-cdc-managed-b-e2e-old-candidate/README.md)。

**边界：**当前候选基于提交 `62ec856f20a81f3da8d56caf290754f8361ac732` 之后的未提交工作区；不同实验的精确源码摘要以各自证据目录为准，不能把旧样本归给后续修改。 同一候选版本的六项固定窗口结果见下文；除单个 C1 样本外的生产入口窗口、guardian 自身重启和跨主机接管未验证，这些结果不是 70 万表容量或多 owner 高可用证明。

## 六个固定窗口的验证范围

[六项固定窗口的完整证据](../../experiments/2026-10-29-cdc-managed-b-e2e-frozen/README.md)使用同一候选源码、独立 guardian JVM、真实 MySQL、Actor Journal 与 MySQL Sink；这六项的协调器运行在测试 JVM、worker 显式接线；另有一个 [生产入口 C1 样本](../../experiments/2026-10-29-cdc-production-entry-c1/README.md)，实际经过独立 `SnapshotGuardianMain`、`SnapshotSupervisorMain` 和 `PekkoServer` 内的 `CdcPlatformExtension`。

C0、C1、迟提交和迟回滚四项在接管后进入 STREAMING，各自源与目标 64 行、384 字段相等。guardian 进程失联或 socket 不可达的两项保留 UNKNOWN grant 和无许可状态，源有 64 行而目标仍为空，是安全暂停，不是数据完成。四个收敛样本清理时强制停止后继 worker，CDC owner 仍为 ACTIVE；正常停机与整个系统的高可用尚未由此证明。

生产入口的独立 C1 样本在真实 MySQL、Journal 和 Sink 上恢复至 STREAMING，源与目标各 64 行、384 字段相等。前两次运行分别在故障注入前因测试配置缺项和明文代理不兼容而失败，原始材料保留；本样本没有生产 raw ACK 顺序证据，强制清理后 CDC owner 仍为 ACTIVE，不证明正常停机。其余故障窗口仍仅有测试入口证据。 [生产入口 C1 证据](../../experiments/2026-10-29-cdc-production-entry-c1/README.md)。

当前未知 B 恢复还受一个明确的规模边界约束：源控制表反查最多 4096 行，且当前没有控制行 GC。历史控制行超过该上限时，未知 B 的恢复会安全暂停。持续新增和取消表会积累控制行，所以 70 万表长期动态订阅不能仅凭共享 Reader 和冷索引实验推断可恢复。 [组件实验与限制](../../experiments/2026-10-29-cdc-b-proof-component/README.md)。

## 受管独立入口的边界

独立 CDC worker 可以由 guardian 创建与隔离，启动链路不再启动 Workflow Sharding 和调度 Actor，最终实验中 supervisor 和 worker 的调度开关均为 false。当前候选的 supervisor 使用专用 CDC 控制存储类，兼容期间仍依赖旧锁和配额表。[真实环境与源码边界](../../experiments/2026-11-08-standalone-guardian/README.md)。

当前 CDC 受管入口已接入专用控制存储类；兼容期间仍使用旧 admission 锁、审计序列和配额表，不能视为完成数据库层迁移。 [候选实现与验证范围](../../experiments/2026-11-08-standalone-guardian/README.md)。

补充实验在真实 MySQL、Journal 与 Sink 中注入旧格式 `unknown` 配额并终止受管 worker：隔离排空后配额回到旧队列，CDC 新 worker 追平 2 行、6 字段。配额由实验注入，未证明真实旧 Workflow 作业及全部迁移窗口。[证据与边界](../../experiments/2026-11-08-standalone-guardian/README.md)。

真实旧 Workflow 作业的单个故障窗口已用 CDC 控制存储接管；两者仍共用旧锁，数据库层切换尚未完成。 [真实作业证据](../../experiments/2026-11-08-standalone-guardian/README.md)。

## 2026-11-09 持久化切换授权与运行时身份

本轮把切换拆成两个提交点。旧 worker 只有在 guardian 隔离 receipt、旧账号排空和 owner `db_drained` 成立后，才会迁移旧配额并写入新的策略身份；新 worker 获得自己的 recovery permit 后，才把旧 Workflow 定义中的用户名映射为运行时账号。原始 Workflow/Split 指纹仍按旧定义计算，密码只从 `passwordEnv` 读取，不写入 Journal 或控制表。

在真实 MySQL、Actor Journal 和 MySQL Sink 的 `KILL_T_BEFORE_J` 实验中，源表 10 行、目标表 10 行、6 个投影字段逐字段一致；第一批 T 在恢复后以 replay 方式再次确认。`KILL_J_AFTER_STORE`、`FENCE_COMMIT_REPLY_LOST`、`STARTUP_COMMIT_REPLY_LOST`、`B_RECOVERY_CRASH`、`DRAIN_COMMIT_REPLY_LOST`、`QUOTA_COMMIT_REPLY_LOST`、`REVOKE_COMMIT_REPLY_LOST`、`OBSERVER_DENIED` 和 `STALE_GRANT` 也完成了本轮固定窗口验证。完整材料见[持久化切换授权实验](../../experiments/2026-11-09-persistent-cutover-authorization/README.md)及各场景目录。

这里的“通过”指本轮真实实验结果。代码层已经实现绑定表、策略切换、permit 顺序和运行时重绑定；切换授权器仍由受管 supervisor 的显式入口调用，尚未扩展到 DDL、多 owner、共享 CDC 或跨实例迁移。未执行的历史场景、旧失败证据和这些边界仍然保留。

## 技术附录与证据入口

[历史源码分析与实验档案](appendices/01-actor-execution-history.md)保留此前全文；[附录索引](appendices/README.md)保留旧版本和失败材料。[A1/A2 报告](../../experiments/2026-10-27-capture-admission/README.md)记录已提交的启动准入实测，[本轮平台报告](../../experiments/2026-10-27-cdc-platform/README.md)承担新调度、生命周期与入口的组合验收。本文解释职责与资源因果链，不把两个版本的结果合成一次实验。

## 2026-11-12 Runtime GC 更新

本轮已把 L 安全 GC 接入独立 CDC Runtime。GC 使用单个有界维护 worker，审计请求、namespace 清点、保护 pin、GC operation 和批次游标均持久化；表级审计仍使用原 BootstrapJournal、目标账本和 T/J/D 证据。旧 namespace 启用前会重新清点订阅、消费者、Journal、账号、连接和事务；清点不完整、未知 B、外来 Journal、危险账号或资源释放未知时安全暂停。

真实入口 RG01—RG07 共 7 项通过，其中未知 B 和异常重启按预期 SAFE_PAUSED；L GC 的 LG01—LG16 加容量补测共 17 项通过。LG15 首次身份修复后未重启 Reader，保留失败材料；按重启边界重跑后通过。此前 5 个完整回归失败套件已修正；同一 JDK 17 的完整回归为 722 成功、0 失败、415 个专用外部环境取消。固定 RG01 负载又连续完成 10 轮、约 12 分钟的生命周期 soak，逐字段对账和最终资源采样均通过。

源码结论、真实结果和未验证边界分别记录在 [Runtime GC 实验报告](../../experiments/2026-11-12-runtime-gc/README.md)。本轮代码修复了 GC no-worthwhile-space-saving 的安全重规划：小 GROUP 保留原始 L、只前移 operation 游标，旧 BLOCKED schedule 会在下一 tick 进入 REPLANNING；真实集群重跑的结果以新 `replan-*` 材料为准。这组结果不代表 70 万真实活跃表、DDL、多 owner guardian 接管、自动扩容或 InnoDB 文件缩容已经验收。

## 本轮最终 acceptance 补充（2026-11-13）

独立 CDC Runtime 在单实例专用 acceptance 环境跑通了一条可复核主线：共享 Reader 捕获 `source_rows`，任务经 B/F 初始化后进入持续消费；真实更新、插入、删除与目标逐字段对账一致。GC namespace 经过账号、Journal、目标账本和 namespace permit 校验后进入 READY，GC proof 进入 ACTIVE。Kubernetes 部署改为让 JVM 直接作为 PID 1，停止时 owner 为 STOPPED、grant 为 0、源端连接为空，重新启动后原任务仍为 STREAMING/HEALTHY。

这只证明单表真实链路、受保护 GC 和一次重启窗口；70 万物理表、长期吞吐、DDL、多 owner 自动接管和跨实例共享 Reader 仍未验证。旧版本重启后的 GC 小批量删除因节省空间不足而安全 BLOCKED，原始 L 保留；新版本的自动重规划需以部署后的真实 `replan-*` 证据确认，不能用本地测试替代。

## 2026-11-13 KubeSphere 集群流水线与线上边界

参考集群中已有成功的 KubeSphere Pipeline 后，本轮实际运行了 `pekko-cdc-runtime` PipelineRun `pekko-cdc-runtime-n7kjw`：构建、Harbor 推送和 `bigdata-lab/pekko-cdc-runtime` 滚动部署均成功。新 Pod 重启后 owner 为 `ACTIVE`，Source MySQL 保留一个 Debezium Binlog Dump 会话且没有 InnoDB 未提交事务；Source 与 Sink 当前 `source_rows/target_source_rows` 的两行六字段逐字段相等。原始对象、状态和对账材料见 [KubeSphere 集群证据](../../experiments/2026-11-12-runtime-gc/cluster-live-20261113/kubesphere-20261113/)。

这条 KubeSphere 结果与 GitHub Actions deploy 分开统计：GitHub Actions 的 verify/image 成功，但 deploy 因环境没有 `KUBE_CONFIG_DATA` 仍保留失败证据。线上 GC 实际返回 `BLOCKED / ACK proof budget exceeded`，所以只能证明安全暂停，没有证明 ACK proof 超预算后的自动重规划、自动扩容或跨重启继续删除。人工清理旧 UNKNOWN owner 后再重启是实验门槛，不是自动 fencing 已完成的证明；70 万真实活跃表、DDL、多 owner 和长期吞吐仍未验证。


## 2026-11-13 ACK proof budget 修复与真实集群结果

线上长链根因已经核实：`primary` 有 7,110 条 ACK，旧实现的固定 4,096 条链预算必然触发 `ACK proof budget exceeded`。本轮改用持久化 `CaptureAckPaging`：每次最多推进 128 个节点，分页状态先提交，proof 完成后才进入 GC 删除。真实 KubeSphere PipelineRun `pekko-cdc-runtime-g7kcr` 成功部署 `build-3-61495e0f2b5e`；重启后 owner 为 `ACTIVE`，任务为 `STREAMING/ADMISSION_READY/HEALTHY/RELEASED`，actual-D proof head `7742`、ACK 节点 `7731`、page `done=1`。原 `DELETING 42→44` operation 已变为 `DONE`，删除 1 个 GROUP、回收 235 字节；Source 与 Sink 两行六字段逐字段相等。

这次真实结果证明长 ACK 链可分页、可跨运行推进并安全重规划；此前 3 个旧 Sleep 会话和第一次 `OWNER_RELEASE_UNPROVEN` 仍作为失败材料保留。分页 proof 表本身尚无独立 GC，70 万活跃表、无限 ACK 历史、自动扩容、DDL 和多 owner 仍未验证。证据见 [ACK proof 预算实验](../../experiments/2026-11-12-runtime-gc/cluster-live-20261113/gc-ack-proof-budget-20261113/)。


## 2026-11-13 分页 proof 表 GC 与容量边界

分页 proof GC 只回收已完成、非当前 actual-D、未被 GC audit、GC frame 或 Recovery Anchor 引用的 proof；refs、nodes、prior、pins、page 按顺序删除，ACK、Journal、L 和目标账本保留。单轮最多 8 个 proof，单个 proof 子行超过 20,000 时安全阻塞。

真实运行中旧 proof `d9b4921a...`（head 7742）被回收，新 proof `f777e7d3...`（head 13052）保留；Source 与 Sink 两行六字段逐字段一致，重启后 owner 仍为 ACTIVE。证据见 [proof GC 材料](../../experiments/2026-11-12-runtime-gc/cluster-live-20261113/gc-ack-proof-gc-20261113/)。功能代码已真实部署；仅删除计数持久化的后续流水线因 Jenkins 长时间 RUNNING 被取消，标为 `evidence_incomplete`。proof state 自身二次 GC、70 万活跃表、并发 B proof 和无限 ACK 历史仍未验证。

## 2. 02-source-and-recovery-contract.md

## 后续补测与边界

新版本将待绑定 B 的取消经过 capture 的命令锁和准入检查：源事务结果不确定时拒绝确认取消；只有实际持久化绑定后才发出 `B_BOUND`。真实 MySQL 的提交迟到、回滚和取消后迟到绑定样本支持对应窗口的行为；该实验版本中未知结果下的自动恢复仍未完成。 详见[后续实验](../../experiments/2026-10-28-cdc-followup/README.md)。上文的失败是当时版本的历史结果，原始材料保留。

## 2026-10-29 补充验证

2026-10-29 的 B04 指定窗口在确认旧资源释放后，重建 Reader 和相同 Journal 身份的 Actor；异常前未 ACK 的写入被追平，再次写入后 4 行、24 字段相等。该实验没有解决 B_PENDING 在 COMMIT 结果未知时的持久化判定。 [实验材料与范围](../../experiments/2026-10-29-p0-followup/README.md)。

## 2026-10-29 写前锚点实验

2026-10-29 写前锚点实验版本在源 B 写入前，把实际 D 的 L ACK 序号和摘要与 B 意图一起持久化；该版本的恢复校验只接受相同的已覆盖 D。后续 B_BOUND 候选的 ACK 父链和覆盖条件见下文。真实迟提交样本记录到锚点与 D 相等，但 Journal 仍为 PAUSED，不能称自动恢复。 详见[本轮证据](../../experiments/2026-10-29-unknown-b/README.md)。

## 事务释放与隔离探针

真实源事务的提交、回滚和迟提交组件探针分别保存了连接 ID 与 InnoDB 事务；迟提交在旧事务未结束时被拒绝。写前 B 锚点仍不能单独证明旧 JVM 无法重连，该探针版本中 UNKNOWN grant 自动恢复尚未接入。 [实验与边界](../../experiments/2026-10-29-b-release-proof/README.md)。

## 受管未知 B：从锚点到恢复许可

**源码结论：**候选恢复先核对 guardian 身份和隔离证明及旧资源排空，再验证实际完成态 D 与 L 的 ACK 父链、B 意图、订阅和 Schema。B_PENDING 可从匹配的原 D 重读；B_BOUND 只有在 ACK 链引用其 BARRIER 时可认定覆盖，否则须满足原 D 重放条件。源控制表反查遇到未知 STOP、fence 或不匹配的 WAKE 等情况会暂停。源 marker 行本身不是原始 ACK 或 D 已推进的证据。

确认条件后，状态库事务记录恢复许可、预算和 grant 转换；Journal 接受恢复后消费许可。明确拒绝和结果未知按不同分支处理。原始回调 ACK 与 D 仍由 Reader 的现有确认路径推进，许可不会直接推进它们。源码中有界反查超限会暂停，不保证大规模历史均可自动恢复。

**实验结果：**[真实 MySQL 组件样本](../../experiments/2026-10-29-cdc-b-proof-component/README.md)验证上述 D/ACK 证明及许可事务的若干路径，但其中 D、ACK、guardian 身份为合成数据。另有[真实迟提交旧候选样本](../../experiments/2026-10-29-cdc-managed-b-e2e-wake-candidate/README.md)，其数据对账通过但不代表完整矩阵。当前候选基于提交 `62ec856f20a81f3da8d56caf290754f8361ac732` 之后的未提交工作区；不同实验的精确源码摘要以各自证据目录为准，不能把旧样本归给后续修改。

## 固定窗口如何验证 B 与许可

[六项固定窗口的完整证据](../../experiments/2026-10-29-cdc-managed-b-e2e-frozen/README.md)使用同一候选源码、独立 guardian JVM、真实 MySQL、Actor Journal 与 MySQL Sink；这六项的协调器运行在测试 JVM、worker 显式接线；另有一个 [生产入口 C1 样本](../../experiments/2026-10-29-cdc-production-entry-c1/README.md)，实际经过独立 `SnapshotGuardianMain`、`SnapshotSupervisorMain` 和 `PekkoServer` 内的 `CdcPlatformExtension`。

C0（COMMIT 未转发）、C1（COMMIT 已成功但回复丢失）、迟提交、迟回滚均恢复至 STREAMING；四项许可记录的 `marker_present` 分别为 0、1、1、0，许可均已消费、旧 grant 已释放，最终各 64 行、384 字段相等。样本保留切点 B、实际 D、L、T、J 和原始 ACK 日志，不以 marker 行推断 ACK。

后继恢复前 guardian 进程失联或 socket 不可达的两项没有发许可，旧 grant 保持 UNKNOWN，目标 0 行；它们验证的是暂停边界。清理四个收敛样本时强制停止后继 worker，CDC owner 仍 ACTIVE，不能把对账通过写成正常停机。

生产入口的独立 C1 样本在真实 MySQL、Journal 和 Sink 上恢复至 STREAMING，源与目标各 64 行、384 字段相等。前两次运行分别在故障注入前因测试配置缺项和明文代理不兼容而失败，原始材料保留；本样本没有生产 raw ACK 顺序证据，强制清理后 CDC owner 仍为 ACTIVE，不证明正常停机。其余故障窗口仍仅有测试入口证据。 [生产入口 C1 证据](../../experiments/2026-10-29-cdc-production-entry-c1/README.md)。

`CdcManagedPendingB` 在证明未知 B 时最多反查 4096 条源控制表记录；超过上限即拒绝恢复并安全暂停。当前没有控制行 GC，长期新增、取消表可能积累历史记录；这是恢复可用性的明确上限，而不是已经验证的 70 万表支持能力。 [组件实验与限制](../../experiments/2026-10-29-cdc-b-proof-component/README.md)。

## 独立入口的受管恢复

受管模式先取得 guardian 隔离证明并确认数据库排空，才允许新的 worker 接续；连接属性不可见时观察结果不完整，不能作为排空证明。单表实时场景的两次真实故障和最终候选关闭调度开关后的数据追平见[实验记录](../../experiments/2026-11-08-standalone-guardian/README.md)。未知 B 与源端 fencing 没有因这一结果得到证明。

新的 CDC 控制存储在旧 owner 已确认排空后，按旧规则释放遗留 Workflow 配额；旧任务重新进入原队列，不会自动转换成 CDC 任务。 [候选实现与验证范围](../../experiments/2026-11-08-standalone-guardian/README.md)。

补充实验在真实 MySQL、Journal 与 Sink 中注入旧格式 `unknown` 配额并终止受管 worker：隔离排空后配额回到旧队列，CDC 新 worker 追平 2 行、6 字段。配额由实验注入，未证明真实旧 Workflow 作业及全部迁移窗口。[证据与边界](../../experiments/2026-11-08-standalone-guardian/README.md)。

真实旧 Workflow 作业在目标已提交、Journal 尚未确认时被终止，CDC 控制存储完成旧配额释放；这不证明未知 B 或独立锁切换。 [真实作业证据](../../experiments/2026-11-08-standalone-guardian/README.md)。

## 2026-11-09 持久化切换授权与运行时身份

本轮把切换拆成两个提交点。旧 worker 只有在 guardian 隔离 receipt、旧账号排空和 owner `db_drained` 成立后，才会迁移旧配额并写入新的策略身份；新 worker 获得自己的 recovery permit 后，才把旧 Workflow 定义中的用户名映射为运行时账号。原始 Workflow/Split 指纹仍按旧定义计算，密码只从 `passwordEnv` 读取，不写入 Journal 或控制表。

在真实 MySQL、Actor Journal 和 MySQL Sink 的 `KILL_T_BEFORE_J` 实验中，源表 10 行、目标表 10 行、6 个投影字段逐字段一致；第一批 T 在恢复后以 replay 方式再次确认。`KILL_J_AFTER_STORE`、`FENCE_COMMIT_REPLY_LOST`、`STARTUP_COMMIT_REPLY_LOST`、`B_RECOVERY_CRASH`、`DRAIN_COMMIT_REPLY_LOST`、`QUOTA_COMMIT_REPLY_LOST`、`REVOKE_COMMIT_REPLY_LOST`、`OBSERVER_DENIED` 和 `STALE_GRANT` 也完成了本轮固定窗口验证。完整材料见[持久化切换授权实验](../../experiments/2026-11-09-persistent-cutover-authorization/README.md)及各场景目录。

这里的“通过”指本轮真实实验结果。代码层已经实现绑定表、策略切换、permit 顺序和运行时重绑定；切换授权器仍由受管 supervisor 的显式入口调用，尚未扩展到 DDL、多 owner、共享 CDC 或跨实例迁移。未执行的历史场景、旧失败证据和这些边界仍然保留。

## 技术附录与证据入口

[历史源码分析与实验档案](appendices/02-delivery-and-ack-history.md)保留本篇此前全文；[附录索引](appendices/README.md)集中提供版本、完整矩阵和原始材料入口。[A1/A2 报告](../../experiments/2026-10-27-capture-admission/README.md)与[平台报告](../../experiments/2026-10-27-cdc-platform/README.md)分别给出后续版本的证据入口，不覆盖旧材料。

## 2026-11-12 Runtime GC 更新

本轮已把 L 安全 GC 接入独立 CDC Runtime。GC 使用单个有界维护 worker，审计请求、namespace 清点、保护 pin、GC operation 和批次游标均持久化；表级审计仍使用原 BootstrapJournal、目标账本和 T/J/D 证据。旧 namespace 启用前会重新清点订阅、消费者、Journal、账号、连接和事务；清点不完整、未知 B、外来 Journal、危险账号或资源释放未知时安全暂停。

真实入口 RG01—RG07 共 7 项通过，其中未知 B 和异常重启按预期 SAFE_PAUSED；L GC 的 LG01—LG16 加容量补测共 17 项通过。LG15 首次身份修复后未重启 Reader，保留失败材料；按重启边界重跑后通过。此前 5 个完整回归失败套件已修正；同一 JDK 17 的完整回归为 722 成功、0 失败、415 个专用外部环境取消。固定 RG01 负载又连续完成 10 轮、约 12 分钟的生命周期 soak，逐字段对账和最终资源采样均通过。

源码结论、真实结果和未验证边界分别记录在 [Runtime GC 实验报告](../../experiments/2026-11-12-runtime-gc/README.md)。本轮代码修复了 GC no-worthwhile-space-saving 的安全重规划：小 GROUP 保留原始 L、只前移 operation 游标，旧 BLOCKED schedule 会在下一 tick 进入 REPLANNING；真实集群重跑的结果以新 `replan-*` 材料为准。这组结果不代表 70 万真实活跃表、DDL、多 owner guardian 接管、自动扩容或 InnoDB 文件缩容已经验收。

## 本轮真实 Reader/Sink 补充（2026-11-13）

最终 acceptance 使用真实 MySQL Source、Debezium Reader、Actor Journal 和 MySQL Sink：`B=binlog.000017:325156481`，`F=binlog.000017:325157844`，全量目标与 Source 逐字段一致；随后更新、插入、删除仍逐字段一致。任务 Journal revision 从 0 增长到 12，L/ACK、offset、目标组账本和 GC proof 均可在 State MySQL 中核对。

一次 PID1 修复后的停止/重启证明了 owner STOPPED → 新 owner ACTIVE 的正常窗口；这不是未知 B 或旧 owner 未排空时的自动接管证明。旧版本重启后的 GC 删除因小批次没有达到 128 字节节省阈值而 BLOCKED，原始 L 没被删除；新版本只允许安全跳过该帧并继续找后续可回收帧，真实集群是否完成重规划以部署后的证据为准。该结果支持“证明不足就保留 L”的协议边界，不能写成任意历史都能自动回收。

## 2026-11-13 KubeSphere 集群流水线与线上边界

参考集群中已有成功的 KubeSphere Pipeline 后，本轮实际运行了 `pekko-cdc-runtime` PipelineRun `pekko-cdc-runtime-n7kjw`：构建、Harbor 推送和 `bigdata-lab/pekko-cdc-runtime` 滚动部署均成功。新 Pod 重启后 owner 为 `ACTIVE`，Source MySQL 保留一个 Debezium Binlog Dump 会话且没有 InnoDB 未提交事务；Source 与 Sink 当前 `source_rows/target_source_rows` 的两行六字段逐字段相等。原始对象、状态和对账材料见 [KubeSphere 集群证据](../../experiments/2026-11-12-runtime-gc/cluster-live-20261113/kubesphere-20261113/)。

这条 KubeSphere 结果与 GitHub Actions deploy 分开统计：GitHub Actions 的 verify/image 成功，但 deploy 因环境没有 `KUBE_CONFIG_DATA` 仍保留失败证据。线上 GC 实际返回 `BLOCKED / ACK proof budget exceeded`，所以只能证明安全暂停，没有证明 ACK proof 超预算后的自动重规划、自动扩容或跨重启继续删除。人工清理旧 UNKNOWN owner 后再重启是实验门槛，不是自动 fencing 已完成的证明；70 万真实活跃表、DDL、多 owner 和长期吞吐仍未验证。


## 2026-11-13 ACK proof budget 修复与真实集群结果

线上长链根因已经核实：`primary` 有 7,110 条 ACK，旧实现的固定 4,096 条链预算必然触发 `ACK proof budget exceeded`。本轮改用持久化 `CaptureAckPaging`：每次最多推进 128 个节点，分页状态先提交，proof 完成后才进入 GC 删除。真实 KubeSphere PipelineRun `pekko-cdc-runtime-g7kcr` 成功部署 `build-3-61495e0f2b5e`；重启后 owner 为 `ACTIVE`，任务为 `STREAMING/ADMISSION_READY/HEALTHY/RELEASED`，actual-D proof head `7742`、ACK 节点 `7731`、page `done=1`。原 `DELETING 42→44` operation 已变为 `DONE`，删除 1 个 GROUP、回收 235 字节；Source 与 Sink 两行六字段逐字段相等。

这次真实结果证明长 ACK 链可分页、可跨运行推进并安全重规划；此前 3 个旧 Sleep 会话和第一次 `OWNER_RELEASE_UNPROVEN` 仍作为失败材料保留。分页 proof 表本身尚无独立 GC，70 万活跃表、无限 ACK 历史、自动扩容、DDL 和多 owner 仍未验证。证据见 [ACK proof 预算实验](../../experiments/2026-11-12-runtime-gc/cluster-live-20261113/gc-ack-proof-budget-20261113/)。


## 2026-11-13 分页 proof 表 GC 与容量边界

分页 proof GC 只回收已完成、非当前 actual-D、未被 GC audit、GC frame 或 Recovery Anchor 引用的 proof；refs、nodes、prior、pins、page 按顺序删除，ACK、Journal、L 和目标账本保留。单轮最多 8 个 proof，单个 proof 子行超过 20,000 时安全阻塞。

真实运行中旧 proof `d9b4921a...`（head 7742）被回收，新 proof `f777e7d3...`（head 13052）保留；Source 与 Sink 两行六字段逐字段一致，重启后 owner 仍为 ACTIVE。证据见 [proof GC 材料](../../experiments/2026-11-12-runtime-gc/cluster-live-20261113/gc-ack-proof-gc-20261113/)。功能代码已真实部署；仅删除计数持久化的后续流水线因 Jenkins 长时间 RUNNING 被取消，标为 `evidence_incomplete`。proof state 自身二次 GC、70 万活跃表、并发 B proof 和无限 ACK 历史仍未验证。

## 3. 03-real-mysql-journal-failure.md

## 本轮补测：通过和失败必须分开

A10 固定主键变化的原始 ACK 与真实 D 行锁，崩溃后重启，四行共 24 字段相等；A13 并发请求样本中的三张活动表各四行、24 字段相等。它们有真实 Actor Journal、Sink 和独立对账，但使用预复制基线。旧版本创建的 CBOR Journal 与 L 前缀也在一次新版本恢复中保留，不能外推到所有旧 manifest。

B04 则先让真实 Actor 和 Sink 应用一次更新，再关闭 Actor 并在真实 Reader 回调中注入异常。失败回调 4 未确认，ACK 只返回 0—3，T/J 保持不变；源与目标有两个字段差异。服务端资源最初为 UNKNOWN，后续观察到可复用。此样本没有执行异常后的自动恢复，不能把差异写成收敛。

B08 在真实 Actor/Sink 已应用更新后切断观察连接：关闭和复查均为 server UNKNOWN、owner 未释放且不可复用，4 行 24 字段相等、T 1/J 4。它没有验证恢复观察或自动重启。

B10 使用独立旧 JVM 和测试代理保留真实 L 事务：旧 JVM 退出后，新 JVM 未创建 Engine、保持 UNKNOWN，事务回滚后仍未自动接管。初始化基线 64 行 384 字段相等，故障后保留五字段差异，不能称目标已收敛。

A14 在源 COMMIT 未知时的取消测试失败；B05 的启动与关闭竞争在 180 秒观察期内未确认释放；进一步在真实 Debezium MySQL task 的 `start` 前加测试门，两组独立槽均复现，测试 JVM 退出后槽位空闲。该切点位于 调用原 task 的 start 实现之前，不能断言永久泄漏。首轮 B08 专用 MySQL 重启还暴露测试设施 TIME_WAIT 检查问题；恢复该专用实例、修正测试设施后重试通过。失败与重试材料分别见[补测报告](../../experiments/2026-10-28-ab-matrix/README.md)。

## 后续补测与边界

后续版本重跑了未决源 COMMIT 与关闭、提交迟到和回滚、工厂阻塞及实际 SDK task 启动时关闭等固定窗口。Reader 级样本中 owner 锁未过早释放；完整初始化作业的未知 B 实验按后续报告分别记录，不能用其他表的对账结果代替被暂停表的初始化完成。 详见[后续实验](../../experiments/2026-10-28-cdc-followup/README.md)。上文的失败是当时版本的历史结果，原始材料保留。

## 2026-10-29 补充验证

2026-10-29 补测重建 Reader 和 Actor 后恢复了 B04 指定样本，保留故障前后的 ACK、T/J/D 和 4 行 24 字段对账；另一关闭窗口先复现 180 秒内未空闲，修复后该窗口及相邻两个启动窗口通过，原失败保留。 [实验材料与范围](../../experiments/2026-10-29-p0-followup/README.md)。

## 2026-10-29 写前锚点实验

后续真实迟提交样本中，新表 a 的 B 意图锚点与实际 D 一致，活动表 b 为 64 行逐字段相等；a 源端 64 行、目标 0 行，Journal 停在 PAUSED。组件测试中的回滚和重启不能替代端到端恢复实验。 详见[本轮证据](../../experiments/2026-10-29-unknown-b/README.md)。

## 事务释放与隔离探针

提交、回滚及迟提交的事务释放组件探针，以及旧 JVM 在数据库空闲后重连写入的反例已补充。它们没有包含 Actor Journal 或 Sink，不能作为端到端恢复和逐字段一致性证据。 [实验与边界](../../experiments/2026-10-29-b-release-proof/README.md)。

## 受管迟提交样本：隔离、排空与对账

在[WAKE 候选样本](../../experiments/2026-10-29-cdc-managed-b-e2e-wake-candidate/README.md)中，真实源事务在 B_PENDING 时保持在途。旧 worker 已隔离但事务未结束时，接管仍等待排空；释放代理后原事务迟提交。控制表 WAKE 使旧 binlog 连接感知断连，之后后继 worker 按许可恢复。样本中没有人工业务写入，新表恢复至 STREAMING，源、目标各 64 行，384 个字段全部相等；故障切点的 B、T/J/D、许可、签名证明和原始 ACK 顺序见报告中的 JSON 与 worker 日志。

这是一条旧候选样本：测试明确启用候选恢复入口，结束清理强制终止后继 worker，CDC owner 行仍为 ACTIVE，不能把它描述为正常停机。更早的[迟提交失败材料](../../experiments/2026-10-29-cdc-managed-b-e2e-old-candidate/README.md)需要人工业务更新唤醒旧连接，仍然保留。

真实 MySQL 的[许可组件实验](../../experiments/2026-10-29-cdc-b-proof-component/README.md)另外验证提交回复丢失、拒绝回退和预算，但使用合成 ACK 与签名，不能替代真实 Journal 故障。当前候选基于提交 `62ec856f20a81f3da8d56caf290754f8361ac732` 之后的未提交工作区；不同实验的精确源码摘要以各自证据目录为准，不能把旧样本归给后续修改。 同一候选版本的六项固定窗口结果见下文；它不覆盖全部故障，生产入口 C1 的单独样本见下文。

## 同一候选版六项固定窗口

[六项固定窗口的完整证据](../../experiments/2026-10-29-cdc-managed-b-e2e-frozen/README.md)使用同一候选源码、独立 guardian JVM、真实 MySQL、Actor Journal 与 MySQL Sink；这六项的协调器运行在测试 JVM、worker 显式接线；另有一个 [生产入口 C1 样本](../../experiments/2026-10-29-cdc-production-entry-c1/README.md)，实际经过独立 `SnapshotGuardianMain`、`SnapshotSupervisorMain` 和 `PekkoServer` 内的 `CdcPlatformExtension`。

| 切点 | 恢复和对账结果 |
| --- | --- |
| C0：COMMIT 未转发 | STREAMING；许可中的 `marker_present=0`，许可已消费，64 行、384 字段相等 |
| C1：COMMIT 成功、回复丢失 | STREAMING；许可中的 `marker_present=1`，许可已消费，64 行、384 字段相等 |
| 迟提交 | 隔离后旧事务迟提交，排空后 STREAMING；许可中的 `marker_present=1`，许可已消费，64 行、384 字段相等 |
| 迟回滚 | 隔离后旧事务迟回滚，排空后 STREAMING；许可中的 `marker_present=0`，许可已消费，64 行、384 字段相等 |
| guardian 进程失联、socket 不可达 | 两项均保持 UNKNOWN grant、无许可；源 64 行、目标 0 行，未完成初始化 |

四个收敛样本的清理由 guardian 强制停止后继 worker，CDC owner 尾态仍为 ACTIVE。guardian 失联样本的后继 snapshot owner 仍 active、ticket launched，未证明隔离和排空；socket 不可达样本恢复 socket 后确认 snapshot owner 已排空退休。`PASS` 是测试预期断言，不代表六项全都收敛或所有资源清理已证明。

生产入口的独立 C1 样本在真实 MySQL、Journal 和 Sink 上恢复至 STREAMING，源与目标各 64 行、384 字段相等。前两次运行分别在故障注入前因测试配置缺项和明文代理不兼容而失败，原始材料保留；本样本没有生产 raw ACK 顺序证据，强制清理后 CDC owner 仍为 ACTIVE，不证明正常停机。其余故障窗口仍仅有测试入口证据。 [生产入口 C1 证据](../../experiments/2026-10-29-cdc-production-entry-c1/README.md)。

这六个固定窗口没有覆盖控制表历史行数超过 4096 的情况。当前未知 B 的源控制表反查上限为 4096 行，超过时安全暂停；当前没有控制行 GC。因而这些实验不能证明 70 万表长期动态订阅下的恢复可用性。 [组件实验与限制](../../experiments/2026-10-29-cdc-b-proof-component/README.md)。

## 两次受管 worker 异常退出

在真实 MySQL、Actor Journal 与 MySQL Sink 上，实时任务连续经历两次 worker 异常退出，guardian 隔离和数据库排空后分别启动新 worker；最终第二次源与目标各 3 行、9 个字段相等。[日志与对账](../../experiments/2026-11-08-standalone-guardian/README.md)同时保留采集关闭导致的排空失败。本实验未保留完整原始 ACK 回调顺序，也未覆盖未知 B。

后续 CDC 控制存储候选又完成一次真实单表 worker 故障接管，对账为 2 行、6 个字段一致；该次与旧版本实验分别计证据。 [候选实现与验证范围](../../experiments/2026-11-08-standalone-guardian/README.md)。

补充实验在真实 MySQL、Journal 与 Sink 中注入旧格式 `unknown` 配额并终止受管 worker：隔离排空后配额回到旧队列，CDC 新 worker 追平 2 行、6 字段。配额由实验注入，未证明真实旧 Workflow 作业及全部迁移窗口。[证据与边界](../../experiments/2026-11-08-standalone-guardian/README.md)。

补充真实旧 Workflow 作业：目标已提交、Journal 尚未确认时终止旧 worker，接管后源与目标 10 行、60 个字段一致。 [真实作业证据](../../experiments/2026-11-08-standalone-guardian/README.md)。

## 2026-11-09 持久化切换授权与运行时身份

本轮把切换拆成两个提交点。旧 worker 只有在 guardian 隔离 receipt、旧账号排空和 owner `db_drained` 成立后，才会迁移旧配额并写入新的策略身份；新 worker 获得自己的 recovery permit 后，才把旧 Workflow 定义中的用户名映射为运行时账号。原始 Workflow/Split 指纹仍按旧定义计算，密码只从 `passwordEnv` 读取，不写入 Journal 或控制表。

在真实 MySQL、Actor Journal 和 MySQL Sink 的 `KILL_T_BEFORE_J` 实验中，源表 10 行、目标表 10 行、6 个投影字段逐字段一致；第一批 T 在恢复后以 replay 方式再次确认。`KILL_J_AFTER_STORE`、`FENCE_COMMIT_REPLY_LOST`、`STARTUP_COMMIT_REPLY_LOST`、`B_RECOVERY_CRASH`、`DRAIN_COMMIT_REPLY_LOST`、`QUOTA_COMMIT_REPLY_LOST`、`REVOKE_COMMIT_REPLY_LOST`、`OBSERVER_DENIED` 和 `STALE_GRANT` 也完成了本轮固定窗口验证。完整材料见[持久化切换授权实验](../../experiments/2026-11-09-persistent-cutover-authorization/README.md)及各场景目录。

这里的“通过”指本轮真实实验结果。代码层已经实现绑定表、策略切换、permit 顺序和运行时重绑定；切换授权器仍由受管 supervisor 的显式入口调用，尚未扩展到 DDL、多 owner、共享 CDC 或跨实例迁移。未执行的历史场景、旧失败证据和这些边界仍然保留。

## 技术附录与证据入口

[历史源码分析与实验档案](appendices/03-failure-experiments-history.md)保留本篇此前全文；[附录索引](appendices/README.md)集中提供版本、完整矩阵和原始材料入口。当前主文只保留支撑核心论点所需的实现、代表性证据和边界。

## 2026-11-12 Runtime GC 更新

本轮已把 L 安全 GC 接入独立 CDC Runtime。GC 使用单个有界维护 worker，审计请求、namespace 清点、保护 pin、GC operation 和批次游标均持久化；表级审计仍使用原 BootstrapJournal、目标账本和 T/J/D 证据。旧 namespace 启用前会重新清点订阅、消费者、Journal、账号、连接和事务；清点不完整、未知 B、外来 Journal、危险账号或资源释放未知时安全暂停。

真实入口 RG01—RG07 共 7 项通过，其中未知 B 和异常重启按预期 SAFE_PAUSED；L GC 的 LG01—LG16 加容量补测共 17 项通过。LG15 首次身份修复后未重启 Reader，保留失败材料；按重启边界重跑后通过。此前 5 个完整回归失败套件已修正；同一 JDK 17 的完整回归为 722 成功、0 失败、415 个专用外部环境取消。固定 RG01 负载又连续完成 10 轮、约 12 分钟的生命周期 soak，逐字段对账和最终资源采样均通过。

源码结论、真实结果和未验证边界分别记录在 [Runtime GC 实验报告](../../experiments/2026-11-12-runtime-gc/README.md)。本轮代码修复了 GC no-worthwhile-space-saving 的安全重规划：小 GROUP 保留原始 L、只前移 operation 游标，旧 BLOCKED schedule 会在下一 tick 进入 REPLANNING；真实集群重跑的结果以新 `replan-*` 材料为准。这组结果不代表 70 万真实活跃表、DDL、多 owner guardian 接管、自动扩容或 InnoDB 文件缩容已经验收。

## 最终真实故障材料补充（2026-11-13）

最终集群材料保留了两类失败和一条通过路径：旧 ClusterIP Journal 连接 20/20 失败；旧 shell launcher 不是 PID1，停止后留下 ACTIVE owner 和 Binlog Dump；`cdc_gc@%`、旧 namespace 摘要和性能表权限又分别触发了 guard/namespace/权限拒绝。修正为 State NodePort、固定 `cdc_gc@192.168.31.115`、`cdc_control@%` definer 及直接 JVM PID1 后，干净运行完成 B/F、实时变更、GC proof ACTIVE 和 STOPPED→ACTIVE 重启。

Source 与目标最终逐字段一致的证据位于 `docs/experiments/2026-11-12-runtime-gc/cluster-live-20261113/final/`。重启后删除计划因小帧节省不足 BLOCKED，属于保留原始 L 的失败证据；不能用前一轮 GC proof ACTIVE 抹掉这一边界。

## 2026-11-13 KubeSphere 集群流水线与线上边界

参考集群中已有成功的 KubeSphere Pipeline 后，本轮实际运行了 `pekko-cdc-runtime` PipelineRun `pekko-cdc-runtime-n7kjw`：构建、Harbor 推送和 `bigdata-lab/pekko-cdc-runtime` 滚动部署均成功。新 Pod 重启后 owner 为 `ACTIVE`，Source MySQL 保留一个 Debezium Binlog Dump 会话且没有 InnoDB 未提交事务；Source 与 Sink 当前 `source_rows/target_source_rows` 的两行六字段逐字段相等。原始对象、状态和对账材料见 [KubeSphere 集群证据](../../experiments/2026-11-12-runtime-gc/cluster-live-20261113/kubesphere-20261113/)。

这条 KubeSphere 结果与 GitHub Actions deploy 分开统计：GitHub Actions 的 verify/image 成功，但 deploy 因环境没有 `KUBE_CONFIG_DATA` 仍保留失败证据。线上 GC 实际返回 `BLOCKED / ACK proof budget exceeded`，所以只能证明安全暂停，没有证明 ACK proof 超预算后的自动重规划、自动扩容或跨重启继续删除。人工清理旧 UNKNOWN owner 后再重启是实验门槛，不是自动 fencing 已完成的证明；70 万真实活跃表、DDL、多 owner 和长期吞吐仍未验证。


## 2026-11-13 ACK proof budget 修复与真实集群结果

线上长链根因已经核实：`primary` 有 7,110 条 ACK，旧实现的固定 4,096 条链预算必然触发 `ACK proof budget exceeded`。本轮改用持久化 `CaptureAckPaging`：每次最多推进 128 个节点，分页状态先提交，proof 完成后才进入 GC 删除。真实 KubeSphere PipelineRun `pekko-cdc-runtime-g7kcr` 成功部署 `build-3-61495e0f2b5e`；重启后 owner 为 `ACTIVE`，任务为 `STREAMING/ADMISSION_READY/HEALTHY/RELEASED`，actual-D proof head `7742`、ACK 节点 `7731`、page `done=1`。原 `DELETING 42→44` operation 已变为 `DONE`，删除 1 个 GROUP、回收 235 字节；Source 与 Sink 两行六字段逐字段相等。

这次真实结果证明长 ACK 链可分页、可跨运行推进并安全重规划；此前 3 个旧 Sleep 会话和第一次 `OWNER_RELEASE_UNPROVEN` 仍作为失败材料保留。分页 proof 表本身尚无独立 GC，70 万活跃表、无限 ACK 历史、自动扩容、DDL 和多 owner 仍未验证。证据见 [ACK proof 预算实验](../../experiments/2026-11-12-runtime-gc/cluster-live-20261113/gc-ack-proof-budget-20261113/)。


## 2026-11-13 分页 proof 表 GC 与容量边界

分页 proof GC 只回收已完成、非当前 actual-D、未被 GC audit、GC frame 或 Recovery Anchor 引用的 proof；refs、nodes、prior、pins、page 按顺序删除，ACK、Journal、L 和目标账本保留。单轮最多 8 个 proof，单个 proof 子行超过 20,000 时安全阻塞。

真实运行中旧 proof `d9b4921a...`（head 7742）被回收，新 proof `f777e7d3...`（head 13052）保留；Source 与 Sink 两行六字段逐字段一致，重启后 owner 仍为 ACTIVE。证据见 [proof GC 材料](../../experiments/2026-11-12-runtime-gc/cluster-live-20261113/gc-ack-proof-gc-20261113/)。功能代码已真实部署；仅删除计数持久化的后续流水线因 Jenkins 长时间 RUNNING 被取消，标为 `evidence_incomplete`。proof state 自身二次 GC、70 万活跃表、并发 B proof 和无限 ACK 历史仍未验证。

## 4. 04-protocol-and-validation.md

## 后续补测与边界

后续源码把 B_PENDING 取消与 marker 写入串行化，并在 COMMIT 结果未知时暂停；这不新增跨重启的事务结果证明。工厂阻塞时如果先收到关闭，不再启动刚创建的 Reader。需要注意，检查后到启动之间以及其他 SDK 内部窗口还需进一步故障验证；该实验版本的 A05/A09 自动继续仍是未完成项。 详见[后续实验](../../experiments/2026-10-28-cdc-followup/README.md)。上文的失败是当时版本的历史结果，原始材料保留。

## 2026-10-29 补充验证

2026-10-29 的源码检查确认，旧 B_PENDING 不能独自证明源事务未执行；安全自动恢复仍需要源事务结果、原始 B 和 D/L 覆盖的持久证明。工厂返回后、SDK 启动前收到关闭的固定窗口先失败，基于 SDK 生命周期信号的修复后通过；长期阻塞与线程容量仍未验证。 [实验材料与范围](../../experiments/2026-10-29-p0-followup/README.md)。

## 2026-10-29 写前锚点实验

写前锚点实验版本开始持久化 B 意图与实际 D/L ACK 锚点；该版本不接受缺锚点、D 变化或其他未绑定 fence。后续 B_BOUND 候选对 D 变化另有 ACK 父链及 BARRIER 覆盖条件。该实验版本的自动恢复仍缺旧资源结束证明和 Journal/UNKNOWN grant 的持久化状态转换。 详见[本轮证据](../../experiments/2026-10-29-unknown-b/README.md)。

## 事务释放与隔离探针

旧执行者隔离与数据库排空需要绑定到同一恢复许可：真实探针证明一次空闲观察后未隔离的旧 JVM 仍可写入。该探针版本的 B 意图、Journal 和 UNKNOWN grant 自动状态转换仍未完成。 [实验与边界](../../experiments/2026-10-29-b-release-proof/README.md)。

## 当前候选：未知 B 的受管恢复边界

**源码结论：**恢复入口先验证 guardian 与 worker 的身份绑定、隔离票据及账号事务排空，再将完成态 D 对应 ACK 父链与 B 意图核对；B_BOUND 还要证明 BARRIER 被实际 ACK 引用，或满足同原 D 的重放条件。反查源控制表时未知操作、未绑定 fence、证明超限均暂停。经过证明后，恢复事务把许可、次数预算和旧 grant 状态一起持久化；新 owner 领取许可，Journal 接受后消费许可，明确拒绝时许可作废且预算最多回退一次。提交回复未知时只有精确读回才能确认状态。

多个 grant 的候选代码进一步限制其他执行者：仅允许满足身份、阶段和初始化证据等条件的 STREAMING TAIL 按原 Journal 与目标组账本重读；不满足条件保持暂停。这是源码路径和安全约束，真实多任务 T/J 故障窗口仍需验证。其他快照存在尚不可见 B 的可能，不能仅凭 ACTIVE 状态释放。

**实验结果：**[真实 MySQL 组件测试](../../experiments/2026-10-29-cdc-b-proof-component/README.md)覆盖合成 ACK 链、B 绑定和许可事务，[旧候选迟提交样本](../../experiments/2026-10-29-cdc-managed-b-e2e-wake-candidate/README.md)覆盖一条真实端到端数据恢复路径。两者版本与层级不同；前者不是实际 Reader/Journal/Sink，后者不是全部故障矩阵。当前候选基于提交 `62ec856f20a81f3da8d56caf290754f8361ac732` 之后的未提交工作区；不同实验的精确源码摘要以各自证据目录为准，不能把旧样本归给后续修改。

## 六项固定窗口的实测与边界

[六项固定窗口的完整证据](../../experiments/2026-10-29-cdc-managed-b-e2e-frozen/README.md)使用同一候选源码、独立 guardian JVM、真实 MySQL、Actor Journal 与 MySQL Sink；这六项的协调器运行在测试 JVM、worker 显式接线；另有一个 [生产入口 C1 样本](../../experiments/2026-10-29-cdc-production-entry-c1/README.md)，实际经过独立 `SnapshotGuardianMain`、`SnapshotSupervisorMain` 和 `PekkoServer` 内的 `CdcPlatformExtension`。

C0、C1、迟提交和迟回滚四项分别在恢复后达到 STREAMING，各有 64 行、384 字段逐字段相等，旧 grant 释放且许可已消费；B 许可中的 `marker_present` 分别为 0、1、1、0。真实 guardian 失联和 socket 不可达两项均在许可发放前安全暂停，旧 grant UNKNOWN，源 64 行、目标 0 行。故障切点、隔离票据、事务、ACK、T/J/D 和对账记录见报告；四项收敛不等于所有恢复窗口。

四个收敛样本的后继 worker 在清理中被强制停止，CDC owner 仍 ACTIVE；guardian 失联样本的后继 snapshot owner 与票据未确认排空退休。多 grant 的 TAIL 重读属于另一源码路径，本矩阵未覆盖。

生产入口的独立 C1 样本在真实 MySQL、Journal 和 Sink 上恢复至 STREAMING，源与目标各 64 行、384 字段相等。前两次运行分别在故障注入前因测试配置缺项和明文代理不兼容而失败，原始材料保留；本样本没有生产 raw ACK 顺序证据，强制清理后 CDC owner 仍为 ACTIVE，不证明正常停机。其余故障窗口仍仅有测试入口证据。 [生产入口 C1 证据](../../experiments/2026-10-29-cdc-production-entry-c1/README.md)。

当前 `CdcManagedPendingB` 的源控制表反查最多 4096 行，超限安全暂停；当前没有控制行 GC。历史新增、取消及其他控制行持续积累时，即使本次 B 和 ACK 证据本身有效，也可能因超限无法自动恢复。该机制尚未证明能支撑 70 万表长期动态订阅。 [组件实验与限制](../../experiments/2026-10-29-cdc-b-proof-component/README.md)。

## 排空证据需要证明观测有效

MySQL 属性采集关闭时，丢失计数为零并不能证明属性可见。候选代码要求观察连接自身的 owner 属性可见，否则排空观察标记为不完整。[受管接管实验](../../experiments/2026-11-08-standalone-guardian/README.md)验证了两次实时 worker 故障恢复；T、J、L、D 的采样并非原子横切面。

真实 MySQL 测试观察到新旧控制存储共锁，旧配额在未排空时保留；排空状态由测试夹具设置，不代表完整故障迁移已验证。 [候选实现与验证范围](../../experiments/2026-11-08-standalone-guardian/README.md)。

补充实验在真实 MySQL、Journal 与 Sink 中注入旧格式 `unknown` 配额并终止受管 worker：隔离排空后配额回到旧队列，CDC 新 worker 追平 2 行、6 字段。配额由实验注入，未证明真实旧 Workflow 作业及全部迁移窗口。[证据与边界](../../experiments/2026-11-08-standalone-guardian/README.md)。

真实旧 Workflow 恢复实验保留了隔离、排空、配额和许可的审计顺序；CDC 控制存储与旧调度仍共用原锁。 [真实作业证据](../../experiments/2026-11-08-standalone-guardian/README.md)。

## 2026-11-09 持久化切换授权与运行时身份

本轮把切换拆成两个提交点。旧 worker 只有在 guardian 隔离 receipt、旧账号排空和 owner `db_drained` 成立后，才会迁移旧配额并写入新的策略身份；新 worker 获得自己的 recovery permit 后，才把旧 Workflow 定义中的用户名映射为运行时账号。原始 Workflow/Split 指纹仍按旧定义计算，密码只从 `passwordEnv` 读取，不写入 Journal 或控制表。

在真实 MySQL、Actor Journal 和 MySQL Sink 的 `KILL_T_BEFORE_J` 实验中，源表 10 行、目标表 10 行、6 个投影字段逐字段一致；第一批 T 在恢复后以 replay 方式再次确认。`KILL_J_AFTER_STORE`、`FENCE_COMMIT_REPLY_LOST`、`STARTUP_COMMIT_REPLY_LOST`、`B_RECOVERY_CRASH`、`DRAIN_COMMIT_REPLY_LOST`、`QUOTA_COMMIT_REPLY_LOST`、`REVOKE_COMMIT_REPLY_LOST`、`OBSERVER_DENIED` 和 `STALE_GRANT` 也完成了本轮固定窗口验证。完整材料见[持久化切换授权实验](../../experiments/2026-11-09-persistent-cutover-authorization/README.md)及各场景目录。

这里的“通过”指本轮真实实验结果。代码层已经实现绑定表、策略切换、permit 顺序和运行时重绑定；切换授权器仍由受管 supervisor 的显式入口调用，尚未扩展到 DDL、多 owner、共享 CDC 或跨实例迁移。未执行的历史场景、旧失败证据和这些边界仍然保留。

## 技术附录与证据入口

[历史源码分析与实验档案](appendices/04-group-protocol-history.md)保留本篇此前全文；[附录索引](appendices/README.md)集中提供版本、完整矩阵和原始材料入口。当前主文只保留支撑核心论点所需的实现、代表性证据和边界。

## 2026-11-12 Runtime GC 更新

本轮已把 L 安全 GC 接入独立 CDC Runtime。GC 使用单个有界维护 worker，审计请求、namespace 清点、保护 pin、GC operation 和批次游标均持久化；表级审计仍使用原 BootstrapJournal、目标账本和 T/J/D 证据。旧 namespace 启用前会重新清点订阅、消费者、Journal、账号、连接和事务；清点不完整、未知 B、外来 Journal、危险账号或资源释放未知时安全暂停。

真实入口 RG01—RG07 共 7 项通过，其中未知 B 和异常重启按预期 SAFE_PAUSED；L GC 的 LG01—LG16 加容量补测共 17 项通过。LG15 首次身份修复后未重启 Reader，保留失败材料；按重启边界重跑后通过。此前 5 个完整回归失败套件已修正；同一 JDK 17 的完整回归为 722 成功、0 失败、415 个专用外部环境取消。固定 RG01 负载又连续完成 10 轮、约 12 分钟的生命周期 soak，逐字段对账和最终资源采样均通过。

源码结论、真实结果和未验证边界分别记录在 [Runtime GC 实验报告](../../experiments/2026-11-12-runtime-gc/README.md)。本轮代码修复了 GC no-worthwhile-space-saving 的安全重规划：小 GROUP 保留原始 L、只前移 operation 游标，旧 BLOCKED schedule 会在下一 tick 进入 REPLANNING；真实集群重跑的结果以新 `replan-*` 材料为准。这组结果不代表 70 万真实活跃表、DDL、多 owner guardian 接管、自动扩容或 InnoDB 文件缩容已经验收。

## 最终验证范围补充（2026-11-13）

本轮把协议验证落到真实部署：owner、Journal、offset、L、T 和 GC proof 都在同一个 State MySQL 中可查询，目标逐字段对账覆盖全量、实时三种变更和一次正常停止/重启。PID1 修复解决了“Pod 已删但 Binlog Dump 仍在”的部署边界；固定 writer host/definer 和性能表权限解决了 ledger guard 的实际准入边界。

未验证范围保持明确：未知 B 自动恢复、DDL、多 owner guardian 接管、70 万活跃物理表、跨实例共享 Reader 和长期吞吐。旧版本 GC 在重启后的 no-worthwhile-space-saving 情形安全 BLOCKED；当前实现只增加了保留 L 的自动重规划，部署后的真实结果仍需单独记录。

## 2026-11-13 KubeSphere 集群流水线与线上边界

参考集群中已有成功的 KubeSphere Pipeline 后，本轮实际运行了 `pekko-cdc-runtime` PipelineRun `pekko-cdc-runtime-n7kjw`：构建、Harbor 推送和 `bigdata-lab/pekko-cdc-runtime` 滚动部署均成功。新 Pod 重启后 owner 为 `ACTIVE`，Source MySQL 保留一个 Debezium Binlog Dump 会话且没有 InnoDB 未提交事务；Source 与 Sink 当前 `source_rows/target_source_rows` 的两行六字段逐字段相等。原始对象、状态和对账材料见 [KubeSphere 集群证据](../../experiments/2026-11-12-runtime-gc/cluster-live-20261113/kubesphere-20261113/)。

这条 KubeSphere 结果与 GitHub Actions deploy 分开统计：GitHub Actions 的 verify/image 成功，但 deploy 因环境没有 `KUBE_CONFIG_DATA` 仍保留失败证据。线上 GC 实际返回 `BLOCKED / ACK proof budget exceeded`，所以只能证明安全暂停，没有证明 ACK proof 超预算后的自动重规划、自动扩容或跨重启继续删除。人工清理旧 UNKNOWN owner 后再重启是实验门槛，不是自动 fencing 已完成的证明；70 万真实活跃表、DDL、多 owner 和长期吞吐仍未验证。


## 2026-11-13 ACK proof budget 修复与真实集群结果

线上长链根因已经核实：`primary` 有 7,110 条 ACK，旧实现的固定 4,096 条链预算必然触发 `ACK proof budget exceeded`。本轮改用持久化 `CaptureAckPaging`：每次最多推进 128 个节点，分页状态先提交，proof 完成后才进入 GC 删除。真实 KubeSphere PipelineRun `pekko-cdc-runtime-g7kcr` 成功部署 `build-3-61495e0f2b5e`；重启后 owner 为 `ACTIVE`，任务为 `STREAMING/ADMISSION_READY/HEALTHY/RELEASED`，actual-D proof head `7742`、ACK 节点 `7731`、page `done=1`。原 `DELETING 42→44` operation 已变为 `DONE`，删除 1 个 GROUP、回收 235 字节；Source 与 Sink 两行六字段逐字段相等。

这次真实结果证明长 ACK 链可分页、可跨运行推进并安全重规划；此前 3 个旧 Sleep 会话和第一次 `OWNER_RELEASE_UNPROVEN` 仍作为失败材料保留。分页 proof 表本身尚无独立 GC，70 万活跃表、无限 ACK 历史、自动扩容、DDL 和多 owner 仍未验证。证据见 [ACK proof 预算实验](../../experiments/2026-11-12-runtime-gc/cluster-live-20261113/gc-ack-proof-budget-20261113/)。


## 2026-11-13 分页 proof 表 GC 与容量边界

分页 proof GC 只回收已完成、非当前 actual-D、未被 GC audit、GC frame 或 Recovery Anchor 引用的 proof；refs、nodes、prior、pins、page 按顺序删除，ACK、Journal、L 和目标账本保留。单轮最多 8 个 proof，单个 proof 子行超过 20,000 时安全阻塞。

真实运行中旧 proof `d9b4921a...`（head 7742）被回收，新 proof `f777e7d3...`（head 13052）保留；Source 与 Sink 两行六字段逐字段一致，重启后 owner 仍为 ACTIVE。证据见 [proof GC 材料](../../experiments/2026-11-12-runtime-gc/cluster-live-20261113/gc-ack-proof-gc-20261113/)。功能代码已真实部署；仅删除计数持久化的后续流水线因 Jenkins 长时间 RUNNING 被取消，标为 `evidence_incomplete`。proof state 自身二次 GC、70 万活跃表、并发 B proof 和无限 ACK 历史仍未验证。

## 5. 05-pekko-vs-flink-cdc.md

## 后续补测与边界

这些失败样本有了针对性的后续修复和实验，详见报告；安全暂停、局部资源释放和固定场景对账仍不是完整 CDC 高可用保证。该历史实验版本尚未完成 DDL、GC、自动接管、长时间 churn、70 万真实源表及同负载 Flink 对比。 详见[后续实验](../../experiments/2026-10-28-cdc-followup/README.md)。上文的失败是当时版本的历史结果，原始材料保留。

## 2026-10-29 补充验证

后续实验验证了一个回调异常后重建 Reader/Actor 的恢复样本，也修复了特定启动关闭竞态；该历史实验版本的未知 B 自动恢复与长期资源占用仍未解决或验证，结果不能扩展为平台全故障自动恢复保证。 [实验材料与范围](../../experiments/2026-10-29-p0-followup/README.md)。

## 2026-10-29 写前锚点实验

写前锚点实验版本增加了锚点及局部证据，但当时未知 B 的任务级自动恢复尚未完成；不能据此宣称已具备 Flink CDC 的故障恢复能力。 详见[本轮证据](../../experiments/2026-10-29-unknown-b/README.md)。

## 事务释放与隔离探针

真实事务释放与旧 JVM 迟到重连的探针进一步说明，自建调度需要独立证明旧执行者隔离；该探针样本本身不能证明未知 B 已实现自动恢复。 [实验与边界](../../experiments/2026-10-29-b-release-proof/README.md)。

## 恢复能力比较仍受证据限制

未知 B 的受管恢复候选已经把进程隔离、数据库排空、D/L 证明、持久许可与 Journal 状态衔接起来，这是源码层面的进展。单个[旧候选真实迟提交样本](../../experiments/2026-10-29-cdc-managed-b-e2e-wake-candidate/README.md)恢复到 STREAMING，64 行、384 字段相等；[组件样本](../../experiments/2026-10-29-cdc-b-proof-component/README.md)验证不同许可与证明分支，但使用合成 ACK 与 guardian 数据。更早需要人工唤醒的[失败样本](../../experiments/2026-10-29-cdc-managed-b-e2e-old-candidate/README.md)仍保留。

当前候选基于提交 `62ec856f20a81f3da8d56caf290754f8361ac732` 之后的未提交工作区；不同实验的精确源码摘要以各自证据目录为准，不能把旧样本归给后续修改。 六项固定窗口已在同一候选版运行，但除单个 C1 样本外的生产入口窗口、多任务 TAIL 重放、长期运行、70 万真实表以及与 Flink 同负载性能比较仍未验证，不能根据这些样本声称两者具有等价的高可用或吞吐。

## 新增固定窗口证据

[六项固定窗口的完整证据](../../experiments/2026-10-29-cdc-managed-b-e2e-frozen/README.md)使用同一候选源码、独立 guardian JVM、真实 MySQL、Actor Journal 与 MySQL Sink；这六项的协调器运行在测试 JVM、worker 显式接线；另有一个 [生产入口 C1 样本](../../experiments/2026-10-29-cdc-production-entry-c1/README.md)，实际经过独立 `SnapshotGuardianMain`、`SnapshotSupervisorMain` 和 `PekkoServer` 内的 `CdcPlatformExtension`。

四项提交/回滚窗口恢复到 STREAMING，各自 64 行、384 字段相等；guardian 失联与不可达两项则安全暂停、没有完成目标初始化。收敛样本的后继 worker 在清理时被强制停止，CDC owner 仍 ACTIVE。该结果说明受管候选在这些指定窗口中具备数据恢复或安全暂停证据，但不构成 Flink 与 Pekko 的高可用能力等价，也不是同负载性能比较。

生产入口的独立 C1 样本在真实 MySQL、Journal 和 Sink 上恢复至 STREAMING，源与目标各 64 行、384 字段相等。前两次运行分别在故障注入前因测试配置缺项和明文代理不兼容而失败，原始材料保留；本样本没有生产 raw ACK 顺序证据，强制清理后 CDC owner 仍为 ACTIVE，不证明正常停机。其余故障窗口仍仅有测试入口证据。 [生产入口 C1 证据](../../experiments/2026-10-29-cdc-production-entry-c1/README.md)。

对目标场景还有一项具体限制：未知 B 恢复反查源控制表最多 4096 行，超限安全暂停，而当前没有控制行 GC。长期新增、取消表会累积控制行，这一上限会影响 70 万表场景的恢复可用性；不能用现有实验宣称该规模已经验证。 [组件实验与限制](../../experiments/2026-10-29-cdc-b-proof-component/README.md)。

## 当前独立 Runtime 的可验证范围

独立 CDC 入口在单表实验中经 guardian 接管并逐字段追平，最终候选关闭了 supervisor 与 worker 的调度开关，证明这两个固定故障窗口的表现；兼容期仍依赖原调度锁和配额表，未知 B、源端 fencing 和大规模持续运行仍未由该实验验证。[版本与证据](../../experiments/2026-11-08-standalone-guardian/README.md)。

CDC 专用控制存储类已接入受管入口，但兼容期仍依赖旧锁与旧配额表；独立数据库结构和旧 owner 故障迁移仍需验证。 [候选实现与验证范围](../../experiments/2026-11-08-standalone-guardian/README.md)。

补充实验在真实 MySQL、Journal 与 Sink 中注入旧格式 `unknown` 配额并终止受管 worker：隔离排空后配额回到旧队列，CDC 新 worker 追平 2 行、6 字段。配额由实验注入，未证明真实旧 Workflow 作业及全部迁移窗口。[证据与边界](../../experiments/2026-11-08-standalone-guardian/README.md)。

CDC 控制存储已参与一次真实旧 Workflow 恢复实验；当前证据不覆盖独立数据库锁切换及生产规模。 [真实作业证据](../../experiments/2026-11-08-standalone-guardian/README.md)。

## 2026-11-09 持久化切换授权与运行时身份

本轮把切换拆成两个提交点。旧 worker 只有在 guardian 隔离 receipt、旧账号排空和 owner `db_drained` 成立后，才会迁移旧配额并写入新的策略身份；新 worker 获得自己的 recovery permit 后，才把旧 Workflow 定义中的用户名映射为运行时账号。原始 Workflow/Split 指纹仍按旧定义计算，密码只从 `passwordEnv` 读取，不写入 Journal 或控制表。

在真实 MySQL、Actor Journal 和 MySQL Sink 的 `KILL_T_BEFORE_J` 实验中，源表 10 行、目标表 10 行、6 个投影字段逐字段一致；第一批 T 在恢复后以 replay 方式再次确认。`KILL_J_AFTER_STORE`、`FENCE_COMMIT_REPLY_LOST`、`STARTUP_COMMIT_REPLY_LOST`、`B_RECOVERY_CRASH`、`DRAIN_COMMIT_REPLY_LOST`、`QUOTA_COMMIT_REPLY_LOST`、`REVOKE_COMMIT_REPLY_LOST`、`OBSERVER_DENIED` 和 `STALE_GRANT` 也完成了本轮固定窗口验证。完整材料见[持久化切换授权实验](../../experiments/2026-11-09-persistent-cutover-authorization/README.md)及各场景目录。

这里的“通过”指本轮真实实验结果。代码层已经实现绑定表、策略切换、permit 顺序和运行时重绑定；切换授权器仍由受管 supervisor 的显式入口调用，尚未扩展到 DDL、多 owner、共享 CDC 或跨实例迁移。未执行的历史场景、旧失败证据和这些边界仍然保留。

## 技术附录与证据入口

[历史源码分析与实验档案](appendices/05-framework-comparison-history.md)保留本篇此前全文；[附录索引](appendices/README.md)集中提供版本、完整矩阵和原始材料入口。当前主文只保留支撑核心论点所需的实现、代表性证据和边界。

## 2026-11-12 Runtime GC 更新

本轮已把 L 安全 GC 接入独立 CDC Runtime。GC 使用单个有界维护 worker，审计请求、namespace 清点、保护 pin、GC operation 和批次游标均持久化；表级审计仍使用原 BootstrapJournal、目标账本和 T/J/D 证据。旧 namespace 启用前会重新清点订阅、消费者、Journal、账号、连接和事务；清点不完整、未知 B、外来 Journal、危险账号或资源释放未知时安全暂停。

真实入口 RG01—RG07 共 7 项通过，其中未知 B 和异常重启按预期 SAFE_PAUSED；L GC 的 LG01—LG16 加容量补测共 17 项通过。LG15 首次身份修复后未重启 Reader，保留失败材料；按重启边界重跑后通过。此前 5 个完整回归失败套件已修正；同一 JDK 17 的完整回归为 722 成功、0 失败、415 个专用外部环境取消。固定 RG01 负载又连续完成 10 轮、约 12 分钟的生命周期 soak，逐字段对账和最终资源采样均通过。

源码结论、真实结果和未验证边界分别记录在 [Runtime GC 实验报告](../../experiments/2026-11-12-runtime-gc/README.md)。本轮代码修复了 GC no-worthwhile-space-saving 的安全重规划：小 GROUP 保留原始 L、只前移 operation 游标，旧 BLOCKED schedule 会在下一 tick 进入 REPLANNING；真实集群重跑的结果以新 `replan-*` 材料为准。这组结果不代表 70 万真实活跃表、DDL、多 owner guardian 接管、自动扩容或 InnoDB 文件缩容已经验收。

## 与 Flink CDC 的比较补充（2026-11-13）

最终运行结果支持的结论很具体：Pekko 路径可以把共享 Reader、表级 Journal、低并发 step worker、目标账本和受保护 GC 组合在一个独立 Runtime 中，并允许在停止后以持久 owner 状态重新启动；它没有因此获得 Flink CDC 的同负载吞吐、Checkpoint 或 70 万活跃表容量证明。GC no-worthwhile-space-saving 会保留 L 并自动重规划，说明自建协议把证据和保守边界暴露给运维，也增加了账号、权限、namespace、Journal、目标 receipt 和 GC 的维护责任；该重规划的真实集群结果仍必须与本地测试分开记录。

因此本文当前的比较仍是职责与证据边界比较，不是性能排名。Flink CDC 的并行 Source、Checkpoint 和成熟运行时能力没有在本仓库的这次单表 acceptance 中被重测；Pekko 的优势只应表述为可按业务需要定制常驻控制和资源准入，不能扩大成整体优于 Flink CDC。

## 2026-11-13 KubeSphere 集群流水线与线上边界

参考集群中已有成功的 KubeSphere Pipeline 后，本轮实际运行了 `pekko-cdc-runtime` PipelineRun `pekko-cdc-runtime-n7kjw`：构建、Harbor 推送和 `bigdata-lab/pekko-cdc-runtime` 滚动部署均成功。新 Pod 重启后 owner 为 `ACTIVE`，Source MySQL 保留一个 Debezium Binlog Dump 会话且没有 InnoDB 未提交事务；Source 与 Sink 当前 `source_rows/target_source_rows` 的两行六字段逐字段相等。原始对象、状态和对账材料见 [KubeSphere 集群证据](../../experiments/2026-11-12-runtime-gc/cluster-live-20261113/kubesphere-20261113/)。

这条 KubeSphere 结果与 GitHub Actions deploy 分开统计：GitHub Actions 的 verify/image 成功，但 deploy 因环境没有 `KUBE_CONFIG_DATA` 仍保留失败证据。线上 GC 实际返回 `BLOCKED / ACK proof budget exceeded`，所以只能证明安全暂停，没有证明 ACK proof 超预算后的自动重规划、自动扩容或跨重启继续删除。人工清理旧 UNKNOWN owner 后再重启是实验门槛，不是自动 fencing 已完成的证明；70 万真实活跃表、DDL、多 owner 和长期吞吐仍未验证。


## 2026-11-13 ACK proof budget 修复与真实集群结果

线上长链根因已经核实：`primary` 有 7,110 条 ACK，旧实现的固定 4,096 条链预算必然触发 `ACK proof budget exceeded`。本轮改用持久化 `CaptureAckPaging`：每次最多推进 128 个节点，分页状态先提交，proof 完成后才进入 GC 删除。真实 KubeSphere PipelineRun `pekko-cdc-runtime-g7kcr` 成功部署 `build-3-61495e0f2b5e`；重启后 owner 为 `ACTIVE`，任务为 `STREAMING/ADMISSION_READY/HEALTHY/RELEASED`，actual-D proof head `7742`、ACK 节点 `7731`、page `done=1`。原 `DELETING 42→44` operation 已变为 `DONE`，删除 1 个 GROUP、回收 235 字节；Source 与 Sink 两行六字段逐字段相等。

这次真实结果证明长 ACK 链可分页、可跨运行推进并安全重规划；此前 3 个旧 Sleep 会话和第一次 `OWNER_RELEASE_UNPROVEN` 仍作为失败材料保留。分页 proof 表本身尚无独立 GC，70 万活跃表、无限 ACK 历史、自动扩容、DDL 和多 owner 仍未验证。证据见 [ACK proof 预算实验](../../experiments/2026-11-12-runtime-gc/cluster-live-20261113/gc-ack-proof-budget-20261113/)。


## 2026-11-13 分页 proof 表 GC 与容量边界

分页 proof GC 只回收已完成、非当前 actual-D、未被 GC audit、GC frame 或 Recovery Anchor 引用的 proof；refs、nodes、prior、pins、page 按顺序删除，ACK、Journal、L 和目标账本保留。单轮最多 8 个 proof，单个 proof 子行超过 20,000 时安全阻塞。

真实运行中旧 proof `d9b4921a...`（head 7742）被回收，新 proof `f777e7d3...`（head 13052）保留；Source 与 Sink 两行六字段逐字段一致，重启后 owner 仍为 ACTIVE。证据见 [proof GC 材料](../../experiments/2026-11-12-runtime-gc/cluster-live-20261113/gc-ack-proof-gc-20261113/)。功能代码已真实部署；仅删除计数持久化的后续流水线因 Jenkins 长时间 RUNNING 被取消，标为 `evidence_incomplete`。proof state 自身二次 GC、70 万活跃表、并发 B proof 和无限 ACK 历史仍未验证。
