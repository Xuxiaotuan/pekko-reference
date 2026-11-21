# K8s 独立 CDC Runtime 端到端验收

日期：2026-11-20

## 环境

- Kubernetes：`v1.23.17`
- 节点：`xjw / 192.168.31.115`、`xxt / 192.168.31.251`
- 命名空间：`bigdata-lab`
- 独立入口：`Deployment/pekko-cdc-runtime`
- 镜像：`100.97.53.78:5001/xxt/pekko-cdc-runtime:build-b9f6daf-amd64`
- 镜像 digest：`sha256:197bc6a3d4596e8afdc1f8d329f9c5dfa88db7ca2a780548d3eb4f5a7d52cd66`
- 验收状态库：`cdc_runtime_e2e_b9f6daf`，位于 `cdc-runtime-acceptance/state-mysql`
- 源库：`pekko_cdc_runtime_acceptance.source_rows`
- 目标表：`cdc_runtime_e2e_b9f6daf.target_rows`

旧的 `pekko-cdc-single` 和 `pekko-workflow` StatefulSet 未修改。

## 结果

### 首次隔离库切换

第一次任务使用 `cdc_runtime_b9f6daf`。控制状态已经切到新库，但 Journal 仍读取旧的 `DB_NAME=cdc_runtime`，触发源码中的 `BootstrapJournalProgressVerifier.create` 身份校验：`server_uuid + DATABASE()` 不一致。该任务进入 `PAUSED/unclassified_failure`，日志保留了 `bootstrap Journal and capture state differ` 的调用位置。

将独立 Deployment 的 `DB_NAME` 绑定到同一隔离库后，重新提交任务成功进入 `STREAMING`。这次失败是配置绑定问题，未修改恢复协议。

### 干净验收任务

- task：`e512dbdde769f23e081d4689bdcbce82d21a5aecb3301f9a11ee19f6547ede8e`
- request：`accept-b9f6daf-clean`
- execution：`3699b5aa-5219-44e8-b591-4a9db4076142`
- 初始状态：`QUEUED -> STREAMING`
- 初始化证据：`B=binlog.000017:401394272`，`F=binlog.000017:401395596`
- Journal revision：启动后为 `10`，增量变更后为 `12`
- Runtime：`ownerState=ACTIVE`，capture `ADMISSION_READY/HEALTHY`

在任务进入 `STREAMING` 后执行一个真实 MySQL 事务，更新 id=4、插入 id=9、删除 id=8。重启 `pekko-cdc-runtime` 后再次执行同样的读取和增量消费，目标结果为：

```text
4  clean-post-restart  63.2500  clean-replayed  2026-11-13 05:48:00.123456  7
9  clean-insert        72.7500  clean-replayed  2026-11-20 17:05:00.123456  1
```

源表和目标表的主键、字符串、金额、文本、时间戳、revision 逐字段输出完全相同。`diff -u` 结果为空，`rows_source=2`、`rows_target=2`、`full_field_diff=0`。

### 取消与资源观察

在另一隔离库中验证了 `STREAMING -> CANCELING -> CANCELED`，取消后 capture 仍为 `ADMISSION_READY/HEALTHY`，目标逐字段对账仍通过。

独立 Runtime 的状态库存在一个 `cdc_sdk` 空闲连接上的长期 InnoDB 事务。源库未观察到对应活动事务。该现象尚未证明为错误，也没有扩大为“全部连接已释放”的结论，需要单独做连接归属和停止排空验证。

### GC

干净验收库中，收敛 `cdc_gc` 为表级权限后，Journal 证明成功激活：

- namespace：`COMPLETE`
- schedule：`READY` 后进入受控删除，曾因实际 D 证明尚未完成安全进入 `BLOCKED`，Runtime 重启后从持久化 actual-D 证据重新规划并恢复为 `READY`
- audit：`ACTIVE`
- proof：`c47f27d78f9eebc83f1de6ed581007eb1691ad93f0bedb9ab364a72d4ef29ee5`

最终 GC 状态：`reclaimed_before=153`，actual-D `head_seq=170`，4 个删除操作均为 `DONE`，其中一个操作删除 2 条原始 GROUP 记录并回收 403 字节。旧任务的 `REQUESTED` 审计仍在旧隔离库中保留，作为独立失败材料。

旧隔离库还保留一条已取消任务的 `REQUESTED` audit，因此 GC 在 `actual D proof pending` 处安全阻塞。该失败证据不能被干净验收库的通过结果覆盖。

## 边界

本次实际验证覆盖独立入口、同库 Journal 身份、LOW/HIGH 初始化、实时回放、真实 MySQL 事务、Runtime 重启、取消、Journal 证明激活和全量逐字段对账。

