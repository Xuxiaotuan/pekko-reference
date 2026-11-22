# LOW/HIGH 全量初始化、窗口回填与持续回放实验

**整体验收：evidence_incomplete。第二阶段内核已实现，新矩阵通过；旧 R02 启动恢复失败保留，未改原恢复协议。**

| 证据层级 | 本轮实际结果 |
|---|---|
| 真实 MySQL + Actor JDBC Journal + Sink 新矩阵 | I01—I24 展开 61 项，61 通过 |
| 独立重算 | 61 项全部复核通过；17,874 个必需相等字段，另有两组新 generation 全量对账 |
| 相关协议/真实 MySQL 单测/旧持久化兼容 | 80 通过，与新矩阵、旧回归的完整源树摘要相同 |
| 后端非外部测试 | 593 通过；排除 ExternalIntegration tag 和已知旧 ActorSpec；916 个打包主类与最终矩阵运行类逐字节相同 |
| 旧真实回归 | 58 项中 57 通过、1 失败：R02 |
| 资源复核 | 无遗留 worker；两个专用实例的实验连接、未结束事务均为 0 |

[版本与逐组运行结果](final-version.json) · [独立对账与摘要复核](final-summary.json) · [最终资源状态](final-resources.json) · [R02 时间线](r02-startup-boundary.json) · [归档索引](artifacts/archive-index.json) · [SHA-256](artifacts/SHA256SUMS) · [归档逐项校验](artifact-verification.json)


源码基线为 `00ad18e624568ef595bd90e2d6ac00008bd235a0` 加本轮未提交修改。最终冻结源树（含测试和资源）的 manifest SHA-256：`c71f260870325aa54bb6818200a875689afaace93d10dc8496a11c99e5f362da`。未提交、未推送、未发布网站。

## 环境与证据分层

- JDK 17.0.14、Scala 2.13.12、Pekko 1.1.3、Persistence JDBC 1.1.1、Debezium 3.6.1.Final、MySQL JDBC 8.0.33。
- 独立原生 MySQL 9.5.0：源 `127.0.0.1:13365`；L、Actor JDBC Journal、初始化材料与真实目标使用 `127.0.0.1:13366`，与源 UUID 不同。只使用专用实验实例与随机实验 schema；不操作其他服务。
- 每个恢复实验启动独立 worker JVM，实际 Actor Journal 使用 MySQL，并保留原始 CBOR payload、serializer id/manifest 和解码值。Snapshot、共享 Debezium Reader、完整组 Sink 均执行真实 SQL。
- 实验表通常为 64 行、6 字段，覆盖整数、精确 DECIMAL、DATETIME(6)、NULL、空串、中文、emoji 和首尾空白。比较导出完整源/目标行集、缺行、多行和逐字段差异；不能用 count 相等替代。
- LOW/HIGH/F/K 在源控制表中通过真实事务写入；时序门只暂停实际代码切点，不制造 SourceRecord 或确认结果。COMMIT 故障代理保留真实服务端协议帧和连接 id。原始回调及 markProcessed/markBatchFinished 顺序单独保存。

[实现与恢复边界](design.md) 说明代码结论。[final-summary.json](final-summary.json) 是对原始材料独立复算的结果；`verify.py` 不依赖 Scala 测试的 equal 字段来推断相等。安全暂停案例保留不相等结果，不把它们写成端到端恢复成功。

## I01—I24 覆盖范围

