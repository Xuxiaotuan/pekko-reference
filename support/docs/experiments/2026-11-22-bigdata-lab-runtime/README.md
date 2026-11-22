# bigdata-lab Capture/Worker 多 JVM 运行核对

本轮通过独立 supervisor 主机访问 `bigdata-lab`，核对真实 Kubernetes 工作负载、Capture/Worker 多副本和运行状态。当前部署是一个 `pekko-cdc-capture` 副本和两个 `pekko-cdc-worker` 副本，使用真实 MySQL Source 和 State MySQL。

## 实际观察

- Capture Deployment 为 `1/1`。
- Worker Deployment 为 `2/2`。
- 两个 Worker 的 `/api/v1/cdc-status` 均可访问。
- 一个 Worker 获得持久 owner，另一个 Worker 在 owner 为 ACTIVE 时持续记录 `owner admission rejected`。
- 当前集群 ConfigMap 明确配置 `source-fence-v2=false`。

这证明了当前两 Worker 的 owner admission 隔离和持久 owner 互斥。它没有证明 source fence v2 的 observer/controller 账号、guardian 自动接管或旧 owner 事务排空。

## 未执行的破坏性动作

本轮没有删除、重启或强制终止 `bigdata-lab` 的现有 Capture/Worker，也没有修改生产 ConfigMap、Secret 或 source-fence 配置。原因是当前部署没有独立 source-fence v2 测试账号和隔离的多 owner acceptance namespace，直接改生产配置会把观察窗口和现有 CDC 任务混在一起。

## 证据文件

- `workload.txt`：Deployment、Service、Pod 和节点信息。
- `worker-status.txt`：两个 Worker 的运行状态。
- `owner-admission.log`：真实 owner grant/reject 日志。
