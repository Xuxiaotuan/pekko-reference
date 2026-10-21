# 目录与实现包统一

基线 `bf249b1823040abc056b65c0c16e462d914c1d73`。本轮统一根目录、文档分类、后端实现包和路径引用，保持原持久化身份、恢复协议、用户命令和全部既有实验。没有部署 Kubernetes、启动 Compose 服务或发布镜像。

当前目录见[完整导航](../../development/directory-layout.md)，实现与兼容约束见[审查记录](review.md)。

## 已完成的结构调整

- 后端由 pekko-server 迁至 server，界面由 xxt-ui 迁至 console；SBT 项目 ID 仍为 pekko-server。
- bootstrap 管启动/Guardian/HTTP 生命周期，api 管路由，workflow 管实体/执行/SPI/调度/查询，cdc 管身份与组协议，connectors 按系统归组，infrastructure 管集群/配置/监控/SQL/工具。
- 文档归为 architecture、guides、articles、development、archive、experiments；当前相对链接已重定位。实验旧文件不改字节，其中源码路径按所记录的版本解释。
- scripts 集中本地运行、测试和打包；quick-test.sh 和根 Compose 是兼容入口；旧模块和 k8s 路径以三条软链接兼容，没有重复源码。编辑器隐藏生成物、工具状态和兼容链接，原数据保留。

340 条文件移动映射覆盖迁移文件。基线 551 个跟踪文件均有保留或迁移目标。普通实现/测试符号重定位见 [class-moves](class-moves.json)，完整文件表见 [file-moves](file-moves.json)。

## 持久化兼容

Actor/Supervisor、WorkflowEvents、DSL、Checkpoint、ExecutionFailure/ExecutionModels、Scheduler 和 CborSerializable 的原声明包名保留。workflow/engine 仅留兼容值类型，执行实现移到 workflow/execution。没有使用类型别名冒充原 JVM 类，也没有修改 persistenceId、目标组账本、事件身份、T→J→原始回调前缀 ACK、C2 最大三次预算和实际释放 fence。

在移动前用旧代码捕获 18 份 CBOR，包括 workflow/scheduler 状态、事件、Checkpoint 和恢复故障。迁移前测试 1/1；迁移后用原 serializer ID/manifest 解码，类型名一致，重新编码与旧字节完全相同。该用例也进入完整后端测试。样本验证和当前版本的恢复回归是不同层级的证据。

## 构建与本地验证

| 检查 | 实际结果 |
|---|---|
| main/Test 干净编译及旧 CBOR 检查 | 通过；18 样本解码/重编码一致 |
| 完整后端 | 504/504，83 套件、0 中止 |
| 产物 | package、Universal/stage、Docker/stage 通过；JAR 无旧包 Engine 或测试设施 |
| 原 quick-test 入口 | 68/68，六套件；实际输出原六个 SBT aliases 和原 mainClass |
| console 隔离安装 | 空目录 frozen/offline 安装成功，无依赖升级 |
| console 检查 | 5/5 契约测试、TS/Vite build、lint 通过 |
| Compose | 展开结果与基线的项目/服务/卷一致，仅 bind 路径迁到 server |
| Kubernetes | 两个环境模板本地 kustomize 渲染通过，未应用到集群 |
| 文档和结构 | 当前文档链接、package/路径对应、兼容链接、Shell 语法、diff 检查通过 |
| 历史保全 | 166 个既有实验文件路径和 SHA-256 不变；前一轮离线核验仍 PASS |

完整后端只在测试 JVM 指定清理等待 60s；仓库默认未变。不将该结果扩大为默认等待下旧百万 Probe 示例的清理也已重新验收。UI 仍有 985.10kB chunk 警告和工具链弃用提示；未做浏览器完整回归。

## 真实 MySQL 回归与独立核验

显式启用真实 MySQL Source、Actor Journal 和事务 Sink，正式 **27/27 断言通过，3 套件、0 中止**；完整日志与源代码在证据包中。离线核验器从原始 SourceRecord 重算组/事件身份、成员证据、审计、原始 ACK 前缀、J 先于 ACK 和全部字段差异，结果 PASS。

