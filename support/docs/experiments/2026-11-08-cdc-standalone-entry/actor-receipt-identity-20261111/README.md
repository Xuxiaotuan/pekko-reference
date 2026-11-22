# Actor receipt 身份校验（2026-11-11）

此前发现 Scala 限定访问的 receipt 构造器在 JVM 中为 public，直接 Java 代码可构造对象，见相邻 `jvm-receipt-boundary-20261111`。此版在 Workflow checkpoint、Bootstrap progress 和恢复许可 receipt 增加 Actor 对象签发身份登记；验证器先检查登记，再在相同 RR 事务内验证数据库身份、最新 Journal 事件和后继范围锁。登记使用弱引用，未被引用的 receipt 不因登记永久保留，但 GC 时间不确定，因此不是硬内存上限。

`mysql-floors-and-initial-fence-failure.log`：隔离 MySQL 9.5.0，Java 测试代码把真实 receipt 所有字段复制到新对象后，Workflow 和 Bootstrap floor 证明均拒绝该对象；原 Actor 对象通过，错误身份、过期/删除事件拒绝和后继写入阻塞通过。第三项恢复许可测试最初因新 SBT 测试类加载器未注册 JDBC 驱动而失败，未进入协议验证；补上显式加载后 `mysql-fence-rerun.log` 通过，Java 复制的恢复许可 receipt 被拒绝，真实 receipt 核验通过；另有两个 JVM 分别验证 Journal 和 SDK offset 范围锁超时（`contender-jvm.log`、`offset-contender-jvm.log`）。

`jvm-access.txt` 是 `javap -p` 结果：签发函数和弱引用登记字段为 JVM private；正常 Java 直接调用只读判断不能添加对象。`jvm-synthetic-entrypoints.txt` 同时记录 Scala 生成的 public synthetic 方法；没有证明它们在任意自定义字节码、反射或 Unsafe 下不可被滥用。本方案的证据边界是非反射的普通 Java 直接构造与当前测试路径，不承诺隔离能在同一 JVM 任意执行代码、访问管理凭据或修改字节码的攻击者。

`C0-74fc110893fd` 是独立 guardian、supervisor、CDC worker 与双 MySQL 的实时阶段强制退出实验：替代 worker 应用后续源变更，全量 64 行、384 字段一致，T 1、J 21、L 27，清理成功。它不覆盖未知 B；未知 B 继续安全暂停。receipt 对象为进程内临时能力，跨 JVM 重启不保留；旧 Workflow 重启后重复 checkpoint 可能无法立即重新签发，是否造成保守 GC 滞留尚未实测。所有工作区关键源码指纹在 `source-sha256.txt`，C0 子目录另有完整源码清单。实验结束后两个专用 MySQL 已关闭。
