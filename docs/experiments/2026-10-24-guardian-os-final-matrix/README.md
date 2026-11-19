# guardian OS 探针：同一最终版本的12项补跑

本轮完成专用 Linux 环境复核、同版完整矩阵和最终资源清点，并单独调查 `ActorSpec`。只新增测试探针与材料，没有修改生产恢复协议、原测试或构建配置，没有提交、推送。

基线：`2fe7c253eea0d5499e3558f5ab1259a068df3f29`。唯一活跃冻结版本：`r-1792851860999716000`；完整运行：`matrix-1792851871492514517`。309份源码与228个运行JAR的散列见 [frozen-runtime.json](frozen-runtime.json)。本轮12项没有选取其他版本的成功案例拼接。

## 实验结果

**12项均达到各自预期：8项恢复收敛，4项安全暂停。**后者未完成同步，不能计作数据恢复成功。独立验收结果见 [verification-result.json](verification-result.json)，现场输出见 [matrix-console.log](matrix-console.log)。

| 场景 | 固定窗口与结果 |
|---|---|
| G5_BEFORE_FORK | 旧 guardian 通过票据检查后停在 fork 前；整个原边界停止后迟到放行，未创建旧 worker；新执行收敛 |
| G6_FORK_UNRECORDED | worker 已运行，票据尚无PID；旧 guardian JVM死亡，新进程没有原 Process 控制能力；OS隔离、排空后收敛 |
| G7_START_ACK_LOST | 原 systemd start job 已受理、ExecStartPre尚未放行；停止原边界后再放行，未出现迟到旧 main；收敛 |
| G7_EXECUTED_ACK_LOST | 旧 worker 已实际执行但结果未附着票据；隔离并恢复，收敛 |
| G10_SOURCE | 原 Source 服务端连接及 InnoDB事务跨旧 JVM死亡继续存在；确认同一连接ID/事务ID，排空后收敛 |
| G10_TARGET | 原目标写事务继续被中继保持；隔离证明成立仍不能提前归还旧配额；事务结束后收敛 |
| G10_JOURNAL | T 已提交、该页 Journal INSERT仍阻塞；原事务结束、逐实例两轮排空后恢复，收敛 |
| G10_SNAPSHOT | Snapshot INSERT先读InnoDB行再等门闩，确实建立事务窗口；原事务结束后收敛 |
| G14_CONTROL_DENIED | 受限账户真实调用systemd stop被拒绝；安全暂停，无新准入，保留原T/J与配额 |
| G14_SIGNAL_ONLY | SIGCONT返回成功但子树仍非空；拒绝把“信号成功”当成隔离成功，安全暂停 |
| G15_LEGACY_UNBOUND | 原绑定实际不存在，诊断PID不获恢复入口信任；安全暂停 |
| G15_UNIT_MISSING | 原unit实际移除且LoadState=not-found，没有可接受的隔离证明；安全暂停 |

8个正例逐一对比10行的全部六个字段：`id/label/amount/note/updated_at/revision`，共80行、480个字段值完全一致。每例核对5页目标账本、原始CBOR Journal、batchId/cursor/Split边界、execution与许可generation，以及目标INSERT审计。四个负例各保留Source10/Target0、10个缺失键和原始行，不与正例合计。

所有进程清理后，另以每个schema一份repeatable-read只读一致性快照，复核完整Source/Sink行、T、原始J和目标审计；12份均与各自正例final或负例sealed验收状态精确一致，见 [data-after-cleanup.json](data-after-cleanup.json)。首次CLI使用默认会话时区，使T的`TIMESTAMP committed_at`显示比UTC JDBC多8小时；原始捕获与比较失败保留，仅将检查连接设为UTC后重读，没有改数据或转换掉字段差异。

G10均保存隔离前后同一 `(connection ID, trx_id)`；不是重新建立一条事务来代替遗留事务。旧实例的完整观测必须达到间隔至少250ms的两轮空确认，才可进入 `db_drained → old_quota_released → 新 recovery_permitted`。本轮仍是静态快照，**D=N/A，没有CDC ACK结果**。

