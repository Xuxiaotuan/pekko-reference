# guardian JVM 重启：Linux OS 隔离探针

本轮只新增测试设施和实验材料，未修改生产恢复协议，也未提交、推送。基线为 `2fe7c253eea0d5499e3558f5ab1259a068df3f29`。

**已取得跨版本的场景结果，尚未完成同一最终版本的整套验收。**当前可独立核验的12个场景中，8个完成恢复，4个安全暂停；统一版本重跑因实验环境控制通道阻塞而未完成，状态为 `external_blocked`。后端全量回归也存在原有 `ActorSpec` 清理超时，状态为 `evidence_incomplete`。不能把下表写成“当前版本12/12全部恢复成功”。

## 实际结果与版本

采用两个冻结版本的材料：`r-1792807935732137000` 的完整运行中选取11个有效场景；`r-1792808308848948000` 补足 Snapshot 定向场景。前一版本该项未建立事务窗口，保留为失败。两份活跃实验源码只在测试 Snapshot 触发器的 InnoDB 读取条件上不同，逐文件散列见 [selected-source-deltas.json](selected-source-deltas.json)。

| 场景 | 实际固定窗口 | 结果 |
|---|---|---|
| G5_BEFORE_FORK | 旧 guardian 已检查启动票据，停在 fork 前并被 SIGSTOP；停止整个边界后再放开旧门闩 | 无迟到 worker；新执行10行、六字段一致 |
| G6_FORK_UNRECORDED | worker 已创建并到达真实 Reader 门闩，启动票据的 PID 尚未写入；旧 guardian JVM 被杀 | 新 JVM 无原 Process；由原 OS 边界证明隔离，恢复后逐字段一致 |
| G7_START_ACK_LOST | systemd 已受理启动，ExecStartPre JVM 停在门闩，异步启动 job 尚未完成，票据未附着进程身份 | 关闭并停止原 job/边界；迟到放行不能启动旧 main；逐字段一致 |
| G7_EXECUTED_ACK_LOST | worker 已实际运行，故意不把创建结果写回票据 | 找到原隔离边界并终止，未重复创建旧 boot；逐字段一致 |
| G10_SOURCE | 同一条真实 Source 会话、InnoDB事务在旧 JVM 退出后仍由中继保留 | 排空前保留原 grant/permit；结束后逐字段一致 |
| G10_TARGET | 目标事务已执行写入并等待，旧 JVM 退出后同一服务端事务仍在 | 同上，目标审计无额外重放副作用 |
| G10_JOURNAL | 真实 Journal INSERT 被触发器门闩阻塞，T 已提交而该页 J 尚未完成 | 同一事务结束且两轮排空后再恢复，逐字段一致 |
| G10_SNAPSHOT | 真实 Snapshot INSERT 先读取 InnoDB 行，再等待门闩；旧 JVM 退出后同一会话/事务仍在 | 定向修正版本恢复成功，逐字段一致 |
| G14_CONTROL_DENIED | 实际以受限服务账户调用 systemd stop，被权限系统拒绝 | 保留旧 owner、permit、额度和T/J；Source10/Target0，缺10键 |
| G14_SIGNAL_ONLY | 发送 SIGCONT 返回成功，但旧边界仍有进程，不能提供退出证明 | 同上，正确暂停，不算数据收敛 |
| G15_LEGACY_UNBOUND | 没有持久化前代绑定；旁路诊断虽然看得到进程，恢复入口不信任它 | 同上，不按 PID 接管 |
| G15_UNIT_MISSING | 测试另行停止旧进程并移除其专属 unit，实际查询为 `LoadState=not-found`，但没有可接受的隔离证明 | 同上，“现在查不到”不能换成隔离成功 |

八个正例共80行、480个字段值，全部比较 `id/label/amount/note/updated_at/revision`，包含负主键、超过JavaScript精确整数范围的主键、中文/emoji、NULL、空串、DECIMAL及微秒时间。每例核对5页目标账本、CBOR Journal页checkpoint、batchId、cursor、Split边界、原execution及许可generation。四个负例保留40个缺失键及完整原始行，不能与正例混算。

精确的场景目录、runtime和归档前缀见 [result.json](result.json) 的 `selectedCases`。全部旧失败和先导运行都归档，未用最后成功覆盖。

