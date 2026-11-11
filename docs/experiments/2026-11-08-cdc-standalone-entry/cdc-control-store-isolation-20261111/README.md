# CDC 控制存储类型拆分（2026-11-11）

工作区关键源码指纹见 `source-sha256.txt`。CDC 控制存储类型移到 `cdc.control`；旧 owner 与配额适配器仍在旧包，构造通过原包内工厂，事务和 SQL 仍复用原实现及原 `pekko_table_admission_lock`。这不是控制存储完全摆脱旧 Workflow 的证明。

- `component-tests.log`：57 项组件测试通过。
- `mysql-tests.log`、`cutover-result.json`：隔离 MySQL 9.5.0 中两项控制存储测试通过，覆盖新旧锁串行、旧配额排空前保留和排空后释放，以及合成切换授权和许可绑定的幂等重读/冲突拒绝。合成许可不是 guardian 证明。
- `C0-0808b414ee69`：独立 guardian、supervisor、CDC worker 与双 MySQL；任务进入 STREAMING 后终止原 worker，替代 worker 接管并消费新源变更。最终源目标各 64 行、384 字段一致，T 1、J 21、L 27；隔离证明、清理、D 与完整对账记录均在目录中。两台实验实例最终事务和连接见状态文件，随后关闭。

实验不覆盖未知 B 自动恢复、所有失败窗口或旧库原地升级；未知 B 继续安全暂停。此前 C1 失败材料保留在 `guardian-capability-extract-20261111`。

后续审查发现原迁移工厂接受任意 `ManagedControlTransactions` 子类，可能绕过 CDC 构造策略。已收紧为只接受 `CdcManagedControlStore`，配额页上限只能取该存储的 `limits`；`factory-guard-tests.log` 的编译期拒绝测试及 25 项 supervisor 测试通过。最终版源码指纹在 `source-tight-sha256.txt`。

收紧后重跑 `mysql-tight-tests.log`：新旧控制锁、旧配额和合成许可绑定两项通过；`C0-6784829b61f2` 的真实独立进程接管后应用新增变更，源目标 64 行、384 字段一致，T 1、J 21、L 27，清理成功。旧版 `C0-0808b414ee69` 和原源码指纹保留；未知 B C1 未在此最终版重跑。
