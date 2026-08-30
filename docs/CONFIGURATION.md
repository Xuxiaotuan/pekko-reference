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
  < pekko-server/src/main/resources/db/mysql/pekko-persistence-schema.sql
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
  'set pekkoServer / Test / testOptions := Seq()' \
  'pekko-server/testOnly cn.xuyinyin.magic.workflow.integration.MySQLPersistenceRecoverySpec -- -n cn.xuyinyin.magic.tags.ExternalIntegration'
```

session override 只影响本次 sbt 会话；若不先清除默认 exclude，include 与 exclude
同一 tag 会导致 0 tests。测试不建库、不删库、不清表，只写唯一 persistence id，
并验证 Journal、Snapshot 和恢复。schema 初始化与最终删除由测试操作者在专用
环境中完成。
