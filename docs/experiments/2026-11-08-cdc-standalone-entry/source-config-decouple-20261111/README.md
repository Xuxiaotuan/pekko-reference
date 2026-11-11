# 共享 Reader 配置数据与旧 Workflow 节点解析分离（2026-11-11）

源码指纹见 `source-sha256.txt`。CDC Source 配置 case class 不再依赖 Workflow DSL；旧节点的配置解析和凭据检查由独立适配器负责。未更改数据库、Journal 或恢复协议。

`component-tests.log` 中配置和旧节点关联测试 45 项通过。改动后在隔离双 MySQL 9.5.0、真实 Reader、Journal 与 Sink 中重跑同 owner RAW 页观察回调异常恢复，进入 STREAMING 并逐字段比较 63 行、每行 6 字段一致。该注入不是网络断连或跨 JVM guardian 接管，也不覆盖未知 B。
