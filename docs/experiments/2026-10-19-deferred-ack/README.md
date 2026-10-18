# 回调返回后延迟确认：真实 MySQL、完整主键组与 Bridge 探针

日期：2026-10-19（Asia/Shanghai）。本次只增加测试设施，验证上轮方案的第一个运行门槛。生产 Source、Bridge、Actor、Sink 和恢复协议均没有修改。

**实际结果：**在 Debezium 3.6.1.Final、ORDERED、一个处理线程的固定条件下，回调 A 返回后，保留的提交句柄能在后续回调中串行调用。真实 `[d] [c]` 以及连续跨界的 `[u,d] [c,d] [c,d] [c,u]` 均完成装配、确认和后续读取；没有观察到该受控路径的循环等待。确认前的实际 D 保持基线，确认后的实际 D 与允许完成的原始回调前缀一致。

**证据层级：真实 Engine + 原始 SourceRecord + 未修改的生产 Bridge + 测试装配器/覆盖控制器 + 真实 JDBC offset/history。** 覆盖确认由测试命令 `CONFIRM` 触发，代替尚未实现的 T/J 覆盖条件；没有运行目标写入、Actor Journal、新组账本、完整生产 Source Stream 或恢复协议。不能把本结果写成 T/J/D 新协议已通过。

## 1. 本次验证的问题

[身份实验](../2026-10-19-source-record-identity/README.md)已确认，主键修改的 DELETE/CREATE 共享 source 物理行坐标和完整 restart offset。仅确认 DELETE 后从 D 恢复，CREATE 不再交付。

因此，逻辑 eventId 加 op 只能区分成员，不能单独约束源恢复点。另一方面，生产 Bridge.publish 等待确认；若把半组 DELETE 交给它，再等待下一回调中的 CREATE，会阻塞配对。

本探针采用测试版装配器：先装配完整组再发布到真实 Bridge。回调尾部只有 DELETE 时暂存半组并返回，不调用原始句柄。完整组得到测试覆盖信号后，只按原始回调顺序确认已经全部覆盖的回调。

这里的 `groupId` 用固定实例的 db/table/file/source.pos/source.row 元组标识；没有实现生产 namespace、generation、日志谱系校验或通用身份规范。

## 2. 固定对象和原始材料

| 项目 | 本次实际值 |
|---|---|
| 项目 HEAD | `436056d8608e0d2d27ddc24988005cf70a42ff4d` |
| 生产差异 | `pekko-server/src/main`、`build.sbt` 相对 HEAD 为空 |
| MySQL | 8.4.11，ROW / FULL / GTID ON，InnoDB |
| 镜像 | `sha256:cc52b8b5900d106bc482241091cfa7496d830280868e19aca8a24e8e7086568e` |
| Debezium / JVM | 3.6.1.Final / Microsoft OpenJDK 17.0.14，UTC |
| 正式运行 | `42e056f826b1`；自己创建的隔离容器，两组各三 JVM，共六 JVM |
| 组件 | 生产 connectorProperties、RealDebeziumEngineAccess、DebeziumBatchBridge |
| 测试替代 | 内存装配、按键配对、覆盖信号，不含任何 T/J 提交 |

[探针源码](../../../pekko-server/src/test/scala/cn/xuyinyin/magic/workflow/nodes/sources/RealMySQLDeferredAckProbe.scala)、[驱动 run.py](run.py)、[只读核验 verify.py](verify.py)、[结果摘要](result.json)、[独立核验输出](verification.json)、[证据包](evidence.tar.gz)、[证据包哈希](evidence.tar.gz.sha256)、[本目录文件哈希](SHA256SUMS)。

每个原始回调在 `callbacks.jsonl` 保存完整 key/value schema、source、sourcePartition、sourceOffset、headers 和生产规范化 Envelope。`trace.jsonl` 保存顺序编号、PID、线程名、回调进入/返回、配对、Bridge 发布/就绪、覆盖信号、原始句柄调用开始/返回和闸门。

`CALLBACK_RETURNING` 在方法退出前记录；后续回调已经进入，配合并发进入保护和顺序 trace，证明前一回调不再阻塞后续交付。不能只凭一条“准备返回”的日志宣布 SDK 已完成后续调用。

## 3. 控制窗口怎样建立

每组先快照 8 行，再提交 streaming marker，等待真实 D 等于 marker 的完整 offset。之后 ARM，执行固定事务。

第一 JVM 在主键组已经配齐、Bridge 已交付、但测试覆盖尚未确认时终止（halt 86）。链式组在此之前只确认普通前缀，原始回调仍因尾部 DELETE 没有覆盖而不能确认。独立读取 D，验证崩溃前后均等于基线。

第二 JVM 使用相同 connector 和 JDBC 状态恢复。再次到达同一覆盖前窗口，D 仍保持基线。随后 CONFIRM 完整组；原始 A 句柄在当前 B 回调中调用。句柄返回后设置闸门，B 尚未确认；控制端查询真实 D。放开闸门后继续配对和确认，检查最终 D。

第三 JVM 从确认后的 D 启动，再向源提交一条全新的普通更新。首次交付只有该新事件，继续确认后 D 再次前进。这个用例检验读取路径能继续工作，不是目标数据对账。

