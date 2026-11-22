# 受管 source fencing 探针：失败记录

目前没有通过的端到端结果。这里保留原始运行和环境诊断，不能据此声称未知 B 已自动恢复。

- `attempt1.log`：命令使用了不存在的 `server` 项目 ID，测试未执行。
- `attempt2.log`：筛选未匹配，测试数为 0。
- `attempt3.log` 和 `FENCE_PROBE-b2650fdfe082/`：真实 MySQL 和独立 guardian 执行了一个用例。旧 worker 隔离后，状态库两个未归属的 `cdc_lab` 空闲连接使 owner 保持 `drain_wait`，测试超时，未进入 source fence 提交窗口。`cleanup.json` 记录 guardian drain 未确认；随后单独检查两库事务与其他连接均为 0，见 `handoff-after-attempt3.txt`。
- `attempt4.log` 和 `FENCE_PROBE-e8ecd945bab6/`：改为独立 worker-control 账号后，一个用例在旧 worker 启动阶段报 `STORAGE_UNAVAILABLE`，未进入故障切点。只读诊断保存在 `attempt4-worker-readonly.txt`；源码要求 state observer 具有全局 `PROCESS`，该次账号缺少此权限，这是待下一次实验验证的原因假设。该次 cleanup 确认成功。
- `attempt5.log` 和 `FENCE_PROBE-55d28f477cbd/`：补充 `PROCESS` 后，真实 guardian 已确认旧 worker 排空并启动替代 worker；测试夹具反射错误选中匿名方法，未调用预期源码，测试失败，cleanup 确认成功。
- `attempt6.log` 和 `FENCE_PROBE-26db81e5d28a/`：固定反射后，真实 guardian 隔离旧 worker、controller 持久 FENCED 并对实际 D/控制事件完成重扫，结果见 `*-fence-probe.json`。预期的 fence COMMIT 回复丢失切点没有被代理捕获，故该用例仍失败；不能将局部重扫观察视为 COMMIT_UNKNOWN 验证。cleanup 确认成功。运行前后的源码清单分别为 `attempt6-source-before.sha256` 和 `attempt6-source-after.sha256`；运行期间源码曾继续修改，须以测试进程在编译时使用的版本限定结论。
- `attempt7.log` 和 `FENCE_PROBE-3113b209f16a/`：一个受管测试探针通过，guardian 隔离并排空旧 worker 后，代理在同一 source 连接观察到 JDBC 命令字 3 的 fence UPDATE，再转发 COMMIT、收到服务端 OK 后丢弃回复；替代 worker 以完整 binding 读回并完成 D/ACK 与控制表重扫。`result.json` 的范围标为 `FENCED_RESCAN_ONLY_PASS`，cleanup 确认成功。该探针通过反射调用候选方法，没有验证生产入口、Sink 或逐字段对账；执行前后源码清单和样本内源码摘要均保留。

上述结果没有验证 source 提交结果未知后的恢复、Actor Journal 到 Sink 的数据流或逐字段对账。后续实验须保留本目录和源摘要，分别记录真实观察与推断。

`journal-suite-attempt1.log` 另记录一个独立的组件套件，共 15 项通过。真实 MySQL `REPEATABLE READ` 下，Actor Journal 的 pending 事件及后继 sequence 范围被锁住；同 JVM 连接和独立 JVM 的下一 sequence 插入均以 1205 超时。同次隔离测试中，按 SDK offset 表结构创建的测试表在执行实际使用的 offset 范围锁查询后，独立 JVM 插入新的非空 offset 也以 1205 超时。原始证据在 `journal-lock-attempt1/`。其他测试覆盖历史 22 条 CBOR 反序列化、旧 JSON 及 Journal receipt 的内存 actor 重启和取消。

该套件只覆盖当时编译的代码、指定 MySQL 版本及测试表形态；本次没有保存完整源码哈希清单，不能把其归因于此后修改的最终源码，也不能把锁超时扩大为 guardian、Reader、Sink 的端到端正确性。