## 这次证明了哪一层

实验环境是专用的 Ubuntu 24.04 **OrbStack Linux machine（LXC，运行于其Linux VM）**，并非独立物理机或裸机。配置上限为2 CPU、3GiB内存、6GiB磁盘，使用真实 systemd255、cgroup v2、JRE17和MySQL8.0.46。Source、Journal/Snapshot、Sink属于同一物理MySQL的不同角色；透明中继在被隔离的旧cgroup之外，仅用来保留原服务端会话。

每代 guardian 和它创建的 worker 位于独立、不可复用的实验 unit/cgroup。`ExitType=cgroup` 让旧 guardian 主JVM死亡后，worker仍可保持存活，实际验证丢失原Process的窗口；停止则使用 `KillMode=control-group`，禁止自动重启。新 guardian 先启动为仅恢复进程，在原票据退休前不能创建新业务worker。

可信外部测试观察器核对原 unit、InvocationID、cgroup和hostBoot后，停止完整边界并签出 `test-systemd-cgroup-v2` 证明。新 supervisor JVM使用启动前传入的公钥pin验签，而不是从可修改的证明目录接受新公钥。原始签名和OS查询、进程成员、服务启动/停止结果均保存。

**生产入口目前仍不支持 guardian JVM 换代。**实验使用 `server/src/test` 下的适配器，将新测试提供方的已验证证明写入控制库的隔离状态，然后复用原生产数据库排空、额度归还、writer permit、Reader、Sink和Journal恢复路径。没有伪造 `managed-child-jvm` 回执，也没有放宽生产 `confirmIsolation` 的提供方校验。

票据关闭只拒绝后续实验准入，不能撤销已通过检查的旧fork。实际隔离依据来自完整OS边界被停止；这轮尚未实现生产启动broker及其跨重启状态转换。G7是“已受理/已执行但结果未附着票据”的固定窗口，**没有通过抓包丢弃真实D-Bus回复**。

隔离后仍按原固定实例清单验证会话、事务及未归属资源。每个旧实例必须完整、间隔至少250ms达到两轮空确认，随后才归还旧任务额度、退休进程票据、发放新许可。G10逐项核对隔离前后同一连接ID和事务ID，并验证 `fenced < db_drained < old_quota_released < 新 recovery_permitted`。未bootstrap的场景关闭实验票据，不伪造owner/PID。

T/J数据恢复语义和旧持久化类型没有改变。本轮仅静态快照，D=N/A，没有CDC回调或ACK顺序结果。负例由测试门闩保持T/J稳定；这不证明 guardian 控制丢失会自动冻结已经获许可的业务写入。

## 设施修正、失败与审查

- 证明验收先以拒绝全部证明的最小实现运行：6项中5通过、1失败；随后6/6通过。非空子树、缺失原身份、开放启动路径、错误代次/operation和篡改签名均拒绝。
- 只读审查发现G15最初只设置失败标志、清理异常会跳过剩余服务、离线核验缺少逐实例两轮确认。修正为真实缺失绑定/缺失unit、逐项清理并保留未知状态，以及完整逐实例断言。原始审查意见和处理见 [code-review.json](code-review.json)。
- Linux机器的类型级LXC drop-in会将 `ProtectControlGroups` 和 `NoNewPrivileges` 设回关闭。首轮不满足该前提，不计最终选取结果。专属unit drop-in已用实际服务确认生效，所选材料同时核对服务运行值和成员 `NoNewPrivs=1`。
- 首轮Snapshot在重复读取状态文件时遇到部分JSON：同名ready标记未撤销，读者可能读到尚未写完的下一份状态。修正为每次请求先撤销自己的ready标记，等本次完成后读取，未放宽断言。
- 随后Snapshot真实门闩会话存在，但事务视图没有对应事务，未建立预定切点。仅修改测试触发器，先读取InnoDB行，再进入GET_LOCK，定向结果及独立语义核验通过。原失败、服务端会话和空事务字段仍保留。
- 离线验收器曾接受被改成“不完整、零轮”的排空材料，已实际复现RED；补强后原材料通过，实例轮次及最终drain事件两种篡改均拒绝。归档核验另外拒绝行值、cursor、签名等5类破坏性对照。
- 原始基线文件清单生成曾把目录符号链接当普通文件读取，随后按Git mode分别核对；未改变这些链接。打包首版误读OrbStack状态JSON层级，错误日志也保留。

