# Journal receipt 与 CDC Source SPI 拆分验证

2026-11-11，将旧 Actor receipt 的 ACK 分发接口移到 Workflow 兼容执行层；CDC Source SPI 不再引用旧 Actor。receipt 仍由旧 Actor 在持久化回调内构造，捕获日志保留位置推进前仍在同一数据库事务中锁定和核验 Journal 事件；未改变 receipt 构造权限或放宽核验。

组件测试使用模拟的目标提交和 checkpoint 回调，验证普通 Source 与 receipt-aware 旧 Source 的 ACK 分发顺序；真实 Journal 的核验单独见下述实验。另读取[历史 MySQL 实验保存的实际旧 Journal 事件](../../2026-10-25-capture-startup/R02-original-trigger-eaa005be087a/final.json)，核对迁包后的 checkpoint 字段；该样本不是本次新生成的事件。

OrbStack 当时未运行；本次使用独立临时数据目录、仅绑定本机端口的 MySQL 9.5.0 运行 `WorkflowJournalFloorMySqlSpec`。真实 Actor Journal 产生 receipt 后，测试验证匹配事件可推进保留位置，错误身份、过期及删除事件被拒绝，核验事务锁阻止后续写入；见 [mysql-test.log](mysql-test.log) 和 [journal-floor-log.tsv](journal-floor-log.tsv)。测试库事务及连接数为 0，见 [server-and-cleanup.tsv](server-and-cleanup.tsv)，随后临时实例正常关闭。[源码散列](source-sha256.txt)仅覆盖部分关键文件，工作区未提交。

该测试使用构造的捕获数据及测试适配器，没有真实 binlog Reader、业务 Sink 或端到端逐字段对账。MySQL 9.5 的结果不能替代 MySQL 8.0 在本次代码上的回归；旧 Actor、存储与 guardian 仍未全部解耦。
