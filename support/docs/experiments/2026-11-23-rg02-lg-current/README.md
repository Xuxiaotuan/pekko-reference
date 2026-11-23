# 2026-11-23 当前版本 RG02/LG 矩阵基线

## 代码与发布基线

- Pekko 代码：`a10a1f7`
- 网站：`4dbb538`
- 当前分支：`main`
- 当前实验使用 MySQL 8.0.36、Debezium、Actor Journal 和 MySQL Sink。

## 当前结果

| 项目 | 当前状态 | 证据 |
| --- | --- | --- |
| RG01 | passed | `support/docs/experiments/2026-11-23-rg02-lg-current/RG01-pass-56fea1c/` |
| RG02 | passed | `support/docs/experiments/2026-11-23-rg02-lg-current/RG02-pass-a325e16/` |
| RG07 | passed | `support/docs/experiments/2026-11-23-rg02-lg-current/RG07-pass-56fea1c/` |
| LG01—LG16 | passed | 当前版本 `LG-current-20261123/`，17 个场景全部通过，LG16-capacity 以 `passed_safe_paused` 记录容量安全暂停 |

RG02 在同一最新 Graph 版本上复跑通过。此前失败版本在 B 表进入 STREAMING 后，物化 Graph 推进了 Journal 的 `revision/deltaAfter/batchSequence`，下一次调度仍用旧 clean continuation 做全量相等比较，触发 `clean bootstrap step no longer matches Journal`。修复后，Continuation 对同一 STREAMING 身份只接受 `revision` 增长且 `deltaAfter/batchSequence` 单调前进，仍拒绝执行身份、定义、页、GC proof 或游标回退。真实复跑中，慢表目标锁窗口内 A 表继续追平，B 表在解锁后恢复，GC 受 pin 保护，正常重启后两表逐字段一致。

## 实验环境

- 临时资源定义：`/private/tmp/v2-mysql-k8s.yaml`
- Source 本地端口：`25821`
- State 本地端口：`25832`
- 每次实验结束后删除 `cdc-runtime-acceptance` 下的临时 Deployment、Service、Secret，并关闭端口转发。

## 证据边界

- 历史 `strict-final` 和 `final/verified3` 材料只能说明历史版本结果。
- 两表实验不能证明 70 万物理表容量。
- 历史 RG02 的 ask 超时、`local_release_unconfirmed` 和逐字段不一致材料仍保留，不能被本次通过覆盖。

## 当前版本复跑

- RG01、RG02、RG07 的历史当前版本证据继续保留，未被本轮 LG 材料覆盖。
- 本轮新增的 LG01—LG16 通过材料使用直接 Graph lifecycle、重启后重新规划 baseline、真实 ACK proof 和当前版本测试设施。
- 后端 `pekko-server/Test/test` 本轮 511 项通过，0 失败，2 项取消。

## 当前实现修复

- 每个 materialized table graph 使用有界的 JDBC blocking executor，不占用 Pekko Streams dispatcher。
- STREAMING graph 保留所属 Journal 会话，直到 graph 停止或故障释放。
- Graph failure 会被 runtime 观察并进入已有失败分类，避免目标只提交首批后静默停滞。
- Journal receipt 的 GC proof projection 在 Reader ACK 更新 `observed_floor` 之后执行，避免慢表窗口的 floor 顺序竞态。

## Graph 重构边界

`BootstrapRunner` 直接实现表级 `TableGraphLifecycle` 和窄化的 `TableGraphGcPublisher` capability，`CdcBootstrapKernel` 不再创建中间适配器。快照阶段的 LOW/HIGH 状态机仍由 Runner 实现，Streaming 阶段由 materialized Graph 自己持有 Source、Sink、Journal 和 ACK 顺序。补充的 continuation 规则允许 Graph 推进同一 STREAMING 状态的 Journal，而不会把合法进度误判为篡改。这里完成的是 Graph 执行边界和恢复匹配规则解耦，保留旧 Runner 的持久化实现以兼容历史状态。

## LG 当前版本状态

当前版本在独立 MySQL、独立 Worker JVM、Actor Journal 和真实 MySQL Sink 上完成 LG01—LG16 共 17 个场景。逐项证据位于 `LG-current-20261123/`，每个目录包含 `acceptance.json`、状态快照、T/J/D 记录和逐字段对账材料。

- LG01—LG15、LG16：`status=passed`，`fullFieldA.equal=true`。
- LG16-capacity：`status=passed_safe_paused`，容量耗尽后 Reader 和表会安全暂停，`fullFieldA.equal=false` 是故障注入窗口的预期结果，证据包含暂停状态和保留上下文。
- LG08、LG10 明确验证慢表或保持 ACK 回调时 GC 返回 `ACTUAL_D_UNPROVEN`，释放后由有界重试推进 proof。
- LG15 使用第二个真实 MySQL 实例身份验证数据库身份拒绝，保留了原始失败材料和当前通过材料。
- 每个通过场景仍是单表主流程，LG10、LG11、LG12、LG15 额外覆盖动态表、取消、资源锁和身份边界。它们不能外推为 70 万物理表容量证明。

本轮没有把历史失败目录覆盖掉。`strict-final`、`verified3`、LG01/LG08/LG10 等中间失败目录继续保留，当前通过目录单独归档。
