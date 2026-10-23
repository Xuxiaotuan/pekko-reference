# 使用指南

[配置](configuration.md)说明环境、JDBC、恢复和资源预算；[部署](deployment.md)说明构建与环境模板。项目总入口为 [根 README](../../README.md)，界面入口为 [console](../../console/README.md)。

物理目录为 `server`、`console`；SBT 项目 ID 和别名保持原样。服务入口仍为 `cn.xuyinyin.magic.PekkoServer`。实验需要显式启用，部署模板的存在不表示已经部署或通过生产验收。

[单表快照 Split](mysql-snapshot-splits.md)说明显式启用、分页与独立恢复；[跨表有界调度](mysql-table-scheduling.md)说明持久化队列、资源准入和完整窗口让出。目前仅支持静态源直连 MySQL Sink，不包含在线增量快照。

[受管静态快照自动接管](mysql-snapshot-takeover.md)说明进程隔离、服务端事务排空、恢复许可和旧配额转换；默认关闭，仅支持 supervisor 持有精确句柄的子 JVM。
