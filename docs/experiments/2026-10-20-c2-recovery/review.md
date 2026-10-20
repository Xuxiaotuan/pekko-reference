# C2 审查与交付边界

以本轮改造前的工作区副本为基线审查，没有把之前未提交的 ExecutionPlan 边界改造归入 C2。检查结果与实际运行结果分开：

- **源码审查后已修正：**首次不可重试失败清除上下文；真实 Debezium 适配器在去敏时丢失安全分类；关闭失败覆盖主要 COMMIT 故障；关闭永不返回导致 Actor 无法暂停。
- **最终源码审查：**未发现额外生产阻断项。次数由持久化 AttemptStarted 累计；旧尝试回调隔离；实际 close 才释放注册表闸门；暂停保留上下文和主要故障。DefineWorkflow 在 currentExecution 存在时被 AlreadyRunning 拒绝，不会用新 revision 替换正在恢复的定义。
- **已知保守分类：**明确的 CheckpointRejected 仍可能归入 progress_outcome_unknown，耗尽预算后暂停，而非立即暴露协议冲突。拒绝不会推进 J/ACK；本轮没有顺手改变这个公共回复适配规则。
- **测试证据：**481 项后端断言；24 项显式真实 MySQL 运行；两个独立离线 verifier。源码审查本身不算运行证明。
- **测试等待例外：**默认 10 秒 ActorSystem 清理导致旧 ActorSpec 套件中止；仅测试 JVM 参数改为 60 秒后完整通过。默认配置未改，初次失败日志保留。
- **外部动作：**没有暂存、提交、推送、发布网站，也没有发送外部消息。

仍不代表 DDL、多 owner、共享读取、生产网络分区、日志清理或 70 万表已验证。新增 Journal 事件不保证旧二进制直接降级；已终态的历史 C2 不自动复活。完整材料见 README 和两个 result.json。
