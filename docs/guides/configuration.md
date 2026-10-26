# 配置指南

## 配置入口

- `application-dev.conf`：本地单节点开发。
- `application-prod.conf`：共享 MySQL JDBC 持久化的集群部署。
- `application-test.conf`：隔离 H2 测试。
- `application-multinode-test.conf`：Task 8 的真实双 ActorSystem、动态 loopback
  端口和共享 file-H2 测试。

使用生产配置时通过 `-Dconfig.file=/path/application-prod.conf` 或等价部署方式
加载配置。启动配置验证器会检查 JDBC plugin、profile、driver、URL 和共享数据库
引用；readiness 会探测 JDBC Read Journal 是否可用。它们不会创建、drop 或
truncate 表，也不替代部署前的三表 schema 初始化检查。

## 标量环境变量

`application-prod.conf` 支持下列标量环境变量覆盖默认值：

| 变量 | 用途 |
|---|---|
| `PEKKO_HOSTNAME` | Artery 对其他成员公布的可达主机名 |
| `PEKKO_PORT` | Artery 端口 |
| `HTTP_HOST` | HTTP 绑定地址 |
| `HTTP_PORT` | HTTP 端口 |
| `PEKKO_SHARDING_SHARDS` | Cluster Sharding 分片数量 |
| `PEKKO_WORKFLOW_SNAPSHOT_EVERY` | 每多少个 workflow 事件创建快照 |
| `PEKKO_WORKFLOW_KEEP_SNAPSHOTS` | 保留的 workflow 快照数量 |
| `PEKKO_LOG_LEVEL` | Pekko 日志级别 |
| `DB_HOST` | MySQL 主机 |
| `DB_PORT` | MySQL 端口 |
| `DB_NAME` | 专用持久化数据库 |
| `DB_USER` | 仅拥有该数据库所需权限的账号 |
| `DB_PASSWORD` | 数据库密码 |
| `MYSQL_CDC_ENABLED` | 显式设为 `true` 才在通用生产配置中启用 MySQL CDC |
| `CDC_OFFSET_FLUSH_INTERVAL_MS` | Debezium offset 刷新间隔；正常运行默认每批尝试刷新（`0`） |

## MySQL CDC 状态配置

通用 `application-prod.conf` 默认关闭 MySQL CDC，避免未部署 CDC 的普通集群因缺少
CDC 凭据而启动失败。启用时设置 `MYSQL_CDC_ENABLED=true`；专用
`cdc-single/application-cdc-single.conf` 已显式启用。

CDC offset 和 schema history 复用 Pekko JDBC persistence 的 MySQL URL，并使用
`DB_USER`、`DB_PASSWORD` 连接。配置中的 `offset-table`、`history-table` 是表名前缀；
运行时按稳定 `connectorId` 派生独立表：前缀最多保留 31 个字符，再拼接下划线和
SHA-256(`connectorId`) 的前 32 个小写十六进制字符。这样不同 connector 不共享、
不覆盖进度。表在恢复时复用，停止或删除 workflow 不会自动清理；清理 connector
状态属于可能造成重放或数据缺口的独立管理操作。

<a id="resident-cdc-platform"></a>

## 常驻 CDC 平台：显式启用

这是 `/api/v1/cdc-tasks` 的共享 Reader、LOW/HIGH 初始化及持续同步入口，与旧 workflow
和 `/snapshot-tasks` 静态快照调度独立。`reference.conf` 默认
`pekko.workflow.cdc-platform.enabled=false`；未配置平台时相关 API 返回 503，不提供演示任务。
`MYSQL_CDC_ENABLED` 不会代替这个独立开关。

配置只在指定的一个 owner JVM 启用。其他集群节点继续关闭本平台；现有 Sharding、
Scheduler 或 guardian 的故障接管不会自动接管这条链路。一个控制 schema 对应一个平台
owner；首版不提供可配置的多平台命名空间。

以下是当前 [`CdcPlatformSettings`](../../server/src/main/scala/cn/xuyinyin/magic/workflow/cdc/CdcPlatformSettings.scala)
实际读取的 HOCON 结构。保存到权限受控的外部配置文件；`${...}` 是该文件显式引用的必填
环境变量，示例不包含密码值，也不是新增的隐式环境变量映射。

