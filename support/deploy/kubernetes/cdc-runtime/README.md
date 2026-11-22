# 独立 CDC Runtime 部署

该目录部署两个生产模块：`pekko-cdc-capture` 与 `pekko-cdc-worker`。Capture 运行 1 个副本并打开 SharedMySQLCapture，Worker 运行至少 2 个副本并可通过 HPA 扩展。

这个目录只部署两个应用入口，不修改旧 Workflow 数据。`pekko-cdc-capture` 使用 `CaptureMain`，运行 1 个副本并持有唯一的 `SharedMySQLCapture`。`pekko-cdc-worker` 使用 `WorkerMain`，运行至少 2 个副本，通过共享状态存储接管表级任务。它要求部署方先创建同名 Secret `pekko-cdc-runtime-config`，至少提供：

`CDC_SOURCE_HOST`、`CDC_SOURCE_PORT`、`CDC_SOURCE_DATABASE`、`CDC_SOURCE_USER`、`CDC_SOURCE_PASSWORD`、`CDC_SOURCE_LINEAGE`、`CDC_STATE_HOST`、`CDC_STATE_PORT`、`CDC_STATE_DATABASE`、`CDC_STATE_USER`、`CDC_STATE_PASSWORD`、`CDC_STATE_LINEAGE`、`CDC_STATE_SDK_USER`、`CDC_STATE_SDK_PASSWORD`。

Source 与 State 的 UUID 必须来自实际 `SELECT @@server_uuid`，并且账号必须按独占角色配置。Secret、Schema 初始化、业务表清单和 GC trusted admin 不写入 Git。

需要 guardian 自动接管时，将 `source-fence-v2` 设为 `true`，并额外提供独立的 source observer/controller 账号和受管 supervisor 配置。该模式会把 B、版本锁、旧事务排空、Journal 恢复许可和 L floor 绑定到同一个 owner attempt；无法证明隔离时会暂停。默认模板仍保持关闭，避免没有受控账号时误启动。

Runtime API 目前按设计绑定容器 loopback；Service 只保留资源入口，生产环境应通过已认证的管理代理暴露 API。部署模板的 TCP probe 只证明进程监听，不证明 CDC 已捕获就绪。

GitHub Actions 的 `deploy` job 默认关闭，只有手动 `workflow_dispatch` 且提供环境 `cdc-runtime` 的 `KUBE_CONFIG_DATA` 时才 apply 本目录。流水线明确不引用旧 Workflow StatefulSet。
部署前应删除遗留的 `bigdata-lab/pekko-cdc-runtime` 单体 Deployment/Service。历史实验材料中的旧对象清单只作为证据保留，不属于当前生产清单。
