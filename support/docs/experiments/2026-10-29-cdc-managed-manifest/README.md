# CDC 受管资源清单：组件验证

状态：**evidence_incomplete**。受管运行取得 owner 后，在启动 Reader 前登记各源库账号、SDK 状态账号、控制与 Journal 账号，检查实例 UUID 和登记状态，并将清单与 owner attempt 关联持久化。清单准备失败时 Reader 不启动；没有清单不得把后续数据库空闲视为完整释放证明。此轮仍未实现许可消费、grant 回收或 Journal 恢复。

本地 4 个相关 suite 共 24 项通过；失败前清单不可得时 Reader 未启动。单独两台真实 MySQL 9.5.0 上，源码实际 `CdcBootstrapKernel.prepareManagedManifest` 返回源账号 `cdc_src`、状态 SDK 账号 `cdc_sdk`、控制/Journal 账号 `cdc_lab` 及两个服务器 UUID；这是直接调用组件，没有启动 CDC Reader、真实 guardian、持久化 Journal 或 Sink。[结构化结果](result.json)、[MySQL 日志](mysql-test.log)和[源码清单](source-sha256.txt)提供证据。

使用真实 MySQL 的另一组件测试验证 attempt 和测试清单持久化并读回，guardian 身份为模拟值，见[数据库状态](manifest-state.tsv)和[执行日志](manifest-persistence.log)。[第一次](mysql-compile-1.log)和[第二次](mysql-compile-2.log)编译错误、[初始单测失败](unit-red.log)及[相关测试](unit-tests.log)均保留；编译错误不属于实际数据库故障。

未验证：SDK 的实际连接均被排空器识别、真实 guardian 的隔离证明与清单/排空记录绑定、跨重启许可、UNKNOWN grant 和 Journal 状态转换、B 提交结果判定，以及真实 Sink 的逐字段对账。不得将本结果写成端到端恢复已完成。
