# Journal SQL 证明与 Actor receipt 分离（2026-11-11）

源码指纹见 `source-sha256.txt`。SQL 核验组件只在调用方事务中验证数据库身份、事件和后继范围锁，不能推进 L；外层仍校验旧 Actor 私有构造的 receipt 及业务身份。此改动没有移除生产层对旧 Actor 类型的依赖。

`compat-tests.log` 的历史序列化样本测试 3 项通过。`mysql-tests.log` 是隔离 MySQL 9.5.0 的两项实验：将 4 条历史 MySQL Journal 事件字节导入新库后重放 checkpoint；真实 Actor 签发 receipt 后测试同事务锁及错误身份、过期、删除事件的拒绝。`journal-rows.tsv` 保留测试 Journal 原始字节和事务查询。

不是旧数据库原地升级，也不是完整 Source、guardian、Sink 的故障接管或全量对账。未知 B 的安全暂停不变。