本次没有验证 70 万真实活跃表、跨实例共享 Reader、多 owner 自动接管、DDL、Paimon、GitHub Actions 自动部署和旧 Workflow 状态迁移。GitHub Actions 的 verify/image 通过，deploy 因仓库缺少有效的 `KUBE_CONFIG_DATA` 失败，集群部署采用已授权的手工镜像推送和 `kubectl` 更新完成。

## 2026-11-21 清理旧服务后的复核

删除旧 `pekko-cdc-single`、`pekko-workflow` 及其 Service、配置和凭据后，独立 Runtime 的 owner 曾因历史 `UNKNOWN` 状态拒绝接管。核对确认 active/unknown grant、未消费 permit、versioned claim 均为 0，随后以条件更新将 owner 收敛为 `STOPPED`，Runtime 重新 admission。

`CdcTaskStore.finish` 增加一行最小修复，在成功的 `STREAMING + validationComplete` checkpoint 中清空历史 failure。amd64 镜像已加载到 `xjw` 节点运行，Harbor 推送因权限不足未完成。

复核结果：`ownerState=ACTIVE`，任务数据库状态为 `STREAMING/HEALTHY/failure_code=NULL`，Reader 日志显示已连接 binlog。源表和目标表当前均为 2 行，逐字段 `diff=0`。Capture 在无新事件的空闲窗口可能显示 `CAPTURE_OBSERVATION_STALE`，发生真实变更后恢复为 `ADMISSION_READY/HEALTHY`。

## 2026-11-21 两组件入口试运行

本轮提交 `331b5d2` 及后续修复已构建为 amd64 镜像，并在 xjw 节点加载 Harbor 镜像 `192.168.31.115:5001/xxt/pekko-cdc-runtime:331b5d2-amd64`。部署清单拆为：

- `pekko-cdc-capture`，单副本，使用 `CdcCaptureRuntimeMain`，负责 Debezium Reader 和 L。
- `pekko-cdc-worker`，Worker-only 配置，使用共享 L、TableActor、TableGraph 和任务 lease。
- `pekko-cdc-worker` HPA，当前使用 CPU 资源指标，最大副本数为 8。

Capture Pod 已实际连接到 `192.168.31.251:32372`，Reader 日志显示从 `binlog.000017/401383979` 恢复并进入 streaming。Worker 能取得新的平台 owner，`/api/v1/cdc-status` 返回 `ownerState=ACTIVE`。

切换过程中发现旧 combined Pod 被强制删除后仍有两个 Docker 容器存活，继续持有旧 owner 和 MySQL 空闲连接。保留了旧状态和连接证据，确认无 InnoDB 未提交事务后才停止这些旧容器并重新接管。该过程说明 Kubernetes Pod 删除与实际 JVM/容器终止不能混为一谈。

两组件试运行中，新任务进入 Worker 调度后出现两类未完成证据：

- 旧遗留 Worker lease 曾为 `UNKNOWN`，在旧容器清理后已能收敛为 `RELEASED`。
- 新 Worker 的 Graph 创建曾报 `com.typesafe.config.ConfigException$Missing`，补齐 `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD` 后重新进入执行。当前新任务仍需要完成真实 Sink 逐字段对账，不能使用此前 combined Runtime 的成功结果代替。

本轮两组件部署因此标记为 `evidence_incomplete`。Capture 独立入口和 Worker owner/lease 已验证，Worker 从 L 到目标的完整初始化、HPA 扩缩容和跨 Worker 交接仍待完成。旧 combined Runtime 的历史通过材料继续保留，未与本轮失败证据合并。

## 2026-11-21 两组件新镜像和流水线复核

本轮源码提交为 `bd4e3e1`。KubeSphere 流水线使用当前分支构建并推送了镜像
`100.97.53.78:5001/xxt/pekko-cdc-runtime:build-10-bd4e3e1ec804`，同时更新了
`pekko-cdc-capture` 和 `pekko-cdc-worker`。旧 `pekko-cdc-runtime` Deployment 保持 0 副本。

流水线结果仍为失败。失败窗口发生在 Capture 重启时，控制库保留了历史 `ACTIVE` 或 `ACQUIRING` owner。前两次镜像的 `IS_USED_LOCK` 读取错误使用了 JDBC `wasNull()`，未能自动回收旧 owner。随后发布的 `build-10-bd4e3e1ec804` 改为显式读取第一列对象并判断 `NULL`，Capture 日志已经出现 `CDC capture owner recovered after source lock release`，说明 source lock 证明和旧 owner 回收路径实际执行成功。流水线最终仍因 Worker 的历史 UNKNOWN grants 阻挡平台 owner 而失败。

当前 Capture 的实际证据如下：

