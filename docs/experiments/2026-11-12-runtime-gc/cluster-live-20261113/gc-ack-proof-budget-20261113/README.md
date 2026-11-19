# ACK proof budget 修复与真实集群验证（2026-11-13）

## 原因

线上 `primary` 的真实 `pekko_capture_log` 已有 7,110 条 ACK，最新 ACK 序号为 7,121。旧实现 `CaptureGcRetention.actualAck` 直接调用 `CaptureAckProof.inspect`，固定最多遍历 4,096 条链并受 15 秒预算限制，因此真实长链必然返回 `ACK proof budget exceeded`。这不是 Source/Sink 数据不一致，也不是 GC 删除事务本身失败。

此前一次重启还发现了 3 个属于专用 CDC 账号的旧 `Sleep` 会话。因为没有未提交 InnoDB 事务，实验中只杀掉了这 3 个精确会话；这次排空事实保留在 `failure-before.txt`，不能用后续成功覆盖。

## 修复

- GC 初始化时启用已有的 `CaptureAckPaging` 表。
- 新增 `pekko_capture_gc_actual_d_v1`，固定一个实际 D 的 proof identity、ACK head 和 operation 归属。
- GC 每次最多推进 128 个 ACK 引用/父链节点，状态写入 `pekko_capture_ack_pages_v1`、`...nodes_v1` 和 `...refs_v1`，可跨 tick、进程重启继续。
- proof 未完成时先提交分页状态并安全阻塞；proof 完成后才进入 `plan/deleteBatch`，避免外层 BLOCKED 回滚分页进度。
- 删除操作完成后，下一次 operation 才重新选择更高 D；旧 ACK/Journal/L 证据不删除。

## 真实 KubeSphere 结果

- PipelineRun：`pekko-cdc-runtime-g7kcr`，`Succeeded`，03:27:46Z—03:32:01Z。
- 实际镜像：`100.97.53.78:5001/xxt/pekko-cdc-runtime:build-3-61495e0f2b5e`。
- Deployment：`bigdata-lab/pekko-cdc-runtime`，1/1 Ready。
- 重启后 owner：`ACTIVE`；任务：`STREAMING / ADMISSION_READY / HEALTHY / RELEASED`。
- `/api/v1/cdc-gc`：`primary / DONE`。
- `pekko_capture_gc_actual_d_v1`：proof head `7742`；分页 proof `done=1`，revision `242`，ACK 节点 `7731`。
- 原 operation `runtime_315c...`：`DELETING 42→44` 变为 `DONE 44→44`，删除 1 个 GROUP，回收 235 字节；原始 L、ACK、Journal 和目标账本仍保留。

## Source、Journal、Sink 对账

真实 Source `source_rows` 与 Sink `target_source_rows` 当前均为两行，字段 `id、label、amount、note、updated_at、revision` 逐字段完全相等，结果见 `reconciliation.json`。Source 端保留一个 Debezium Binlog Dump 会话且没有 InnoDB 未提交事务；这符合持续 CDC 运行状态。

## 结论与边界

本轮真实证明了：超过 4,096 ACK 的 D 链可以持久化分页、跨运行推进、完成 GC 安全重规划，并保持 MySQL Source/Journal/Sink 一致。它没有证明 70 万活跃表、无限 ACK 历史、自动扩容、DDL 或多 owner 接管。分页 proof 表本身也需要后续归档/GC 策略，当前只保留完整证据。
