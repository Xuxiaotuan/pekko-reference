# JVM receipt 与 Journal verifier 可信边界（2026-11-11）

本实验纠正此前将 Scala 访问限定误称为 JVM 防伪边界的表述。`before-javap.txt` 是改动前字节码：旧 Workflow `PersistedCheckpoint` 和 Bootstrap `PersistedProgress` 的构造器均为 JVM `public`；`ReceiptConstruction.java` 从外部 Java 类直接构造了 Workflow receipt，`java-construction.txt` 记录执行输出。该例传入空 checkpoint，只证明对象可在 Actor 外构造，没有证明它能通过 Journal 或 GC 校验。Bootstrap receipt 的可构造性目前只有字节码证据，没有单独运行 Java 构造实验。

改动前旧 Workflow 的 `CdcJournalProgressVerifier` 是 JVM 接口，可由其他 JVM 代码提供实现；若可信边界涵盖不受信任的同 JVM 代码，潜在绕过需要进一步以真实数据库验证。现已把它改为 `final` 类，正常路径仍在 caller 的 RR 事务核验数据库身份、Journal 最新事件及锁；`after-javap.txt` 和 `NoOpVerifier.java` / `noop-final.txt` 记录 Java 无法继承该最终类。JVM 构造器、依赖注入及同进程恶意代码的完整威胁模型尚未证明安全，receipt 本身仍可被 Java 构造，旧 Actor 类型耦合也仍未解决。

`mysql-journal.log` 记录隔离 MySQL 9.5.0 上两项实验：四条历史事件重放 checkpoint，以及当前 Actor receipt 的错误身份、过期/删除事件拒绝和后继写入范围锁。`journal-rows.tsv` 保留 Journal 原始字节；`source-sha256.txt` 为未提交源码指纹。上述结果不能证明 JVM receipt 不可伪造、旧库整体升级或跨 JVM CDC 接管。

此前 `journal-proof-separation-20261111`、`workflow-event-migration-20261111` 等材料中“只有 Actor 构造”等措辞只能作为 Scala 源码正常路径描述，不是 JVM 层访问控制证明；历史文件未删除。
