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

使用 CDC lab pool 创建了独立双 MySQL 槽，source/state UUID、端口和 datadir 均在派发前核对。矩阵首次运行暴露了测试设施中的两个旧边界，已修正源码路径和测试 worker 入口。修正后 `V2_READER` 子矩阵在 worker 启动阶段未生成 ready 证据，进程已终止，不能记为通过。

因此本轮真实故障矩阵结论是 `evidence_incomplete`。没有把 Graph 单测、source fence 存储测试或 bigdata-lab 两 Worker owner admission 结果升级成 source fence v2 + guardian + Graph + Sink 的端到端通过。

## bigdata-lab 边界

当前 `bigdata-lab` 的 Capture/Worker 仍配置 `source-fence-v2=false`。本轮没有修改生产 ConfigMap、Secret、Deployment 或业务 MySQL。现有线上证据只支持一 Capture、两 Worker 的 owner admission 互斥，不能支持 v2 自动接管结论。

## 未验证边界

- `cdc-runtime-acceptance` 中完整的 source fence v2、guardian 隔离、旧事务排空、Graph 到真实 Sink 的逐字段对账仍未通过。
- L floor 的 Graph 持续推进已完成代码接线和组件级验证，尚未在共享 Reader 的真实 GC 删除窗口中单独复跑。
- `TableCdcGraphRuntime` 已接管 Streaming 尾流，快照 LOW/HIGH/F 仍由 `BootstrapRunner` 执行，尚非完全移除 Runner 的重写。
