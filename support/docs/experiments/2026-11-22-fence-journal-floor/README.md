# Source fence、Journal fence 与 L floor 探针

本轮使用隔离 MySQL 8.0.36 容器、真实 JDBC Actor Journal 和测试控制连接，验证当前代码中已经存在的源控制保护、Journal range lock 和 Journal floor 投影。

## 实际结果

| 探针 | 结果 | 证据层级 |
|---|---|---|
| `CaptureControlFenceTriggerMySqlSpec` | PASS | 真实 MySQL trigger、procedure、受限 writer/controller、重复 marker、未授权写入和版本保护 |
| `SourceFenceLockOrderMySqlSpec` | PASS | 两个独立 source/state 连接的锁等待、释放后重试和 owner GET_LOCK 互斥 |
| `CdcJournalFenceMySqlSpec` | PASS | 真实 MySQL Actor Journal pending event range lock、伪造 receipt 拒绝和 contender lock timeout |
| `BootstrapJournalFloorMySqlSpec` | PASS | 新 JVM receipt 重签、stale/deleted evidence 拒绝、Journal floor 从 0 推进到 2、GC checkpoint 重启恢复 |

这些结果证明了源控制表保护、Journal receipt 校验和 floor projection 的组件到真实数据库边界。测试使用独立 source/state 容器，端口为 25521、25522 和 25822。密码没有写入材料。

## 测试设施修正

触发器探针原来把受限账号固定创建为 `@127.0.0.1`，容器网络实际使用 Docker bridge 地址，导致连接被 MySQL 拒绝。测试现在从当前 JDBC 会话读取实际 client host，再创建精确 host 账号。生产 fencing 逻辑没有修改。

## 尚未完成的端到端边界

- source fence v2 与 guardian、observer/controller、多 owner worker 的完整自动接管尚未运行。
- 旧 owner 进程隔离、遗留事务排空和新 owner 自动获准仍需要受管 guardian JVM 环境。
- L floor 的本轮验证是 Journal floor 与 GC checkpoint 探针，不等于共享 Reader 在真实业务流量下的完整 L GC 矩阵。
- `TableCdcGraphRuntime` 仍是 `BootstrapRunner` façade，未完成完整独立 materialized Graph 重写。
- 当前本地 Kubernetes context 是 OrbStack，没有 `bigdata-lab` 远端对象。旧单体 `pekko-cdc-runtime` 的集群删除没有在本轮执行。
