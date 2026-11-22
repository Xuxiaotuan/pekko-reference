# Supervisor 重启恢复：提交前整体终审

本轮审查覆盖恢复边界、资源释放和持久化兼容。审查中补强了一条 fork 后身份失败的异常路径；修正后的只读复审未发现剩余 Critical / Important 阻塞项。该判断限于下述代码与验证范围，不是对所有运行环境的无故障保证。

## 版本与证据不能混用

代码仍以 `afff7ab27560b74ad026aa370dba1cf920fdc587` 为 Git 基线。首次[生产实现实验](../2026-10-23-supervisor-jvm-recovery/README.md)及此前[guardian 探针](../2026-10-23-guardian-restart/README.md)全部保持原样；其中“未提交、未推送”是各自冻结时的状态。它们不是当前提交结果的声明。

本次精确实现由[source-manifest.json](source-manifest.json)和归档中的 `source.zip` 标识。相对首次生产实验的301个文件，仅 `ManagedJvmIsolation.scala`、`SnapshotGuardianService.scala`、`SnapshotSupervisorStoreSpec.scala` 三个文件发生变化；前两者是本次异常路径补强，第三个是回归测试。逐文件前后散列见[result.json](result.json)的 `sourceDelta`，文本差异见归档 `source/review-guard.diff`。其余298个源码、资源和构建文件一致。

本次提交同时纳入此前尚未提交的7个探针源码及其实验材料，因为它们是本轮回归的依赖；内容与原探针冻结散列一致。所有基线已跟踪实验文件逐项核对 Git blob 与当前字节，清单见[preserved-experiments.json](preserved-experiments.json)。没有重写旧 C2 失败材料或把先导失败改为成功。

## 源码审查结论

| 边界 | 当前实现与核对结果 |
|---|---|
| 恢复授权 | 固定 guardian 公钥 pin、原 JVM/capability、controller epoch 和一次性 boot 票据共同约束；不能按 PID 重新收养。fork 前持久化占额，dispatching 未知不能按超时取消。 |
| 隔离与目标副作用 | 原始退出证明验签并绑定 worker/operation；与旧 owner fence 同一 admission 事务提交。随后按原 manifest 和固定数据库身份检查遗留会话/事务，两轮完整空观察后才归还旧额度。 |
| 资源释放 | 进程槽位、表任务额度和 writer permit 分开处理；未知状态保留额度。健康 owner 仅清理完整观察证实结束的会话。外部 RPC、进程等待和数据库观察不占 admission SQL 事务。 |
| 持久化兼容 | 新增两张独立控制表；旧 Journal/Snapshot 类型、serializer manifest、persistenceId、execution、batchId、Split 和 T/J/D 协议未改。旧入口保留；类型兼容不等于允许任意新旧控制程序混跑。 |

本次沿调用链和 SQL 事务直接审查；未以代码图工具代替核对。[审查记录](code-review.json)分别记录审查判断、复现、修正和实际验证。

## 补强：fork 已发生，身份未知仍必须占额

原 `ManagedJvmIsolation.launch` 在 `start()` 返回后才查询 `Process.info()`。若该查询或身份构造抛错，原清理分支尝试强杀并等待，但没有保存这个尚无完整身份的原 Process。新库存因此可能把持久化 dispatching 票据报告为 NOT_STARTED，允许 CANCEL。

本轮在 Java17 的真实 fork 上，仅对调用线程的 `Process.info()` 拒绝 `manageProcess` 权限；不增加生产注入开关。RED实际为0/1：库存返回 NOT_STARTED，而预期为 UNKNOWN。补强后，start返回即保存原 Process；身份完整时迁移到正常 children，否则保留未知句柄与槽位，库存 UNKNOWN，拒绝取消、boot复用和容量超发。现有 runtime 遇 UNKNOWN 暂停，不继续释放或启动后继。即使清理尝试已结束，缺少完整身份和证明时也不自动释放该槽位，需要另行完成受控外部隔离处置。

GREEN相关两套测试16/16，全量后端580/580。这个定向例使用 H2 控制表与真实 OS 子进程，**不是 MySQL 业务恢复实验**；它证明 fork 后身份拒绝的库存分类和准入行为，没有复现强杀10秒后进程仍存活、真实 OS 身份信息缺省或 PID复用。权限拒绝只属于测试设施；生产运行不要求安装 SecurityManager。

## 修正后本轮实际验证

| 层级 | 结果 | 精确 runner / 证据 |
|---|---|---|
| 生产控制协议 MySQL 故障矩阵 | 32/32：29收敛、3安全暂停 | `matrix-1792765012905845000` |
| 实际 guardian/supervisor main 与固定 PekkoServer worker | 1/1 | `main-smoke-1792765442285295000` |
| 原同集群自动接管 | 22/22，全部收敛 | `takeover-regression-1792766002561667000` |
| 原 Split 独立恢复 | 22/22：16收敛、6安全暂停 | `snapshot-regression-1792766687311563000` |
| 原 guardian 测试探针 | 10/10：8收敛、2安全暂停 | `probe-regression-1792766790956290000` |
| 新身份失败分支 | RED0/1 → GREEN16/16 | `logs/identity-red.log`、`logs/identity-green.log` |
| 后端全量与生产制品 | 580/580，110 suites；package通过 | `logs/backend-final.log`，859个生产class匹配冻结版本，654个测试class未进入生产JAR |

