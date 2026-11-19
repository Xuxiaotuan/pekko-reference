# 恢复许可的实际使用边界

状态：**源码阅读与既有组件实验证据；不是新协议实现或端到端验证。** 基准 `68b0a1d20d8ccdb05fb244c58ba87dfe09d128e7`。本记录不修改原有证明与失败材料。

1. `CdcManagedRecovery.recover` 在 guardian/排空证明后调用 `CdcManagedPendingB.verify`，再调用状态库的 `releaseForPendingB` 持久化许可。源侧与状态侧并非同一事务。
2. `SharedMySQLCapture.start` 使用 `authorizedBPermits` 判定 pending B 可恢复；在此之后才创建并启动引擎，再通过 `writeMarker` 写入源控制表。`writeControl` 为此打开独立源连接和事务。
3. `CdcBootstrapKernel.create` 向 Journal 提供 `authorizedBPermit` 校验。`run` 对 PAUSED Journal 调用 `resumePendingB`，其校验与 Journal 持久化由异步消息衔接；已是 QUEUED 的状态根据 Journal 状态判断是否恢复，然后调用 `consumeBPermit`，后者仍会检查状态库中的许可、owner 和 grant。当前这些接口均未绑定源版本。
4. 把版本行锁持有到 Reader 的 `writeMarker` 前，并让 marker 触发器更新同一版本行时，当前独立源连接会发生锁等待，不能简单扩大锁生命周期。该推演已由[真实 Reader 探针](../2026-11-07-marker-version-reentry/README.md)复现，但该探针不含 guardian、Journal、Sink 和版本化许可。
5. 版本行锁释放后源码仍需完成许可使用、Journal 和控制写入；[组件反例](result.json)显示测试许可在源版本改变后仍被当前授权方法接受。该样本没有真实 guardian、Journal、D 或 Sink。

定向基线：本轮运行 `CdcTaskStoreSpec` 与 `CaptureAckProofSpec`，11 项通过。它们不验证版本许可新协议。生产改造需将版本、源身份及许可状态的校验绑定到每个真正有副作用的路径，并设计锁释放、锁连接失效及 marker 重入顺序；否则不能把仅增加许可字段或单次检查称为消除竞态。
