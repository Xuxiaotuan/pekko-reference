# L floor 两表 GC 复跑材料

日期：2026-11-22

本轮使用隔离的两台 MySQL 8.0.36，创建 `a`、`b` 两张源表和对应目标表，目标是复跑 Runtime GC 的启用重启窗口。

## 结果

本轮没有形成通过证据。第一次失败是 GC worker 账号没有包含新建的 `pekko_capture_epochs_v1` 写权限，测试设施已补齐该授权。第二次复跑已进入真实 Debezium streaming，但 `a` 的 Graph 在 180 秒窗口内没有进入 `STREAMING`，因此没有执行删除或恢复断言。

保留材料：

- `RG01-old-runtime.log` 保存真实 MySQL、Debezium 和 Worker 日志。
- `/private/tmp/pekko-cdc-gc-20261122/retry/RG01-enable-restart-84e6d2f892d0/failure.json` 记录第二次运行的 `streaming a` 超时。

当前结论是 `evidence_incomplete`。不能把组件级 L floor 证明升级成跨进程 GC 通过，也不能把这次两表运行写成已完成。

## 2026-11-22 最终复跑补充

在修正合并入口的 ready、worker lease 心跳、停止 grant 竞态和 GC 探针授权后，RG01 仍未形成通过证据。

- `RG01-gc-ready-no-delete`：GC namespace 已达到 `COMPLETE`，GC 状态为 `READY`，但在任务正常重启后的窗口内没有产生可删除的 GROUP operation，测试超时。材料保留了任务、L、D、pins 和 GC 状态。
- `RG01-final-http-timeout`：继续修正 planned-stop 后重启路径后，真实 MySQL + Debezium 子进程在启动阶段 HTTP 查询超时，未形成业务对账结果。

这两次材料都属于 `evidence_incomplete`。它们证明探针没有把 GC 失败伪装成成功，也不能证明 L floor 安全 GC 已完成。当前可确认的是 namespace 账号边界和主流程资源释放修复，GROUP 删除和跨重启 GC 仍待单独定位。

## 2026-11-23 再复跑

- `RG01-pass-20261123`：RG01 已通过。GC namespace 为 `COMPLETE`，GC operation 为 `DONE`，audit 为 `ACTIVE`，`to_exclusive > from_seq`，任务重启后恢复到 `STREAMING`，源/目标逐字段对账通过。
- `RG02-failure-20261123`：慢表窗口未通过。GC 进入 `actual D proof pending`，两个任务随后出现 `local_release_unconfirmed`，材料保留了 pins、checkpoint、任务和 GC 状态。
- `RG07-pass-20261123`：RG07 独立异常重启窗口通过，owner 未被猜测为已释放，重启后任务恢复并完成对账。

本轮同时发现并修复了 GC ledger trigger 的 definer 绑定问题。RG02 的慢表窗口仍是未通过边界。
