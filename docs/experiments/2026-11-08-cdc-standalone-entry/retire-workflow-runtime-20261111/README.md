# 独立 CDC Runtime 移除旧执行器

发布核对：生产代码提交 `f6313c0`，网站提交 `1b66566`。GitHub Jekyll 检查与 Pages 部署均成功；五篇正文及系列页直连均返回 200，分别核对版本/证据段和新验证表。见 `publication-verification.json`。本机 Jekyll 首次缺少匹配的 Ruby/Bundler，切换已安装 Ruby 后又遇到代理 TLS 与未认证 GitHub metadata 请求限额；本机构建未完成，已终止该次本机任务，实际构建结论来自远端成功任务。原错误日志保留。

最终版本检查发现，提交前仅移除了两个生产文件的末尾空行，导致先前生成的清单与发布字节不同。原清单保留为 `precommit-production-manifest.json`，`final-production-manifest.json` 重新按已提交的生产文件计算；各实验的原始 source-sha256 清单保持不变。

## 生产代码与兼容边界

生产 `workflow` 目录已移除。旧 Actor、Engine、注册表、执行计划、可靠会话、旧表调度 Runtime 和旧 Workflow receipt 投影移入测试源集，以保留历史尝试；不再随生产 JAR 分发。原来的旧 receipt 投影从 MySQLCaptureStore 移入测试类 LegacyWorkflowProjection，继续调用 Store 的同事务 proofFrame 校验；该测试类不能被解释为当前生产 API。

生产保留 BootstrapJournal 的进度及恢复凭据验证。历史 Actor 快照 payload 拆到 `cdc.compat.LegacyWorkflowState`，旧 manifest 经显式迁移解码；锁、表结构、persistenceId 及历史实验材料未删除。配置键或兼容模型中的 workflow 字样不等于仍有通用执行服务。

## 本轮实际检查

| 检查 | 结果及材料 |
|---|---|
| 干净编译 main/test | `clean-compile.log` 通过，排除旧 class 文件干扰 |
| 生产 JAR | `production-package.json` 不含旧 Actor、旧执行器或旧 receipt 适配器；包含历史状态模型 |
| 历史序列化样本 | `compat-pass.log` 读取 18 个冻结样本，比较迁移后的明确类名并保持原字节内容 |
| 历史恢复与控制状态 | `compat-first-failure.log` 中其余 42 项通过；样本测试首次失败是断言仍要求已迁移的 checkpoint 旧类名，补正明确映射后通过 |
| C0 真实 Source/Journal/Sink | `mysql-run.log` 的 C0 通过；子 JVM 类路径排除 test-classes，guardian、supervisor 和 worker 不可加载旧执行器 |
| C0 逐字段对账 | `C0-42bbc60a4131/02-recovered.json`：64 行、384 字段相等，缺行、额外行及字段差异均为空；含 T/J/L/D 和隔离身份 |
| C0 资源释放 | 同目录 `cleanup.json`：会话释放、guardian 排空和进程退出均确认 |
| receipt 与 Journal | `mysql-journal-rerun.log` 的 3 项通过；旧 Journal 字节、旧实验投影防伪/锁定检查及生产 Bootstrap 跨 JVM 重签分别验收 |
| 首次 Journal 失败 | `mysql-run.log` 中第一项旧字节读取缺少 JDBC 驱动注册；在测试设施显式注册后重跑，未更改生产协议 |
| 默认测试 | `default-tests.log`：710 项中 709 通过、1 失败、2 取消；失败的历史全量故障门只拦截旧回调而未选择该路径 |
| 故障门修正后 | `legacy-gate-rerun.log`：显式选用测试要拦截的 checkpointCommitted 路径，原 T/J、第二节点重放、目标账本和资源断言保留；该项通过。没有重跑全部 710 项，也不把首次失败改写为全绿 |

## 未验证与未实现

未知 B 继续安全暂停。相邻控制包迁移的 C1 自动恢复期待失败及 PAUSED/UNKNOWN 证据见[原记录](../control-package-decouple-20261111/README.md)，没有借代码迁出清除失败。

receipt 对象身份属于进程内能力：恢复后的 Bootstrap Actor 从真实 Journal 重新签发，并在投影事务中核对最新 Journal。对象字段复制不构成签发；同一 JVM 的旧引用在 Journal 未变时仍可能通过。反射、Unsafe、恶意字节码和特权数据库写入不在此防伪验证内。

生产 L 没有实际安全删除路径；归档、ACK 证明和保留位置投影不能替代完整 GC 验收。本轮不覆盖 guardian 自身重启、所有故障窗口、DDL、完整历史数据库整体升级、70 万真实活跃表容量或与 Flink 的同负载性能比较。历史旧 Actor 重放发生在保留的测试设施中；生产侧历史样本解码和新 Runtime 接管是分别证明的事项。
