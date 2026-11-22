# L floor 两表 GC 复跑材料

日期：2026-11-22

本轮使用隔离的两台 MySQL 8.0.36，创建 `a`、`b` 两张源表和对应目标表，目标是复跑 Runtime GC 的启用重启窗口。

## 结果

本轮没有形成通过证据。第一次失败是 GC worker 账号没有包含新建的 `pekko_capture_epochs_v1` 写权限，测试设施已补齐该授权。第二次复跑已进入真实 Debezium streaming，但 `a` 的 Graph 在 180 秒窗口内没有进入 `STREAMING`，因此没有执行删除或恢复断言。

保留材料：

- `RG01-old-runtime.log` 保存真实 MySQL、Debezium 和 Worker 日志。
- `/private/tmp/pekko-cdc-gc-20261122/retry/RG01-enable-restart-84e6d2f892d0/failure.json` 记录第二次运行的 `streaming a` 超时。

当前结论是 `evidence_incomplete`。不能把组件级 L floor 证明升级成跨进程 GC 通过，也不能把这次两表运行写成已完成。
