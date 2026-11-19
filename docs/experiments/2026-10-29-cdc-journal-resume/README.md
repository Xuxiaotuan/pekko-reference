# 未知 B 的 Journal 恢复转换：组件验证

状态：**evidence_incomplete**。增加一个异步验证许可后才可尝试的 Journal 转换：只允许 revision、execution、definition 精确匹配，且未规划/未写窗口、未取消、失败原因为资源释放未知的 PAUSED 状态回到 QUEUED；验证结束时重新检查 revision 和状态。默认许可验证器拒绝，因此正常运行路径尚未启用该转换。Journal 仍使用原有 `Advanced` 事件和 `BootstrapProgress` 类型。

隔离 MySQL 9.5.0 上的真实 Pekko JDBC Journal 测试写入 PAUSED，停止并重新创建 Actor 后读回 revision 1；注入的无效许可被拒绝，注入的有效测试许可使 revision 2 变成 QUEUED；再次重建 Actor 后读回同一状态，Journal 中序号为 1、2。见 [结果](result.json) 和 [执行日志](mysql-test.log)。许可验证器是测试注入，未连接真实 guardian、Reader、UNKNOWN grant 或 Sink，不能证明未知 B 自动恢复。

[初始编译失败](initial-red.log)和[本地快照插件配置错误导致的失败](local-snapshot-config-failure.log)已保留；修正测试配置后的[本地测试](local-tests.log)通过。源码摘要见 [source-sha256.txt](source-sha256.txt)。
