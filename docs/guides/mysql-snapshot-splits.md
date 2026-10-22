# 单表快照 Split：分页与独立恢复

本能力是显式启用的静态数据集全量读取。它不实现在线增量快照，也不改变 `mysql.cdc` 的完整组、T/J/D 或原始回调前缀 ACK 协议。

## 启用条件

使用 `mysql.snapshot → mysql.write` 直连；这一步不接受 Transform。读取期间源数据、主键和 Schema 必须保持不变。建议使用受控静态副本或在业务确认的停写窗口运行；配置声明本身不会阻止外部写入。目标建议为空表，其他目标唯一约束也可能导致暂停；本能力不会删除目标多余行。

目标先显式执行现有 `server/src/main/resources/db/mysql/pekko-sync-ledger-schema.sql`，再执行新增的 `pekko-snapshot-batch-evidence-schema.sql`。应用不会自动创建或修改这两张账本表。

在原 Source DSL config 中增加：

```json
{
  "table": "source_rows",
  "primaryKey": "id",
  "columns": ["id", "label", "amount", "note", "updated_at", "revision"],
  "chunkSize": 1000,
  "splitRows": 100000,
  "splitParallelism": 2,
  "maxPageBytes": 1048576,
  "snapshotConsistency": "quiescent"
}
```

连接参数沿用现有 DSL，建议通过 `passwordEnv` 引用密码。Sink 沿用 `mysql.write` 的连接、table、batchSize 和 mode。完整 DSL 创建、执行入口与旧版本相同。缺少 `splitRows` 时保留原单分区快照路径，旧任务不自动迁移。

| 配置 | 含义和限制 |
|---|---|
| chunkSize | 每页行数，必填，Split 模式最多 10000 |
| splitRows | 发现一片范围时扫描的目标主键数，最多 1000000，不能小于 chunkSize |
| splitParallelism | 同一表 owner 内的并行片数，默认 2，最多 8 |
| maxPageBytes | 一页序列化后的 UTF-8 字节上限，默认 1 MiB，最多 16 MiB；超出后暂停，不重新分批 |
| snapshotConsistency | 必须明确写 quiescent；这是调用方的数据静止约束，非数据库锁 |

## 执行和进度

先冻结 execution/revision、源目标配置指纹、全局主键上界和分页参数。按实际主键生成 `(lower, upper]` 范围，先写 Actor Journal 再执行；同一 execution 恢复不重新规划已登记范围。主键沿用现有单列、非空、数值类型限制。

最多保留 16 个 Split 的规划窗口。窗口内可以并行、乱序完成；所有片完成后持久化窗口归档，再发现下一窗口。慢片可能阻止窗口继续扩展，这是限制当前状态大小的取舍，不是无限调度队列。

一片内继续 keyset 分页：`pk > lastCursor AND pk <= splitUpper ORDER BY pk LIMIT pageRows`。批次身份使用 execution、source、Split ID 和片内序号；临时 Reader/attempt 身份不参与批次 ID。

每页：目标效果与账本/内容摘要同事务提交（T）→ Actor 确认交付进度（J）→ 继续处理下一页。可能存在有界读取预取，未提交页的临时游标不作为恢复依据。快照没有 binlog offset，D 明确为 N/A，不能称为 T/J/D 已接通。

目标存在该批次时同时核对元数据和源/目标页摘要；同身份内容不同则暂停。摘要是重放冲突检查，不能检测全部并发源变更，也不替代逐字段验收。

## 恢复、停止和暂停

- 每片保存独立 Checkpoint；健康片可继续完成。owner/JVM 重启后跳过已完成片，分别重建未完成片。
- 一片同 execution 最多自动恢复 3 次，次数写入 Journal，重建会话不重置。已完成片的完成回复丢失不会重新读取该片。
- 页交付的 COMMIT/J 结果不确定时，先确认旧资源释放，再使用相同身份及最新持久化进度重放。计划/枚举元数据回复丢失未作为自动恢复保证；失败会保留上下文，已验证的是明确进程故障后的 Journal 恢复。
- 明确拒绝、摘要冲突、非可重试错误及预算耗尽保留上下文暂停。`snapshot_blocked` 不表示数据已经一致；当前没有新增人工继续/放弃 API。
- 任一片资源释放未知会关闭整个执行的新准入，已接纳工作排空；Engine 保留释放 fence，不能靠超时启动替代 Reader。
- 停止后不接纳新 T，已经进入目标提交的页继续保存结果和 J；确认资源释放后 owner 才退出。读取有 JDBC 查询/Socket 超时，停止不承诺固定 10 秒完成。

Split 路径使用独立的游标读取和 JDBC 超时配置；旧 Source、CDC 及普通 Sink 路径保留原设置。目标每次 Split 提交最多一个池连接、无最小空闲连接；Source 每会话一个池连接。驱动取消、Journal 与管理查询还有各自连接，不能把 Reader 数当数据库全局物理连接硬上限。

## 兼容和证据边界

旧持久化类名、persistenceId、Checkpoint 和原事件保持。新增事件及可选 Split 状态；未启用时不序列化额外状态字段。旧 CBOR 样本和新非空状态分别测试。写入新事件后，不承诺旧二进制直接降级。

maxPageBytes 限制接受的应用页大小，不能证明 JVM RSS 硬上限。驱动获取、单行大字段和字符串转换仍有临时内存；宽行拒绝实验只证明对应输入会安全暂停。跨表资源预算和完整窗口让出是另行启用的[有界调度](mysql-table-scheduling.md)，不属于原单表实验结论。尚无共享 Reader、跨节点 Split 抢占、多 owner fencing、并发源写入一致性、DDL、全量到 binlog 切换或 70 万张物理表容量结论。真实永久阻塞的 JDBC close、源实例替换及其他未在实验表中覆盖的 MySQL 类型也未验证。

实际结果与原始材料见 [最终审查与重跑](../experiments/2026-10-21-snapshot-splits-review/README.md)；[首次实验及失败材料](../experiments/2026-10-21-snapshot-splits/README.md) 保留原版本归属。
