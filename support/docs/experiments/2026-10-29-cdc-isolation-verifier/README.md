# 受管隔离证明校验：组件验证

状态：**evidence_incomplete**。新增的校验器要求 guardian 绑定、公钥签名、worker boot/PID/启动时间、操作 ID、已退役票据、db_drained 状态、封存清单版本和每个清单账户都相符。它目前没有接入生产恢复许可，不能释放 CDC owner 或 grant。

本地测试验证篡改身份、账户缺失、未排空和无效签名被拒绝；另一次测试读取[真实 guardian 静态快照回归](../2026-10-29-cdc-guardian-baseline/final.json)产生的已签名进程隔离证明及数据库排空记录，并从其账户清单构造测试输入，校验通过。该证据来自静态快照，不是 CDC owner 的受管启动或未知 B 恢复。见[真实证明校验日志](real-proof-test.log)、[本地测试](unit-tests.log)、[首次编译失败](initial-red.log)和[源码摘要](source-sha256.txt)。
