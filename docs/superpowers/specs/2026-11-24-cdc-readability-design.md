# CDC Runtime 可读性重构设计

## 目标

在不改变 LOW/HIGH/F、T/J/D、Journal 序列化、persistenceId、数据库表结构和恢复协议的前提下，让 CDC 主流程可以沿着 Runtime、Capture、Table Session、Snapshot、Streaming、GC 六个边界阅读。

## 方案

保留现有实现作为行为基线，先提取装配和生命周期协作者，减少 `CdcBootstrapKernel`、`TableCdcGraphRuntime`、`BootstrapRunner` 和 `SharedMySQLCapture` 中的职责交叉。Graph 仍使用当前 materialized graph，LOW/HIGH 仍由 `BootstrapRunner` 实现。

## 非目标

- 不改变恢复语义和持久化兼容。
- 不重写 LOW/HIGH、ACK、GC 或 source fence 算法。
- 不删除已验证实验材料。
- 不引入新的运行时组件或依赖。

## 结构边界

```text
CdcRuntimeMain
  -> CdcRuntime
  -> CdcPlatformRuntime
  -> CdcBootstrapKernel
      -> CaptureRegistry
      -> TableSessionFactory
      -> TableRuntimeController
      -> RecoveryCoordinator
      -> GcCoordinator
```

现阶段先建立薄协作者和类型化生命周期边界，内部仍委托现有生产实现。当前已落地 `TableGraphController` 和 `CdcJdbcConnections`。后续可以在不改变入口的情况下继续拆分 `BootstrapRunner` 和 `SharedMySQLCapture`。

## 验收

- `pekko-server/Test/test` 通过。
- 现有 Graph、恢复和 GC 测试不退化。
- 旧 persistence 样本和实验材料路径不变。
- 主流程可以从 `CdcRuntimeMain` 追到 Table Graph，而无需阅读 Workflow 兼容路径。