```hocon
include classpath("application-prod.conf")

# Journal 的 URL/账户可独立配置；本例与控制库同 schema、使用不同账户。
pekko-persistence-jdbc.shared-databases.slick.db {
  url = ${CDC_CONTROL_JDBC_URL}
  user = ${CDC_JOURNAL_USER}
  password = ${CDC_JOURNAL_PASSWORD}
}

# 本平台的控制/L/spool/receipt/Sink 与 SDK state 使用同一个 MySQL URL。
# 每个 capture 的 SDK state 与 L 连接使用其专属 SDK state 账户，不能沿用控制账户。
pekko.workflow.mysql-cdc {
  enabled = true
  state-jdbc {
    url = ${CDC_CONTROL_JDBC_URL}
    username = ${CDC_CONTROL_USER}
    password = ${CDC_CONTROL_PASSWORD}
    offset-table = "debezium_offset_storage"
    history-table = "debezium_database_history"
    offset-flush-interval-ms = 0
  }
}

pekko.workflow.cdc-platform {
  enabled = true
  snapshot-steps = 2
  tail-steps = 2
  candidate-page = 32
  max-pending-operations = 64
  poll-interval-ms = 100
  tail-delay-ms = 500
  captures = [
    {
      id = "shop-source"
      host = ${CDC_SOURCE_HOST}
      port = 3306
      database = "shop"
      username = ${CDC_SOURCE_USER}
      password = ${CDC_SOURCE_PASSWORD}
      server-id = 540001
      source-lineage = ${CDC_SOURCE_UUID}
      state-lineage = ${CDC_STATE_UUID}

      exclusive-sdk-accounts = true
      sdk-state-username = ${CDC_SDK_STATE_USER}
      sdk-state-password = ${CDC_SDK_STATE_PASSWORD}

      # 成对配置；若省略，两项均省略，改用上面的 source 账户观察。
      source-observer-username = ${CDC_SOURCE_OBSERVER_USER}
      source-observer-password = ${CDC_SOURCE_OBSERVER_PASSWORD}

      batch-size = 128
      chunk-rows = 32
      page-rows = 8
      max-log-bytes = 134217728
      max-spool-bytes = 67108864
    }
  ]
}
```

`CDC_CONTROL_JDBC_URL` 应为 `jdbc:mysql://state-host:3306/cdc_control` 这样的专用 schema
URL；源和状态实例须有不同的实际 `@@server_uuid`，分别填入两个 lineage。源数据库名
和目标表名是受校验的 SQL 标识符；`server-id` 在源实例复制客户端中须唯一。
一个配置最多 32 个 capture，ID 唯一，所有 capture 共享一个控制 URL；source/SDK-state
专属账号不能跨 capture 复用。这里只显式配置一个数据库捕获域，没有按实例自动合并配置的机制。

两类 step 预算均须为正、总和不超过 32；candidate-page 上限 128。
`batch-size` 同时用于本 capture 的 SDK 与组回放上限，`chunk-rows/page-rows` 约束
初始化窗口和页。L/spool 是有限容量，不因配置线程数增加而自动扩容；当前没有自动 GC。
这里没有 `sink-host`、`sink-user` 或 `state-observer-user` 字段。当前 Sink 的主机、端口、库
从控制 JDBC URL 派生，写入账户也是 `CDC_CONTROL_USER`。不能只在配置里填一个未被读取的字段，
就认为已实现独立 Sink 部署。

源端 JDBC 目前在内核中使用 `useSSL=false`；本配置没有新增源 TLS 字段，不能当作跨不可信网络的
TLS 部署模板。通用 JDBC URL 的参数也不代表 SDK 和源端所有连接自动采用同一 TLS 配置。

### 账号与可信观察边界

