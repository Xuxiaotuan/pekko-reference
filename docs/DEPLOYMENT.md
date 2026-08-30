# 部署指南

## 先决条件

- JDK 11+，构建时使用 SBT 1.9.x。
- 每个节点可通过其 `PEKKO_HOSTNAME:PEKKO_PORT` 相互访问。
- 所有节点使用相同 ActorSystem 名称和同一 MySQL 持久化数据库。
- MySQL schema 已由
  `pekko-server/src/main/resources/db/mysql/pekko-persistence-schema.sql`
  初始化。

生产进程不会自动创建、drop 或 truncate 持久化表。

## 本地两节点 compose

先构建本地镜像并设置数据库密码：

```bash
sbt 'pekkoServer / Docker / publishLocal'
export DB_PASSWORD='choose-a-local-password'
export MYSQL_ROOT_PASSWORD='choose-a-different-root-password'
docker compose up
```

`docker-compose.yml` 启动一个 MySQL 和两个同时具有 coordinator / worker /
api-gateway 默认角色的 Pekko 节点。两个节点使用相同 schema，Artery 都监听容器内
2551，HTTP 都监听容器内 8080；宿主映射分别为 2551/8080 和 2552/8081。

该拓扑用于本地启动与恢复演练，不是生产高可用声明。生产配置的
`keep-majority` 在两个成员对半分区时没有多数派；生产建议至少三个成员，并根据
故障域验证 SBR 策略。Task 8 的确定性故障测试是在终止首节点后由存活节点明确
down 已终止地址。

## 生产拓扑

至少部署：

- 两个 `coordinator`，使 Scheduler Singleton 有接管候选；
- 两个 `worker`，使 workflow shard 有接管候选；
- 三个集群成员，避免两节点多数派歧义；
- 一个外部管理、备份和监控的 MySQL 8 数据库。

角色可以重叠。自定义 seed/roles 时使用 typed HOCON 文件，详见
[配置指南](CONFIGURATION.md)，不要依赖 JSON-looking 的 shell 字符串列表。

## 初始化 MySQL

```bash
mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p "$DB_NAME" \
  < pekko-server/src/main/resources/db/mysql/pekko-persistence-schema.sql
```

为每个环境创建独立数据库和最小权限账号。升级前备份，并在预发布环境验证 schema
兼容性。启动配置验证器只检查 JDBC 配置形状，readiness 探测 Read Journal；部署
流程仍需显式确认三张表已初始化。

## 节点故障与调度

- Workflow entity 在 worker 丢失后由 Sharding 在其他 worker 上重新建立，并从
  JDBC Journal / Snapshot 恢复。
- Scheduler Singleton 在 coordinator 丢失后由候选 coordinator 接管。
- 未 ACK 的 pending trigger 会重投；已被实体接受的相同 schedule 水位会返回
  `DuplicateExecution`。这是 at-least-once + entity-side dedup，不是端到端
  exactly-once。
- 故障检测和 SBR 决策时间取决于网络、配置和成员数；没有固定“小于 10 秒”承诺。

## 验证命令

```bash
sbt \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.integration.TwoNodeWorkflowRecoverySpec' \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.integration.SchedulerFailoverSpec'

sbt 'pekko-server/Test/compile' 'pekko-server/test'
```

外部 MySQL 命令见 [配置指南](CONFIGURATION.md)。没有实际运行该测试时，MySQL
runtime recovery 必须标记为 `evidence_incomplete`；凭据或隔离 schema 不可用时标记
为 `external_blocked`。
