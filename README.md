# Pekko CDC Runtime

> **当前基线**：CDC 实现以本仓库的 `master` 分支为唯一基线。当前远端历史默认分支仍是旧 Workflow 工程，不能作为 CDC 代码基线。`master` 的生产部署由两个应用模块组成：`pekko-cdc-capture` 和 `pekko-cdc-worker`。

## 两个生产模块

```text
pekko-cdc-capture  1 个副本
  └── 每个 MySQL capture domain 一个 SharedMySQLCapture

pekko-cdc-worker   至少 2 个副本
  └── 领取 TaskLease
      └── materialize 独立 TableCdcGraph
          └── TableActor 管理 Graph 生命周期
```

Capture 保持单副本并由 Capture lease 保护 Reader。Worker 至少运行两个副本，通过 Control DB 竞争任务租约，扩容不会复制 Capture Reader。

构建两个生产模块：

```bash
sbt 'pekko-cdc-capture/Docker/publishLocal' 'pekko-cdc-worker/Docker/publishLocal'
```

核心共享实现仍位于 `pekko-server` 兼容库中，两个模块只提供独立应用入口和镜像。旧 Workflow、前端和历史持久化迁移不属于这两个生产模块。

项目目标是面向单 MySQL 实例的按需常驻 CDC：通过共享 Reader 捕获变更，为新增表执行分页全量与增量衔接，再持续同步到目标。70 万张表是目标场景，尚不是 70 万张真实物理表的容量验收结果。

生产启动类分别是 `cn.xuyinyin.magic.cdc.capture.CaptureMain` 和 `cn.xuyinyin.magic.cdc.worker.WorkerMain`，只装配 CDC 服务和 `/api/v1/cdc-*` 路由。`CdcRuntimeMain` 仅作为旧脚本兼容入口，不是当前生产模块。原 Workflow HTTP、Cron 和管理前端不属于当前入口。历史实验和失败证据仍在 `docs/experiments`，旧前端试验保留在 `server/src/test/examples/legacy-console`。

生产 `workflow` 目录已移除。旧 Actor、执行器、注册表和 Workflow receipt 投影保留在测试设施；生产仅保留历史状态模型、manifest 迁移及必要的旧表互斥/配额兼容。生产包和排除测试类的真实 MySQL 接管检查见[运行时收敛记录](docs/experiments/2026-11-08-cdc-standalone-entry/retire-workflow-runtime-20261111/README.md)。系列文章见 [CDC 设计系列](docs/articles/cdc-design/README.md)。

## 构建与启动

使用 Java 17 和仓库指定的 SBT。构建项目 ID 保持 `pekko-server`。

```bash
sbt 'pekko-server/compile' 'pekko-server/Test/compile'
sbt runCdcRuntime
sbt 'pekko-server/Universal/stage'
```

CDC 平台需要显式配置 `pekko.workflow.cdc-platform.enabled` 和 `pekko.cdc-standalone.http.port`。独立入口默认仅允许回环地址；HTTP 绑定成功不等于 Reader 捕获就绪或表初始化完成。受管启动还需显式 guardian/supervisor 配置；无法确认旧 owner 隔离和资源释放时须安全暂停。配置和验证边界见[独立入口记录](docs/experiments/2026-11-08-cdc-standalone-entry/README.md)。

普通编译与单元测试不是 MySQL、Journal、Sink 故障恢复实验。旧版本的实验结论和失败证据以各自冻结版本为准。

## MySQL schema

Journal/Snapshot 和完整组目标账本须由部署流程预建。以下命令初始化 Journal schema；显式 CDC 平台还会初始化自己的控制/L/Bootstrap 辅助表，SDK 会创建派生的 offset/history 表，权限和完整前提见[配置指南](docs/guides/configuration.md#持久化与目标表前提)。

```bash
mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p "$DB_NAME" \
  < server/src/main/resources/db/mysql/pekko-persistence-schema.sql
```

必需变量为 `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USER` 和 `DB_PASSWORD`。
完整配置与外部 MySQL 恢复测试见 [配置指南](docs/guides/configuration.md)，两节点本地
拓扑与生产注意事项见 [部署指南](docs/guides/deployment.md)。

## 证据边界

- Task 8 的 H2 双节点恢复和 Singleton 接管属于本地集成证据。
- 真实 MySQL Source / Actor Journal / Sink 的八窗口记录见[恢复实验](docs/experiments/2026-10-19-recovery-windows/README.md)。
  当时七项收敛、C2 进入失败终态；这是保留的历史结果。后续有界恢复和明确拒绝修复见 [26 项真实矩阵](docs/experiments/2026-10-21-checkpoint-rejection/README.md)，其中安全暂停不计收敛。
- [A 阶段构建与回归记录](docs/experiments/2026-10-19-stage-a-convergence/README.md)区分源码保全、依赖体积、启动检查和真实故障结果；没有 70 万表容量或生产可用性证明。
- Task 2 仍保留公开 wire `Option[Long]` 未强制 unboxing 和 terminal event 字符串
  缺少总 byte bound 的既有证据缺口。
- Task 7 仍保留 route-level 400/409 及逐路由 503/504/500 错误矩阵不完整的既有
  证据缺口。
