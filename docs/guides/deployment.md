# 部署指南

## 先决条件

- JDK 11+，构建时使用 SBT 1.9.x。
- 每个节点可通过其 `PEKKO_HOSTNAME:PEKKO_PORT` 相互访问。
- 所有节点使用相同 ActorSystem 名称和同一 MySQL 持久化数据库。
- MySQL schema 已由
  `server/src/main/resources/db/mysql/pekko-persistence-schema.sql`
  初始化。

工作流 JDBC Journal/Snapshot 由部署流程预建，生产进程不会自动创建、drop 或 truncate
这些持久化表。显式启用的 CDC 平台另会创建自己的辅助表，见下文专用部署前提。

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
[配置指南](configuration.md)，不要依赖 JSON-looking 的 shell 字符串列表。

## 初始化 MySQL

```bash
mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p "$DB_NAME" \
  < server/src/main/resources/db/mysql/pekko-persistence-schema.sql
```

为每个环境创建独立数据库和最小权限账号。升级前备份，并在预发布环境验证 schema
兼容性。启动配置验证器只检查 JDBC 配置形状，readiness 探测 Read Journal；部署
流程仍需显式确认三张表已初始化。

<a id="resident-cdc-prerequisites"></a>

## 常驻 CDC 平台的专用部署前提

先看[完整配置与账号边界](configuration.md#resident-cdc-platform)。平台默认禁用，
不是把所有集群节点的 `MYSQL_CDC_ENABLED` 打开就完成部署；同一控制 schema 只指定一个
JVM 启用 `pekko.workflow.cdc-platform.enabled=true`。已有集群的 workflow 高可用与这条
共享 Reader 链路的恢复范围分开判断。

首次启用前在专用状态/目标 schema 中执行下列既有脚本。参数代表控制库，密码由 `-p`
交互输入；示例只说明部署步骤，不会在本次文档修改中实际连接数据库。

```bash
mysql -h "$CDC_STATE_HOST" -P "$CDC_STATE_PORT" -u "$CDC_SCHEMA_ADMIN" -p "$CDC_STATE_DATABASE" \
  < server/src/main/resources/db/mysql/pekko-persistence-schema.sql
mysql -h "$CDC_STATE_HOST" -P "$CDC_STATE_PORT" -u "$CDC_SCHEMA_ADMIN" -p "$CDC_STATE_DATABASE" \
  < server/src/main/resources/db/mysql/pekko-sync-cdc-group-ledger-schema.sql
```

第一个脚本提供真实 Actor Journal/Snapshot/Read Journal 所需表；第二个提供完整组目标账本，
必须与组 DML 在同一目标事务中使用。`pekko-sync-ledger-schema.sql` 属于旧批次协议，
不是这里完整组账本的替代品。目标表由部署流程预建且最初为空；当前平台把目标放在
控制 URL 的 schema，不能单独配置第三个 Sink 服务器或 Sink 账户。

新辅助表由代码首次初始化，包括 SQL task/owner/grant/dispatch、capture L 与
Bootstrap 存储/receipt；offset/history 由 SDK 按 capture ID 派生。capture L 的五张表仍在
控制 URL 的 schema，但由该 capture 专属 SDK state 账号连接；该账号也必须具有这些表的
必要创建及 SELECT/INSERT/UPDATE/DELETE 权限，不能仅对 offset/history 授权。
SQL 控制索引、spool、目标 receipt/Sink 和 observer 继续使用控制账户；创建索引需要
控制账户的对应权限。已有命名空间恢复
不得删表重建，控制表丢失也不能当作全新安装跳过旧承诺。

部署前应确认源与状态为不同物理 MySQL UUID，源满足 InnoDB、ROW/FULL、
`binlog_order_commits=1` 和首版固定 Schema/单列有符号整数主键约束。源库允许创建
`pekko_capture_control`，SDK source 账户专用于该 capture；SDK state 也使用每 capture
专属账户，并覆盖本 capture 的全部 L 连接。控制/Journal/Sink 长连接不能复用 SDK state 账号，
也不能反过来让 L 事务使用不在观察域内的共享控制账号。按配置指南为可信 observer
直接授予 PROCESS 并验证会话/事务全局可见性，不能凭无权限观察得到的空结果启用资源回收。

只在一个专用环境确认启动准入后，再通过 `/api/v1/cdc-tasks` 提交真实任务。
202 是持久受理，不是初始化完成；业务应等待初始化证明与实际健康状态，不能仅凭 phase
文本或旧初始化证明判断当前可用。初次部署的权限、真实源/目标类型、网络及 MySQL/SDK
版本组合仍须在自己的环境验证；本项目的有限实验不等于通用生产兼容证明。

维护停止须保留进程停止预算及原始资源证据。平台 stop 有界回应不代表 Reader、服务端 dump、
事务或 owner 已释放；UNKNOWN 继续占用 grant/配额，不得在未确认情况下启动替代实例。
真正的 STOPPED 且无 ACTIVE/UNKNOWN grant 才允许受控重启。当前新链路不支持多 owner
自动接管；原静态快照 guardian 的证明不能自动用于共享 Reader。不要靠重设 PID、修改 owner
行、删除账本或强行清 D/L 来恢复运行。

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

外部 MySQL 命令见 [配置指南](configuration.md)。没有实际运行该测试时，MySQL
runtime recovery 必须标记为 `evidence_incomplete`；凭据或隔离 schema 不可用时标记
为 `external_blocked`。