| 角色 | 当前使用位置 | 必须满足的约束 |
|---|---|---|
| `CDC_SOURCE_USER` | 本 capture 的 Debezium、marker 与分页快照短连接 | 该 capture 专用；具有复制读取、源表读取及专用控制表创建/读写权限，不给其他服务复用 |
| `CDC_SDK_STATE_USER` | 本 capture 的 offset/history、Reader owner 与全部 L Store 连接 | 该 capture 专用；与控制/Journal/Sink 长连接账户分离，具有派生 offset/history 及 L 表的必要创建、SELECT/INSERT/UPDATE/DELETE 权限 |
| `CDC_CONTROL_USER` | SQL task/grant/dispatch、spool、目标 receipt、Sink 与 state observer | 当前控制/Sink 合用；有辅助表/索引创建及目标 DML 权限，并直接授予全局 PROCESS 供 state observer 观察；不承担 capture 的 L 事务 |
| `CDC_JOURNAL_USER` | Pekko JDBC Journal/Snapshot/Read Journal | 预建持久化表的运行权限；本例单独配置，不属于 SDK state 观察域 |
| `CDC_SOURCE_OBSERVER_USER` | 源服务器的只读观察连接 | 可独立于 SDK source；必须有实际全局 PROCESS 可见性，能执行身份查询、会话/事务查询与 canary 命名锁 |

具体 MySQL 权限须结合固定 Debezium/MySQL 版本预部署验证；配置中的
`exclusive-sdk-accounts=true` 是管理员对独占性的声明，代码会检查配置复用和实际遗留连接，
不能阻止管理员把该账号交给别的程序。独占性失效时，不能靠忽略多出的连接恢复额度。

source observer 两字段都省略时，source 账户还必须直接具有全局 PROCESS 权限。
state observer 没有单独配置字段，使用控制账户；仅有某个库的可见性不够。当前实现从
`SHOW GRANTS` 核实直接全局 PROCESS（或全局 ALL PRIVILEGES），不会把角色中的间接权限
或“查询结果为空”当成完整观察证明。无需为此给 SDK state 账户全局 PROCESS。

[`CaptureResourceObserver`](../../server/src/main/scala/cn/xuyinyin/magic/connectors/mysql/source/capture/CaptureResourceObserver.scala)
在 Reader 启动前建立两端原始锚连接，核对 UUID、connection id 与 canary lock，期间不自动
重连替换锚。服务端会话/事务仍存在、权限不足、锚丢失或归属不明时保持 UNKNOWN。
source 账号下的 marker/snapshot 短连接也属于同一 capture 的资源，不能人为从统计中排除。

L 的物理位置仍是控制 URL 指向的 schema，连接凭据改用该 capture 的 SDK state 账号。
权限须覆盖 `pekko_capture_readers`、`pekko_capture_log`、`pekko_capture_schemas`、
`pekko_capture_subscriptions` 和 `pekko_capture_fences`，不能只授权 offset/history。
这样 L 的 COMMIT 或关闭结果不确定时，其遗留会话/事务仍在 observer 的账号域内。
直接装配 `SharedMySQLCapture` 的调用者也必须满足这个前提；把 L Store 接到另一共享账号，
不能取得“该 capture 全部资源已释放”的保证。观察连接本身仍使用有 PROCESS 权限的控制账号。

### 持久化与目标表前提

