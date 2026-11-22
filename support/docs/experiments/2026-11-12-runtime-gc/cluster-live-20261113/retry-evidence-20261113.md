# 集群重试证据（2026-11-13）

本文件补充 `cluster-live-20261113`，保留每次真实集群重试的独立结果，不把后续成功或清理动作覆盖成单一绿灯。

## 通过的窗口

- 镜像 `cluster-runtime-20261113d` 修复了 Reader/Target 对 MySQL `TEXT` 与 JDBC `LONGVARCHAR(-1)` 的比较后，任务 `cluster-acceptance-3` 完成全量初始化。
- 真实切点：`B=binlog.000017:324264418`，`F=binlog.000017:324266687`，`schema=5634ed2826896aadf59ccc64e3e564b98d108fddfa981b76f9f8dae0d26bb3c5`。
- 任务进入 `STREAMING`，Journal revision 到 10，目标表初始逐字段等于源表。

## 失败窗口

- `cluster-acceptance-1`：旧 State 连接路径失败后 `PAUSED/OWNER_RELEASE_UNPROVEN`。
- `cluster-acceptance-2`：Reader Schema 修复前，`source schema differs from sealed schema`。
- `cluster-acceptance-3` 增量阶段：目标 Sink 仍按 JDBC 整数严格比较，`TEXT (12) -> TEXT (-1)` 被拒绝；目标没有部分写入。
- `cluster-acceptance-4`、`cluster-acceptance-5`：Journal 仍使用旧 `DB_HOST` ClusterIP，State 连接探针显示 ClusterIP 20/20 失败，NodePort 20/20 成功，任务为 `transient_infrastructure`。
- `cluster-acceptance-6`：同表旧订阅仍然存在，按协议返回 `table already subscribed`；没有通过手工删除订阅伪造第二次订阅。

## 资源释放边界

- 多次滚动更新观察到旧 Pod 删除后 Source 仍短时间保留 `pekko_cdc_runtime` 连接和 State `cdc_control/cdc_sdk` 连接；未知 owner 时 Runtime 保持暂停。
- 已记录并清理隔离 acceptance 的残留连接和 owner 状态；旧业务资源未修改。
- acceptance Deployment 已改为 `Recreate`，避免新旧 Runtime 重叠启动。

## 当前结论

本轮已经证明全量初始化本身可以完成，也保留了三类实际阻塞：Reader Schema 映射、Sink Schema 映射、Journal 地址与旧订阅/旧连接生命周期。最终端到端增量对账需在清空隔离 acceptance State 后使用同一最终镜像重新跑；本文件中的失败材料不能被重跑覆盖。
