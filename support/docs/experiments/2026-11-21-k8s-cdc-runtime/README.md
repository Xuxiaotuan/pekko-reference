# 2026-11-21 CDC runtime rollout evidence

本目录保留两类证据。

- `2026-11-21-runtime-schema-version-conflict.log` 记录第一次部署失败。原因是共享 schema version 表中版本 3 已经属于历史 `cdc-task-store-v3`，新租约迁移错误复用了版本 3，Capture 因 checksum 冲突拒绝启动。
- `2026-11-21-before-rollout-interrupt-*` 记录旧 Capture/Worker Pod 长时间 Terminating 时的进程、Pod、Deployment、租约和数据库 owner/grant 状态。旧 JVM 已确认属于 CDC runtime，随后才终止并清理旧 Pod。

这批材料只证明发布过程和失败边界，不证明 Sink 端到端一致性。最终成功发布仍需重新构建、rollout、schema version=5 检查，并另跑真实 MySQL、Journal、Sink 对账。
