# 未知 B 的 grant 与许可事务：组件实验

状态：**组件通过，端到端证据不足**。独立 source/state MySQL 9.5.0 中运行 `CdcGrantRecoveryMySqlSpec`，两次使用新的独立 schema。实验构造合成 D/L 锚点，guardian 身份、数据库排空状态和签名证明均为测试构造；未启动真实 guardian、CDC Reader、Actor Journal 或 Sink，不能据此声称未知 B 自动恢复。

组件验证了以下行为：错误 owner 的证明被拒绝且 UNKNOWN grant 与 owner 保留；通过 JDBC 测试代理在真正的 MySQL COMMIT 完成后抛出异常，调用方得到 `commit_outcome_unknown`，读回确认 grant 已 RELEASED、owner 已 STOPPED 和许可已持久化，重复同一请求返回同一许可；后继 owner 在领取 grant 前重启，旧许可被失效并产生新的许可；新 owner 获得并消费许可后，旧许可拒绝复用，消费重复请求保持幂等。

第二次实验将两个许可的 `created_at` 都固定为 7，仍选择绑定当前 owner 且未消费的许可，而不是按时间盲选；见[同时间戳结果](tie-result.json)、[执行日志](tie-test.log)、[测试时源码摘要](tie-source-sha256.txt)。首次通过的样本见[结果](result.json)、[最终数据库行](final-state.tsv)和[日志](test.log)；`post-test-source-sha256.txt` 是该样本完成后才采集的摘要，不能当作首次运行时的源码指纹。一次重跑因并发编辑导致 Kernel 编译失败，未进入数据库测试，保留[失败日志](tie-test-compile-failure.log)。

第三次实验将三份许可的 `created_at` 固定为 7，并验证后继 owner 已绑定许可、尚未领取 grant 时，即使调用确认释放的正常停止，owner 仍转为 UNKNOWN；使用新的测试隔离证明后产生第三份许可，旧许可不能复用。见[结果](clean-stop-result.json)、[测试日志](clean-stop-test.log)和[源码摘要](clean-stop-source-sha256.txt)。结果仍是合成隔离证明下的数据库组件验证，并非真实进程隔离。

“提交后回复丢失”是 JDBC 代理在底层 `commit()` 返回后人为抛出异常，保留了真实服务器提交，但不是网络代理、独立旧 JVM 或真实 guardian 的故障实测。不能将本实验扩大为迟提交、真实隔离或逐字段对账通过。
