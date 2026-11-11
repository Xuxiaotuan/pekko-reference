# 目录与兼容入口

当前默认入口是 `cn.xuyinyin.magic.cdc.runtime.CdcRuntimeMain`。管理前端已从产品源码移除；早期前端试验保存在 `server/src/test/examples/legacy-console`。SBT 项目 ID 仍为 `pekko-server`。

CDC 平台协调与任务控制位于 `server/src/main/scala/cn/xuyinyin/magic/cdc/platform`，引导流程位于 `cdc/bootstrap`，检查点位于 `cdc/checkpoint`，Split 状态与读取协调位于 `cdc/snapshot`，启动入口位于 `cdc/runtime`，旧 Workflow 节点接口和静态快照执行器位于 `cdc/compat/nodes`，MySQL 捕获与应用位于 `connectors/mysql`。旧 Workflow HTTP、Cron、Sharding 和 Server 仅保留为测试材料，不属于生产 Compile 源集。

`cdc/control` 还包含受管容量配置和数据库恢复许可视图。旧执行节点的凭据重绑定位于 `workflow/execution/WorkflowCredentialBinding`；受管 JDBC 不再直接接收 Workflow 节点类型。共享 Reader 的 Debezium 配置、状态表命名及 offset 编码位于 `connectors/mysql/source/MySQLDebeziumConnector`。CDC supervisor 的配置、worker 启动设置和 owner bootstrap 位于 `cdc/guardian`，历史 supervisor/guardian 主类名保留；MySQL 排空观察及数据库身份读取位于 `cdc/guardian`；owner 接口仍由旧包限定实现，以免扩大信任边界。控制事务、guardian capability 和旧执行器尚未全部解耦。

## 尚未完成的兼容迁移

生产源码仍有 `workflow` 目录。部分仍在使用的类型存储在 Pekko Journal 或 Snapshot 中；`actors`、`execution`、`model` 和 `scheduling` 中仍混有旧 Workflow 编排与共享兼容实现。不能把这些目录称为已经清理完成。

`BootstrapJournal.Advanced`、`BootstrapProgress`、`BatchCheckpoint` 等现有 JVM 类名可能出现在 Jackson manifest 中。迁移时必须保留旧数据的解码路径，验证旧 Journal 和 Snapshot 样本后再删除旧类。历史 Workflow 事件已迁到 `cdc/compat`，由 JacksonMigration 识别旧类名；18 种旧序列化样本和 4 条历史 MySQL Journal 事件的解码通过。将一组 4 条历史事件原始字节写入隔离 MySQL 后，当前 Actor 恢复了指定 checkpoint；完整历史数据库原地升级及旧程序读取新事件尚未验证；本次目录重构也尚未覆盖全部持久化类型。

历史 `docs/experiments` 保持原路径和证据，不把旧版本测试结果提升为迁移版本的端到端保证。`console/node_modules` 和 `console/dist` 是本机旧生成物，未纳入 Git，也未在本轮删除。
