# 目录与实现包审查

基线 `bf249b1823040abc056b65c0c16e462d914c1d73`，从干净 feature 分支实施。用户明确授权执行、验证、提交和推送；没有部署或发布镜像。

## 范围与实现

统一根目录 server/console/scripts/deploy 和文档分类，后端归入 bootstrap/api/workflow/cdc/connectors/infrastructure。保留原 PekkoServer main、SBT 项目 ID/六个别名、根 quick-test/Compose 入口及两份历史 probe main。三个旧路径仅以软链接兼容，编辑器隐藏这些链接和生成目录；没有重复源码，也没有清理原始实验输出、.tasks、.kiro 或用户配置。

原持久化 Actor/Supervisor 声明、WorkflowEvents、WorkflowDSL、Checkpoint、ExecutionFailure/ExecutionModels、Scheduler 模型和 CborSerializable 标记保持原 package；没有用类型别名冒充 JVM/Journal 类名兼容。执行实现位于 workflow/execution，engine 只保留原值类型。恢复协议、完整组身份、账本、T/J/D 顺序和 C2 预算未改变。

## 独立只读审查

最终 reviewer 检查全部 340 条文件移动映射、源码差异、配置、命令入口和历史保全，未发现可操作回归或阻断项。审查独立确认：

- 无目标缺失或未映射的基线文件删除；166 个既有实验文件路径和字节不变。
- 生产源码变化限于包名、导入和对应包可见性；未移动直接声明 CborSerializable 的生产 owner。
- 原 main、SBT aliases、旧 probe 转发器存在，代码资源、前端代码与部署 YAML 保留基线内容；脚本/Compose 挂载路径与新目录对应。
- 目录 guard、shell 语法和 diff whitespace 检查通过。

Reviewer 没有执行后端/UI/MySQL 测试，测试结论另以本任务真实执行记录为准。审查后增加三条兼容路径软链接，并用实际目录 guard 验证其目标；没有再次变更运行源码。

## 实际验证与探索错误

- 迁移前捕获 18 个旧 CBOR 样本，包括 workflow/scheduler 状态、事件、Checkpoint 和恢复故障；基线读回测试 1/1。迁移后读取原 manifest，重新序列化仍逐字节一致；完整后端再次执行该用例。
- 清空的是 server 的编译输出，随后完整编译 main/Test；根 target 下的历史实验未清理。生产 JAR 检查确认没有旧包 WorkflowExecutionEngine.class 或测试设施，保留旧持久化类型和原 main。
- 完整后端 504/504，83 套件；package、Universal/stage、Docker/stage 成功。测试 JVM 清理等待设为 60s，仓库默认不变；没有把这个结果写成默认等待下旧百万 Probe 示例也已重新验收。
- 原 quick-test.sh 实际运行 68/68、六套件，并输出原六个 SBT aliases 和原 mainClass。不是只看包装脚本存在。
- console 在独立空目录通过 frozen/offline 安装，五项契约测试、TS/Vite build 和 lint 均通过；没有前端库升级。没有浏览器全流程回归。
- Compose include 配置与基线展开结果比对，项目、服务、命名卷一致，唯一变化为绑定路径 server；未启动 Compose 服务。两套 Kubernetes 模板本地 kustomize 渲染通过，未访问集群或应用资源。
- 当前/迁移文档 Markdown 链接检查排除 fenced code 和不可变实验材料，未发现缺失目标。历史材料里的旧源码路径按其记录版本解释。
- 前期样本测试漏引入 JsonFormat、protected/private 包作用域未重命名、两个含特殊 package block 的测试 import 遗漏，均由编译器定位并修正；完整日志保留 exploratory。它们是目录迁移设施/编译问题，不是恢复协议的新故障。

真实 MySQL 当前回归结果、独立 ACK/审计/逐字段检查以 result.json 和 run-manifest 为准；局部测试、离线材料一致性和端到端实验分开报告。

## 保留的边界

本轮不增加生产 HA、强制 Sharding handoff、分区/SBR、多 owner fencing、DDL、共享 Reader、新快照或 70 万表容量证明；不承诺旧二进制任意滚动混跑或降级。普通内部实现的旧 import 不作为源码/二进制兼容 API。原 C2 预算耗尽后的人工恢复入口仍未增加。

旧实验保留原始源码位置、FQN、结果和失败，不能因目录整理而改写历史证据。Compose include 需要 2.20+，本轮实际工具版本见 compose-check。UI 的 985.10kB chunk 警告、工具链弃用提示、Scala 编译 warning 不在此次结构整理中扩展处理。
