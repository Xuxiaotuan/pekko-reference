# LG01—LG16 严格串行重跑

本目录是 2026-11-12 在专用双 MySQL（Source `25821`、State `25832`）上重新执行的最终矩阵。运行前恢复 `CaptureGcMatrixProvision` 的严格账号门：State 库客户端账号集合必须恰为 `root@localhost`；所有 17 个 case 串行执行，没有与 Runtime suite 并行。

- 命令：`RUN_CAPTURE_GC_MATRIX=1 CDC_LAB_EXCLUSIVE_LEASE=capture-gc ... testOnly cn.xuyinyin.magic.workflow.integration.CaptureGcMatrixSpec`
- 结果：LG01—LG16 与 LG16-capacity **17/17 通过**。
- 每个 case 保留 `initial.log`、`final.json`、`acceptance.json`、T/J/L/D、资源采样和逐字段对账；容量用例按预期安全暂停，未伪造收敛。
- 先前 `final/verified3` 在并行污染下通过的材料继续保留，不能与本目录混称为同一运行；这次严格重跑才是账号清点门恢复后的有效矩阵证据。

这证明的是受保护前缀的逻辑删除、重启读取和慢表/容量边界。它不证明 InnoDB 文件物理缩容、DDL、多 owner 自动接管、70 万真实活跃表或长期吞吐。
