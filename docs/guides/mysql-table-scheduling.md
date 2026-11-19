# 跨表有界快照调度

这一步把静态 `mysql.snapshot → mysql.write` Split 任务放入持久化待执行索引，按资源配额激活，完成一个完整窗口后让出。分页、批次身份、目标账本和 Actor Journal 仍沿用[单表 Split](mysql-snapshot-splits.md)。默认调度不自动接管失联 owner；另有显式启用的[受管静态快照隔离后接管](mysql-snapshot-takeover.md)，不包含共享 CDC。

## 启用与入口

默认关闭。先在控制数据库显式执行 `server/src/main/resources/db/mysql/pekko-table-scheduling-schema.sql`；H2 本地环境有同名 H2 脚本。已有 Journal 表、目标批次账本及摘要表仍需分别部署，不自动迁移现有任务。

配置位于 `pekko.workflow.table-scheduling`：

```hocon
enabled = true
max-active-tables = 8
max-active-tables-per-node = 4
max-connections-per-instance = 16
max-concurrent-starts = 4
max-page-reservation = 192 MiB
candidate-page = 128
max-pending-operations = 64
poll-interval = 250 ms
jdbc {
  url = "jdbc:mysql://127.0.0.1:3306/control?connectTimeout=10000&socketTimeout=30000"
  username = ${DB_USER}
  password = ${DB_PASSWORD}
}
instance-aliases = [
  { from = "localhost:3306", to = "mysql-production-a" },
  { from = "127.0.0.1:3306", to = "mysql-production-a" }
]
```

同一物理实例的域名、IP、代理入口必须由运维统一映射；系统不自动发现数据库实例身份。同实例的 Source/Sink 消耗相加，不能用数据库名或用户名拆开规避配额。所有协调器的 SchedulingLimits 必须一致，首次写入固定该组限额的摘要，后续限额不匹配直接拒绝操作。摘要不包含实例别名；映射一致性仍由部署保证，尚未验证配置分叉的多节点环境。修改配额的在线迁移尚未实现，不能直接清空摘要绕过活跃授权。

新建大量冷任务使用 `POST /api/v1/snapshot-tasks?requestId=...&revision=1&notBefore=0`，请求体为完整 DSL。`202` 仅表示运行意图已落库，回复包含 executionId 和 phase，不表示 Actor 已启动或数据已完成。重试必须使用原 requestId；同一 workflowId 同时只允许一个未结束 execution。调度唯一键是 workflowId，不是对任意不同定义中的同一物理表加全局排他锁，表与任务标识的映射需要调用方保持一致。历史 requestId 不会因近期历史淘汰而重新执行。

`GET /api/v1/snapshot-tasks?pageSize=100&after=...` 按 workflowId 游标分页，只返回摘要，不唤醒 Actor、不装载全部 DSL。新入口中尚未激活的任务还没有 Actor Journal 记录，旧的 Journal 工作流列表不承担完整冷任务目录职责。

旧工作流 API 和普通工作流调度保留。受管 Split 任务的手动、定时和恢复路径在开始连接器 IO 前进入同一准入门；有索引的新版 Supervisor 手动启动直接落库。旧执行入口和 Cron 会先短暂激活实体，再转入队列，不适合 70 万条冷任务的批量注册，不承诺这些入口的冷实体激活数量有同样上限。现有 Cron 调度器没有改为 70 万个定时任务的承载方案，原触发身份和时间水位语义保留。

旧 Define/Create 的受管初始定义先登记索引，再写 Journal；索引不可用时不先推进 Journal。已存在受管定义的修订必须走新的持久化 POST 入口，旧 Define 明确拒绝，包含把受管任务改为其他类型的更新，避免绕过索引造成两份定义分叉。原运行 completed 后可提交更高 revision 和新 requestId；新授权激活时把该定义修订写入 Journal，再开始新 execution。修订不会清空目标，新运行仍需满足静态源和目标约束，insert 模式重复写同一目标可能因唯一键冲突暂停。未结束执行不允许改定义。控制索引和 Journal 没有跨库原子事务，定义写入结果未知时需要使用原身份重试，不能把 202 当成两份状态已经同时完成。

上段描述默认模式。启用 HA 时，初始定义和触发意图先仅落控制索引；取得 writer permit 后才创建持久化实体并写入定义/执行事件，冷查询另走只读 Snapshot/Journal 投影。不能把“已登记”解释成 Journal 已写入。

## 三份状态各自负责什么

SQL 索引负责定义、运行意图、排队位置和资源授权，不能代替进度 Checkpoint。Actor Journal 保存 Split 计划、独立页进度、重试次数及窗口状态。目标账本与页摘要和数据效果同事务提交。

