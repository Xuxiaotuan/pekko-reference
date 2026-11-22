# guardian 通信迁包验证与旧测试失败

2026-11-10，`SnapshotGuardianWire` 从旧 Workflow 包迁至 `cdc.guardian`。干净编译及 guardian、supervisor store、Split 兼容共 39 项组件测试通过；这些测试不能替代独立 guardian JVM 的故障接管实验。

在专用 MySQL 容器中尝试运行 `RealMySQLSupervisorMainSpec`，该旧测试在构造 supervisor 配置时失败，错误为 `Workflow workers are not part of the CDC runtime`，尚未启动 guardian、supervisor 或 worker。原因是该历史测试仍请求旧 Workflow worker，而当前独立 CDC 入口显式拒绝这种启动方式；失败不证明 CDC guardian 通信失败，也不证明其可用。完整输出见 [legacy-supervisor-failure.log](legacy-supervisor-failure.log)。

测试 JVM 结束后确认事务和测试库连接均为 0，见 [server-and-cleanup.tsv](server-and-cleanup.tsv)，随后停止隔离容器。未绕过入口校验。仍需使用当前受管 CDC 入口及其依赖的独立 Source/State MySQL 运行真实 guardian、Journal、Sink 故障实验；当前工作区代码未提交。
