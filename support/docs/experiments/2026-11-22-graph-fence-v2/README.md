# Graph 主路径与 source fence v2 验证

日期：2026-11-22

## 本轮代码变更

- `CdcBootstrapKernel` 为每个表创建唯一的 `TableCdcGraph`。快照阶段仍由 `BootstrapRunner` 按 LOW/HIGH/F 完成，Journal 进入 `STREAMING` 后，`TableCdcGraphRuntime` 接管持续读取、目标提交、Journal 更新和 L 投影。
- Graph 使用 `BOOTSTRAP` consumer 身份，因此 ACK 后的 `observed_floor` 与 Journal 证明可以推进同一条 L floor。GC checkpoint 请求会先停止尾流 Graph，再由原 Runner 封存 Journal proof，避免两个执行器同时推进同一张表。
- source fence v2 的恢复许可完成后不再返回 `SOURCE_FENCE_V2_RUNNER_GATE_UNAVAILABLE`。已验证的 Reader gate 先安装并启动 SharedMySQLCapture，后续任务从同一 Journal `deltaAfter` 进入 Graph。
- 测试矩阵的旧源码路径已修正为当前 `cdc/platform` 路径，并使用显式 CDC test worker 入口。该入口只存在于测试配置，不是生产部署入口。

## 已完成的本地证据

| 层级 | 结果 | 证据 |
| --- | --- | --- |
| Compile/Test compile | PASS | `sbt 'pekko-server/Compile/compile' 'pekko-server/Test/compile'` |
| Table Graph materialize/stop/rematerialize | PASS，2 tests | `TableCdcGraphSpec` |
| Journal fence storage | PASS，6 tests | `CdcRecoveryFenceStorageSpec` |
| Graph source/journal/ACK wiring | PASS，2 tests | `TableCdcGraphSpec` |
| Graph + Bootstrap source compile | PASS | 本轮增量编译 |

## 真实 MySQL/guardian 矩阵结果

使用 CDC lab pool 创建了独立双 MySQL 槽，source/state UUID、端口和 datadir 均在派发前核对。历史运行材料保留了测试设施修正前的启动失败、旧 Owner 未排空和逐字段缺失证据。最终运行在同一隔离环境完成了 source fence v2、Guardian、Journal、Graph 和真实 Sink 的 C1 逐字段对账。

历史材料仍按 `evidence_incomplete` 保存，不能删除或改写。最终 C1 材料单独记录为 `V2_READER_B_ONLY_PASS`，结论只覆盖本轮使用的单表、64 行、384 字段和固定故障窗口。

## bigdata-lab V2_READER 实际复跑

在 `cdc-runtime-acceptance` 中创建了两个临时 MySQL 8.0.36 实例，业务 `bigdata-lab/mysql` 未复用。Worker 已完成 Guardian attestation、managed manifest、runtime、Capture 和 HTTP ready。C1 代理真实丢弃旧 Owner 的 COMMIT 回复，旧 Owner 隔离、Source binlog 会话排空、新 Owner 消费 `RECOVERY_PENDING` permit、安装 Reader gate 后继续执行。

最终 C1 结果为 `PASS`。新 Owner 完成两窗口 LOW/HIGH 扫描、B 到 F 回放、激活和 Streaming，`03-v2-reader-captured-c1-20261122.json` 显示 `equal=true`、`fieldsCompared=384`，缺失、额外和字段差异均为空。`cleanup-c1-20261122.json` 显示 Guardian drain 已确认、源端和 state 连接已释放、数据断言通过。完整材料见 [`k8s-v2-reader-20261122`](k8s-v2-reader-20261122/README.md)。

修复前的 C1 失败材料仍保留在 [`v2-reader-c1-failure-20261122`](k8s-v2-reader-20261122/v2-reader-c1-failure-20261122/)，其中包含 `RECOVERY_PENDING/local_release_unconfirmed`、Reader 未启动和逐字段缺失证据，不能用修复后的通过结果覆盖。

## bigdata-lab 边界

当前 `bigdata-lab` 的 Capture/Worker 仍配置 `source-fence-v2=false`。本轮没有修改生产 ConfigMap、Secret、Deployment 或业务 MySQL。现有线上证据只支持一 Capture、两 Worker 的 owner admission 互斥，不能支持 v2 自动接管结论。

## 未验证边界

- source fence v2、Guardian 隔离、旧 Owner 排空、Journal 恢复和 Graph 到真实 Sink 的 C1 矩阵已在本轮通过。证据是 `03-v2-reader-captured-c1-20261122.json` 和 `cleanup-c1-20261122.json`。
- L floor 的 Graph 持续推进已完成代码接线和组件级验证，尚未在共享 Reader 的真实 GC 删除窗口中单独复跑。
- `TableCdcGraphRuntime` 已接管 Streaming 尾流，快照 LOW/HIGH/F 仍由 `BootstrapRunner` 执行，尚非完全移除 Runner 的重写。

## V2_READER 历史失败与最终复跑

历史 `final15`—`final22` 材料分别记录了 Worker ready 表象、旧 Owner 资源排空等待、重复恢复导致的 `unclassified_failure` 和 capture-state 5 秒查询超时。这些失败没有被删除。源码修复了三处实际问题，恢复入口只把 `bootstrapReady` 作为 admission 条件、已释放 permit 在 proof 分页未完成时继续推进、capture-state 控制语句使用 30 秒有界超时。

最终 `final23` 在同一环境完成了旧 Owner 隔离、遗留事务为空、Journal `RECOVERY_PENDING` 消费、Reader gate、两窗口扫描、B 到 F 回放、Graph 激活和目标对账。历史失败与最终通过分别保留，不能把最终通过追溯成历史运行也曾通过。

## 最终 V2_READER 主流程复跑

无故障主流程和 C1 故障流程均在同一隔离双 MySQL 环境完成。无故障流程的 `03-v2-reader-b-captured.json` 与 C1 流程的 `03-v2-reader-captured-c1-20261122.json` 都显示 `equal=true`、`fieldsCompared=384`，缺失、额外和字段差异均为空。两份 `cleanup` 均确认 Guardian drain、连接释放和数据断言。

本轮为两个真实边界提供了不同证据。主流程证明 Graph、真实 Sink 和逐字段对账链路，C1 证明旧 Owner 排空、Journal `RECOVERY_PENDING`、新 Owner Reader gate 和恢复后的 Graph 连续执行。修复前失败证据继续保留，不能把局部单测或旧失败抹掉。
