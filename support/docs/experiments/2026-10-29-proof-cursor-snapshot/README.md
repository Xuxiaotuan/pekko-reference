# MySQL 快照失效后的游标反例

2026-10-29 在专用隔离 MySQL（UUID 和端口见 [result.json](result.json)）执行：控制表初始包含 `b`、`d`，连接 A 在 `REPEATABLE READ` 只读事务内读取第一页 `b`；连接 B 提交插入 `a`；A 原快照在 `b` 后读取到 `d`。A 结束后，新连接全表读取到 `a,b,d`，从保存的游标 `b` 续读仅得到 `d`，漏掉 `a`。

这是对数据库快照与游标语义的一次真实反例，**不是项目恢复路径或端到端实验**。它说明跨连接续用游标必须额外证明被扫描集合没有变化；不能把持久化游标本身当作证明。实验未删除专用测试 schema，也未修改生产恢复协议。

另在同一个专用 schema 测试了在 marker 表上增加 AFTER INSERT 触发器，向第二张目录表写入一行。对该事务位置区间运行 `mysqlbinlog --base64-output=DECODE-ROWS --verbose`，观察到两条 INSERT 行事件，见 [trigger-binlog.json](trigger-binlog.json)。当前 Reader 对控制 marker 的事务元数据要求 `event_count == 1`；因此目录同事务写入可能触及这一约束，不能在未验证 Debezium 实际事务元数据和 Reader 行为前直接安装触发器。这个 binlog 样本本身没有运行 Debezium，也没有证明 Reader 一定失败。
