# Guardian owner 契约解耦（2026-11-11）

未提交工作区的源码指纹见 `source-sha256.txt`。MySQL 排空观察和数据库身份查询迁到 CDC guardian。owner 生命周期接口仍留在旧包：中间版本直接公开该接口会扩大可实现范围，已撤回；supervisor 仍要求同一事务上下文。进程隔离 capability 和证明签发未改。

- `component-tests.log`：关联测试 39 项通过，覆盖 owner 生命周期、supervisor 和 Guardian 线协议；不是完整 CDC 主流程。
- `mysql-drain-attempt-1-failed.log`：首次真实 MySQL 9.5.0 测试失败，5 秒窗口末 MySQL 仍有 thread 11 的未归属 RUNNING 事务；账号会话列表为空。随后单独查询时该事务已不在 `innodb_trx` 中。事务具体归属及消失时间未能确认。
- `mysql-drain-attempt-2-passed.log`：中间版本第二次测试中，外来/无标签物料账号连接阻止空排空判断，关闭后观察到空；一次通过不抹去第一次失败或证明关闭耗时稳定。
- `mysql-drain-final.log`：恢复原接口可信边界后的最终版本重跑通过，观察到外来/无标签账号阻止排空，关闭后为空。
- `mysql-final-state.tsv`：实验后实例版本、活动事务与会话查询。

实验未覆盖受管 guardian 进程隔离、实际接管、CDC Source/Sink 或逐字段对账，不得推断端到端可靠性。
