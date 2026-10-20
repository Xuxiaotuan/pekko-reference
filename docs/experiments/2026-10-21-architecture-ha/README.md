# CDC 平台结构与优雅退出验证

本轮以 `b083bec7cf37c1d81087ef9ea69e1cc2380388ce` 为干净基线，整理运行职责和前端目录，并修复检查中实际发现的停止、探测和服务生命周期问题。公开/持久化身份、目标组账本、T→J→ACK、C2 累计三次恢复和全部历史实验保留。没有部署外部服务或发布镜像。

[架构说明](../../ARCHITECTURE.md)描述当前边界，[源码导航](../../CODE_STRUCTURE.md)给出执行链路，[审查记录](review.md)区分已修问题、探索失败和未验证事项。本轮完成不代表已经通过生产高可用认证。

## 结构变化与源码结论

后端保留单 SBT 服务，按责任拆实现：

- `EventSourcedWorkflowActor` 保留命令/回复、持久化声明和副作用控制；`WorkflowStateTransitions`、`WorkflowDefinitionCodec`、`WorkflowHistory` 分别负责纯投影、定义校验和结果/历史编码。没有通过迁移 Journal 类名来拆目录。
- 私有 `StopController` 先于 recovery gate 处理 Stop，拒绝新执行，取消排队恢复，并允许当前 attempt 已接纳的 J 确认继续完成。可靠执行等 `engine.cancel` 的实际释放 Future 后退出，不把取消结果误写为业务完成。普通行流另等真实 Engine 结果并持久化真实成功/失败。
- `server/http/HttpServerLifecycle` 集中绑定、成功日志和协调 unbind/drain，使用框架关闭 hook；默认 HTTP grace 5s，外围 phase 10s，并校验配置关系。
- `cluster/ReadinessCheck` 合并实际探测，默认 2s 返回未确认就绪；超时不释放仍运行的 JDBC 探测 slot。`/health` 与 `/health/ready` 共用实际探测，`/health/live` 只说明进程响应，修正不可达的 `/api/v1/status`。
- Guardian 真正使用配置的 shard count 并拒绝非正数；legacy 定义回复 Actor 有 5s 预算，结果未知不冒充成功；Docker EXPOSE 对齐 8080。

前端按 `features/workflows`、`features/schedules`、`features/cluster` 组织页面/API/DTO，`shared/api` 放 HTTP 工具，`app` 放布局。现有 URL、请求身份和后端契约保留；退役页面/旧类型及旧文档逐字节归档到 `examples/legacy`，不进入产品源集。删除十个未使用的直接产品依赖声明，显式声明测试已使用的 esbuild 0.21.5，沿用 Yarn Classic 锁文件。部分包仍被间接依赖，不宣称全部从依赖树消失。

## 本轮实际检查

| 检查 | 实际结果 | 能支持什么 |
|---|---|---|
| 固定窗口红绿及最终定向检查 | 50/50，8 套件；真实行为红灯保留 | Stop、恢复竞态、普通流历史、就绪和 HTTP 局部契约 |
| 完整后端 | 503/503，82 套件、0 中止 | 默认源集回归；真实 MySQL 另行启用 |
| 打包与 stage | package、Universal/stage、Docker/stage 成功 | 产物构建；没有构建/推送 Docker 镜像 |
| 根 quick-test 入口 | 68/68，6 套件 | 局部快速回归；不是全量测试 |
| 独立空 node_modules 的 UI 安装 | 安装成功，frozen/offline 再核对通过 | 当前锁文件可安装；不依赖原脏安装残留 |
| 隔离 UI 检查 | 契约测试 5/5、TS/Vite build、lint 全通过 | 契约和静态构建；没有浏览器完整回归 |
| 显式真实 MySQL Runner | 27/27，3 套件、0 中止 | 下述固定窗口；不是 27 项自动恢复成功 |
| 独立离线核验 | PASS | 原始 SourceRecord、组身份、审计、ACK、T/J/D、六字段材料内部一致性 |
| 源码与历史保全 | 223 个运行源码/构建文件冻结；154 个旧文件哈希不变；6 份 UI 归档字节相同 | 实际运行版本和旧失败证据可追溯 |

后端完整检查只在测试 JVM 使用 `-Dpekko.actor.testkit.typed.system-shutdown-default=60s`；仓库默认清理等待未改。完整执行日志记录在 `backend-final.log`。不能把这个结果写成默认等待下旧百万 Probe 示例的清理也已经重新验证。

完整后端命令：

```sh
sbt -java-home /path/to/java17 \
  -J-Xmx3G -Dpekko.actor.testkit.typed.system-shutdown-default=60s \
  'pekko-server/test' 'pekko-server/package' \
  'pekko-server/Universal/stage' 'pekko-server/Docker/stage'
```

## 27 项真实 MySQL 结果的分类

使用 MySQL 8.4.11、ROW/FULL、GTID ON；真实 Debezium Source、事务 Sink 和 JDBC Actor Journal。版本和每项随机专用 schema 见 [run-manifest](run-manifest.json)。每个实验保存切点 T/J/D、原始 SourceRecord/回调/ACK、Journal payload、账本、审计和源/目标全部值。

| 范围 | 项数 | 实际结果 |
|---|---:|---|
| C2 自动恢复矩阵收敛项 | 10 | 同一 execution 有界恢复，最终逐字段一致 |
| C2 安全暂停项 | 7 | 保留上下文和实际差异；不计同步成功 |
| N0/J1/J2/C1/C0/K1/K2 历史窗口 | 7 | 全部收敛 |
| none、cancel-T 的实际释放控制 | 2 | Reader 关闭，liveRunners=0，逐字段一致 |
| 新增 stop-T | 1 | 已接纳批次确认及资源释放后 owner 才终止；新 JVM 恢复同 execution 并收敛 |