```text
registered → waiting/ready → held → running
                              ↑       │
                  下一个授权  │       ├─ 完整窗口排空、J 归档 → ready
                                      ├─ 全部完成、J 终态 → completed
                                      ├─ 明确失败且释放已确认 → blocked
                                      └─ 释放结果未知 → unknown（继续占配额）
```

每次授权有独立 grantId；execution、计划、Split ID 和批次 ID 不随让出变化。调度事件和可选 admission 状态是新增持久化类型，旧类名、persistenceId 及数据 Checkpoint 不变；旧 CBOR 字节另有兼容测试。产生新事件后不保证旧二进制可以直接降级。

## 有界调度与窗口让出

准入在控制数据库同一个事务锁内同时检查表数、实例连接向量和页预留量；已发出但尚未绑定的授权也占配额，并限制跨轮询累计启动数。候选页至多 1000，列表页至多 100，SQL 异步操作另有待处理上限，不为每个等待任务创建 Actor 或 Future。

一表并行度为 P 时，预留 P 个 Source 和 P 个 Sink 连接；页预留量为 `3 × P × maxPageBytes`，用于应用页和预取的保守计账。它不等同于 JVM RSS 硬限制，驱动、单行转换、序列化及消息还有额外内存。Journal、控制池、连接取消和管理查询另计，不能将 Reader 数当实例物理连接总上限。

每次最多运行现有 16 个 Split 的完整窗口。所有已接纳 T/J 排空、Source 关闭、窗口归档写入 Journal 后才能重新排队；不在半页或半个 Split 中间抢占。慢片会拉长一次占用时间，没有固定秒级公平性保证。临时资源不满足的候选移到队尾，永久超过配额的定义进入 blocked。这是队尾轮转；持续到达的小资源任务是否会使大资源任务饥饿，尚未验证，不承诺加权公平性。

节点还保留到实际 Actor 终止才释放的本地激活名额。实体经 Shard 的 `Passivate` 正式让出，兼容生产的 `remember-entities=on`；仅让 Actor 自行停止会触发重新激活，不能达到冷却效果。

HA 模式另行强制关闭 remember-entities 和运行中 shard 再平衡，避免在进程未隔离时迁移持久化 writer；不能把默认模式的配置直接照搬到 HA。

## 故障、恢复与人工边界

协调器重启重新查询持久化授权，重复发送不会改变执行身份。发出授权、绑定 owner 或归还事务 COMMIT 回复丢失时，重读索引确认准确的 grant、execution 和 owner，不能凭异常认定回滚。旧归还结果尚未在本地确认时，新窗口授权保持 HELD，不先绑定成 RUNNING。无法确认绑定结果时保留 fence；归还失败使用同一执行上下文重试控制操作，不消耗数据 Split 恢复预算。归还命令、失败重试和成功回调均固定原 grantId，旧窗口消息不能按 executionId 重新选择新窗口授权。若新窗口 deferred 时遇到旧本地映射，只在 SQL 确认旧授权已不存在后清理该映射，不更新新授权；读取仍失败就保留上下文重试。Stop 只有在实际取消与资源释放成功后，才对控制库归还失败安排同一 grant 的重试；实际释放未知仍不自动回收。资源释放已确认的失败实体归还配额后也会让出，本地激活名额一直保留到实际终止；释放未知的实体继续占用名额。

同节点实体重建仍从原 Journal 独立恢复。T 已提交而 J 尚未持久化时，以原页身份重放并校验目标账本；J 已持久化回复丢失时按实际 J 跳过已完成页。快照 D 为 N/A，原 CDC 的 T→J→原始回调前缀 ACK→D 协议没有改动。

unknown 保留授权及全部配额，不能因超时、心跳丢失或集群移除而回收。默认模式下新 JVM 的 nodeId 不同，不能自动绑定旧 owner 的 running 授权。初次进程崩溃实验先确认旧进程退出、数据库连接全部消失，再由测试代表运维显式重新排队；这些历史材料仍是人工恢复验证。另行启用的 HA 模式增加 recovering、隔离证明和 writer permit，见[接管指南](mysql-snapshot-takeover.md)，不能省略维护迁移前提。当前没有新增生产人工继续/放弃 API。

## 验证范围

初次真实多表故障窗口、70 万条元数据实验、全量逐字段对账和失败记录见[初次材料](../experiments/2026-10-22-table-scheduling/README.md)；提交前发现的授权重试与 Stop 问题、修复后的源码版本和新回归见[整体审查材料](../experiments/2026-10-22-table-scheduling-review/README.md)。初次归档保持原样，不能把其中旧运行时标为修复后版本。70 万条实验不包含 70 万张物理表、70 万个常驻 Actor 或吞吐承诺。后续[受管自动接管材料](../experiments/2026-10-23-snapshot-takeover/README.md)单独标注源码及环境；共享 binlog、DDL、在线源变更一致性和永久阻塞的 JDBC close 仍未验证。
