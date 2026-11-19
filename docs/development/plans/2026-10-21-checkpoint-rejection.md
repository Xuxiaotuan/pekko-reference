# Checkpoint 明确拒绝的最小修复

用户已授权实施并回归真实 MySQL；不提交、不推送。保留现有全部未提交改动和旧证据。

1. Actor 的 CheckpointRejected 转为已有 CdcExecutionException / protocol_conflict；初始化的同类明确拒绝同样处理。
2. Engine 在 J 与 ACK 回调边界保留已有类型化失败；未类型化异常（包括 ask/回复超时）仍为 progress_outcome_unknown。不可按异常文案猜测是否拒绝。
3. 补充 Actor 拒绝后预算不增长、上下文跨重启保留、AlreadyStored 成功，以及 Engine 分类与不越过 ACK 的测试。先观察失败再修复。
4. 真实 Source/Journal/Sink 在 T 提交后，由探针向真实 Actor 提交合法身份但跳号的 Checkpoint：初次执行明确拒绝、已有一次恢复后明确拒绝。固定闸门，检查拒绝由真实 Actor 返回、J/D 与 ACK 不推进、不额外消费预算、重启仍暂停。
5. 回归原 24 项，加两项新拒绝场景，共 26 项。保留 T/J/D、原始回调及 ACK、六字段完整行、审计和数据库 dump；独立离线核对。新材料单独目录，旧材料逐个哈希不变。
6. 后端测试与打包，检查差异和运行源码摘要；区分自动收敛与安全暂停，不把暂停计为恢复成功。