先由部署流程初始化 Journal/Snapshot 三表和完整组账本，见
[部署指南](deployment.md#resident-cdc-prerequisites)。目标表必须预先创建在控制 URL 指向的库，
最初为空且独占，与源的受支持字段和单列有符号整数主键匹配；使用 InnoDB，禁止业务 trigger、
外键和额外唯一约束。初始化审计通过前不向业务发布。

平台启动会创建/检查自己的 task/owner/grant/dispatch 表、L 和 Bootstrap 辅助表；
SDK 会创建按 capture ID 派生的 offset/history 表。因此普通 workflow 的“不自动建持久化表”
不意味着显式 CDC 平台无需 DDL 权限。不要将
`db/mysql/pekko-cdc-schema.sql` 当作平台完整部署脚本：它含验收夹具表和未派生的基础名称，
不能替代现存 capture 的实际 offset/history。

### API 受理、状态与停止

POST `/api/v1/cdc-tasks` 只接受以下五个字符串；凭据由服务配置提供：

```json
{"requestId":"add-orders-g1","captureId":"shop-source","table":"orders","generation":"g1","targetTable":"orders_replica"}
```

HTTP 202 仅表示定义已持久受理，可能仍为 QUEUED。捕获准入、LOW/HIGH 初始化、目标激活、
当前健康与资源释放是独立状态；同 requestId 以同定义重试，同身份改变定义返回冲突。
列表用 `after/pageSize` keyset 查询，`/api/v1/cdc-captures` 与 `/api/v1/cdc-status` 提供实际捕获域
和 owner 状态。`/api/v1/cdc-tasks/:taskId/cancel` 受后端 cancelAllowed 约束；PAUSED 没有通用 Resume。
旧 `/snapshot-tasks` 继续表示静态快照，不能把其完成视为 CDC 初始化完成。

CoordinatedShutdown 调用受管 stop，它是维护停止，不发业务取消 K。本次等待超时可返回 false，
但旧 operation 继续核实真实释放；本地 SDK completion 不等于 reusable。UNKNOWN grant 继续占预算，
旧 owner 不是 STOPPED 或仍有 ACTIVE/UNKNOWN grant 时，新 runtime 不启动替代 Reader。
当前没有新共享链路自动接管、PID/TTL 解锁或管理强制重置 API；不能手改 owner/grant 来制造可恢复状态。
相关机制和真实验证边界见[平台实验报告](../experiments/2026-10-27-cdc-platform/README.md)。

## Seed 和角色是 typed list

`pekko.cluster.seed-nodes` 与 `pekko.cluster.roles` 是 HOCON 列表。普通 shell / Docker
环境变量始终是字符串，因此把 `PEKKO_SEED_NODES='["..."]'` 或
`PEKKO_ROLES='["worker"]'` 当作字符串注入并不等价于 typed list。默认 compose
拓扑直接使用生产配置中已有的 seed/roles 列表。自定义部署请提供一段 HOCON：

```hocon
include classpath("application-prod.conf")

pekko.cluster {
  seed-nodes = [
    "pekko://pekko-cluster-system-prod@node1:2551",
    "pekko://pekko-cluster-system-prod@node2:2551",
    "pekko://pekko-cluster-system-prod@node3:2551"
  ]
  roles = ["coordinator", "worker", "api-gateway"]
}
```

`worker` 承载 workflow entity；`coordinator` 承载 Scheduler Singleton；
`api-gateway` 承载 HTTP ingress。需要 Singleton 接管时至少要有两个 coordinator，
需要 entity 接管时至少要有两个 worker。

## 工作流与调度语义

当前执行器只支持一条连通的线性路径：一个 Source、零到多个 Transform、一个
Sink。分支、合流、环和断开的节点不在 MVP 范围。

调度器采用 at-least-once 投递：

1. 持久化 `TriggerPrepared`；
2. 向 Sharding entity 发送 scheduled execution；
3. 收到 accepted / duplicate / already-running 后持久化 ACK；
4. 未 ACK 的 trigger 在恢复或重试定时器触发后再次投递。

因此外部 Sink 仍需根据自身副作用边界设计幂等性；这里验证的是实体接受执行的
幂等，不是任意外部系统的 exactly-once 副作用。

## MySQL schema

```bash
mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p "$DB_NAME" \
  < server/src/main/resources/db/mysql/pekko-persistence-schema.sql
```

所有节点必须连接同一个专用数据库。不要让不同环境共享同一 schema，也不要把
测试指向个人、Tailscale 或既有业务数据库。

## 外部 MySQL 恢复测试

外部测试默认通过 `ExternalIntegration` tag 排除。测试只接受以 `pekko_test_`
开头、且 JDBC URL 中数据库名与声明完全一致的专用 schema：

```bash
export PEKKO_TEST_MYSQL_JDBC_URL='jdbc:mysql://127.0.0.1:3306/pekko_test_recovery'
export PEKKO_TEST_MYSQL_SCHEMA='pekko_test_recovery'
export PEKKO_TEST_MYSQL_USER='pekko_test'
export PEKKO_TEST_MYSQL_PASSWORD='replace-me'

sbt \
  'project pekko-server' \
  'set Test / testOptions := Seq()' \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.integration.MySQLPersistenceRecoverySpec -- -n cn.xuyinyin.magic.tags.ExternalIntegration'
```

session override 只影响本次 sbt 会话；若不先清除默认 exclude，include 与 exclude
同一 tag 会导致 0 tests。测试不建库、不删库、不清表，只写唯一 persistence id，
并验证 Journal、Snapshot 和恢复。schema 初始化与最终删除由测试操作者在专用
环境中完成。


## 列表查询的资源上限

`application.conf`、开发、生产和测试配置都提供 `pekko.workflow.query`：

| 配置 | 默认值 | 含义 |
|---|---|---|
| `summary-parallelism` | 8 | 每个列表流最多同时向 Actor 发起多少个摘要 ask；结果保留输入 ID 顺序 |
| `max-concurrent-lists` | 4 | 同一 QueryService 实例允许的列表请求数；没有额外等待队列 |
| `timeout` | 5s | ID 枚举和摘要查询整个流的完成预算 |
| `summary-timeout` | 5s | 单个 ask 的等待预算 |

同一服务实例的未完成 ask 总量还受两项并发配置乘积限制，默认最多 32 个。
列表超时或失败后，已经发出的 ask 继续占用其额度，直到回复、失败或 ask 自身超时；
不能靠反复取消列表释放额度，再堆积新的 ask。参数必须为正，乘积必须能表示为 Int。

额度用尽返回 HTTP 503，整体超时返回 HTTP 504；分页 DTO 仍为 `{page,pageSize,items}`，
没有隐藏的部分成功页。前端可保留旧结果并提示刷新失败。此上限按服务实例生效，
不是集群全局限流或租户公平调度。

这里取消的是查询流，不能据此宣称已中断数据库执行中的 SQL 或 Actor 已开始的恢复。
旧列表仍通过 `currentPersistenceIds` 丢弃深页前面的 ID；未登记到新索引的任务继续询问分片实体，可能唤醒冷任务，已登记的受管快照使用索引摘要。没有稳定跨页快照或 70 万真实任务容量保证。

显式启用的[跨表快照调度](mysql-table-scheduling.md)另有持久化冷任务目录及游标分页，不枚举或唤醒全部实体；配置、实例别名、连接预留与物理连接的区别及 SQL 部署见该指南。70 万条实验只覆盖目录元数据。

## CDC 目标写入的语句生命周期

`mysql.cdc.apply` 每个批次连接内复用相同 SQL 的 PreparedStatement：完整组账本查询、
账本插入、目标 upsert 和 delete。所有参数逐次绑定，每条事件仍按原顺序执行，
不使用 JDBC executeBatch，也不合并跨完整组的事务。

语句只活在此次事务内，COMMIT 前关闭；关闭失败仍尝试其他语句的关闭并回滚。
没有为每张表创建常驻连接池，没有跨执行或跨批次缓存目标 Schema，目标元数据仍逐批校验。
`prepareStatement` 调用减少不等于 executeUpdate 次数或数据库网络往返同比减少。

## Protobuf 依赖兼容性

构建将 `protobuf-java` 固定为 4.33.4。原先 MySQL Connector/J 的传递 4.26.1
不含 Avatica 旧生成代码需要的 GeneratedMessageV3；新版本保留对应兼容层。
本轮同时验证 Avatica 和 Connector/J 生成消息的序列化往返，并重新运行 Calcite 套件。
没有降级 Connector/J 或改变 CDC 事件/Journal 序列化格式。

版本依据：[Protobuf v33.4](https://github.com/protocolbuffers/protobuf/releases/tag/v33.4)、
[生成代码与 runtime 的兼容边界](https://protobuf.dev/support/cross-version-runtime-guarantee/)。
完整本轮结果见[写入热路径和查询优化记录](../experiments/2026-10-20-hot-path-query/README.md)。

## 就绪与 HTTP 退出预算

`pekko.workflow.readiness-timeout=2s` 约束观测响应，实际探测未结束时继续占用唯一 slot；不会用超时释放底层查询额度。`/health` 与 `/health/ready` 使用相同就绪检查，失败或未确认返回 503。

`http.shutdown-grace=5s` 必须严格小于 `pekko.coordinated-shutdown.phases.service-requests-done.timeout`（默认 10s），且两者为正。binding 通过 Pekko HTTP 注册协调 unbind/drain。外部 Pod/JVM 停止预算还要覆盖 preStop、Sharding handoff 及其他阶段。

Guardian 现在明确读取 `pekko.cluster.sharding.number-of-shards`，默认 100。旧版本忽略过非默认值，修复后会改变相应 hash 映射；已有集群不能把它当无损滚动参数。见[架构与迁移边界](../architecture/README.md)。
