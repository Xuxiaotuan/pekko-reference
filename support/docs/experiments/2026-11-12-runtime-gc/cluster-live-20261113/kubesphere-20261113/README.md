# KubeSphere 集群流水线与新镜像实证（2026-11-13）

这组材料验证的是“参考集群已有成功流水线后，使用 KubeSphere Pipeline 在集群内构建、推送并滚动部署 CDC Runtime”的实际结果。它与 GitHub Actions 的部署结果分开记录：GitHub Actions 的 verify/image 成功，但 deploy 因环境没有 `KUBE_CONFIG_DATA` 失败；本次 KubeSphere Pipeline 使用集群内 Jenkins/Kubernetes ServiceAccount 和 Harbor，真实完成了部署。

## 流水线结果

- Pipeline：`pekko-cdc-runtime`，namespace `bigdata-lab-devops845xv`
- PipelineRun：`pekko-cdc-runtime-n7kjw`
- 运行时间：2026-11-13 02:17:23Z 至 02:21:50Z
- PipelineRun phase：`Succeeded`，Jenkins result：`SUCCESS`
- 实际镜像：`100.97.53.78:5001/xxt/pekko-cdc-runtime:build-1-189ddce37c7d`
- 目标 Deployment：`bigdata-lab/pekko-cdc-runtime`
- 目标 Pod：`pekko-cdc-runtime-68b4c44bff-45fw6`，`1/1 Running`

原始对象和摘要见 `pipelinerun.yaml`、`deployment.yaml`、`api-status.txt`。Deployment 的 `last-applied-configuration` 仍包含 Kustomize 的 GHCR 模板，但运行中 `.spec.template.spec.containers[0].image` 已是上面的 Harbor 镜像；判断以实际 Deployment 字段为准。

## 重启与运行状态

通过真实 Deployment 滚动重启后：

- `/api/v1/cdc-status`：`enabled=true`、`ownerState=ACTIVE`、`activeSteps=0`、`reason=null`。
- Source MySQL：存在一个 Debezium Binlog Dump 会话，说明 Reader 已重新建立 binlog 会话；没有 InnoDB 未提交事务。
- State MySQL：当前 owner 为 `ACTIVE`，最近 grant 已 `RELEASED`，任务为 `STREAMING/HEALTHY`，`capture_readiness=ADMISSION_READY`。
- 首次启动时旧 owner 曾为 `UNKNOWN`。在重启前单独核对了源端连接、InnoDB 事务、活跃 grant、B recovery permit 和 versioned claim 均无未清理项，随后仅把该 owner 按现有 `endOwner` 的 clean predicate 置为 `STOPPED`，再执行滚动重启。这个人工恢复门槛是实验动作，不能解释成自动 fencing 已被证明。

## 全量逐字段对账

`source-state.txt` 与 `target-state.txt` 保存了同一采样点的所有字段：`id、label、amount、note、updated_at、revision`。`reconciliation.json` 的实际结果为：2 行、逐字段完全相等，状态 `passed`。

这证明本次新镜像重启后，当前 acceptance 表的 Source/Sink 内容一致；它不等于 70 万真实活跃表或长时间吞吐已验证。

## GC 结果与边界

`/api/v1/cdc-gc` 实际返回：

```json
{"items":[{"captureId":"primary","phase":"BLOCKED","reason":"requirement failed: ACK proof budget exceeded"}]}
```

因此本轮 GC 结论是：

- 部署、重启、owner 恢复和当前数据对账：`passed`。
- GC 自动重规划在“无收益小帧”场景下的代码路径已有本地回归和历史证据；本次线上运行遇到的是另一种 `ACK proof budget exceeded` 阻塞，当前实现不会把它自动改写成 `REPLANNING`，所以该边界为 `evidence_incomplete/blocked`。
- 原始 L、operation、ACK/Journal 证明未被删除；`next_seq=42`、`to_exclusive=44` 的 operation 仍处于 `DELETING`，安全暂停优先于猜测性删除。
- 未验证 ACK proof 超预算后的自动扩容、跨重启继续删除和 Sink 长时间稳定性；这些不能由 Pipeline 成功或两行对账推出。

全部原始文件的 SHA-256 在 `SHA256SUMS` 中。失败材料没有覆盖，旧 GitHub Actions deploy 失败仍按原证据保留。
