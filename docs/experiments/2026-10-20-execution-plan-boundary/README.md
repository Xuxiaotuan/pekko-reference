# Workflow DSL 到运行计划的边界

日期：2026-10-20，Asia/Shanghai。基础提交 `d046cce2dc850ea60bc3513db84378484b0a1043`；本轮为未提交工作区改动，实际运行代码由 `source-manifest.json` 定位。

## 1. 目标与实际改动

本轮落实架构讨论的第一步：把旧画布定义转换为仅供运行使用的内存计划，保留 CDC、可检查点辅助任务、普通行流三种路径。没有同时实施 C2 恢复策略、查询投影、Reader 配额或共享读取。

- `WorkflowPlanCompiler` 使用与 Engine 相同的不可变实例级 NodeRegistry，复用 WorkflowValidator 的线性拓扑、CDC 直连、禁用 Transform 等规则；在同一目录中绑定实际 Source/Sink 适配器。
- `ExecutionTask` 保存任务 id/name；`ExecutionNode` 保存节点身份、角色、类型、标签和配置；`ExecutionPipeline` 保存已排序的源、转换、目标。位置、样式、边动画、作者和执行历史不进入计划。
- `MySqlCdcPlan` 使用 CDC 的直连批次路径；`CheckpointedLinearPlan` 保留辅助任务的批次内 Transform；`RowStreamPlan` 使用普通行流。三类共享原有执行/结果及取消设施，没有复制一套提交协议。
- Engine 接收旧 Workflow 的两个 execute 重载仍保留，入口先编译，内部运行方法使用运行描述。Actor 可恢复判断委托同一编译规则，移除自身重复的 Source/Sink 接口类型判断。
- 连接器仍接受原 Node 接口。调用时通过 `ExecutionNode.connectorNode` 生成零坐标、无样式的兼容参数；配置不丢字段，节点 id/type/label 保留。环境密码仍在实际执行时解析一次，不在编译阶段查找。

这里的编译是定义结构与执行能力绑定，不是数据库连接检查或全部连接器配置的强类型解析。原配置解析、SQL 类型校验仍在连接器中。计划的 `resumable` 沿用已有 Checkpoint 能力组合，不能解释为新增的任意故障恢复承诺。

## 2. 保持的兼容边界

HTTP DTO、Workflow DSL、Actor 命令与事件字段、状态模型、persistenceId 和序列化包名没有改变。计划不实现 CborSerializable、不作为事件或快照字段持久化。

运行顺序仍是 Sink 的目标事务/账本 T 提交 → Actor Checkpoint J 确认 → Source ACK；保持 mapAsync(1)、完整组与原始回调前缀确认、取消排空和实际释放观测。

注册实例发生替换时按实际能力选择，不凭 mysql 名称推断可靠性。普通 Source 搭配支持 Checkpoint 的 Sink 保持原普通行流；携带 ReliableRunContext 的恢复若丢失 Source/Sink 能力，继续拒绝执行，不降级为行流。能力失败的节点结果仍按原定义顺序报告。

Actor 中已有 Kafka 跨运行进度规则仍保留；此次没有用目录重排掩盖该耦合。生产源码中仅修改 Engine 和 Actor，另增计划模型及编译器；80 份基线 Scala 源码未变，包括 Source、Bridge、组装配器、Sink、Checkpoint 和持久化事件模型。110 份既有实验材料逐文件哈希保持一致。

## 3. 本轮本地验证

- 改造前 Engine/Actor 恢复基线：47 项，2 个套件，全部通过。
- 新计划 API 未实现时运行契约测试，记录缺失类型的编译失败；实现后 8 项计划契约通过。
- 另外在旧 Engine 上保留仅供新 Actor 编译的 `isResumable` 兼容方法，原执行逻辑不变；两个连接器调用测试均复现坐标 1234/5678 被传入，断言零坐标失败。恢复新 Engine 后两项通过。它们验证真实调用参数，不只是测试一个 getter。
- 最终聚焦：56 项，3 个套件，全部通过。包括画布隔离、配置及 Transform 输出保留、密码注入/脱敏、能力替换/丢失、T/J/ACK 次序和 Actor 恢复。
- 全后端默认门：471 项，77 个套件，零失败、零中止；ExternalIntegration 按原配置排除，真实运行另列。
- `Compile/packageBin` 与 `Universal/stage` 通过；发布目录 193 个 JAR。生产 JAR 中存在计划/编译器类，不含新测试类或 TestNodeRegistry。没有部署发布目录。
- 前端和接口契约未改；本轮未重新执行浏览器联调或前端构建。

首次 Engine 接入检查曾因机械替换错误出现三处编译错误：两个完整包名和一个遗留变量引用。已按错误位置修正后完成上述回归，原始日志保留，不将这些错误视为生产恢复实验。

