# 持久化切换授权与运行时凭据重绑定

源码版本：工作区 `feature/pekkosync-mvp-cleanup-datafusion`，基线 `68b0a1d` 加本次未提交改动。

## 真实结果

| 场景 | 结果 | 主要证据 |
|---|---|---|
| `KILL_T_BEFORE_J` + 账号锁定/运行时重绑定 | passed | `real-kill-t-before-j-success/`，10 行、6 字段一致 |
| `KILL_J_AFTER_STORE` | passed | `kill_j_after_store/` |
| `FENCE_COMMIT_REPLY_LOST` | passed | `fence_commit_reply_lost/` |
| `STARTUP_COMMIT_REPLY_LOST` | passed | `startup_commit_reply_lost/` |
| `B_RECOVERY_CRASH` | passed | `b_recovery_crash/` |
| `DRAIN_COMMIT_REPLY_LOST` | passed | `drain_commit_reply_lost/` |
| `QUOTA_COMMIT_REPLY_LOST` | passed | `quota_commit_reply_lost/` |
| `REVOKE_COMMIT_REPLY_LOST` | passed | `revoke_commit_reply_lost/` |
| `OBSERVER_DENIED` | passed/refused safely | `observer_denied/` |
| `STALE_GRANT` | passed/refused safely | `stale_grant/` |

- `KILL_T_BEFORE_J`：真实旧 Workflow、独立 worker、Actor Journal、MySQL Sink。旧账号锁定并排空后，旧配额迁移，候选 worker 获得 permit，两个 ACTIVE credential binding 写入；Source/Sink 使用新运行时账号，恢复后源 10 行、目标 10 行、6 个投影字段逐字段一致。第一批 T 在恢复后以 `replay=true` 重新确认。
- `KILL_J_AFTER_STORE`：真实 Journal 重启窗口通过，源/目标逐字段一致。
- `FENCE_COMMIT_REPLY_LOST`、`STARTUP_COMMIT_REPLY_LOST`、`B_RECOVERY_CRASH`、`DRAIN_COMMIT_REPLY_LOST`、`QUOTA_COMMIT_REPLY_LOST`、`REVOKE_COMMIT_REPLY_LOST`：真实 MySQL/Journal/Sink 窗口通过当前矩阵断言。
- `OBSERVER_DENIED`、`STALE_GRANT`：真实拒绝和旧 grant 隔离路径通过，未将拒绝伪装成恢复成功。

证据目录分别位于本目录下的场景子目录；材料已去除实验密码。每个场景保留 worker 日志、T/J/D、owner/permit/audit、Sink 对账和清理记录。

## 代码结论

- 新增 additive cutover authorization 和 credential binding 表，不写入 Journal/Snapshot payload 或密码。
- 旧定义指纹使用原始 Workflow 节点；运行时账号替换只在 permit、owner、operation、endpoint、database、legacy username 和 definition hash 全部匹配时发生。
- 切换阶段只写授权；新 worker 获取 permit 后才激活 binding。旧 policy 在切换后拒绝旧入口，新 policy 可跨重启重建。
- MySQL observer 对空 `trx_mysql_thread_id` 保守记为未归属事务；`performance_schema` 不可观测时安全暂停。

## 仍未验证边界

- 本次真实 cutover 场景使用测试授权入口写入切换授权；独立 guardian 的签名 receipt 已由现有隔离矩阵验证，但尚未把生产 `SnapshotCutoverAuthorizer.authorize` 作为唯一入口接入外部 operator API。
- 仅覆盖单实例、单旧 owner、静态快照和 MySQL Sink；未扩展 DDL、多 owner、共享 CDC 或跨实例迁移。
- 10 个故障场景通过不等于全部 23 个历史场景都已重新执行；未执行场景保留原有材料和状态。

## 独立生产 GuardianMain 验证

`RealMySQLSupervisorMainSpec` 使用真实 `SnapshotGuardianMain`、`SnapshotSupervisorMain` 和固定 `PekkoServer` worker JVM，完成 snapshot 后重启 supervisor，completed rows 未被重写。该独立 Guardian/生产入口证据保存在 `real-independent-guardian-supervisor-main/`。它验证生产入口和 guardian 生命周期；旧账号切换的 `SnapshotCutoverAuthorizer` 仍需在 operator 调用路径中执行一次完整签名授权实验。

## 独立生产 GuardianMain 验证

`RealMySQLSupervisorMainSpec` 使用真实 `SnapshotGuardianMain`、`SnapshotSupervisorMain` 和固定 `PekkoServer` worker JVM，完成 snapshot 后重启 supervisor，completed rows 未被重写。该独立 Guardian/生产入口证据保存在 `real-independent-guardian-supervisor-main/`。它验证生产入口和 guardian 生命周期；旧账号切换的 `SnapshotCutoverAuthorizer` 仍需在 operator 调用路径中执行一次完整签名授权实验。
