# Actor 执行模型的本地机制实验

服务于[第一篇重写稿](../../cdc-design/01-why-pekko-for-resident-cdc.md)。日期：2026-10-18。证据层级：**本地真实 Pekko 运行 + 项目 Bridge 组件实验 + 配置解析**。状态：本次运行全部机制断言通过。70 万表容量、集群恢复和 CDC 端到端结果均为 `evidence_incomplete`。

## 固定对象与运行方式

Pekko Actor / Typed / Sharding / Stream 等核心依赖为 1.1.3；Scala 2.13.12；Microsoft OpenJDK 17.0.14；macOS。本次具体平台、依赖文件名、项目 HEAD、源码与配置哈希见 [environment.json](environment.json)。上游固定源码提交、URL 和内容哈希见 [source-manifest.json](source-manifest.json)。

探针：[ActorExecutionProbe.java](ActorExecutionProbe.java)。运行器：[run.py](run.py)。结果：[result.txt](result.txt)。

从仓库根目录执行：

```sh
python3 docs/experiments/2026-10-18-actor-execution/run.py
```

运行器读取本地 SBT `compile/dependencyClasspath` 导出，使用已有依赖，不启动 SBT 或下载新依赖。需要先有该导出和 Scala 2.13.12 编译器缓存；不存在时应先用仓库构建流程准备。JDK 默认指向本次机器上的 JDK 17，可用 `PEKKO_PROBE_JAVA_HOME` 指定另一套 JDK 17。脚本当前的 Coursier 缓存查找路径针对这台 macOS 开发机，不是跨平台安装器。

`DebeziumBatchBridge.scala` 与 `CborSerializable.scala` 从当前生产源码重新编译到临时目录，没有复用旧 Bridge class，也没有修改它们。后者是项目 `reference.conf` 中 Jackson 绑定必需的标记接口。临时 class 在运行后自动清理。重跑会覆盖本目录 result/environment 两份输出。

配置解析读取项目三套配置及 reference fallback；实际 ActorSystem 强制 `provider=local`、禁止退出 JVM，使用两个自定义/指定执行池，不加入集群。它不创建 EventSourced Actor，不打开 Journal、数据库连接或 Debezium Engine。

## 实验 A：共享执行与串行处理

固定条件：10,000 个普通 Typed Actor，专用 Dispatcher 固定两线程、throughput=5；单个测试线程按轮向每个 Actor 发送 0、1、2、3；所有 Actor 保持存活。

每次处理使用原子计数器检查同一 Actor 是否重叠，检查每个 Actor 自己的预期顺序，记录线程名和最大并发。等待 40,000 条消息全部完成后，再观察无新消息的 300 ms。

实测：线程名集合大小 2，最大处理并发 2，重叠 0，顺序错误 0，空闲观察窗口新增处理 0。最后一项不是 CPU profiler 测量。有限样本不能替代 Mailbox 不变量的源码论证。

实验 A 的阻塞对照：两个 Actor 的处理函数各自等待门闩，确认二者均已进入后，向第三个 Actor 发消息。300 ms 内第三个没有执行，释放门闩后完成。探针中的 10 秒等待上限只是异常退出保护；正常观测在释放门闩前完成，不用它来触发结果。

## 实验 B：实际 Bridge 占满 JDBC Dispatcher

固定条件：从基础配置取得真实 Pekko `pekko.workflow.jdbc-dispatcher`，固定 8 条线程；每条提交一个空的真实 Bridge 的 `take()`。

先等待 8 个调用开始，再检查线程栈，直到 8 条线程都包含 `java.util.concurrent.ArrayBlockingQueue.take`。这个栈条件才是故障窗口的门槛，不能仅凭任务已提交就宣布线程池被占满。

之后向同一池提交 sentinel：300 ms 内未执行。默认 Dispatcher 上的控制 Actor 在这个窗口内可响应，排除了整个 ActorSystem 不工作的解释。测试主线程随后直接关闭全部 Bridge：8 个读取均退出，sentinel 执行。

这是有可解除条件的线程池饥饿复现。sentinel 代表同池后续工作，**不是 JDBC 提交或 Source ACK 的替代实现**。没有执行真实 MySQL/Stream；没有验证实际取消路径，因为本次 close 由测试主线程直接调用，而生产 Source 的 close 本身通过同一阻塞池提交。

## 实验 C：有效钝化策略

分别解析 application.conf、application-dev.conf、application-prod.conf，构造 1.1.3 的 classic ClusterShardingSettings。Typed settings 的构造也从该 classic 配置构造路径转换；本探针读取内部 `passivationStrategy` 仅用于版本验证，不建议生产代码依赖该内部方法。

三套配置的 rememberEntities 都为 true，有效策略都是 NoPassivationStrategy。仅通过 settings.withRememberEntities(false) 改一个条件后，分别得到 30 分钟、5 分钟、1 小时的 IdlePassivationStrategy。JDBC 池分别为 8、4、16；实体恢复策略均为 all。

这验证了版本对应的配置选择，未验证线上进程使用哪套配置，也未运行真实钝化、rebalance 或实体恢复。

## 装配问题与证据边界

探针搭建阶段先遇到 shell 的 JAVA_HOME 指向旧 JDK，不能编译 Java text block；固定 JDK 17 后解决。随后 ActorSystem 因缺少 CborSerializable 无法启动：仅缩小 application fallback 没能消除问题，检查确认项目 reference.conf 也声明该绑定；加入真实标记接口的编译后解决。以上是独立探针装配错误，未计作产品故障或实验成功。原始结果文件保存的是完成上述修正后的成功运行。

本轮未做内存斜率、吞吐极限、P99、70 万实体启动、Journal 恢复风暴、Sharding 迁移、真实 MySQL 冷流饥饿或 Flink 对照测试。实验不能支持相应容量或端到端结论。生产代码未修改。

已有 MySQL 与 Actor Journal 故障结果保留在[独立报告](../2026-10-18-cdc-journal-window.md)，没有在本实验中重跑，也不用于推导 Actor 容量。