独立只读源码审查没有报告可操作问题。审查者未独立运行测试或数据库，其审查意见仅补充上述实际验证。

## 4. 真实 MySQL 回归

正式运行使用隔离容器 MySQL 8.4.11、ROW/FULL、GTID ON，Java 17.0.14、Pekko 1.1.3、Debezium 3.6.1.Final、Protobuf 4.33.4。每项创建随机测试 schema；Source、Sink、组账本、业务审计和 Actor JDBC Journal 均使用真实 MySQL。

逐字段比较 id、label、amount、note、updated_at、revision，并额外核对 T/J/D、回放组、审计副作用及原始 ACK 前缀。具体本轮结果在下表中列出。

| 窗口 | 缺行 / 多行 / 字段差异 | 自动恢复 |
|---|---|---|
| N0 | 0 / 0 / 0 | PASS |
| J1 | 0 / 0 / 0 | PASS |
| J2 | 0 / 0 / 0 | PASS |
| C1 | 0 / 0 / 0 | PASS |
| C2 | 2 / 2 / 212 | FAIL_TERMINAL |
| C0 | 0 / 0 / 0 | PASS |
| K1 | 0 / 0 / 0 | PASS |
| K2 | 0 / 0 / 0 | PASS |

C2 本轮仍为 T=17 组、J.sequence=0、审计 82 条；Actor 已持久化失败终态，恢复未启动 Reader。没有清理 Journal、重置 D、更换 generation 或手工补齐目标。

两个取消场景 none/cancel-T 均为 64 行、六字段全量一致，实际关闭后报告 Released，命名 Debezium runner 数为 0。cancel-T 仍先观察到 TimedOut，待 T/J/ACK 排空和实际关闭后才确认释放。测试通过的是这些准确的观察断言，不是十场景自动恢复全部成功。

## 5. 材料与复核

- [源码清单](source-manifest.json)：基础提交、正式运行的 212 份源码/资源/构建哈希及改动范围。
- [运行清单](run-manifest.json)：十项新随机 schema、实际环境和运行方式。
- [验证汇总](validation.json)、[恢复核验](result.json)、[释放核验](session-result.json)。
- [原始归档](evidence.tar.gz)：394 个成员，含原始 SourceRecord/ACK 日志、切点与最终 T/J/D、全量行、审计、数据库 SQL 导出、运行源码快照、改造前 Engine/Actor、测试日志和逐文件 SHA256。

在本目录执行：

```sh
shasum -a 256 -c SHA256SUMS
python3 -B verify.py
python3 -B verify_sessions.py
```

校验器复用既有八窗口及释放材料校验规则，重算字段差异、组身份与账本、源序审计和原始 ACK 前缀。它们通过表示本轮材料内部一致，不表示 C2 已修复。归档不包含数据库实际密码；临时环境凭据不进入仓库。隔离容器完成导出后停止，数据卷保留。

本地后端命令：

```sh
sbt -java-home /Users/xujiawei/Library/Java/JavaVirtualMachines/ms-17.0.14/Contents/Home -J-Xmx3G \
  'pekko-server/test' 'pekko-server/Compile/packageBin' 'pekko-server/Universal/stage'
```

真实运行沿用[已有隔离实验设施](../2026-10-19-stage-c-sessions/README.md#7-如何复核)，显式启用 ExternalIntegration，执行 RealMySQLCdcSessionReleaseSpec 和 RealMySQLCdcRecoveryWindowsSpec。默认后端测试不包含这两组真实实验。原始日志中错误窗口的预期异常不能等同于测试失败。

## 6. 结论范围与下一步

本轮源码结论：执行内核不再直接传递原始画布节点；计划绑定当前目录适配器，Actor 与 Engine 共用可恢复分类规则，CDC 与辅助任务具有显式计划类型。保留原连接器 Node 兼容边界，没有宣称全部连接器已类型化或所有耦合均已消除。

本轮实验结论：本地功能、Actor 恢复、生产打包及上述十个固定真实窗口保留了既有行为。正式真实进程均使用本轮 Test Classpath，未部署打包目录；同一新构建下的重启不等于旧二进制到新二进制的生产滚动升级验证。

未验证或未实施：C2 自动恢复修复、DDL、多 owner/跨进程 fencing、共享 Reader、Reader/Writer 资源配额、独立目录投影、增量快照初始化衔接、长期压力、吞吐和 70 万表容量。Actor 中 Kafka 的跨运行进度特例仍在。接下来应单独设计提交结果不确定的恢复决策，继续沿同一复制身份和目标账本核实覆盖，而不是增加无条件重试。

本轮未提交、推送、部署或更新网站；前一轮历史实验与文章原样保留。
