# 最终 acceptance 验收（2026-11-13）

本目录记录 `pekko-reference` 独立 CDC Runtime 在专用 K8s acceptance 环境的最终一轮证据。源码版本为工作区提交前的 `cluster-runtime-20261113k` 镜像；正式提交 SHA 以仓库提交记录为准。

## 通过项

- State MySQL 使用 NodePort `192.168.31.115:32373`，Journal、Debezium offset/history、控制表均落在同一个 `cdc_runtime`。
- 新入口启动后 owner 为 `ACTIVE`，GC namespace 为 `COMPLETE/OPEN`，运行 API `/api/v1/cdc-gc` 返回 `READY`。
- `cluster-final-proof` 全量初始化完成，任务证据中的 `B=binlog.000017:325156481`、`F=binlog.000017:325157844`，Journal revision 从 0 到 10。
- 实时事务包含更新、插入、删除；Source 与 `target_source_rows` 的 `id、label、amount、note、updated_at、revision` 逐字段对账相同，见 `row-reconciliation.txt`。
- GC proof 成功进入 `ACTIVE`，GC namespace 保持 `READY`；GC 执行完成前保留原始 L/ACK/Journal/T 证据。
- 由 PID 1 直接运行 JVM 后，缩容停止完成：owner 持久化为 `STOPPED`、active grant 为 0、源端 `pekko_cdc_runtime` 连接列表为空。随后重新启动，owner 再次获准，原任务恢复为 `STREAMING/HEALTHY`，Source/Target 仍逐字段一致。

## 需要保留的失败或边界

- 旧镜像的 shell launcher 不是 PID 1，曾留下 `ACTIVE` owner 和 Binlog Dump；该失败证据保存在上层 `retry-evidence-20261113.md`，最终部署改为直接执行 `pekko-reference -main ...`。
- 旧 State ClusterIP 连接 20/20 失败、NodePort 20/20 成功；保留在上层重试材料。
- `cdc_gc@%` 被 ledger guard 拒绝，后改为固定 `cdc_gc@192.168.31.115`；旧 namespace 摘要不能直接改写，因此最终轮重建专用 acceptance State 库。
- GC 重启后的删除计划因当前小批次帧的可节省空间小于 128 字节而 `BLOCKED`，原始 L 未删除；这是安全保守边界，不是把小收益强行标绿。
- 本轮没有验证 DDL、多 owner 自动接管、源侧 fencing v2、70 万张活跃物理表、跨实例共享 Reader，也没有把当前单表真实运行结果扩大为容量承诺。

`runtime.log` 保存最终运行日志，`state.sql.txt` 保存 owner、task、namespace、GC proof、L/Journal 的最终状态，`row-reconciliation.txt` 保存逐字段对账输出。