收敛和释放项源/目标均为 64 行，逐一对比 `id/label/amount/note/updated_at/revision` 六字段，缺行、多行和字段差异均为 0。材料包括 DECIMAL、NULL/空串、中文/emoji、微秒时间、同事务多次更新和主键修改。七项安全暂停保留其非零差异，不把“断言符合安全策略”扩大成端到端同步保证。REJECT-J 和 REJECT-J-RETRY 仍分别保持预算 0、1，不因明确拒绝新增恢复尝试。

### 新 stop-T：目标提交后停止 owner

测试先建立可信初始化及持久化流式 D。对 id=1 做真实更新，在真实 Sink 已提交 T、J 请求尚未到达 Actor 的位置扣住返回，保存 cutpoint；此时 T 已变、J 和 D 仍在基线。

发送真实 Actor Stop 后，实体仍响应 `stopping`，J/D 不提前推进，没有 `ENGINE_CLOSED` 或 `OWNER_STOPPED`。释放 T 返回后，原始日志确认顺序：

```text
T_COMMITTED → STOP_REQUESTED → JOURNAL_CONFIRMED
            → ACK_RETURNED → ENGINE_CLOSED → OWNER_STOPPED
```

Reader 只关闭一次，owner 终止时 liveRunners=0。随后终止测试 JVM，以新 JVM 读取真实 Journal；恢复相同 executionId 和推进后的 J，旧 Journal 未被写入假的 ExecutionCompleted/ExecutionFailed。接管使用一次既有恢复预算。再次修改源行后，最终 D 与最新 J cursor 对齐，64×6 全字段一致，目标账本 3 组，触发器审计 68 条，与原始记录推导的变化顺序相同。

这项是单 owner、真实 Actor Stop 与跨 JVM 恢复实验；不是实际 Sharding rebalance、多 owner fencing、强杀时 drain 或生产滚动发布实验。

## 证据和复核

- [离线结果](result.json)、[运行版本和场景](run-manifest.json)、[审查记录](review.md)。
- [证据包](evidence.tar.gz)：27 个实验库的 utf8mb4 SQL dump、独立 SQL 审计、原始日志/JSON；前后两份完整 server 源码与构建；41 份最终 UI 源文件/配置/归档文件；后端、前端、quick-test 和探索日志；各层 manifest。
- [历史保全](historical-artifact-check.json)、[UI 逐字节归档](ui-archive-check.json)、[产物核对](package-check.json)、[清理状态](cleanup.json)、[最终检查](final-check.json)、[根文件摘要](SHA256SUMS)。

证据包的 `source-final-manifest.json` 与真实矩阵开始时的 `run-source-manifest.json` 一致，223 个文件在正式实验期间没有改动；`change-manifest.json` 记载相对基线变化的 22 个源码/构建文件。运行对象是该快照和本次提交，不能只 checkout 基线 commit 重现新行为。JAR 不含真实实验设施；归档不含数据库凭据。

探索性编译失败、初次并发编辑导致的后端失败、HTTP 测试读取 EOF 的误判、早期单窗口预跑都留在 `exploratory`，不算正式 27 项。红绿证据和最终日志的区别见 review。没有改写旧 C2 失败或旧文章实验结果。

离线核验只读，不启动数据库：

```sh
python3 docs/experiments/2026-10-21-architecture-ha/verify.py
cd docs/experiments/2026-10-21-architecture-ha
shasum -a 256 -c SHA256SUMS
```

核验器复用不可变的历史验证模块，并独立核对新增 stop-T：从原始 SourceRecord 重算身份、成员和审计，确认真实 J 先于 ACK、原始 ACK 是连续前缀且不跨未确认组，核对实际关闭/终止顺序，重新计算全部字段差异。离线 PASS 证明材料内部一致性，不代替重新运行数据库实验。

重跑须准备独立 loopback MySQL、Java 17，启用 `RUN_MYSQL_CDC_RECOVERY_WINDOWS=1` 及 `CDC_WINDOW_MYSQL_HOST/PORT/USER/PASSWORD` 环境，编译 test 后显式运行三个套件 `RealMySQLCdcAutomaticRecoverySpec`、`RealMySQLCdcRecoveryWindowsSpec`、`RealMySQLCdcSessionReleaseSpec`。每项创建独立随机 schema；导出必须显式 utf8mb4。使用现有 Test classpath，不把设施打入生产包。

## 未验证边界

- **强制接管与生产 HA：** Pekko 默认 handoff 预算内部可能约 55s 后强制 stop；永久阻塞 JDBC、JVM kill、SBR/网络分区和多 owner fencing 未证明。退出预算必须同时覆盖 Pod preStop、HTTP、Sharding 和后续阶段；没有生产 RTO/SLA 证据。
- **恢复运维：** 三次预算含 execution 中断/接管，频繁重启可耗尽；尚无人工审核后的继续/放弃 API。安全暂停时业务数据仍可不一致。
- **配置升级：** 非默认 shard count 由此前忽略变为实际生效，可能改变映射；已有集群不可直接滚动修改，不承诺无损热切换。
- **探测：** 2s 响应预算没有取消实际 JDBC；永久挂起会保留工作 slot 并持续未就绪。
- **范围与性能：** 未做 DDL、共享 Reader、新快照、长分区、服务端掉电、binlog 清理/重置和 70 万表实验；未作浏览器完整回归。UI 仍有 985.10kB chunk 警告和工具链弃用提示，不声称体积/性能已优化达标。