## 环境恢复与最终状态

专用机器仍为 `pekko-guardian-os-probe-20261024`，机器ID未换，MySQL server UUID未换。本轮检查时，前次仅针对该机的重启已完成，实际命令执行恢复。未全局重启OrbStack，也未操作其他服务。控制通道此前阻塞的根因仍未核实，不能把“本次恢复”写成基础设施根因已修复。记录见 [environment-recovery.json](environment-recovery.json) 和 [machine-info.json](machine-info.json)。

环境是 Ubuntu24.04 的 OrbStack Linux machine，即共享其Linux VM内核的LXC容器，不是独立物理机；真实systemd255、cgroup v2、MySQL8.0.46、Linux JRE17。限制仍为2 CPU、3GiB内存、6GiB磁盘。Source/Journal/Snapshot/Sink使用同一物理MySQL的不同角色。

前后只读清点见 [resources-before.json](resources-before.json)、[resources-after.json](resources-after.json)。

| 资源或持久化记录 | 最终观测 |
|---|---|
| 实验 guardian/supervisor/worker JVM | 0，逐案例cleanup与最终`/proc`清点一致 |
| 实验服务定义 | 0；每例旧、新边界成员均已退出 |
| systemd失败状态 | 保留23条`not-found failed failed` tombstone；没有运行成员，未为清零报表而reset-failed |
| MySQL业务连接 / InnoDB事务 | 0 / 0；唯一非检查连接是原有event_scheduler daemon |
| 8个正例的调度任务配额 | task=completed，owner_id/grant_id均NULL；旧实验票据retired |
| 8个正例的后继控制记录 | owner仍active，writer permission=parked，实验new ticket仍launched；这些记录没有被清零 |
| 4个负例的配额 | 原task=running，原owner/grant保留，owner/permission=active，old ticket=sealed；没有新许可或新业务worker |
| 前次29个schema | 六类已采集控制/配额表记录前后相等；未据此声称整个schema的数据和结构都已对比 |

物理资源退出与逻辑恢复许可是不同结论。负例清理最后由测试观察器终止进程，**这次清理不回填为先前可接受的隔离证明**，因此原控制记录和配额仍保留。正例后继实验票据也没有退休；本轮没有验证生产后继worker完成后的跨重启票据回收。不能总结成“所有owner、ticket和配额全部归零”。

## ActorSpec：定位到了哪一层

原 `ActorSpec` 未修改。源码的业务Actor是100个router routee；循环却为每条消息创建一个 `TestProbe`，一共100万个，回复队列也未消费。测试名“create 1 million actors”把这些测试探针混入了业务Actor资源观察。

Pekko1.1.3的 `TestProbeImpl` 使用 `system.systemActorOf` 创建探针，ActorSpec不逐个停止它们。旧失败日志显示断言已通过，之后system guardian在10秒清理边界仍等待大量`/system/testProbe-*`终止。本轮在独立SBT执行中抓到对应的RUNNABLE线程：`TerminatingChildrenContainer.remove → removeChildAndGetStateChange → handleChildTerminated`，底层正在执行Scala不可变HashSet删除；测试线程等待 `TestKitUtils.shutdown/Await`。原始线程转储、版本源码和GC日志均在新归档中。

新增 [ActorShutdownProbe.scala](../../../server/src/test/scala/cn/xuyinyin/magic/actor/ActorShutdownProbe.scala) 只作独立诊断，保持100个routee、每万条休眠100ms、相同消息数和未消费回复，仅改变回复探针数量。每组在独立Java17、3GiB堆、G1进程运行；没有用扩大原10秒超时来获得通过。探针在10秒边界只观察termination future，避免失败时打印百万节点树；不替代原ActorSpec验收。