- `pekko-cdc-capture` 使用 `build-10-bd4e3e1ec804`，Pod 为 `Running/Ready`。
- `pekko-cdc-worker` 使用同一镜像，Pod 为 `Running/Ready`。
- Capture owner 为 `ACTIVE`，Reader 从 `binlog.000017/410958877` 继续读取。
- Debezium 日志显示已连接 `192.168.31.251:32372`，server id 为 `18501`，随后持续产生 binlog records。

Worker 的平台 owner 仍被历史 `UNKNOWN` grants 阻挡，控制库保留 4 条 `UNKNOWN` grant。系统因此没有强行接管，也没有把旧任务的暂停状态解释为端到端成功。当前只能证明 Capture 独立入口、source lock recovery 前置条件和新镜像启动，不能证明本轮 Worker 从 L 到 Sink 的全量逐字段一致性。

`IS_USED_LOCK` 的修复提交为 `bd4e3e1`，已经通过 21 项运行时测试，并在本轮 Capture Pod 日志中得到实际恢复证据。Worker 平台 owner 的历史 UNKNOWN grants 尚未完成安全清理，因此新的真实 Sink 对账仍未执行，保留为未验证边界。

## 2026-11-21 g7 真实 Worker 任务结果

在旧 UNKNOWN task lease 保留的前提下，新建了独立任务 `103dfafcb3e5a030bac232f19addb98e863b35e47dfa9a8151ee021c40d1d19c`，generation 为 `g7`，目标表为 `target_rows_g7`。Capture 投影已写入该订阅，Worker 领取后进入执行，但首次 Journal proof 仍失败，任务进入 `PAUSED/UNKNOWN/unclassified_failure`。

失败调用链落在 `BootstrapJournalProgressVerifier.verifyOn`。原始 `event_journal`、任务状态和投影均保留。该结果证明了新任务从控制投影进入 Worker 的路径，不能证明 Sink 已完成初始化或逐字段一致。

随后提交 `75d7ff0` 将首次 Journal identity 不完整时的 proof 激活改为延迟，并保持 `journal_floor` 不前进。该修复完成本地编译和 21 项核心测试，但尚未在集群流水线中得到新任务的真实 Sink 对账结果。

最终流水线复核中，`pekko-cdc-capture` 和 `pekko-cdc-worker` 均已运行同一 Harbor 镜像并处于 `Ready`。KubeSphere PipelineRun 仍报告失败，失败日志未提供新的业务成功证据，不能把 Pod Ready 升级成端到端完成。当前验证结论保持 `evidence_incomplete`。

在提交 `75d7ff0` 的镜像上又提交了 generation `g8`，task 为 `57e9de99fda67e5b32991a51e5170ba6136b0b505451ed1e5e4c0e52648cc0a2`。Worker 已成功 admission，但任务第一次执行仍进入 `PAUSED/UNKNOWN/unclassified_failure`，Journal 只留下序号 1 的暂停事件，目标表逐字段对账没有产生。该结果说明“首次 Journal proof 延迟”修复尚未被完整端到端路径证实，g7 和 g8 的失败材料都保留。

Pipeline 的多个 Run 均完成了镜像构建、推送和两 Deployment 更新，但最终状态仍为 `Failed`。当前线上两组件使用同一 Harbor 镜像且 Pod 为 `Ready`，这项证据只支持部署和进程健康，不支持 Worker 初始化、Sink 提交或最终一致性。

## 2026-11-21 bbb56b2 两组件真实运行复核

本轮使用提交 `bbb56b2` 构建了 amd64 镜像并部署到 `pekko-cdc-capture` 和 `pekko-cdc-worker`。镜像 digest、Pod、Reader、Worker、任务和 Task lease 的原始结果见 [结构化证据](2026-11-21-bbb56b2-runtime-e2e.json)。

第一次本地构建继承了 macOS arm64 架构，节点启动时报 `exec format error`。该镜像没有继续用于验收。随后使用新 tag 重新构建 `linux/amd64` 镜像，两个 Pod 均进入 `Running/Ready`。Capture 已连接真实 MySQL `192.168.31.251:32372`，从 `binlog.000017/411693051` 继续读取。

使用真实 Worker 提交任务 `accept-bbb56b2` 后，任务进入 `PAUSED/UNKNOWN`，失败信息为 `java.util.NoSuchElementException: empty.head`。Task lease 最终为 `RELEASED`，`last_l_seq=0`、`last_journal_revision=1`、`last_target_batch=NULL`。日志还记录了新订阅与已有订阅重复的 `table already subscribed` 警告。

本轮没有执行目标表逐字段对账，因为 Worker 在初始化完成前已经失败。该材料只能证明 amd64 部署、Reader 连接和 Worker owner admission，不能证明新版本的全量初始化、Sink 提交或端到端一致性。