没有手工编辑、导入或回写 offset 表；没有根据目标状态伪造源位置；没有在探针中拆分或合并 SDK 原始回调。原始形状由 max.batch.size=1/2 和实际记录共同确认，不从配置值推定。

## 4. 两组实际结果

| 场景 | 第二 JVM 原始回调 | 完整交付 | 原始 ACK 顺序 | 最大待确认原始回调 |
|---|---|---|---|---:|
| split-pair | `[d1] [c1]` | `[g1]` | 0 → 1 | 2 |
| chained-pairs | `[u0,d1] [c1,d2] [c2,d3] [c3,u4]` | `[u0] [g1] [g2] [g3,u4]` | 0 → 1 → 2 → 3 | 2 |

两组都完成覆盖前崩溃/恢复、延迟旧句柄调用、全部原始回调确认、确认后新 JVM 的新事件读取。第二组从 B 中确认 A、从 C 中确认 B，最后从 D 中确认 C/D。全程原始句柄调用发生在有序回调路径中；测试控制线程只调用 Bridge 的覆盖信号句柄，没有直接调用真实 committer。

所有 RAW_ACK_BEGIN 前，核验器都从原始回调重新计算它涉及的 groupId，检查对应组已完整交付并得到覆盖信号，且确认没有越过更早回调。主键组验证反向 key headers 和共享 offset。

### 实际 D 的关键切点

分组前缀不能用 pos 的最大值表示。链式组的实际快照为：

| 切点 | restart pos | event | row |
|---|---:|---:|---:|
| ARM 前基线 | 57357 | 2 | 1 |
| 配齐 g1、覆盖尚未确认 | 57357 | 2 | 1 |
| g1 覆盖后，A 已确认、B 未确认 | 58722 | 4 | 1 |
| 全部四个回调确认后 | 58722 | 10 | 1 |

中间切点已经允许确认 A 中的 DELETE，因为对应 CREATE 也在测试语义下获得了完整组覆盖；并不要求 CREATE 所属的原始 B 句柄也先执行。此时 B 尾部的下一组 DELETE 尚未覆盖，所以 B 的句柄不能执行，D 没有越过该缺口。

这区别于身份实验的反例：那里只有 DELETE 被确认，而 CREATE 没有任何完整组覆盖。两个实验不能用“都是确认了 DELETE”混为一谈。

split-pair 中，A 与 B 的末尾 offset 相同；中间和最终 D 因而相同。这里配对及覆盖已完成，不是用“D 相同”证明 CREATE 被处理；完整性来自原始记录、分组内容和覆盖先于 ACK 的顺序证据。

## 5. 能支持什么，不能支持什么

**本次实测支持：**固定版本下，按序延迟调用已返回回调的句柄可运行；测试版分组装配能让跨回调成员抵达；覆盖信号约束下的实际 D 没有提前越过未完成组；两个有限场景完成后仍能读取新变更。

**源码约束：**Debezium 3.6.1 的 committer 不是线程安全的；应用需要串行化其调用。固定源码见[上次源码清单](../2026-10-18-series-review/source-manifest.json)。本探针只走有序 callback 调用句柄，没有验证任意线程、任意时刻和关闭后的调用合法性。

**设计提案：**生产 Source 前置完整组装配；目标以完整组原子写入 DML 和证据；J 接受覆盖凭据后，按原始回调前缀确认。这个闭环尚未实施。

**evidence_incomplete：**真实 T/J 覆盖、组账本去重、目标原子性及 COMMIT 回复丢失、J/D 故障、新协议全量逐字段对账、关闭与迟到 ACK 竞争、身份冲突、超大记录、缺失配对、字节级内存预算、长期运行、公平性、多 owner、DDL、快照中断和 70 万表容量。

测试装配器使用小样本内存集合及手动闸门。待确认原始回调不超过 2 的断言通过，不表示所有缓存都有生产可用的清理及字节上限。普通 Bridge 读取仍可能阻塞共享池，第一篇的资源限制也没有被本实验修复。

## 6. 复跑与只读复查

先准备 JDK 17、SBT 依赖及 Docker 中的 mysql:8.4，从仓库根执行：

```sh
sbt -java-home /path/to/jdk17 -J-Xmx3G \
  'project pekko-server' 'Test / compile' 'export Test / fullClasspath'
ACK_PROBE_JAVA_HOME=/path/to/jdk17 \
  python3 docs/experiments/2026-10-19-deferred-ack/run.py
```

驱动仅访问自己创建的 loopback 容器和唯一数据库；结束后停止容器并保留数据。示例密码仅属于隔离实例。默认 JDK 路径是本机路径，不是通用安装配置。

从证据包解压到一个新的空目录后：

```sh
python3 docs/experiments/2026-10-19-deferred-ack/verify.py \
  /path/to/extracted/42e056f826b1
```

核验器不启动数据库，只检查保存材料中的原始回调、成员配对、覆盖与 ACK 顺序、进程身份、独立 D 快照和后续新事件。它不是第二次独立运行。

本次实际完成 Test/compile、两组真实 Engine/Bridge 场景、六 JVM、原始轨迹独立复算；没有重跑此前 MySQL→MySQL / Actor Journal 故障实验或整个项目套件。本轮没有发生探针失败后重试；身份实验此前的容器就绪失败仍保留在独立归档，不重复计数。
