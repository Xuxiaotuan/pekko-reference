# Pekko CDC / Workflow

这是一个基于 Apache Pekko Typed、Cluster Sharding、Event Sourcing、Streams
和 HTTP 的 CDC / 工作流项目，以长期运行的 CDC 复制任务为主，保留必要的线性工作流和调度。当前验收范围是单数据中心、共享 JDBC 持久化和严格的
线性流水线；没有生产就绪、可用性百分比、固定故障恢复时间或线性扩展承诺。

源码入口和各层职责见[源码导航](docs/CODE_STRUCTURE.md)。

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

使用 Java 17、Scala 2.13.12 和仓库指定的 SBT。SBT 项目 ID 是 `pekko-server`。

```bash
./quick-test.sh
# 可传入本机 JDK：./quick-test.sh -java-home /absolute/path/to/jdk17

sbt 'pekko-server/Test/compile' 'pekko-server/test'

sbt \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.integration.TwoNodeWorkflowRecoverySpec' \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.integration.SchedulerFailoverSpec'
```

两个 failover spec 会启动真实的同名 ActorSystem、绑定不同的 loopback 端口，
共享一个临时 file-H2 JDBC schema，并实际终止承载 entity / Singleton 的节点。
它们不是 probe-only 的多节点替身。

`quick-test.sh` 只执行 Validator、完整组装配和 Apply Sink 的 54 项本地检查，
不代表真实 MySQL 恢复验证。完整 `test` 保留既有 Calcite 套件，不通过排除它们宣称全库通过。

```bash
sbt runServer                         # 默认本地 H2；不要据此判断 MySQL 可用
sbt 'pekko-server/Universal/stage'     # 本地生成发布目录
sbt checkDeps
```

`dockerBuild` 指向 `pekko-server/Docker/publishLocal`；`dockerPublish` 会发布镜像，
应在明确发布目标后另行执行。旧 Actor、模拟 CDC、CRDT 和工具示例已迁入 Test，
原包名和内容保留，见[示例入口与迁移说明](pekko-server/src/test/examples/README.md)。

## 最小管理界面（B 阶段）

`xxt-ui` 提供分页摘要、完整 DSL 创建、请求执行、近期历史，以及已有计划的暂停/恢复。
入口与本地启动方式见 [UI README](xxt-ui/README.md)，实际检查和兼容变化见
[B 阶段记录](docs/experiments/2026-10-19-stage-b-console/README.md)。
列表仍枚举 Journal ID 并查询 Actor；不提供全局任务总数或稳定快照分页。
SQL/通用 DAG 演示不再进入产品路由；未知指标不再返回固定数值。

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
- 真实 MySQL Source / Actor Journal / Sink 的八窗口记录见[恢复实验](docs/experiments/2026-10-19-recovery-windows/README.md)。
  七项收敛；C2 的 COMMIT 回复丢失仍进入持久化失败终态，不能自动恢复。
- [A 阶段构建与回归记录](docs/experiments/2026-10-19-stage-a-convergence/README.md)区分源码保全、依赖体积、启动检查和真实故障结果；没有 70 万表容量或生产可用性证明。
- Task 2 仍保留公开 wire `Option[Long]` 未强制 unboxing 和 terminal event 字符串
  缺少总 byte bound 的既有证据缺口。
- Task 7 仍保留 route-level 400/409 及逐路由 503/504/500 错误矩阵不完整的既有
  证据缺口。
