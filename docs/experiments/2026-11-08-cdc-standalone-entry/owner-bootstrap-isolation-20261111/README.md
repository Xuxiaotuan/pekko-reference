# CDC owner bootstrap 拆分（2026-11-11）

未提交代码关键指纹见 `source-sha256.txt`，完整生产和测试源码清单位于 C0 子目录。CDC-only owner bootstrap 已移至 `cdc.guardian`；旧存储适配器在安装 owner 上下文后将控制连接登记绑定到原存储与原 owner，登记仍在同一物理连接中执行。旧 JDBC 适配器、控制存储及部分 Workflow 执行路径仍存在。

`component-tests.log`：57 项 guardian、supervisor、owner 及 CDC 停止测试通过。`C0-54be55962cd7`：独立 guardian、supervisor、CDC worker JVM，双隔离 MySQL 9.5.0、真实 Debezium、Actor Journal 与 Sink；单表进入实时后终止旧 worker，新 worker 接管后应用新的源更新，源与目标 64 行、384 字段一致，T 为一组增量、J 21 条、L 27 条；`cleanup.json` 记录隔离排空确认、会话消失和清理成功。实验完整 T/J/L/D、进程证明、对账和资源观察均在 C0 子目录中；最终两实例事务数见状态文件，实例随后关闭。

实验不覆盖初始化期间、COMMIT 结果未知、未知 B 自动恢复或 70 万表容量；未知 B 继续安全暂停。
