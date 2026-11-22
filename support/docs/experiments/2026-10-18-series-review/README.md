# 系列重写：历史证据复算、有限模型与源码版本

本目录支持第二至第五篇重写。新增检查发生于 2026-10-18；生产 CDC 协议没有修改。本目录不是新的真实数据库实验结果。

## 1. 历史证据复算

[verify_evidence.py](verify_evidence.py)直接读取已有 tar.gz，不解压写回旧证据，不连接数据库。它验证压缩包哈希和内部清单的 54 个文件哈希，再从两组最终完整 source/target 行数据重新计算缺失键、多余键、全部共同主键字段差异。

另外检查不同恢复 PID、同 batchId、恢复首事件等于原首事件、切点 T 比 J 多一批、崩溃后 D 不变、成功组审计不重复和最终 D/J 一致、失败组状态与审计和 D 不变。

结果保存在 [evidence-review.json](evidence-review.json)：成功组 64/64 行、零差异；失败组 64/64 行、缺 65、多 64，48 个共同主键有 216 处字段差异。归档内测试源文件另有哈希，本文提交前核对其与当前测试设施一致。

这是**历史文件的独立复算**。它没有再次执行原 Scala 测试，也没有补测任何原来未覆盖的窗口。

## 2. 重分批有限模型

[protocol_model.py](protocol_model.py)与[结果](protocol-model-result.json)描述一个抽象目标事件账本。六个事件的 32 种原分批、原提交边界、所有允许的源恢复前缀以及恢复后分批，共组成 7,696 个组合。每个组合检查最终状态与每个事件审计恰好一次。

该模型假设稳定唯一身份由外部正确提供、目标事务原子、只有一个有序 writer、证据保留、恢复不跳过未提交前缀。它不从 SourceRecord 推导 ID，不模拟 Actor、Journal、offset store、DDL、快照、多 owner 或 MySQL 锁。

模型还断言三个反例：旧 after-image 可回退新值；最大完成索引可跳过缺口；提前删除去重证据可重新执行旧事件。相同身份不同 payload 必须拒绝。

因此：`all_schedules_pass=true` 只表示**这个有限模型内的检查成立**，不表示生产新协议已经实现，也不是形式化证明器的无界证明。

复算与模型检查可从仓库根目录执行：

```sh
python3 docs/experiments/2026-10-18-series-review/verify_evidence.py
python3 docs/experiments/2026-10-18-series-review/protocol_model.py
```

两条命令只使用 Python 标准库；重跑会覆盖各自结果 JSON。归档证据不变。

## 3. 上游源码

[source-manifest.json](source-manifest.json)记录本次新增精读的六个上游文件及 SHA-256：

- Flink CDC 3.6.0：`bf160337ff558d70302e7884f68937611873117e`。SnapshotSplitAssigner、HybridSplitAssigner、SourceReader、BinlogSplitReader。
- Debezium 3.6.1.Final：`63371830ceff75437f95c775b91eeeaf55a056c1`。BinlogOffsetContext、AsyncEmbeddedEngine。

提交由官方 Git tag 解析，下载使用固定提交 URL。相关内容为源码分析，不代表本轮运行过对应 Flink 作业。Pekko 1.1.3 源码清单沿用[第一篇材料](../2026-10-18-actor-execution/source-manifest.json)。

本轮还读取项目实际 Debezium embedded 依赖 JAR 的 `META-INF/services/io.debezium.engine.DebeziumEngine$BuilderFactory`，provider 为 `io.debezium.embedded.async.ConvertingAsyncEngineBuilderFactory`，用于确认第二篇分析的 Async Engine 路径。详情见 [runtime-provider.json](runtime-provider.json)。

## 4. 未新增的证据

没有重跑 MySQL 故障实验，没有执行 J 已持久化/D 滞后、offset flush 故障、COMMIT 回复丢失、Sharding 接管、DDL、70 万表或 Flink/Pekko 同负载性能对照。第一篇的实际 Actor/Bridge 实验保留在[独立目录](../2026-10-18-actor-execution/README.md)。
