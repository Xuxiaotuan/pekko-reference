# 独立 CDC Runtime 部署

这个目录只部署 `CdcRuntimeMain`，不修改 `pekko-cdc-single`、`pekko-workflow` 或其数据库。它要求部署方先创建同名 Secret `pekko-cdc-runtime-config`，至少提供：

`CDC_SOURCE_HOST`、`CDC_SOURCE_PORT`、`CDC_SOURCE_DATABASE`、`CDC_SOURCE_USER`、`CDC_SOURCE_PASSWORD`、`CDC_SOURCE_LINEAGE`、`CDC_STATE_HOST`、`CDC_STATE_PORT`、`CDC_STATE_DATABASE`、`CDC_STATE_USER`、`CDC_STATE_PASSWORD`、`CDC_STATE_LINEAGE`、`CDC_STATE_SDK_USER`、`CDC_STATE_SDK_PASSWORD`。

Source 与 State 的 UUID 必须来自实际 `SELECT @@server_uuid`，并且账号必须按独占角色配置。Secret、Schema 初始化、业务表清单和 GC trusted admin 不写入 Git。

Runtime API 目前按设计绑定容器 loopback；Service 只保留资源入口，生产环境应通过已认证的管理代理暴露 API。部署模板的 TCP probe 只证明进程监听，不证明 CDC 已捕获就绪。

GitHub Actions 的 `deploy` job 默认关闭，只有手动 `workflow_dispatch` 且提供环境 `cdc-runtime` 的 `KUBE_CONFIG_DATA` 时才 apply 本目录。流水线明确不引用旧 Workflow StatefulSet。
