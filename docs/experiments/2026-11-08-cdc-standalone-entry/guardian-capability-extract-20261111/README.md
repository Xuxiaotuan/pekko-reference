# Guardian capability 从旧调度包拆分（2026-11-11）

未提交工作区。guardian 的原始子进程句柄、签名服务和受限控制视图迁入 `cdc.guardian`；真实控制存储创建适配器时仍核验 owner 与 guardian 使用同一事务上下文，原 `attach` 和 capability 绑定方法仍限于旧包。该拆分没有重写控制存储，也没有启用未知 B 自动恢复。

`component-tests.log` 为初次拆分的 41 项组件测试通过；`c1-failed.log` 与 `C1-fd3b19a277bd/` 为该版本的独立 guardian、supervisor、CDC worker、双 MySQL 和目标表实验。它在旧 worker 隔离后等待 STREAMING 超时：PAUSED、UNKNOWN grant，源 64 行、目标 0 行。`cleanup.json` 报告隔离排空和会话清理成功；状态表、J/L/D、逐字段对账及签名证明均保留。两台测试 MySQL 的最终事务数和会话见 `*-final-state.tsv`，实例已关闭。

C1 后将 takeover coordinator 独立成文件，未改变其逻辑；`component-final.log` 为最终拆分版本的 41 项组件测试，源码指纹在 `source-final-sha256.txt`。C1 对应 `source-sha256.txt` 和实验目录中的完整源码清单，**未在最终文件拆分版本重跑**。结果不证明未知 B 自动追平、其他故障窗口或 70 万表容量。

最终还将无生产调用的旧 `SnapshotTakeoverCoordinator` 移入测试源集，保留探针；`component-test-scope.log` 中 41 项相关测试通过，`source-test-scope-sha256.txt` 记录该版文件。C1 未在该版重跑。
