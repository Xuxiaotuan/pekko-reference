# guardian OS 材料提交前复核

本目录追加记录2026-10-25的提交前检查，不覆盖2026-10-24冻结矩阵与旧失败材料。生产代码、原测试与运行配置均未修改；本轮没有重新运行真实MySQL矩阵。

原两组证据归档的离线校验通过；同一最终版本仍是 `r-1792851860999716000`，12项中8项收敛、4项安全暂停。完整材料见 [同版矩阵报告](../2026-10-24-guardian-os-final-matrix/README.md)。本次追加核验的实际结果、命令和退出码见 [result.json](result.json)。

首次后端全量回归为585通过、1失败、111个suite完成、0中止。失败是 `WorkflowExecutionEngineSpec` 的 `release an old execution before restarting the same durable execution ID`，第805行观察到 `close-1` 的索引2不小于 `open-2` 的索引1。该命令随后串接的package没有执行。原始失败日志完整保留，没有用后续结果覆盖。

该suite在独立SBT进程复核为38/38通过。只读检查发现测试 `CancellationSource` 用 `termination.onComplete(...)(blockingEc)` 异步记录close事件，而它继承的默认 `openBatches` 返回已完成的close future；测试没有把观察回调的执行纳入资源关闭确认。因此“观察回调调度顺序导致断言不稳定”是有源码依据的假设，**尚未通过固定窗口探针证明，不是已修复结论**。本次没有修改测试或恢复协议，没有把close日志的先后直接当作真实数据库资源重叠的证据。

随后再执行一轮原生后端全量命令确认复现情况，结果同样单列于result.json。即使该轮通过，也不证明此顺序断言已经稳定；诊断状态保留 `evidence_incomplete`。

原始三次SBT日志、相关源码与SHA256、提交前离线校验摘要放在 `evidence.tar.gz`，摘要与文件散列见result.json及SHA256SUMS。只有测试设施和实验材料进入本次提交；私有机器导出、真实密码和私钥仍在仓库外。
