# L 安全 GC 实施中的组件证据

当前为未提交候选，基线 `529abb4`。本目录不是 LG01—LG16 的完成报告。

## 当前实现

- `CaptureGcRetention`：持久化保留需求及 epoch；新建或加强保护允许，未经恢复证明禁止推进保护位置；按实际 SDK D 的行锁读取、分区解码及 ACK 链校验取证，识别未知 B 和清点未完成。没有删除或撤销保护的方法。
- `BootstrapProgress.gcCheckpoint`：可选字段，缺失时读为 None。普通 Journal Write 禁止修改该字段；专用封存命令绑定 STREAMING、revision、definition、execution、deltaAfter 和 activation 身份。
- Runner 的 GC 审计请求走正常调度步骤，每步使用原 StepAudit 的审计单位；仅审计完成且 Journal 状态未变时发起封存。当前没有把 GC 请求接入生产 API 或自动调度。
- Journal 到状态库的 `rememberCheckpoint` 保存 PENDING_TARGET 候选，核验真实签发 receipt、同事务精确 Journal 和订阅 generation；不推进 pin。该方法目前完成编译，尚无真实目标绑定和激活实验。

## 实际验证范围

| 证据 | 结果 | 限制 |
|---|---|---|
| `retention-component.log`、`retention-checkpoint-compile.log` | 专用 MySQL 中慢表 pin 重连保留、重复登记不增长 epoch、无证明推进被拒绝；实际 D 的 ACK=2，最新 ACK=3，采用 2；未知 B 阻断 | SDK offset 与 ACK 由测试夹具写入；无真实 Reader 或 Sink，不计入 LG01—LG16 |
| `journal-seal-component.log`、`journal-checkpoint-restart.log` | 真实 MySQL JDBC Actor Journal 中普通写入修改 gcCheckpoint 被拒绝、过期 revision 拒绝、专用封存接受；Actor 重建后读回证明 | activation 为测试值，未经过真实目标审计，不证明 GC 证明已经生效 |
| `gc-checkpoint-tests.log` | 24 项本地相关检查通过 | 不包含默认排除的外部矩阵 |
| `progress-compatibility.log` | 9 项通过；旧 JSON、保留的旧 CBOR、continuation 边界 | 不证明完整历史数据库升级 |
| `initial-gc-compile-tests.log` | 首次编译失败保留：BootstrapProgress 新增字段后 JSON formatter 仍为 jsonFormat21 | 改为 jsonFormat22 后重编译通过 |

LG01—LG16 均尚未作为完整 MySQL Source / Actor Journal / Sink 故障场景执行；没有本轮逐字段对账结果。原 L、ACK、归档和目标账本均未被回收。

## 继续实施的必要链路

1. 目标账本/activation 证据的生产身份及不可变性验证，候选到 ACTIVE 的事务转换。
2. 将有效恢复证明接入恢复审计及 ACK 前缀读取，禁止恢复到已回收位置之前。
3. 完整保留需求与动态订阅准入绑定，归档引用和实际 D 的保护。
4. 固定范围、小批次删除与预算的同事务提交；提交结果未知后的持久状态重读。
5. 真实 Source、Journal、Sink 的 LG01—LG16 故障矩阵、逐字段对账及资源释放检查。

未知 B 保持安全暂停；当前 GC 判定明确要求恢复证明，不会因上述组件通过而允许删 L。代码和材料未提交推送。
