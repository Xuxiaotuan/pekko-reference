# 2026-11-23 当前版本 RG02/LG 矩阵基线

## 代码与发布基线

- Pekko 代码：`ef56548`
- 网站：`747d9ed`
- 当前分支：`main`
- 当前实验使用 MySQL 8.0.36、Debezium、Actor Journal 和 MySQL Sink。

## 当前结果

| 项目 | 当前状态 | 证据 |
| --- | --- | --- |
| RG01 | passed | `support/docs/experiments/2026-11-22-graph-fence-v2/l-floor-followup-20261122/RG01-pass-20261123/` |
| RG02 | failed | `support/docs/experiments/2026-11-22-graph-fence-v2/l-floor-followup-20261122/RG02-failure-20261123-retry7/` |
| RG07 | passed | `support/docs/experiments/2026-11-22-graph-fence-v2/l-floor-followup-20261122/RG07-pass-20261123/` |
| LG01—LG16 | not_run_current_version | 当前版本尚未重新执行 |

RG02 retry7 将验收 worker 的 `snapshot-steps` 调整为 2。慢表目标锁窗口仍出现 Journal ask 超时，随后任务进入 `local_release_unconfirmed` 安全暂停，源表与目标表逐字段对账未收敛。

## 实验环境

- 临时资源定义：`/private/tmp/v2-mysql-k8s.yaml`
- Source 本地端口：`25821`
- State 本地端口：`25832`
- 每次实验结束后删除 `cdc-runtime-acceptance` 下的临时 Deployment、Service、Secret，并关闭端口转发。

## 证据边界

- 历史 `strict-final` 和 `final/verified3` 材料只能说明历史版本结果。
- 两表实验不能证明 70 万物理表容量。
- RG02 在 Journal ask 超时根因修复前不能标记为通过。
