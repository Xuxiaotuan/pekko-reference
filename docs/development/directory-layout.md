# 目录与兼容入口

物理目录统一为：

```text
pekko-reference/
├── server/                  后端源码、资源和测试
├── console/                 管理界面
├── scripts/                 开发、局部测试、打包与目录检查
├── deploy/
│   ├── local/               Compose 配置
│   └── kubernetes/          原 k8s 环境模板
├── docs/
│   ├── architecture/        当前架构与协议
│   ├── guides/              配置与部署
│   ├── articles/            技术文章及学习系列
│   ├── development/         设计、计划及迁移记录
│   ├── archive/             旧总结与发布说明
│   └── experiments/         不可变历史证据
├── project/                 SBT 插件与版本
├── build.sbt
├── docker-compose.yml       根 Compose 兼容入口
├── quick-test.sh            根测试兼容入口
└── README.md
```

## 后端包

以下路径相对于 `server/src/main/scala/cn/xuyinyin/magic`，package 与物理目录一致：

| 位置 | 责任 |
|---|---|
| `PekkoServer.scala` | 保留原 main FQN；启动入口 |
| `bootstrap` | Guardian、服务启动、HTTP binding 与协调关闭 |
| `api/http/routes` | HTTP 输入、DTO 和路由 |
| `workflow/actors` | Entity/Supervisor、状态投影、定义和历史辅助 |
| `workflow/execution` | 执行计划、运行图、会话、资源释放、节点注册表和执行适配器 |
| `workflow/spi` | Source/Sink/Transform、批次读取和资源生命周期接口 |
| `workflow/query`、`workflow/scheduler` | 有界查询与持久化调度 |
| `cdc` | 信封、稳定事件身份与完整组协议 |
| `connectors/mysql/source`、`mysql/sink` | MySQL 读取/装配、目标事务和账本 |
| `connectors/kafka` | Kafka 解析、读取与进度编码 |
| `connectors/file`、`builtin`、`jdbc` | 文件、内置节点与 JDBC 密码解析 |
| `infrastructure/cluster` | 集群探测、成员观测和 Sharding 路由 |
| `infrastructure/config`、`monitoring`、`sql`、`util` | 配置、可观测性、Calcite 与非持久化工具 |

## 有意保留的兼容位置

`common/CborSerializable` 是 serializer binding 的原标记；`workflow/actors`、`events`、`model`、`checkpoint`、`scheduler` 保留原有公开/持久化声明。`workflow/engine` 只保留 `ExecutionFailure`、`CdcExecutionException`、`ExecutionResult` 和 `NodeExecutionResult` 的原定义位置，执行实现位于 `workflow/execution`。

不将这些类型改成新包里的类型别名：类型别名不能保持 JVM 类名、Jackson manifest 或历史 Journal 兼容。原 persistenceId、T→J→原始回调前缀 ACK、完整组身份、C2 恢复预算和资源释放时序均不因目录整理改变。

## 命令与历史

- `pekko-server → server`、`xxt-ui → console`、`deploy/k8s → kubernetes` 仅为兼容软链接，保留旧 cd、脚本和部署路径命令；没有第二份源码，编辑器默认显示新目录。
- SBT 项目 ID 仍是 `pekko-server`；`runServer`、`testAll`、`checkDeps`、`dockerBuild`、`dockerPublish`、`cleanAll` 的原别名保持。
- `./quick-test.sh` 委托 `scripts/test.sh`；工作目录、参数转发和失败退出码保持。普通实现的包名和定向测试包名按新导航更新。
- `cn.xuyinyin.magic.PekkoServer` 及原真实实验 process main 名称保持。两份早期 MySQL probe main 通过 Test 源集的转发器兼容；不进入生产包。
- 根 `docker compose` 入口保留，通过 include 使用本地配置。项目名、原命名卷和服务定义不变；需要 Compose 2.20+。不启动环境、不迁移卷。
- `docs/experiments` 原文件路径与字节保持；其中旧源码路径、测试名和版本是历史证据，不能批量改成新布局。重现历史实验使用其保存的 source snapshot；当前回归另记录。
- `.tasks`、`.kiro`、日志和构建产物不删除。编辑器隐藏生成目录与工具状态；构建仍产生 `server/target`、根 `target`、`console/dist`、`console/node_modules`。

当前目录迁移的代码、验证材料和未验证范围单独记录，不能把结构整理视作生产 HA 或容量证明。
