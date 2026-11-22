# CDC 控制实现脱离 Workflow 包

本次把事务锁、owner 登记、guardian capability、隔离与排空记录、切换授权、配额以及 supervisor 实现从 `workflow.scheduling` 迁入 `cdc.control`。原来的包内访问限制对应收敛为 `private[control]` / `protected[control]`，没有开放任意调用方构造控制能力。guardian 和 supervisor 的历史 main 类名由 `cdc/compat/LegacySupervisorEntrypoints.scala` 转发。调度持久化模型移到 compat 目录，但保留原包名、类名和字段；数据库表名、锁行、SQL、persistenceId 均未变更。

## 本轮实际结果

- 生产与测试编译通过，控制相关本地测试 87 项通过，2 项因未配置外部环境取消。见 `compile-4.log`。
- MySQL 控制锁实验在全新 `pekko_test_control_move_20261111b` 中通过：新旧控制入口串行，UNKNOWN 配额在 drain 前保留、drain 后释放，旧准入策略拒绝继续进入。见 `mysql-fresh-schema.log`。
- 首次误用了已有 `pekko_test_control_move_20261111`，其中已有 `policy_hash=cutover-blocked`，因此锁测试无法进入，1 项失败。未清除旧库或改动屏障使其通过；原日志见 `mysql-run.log`。随后给测试设施增加空库前置检查，并使用另一全新库重跑通过，见 `mysql-empty-guard.log`。
- 同次 MySQL 切换授权元组写入及重读测试通过，见 `mysql-run.log` 的 CdcCutoverStoreMySqlSpec。
- C0 使用独立 guardian、supervisor、worker JVM、真实 MySQL Source、JDBC Actor Journal 与 MySQL Sink。旧 worker 被终止并由 replacement 接管，再写入变更后 64 行、384 字段一致，缺行、额外行及字段差异均为空。`C0-d865987de1f3/02-recovered.json` 保存 T/J/L/D 和数据，`cleanup.json` 确认 guardian 已退出、会话释放与排空成立。见 `managed-c0.log`。
- C1 沿用历史自动恢复测试，其等待 STREAMING 的断言超时失败。`C1-eeb38b0a8f6c/02-isolated.json` 保留 PAUSED、UNKNOWN grant 和 UNKNOWN 捕获就绪证据；这是当前保留的未知 B 安全暂停边界，不能称为恢复及逐字段对账通过。其 `cleanup.json` 确认隔离、排空及会话释放成立，失败日志完整保留在 `managed-c1.log`。
- 删除生产 supervisor 中仅抛异常的 `tableStore` 占位接口。CDC 探针直接使用 owner registry；历史 Workflow 探针的旧 Store 装配迁入测试设施。随后测试编译与 supervisor store 的 25 项检查通过，见 `final-compile.log`。

上述是包迁移及固定故障窗口证据，不涵盖 guardian 自身重启后的 systemd 隔离，也不证明未知 B 自动恢复、L 实际安全删除或 70 万活跃表容量。Journal 旧 Actor receipt 与旧执行器仍需继续拆分；生产 workflow 目录剩余 18 个 Scala 文件，不能称为完整交付。
