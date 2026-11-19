# 受管静态快照：隔离后自动接管

本入口只支持固定数据库部署中的 `mysql.snapshot → mysql.write` 静态 Split 任务，Source 必须保持静止。它在[跨表调度](mysql-table-scheduling.md)上增加 owner、隔离证明、数据库排空和恢复许可，不替换原 Split/T/J 协议。静态快照没有 CDC ACK，D 为 N/A。

## 部署前提

默认关闭。在**停写、旧 worker 已退出且遗留数据库工作已结束**的维护窗口，显式部署 `server/src/main/resources/db/mysql/pekko-snapshot-ownership-schema.sql` 的六张控制表；原调度、Journal、Snapshot、目标账本和页证据表仍分别部署。不能把已有未知归属的活跃授权直接导入此模式，也不支持与旧二进制混跑。控制库、Journal/Snapshot、目标业务表、账本及摘要表必须使用 InnoDB；目标端在运行时核查。

每个受管 worker JVM 在创建 ActorSystem **之前**调用 `SnapshotOwnerBootstrap.prepare`（服务启动入口已接入），使用 supervisor 分配的全新 UUID boot ID。一个 JVM 只安装一个 owner 上下文，不能重用 boot ID。Source、Sink、共享 Journal/Snapshot 池的物理连接在交给业务代码前登记，控制池每次借用也登记自身连接。数据库端点必须固定为 MySQL host:port，配置中的其他 Source/Sink 实例提前列入清单。

```hocon
pekko.workflow.table-scheduling.enabled = true
pekko.workflow.snapshot-ha {
  enabled = true
  owner-id = ${MANAGED_WORKER_BOOT_ID}
  deployment-id = "managed-snapshot"
  instances = [
    # 控制库和共享 Journal 已自动登记；这里列出额外 Source/Sink 实例。
    # { url = "jdbc:mysql://db:3306/app?connectTimeout=5000&socketTimeout=30000",
    #   username = ${DB_USER}, password = ${DB_PASSWORD} }
  ]
}
pekko.cluster.downing-provider-class = ""
pekko.cluster.auto-down-unreachable-after = off
```

不允许自动 downing 绕过隔离。HA 模式禁用 remember-entities 和运行中 shard 自动再平衡；旧 member 仅在 SQL 记录 `db_drained` 后，按 address、UID 和 boot 专属 role 匹配移除。集群 Unreachable、移除或心跳过期都不是进程隔离证明。

第一版 supervisor 集成是 Scala API：`ManagedJvmIsolation(deploymentId,maxWorkers)` 只接受自己 `launch(bootId,ProcessBuilder)` 创建并持有精确 Process 句柄的子 JVM；`SnapshotTakeoverCoordinator(...).start(...)` 启动有界周期协调。**尚未提供独立的通用 supervisor 服务或部署控制器**。进程内重建 coordinator 可复用原 manager 句柄和 SQL operationId；整个 supervisor JVM 重启后不能仅凭 PID 重新收养进程，缺少可信句柄时保持阻塞。Kubernetes、远端主机及外部进程管理器的隔离提供方尚未实现。

## 持久化状态和许可

```text
owner: active → revoking → fenced → drain_wait → db_drained
                    │         │          │
                    │         │          └─ 所有固定实例两次完整空观测
                    │         └─ 精确 boot / PID / start / deployment 隔离回执
                    └─ 禁止新资源登记，不释放原配额

task:  running/recovering/unknown → ready → held → recovering → running
           旧配额继续计账        ↑                 ↑
                          db_drained 后归还    持久化 writer permit 后才创建恢复 writer
```

隔离提供方只在精确子进程已退出后出具回执；SIGSTOP 或长暂停不会被认作死亡。回执固定 operationId 和启动身份，错误回执不能推进。未登记 owner 就死亡的启动失败，在同一控制事务锁下写 `startup_failed` 墓碑；若延迟注册先完成，则仍走常规实例排空路径。已确认排空且无遗留配额的句柄才退休，历史证明继续留在 SQL。

