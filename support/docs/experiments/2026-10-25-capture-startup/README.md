# C：启动恢复探针（2026-10-25）

本包只新增测试设施和证据；生产协议未修改、未提交、未推送。三条有效场景结论：自然 R02 触发 **PASS**；确定性选点前 B 提交 **FAIL**；已有 B 且 D=INITIAL 重启后 **FAIL（5 个字段差异）**。不能据此宣称 bootstrap 全场景通过。旧 `../2026-10-25-bootstrap/r02-startup-boundary.json` 原失败原样保留。

## 环境与版本

独占槽 b：source `14375 / 561ae360-2c2c-4de9-bf01-3b913e7226a6`，state `14376 / 542fff04-a65b-4f5f-a911-90ff25211e3d`，MySQL 9.5.0；Actor 使用真实 JDBC Journal，目标使用真实 MySQL Sink。未连接旧 13365/13366 或 3306。

JDK：Microsoft 17.0.14。四次有效构建均 `Test / compile` 成功，冻结文件在 `candidate-{1..4}.json`，对应源码 ZIP 与 build log 均保留。候选 1—4 的 production SHA-256 均为 `4953d46ef2fa32c4ecc588afd04c14a067aab024f6b0a218145ea8dc67ee55f4`，收尾时当前生产文件逐项仍与候选 4 清单一致。候选 3 的测试摘要 `255498f9278961ee36f0f3f0d382ec311aeb4d6db1be4e0c2346ef3697377e6b`；候选 4 `f10069601fb1e370b32a3c9d08ac5170235b9254b7148f73d0fba98b9535d691`。这是本轮冻结候选之间的相同性，不能替代旧实验全源树摘要。

原始自然 R02 的 SDK 直接连接，保留原 TLS 设置；确定性探针经测试 SQL wire proxy，仅将 SDK `database.ssl.mode=disabled` 以通过独占 loopback 明文代理。所有场景均保持 `snapshot.mode=no_data`、`snapshot.locking.mode=none`。没有改写 SDK SQL、回复、SourceRecord、ACK 或 D。

每个 schema 的 a—g 源表与目标表预先从 fixture 的 `source_rows` 前四条复制；这是已知初始化基线，不是 LOW/HIGH 在线初始化。每次对账保存全部行、六个字段、缺失/多余主键和逐字段差异。

## 三个有效场景

| 场景 | 候选 / 目录 | 本轮结果 |
|---|---|---|
| 原自然触发：Schema 可见后 PREPARE、保存、崩溃、重启立即 SUBSCRIBE | 3 / `R02-original-trigger-eaa005be087a` | PASS，5.6 秒；不据此否认旧失败 |
| Schema snapshot 结束前保存 INITIAL D；重启 SDK status SQL forward 前提交 B | 3 / `R02-before-point-1953f1c0d72e` | FAIL，B a 超时；旧 D117041 → B ROW117732 / END117882 → 新 D117882 |
| B 已绑定、实际 D 仍 INITIAL；业务更新后崩溃、重启、写新 witness | 4 / `B-bound-D-initial-0d00664ccfa7` | FAIL，id1 五个字段遗漏；id2 witness 已有 T/J |

自然 R02 初始 batch=1、恢复 batch=128；确定性 R02 同样 1→128。B+INITIAL 为 128→128。只确认每个确定性门的一次真实命中，未测重复命中频率。

`SCHEMA_EMITTED` 测试 factory 对 Debezium 3.6.1 的 `RelationalSnapshotChangeEventSource` no_data step7 日志同步设门；该位置在 Schema 入队后、`preSnapshotCompletion` 之前。SDK 升级若无此原始事件，测试必须失败，不用 sleep 替代。

B+INITIAL 的实际门序列：先停 source snapshot producer；等待真实 schema callback 原始 ACK 返回并写入 INITIAL D，在已有 `RAW_ACK_RETURNED` observer 停 callback 返回；释放 producer，保持 consumer callback；提交真实 B，并观察 `BinlogStreamingChangeEventSource` 已把独占源唯一的一条 CREATE 行入队；释放 callback，让 SDK 自己取得下一批；在 `B_BOUND` 后、原始 `RAW_ACK_BEGIN` 前停住。没有使用 offset 行锁、重分批、手造记录或伪 ACK。

该窗口实际 D 为 `binlog.000003:194970 / INITIAL`，B 为 ROW195262 / END195412，GTID `...:487`。业务 id1 更新为 ROW195705 / END195893，GTID `...:488`，已在崩溃前提交。重启真实 schema callback 显示新点196292，越过业务事务；之后才写 id2 witness（ROW196585 / END196746，GTID `...:490`）。

witness 后 T 有 1 条 group ledger，J 有 6 条真实事件，Actor checkpoint 到 L sequence20，实际 D 为 pos196371/event3、无 INITIAL。source/target 均四行，没有缺失或额外主键，但 id1 的 label、amount、revision、note、updated_at 五个字段仍旧。完整值见 `after-witness-T-J.json`、`outcome.json`；事务位置、GTID 与提交时间见 `binlog-window.txt`；SDK/ACK/B/T/J 原始顺序见 `timeline.json` 与 worker log。

## 保留的设施失败

- 第一次 sbt 使用错误 project key，未编译；`sbt-invalid-project-entry.log` 保留。
- 候选 1：A 槽初始只授权 socket/localhost，TCP127.0.0.1 拒绝，fixture 初始化即失败。A 后续限定补授权；不是 R02 复现。
- 候选 2：测试 proxy 拒绝 SDK 默认 preferred TLS，SDK 连接失败；原始日志保留，不冒充生产红例。当前自然触发绕过 proxy，确定性探针明确记录测试传输差异。
- 候选 3 的第一次 B+INITIAL：observer 误期待 SUBSCRIBE 明文，实际 SDK binlog DEBUG 将字符串表示为字节数组；B 行已入队，但观察漏报、callback 未释放，故该次仅为设施失败，不声称命中 B_BOUND。候选 4 改用精确的单条 CREATE enqueue 原始事件后命中，未增加 sleep 或 timeout。

## 边界与收尾

未覆盖：并发新增/取消、关闭竞态、commit unknown、启动失败重试矩阵、静默库/heartbeat、未来启动协议、完整旧回归、全部后端或 LOW/HIGH 矩阵。整体仍为 `evidence_incomplete`，两个语义红例保持失败。

`cleanup-after-release.json` 确认 worker=0、source/state 其他实验连接=0、事务=0、所有本轮 owner advisory lock 已释放，b 租约已释放。保留实验 schema、binlog 和原始失败以供核查；没有 purge 或删除数据。凭据内容扫描零命中。
