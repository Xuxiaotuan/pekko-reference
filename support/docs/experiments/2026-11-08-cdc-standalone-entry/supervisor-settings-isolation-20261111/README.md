# CDC supervisor 设置与入口分离（2026-11-11）

未提交的源码指纹见 `source-sha256.txt`，完整生产与实验源码清单位于 C0 子目录。CDC supervisor 的配置校验、worker 启动参数、隔离账号清单和数据库排空观察配置移至 `cdc.guardian.SnapshotSupervisorSettings`；旧 guardian/supervisor main 类名仍保留。控制存储和部分旧 Workflow 类型仍被该设置类引用，不能声称控制存储已完全解耦。

首次测试编译因一个测试源码保留旧 import 失败，见 `initial-compile-failed.log`；修复该 import 后，`component-tests.log` 的 54 项测试通过。

`C0-ea48fc141b6e` 与 `c0.log`：使用独立 guardian、supervisor、CDC worker、两个 MySQL 9.5.0、真实 Debezium、Journal 和 Sink；任务进入 STREAMING 后终止原 worker，新 worker 接管后更新源表，最终源目标各 64 行、384 字段一致，目标增量 T 一组，J 21、L 27；隔离证明、T/J/L/D、对账、进程及排空记录在实验目录中。清理成功；两个专用实例最终事务数均为 0，随后关闭。

本实验不覆盖初始化中途、事务在途、未知 B 自动恢复、旧数据库原地升级及 70 万表规模。未知 B 继续安全暂停，既有 C1 失败证据未删除。
