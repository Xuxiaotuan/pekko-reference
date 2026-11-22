# Journal 投影接口与旧 Actor receipt 类型分离

生产 `CdcJournalProgressVerifier.verifyOn` 和 `MySQLCaptureStore.projectWorkflowVerified` 改为接收 CDC 的 `JournalCheckpointReceipt`。这两个接口不再引用旧 Actor 或其嵌套 receipt 类型。历史调用通过 `LegacyJournalReceiptAdapter.fromActor` 转换；该适配器先校验旧 Actor 的实际签发登记，随后登记独立 CDC 句柄的进程内身份。适配器是刻意保留的兼容依赖，并不意味着旧 Actor 实现已经删除。

CDC 句柄的 JVM 构造器仍可被 Java 调用；仅复制其字段不能取得适配器私有登记。因此没有把 Scala 构造器可见性当成安全保证。最终 verifier 仍在 Store 更新保留位置的同一事务中核验数据库身份、精确 Journal 事件、最新序列、删除标记和 checkpoint 内容；没有将同事务核验替换成转换时的一次检查。

## 真实结果

专用 MySQL 25822，JDBC Actor Journal；`mysql-run.log` 中 3 项测试通过：

- 保留的旧 MySQL Journal 字节可重放。
- Java 构造的旧 receipt 在兼容转换时被拒绝，Java 构造的 CDC 句柄在 verifier 中被拒绝；真实签发句柄通过。旧事件、错误身份和已删除事件被拒绝；范围锁使后续 Journal 写入等待。
- Bootstrap Journal 在独立 JVM 恢复并重新签发 receipt 后完成投影，伪造副本仍被拒绝；子进程输出见 `receipt-restart-child.log`。

该实验不包含 Source 到 Sink 的全量逐字段对账或 L 的实际删除，也不证明 guardian 自身重启或未知 B 自动恢复。进程内登记不是密码学签名，不涵盖反射、Unsafe、恶意字节码或特权数据库篡改。未知 B 仍保持安全暂停。旧执行器和兼容适配器仍需进一步迁出；本目录不代表完整交付。
