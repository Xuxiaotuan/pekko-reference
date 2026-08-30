# Pekko Workflow MVP

这是一个基于 Apache Pekko Typed、Cluster Sharding、Event Sourcing、Streams
和 HTTP 的工作流 MVP。当前验收范围是单数据中心、共享 JDBC 持久化和严格的
线性流水线；没有生产就绪、可用性百分比、固定故障恢复时间或线性扩展承诺。

## 当前边界

- 工作流只接受一个连通的 `Source -> Transform* -> Sink` 路径；分支、合流、环和
  孤立节点会被拒绝。
- 工作流定义、修订号、执行终态和调度去重水位由 Event Sourced entity 持久化。
- 工作流 entity 仅运行在 `worker` 角色；持久化调度器是限制在 `coordinator`
  角色的 Cluster Singleton。
- 调度投递是 at-least-once。调度器先持久化 pending trigger，收到实体的
  `ExecutionAccepted`、`DuplicateExecution` 或 `AlreadyRunning` 后才 ACK；实体按
  schedule 和 scheduled-at 水位保证业务幂等。
- 生产配置使用 MySQL JDBC Journal、Snapshot Store 和 Read Journal。H2 只用于
  隔离测试，不能替代 MySQL 运行证据。

## 快速验证

```bash
sbt 'pekko-server/Test/compile' 'pekko-server/test'

sbt \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.integration.TwoNodeWorkflowRecoverySpec' \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.integration.SchedulerFailoverSpec'
```

两个 failover spec 会启动真实的同名 ActorSystem、绑定不同的 loopback 端口，
共享一个临时 file-H2 JDBC schema，并实际终止承载 entity / Singleton 的节点。
它们不是 probe-only 的多节点替身。

## MySQL schema

生产进程不会创建或删除持久化表。先在专用数据库执行版本化 schema：

```bash
mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p "$DB_NAME" \
  < pekko-server/src/main/resources/db/mysql/pekko-persistence-schema.sql
```

必需变量为 `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USER` 和 `DB_PASSWORD`。
完整配置与外部 MySQL 恢复测试见 [配置指南](docs/CONFIGURATION.md)，两节点本地
拓扑与生产注意事项见 [部署指南](docs/DEPLOYMENT.md)。

## 证据边界

- Task 8 的 H2 双节点恢复和 Singleton 接管属于本地集成证据。
- 没有提供专用 `pekko_test_*` MySQL schema 与凭据时，MySQL Journal、Snapshot
  和 recovery 保持 `evidence_incomplete` / `external_blocked`。
- Task 2 仍保留公开 wire `Option[Long]` 未强制 unboxing 和 terminal event 字符串
  缺少总 byte bound 的既有证据缺口。
- Task 7 仍保留 route-level 400/409 及逐路由 503/504/500 错误矩阵不完整的既有
  证据缺口。