观察器在每个固定实例独立读取服务端会话和 InnoDB 事务，不按 schema 过滤。它同时使用登记连接 ID、连接属性和持久化 worker 账户清单；未归属的 worker 账户连接、孤立事务、身份信息丢失、权限不足、超量清单及读取失败都阻止归还。观察账号需要足够的 PROCESS 和 performance_schema 查询权限；不能把“看不到”解释成“没有”。连接属性只是归属辅助信息，安全前提仍是可信受管二进制和固定部署，不能抵御恶意客户端伪造属性。

服务端 UUID 或启动时间变化时暂停自动接管。两次空观测用于消除瞬时读数，**不是隔离的替代品**。观察器一次清单最多 10000 行；每个 owner 未确认连接登记最多 4096 条，确认结束的活跃连接记录才可裁剪。超过边界保持失败状态，需要调整部署或运维处理，不能截断后作空判断。

旧配额和 writer 所有权分别保存：正常完整窗口让出会释放运行额度，但旧 owner 的 writer 所有权保留，其他 owner 不能凭新 grant 偷走 writer。故障接管必须先证明旧 owner 排空，保持原 execution/revision/definition hash，再生成新的 permit、grant 和单调 generation。`recovering` 也占用启动额度；恢复到一半再崩溃，需要对第二个 owner 重新隔离和排空。旧 grant 的激活、归还和过期消息不能更新新许可。

所有控制转换沿用短 admission 事务；外部进程终止和数据库观察不在锁内执行。COMMIT 回复丢失后重读已持久化状态，复用 operation，不凭异常释放额度。控制 SQL 及转换审计是新增表，不改变旧 Journal/Snapshot 类名或字段。

## T/J、查询与触发

恢复继续使用原 persistenceId、execution、Split、页 cursor 和 batchId。T 已提交而 J 未存时，原页身份重放，由真实目标账本和页摘要验证、去重；J 已存时从该页之后继续。明确失败、重试预算和暂停语义不变。控制许可只决定谁能恢复，不代表数据完成，也不替代 T 或 J。

HA gate 在创建持久化 Actor 前取得许可。冷实体的查询由同一个 JDBC Snapshot DAO、原 serializer/manifest 和只读 Journal 尾部投影完成，不为查询取得 writer。兼容已有 Snapshot 和已删除的 Journal 前缀，遇到缺失的尾部序列或反序列化错误返回失败。每个 engine 同时最多 8 个查询，调用方最多等待 5 秒；底层 JDBC 未结束时仍占查询名额，避免超时不断创建悬挂工作。返回状态不是跨 SQL/Journal 的原子快照。

受管手动/Cron 触发先持久化 SQL 意图，正常 dispatcher 再激活 writer。Cron 原 triggerId、scheduleId 和时间水位保留，恢复时沿用原 scheduled trigger；运行中重叠触发记录 skipped，重复及落后水位不启动第二次运行。新增控制表保存触发决定，不把它描述为旧 Journal 已产生相同事件。原 SchedulerCoordinator 的事件类型不变，也未扩展为 70 万 Cron 定时器平台。

本地触发缓存跟随 grant 生命周期：失败任务的拒绝释放经 SQL 确认后，成对回收本地 grant 和触发对象；提交回复丢失时先读回控制记录，读回失败则保留上下文。按 grant ID 清理，迟到的旧释放不能删除新窗口的触发信息。SQL 中的接受意图、Cron 水位和历史审计仍保留，不能通过删除这些记录代替缓存回收。此修复和提交前审查的独立验证见[审校补充材料](../experiments/2026-10-23-snapshot-takeover-review/README.md)。

## 适用边界

这是进程硬隔离之后的故障接管，不支持活 owner 无停机迁移。连接预算仍只约束原 Source/Sink 预留向量；Journal、控制池、观察连接、取消连接另计。页额度不是物理 RSS 硬限制，控制锁、审计和查询的生产负载尚未压测。历史控制记录不自动删除。

初版实验及所有失败过程见[自动接管历史材料](../experiments/2026-10-23-snapshot-takeover/README.md)，其中末段缓存缺口是当时的未修复状态，后续修复和验证单独归档，不覆盖旧材料。不据此宣称 CDC、多数据库分布式事务、数据库 failover、跨机器网络分区、多 supervisor 自动接管、DDL、共享读取或 70 万张物理表已验证。旧[隔离实验](../experiments/2026-10-23-owner-isolation/README.md)仍是测试显式恢复，不改标为生产自动接管。