| 消息数 | 回复探针 | 退出前堆占用 | 实际关停耗时 |
|---|---:|---:|---:|
| 10万 | 复用1个 | 32.0MiB | 52ms |
| 10万 | 每条1个，共10万 | 193.6MiB | 962ms |
| 100万 | 复用1个 | 139.8MiB | 30ms |
| 100万 | 每条1个，共100万 | 1443.7MiB | 3327ms |

这些是单次诊断观测，包含未消费回复队列、GC和JVM差异，不能推出单Actor固定内存成本或生产容量。它们支持“探针规模明显放大资源与关停开销”，不是吞吐基准。

本轮原ActorSpec独立运行1/1通过，完整后端回归也通过；**旧超时这次没有复现，未证明其确切触发条件，也未修复该测试**。当前定位是大规模测试子Actor的清理路径和10秒验收边界；没有证据把旧失败归因于CDC协议、数据库排空或某个锁死。一次线程转储请求发生在测试JVM已正常退出后，返回1，其失败也留存，没有改用PID重试。

旧超时的确切触发条件仍标记为 `evidence_incomplete`；环境控制通道此前阻塞的根因同样没有定论。

## 回归、保留材料与范围

- 证明单测6/6；后端完整回归586/586、111个suite完成、0中止，随后生产package成功。本轮命令与日志保留；生产JAR的859个class与冻结生产classes一致，不含测试class。
- 808个已跟踪普通文件及3个符号链接保持原值，其中129个生产文件、323个原实验文件。旧探针报告、混合版本结果、失败运行、OOM/超时和阻塞材料均未覆盖；保留散列见 [source-boundary.json](source-boundary.json)。
- [前轮完整材料](../2026-10-24-guardian-os-restart/README.md) 仍描述当时的跨版本结果和环境阻塞。当前补跑通过只补齐新证据，不改写那次失败的历史。
- 当前生产入口依然不支持guardian JVM换代。本轮使用测试提供方 `test-systemd-cgroup-v2`，经外部可信观察器验签和测试控制表适配，复用生产排空、额度、Reader/Sink/Actor Journal恢复；没有伪造`managed-child-jvm`证明，也没有放宽生产提供方校验。
- G7模拟“已受理/已执行而结果未附着票据”的窗口，未真实丢弃D-Bus网络回复。Snapshot事务窗口不等于验证Actor从Snapshot快照加载恢复；当前恢复链路以Journal重放为证据。
- 仍未覆盖：主机/OS管理器重启接管、跨机选主、真正不可杀的进程、恶意同UID/root、真实PID复用、外部重启旧unit、生产密钥治理、多物理数据库故障切换、长期运行及70万物理表。四个负例的门闩保证T/J稳定，不证明控制能力丢失会自动冻结已获许可的业务写入。

原始12项材料、签名、进程与连接/事务视图、T/J和逐字段行、冻结源码/class、诊断日志及制品在 `evidence.tar.gz`。依赖JAR由228项散列锁定，完整runtime仍留在ignored target。真实密码、私钥不进入公共归档；旧私有机器备份仍保留。归档完整性与语义可离线运行：

```bash
python3 docs/experiments/2026-10-24-guardian-os-final-matrix/verify.py
# 后续工作区源码已变化时，只核对本次冻结证据：
python3 docs/experiments/2026-10-24-guardian-os-final-matrix/verify.py --archived-source
```

新归档核验器是在实验后编写的离线工具，不冒充活跃运行版本。它复用冻结版本的逐案例验收器，并额外核对同版完整12项、最终资源、六类完整控制记录、制品和前轮文件保持。只读复审发现首版可能接受负例grant被替换、正例owner被删除、额外running任务；实际RED复现保留于 `resource-verifier-red.json`，补强后与每例final/sealed的六类记录逐行精确比较，并拒绝这三种破坏以及虚构残留进程、清空负例配额，共五种对照。它不连接数据库、不发进程信号、不修改恢复状态。
