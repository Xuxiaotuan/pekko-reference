# Journal 许可身份绑定及重启组件验证

状态：**组件测试通过；不表示源 fencing 与恢复许可已端到端打通。** 本次修改 `BootstrapProgress` 追加可选 `recoveryPermit`，保持 `BootstrapJournal.Advanced` 事件类名与 persistence ID。PAUSED 或未初始化的 QUEUED 状态均须经当前 validator 才能绑定/重绑 permit；普通 Write 不得更改绑定。Kernel 对恢复回复丢失的读回要求 revision、execution、definition 与 permit ID 均匹配。

真实 MySQL 9.5.0 的 Actor Journal 单场景：[结果](result.json)、[运行日志](runner.log)、[源码摘要](source.sha256)、[实验清理](cleanup-lab.json)。实验先持久化 PAUSED，恢复 Actor，校验并绑定 `test-permit`，再重启，拒绝错误 permit，校验并重绑 `replacement-permit`，再次重启后仍读取到后者；Journal 序列为 1、2、3。validator 为测试注入函数，并未验证 guardian、Debezium、Sink、实际 D 或生产 v2 permit。

兼容测试使用已保存的 22 条真实旧 MySQL `BootstrapJournal.Advanced` CBOR，均成功反序列化为无 permit 的旧状态；还从这些旧事件中提取其未修改的旧 state 编码为独立 snapshot 形态，成功解码。旧 19 字段 continuation JSON 可读取，新 JSON 包含 permit 时可往返。该 snapshot 样本由旧事件内容提取，不是从历史 snapshot 表读取的原始 snapshot bytes；它不能代表所有历史版本。

内存 Journal 新增断言在修改前以 `None was not equal to Some("permit-1")` 失败，修改后通过。兼容测试初次编译因缺少 spray-json 字符串类型导入失败，补齐导入后通过；这两次命令的原始日志未归档，不能将本说明当作独立可复算的失败日志。

本次没有验证源 fence 在 Journal 异步校验及持久化之间始终有效，也没有真实 COMMIT 回复丢失、guardian、Reader 或 Sink 端到端故障实验。源/状态实例停止前均无用户连接和活跃事务，已停止并释放租约。
