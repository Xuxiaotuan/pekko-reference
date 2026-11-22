# 恢复许可与源端 fence：进行中证据

状态：`evidence_incomplete`。本记录只描述本轮组件试验，不能证明真实 MySQL、guardian、Journal 与 Sink 的完整恢复。

## 许可证据丢失反例

2026-11-07，H2 内存状态库，Java 17，`CdcRecoveryFenceStorageSpec`。创建版本化许可并模拟新 owner 认领后，删除该许可对应的 `pekko_cdc_b_fence_v2` 记录；修复前调用 `authorizedBPermit` 未抛异常，测试失败：`missing sidecar must not silently become a legacy permit`。这说明仅凭 sidecar 是否存在区分版本会误判。

修复后，在许可主记录持久化 `proof_version`，不可识别版本和 v2 证据缺失不按 v1 处理；相关四个组件测试套件共 14 项通过。此轮结果不包含新加的源端意图逻辑，也不证明旧表在线迁移或 MySQL 行为。

## 源端意图缺失反例

2026-11-07，同一 H2 测试增加源端提交前持久化意图的要求；实现前测试失败：`source fence intent must exist before source commit None was empty`。加入持久化记录及精确重试检查后，四个相关套件共 15 项通过，包含 H2 旧表加列和重复初始化。测试中的 guardian 与源端绑定是合成数据；真实 MySQL 意图提交及进程重启故障尚未验证。

一次误用 Java 11 的构建在 Scala 编译阶段报 `bad option: '-release:17'`；切换 Java 17 后运行上述测试。该失败不是业务协议测试结果。

## 控制表分页状态反例

2026-11-07，H2 组件测试要求逐页游标能够持久化、精确重试并拒绝过期游标。实现前失败：`bounded source scan needs durable compare-and-set progress None was empty`。加入游标 CAS 后，`CdcRecoveryFenceStorageSpec` 与 `CdcTaskStoreSpec` 共 10 项通过，覆盖相同页重试、过期游标拒绝与完成后拒绝推进。该结果仅是状态库组件测试，不证明真实 MySQL、跨进程重启或端到端恢复。

## 关闭 owner 时遗留的版本化许可

2026-11-08，H2 组件测试固定无活动 grant、已消费的 v2 许可仍由当前 owner 认领的状态。修复前 `endOwner` 返回 `true` 并将状态写为 `STOPPED`，与仍有 owner 绑定证据不符；红测见 [end-owner-claimed-v2-red.log](end-owner-claimed-v2-red.log)。修复后该状态保留 `UNKNOWN`，版本字段降为 v1 但仍有 v2 sidecar 时同样暂停；只有已消费的原生 v1 许可仍可正常停止。三个状态及兼容性套件 16/16 通过，见 [end-owner-claimed-v2-green.log](end-owner-claimed-v2-green.log)。该实验没有真实 MySQL、guardian、Journal 或 Sink，不能证明自动接管已完成。

同日，在独占本机 MySQL 25822、核对服务端 UUID 后，使用三个新建隔离 schema 运行同一状态检查：v2 与损坏降级分别为 `UNKNOWN`，原生 v1 为 `STOPPED`；真实 MySQL 状态测试 1/1 通过，记录见 [end-owner-mysql-attempt1.log](end-owner-mysql-attempt1.log)。这只是状态库实验，未运行 guardian、Actor Journal 或 Sink，也不是故障恢复及逐字段对账的证明。