## 验证与尚未完成的验收

| 检查 | 本轮结果 |
|---|---|
| 所选12个场景独立核验 | 两个冻结版本：8收敛、4正确暂停，80行逐字段一致 |
| 最后测试设施版本的证明单测 | 6/6 |
| 后端全量 | 586个断言通过，但 `ActorSpec` suite清理超时中止，**不算全量通过** |
| 原 `ActorSpec` 单独复核 | 同样在10秒ActorSystem清理阶段中止；未修改或放宽该相邻测试 |
| 最初默认1GiB堆的全测 | 持续GC/OOM，中止；保留原日志，随后使用独立3GiB测试进程 |
| 生产package | 成功；859个生产class匹配冻结版本，661个测试class未进入生产JAR |
| 原有文件保持 | 808个已跟踪普通文件及3个符号链接不变，其中129个生产文件、323个旧实验文件 |
| 同一最终版本完整矩阵 | **external_blocked**：最后一次G5已有数据完成记录，但没有完整结果与清理确认，不计验收 |
| 当前实验环境资源退出 | **未确认**：只对专用机器尝试重启；管理执行通道未恢复，不能报告所有进程/连接/事务已结束 |

最后一次统一重跑时，`orb run`、SSH执行及后续只读控制请求阻塞。独立文件pull/export仍可用，已取回材料并保存私有机器快照；取消本轮本地矩阵客户端，避免将其视为仍可受控的后台实验。只尝试重启名为 `pekko-guardian-os-probe-20261024` 的专用机器，没有全局重启OrbStack或操作现有Kubernetes/旧实验容器。当前阶段未进一步重试基础设施修复。根因尚未核实，不能归因为生产恢复协议；详情见 [environment-blocker.json](environment-blocker.json)。

所选场景各自的清理记录可核验，但它们不能替代当前机器的最终退出证明。未知时不补写“已释放”或“自动恢复成功”。私有文件系统导出位于 [private-backup.json](private-backup.json) 所指位置，权限0600，包含私有凭据，故不进入公共归档；未验收从该镜像恢复MySQL的一致性。

尚未覆盖：主机/OS管理器重启接管、跨机器选主、真实不可杀进程、恶意同UID/root、真实PID复用、外部程序重新启动旧unit、生产密钥治理、多个物理数据库故障切换、生产滚动升级与回退、独立Actor Snapshot载入路径、长期运行和70万物理表。不能把这轮探针称为生产自动接管保证。

## 材料与复现

原始案例、签名证明、完整T/J及逐字段行、进程/连接/事务、失败日志、冻结源码和运行class、制品检查都在按 `result.json.archive.parts` 顺序连接的归档中。散列见 [archive-manifest.json](archive-manifest.json) 和 [SHA256SUMS](SHA256SUMS)。依赖JAR使用精确散列清单；完整本地runtime仍留在ignored target目录。私钥与真实密码不进入公共材料，并扫描了JAR/ZIP及Journal Base64载荷。

离线运行：

```bash
python3 docs/experiments/2026-10-24-guardian-os-restart/verify.py
# 后续源码已经变化时，只核对历史冻结材料：
python3 docs/experiments/2026-10-24-guardian-os-restart/verify.py --archived-source
```

验证器不连接数据库、不操作进程或ACK。归档聚合入口是在实验结束后补齐，明确记录跨版本与阻塞状态；该离线变化单列于 [validation-revision.json](validation-revision.json)，不冒充活跃实验源码版本。

现场重跑必须先恢复独立Linux实验环境并核对资源。Java17编译、导出 `pekko-server/Test/fullClasspath` 后，在macOS执行 `run.py freeze`；在专用Linux机器以显式 `RUN_GUARDIAN_OS_PROBE=1` 执行 `run.py matrix --runtime /work/target/guardian-os-runtimes/<冻结版本>`，可用 `--scenario` 定向运行。该入口仅用于专用、可丢弃的loopback MySQL环境，每次新增随机schema，不删除旧schema。环境恢复后应重新跑统一版本全套；本轮没有把这个待做项写成成功。
