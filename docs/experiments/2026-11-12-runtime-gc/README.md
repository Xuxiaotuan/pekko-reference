# 独立 Runtime 的 L GC 调度与旧命名空间启用

本轮把 L GC 从 Runner 内存请求接入独立 `CdcRuntimeMain`：审计请求、GC operation、namespace inventory、pins 和批次游标均持久化在同一 state MySQL。未知 B 仍安全暂停；本轮没有提交或推送。

源码基线由 [source-manifest.sha256](final/source-manifest.sha256) 固定。`final2` 的运行清单和本轮最终工作区清单分开保存；严格账号清点修复后没有把旧运行结果改写成新版本结果。早期失败材料没有被覆盖。

## 实际结果

- 独立 Runtime 真实入口 RG01—RG07 共 **7 项通过**：5 项收敛、2 项按预期安全暂停。
- 固定 RG01 负载连续重复 10 轮，约 12 分钟的生命周期 soak **10/10 通过**；每轮逐字段相等，最终连接和事务采样为 0，见 [soak 证据](soak-20261113/README.md)。
- L GC 真实 Reader/Journal/Sink 的严格串行重跑 LG01—LG16 加容量补测共 **17 项通过**，记录在 [capture-gc 严格证据](../2026-11-12-capture-gc/strict-final/verification.log)。此前并行运行导致账号清点门失败的材料仍保留，未用放宽测试门掩盖。
- 选定本地主流程、Runtime、Journal、关闭、兼容和协议回归共 **53 项通过**，见 [regression.log](final/regression.log)。
- 收敛用例的真实 MySQL 源表和目标表逐字段对账一致；慢表场景在持有目标行锁时仍保留保护前缀，释放锁后继续追平。未知 B、未清点 Journal、危险账号、清点中断、异常退出均没有放行删除。
- [资源释放](final/resource-release.json) 显示最终检查时 Source/State 两个专用 MySQL 的外部连接和 InnoDB 事务均为 0，随后实例已关闭。测试数据目录、Journal 和失败材料保留。

## 主流程改造

1. `CdcPlatformKernel` 增加 GC 维护边界和状态查询；`CdcPlatformRuntime` 创建单个有界 GC worker，与 Snapshot/Tail 预算分离。GC 任务不为每张冷表创建常驻 Actor。
2. `CdcGcCoordinator` 每次维护只推进一个持久化小单元：读取实际 D、检查 namespace/pin/proof/unknown B、固定 operation 上界，调用 `CaptureGcRetention` 做小批删除；锁超时或提交结果未知时写入 BLOCKED/UNKNOWN，不伪造完成。
3. 每个 STREAMING 任务的 GC 审计请求先写 `pekko_capture_gc_audits_v1`，通过原 `StepAudit` 后由 Actor Journal 封存 v2 proof，目标组账本和 activation 收据验证成功才标记 ACTIVE。已有覆盖当前位置的证明不会重复创建 Actor。
4. 增加 `GET /api/v1/cdc-gc`，只读展示 namespace、operation 和阻塞原因。API ready、namespace COMPLETE、表初始化完成、GC proof ACTIVE 分开判断。
5. `CdcGcSettings` 只在 `gc.enabled=true` 时启用；账号、角色、trusted admin、producer inventory 和旧命名空间启用均显式校验。未配置 GC 时保持原独立 Runtime 行为。
6. 平台 JDBC 的默认 socket timeout 从 5 秒改为 30 秒，避免慢表持锁时被误判为基础设施失败；connect timeout 仍为 5 秒。

## 旧命名空间启用边界

Reader 启动前，Runtime 取得 owner 和 capture 命名锁，检查旧 producer 会话、事务、grant 和未知 B，分页清点订阅、Bootstrap consumer、真实 Journal 最新事件与身份，并写入不可变 namespace 配置摘要、inventory root/count。中断时事务回滚；重启重新清点，不续用半截游标。

旧命名空间只有在 `enable-legacy=true` 且管理员明确提供 GC 配置时启用。以下情况保持 BLOCKED/UNKNOWN：

- 旧 B_PENDING、孤儿 Journal、删除标记、snapshot-only 历史或不支持的 Actor identity；
- producer 有危险全局/代理/证据表修改权限；
- Journal、目标账本或 namespace permit 可以被 producer 修改；
- 旧 owner、连接或事务排空未知；
- 持久化 discovery 未完成；
- GC writer 与 SDK/control/Journal 账号复用。

## 入口故障矩阵

| 用例 | 注入窗口 | 结果 |
|---|---|---|
| RG01 | 旧 namespace 启用、GC、Runtime 停止、重启、继续写入 | 通过；operation 续跑，逐字段一致 |
| RG02 | b 为慢表并持有目标行锁，a 同时推进 | 通过；慢表 pin 阻挡前缀，释放后 b/a 继续追平 |
| RG03 | 新增 B_PENDING | 安全暂停；无 GC frame 删除 |
| RG04 | 注入 foreign/孤儿 Journal | BLOCKED；不启用 namespace、不删除 L |
| RG05 | producer 增加危险 schema 权限 | BLOCKED；保留业务同步证据 |
| RG06 | 清点阶段触发 pin 写入失败 | 事务回滚；discovery 仍 UNKNOWN，重启重新扫描 |
| RG07 | Runtime 在 owner ACTIVE、GC 运行中直接退出 | 新 Runtime 保留 UNKNOWN，不凭 PID 自动接管；业务数据仍可对账 |

RG 证据目录在 [final/verified-runtime-final2](final/verified-runtime-final2)；首次启动时未先启动实验 MySQL 的连接拒绝材料保留在 [final/verified-runtime-final](final/verified-runtime-final)。每个目录保留 `initial.log`、`final.json`、`failure.json`（如有）、T/J/L/D、pins、namespace、schedule、operation 和逐字段 comparisons。RG02 的第一次失败原因和修正版慢表重跑分别保存在 [final/runtime](final/runtime) 与 [final/slow-rerun2](final/slow-rerun2)，没有删除失败证据。

## L GC 结果和限制

L 只删除可重建的 GROUP 正文，保留 ACK、控制帧、Schema、恢复帧、目标完整组账本、Journal 和基线收据。逻辑 `used_bytes` 会重算并核对；没有宣称 InnoDB 文件缩小或系统总存储下降。

首版仍有明确限制：

- 取消表的保护不会自动撤销；慢表可以阻挡全局前缀。
- 容量耗尽安全暂停，未验证自动扩容。
- 受保护恢复点之外的旧任意历史不承诺可恢复；证据缺失直接拒绝。
- DDL、多 owner guardian 接管与 GC 的完整组合、恶意管理员、70 万真实活跃表和长时间吞吐未在本轮验证。
- GC 调度当前按单 Runtime/capture 有界执行；没有把 70 万表变成 70 万常驻 Actor。

[上一轮 L GC 组件和 17 项矩阵](../2026-11-12-capture-gc/README.md)仍保留原始失败材料。源码与实验文件已提交到 `0b571796858d14f46f7d81196139f677192a812b`；网站正文将在对应站点提交后再核对线上链接。

## 严格最终重跑

在恢复 Capture GC 严格账号清点后，独立 Runtime RG01—RG07 使用相同专用 Source/State MySQL 串行执行，**7/7 通过**；日志和逐项材料见 [strict-final](strict-final/)。该运行与前一轮 `final2` 分开保存，源码清单以 `strict-final/source-manifest.sha256` 为准。