| 编号 | 固定窗口及检查内容 | 不应扩大成的保证 |
|---|---|---|
| I01 | 同一源实例已有 5 表持续同步后动态增加 2 表；全量初始化、tail 与逐字段对账 | 70 万真实表吞吐或连接容量 |
| I02 | 空表、无 GROUP 的 F 激活；之后插入进入 tail | 用 GROUP 伪装 F 游标 |
| I03 | 负数/稀疏键；落在已越过位置的插入与超过冻结 U 的插入 | 扫描自身会看到所有后来插入 |
| I04 | LOW 后开启 RR，读取期间更新/删除 | 业务事务全局原子可见 |
| I05 | 读连接关闭后 HIGH 前更新、删除、重建同键 | 只依赖 snapshot 的最终状态 |
| I06 | 事务早于 LOW 开始、读窗口中提交；多行及同事务多次更新 | 跨表事务原子发布 |
| I07 | HIGH/封存之后继续变更 | HIGH 之后的变化可以直接丢弃 |
| I08 | 已完成 Split 之后双向修改主键，跨范围和 U；真实 DELETE/CREATE 跨回调 | 每 Chunk HIGH 过滤即可避免漏行 |
| I09 | 分片边界行或整个已计划范围消失 | 缺失边界可以重算原 Split |
| I10 | LOW/HIGH/F 的 C0、C1、晚提交、晚回滚，分别注入和记录 | marker 存在即 ACK 前缀覆盖 |
| I11 | RAW 页已持久化、读视图仍开着时崩溃；重启前删除边界行 | 换事务后继续旧页游标 |
| I12 | 实际 KILL 读取连接；新 attempt 重读原范围 | 新连接继承 RR view |
| I13 | HIGH 已绑定但未覆盖时禁止封存；另测 D 写入被锁住时 L/T/J 前进 | D 必须与 T/J 同步推进 |
| I14 | 窗口 fold 中崩溃 | 未封存中间状态可以当结果页 |
| I15 | FINAL、RAW、Schema、L proof、spool registry 缺失或损坏 | 自动从最新位点重建并发布 |
| I16 | baseline T 已提交、J 页进度未提交；重启前源数据改变 | 重读当前源数据可以沿用旧页身份 |
| I17 | baseline 及原始组回放分别覆盖 C0/C1/晚提交/晚回滚 | baseline 代理实验等同于真实回放 Sink 实验 |
| I18 | J 明确拒绝，包括 T 之后；3 次预算耗尽；baseline/replay close 未确认；回放等待 40 秒后取消 | 等待超时意味着 Future 已停止 |
| I19 | 最后完成窗口、F 已确定但 J 尚未转阶段时崩溃 | 可以随重启移动 F |
| I20 | 原始组 T/J 间隙，批大小 1→128 和 128→1 | 批次身份是原始组身份 |
| I21 | 激活 T 已提交、J 尚未 STREAMING；F 后变更 | 激活 receipt 单独代表可发布 |
| I22 | B 前、读视图中、目标提交后取消；K 之后晚到旧 LOW，其他表继续 | 取消必然删除目标；晚 marker 可复活旧代 |
| I23 | 激活前/激活 T 后取消竞争；新 generation 与新独占目标 | 复用旧部分目标自动初始化 |
| I24 | 窗口内 DDL 安全暂停；单表 spool 容量不足且其他表继续 | DDL 演进或整个读取域自动恢复 |

## 本轮发现并保留的失败

1. 未封存重启重新计算上界：删除原边界行后最终数据仍相等，但固定范围改变。I11 明确断言范围后失败；修复为复用原 spec、新 attempt 整段重读。
2. MySQL 元数据列标签大小写：目标预检读取失败。统一内部标签后复跑。
3. 测试设施并行复用 server-id 导致 MySQL 踢掉 Reader；每个 capture 使用独立且重启稳定的 server-id。失败归为实验干扰，不能算协议通过。
4. 新 generation 测试误用 g1，按生产约束被拒绝；改为 g2 和新目标。保留原拒绝结果。
5. Journal 单测误用未初始化的 JDBC snapshot store，导致恢复超时；单测改为独立内存 Journal/local snapshot，实际故障实验始终使用 MySQL JDBC Journal。
6. 独立审查指出 replay Future 超时/close 错误可能提前释放额度、DATETIME 误收 TIMESTAMP、registry 丢失归零。均有 RED→GREEN 证据，回放超时反例使用真实提交回复阻塞超过 40 秒。
7. registry 防归零修复后的并行首次登记出现间隙锁死锁；修复登记准入及目标认领顺序，再跑动态 5+2 与完整矩阵。
8. 早期 runner 使用共享父目录收集 case，平行运行时会把其他 attempt 的目录归入同一次结果。早期列表只作诊断材料。最终运行使用独立目录，并把冻结源码摘要写入每个 case，独立核对全部预期编号。

