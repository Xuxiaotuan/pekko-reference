# 2026-11-23 当前版本 RG02/LG 矩阵基线

## 代码与发布基线

- Pekko 代码：`56fea1c`
- 网站：`8c563e3`
- 当前分支：`main`
- 当前实验使用 MySQL 8.0.36、Debezium、Actor Journal 和 MySQL Sink。

## 当前结果

| 项目 | 当前状态 | 证据 |
| --- | --- | --- |
| RG01 | passed | `support/docs/experiments/2026-11-23-rg02-lg-current/RG01-pass-56fea1c/` |
| RG02 | passed | `support/docs/experiments/2026-11-23-rg02-lg-current/RG02-pass-b9c1111/` |
| RG07 | passed | `support/docs/experiments/2026-11-23-rg02-lg-current/RG07-pass-56fea1c/` |
| LG01—LG16 | not_run_current_version | 当前版本尚未重新执行 |

RG02 首次在延长 Journal ask 预算后仍未收敛。进一步检查发现两处实现缺口：流式 Graph 的 JDBC 阻塞操作占用 Streams 运行线程，且 STREAMING 进入后提前释放了仍被 Graph 使用的 Journal。修复后，慢表目标锁窗口内 A 表继续追平，B 表在解锁后恢复，GC 受 pin 保护，重启后两表逐字段一致。

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

- RG01 在当前代码版本 `56fea1c` 通过，包含 GC DONE、正常重启和两表逐字段对账。
- RG02 在前一提交 `1a0a0e4` 通过，包含慢表锁窗口、GC partial/final、重启计划和两表逐字段对账。Graph capability 重构只改变 GC 接口边界，未改变 Reader、Sink、Journal 或 L 顺序。
- RG07 在当前代码版本 `56fea1c` 通过，异常重启保持安全暂停，随后恢复并完成两表逐字段对账。

## 当前实现修复

- 每个 materialized table graph 使用有界的 JDBC blocking executor，不占用 Pekko Streams dispatcher。
- STREAMING graph 保留所属 Journal 会话，直到 graph 停止或故障释放。
- Graph failure 会被 runtime 观察并进入已有失败分类，避免目标只提交首批后静默停滞。
- Journal receipt 的 GC proof projection 在 Reader ACK 更新 `observed_floor` 之后执行，避免慢表窗口的 floor 顺序竞态。

## Graph 重构边界

`TableCdcGraphRuntime` 已移除对 `BootstrapRunner` 的公开委托。运行时只依赖表级生命周期和窄化的 `TableGraphGcPublisher` capability，快照阶段仍通过兼容适配器复用旧 Runner，Streaming 阶段由 materialized Graph 自己持有 Source、Sink、Journal 和 ACK 顺序。这完成了执行边界解耦，没有宣称 LOW/HIGH 快照算法已经重写。

## LG 当前版本状态

LG01—LG16 的当前版本入口和逐项证据尚未完成。本 README 不把历史 `strict-final`、`verified3` 或组件测试写成当前版本通过。L floor 完整矩阵仍为 `not_run_current_version`。
