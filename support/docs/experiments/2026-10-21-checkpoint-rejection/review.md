# Checkpoint 明确拒绝分类修复的审查

以 `/tmp/pekko-checkpoint-rejection-20261021/before` 的本轮起始工作区为基线；此前未提交的 ExecutionPlan 和 C2 改造属于已有改动，没有归入本轮修复。运行前后源码摘要和六个源码文件的变化摘要随证据保存。

- 两个生产文件修改：Actor 将真实 `CheckpointRejected` 转为已有 `CdcExecutionException(protocol_conflict)`；Engine 的 J/ACK 边界保留类型化失败。没有新增失败代码、持久化事件、重试层或预算规则。
- 分类依赖真实回复类型，不匹配异常文案。`TimeoutException` 以及带有“checkpoint rejected”字样的普通异常仍表示结果不确定。`CheckpointAccepted` 与 `CheckpointAlreadyStored` 继续成功。
- 原有 Controller 先确认本地释放，再决定恢复。明确拒绝进入既有 Blocked 持久化分支，不新增 Scheduled/AttemptStarted；已有消耗不退款、不清零。不清除 currentExecution、revision、边界及 J。
- T→J→Source ACK 顺序保留；J 拒绝后不会执行该交付的 Source ACK。若本地释放也不能确认，原有安全暂停仍优先，主要协议冲突描述保留。
- 两个真实负例由设施在真实 T 提交后提交“身份有效、交付序号跳号”的 Checkpoint，调用真实 Actor 判断；没有伪造拒绝、J 成功、D 或业务结果。
- 只读复核未发现新增可操作问题；复核本身没有运行测试或数据库，不能当作实验通过证据。实际测试与独立证据重算见 README、两个 result.json 及 evidence.tar.gz。

本轮没有修改恢复控制器、Source 组装配、目标组账本、DDL、多 owner 或共享读取；没有暂存、提交、推送和发布。
