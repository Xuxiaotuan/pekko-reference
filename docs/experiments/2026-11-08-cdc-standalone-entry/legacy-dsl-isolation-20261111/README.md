# 旧 Workflow DSL 隔离（2026-11-11）

将旧 Workflow JSON 模型与仅供旧节点使用的 SPI 一起放入 `cdc.compat.nodes`，旧执行器仍存在。Workflow 定义的 JSON 字段和 JSON 编解码未改变；源码包名改变，不承诺外部二进制 API 兼容。

`component-tests.log` 记录 34 项旧执行计划、校验、分片及历史事件测试通过。隔离 MySQL 9.5.0 的 `mysql-journal.log` 记录四条历史事件字节重新载入 Actor，指定 checkpoint 恢复；当前 Actor 签发的 receipt 在错误身份、过期、删除事件和事务范围锁场景中被核验。`journal-rows.tsv` 保留 Journal 原始字节和实验后事务计数，`source-sha256.txt` 为关键源码指纹。

仅证明所列历史事件和指定窗口，不证明完整旧数据库原地升级、旧程序读取新版本、跨 JVM guardian 自动接管或未知 B 追平。
