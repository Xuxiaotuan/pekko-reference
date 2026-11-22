# 关闭守卫与 INNODB_TRX 观察实验

范围：专用本机 MySQL 9.5.0 state 实例，Connector/J 9.1.0，Java 17。表和账号属于本实验；未运行源端 fencing，也未更改 owner 状态或 CDC 恢复协议。下面是运行时观察，不是所有 MySQL 版本或负载的保证。

## 实验发现

应用的带探针第 2 次运行中，连接 2392、PFS thread 2506、事务 event 6 的 history SELECT 和后续 ROLLBACK 均有 PFS 记录；JDBC close 在 11:58:35.516 开始、35.517 返回。此后 `INNODB_TRX` 行在多个采样点仍显示，到 20 秒采样消失；该轮 owner 为 UNKNOWN，下一次启动安全暂停。代理覆盖 SDK 账号的全部连接，改变了 JDBC 对象身份和驱动注册顺序，输出日志也可能影响时序；不能以此声称无探针生产环境已得到同样结果。原始应用日志、PFS 采样和代理记录存放于本机私有实验目录，应用结果见上级目录的 `shutdown-runtime11-runtime11-20261108.json`。

为检验缓存假设，在没有 CDC JVM 或其他业务连接的同一专用实例中，建立真实 JDBC 非自动提交连接并读取表，查询 PFS 确认事务后关闭连接；分别以一个自动提交 observer 约 20 ms 或 300 ms 间隔读取 `INNODB_TRX`，停止查询 600 ms 后用新连接复查。PFS 通过 server、connection、thread、event 及 SQL nesting 关联，不能仅依靠 `trx_id`；实验中不同连接复用了同一个该字段值。

- 20 ms 两次：close 返回后分别 58 和 60 次采样仍显示旧行，约持续 1.49 秒；静默 600 ms 后新连接不再显示。PFS 均记录 ROLLBACK，同一 thread 的 data_locks 为空。
- 300 ms 两次：第一次采样仍显示旧行，第二次采样已消失；静默后新连接亦为空。
- v1 的首个高频样本复现，但其他组在关闭前没有观察到目标行，不能支持组间比较。其原始文件保留；v2 加入启动前静默，确保各组均先观察到目标事务。

v2 与 MySQL 9.5.0 对应源码中的事务信息缓存行为一致，见 [trx0i_s.cc](https://github.com/mysql/mysql-server/blob/mysql-9.5.0/storage/innobase/trx/trx0i_s.cc)。受控实验支持“高频读取可以延长旧行可见时间”，但不单独证明每次生产 UNKNOWN 均由此造成，也不证明改变采样间隔足以覆盖所有未排空事务。

## 材料

- [受控实验 v1 原始记录](../innodb-trx-cache-control-v1-20261108.tsv)；[v2 原始记录](../innodb-trx-cache-control-v2-20261108.tsv)。每行保留单调时间、毫秒时间和动作；值不包含凭据。
- [实验 Java 代码](CacheProbe.java)：仅供隔离实例运行，使用环境变量接收凭据；脚本中的实例、库和表均为本次实验值，不能直接用于其他环境。
- [无代理对照](../shutdown-runtime10-runtime10-20261108.json)：同库 3 轮均 STREAMING、初始化与增删改后逐字段一致，结束时 STOPPED。
- [带代理与双观察者对照](../shutdown-runtime12-runtime12-20261108.json)：同库 3 轮对账一致且结束时 STOPPED，未复现 UNKNOWN；比较观察者数据及完整运行日志在本机私有目录。
- [观察权限错误的对照](../shutdown-runtime8-runtime8-20261108.json)：旧库观察账号无法读取新实验库，三轮对账均为证据不完整，保留失败结果。

受控实验的执行前后均需确认实例归属、连接和事务清零；PFS 历史有容量限制，缓存读取也会改变观察结果。上述材料没有覆盖 DDL、多 owner 接管、持续大负载或线上服务。

## 调整守卫间隔后的真实库回归

冻结包 application jar SHA-256 `1e3d41c63cfa81af28bdc678239e7bdec547492e095a1162883853588b2af744`，无 JDBC 代理，5 次同库重启均进入 ACTIVE、结束为 STOPPED，终态事务数均为 0；每次启动初始数据逐字段相等。前 4 轮增删改后对账相等，第 5 轮在 45 秒等待内不相等，故**不能将五轮端到端回归记为通过**。该轮源侧变化已进入 L 的 GROUP 35、36、37；任务最终状态为 PAUSED，失败码 `automatic_recovery_exhausted`；目标账本未新增对应组。原因及后续自动恢复是否能追平仍需验证。实际 T/J/D 和采样时序见 [runtime13 报告](../shutdown-runtime13-runtime13-20261108.json)；报告中的 T 为目标组账本记录，J 为 Journal 记录数和最大 ordering，D 为采样到的源偏移值，三者计数或前进不能单独证明端到端提交语义。

### 第 5 轮失败的只读核对

检查时间以现场文件和报告为准。测试表源端为 `(1, round5, 85)`、`(7, new, 95)`，目标仍为 `(1, round4, 84)`、`(6, new, 94)`。L 中 GROUP 35/36/37 分别包含该轮更新、删除和插入；订阅为 CAPTURE_READY，consumer `observed_floor=31`，任务 `delta_after=31`、`wake_seq=37`、`phase=PAUSED`、`failure_code=automatic_recovery_exhausted`。捕获日志中该轮 ACK 延续至 seq 49；采样 D 为 binlog.000009:26737（GTID 1–707），目标组账本 12 组、Journal ordering 17。源数据已捕获至 L，但当时尚未写入目标；这组观察本身不能证明永久丢失或恢复后必然一致。

对 Journal 中 CBOR 事件的只读解析结果：revision 9/10 的 `recoveryAttempts=0`，11/12 为 1，13/14 为 2，15/16 为 3；11–16 的 failure 为 `execution_interrupted`，17 为 3 且 `automatic_recovery_exhausted`。结合 [BootstrapRunner](../../../../server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapRunner.scala)、[CdcTaskStore](../../../../server/src/main/scala/cn/xuyinyin/magic/workflow/cdc/CdcTaskStore.scala) 和 [BootstrapJournal](../../../../server/src/main/scala/cn/xuyinyin/magic/workflow/bootstrap/BootstrapJournal.scala) 的当前源码，正常重启更换 owner 时，已有 continuation 不会直接交给不同 owner，执行路径会累计恢复次数，达到上限后暂停。这是本次样本的源码与 Journal 联合判断，不能推广为任意暂停的原因。这里只记录问题，未修改恢复协议或重置实验状态。

私有原始证据位于 `~/.codex/experiments/2026-11-08-cdc-standalone-entry/`：`runtime13-final-readonly-state-20261108.tsv` 保存 L、订阅、consumer、任务、目标组账本、Journal 原始 CBOR 的十六进制与 D；`jdbc-close-cache-raw-20261108.tar.gz` 保存进程日志、PFS 与双 observer 采样。文件权限为 0600，不含账号密码。运行库 UUID 与候选制品指纹见相邻报告和本节上方；CBOR 解码环境及其版本如未另行固定，应保留该局限。

实验结束前两台专用实例均检查为 0 事务、无其他客户端；已按池租约停止 source 25821 和 state 25822，确认端口关闭，恢复 state 的原始 MySQL 配置并按字节比较一致，然后释放池租约。实验仅操作本实验池，未停止或更改其他服务；其他服务的业务健康状态未在本次实验中检查。