9. 本轮曾把旧直读恢复回归与新矩阵并行编排，额外源端事务元数据造成 LOW 等待超时。已停止该回归进程树并保留现场，最终矩阵、相关测试及旧回归严格串行。I13 将 D 阻塞固定在 F 已绑定但未获得 L ACK 覆盖的切点，验证覆盖完成后 T/J 可在 D 滞后时完成激活；不把它扩展为 SDK 在任意 offset I/O 阻塞下都能继续读取新 marker。

旧 C2 失败材料和先前实验目录均保留；没有为了通过本轮测试修改旧恢复协议或删除失败样本。

## 复跑与独立核对

实验实例与受限实验账号需要自行准备；凭据文件不进入材料。示意命令：

```sh
export JAVA_HOME=/path/to/jdk17
python3 docs/experiments/2026-10-25-bootstrap/run.py unit --settings-file /private/mysql-settings.json
python3 docs/experiments/2026-10-25-bootstrap/run.py matrix --settings-file /private/mysql-settings.json
# 所有 bootstrap Reader 都结束后再跑旧回归；旧回归末尾有专用实例的 binlog 保留边界测试。
python3 docs/experiments/2026-10-25-bootstrap/run.py regression --settings-file /private/mysql-settings.json
python3 docs/experiments/2026-10-25-bootstrap/verify.py target/bootstrap-runners/MATRIX_RUN final-summary.json
```

`matrix --case I18` 等筛选适合诊断；独立复核只有在覆盖冻结版本中的完整矩阵、所有进程释放检查存在且所需逐字段比较通过时才输出整体 PASS。

## 旧回归暴露的阻断项：R02

实际失败不是目标对账误报：B 没有绑定，未进入捕获就绪。恢复日志显示：

- `12:32:09.628 UTC` 发出 PROCESS_READY；`12:32:09.685 UTC`，SUBSCRIBE 的控制事务完成。
- `12:32:09.829 UTC`，Debezium 才加载旧 D：`mysql-bin.000006:211714068`，`snapshot=INITIAL`。
- 后续日志明确记录上次 Schema snapshot 未完成、重新进行 snapshot；`12:32:10.856 UTC` 再选源端起点，变为 `211714909`。
- [真实 binlog 截段](r02-startup-window.txt) 中 B 的 ROW event 位于 `211714759`，事务在 `211714909` 提交，GTID 为 `453631`；新 snapshot 恰好从事务末尾开始。B marker 在新的读取起点之前提交，后续没有被 Reader 捕获；原测试最终 `B a` 等待超时。PROCESS_READY 与已保存 Schema 均不足以证明 Engine 已完成初始 Schema snapshot。

原始现场是 `target/shared-capture-matrix/R02-bbf6cab03f90/`，在本轮 archive-index 中可定位。启动快照恢复需要另行明确订阅准入与重放起点；这里未修改生产恢复协议，也没有让旧 R02 增加等待来掩盖失败。**尚未实验验证“B 已生效且 D 仍为 INITIAL”时的重启边界，不能由本轮结果推出该窗口安全。**

61 项新矩阵通过不消除这个旧启动边界。整个项目的无保留验收状态仍为 `evidence_incomplete`。

## 未验证边界

DDL 演进、共享 Reader/初始化的自动接管、GC、70 万真实表及长期 churn、数据库切主、网络分区下跨 owner 证明、任意外部重写与哈希同时伪造未验证。Source Schema 的库级发现/缓存及 ACK 全历史审核的长期成本未解决。默认 8 个显式会话是首版准入限制，不能称为 70 万活跃任务调度实现。

业务发布是调用方遵守的 readiness 契约，未提供 SQL 原子换表/权限切换。初始化中直接 SQL 查询目标可看到部分数据和暂时回退。未接入产品启动、API/UI 或旧 guardian 接管入口。

后端常规测试明确排除外部集成 tag 和已知清理超时的旧 `cn.xuyinyin.magic.actor.ActorSpec`。该旧失败证据保留，不能把本轮相关测试通过写成所有历史测试与所有环境均通过。
