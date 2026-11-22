# 旧 Workflow 事件类名迁移验证（2026-11-11）

这组证据对应未提交工作区，具体源码见 `source-sha256.txt`。迁移把 Journal 事件模型放入 `cdc.compat`，通过 JacksonMigration 读取旧 manifest；checkpoint receipt 仍由旧 Actor 持久化成功后签发，Journal 校验仍在同一 MySQL 事务持锁完成。未知 B 的安全暂停边界没有改变。

- `compat-tests.log`：clean compile、测试编译及 55 项关联测试通过。其中 18 种旧 manifest 的 CBOR 样本是测试生成的，另有 4 条来自既有真实 MySQL Journal 实验的旧事件做了解码；这不是旧数据库完整 Actor 重启实验。
- `mysql-journal-floor.log`：独立 MySQL 9.5.0 中，当前 Actor 写入 Journal 后执行 receipt 核验；错误身份、过期和删除事件被拒绝，后继写入被事务锁阻塞。测试使用独立实验数据库，并非全量 CDC Source 到 Sink 对账。
- `mysql-old-actor-replay.log`、`mysql-old-replay-rows.tsv`：将上述 4 条历史事件的原始字节和 manifest 插入隔离 MySQL，启动当前 Actor，观察到 boundary 和指定 checkpoint 恢复。该实验只覆盖这一个历史序列，不是原数据库原地升级或端到端接管。
- `mysql-journal-rows.tsv`：测试结束后采集的服务器版本、UUID、活动事务数和 Journal 原始 payload；事件按测试逻辑可能被标记删除。

尚未验证完整历史数据库的原地升级、其他历史状态的 Actor 恢复、旧程序读取新 manifest 的降级兼容，以及本次变更后的完整自动接管或全量逐字段一致性。旧事件解码和真实数据库锁测试不能证明上述行为。
