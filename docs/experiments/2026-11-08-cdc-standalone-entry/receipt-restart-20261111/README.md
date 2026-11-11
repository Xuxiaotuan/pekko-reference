# Bootstrap Journal receipt 跨 JVM 探针

## 实际执行

使用专用 MySQL（server UUID `6e135a5a-dd76-11f1-9924-d74efd0f4f62`）、JDBC Actor Journal 和独立子 JVM。父进程写入 Journal，登记消费位置 1，确认 `journal_floor=0`，停止原 Actor；子 JVM 从同一 Journal 恢复 Actor 并重新获取 receipt。

子 JVM 中，根据真实 receipt 字段构造的副本被拒绝，floor 仍为 0；恢复后的 Actor 签发的 receipt 经同事务 Journal 校验后，floor 推进到 1。父 JVM 保持原 receipt 的强引用，且 Journal 未变化时，该旧对象仍被接受；这不是按 Actor 生命周期撤销的能力。之后追加 Journal 事件，旧 receipt 被拒绝，删除标志置位的最新事件也被拒绝；恢复删除标志后 floor 推进至 2。日志分别见 `receipt-restart-child.log` 和 `sbt-run-server.log`。

这次验证使用测试夹具把 discovery 状态直接设为 COMPLETE，不是命名空间出生证明。它不包含 Source、Sink、逐字段对账或真实 L 删除，也不证明 Java 反射、Unsafe、恶意字节码或特权数据库访问不能伪造能力。代码检索未发现生产删除 `pekko_capture_log` 的路径；归档与 floor 投影不能当成已实现安全 GC。

前两次运行未执行任何测试：`sbt-run.log` 使用错误的项目 ID；`sbt-run-corrected.log` 和 `sbt-run-integration.log` 因项目选择或默认排除外部测试而运行 0 项。最终 `sbt-run-server.log` 使用 `pekko-server` 项目并移除该次测试过滤，运行 1 项且通过。