五套真实实验使用相同的当前301文件版本。每个新矩阵正例以真实 MySQL Source、Actor Journal 和 Sink 比较10行的 `id/label/amount/note/updated_at/revision`，共290行、1740个字段值；同时核对五页目标账本、CBOR Journal页checkpoint、批次、cursor、边界、generation和原始签名证明。S11 permit及S12必须由第三个controller完成第3代恢复，两位前任均已隔离/排空/退休。三个S15负例保留30个缺失键，属于安全暂停，**不能记为数据收敛**。

修正后的32项不仅检查 ScalaTest退出0，还再次执行独立签名、账本与状态顺序核验。离线验证器复用首次实验的语义断言，并用字段、cursor、签名三类篡改反例确认会拒绝坏证据。完整结果与归档路径见[result.json](result.json)。

此前冻结版本的原CDC27项真实实验及其完整失败过程仍保留，离线归档再次验过。本次最后的Process身份保护不改变CDC数据路径；这27项**没有在最后补强后的版本重跑**，不计为本轮当前版本的端到端结果。不能因为backend全量通过就将外部MySQL集成也算作本轮新跑。

## 仍未验证的边界

- 只覆盖本机受管静态快照，D=N/A。guardian自身JVM重启、主机重启、跨主机选主、共享CDC、多owner CDC和DDL均不在范围。
- guardian控制能力丢失时暂停接管和资源再分配；仍存活的旧worker可能继续已获许可的工作。负例门闩让T/J保持不变，不是数据面自动冻结保证。
- 矩阵使用时序包装器驱动生产协议；实际生产main另有1项验证。不能把32项全称为无包装CLI实验。新矩阵worker是独立单节点测试集群；同集群接管另计。
- Source、Journal、Snapshot、Sink角色位于同一物理MySQL。遗留事务窗口使用测试透明中继保留原连接；不等于不同物理数据库故障。COMMIT异常注入也不等于真实TCP COMMIT包丢失。
- Snapshot INSERT遗留事务有证据，但没有独立验收从某个Actor Snapshot载入并跳过对应Journal前缀的完整路径。
- 未验收恶意同UID/root进程、真实PID复用、数据库故障切换、生产数据升级、混合版本控制程序、长期负载和700000张物理表。配置摘要覆盖配置文本/堆/classpath字符串，不是全部JAR内容证明。

本轮第一次旧接管回归为21/22：`KILL_DB_TARGET_WAIT`的processlist会话已消失，innodb_trx仍列旧trx，未满足“同一旧会话与事务仍在”的严格窗口要求。原断言没有降低，也没有修改生产协议或旧测试源码。定向重验1/1及原独立验证器通过后，再完整回归22项；首次失败runner和定向runner均列于result.json的historicalAttempts，原始目录全部归档。这也不证明该旧窗口设施能在所有运行环境中稳定成立。

本轮首个Split回归同为21/22：QUERY_TIMEOUT的业务查询正在被故意延迟时，测试STATE探针新建连接握手超时，stderr定位在withConnection状态查询，子进程随后退出。事后数据库没有遗留事务、最大连接数13；同一JDBC驱动连续10个新连接成功（4—239ms），定向QUERY_TIMEOUT1/1、65行×6字段对账通过后，重新完整回归22项。没有更改原源码或放宽超时；瞬时握手超时的具体原因仍未查明，旧失败及原始stderr全部保留。辅助健康探针首版JSON与logback输出混排，导致解析失败；只调整探针输出收集后解析成功，原错误输出也归档，不能算数据库连接失败。

首次报告中S6清理失败、no-fork时序分类失败、遗留事务窗口未建立、错误中继阻塞控制连接、S11退出0但语义拒绝，以及随后严格失败的材料仍在原归档。当前新增RED也保留，不用最后成功覆盖失败。

## 复现与清理

在线仍使用[原run.py](../2026-10-23-supervisor-jvm-recovery/run.py)，只对显式隔离的回环MySQL启用；新源码编译后可复现五套真实实验。本次启动原任务容器时回环端口变为32820；环境变量按实际端口设置，没有复用旧材料中的端口假装同一现场。所有schema新增而非覆盖。

本轮停止前客户端0、InnoDB事务0、临时账户0、实验JVM0。任务容器已停止，原234个schema和数据卷保留，新增133个schema另行导出；共367个schema仍在原卷。未操作其他容器。数据库导出、进程/连接/事务和清理证据随归档保存。

离线运行 `python3 docs/experiments/2026-10-23-supervisor-final-review/verify.py`；需Python3与cryptography，不访问数据库、不发ACK、不恢复任务、不操作进程。后续源码变化时加 `--archived-source`。归档按[archive-parts.json](archive-parts.json)顺序拼接；文件散列见[archive-manifest.json](archive-manifest.json)，根文件见[SHA256SUMS](SHA256SUMS)。实际凭据和私钥不入库，公开证据已递归扫描包括冻结JAR/ZIP内部。

本目录是提交前冻结的验收记录。提交与远端结果以包含本目录的Git commit及远端分支为准，不回写原实验的历史状态。部署前提见[使用指南](../../guides/mysql-supervisor-recovery.md)。
