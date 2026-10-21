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
查询仍通过 `currentPersistenceIds` 丢弃深页前面的 ID，并询问分片实体，可能唤醒冷任务。
没有稳定跨页快照、独立任务目录或 70 万任务容量保证。

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