| 范围 | 数量 | 结果解释 |
|---|---:|---|
| C2 自动恢复收敛项 | 10 | 同一 execution 恢复，64×6 字段无差异 |
| 安全暂停项 | 7 | 预算/证据/明确拒绝守住边界；仍保留数据差异 |
| N0/J1/J2/C1/C0/K1/K2 | 7 | 原恢复窗口全部收敛 |
| none、cancel-T | 2 | 实际 Reader 释放，liveRunners=0，对账一致 |
| stop-T | 1 | T 提交后停止，J/ACK/真实关闭先于 owner 退出；新 JVM 同 execution 收敛 |

20 项收敛/释放场景的源/目标各 64 行，对 `id/label/amount/note/updated_at/revision` 六字段逐一比较，缺行、多行、字段差异为 0。七项安全暂停不能写成同步成功；实际不一致保留在 result.json。REJECT-J 和 REJECT-J-RETRY 分别保持预算 0、1。COMMIT 不确定、旧 J/D、重新分批、主键迁移及 ACK 间故障仍按原协议验证。

stop-T 另核对实际顺序 `T_COMMITTED → STOP_REQUESTED → J_CONFIRMED → ACK_RETURNED → READER_CLOSED → OWNER_STOPPED`，恢复相同 execution，目标 3 组、审计 68 条，原始 ACK 分别为 bootstrap [0..4]、session [0,1]、recovered [0]；没有假的业务完成终态。它是单 owner 固定窗口，不是生产 Sharding 强制接管证明。

## 证据与重现

[独立结果](result.json)、[实际版本与场景](run-manifest.json)、[证据包](evidence.tar.gz)、[历史保全](historical-artifact-check.json)、[产物](package-check.json)、[Compose 比对](compose-check.json)、[最终检查](final-check.json)、[清理状态](cleanup.json)、[文件摘要](SHA256SUMS)。

压缩包保存 27 个实验库的 utf8mb4 SQL dump、独立 SQL 审计、原始回调/ACK、切点和最终 T/J/D、Journal payload、源/目标所有字段；前后完整 server 源码与构建；41 份 console 源码/配置/历史文件；最终命令/部署入口；旧 CBOR 生成工具与样本；完整构建、前端及探索失败日志。原有数据库凭据未打包；只读核验不启动服务。

```sh
python3 docs/experiments/2026-10-21-directory-layout/verify.py
python3 scripts/check-layout.py
cd docs/experiments/2026-10-21-directory-layout
shasum -a 256 -c SHA256SUMS
```

重跑数据库实验须使用独立 loopback MySQL ROW/FULL、GTID ON 和 Java 17；显式设置 `RUN_MYSQL_CDC_RECOVERY_WINDOWS=1`、`CDC_WINDOW_MYSQL_HOST/PORT/USER/PASSWORD`。编译 Test 后用实际 classpath 运行 RealMySQLCdcAutomaticRecoverySpec、RealMySQLCdcRecoveryWindowsSpec、RealMySQLCdcSessionReleaseSpec；它们的原 process main 名称未改变。各项新建专用随机 schema。离线结果仅证明保存材料内部一致性，不能替代真实重跑。


## 源码与未验证范围

本轮正式真实回归使用冻结的 226 个源码/资源/构建文件；正式启动后不再修改运行源码。前期编译日志保留原样：漏引入 JsonFormat、包限定可见性和两个特殊测试 import 是已纠正的迁移/设施错误，不能当成生产恢复协议故障。

普通内部实现的旧 import 不作为兼容 API；原 main、SBT alias、根脚本和历史 probe main 保留。Compose include 需要 2.20+。没有新增强制接管、多 owner fencing、DDL、共享 Reader、长分区、服务端掉电、binlog 清理/重置、70 万表或生产 SLA 证据；本轮整理不意味着这些边界已解决。
